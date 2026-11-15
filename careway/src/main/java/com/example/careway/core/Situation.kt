package com.example.careway.core

/**
 * Scenario triggers from the planning document (시나리오 1~4).
 * A trigger only induces the *situation* (inputs). Detection, alerts, voice and vehicle control
 * always run on the normal feature path: ContextEngine -> ActionCoordinator -> VehicleGateway.
 */
enum class Trigger(val scenario: Int, val label: String) {
    IGNITION(1, "시동 걸기 · P단 정차"),
    NIGHT_DRIVE(2, "야간 도로 진입 · 전조등 OFF"),
    HEADLIGHT_ON(2, "전조등 켜기"),
    RAIN_START(3, "소나기 시작 · 와이퍼 연속 작동"),
    RAIN_STOP(3, "비 그침 · 와이퍼 정지"),
    TUNNEL_DRIVE(4, "터널 접근 주행 시작"),
    RESET(0, "상황 종료 · 차량 신호로 복귀"),
}

data class TunnelProgress(val tunnel: Tunnel, val finished: Boolean = false)

/**
 * Induced driving situation layered over the vehicle snapshot. Null means "use the vehicle signal".
 * Never touches the brake pedal, pedal-event source or UX restriction: the safety-fit interlock
 * and the reaction measurement always use the vehicle's own signals.
 */
data class Situation(
    val driveKph: Int? = null,
    val driveSince: Long = 0,
    val night: Boolean? = null,
    val lowBeam: Boolean? = null,
    val rain: Boolean? = null,
    val tunnelSince: Long? = null,
) {
    val active get() = driveKph != null || night != null || lowBeam != null || rain != null || tunnelSince != null

    fun after(trigger: Trigger, now: Long): Situation = when (trigger) {
        // Departure check uses the real parking interlock; nothing is induced on top of it.
        Trigger.IGNITION, Trigger.RESET -> Situation()
        Trigger.NIGHT_DRIVE -> Situation(driveKph = 40, driveSince = now, night = true, lowBeam = false)
        Trigger.HEADLIGHT_ON -> if (active) copy(lowBeam = true) else this
        Trigger.RAIN_START -> copy(
            driveKph = driveKph ?: 60, driveSince = if (driveKph == null) now - RAMP_MS else driveSince,
            night = night ?: true, lowBeam = true, rain = true)
        Trigger.RAIN_STOP -> if (rain == true) copy(rain = false) else this
        Trigger.TUNNEL_DRIVE -> copy(
            driveKph = TUNNEL_KPH, driveSince = now - RAMP_MS, night = false, lowBeam = true, tunnelSince = now)
    }

    fun apply(source: VehicleSnapshot, now: Long): VehicleSnapshot {
        if (!active) return source
        var s = source
        driveKph?.let { kph ->
            val ramp = (now - driveSince).coerceIn(0, RAMP_MS).toFloat() / RAMP_MS
            s = s.copy(speedMps = Reading(kph / 3.6f * ramp, now), gear = Reading(Gear.DRIVE, now))
        }
        night?.let { s = s.copy(dark = Reading(it, now)) }
        // A vehicle low beam that is really ON always wins, so switching the lamp on in the car clears the warning.
        lowBeam?.let { if (source.lowBeam?.fresh(now) != true) s = s.copy(lowBeam = Reading(it, now)) }
        rain?.let { s = s.copy(rain = Reading(it, now), wiperLevel = Reading(if (it) "CONTINUOUS_LEVEL_2" else "OFF", now)) }
        tunnelSince?.let { s = s.copy(tunnel = Reading(tunnelAt(now - it).tunnel, now)) }
        return s
    }

    /** Ends the tunnel guidance once the vehicle has passed the entrance, as real navigation does. */
    fun settled(now: Long): Situation =
        if (tunnelSince != null && tunnelAt(now - tunnelSince).finished) copy(tunnelSince = null) else this

    fun describe(now: Long): String = listOfNotNull(
        driveKph?.let { "주행 ${it}km/h" },
        night?.let { if (it) "야간" else "주간" },
        lowBeam?.let { if (it) "전조등 ON" else "전조등 OFF" },
        rain?.let { if (it) "소나기 · 와이퍼 연속 작동" else "비 그침" },
        tunnelSince?.let { tunnelText(tunnelAt(now - it).tunnel) },
    ).joinToString(" · ")

    companion object {
        const val RAMP_MS = 3_000L
        const val TUNNEL_KPH = 80
        /** The induced guidance starts this far before the tunnel entrance. */
        const val APPROACH_M = 250.0

        /** Same shape as NaviHelper TBT: only the remaining distance to the entrance, at a constant 80 km/h. */
        fun tunnelAt(elapsedMs: Long): TunnelProgress {
            val d = TUNNEL_KPH / 3.6 * elapsedMs.coerceAtLeast(0) / 1000.0
            return if (d < APPROACH_M) TunnelProgress(Tunnel(TunnelPhase.APPROACH, APPROACH_M - d))
            else TunnelProgress(Tunnel(TunnelPhase.CLEAR), finished = true)
        }

        fun tunnelText(tunnel: Tunnel?): String = when (tunnel?.phase) {
            TunnelPhase.APPROACH -> "터널 입구 ${tunnel.distanceM?.toInt() ?: 0}m 전"
            TunnelPhase.CLEAR -> "터널 안내 없음"
            null -> "수신 대기"
        }
    }
}
