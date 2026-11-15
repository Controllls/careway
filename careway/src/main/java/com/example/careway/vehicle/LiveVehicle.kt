@file:Suppress("DEPRECATION")

package com.example.careway.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.drivingstate.CarUxRestrictionsManager
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.careway.core.*
import com.example.careway.vehicle.SdkCalls.call as sdkCall

/** Typed SDK integration. Simulation is a different gateway and never feeds this adapter. */
class LiveVehicle(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context)
    val gateway = PleosGateway(vehicle)
    private val navi = NaviHelper(context)
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private val readSupport = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val registered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val rainMutex = java.util.concurrent.locks.ReentrantLock()
    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val mutable = StateCell(VehicleSnapshot())
    val state = mutable
    @Volatile var onBrake: ((VehicleSnapshot) -> Unit)? = null
    private val problemState = StateCell<Map<String, String>>(emptyMap())
    val problems = problemState
    private val workers = java.util.concurrent.Executors.newScheduledThreadPool(9)
    private fun every(block: () -> Unit) { workers.scheduleWithFixedDelay({ if (!closed) runCatching(block).onFailure { problem("연결", it) } }, 0, 1000, java.util.concurrent.TimeUnit.MILLISECONDS) }
    @Volatile private var closed = false
    @Volatile private var naviReady = false
    private fun now() = SystemClock.elapsedRealtime()
    private fun problem(key: String, e: Throwable) {
        problemState.update { it + (key to (e.message?.take(120) ?: e.javaClass.simpleName)) }
    }
    private fun good(key: String) { problemState.update { it - key } }
    private fun mutate(block: (VehicleSnapshot) -> VehicleSnapshot) { if (!closed) mutable.update(block) }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            acceptBrake(status)
        }
        override fun onFailed(e: Exception) { registered.remove("브레이크"); acceptBrake(null); problem("브레이크", e) }
    }
    private val lightListener = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) { updateLight(state) }
        override fun onFailed(e: Exception) { registered.remove("하향등"); mutate { it.copy(lowBeam = null) }; problem("하향등", e) }
    }
    private val wiperListener: WiperStateListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) {
            // State only reports ON/OFF; continuous levels are WiperSwitch, read separately.
            if (!closed) runCatching { workers.execute { if (!closed) readRain() } }
        }
        override fun onFailed(e: Exception) { registered.remove("와이퍼"); mutate { it.copy(rain = null, wiperLevel = null) }; problem("와이퍼", e) }
    }
    private val navListener = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val nearest = tbtInfoList.filter { it.type == TurnType.TURN_TUNNEL && it.distance.isFinite() && it.distance >= 0 }
                .minByOrNull { it.distance }
            val tunnel = nearest?.let { Tunnel(TunnelPhase.APPROACH, it.distance) } ?: Tunnel(TunnelPhase.CLEAR)
            mutate { it.copy(tunnel = Reading(tunnel, now())) }
            good("내비게이션")
        }
        override fun onRouteCancelled() { mutate { it.copy(tunnel = null) } }
        override fun onReRouteEnded() { mutate { it.copy(tunnel = null) }; runCatching { navi.getTBTInfo() } }
        override fun onError(errorCode: NaviErrorCode) {
            mutate { it.copy(tunnel = null) }; problem("내비게이션", IllegalStateException(errorCode.name))
        }
    }
    private val carListener = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) { acceptProperty(value) }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            invalidate(propertyId); problem("차량 상태", IllegalStateException("센서 값 오류: $propertyId"))
        }
    }

    fun start(context: Context) {
        closed = false
        runCatching {
            vehicle.initialize()
        }.onFailure { problem("Pleos 연결", it) }
        runCatching {
            navi.initialize(onConnected = { if (!closed) {
                naviReady = true
                runCatching { navi.addListener(navListener); navi.getTBTInfo() }
                    .onFailure { problem("내비게이션", it) }
            } },
                onFailed = { naviReady = false; problem("내비게이션", it) })
        }.onFailure { problem("내비게이션", it) }
        runCatching {
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (closed) return@createCar
                if (ready) {
                    properties = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                    runCatching {
                        ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                        ux?.registerListener { restrictions ->
                            mutate { it.copy(uxRestricted = Reading(restrictions.isRequiresDistractionOptimization, now())) }
                        }
                    }.onFailure { problem("주행 조작 제한", it) }
                    ids.forEach { id -> runCatching { properties?.registerCallback(carListener, id, 10f) }
                        .onFailure { problem("차량 센서 $id", it) } }
                } else {
                    properties = null
                    mutable.value = VehicleSnapshot()
                    problem("차량 상태", IllegalStateException("차량 서비스 연결이 끊어졌습니다"))
                }
            }
        }.onFailure { problem("차량 상태", it) }
        every {
            query("브레이크", { acceptBrake(null) }) {
                ensureReadable("브레이크")
                if (registered.add("브레이크")) vehicle.getBrake().registerBrakePedalStatus(brakeListener)
                acceptBrake(sdkCall<Boolean?> { ok, fail -> vehicle.getBrake().getCurrentBrakePedalStatus(ok, fail) }, false)
            }
        }
        every {
            query("하향등", { mutate { it.copy(lowBeam = null) } }) {
                ensureReadable("하향등")
                if (registered.add("하향등")) vehicle.getLight().registerLowBeamLightState(lightListener)
                updateLight(sdkCall { ok, fail -> vehicle.getLight().getCurrentLowBeamLightState(ok, fail) })
            }
        }
        every { readRain() }
        every {
            runCatching { ux?.currentCarUxRestrictions }.onSuccess { r ->
                mutate { it.copy(uxRestricted = r?.let { Reading(it.isRequiresDistractionOptimization, now()) }) }
            }.onFailure { mutate { it.copy(uxRestricted = null) }; problem("주행 조작 제한", it) }
        }
        ids.forEach { id -> every {
            val manager = properties
            if (manager != null) runCatching { manager.getProperty<Any>(id, 0) }
                .onSuccess { if (it != null) acceptProperty(it) else invalidate(id) }
                .onFailure { invalidate(id); problem("차량 센서 $id", it) }
        } }
        every { if (naviReady) runCatching { navi.getTBTInfo() }.onFailure { problem("내비게이션", it) } }
    }
    private fun query(key: String, invalidate: () -> Unit, block: () -> Unit) {
        try { block(); good(key) }
        catch (e: java.util.concurrent.TimeoutException) { invalidate(); problem(key, e) }
        catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: Exception) { invalidate(); problem(key, e) }
    }
    @Synchronized private fun acceptBrake(pressed: Boolean?, fromEvent: Boolean = true) {
        if (closed) return
        val previous = mutable.value.brake?.value
        val reading = pressed?.let { Reading(it, now()) }
        mutate { it.copy(brake = reading, brakeFromEvent = pressed != null && (fromEvent || (it.brakeFromEvent && previous == pressed))) }
        // Keep each press/release edge even if a quick tap fits between two UI refresh ticks.
        if (fromEvent && previous != pressed) onBrake?.invoke(mutable.value)
    }
    private fun readRain() = query("와이퍼", { mutate { it.copy(rain = null) } }) {
        if (!rainMutex.tryLock()) return@query
        try {
        ensureReadable("와이퍼")
        if (registered.add("와이퍼")) vehicle.getWiper().registerWiperState(front, wiperListener)
        val switch = sdkCall<WiperSwitch?> { ok, fail -> vehicle.getWiper().getCurrentWiperSwitch(front, ok, fail) }
        val raining = when (switch) {
            WiperSwitch.CONTINUOUS_LEVEL_1, WiperSwitch.CONTINUOUS_LEVEL_2, WiperSwitch.CONTINUOUS_LEVEL_3,
            WiperSwitch.CONTINUOUS_LEVEL_4, WiperSwitch.CONTINUOUS_LEVEL_5 -> true
            WiperSwitch.OFF, WiperSwitch.MIST, WiperSwitch.SERVICE -> false
            WiperSwitch.AUTO -> {
                val status = sdkCall<WiperState?> { ok, fail -> vehicle.getWiper().getCurrentWiperState(front, ok, fail) }
                when (status) { WiperState.ON -> true; WiperState.OFF -> false; else -> null }
            }
            null, WiperSwitch.OTHER -> null
            else -> false
        }
        mutate { it.copy(rain = raining?.let { b -> Reading(b, now()) }, wiperLevel = switch?.let { w -> Reading(w.name, now()) }) }
        } finally { rainMutex.unlock() }
    }
    private fun ensureReadable(key: String) {
        if (key in readSupport) return
        val readable = listOf(AccessType.READ, AccessType.READ_WRITE)
        val supported = sdkCall<Boolean> { ok, fail -> when (key) {
            "브레이크" -> vehicle.getBrake().checkBrakePedalStatusCapabilities({ c -> ok(c.accessType in readable) }, fail)
            "하향등" -> vehicle.getLight().checkLowBeamLightStateCapabilities({ c, _ -> ok(c.accessType in readable) }, fail)
            else -> vehicle.getWiper().checkWiperSwitchCapabilities({ c, areas -> ok(c.accessType in readable && areas[front]?.isNotEmpty() == true) }, fail)
        } }
        check(supported) { "이 차량에서 읽기를 지원하지 않습니다" }
        readSupport += key
    }
    private fun updateLight(s: LightState?) {
        val on = when (s) { LightState.ON -> true; LightState.OFF -> false; else -> null }
        mutate { it.copy(lowBeam = on?.let { b -> Reading(b, now()) }) }
    }
    private fun acceptProperty(p: CarPropertyValue<*>) {
        if (closed) return
        if (p.status != CarPropertyValue.STATUS_AVAILABLE) { invalidate(p.propertyId); return }
        val at = now()
        mutate { s -> when (p.propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> s.copy(speedMps = (p.value as? Float)?.takeIf { it.isFinite() }?.let { Reading(it, at) })
            VehiclePropertyIds.NIGHT_MODE -> s.copy(dark = (p.value as? Boolean)?.let { Reading(it, at) })
            VehiclePropertyIds.GEAR_SELECTION -> s.copy(gear = when (p.value as? Int) {
                android.car.VehicleGear.GEAR_PARK -> Gear.PARK
                android.car.VehicleGear.GEAR_DRIVE -> Gear.DRIVE
                android.car.VehicleGear.GEAR_REVERSE -> Gear.REVERSE
                android.car.VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
                else -> null
            }?.let { Reading(it, at) })
            else -> s
        } }
        good("차량 센서 ${p.propertyId}")
        good("차량 상태")
    }
    private fun invalidate(id: Int) = mutate { s -> when (id) {
        VehiclePropertyIds.PERF_VEHICLE_SPEED -> s.copy(speedMps = null)
        VehiclePropertyIds.NIGHT_MODE -> s.copy(dark = null)
        VehiclePropertyIds.GEAR_SELECTION -> s.copy(gear = null)
        else -> s
    } }
    fun close() {
        closed = true; onBrake = null; workers.shutdownNow()
        runCatching { properties?.unregisterCallback(carListener) }
        runCatching { ux?.unregisterListener() }
        runCatching { car?.disconnect() }
        runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
        runCatching { vehicle.getLight().unregisterLowBeamLightState(lightListener) }
        runCatching { vehicle.getWiper().unregisterWiperState(front, wiperListener) }
        runCatching { navi.removeListener(navListener); navi.release() }
        runCatching { vehicle.release() }
        mutable.value = VehicleSnapshot()
    }
    companion object {
        private val ids = listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED, VehiclePropertyIds.GEAR_SELECTION,
            VehiclePropertyIds.NIGHT_MODE)
    }
}
