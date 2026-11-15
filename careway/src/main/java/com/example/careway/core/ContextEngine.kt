package com.example.careway.core

/** Pure policy: never calls a vehicle API. Missing/stale observations cannot authorize a write. */
class ContextEngine {
    private var rainUntil = Long.MIN_VALUE
    private var tunnelUntil = Long.MIN_VALUE

    fun evaluate(s: VehicleSnapshot, settings: CareSettings, now: Long, enhanced: Boolean = false,
                 ventilationRequested: Boolean = false): CarePlan {
        val rainReading = s.rain?.fresh(now)
        if (rainReading == true) rainUntil = now + 10_000
        val rain = settings.rain && rainReading != null && now <= rainUntil
        val dark = s.dark?.fresh(now)
        val night = settings.night && dark == true
        // Navigation gives only the distance to the tunnel entrance. Care starts 100 m before it and is
        // held for a fixed time after the last such announcement, because no inside/exit signal exists.
        val t = s.tunnel?.fresh(now, 10_000)
        val approaching = t?.phase == TunnelPhase.APPROACH &&
            t.distanceM?.let { it.isFinite() && it in 0.0..TUNNEL_START_M } == true
        if (approaching) tunnelUntil = now + TUNNEL_HOLD_MS
        val tunnel = settings.tunnel && now <= tunnelUntil
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
        // P gear at standstill is the same interlock as the reaction check that requested the ventilation.
        if (ventilationRequested && s.stationaryInPark(now) && !tunnel) {
            desired[Control.HVAC_POWER] = 1
            desired[Control.RECIRCULATION] = 0
        }
        if (rain && tunnel) alerts += CareAlert("rain_tunnel", "앞유리 시야를 우선합니다", "빗길 터널에서는 외기와 김서림 제거를 유지합니다.")
        else if (rain) alerts += CareAlert("rain", "와이퍼 작동, 시야를 확인하세요", "김서림 제거와 미러 열선을 지원하는 차량에서 자동 케어를 요청합니다.")
        else if (tunnel) alerts += if (approaching)
            CareAlert("tunnel", "터널이 가까워지고 있어요", "내기 순환을 지원하는 차량에서 공조 전환을 요청합니다.")
            else CareAlert("tunnel", "터널을 지나고 있어요", "매연이 들어오지 않도록 내기 순환을 유지합니다.")
        if (settings.night && s.moving(now) && s.lowBeam?.fresh(now) == false && (dark == true || tunnel)) {
            alerts.add(0, CareAlert("headlights", "전조등을 켜주세요", if (enhanced) "속도를 줄이고 전방을 충분히 살펴주세요." else "어두운 도로입니다. 하향등이 꺼져 있습니다.", true))
        }
        return CarePlan(desired, alerts, rain, tunnel, night)
    }
    companion object {
        /** Recirculation starts when the tunnel entrance is this close. */
        const val TUNNEL_START_M = 100.0
        /** Recirculation is kept this long after the last entrance announcement, then restored. */
        const val TUNNEL_HOLD_MS = 30_000L
    }
}

enum class ReactionPhase { IDLE, WAITING, STIMULUS, REST, COMPLETE, EARLY, TIMEOUT, CANCELLED }

/** Today's condition from the average of the three brake reactions. */
enum class Condition(val label: String) {
    NORMAL("정상"), CAUTION("주의"), SLOW("느림");
    companion object {
        /** Up to 0.5 s on average is normal; 1 s or more is slow. */
        const val NORMAL_MAX_MS = 500L
        const val SLOW_MIN_MS = 1_000L
        fun of(averageMs: Long) = when {
            averageMs <= NORMAL_MAX_MS -> NORMAL
            averageMs < SLOW_MIN_MS -> CAUTION
            else -> SLOW
        }
    }
}

/** 620 -> "0.62" */
fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.2f", ms / 1000.0)

data class ReactionState(
    val phase: ReactionPhase = ReactionPhase.IDLE,
    /** Last trial while resting; the three-trial average once complete. */
    val reactionMs: Long? = null,
    val delayed: Boolean = false,
    val message: String = "P단 · 완전 정차 상태에서 시작하세요",
    val trial: Int = 0,
    val samples: List<Long> = emptyList(),
    val condition: Condition? = null,
)

/**
 * One check is three brake reactions; their average decides the condition.
 * Monotonic timestamps only. Display acknowledgment starts timing, never a scheduled timer alone.
 */
class ReactionEvaluator(private val nextDelay: () -> Long = { 2_000L }) {
    var state = ReactionState(); private set
    private var dueAt = 0L
    private var shownAt: Long? = null
    private var previousBrake: Boolean? = null
    private var restUntil = 0L
    private val samples = mutableListOf<Long>()
    val active get() = state.phase == ReactionPhase.WAITING || state.phase == ReactionPhase.STIMULUS ||
        state.phase == ReactionPhase.REST

    fun start(s: VehicleSnapshot, now: Long, delayMs: Long): Boolean {
        if (!s.reactionReady(now) || s.brake?.fresh(now) != false || active) return false
        samples.clear()
        previousBrake = false
        arm(1, now, delayMs)
        return true
    }

    private fun arm(trial: Int, now: Long, delayMs: Long) {
        dueAt = now + delayMs.coerceIn(1_500, 3_500)
        shownAt = null
        state = ReactionState(ReactionPhase.WAITING, trial = trial, samples = samples.toList(),
            message = "측정 $trial/$TRIALS · 동물이 도로에 나타나면 브레이크를 밟으세요")
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
        if (state.phase == ReactionPhase.REST) {
            // The next trial starts only after the pedal is released and the result was shown for a moment.
            if (pressed == false && now >= restUntil) arm(state.trial + 1, now, nextDelay())
            return
        }
        if (edge) {
            val start = shownAt
            val pressedAt = s.brake?.atMs ?: now
            if (start == null || pressedAt - start < 100) {
                state = ReactionState(ReactionPhase.EARLY, message = "조금 일찍 밟았어요. 발을 뗀 뒤 다시 시도하세요")
            } else if (pressedAt - start <= 5_000) record(pressedAt - start, now)
            else timeout()
        } else if (shownAt?.let { now - it >= 5_000 } == true) timeout()
        else if (shownAt == null && now - dueAt > 2_000) cancel("화면 표시가 지연되어 측정을 취소했습니다")
    }

    private fun record(elapsed: Long, now: Long) {
        samples += elapsed
        val trial = state.trial
        if (samples.size < TRIALS) {
            restUntil = now + REST_MS
            state = ReactionState(ReactionPhase.REST, elapsed, trial = trial, samples = samples.toList(),
                message = "${trial}회 ${seconds(elapsed)}초 · 발을 떼면 다음 측정을 시작합니다")
            return
        }
        val average = samples.sum() / samples.size
        val condition = Condition.of(average)
        state = ReactionState(ReactionPhase.COMPLETE, average, condition == Condition.SLOW, when (condition) {
            Condition.NORMAL -> "컨디션 정상 · 평균 ${seconds(average)}초. 여유 있게 출발하세요"
            Condition.CAUTION -> "컨디션 주의 · 평균 ${seconds(average)}초. 평소보다 여유를 두고 출발하세요"
            Condition.SLOW -> "컨디션 느림 · 평균 ${seconds(average)}초. 전방 주의 알림 모드를 강화합니다"
        }, trial, samples.toList(), condition)
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

    companion object {
        const val TRIALS = 3
        /** Each trial result stays on screen at least this long before the next one is armed. */
        const val REST_MS = 1_000L
    }
}
