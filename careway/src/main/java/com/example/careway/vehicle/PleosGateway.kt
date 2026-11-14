package com.example.careway.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.*
import ai.pleos.playground.vehicle.api.model.capability.*
import ai.pleos.playground.vehicle.constant.area.*
import com.example.careway.core.*
import java.util.concurrent.atomic.AtomicBoolean
import com.example.careway.vehicle.SdkCalls.call as sdkCall

/** SDK callbacks complete a bounded future on a worker thread; never wait on the UI thread. */
internal object SdkCalls {
    fun <T> call(call: ((T) -> Unit, (Exception) -> Unit) -> Unit): T {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "SDK wait on UI thread" }
        val result = java.util.concurrent.CompletableFuture<T>()
        try { call({ result.complete(it) }, { result.completeExceptionally(it) }) }
        catch (e: Exception) { result.completeExceptionally(e) }
        try { return result.get(2500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception ?: e) }
        finally { result.cancel(false) }
    }
}

class PleosGateway(private val vehicle: Vehicle) : VehicleGateway {
    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val roof = WindowArea(WindowValue.ROOF_TOP_1)
    private val left = MirrorArea(MirrorValue.DRIVER_LEFT)
    private val right = MirrorArea(MirrorValue.DRIVER_RIGHT)
    private var climateSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private var ambientSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private var powerSeat = SeatArea(SeatValue.ROW_1_LEFT)

    override fun capability(control: Control): Capability = when (control) {
        Control.HVAC_POWER -> {
            val (config, areas) = sdkCall<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
                vehicle.getHVAC().checkHvacPowerCapabilities({ c, a -> ok(c to a) }, fail)
            }
            val area = areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values }
            if (area != null) powerSeat = area
            supported(config, area != null)
        }
        Control.DEFROST -> {
            val (config, areas) = sdkCall<Pair<CapabilityConfig, List<WindowArea>>> { ok, fail ->
                vehicle.getHVAC().checkHvacDefrosterCapabilities({ c, a -> ok(c to a) }, fail)
            }
            supported(config, front in areas)
        }
        Control.RECIRCULATION -> {
            val (config, areas) = sdkCall<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
                vehicle.getHVAC().checkHvacRecirculationCapabilities({ c, a -> ok(c to a) }, fail)
            }
            val area = areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values }
            if (area != null) climateSeat = area
            supported(config, area != null)
        }
        Control.MIRROR_LEFT, Control.MIRROR_RIGHT -> {
            val (config, areas) = sdkCall<Pair<CapabilityConfig, Map<MirrorArea, AreaBounds<Int>>>> { ok, fail ->
                vehicle.getSideMirror().checkHvacSideMirrorHeatCapabilities({ c, a -> ok(c to a) }, fail)
            }
            bounded(config, areas[mirror(control)])
        }
        Control.AMBIENT -> {
            val (config, areas) = sdkCall<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail ->
                vehicle.getLight().checkAmbientLightBrightnessCapabilities({ c, a -> ok(c to a) }, fail)
            }
            val area = areas.keys.firstOrNull { SeatValue.ROW_1_LEFT in it.values }
            if (area != null) ambientSeat = area
            bounded(config, areas[area])
        }
        Control.GLASS_FRONT, Control.GLASS_ROOF -> {
            val (config, areas) = sdkCall<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>> { ok, fail ->
                vehicle.getWindow().checkSmartGlassLevelCapabilities({ c, a -> ok(c to a) }, fail)
            }
            bounded(config, areas[window(control)])
        }
    }

    private fun supported(c: CapabilityConfig, area: Boolean) = Capability(
        c.accessType == AccessType.READ_WRITE && area,
        reason = if (!area) "대상 영역을 지원하지 않습니다" else if (c.accessType != AccessType.READ_WRITE) "읽기·쓰기 권한 또는 차량 지원이 필요합니다" else "",
    )
    private fun bounded(c: CapabilityConfig, b: AreaBounds<Int>?): Capability = supported(c, b != null)
        .copy(min = b?.minValue ?: 0, max = b?.maxValue ?: 0)
    private fun mirror(c: Control) = if (c == Control.MIRROR_LEFT) left else right
    private fun window(c: Control) = if (c == Control.GLASS_FRONT) front else roof

    override fun read(control: Control): Int {
        val value: Int? = when (control) {
            Control.HVAC_POWER -> sdkCall<Boolean?> { ok, fail -> vehicle.getHVAC().getHvacPowerState(powerSeat, ok, fail) }?.let { if (it) 1 else 0 }
            Control.DEFROST -> sdkCall<Boolean?> { ok, fail -> vehicle.getHVAC().getHvacDefrosterState(front, ok, fail) }?.let { if (it) 1 else 0 }
            Control.RECIRCULATION -> sdkCall<Boolean?> { ok, fail -> vehicle.getHVAC().getHvacRecirculationState(climateSeat, ok, fail) }?.let { if (it) 1 else 0 }
            Control.MIRROR_LEFT, Control.MIRROR_RIGHT -> sdkCall { ok, fail -> vehicle.getSideMirror().getHvacSideMirrorHeatState(mirror(control), ok, fail) }
            Control.AMBIENT -> sdkCall { ok, fail -> vehicle.getLight().getAmbientLightBrightness(ambientSeat, ok, fail) }
            Control.GLASS_FRONT, Control.GLASS_ROOF -> sdkCall { ok, fail -> vehicle.getWindow().getCurrentSmartGlassLevel(window(control), ok, fail) }
        }
        return requireNotNull(value) { "현재 설정을 읽지 못했습니다. 자동 제어를 보류합니다" }
    }

    override fun write(control: Control, value: Int) {
        try { sdkCall<Unit> { ok, fail ->
            when (control) {
                Control.HVAC_POWER -> vehicle.getHVAC().setHvacPower(powerSeat, value != 0, { _, _ -> ok(Unit) }, fail)
                Control.DEFROST -> vehicle.getHVAC().setHvacDefroster(front, value != 0, { _, _ -> ok(Unit) }, fail)
                Control.RECIRCULATION -> vehicle.getHVAC().setHvacRecirculation(climateSeat, value != 0, { _, _ -> ok(Unit) }, fail)
                Control.MIRROR_LEFT, Control.MIRROR_RIGHT -> vehicle.getSideMirror().setHvacSideMirrorHeat(mirror(control), value, { _, _ -> ok(Unit) }, fail)
                Control.AMBIENT -> vehicle.getLight().setAmbientLightBrightness(ambientSeat, value, { _, _ -> ok(Unit) }, fail)
                Control.GLASS_FRONT, Control.GLASS_ROOF -> vehicle.getWindow().setSmartGlassLevel(window(control), value, { _, _ -> ok(Unit) }, fail)
            }
        }
        } catch (_: java.util.concurrent.TimeoutException) { /* The state may have changed despite a lost acknowledgment. Read it back. */ }
        // Set acknowledgment can precede VHAL propagation. Await the actual value, with a bound.
        val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
        while (read(control) != value) {
            if (android.os.SystemClock.elapsedRealtime() >= deadline) throw java.util.concurrent.TimeoutException("차량 적용 확인 시간 초과")
            Thread.sleep(500)
        }
    }
}
