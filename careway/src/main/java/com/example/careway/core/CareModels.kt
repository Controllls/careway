package com.example.careway.core

import kotlin.math.abs

data class Reading<T>(val value: T, val atMs: Long) {
    fun fresh(now: Long, ttl: Long = 3_000): T? = value.takeIf { now - atMs in 0..ttl }
}

enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL }
enum class TunnelPhase { APPROACH, INSIDE, EXIT, CLEAR }
data class Tunnel(val phase: TunnelPhase, val distanceM: Double? = null)

data class VehicleSnapshot(
    val speedMps: Reading<Float>? = null,
    val gear: Reading<Gear>? = null,
    val parkingBrake: Reading<Boolean>? = null,
    val brake: Reading<Boolean>? = null,
    val dark: Reading<Boolean>? = null,
    val lowBeam: Reading<Boolean>? = null,
    val rain: Reading<Boolean>? = null,
    val tunnel: Reading<Tunnel>? = null,
    val glare: Reading<Boolean>? = null,
    val uxRestricted: Reading<Boolean>? = null,
    val brakeFromEvent: Boolean = false,
    val wiperLevel: Reading<String>? = null,
) {
    fun stationaryInPark(now: Long) = speedMps?.fresh(now)?.let { it.isFinite() && abs(it) < 0.1f } == true &&
        gear?.fresh(now) == Gear.PARK
    fun parked(now: Long) = stationaryInPark(now) && parkingBrake?.fresh(now) == true
    // The same interlock applies to all live connections, including emulators.
    fun reactionReady(now: Long) = parked(now) && uxRestricted?.fresh(now) == false && brakeFromEvent
    fun reactionStartBlocker(now: Long): String? = when {
        gear?.fresh(now) != Gear.PARK -> "P단에서 시작할 수 있습니다"
        !stationaryInPark(now) -> "차속 0과 완전 정차를 확인해야 합니다"
        parkingBrake?.fresh(now) == false -> "주차 브레이크가 해제돼 있습니다. 체결 후 시작하세요"
        parkingBrake?.fresh(now) != true -> "주차 브레이크 신호를 확인할 수 없습니다"
        uxRestricted?.fresh(now) != false -> "차량의 화면 조작 허용을 기다리고 있습니다"
        !brakeFromEvent -> "브레이크 페달 이벤트 연결을 기다리고 있습니다"
        brake?.fresh(now) == true -> "브레이크에서 발을 뗀 뒤 다시 시작하세요"
        brake?.fresh(now) != false -> "브레이크 페달 상태를 확인할 수 없습니다"
        else -> null
    }
    fun moving(now: Long) = speedMps?.fresh(now)?.let { it.isFinite() && abs(it) >= 0.5f } == true
}

enum class Control(val label: String) {
    HVAC_POWER("운전석 공조"),
    DEFROST("앞유리 김서림 제거"), RECIRCULATION("내기 순환"),
    MIRROR_LEFT("왼쪽 미러 열선"), MIRROR_RIGHT("오른쪽 미러 열선"),
    AMBIENT("실내 조명 감광"), GLASS_FRONT("앞유리 스마트 글래스"), GLASS_ROOF("루프 스마트 글래스")
}

data class Capability(val supported: Boolean, val min: Int = 0, val max: Int = 1, val reason: String = "") {
    fun accepts(value: Int) = supported && value in min..max
}

data class CareSettings(
    val night: Boolean = true,
    val rain: Boolean = true,
    val tunnel: Boolean = true,
    val voice: Boolean = true,
    // A vehicle-specific, reviewed level is required: SDK does not define tint direction or road legality.
    val glassLevel: Int? = null,
)

data class CareAlert(val id: String, val title: String, val detail: String, val urgent: Boolean = false)
data class CarePlan(
    val desired: Map<Control, Int> = emptyMap(),
    val alerts: List<CareAlert> = emptyList(),
    val raining: Boolean = false,
    val tunnelActive: Boolean = false,
    val glareActive: Boolean = false,
    val nightActive: Boolean = false,
)

interface VehicleGateway {
    suspend fun capability(control: Control): Capability
    suspend fun read(control: Control): Int
    suspend fun write(control: Control, value: Int)
}

data class CareEvent(val atMs: Long, val title: String, val detail: String, val demo: Boolean)
