package com.example.careway.core

/** Pure policy: never calls a vehicle API. Missing/stale observations cannot authorize a write. */
class ContextEngine {
    private var rainUntil = Long.MIN_VALUE
    private var tintStarted: Long? = null
    private var tintExpired = false

    fun evaluate(s: VehicleSnapshot, settings: CareSettings, now: Long, enhanced: Boolean = false,
                 ventilationRequested: Boolean = false): CarePlan {
        val rainReading = s.rain?.fresh(now)
        if (rainReading == true) rainUntil = now + 10_000
        val rain = settings.rain && rainReading != null && now <= rainUntil
        val dark = s.dark?.fresh(now)
        val night = settings.night && dark == true
        val t = s.tunnel?.fresh(now, 10_000)
        val tunnel = settings.tunnel && t != null && when (t.phase) {
            TunnelPhase.APPROACH -> t.distanceM?.let { it.isFinite() && it in 0.0..200.0 } == true
            TunnelPhase.INSIDE, TunnelPhase.EXIT -> true
            TunnelPhase.CLEAR -> false
        }
        val glareRequested = dark == false && (
            s.glare?.fresh(now) == true || (settings.tunnel && t?.phase == TunnelPhase.EXIT &&
                t.distanceM?.let { it.isFinite() && it in 0.0..100.0 } == true))
        if (!glareRequested) { tintStarted = null; tintExpired = false }
        if (glareRequested && tintStarted == null) tintStarted = now
        if (tintStarted?.let { now - it >= 15_000 } == true) tintExpired = true
        val glare = glareRequested && !tintExpired
        val desired = linkedMapOf<Control, Int>()
        val alerts = mutableListOf<CareAlert>()
        if (night) desired[Control.AMBIENT] = 0 // adapter maps this to the supported minimum
        if (rain) {
            desired[Control.AMBIENT] = 0
            desired[Control.HVAC_POWER] = 1
            desired[Control.DEFROST] = 1
            desired[Control.RECIRCULATION] = 0
            desired[Control.MIRROR_LEFT] = 1
            desired[Control.MIRROR_RIGHT] = 1
        }
        // Visibility takes precedence over tunnel fumes. Do not oscillate the same actuator.
        if (tunnel && !rain) desired[Control.RECIRCULATION] = 1
        if (ventilationRequested && s.parked(now) && !tunnel) {
            desired[Control.HVAC_POWER] = 1
            desired[Control.RECIRCULATION] = 0
        }
        if (rain && tunnel) alerts += CareAlert("rain_tunnel", "앞유리 시야를 우선합니다", "빗길 터널에서는 외기와 김서림 제거를 유지합니다.")
        else if (rain) alerts += CareAlert("rain", "와이퍼 작동, 시야를 확인하세요", "김서림 제거와 미러 열선을 지원하는 차량에서 자동 케어를 요청합니다.")
        else if (tunnel) alerts += CareAlert("tunnel", "터널이 가까워지고 있어요", "내기 순환을 지원하는 차량에서 공조 전환을 요청합니다.")
        if (glare) {
            settings.glassLevel?.let {
                desired[Control.GLASS_FRONT] = it
                desired[Control.GLASS_ROOF] = it
            }
            alerts += CareAlert("glare", "출구 빛에 대비하세요", if (settings.glassLevel == null) "스마트 글래스의 차량별 검증 값이 필요합니다." else "설정한 스마트 글래스 단계로 눈부심을 줄입니다.")
        }
        if (settings.night && s.moving(now) && s.lowBeam?.fresh(now) == false && (dark == true || tunnel)) {
            alerts.add(0, CareAlert("headlights", "전조등을 켜주세요", if (enhanced) "속도를 줄이고 전방을 충분히 살펴주세요." else "어두운 도로입니다. 하향등이 꺼져 있습니다.", true))
        }
        return CarePlan(desired, alerts, rain, tunnel, glare, night)
    }
}

enum class ReactionPhase { IDLE, WAITING, STIMULUS, COMPLETE, EARLY, TIMEOUT, CANCELLED }
data class ReactionState(
    val phase: ReactionPhase = ReactionPhase.IDLE,
    val reactionMs: Long? = null,
    val delayed: Boolean = false,
    val message: String = "P단 · 주차 브레이크 · 완전 정차 상태에서 시작하세요",
)

/** Monotonic timestamps only. Display acknowledgment starts timing, never a scheduled timer alone. */
class ReactionEvaluator(private val previous: () -> List<Long>) {
    var state = ReactionState(); private set
    private var dueAt = 0L
    private var shownAt: Long? = null
    private var previousBrake: Boolean? = null
    val active get() = state.phase == ReactionPhase.WAITING || state.phase == ReactionPhase.STIMULUS

    fun start(s: VehicleSnapshot, now: Long, delayMs: Long): Boolean {
        if (!s.reactionReady(now) || s.brake?.fresh(now) != false || active) return false
        dueAt = now + delayMs.coerceIn(1_500, 3_500)
        shownAt = null
        previousBrake = false
        state = ReactionState(ReactionPhase.WAITING, message = "동물이 도로에 나타나면 브레이크를 밟으세요")
        return true
    }

    fun tick(s: VehicleSnapshot, now: Long, foreground: Boolean = true) {
        if (!active) return
        if (!foreground || !s.reactionReady(now) || s.brake?.fresh(now) == null) {
            cancel("정차 조건 또는 센서 연결이 바뀌어 측정을 중단했습니다")
            return
        }
        val pressed = s.brake?.fresh(now)
        val edge = pressed == true && previousBrake == false
        previousBrake = pressed
        if (edge) {
            val start = shownAt
            val pressedAt = s.brake?.atMs ?: now
            if (start == null || pressedAt - start < 100) {
                state = ReactionState(ReactionPhase.EARLY, message = "조금 일찍 밟았어요. 발을 뗀 뒤 다시 시도하세요")
            } else if (pressedAt - start <= 5_000) {
                val elapsed = pressedAt - start
                val samples = previous().filter { it in 100..5_000 }.takeLast(10).sorted()
                val threshold = if (samples.size >= 3) maxOf(1_000L, (samples[samples.size / 2] * 1.5).toLong()) else 1_000L
                state = ReactionState(ReactionPhase.COMPLETE, elapsed, elapsed > threshold,
                    if (elapsed > threshold) "전방 주의 알림 모드를 강화합니다" else "반응을 기록했어요. 여유 있게 출발하세요")
            } else timeout()
        } else if (shownAt?.let { now - it >= 5_000 } == true) timeout()
        else if (shownAt == null && now - dueAt > 2_000) cancel("화면 표시가 지연되어 측정을 취소했습니다")
    }

    fun readyToShow(now: Long) = state.phase == ReactionPhase.WAITING && now >= dueAt
    fun stimulusVisible(now: Long) {
        if (readyToShow(now)) {
            shownAt = now
            state = state.copy(phase = ReactionPhase.STIMULUS, message = "지금 브레이크를 밟으세요")
        }
    }
    fun cancel(reason: String = "측정을 취소했습니다") {
        if (active) state = ReactionState(ReactionPhase.CANCELLED, message = reason)
    }
    private fun timeout() { state = ReactionState(ReactionPhase.TIMEOUT, delayed = true, message = "반응이 기록되지 않았어요. 휴식 후 다시 확인해 주세요") }
}
