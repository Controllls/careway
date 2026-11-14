package com.example.careway

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import com.example.careway.core.*
import com.example.careway.data.*
import com.example.careway.vehicle.*
import kotlin.random.Random

data class CareUiState(
    val demo: Boolean = true,
    val startupPrompt: Boolean = false,
    val foreground: Boolean = false,
    val enabled: Boolean = true,
    val busy: Boolean = false,
    val now: Long = 0,
    val snapshot: VehicleSnapshot = VehicleSnapshot(),
    val settings: CareSettings = CareSettings(),
    val plan: CarePlan = CarePlan(),
    val reaction: ReactionState = ReactionState(),
    val showStimulus: Boolean = false,
    val enhanced: Boolean = false,
    val statuses: Map<Control, String> = emptyMap(),
    val capabilities: Map<Control, Capability> = emptyMap(),
    val values: Map<Control, Int> = emptyMap(),
    val problems: Map<String, String> = emptyMap(),
    val voiceStatus: String = "음성 준비 중",
    val message: String? = null,
    val events: List<CareEvent> = emptyList(),
    val reactions: List<ReactionRecord> = emptyList(),
    val scenario: DemoScenario = DemoScenario.PARKED,
) {
    val parked get() = snapshot.parked(now)
    val reactionReady get() = snapshot.reactionReady(now)
    val canViewReaction get() = demo || (snapshot.stationaryInPark(now) && snapshot.uxRestricted?.fresh(now) == false)
    val canConfigure get() = demo || (parked && snapshot.uxRestricted?.fresh(now) == false)
}

class CareController(private val app: Application) {
    private val store = CareStore(app)
    private val mutable = StateCell(CareUiState(
        settings = store.settings(), events = store.events(), reactions = store.reactions()))
    val state = mutable
    private val simulator = DemoVehicle()
    @Volatile private var live: LiveVehicle? = null
    val restoreLiveConnection get() = store.liveInputPreferred()
    private var engine = ContextEngine()
    private var evaluator = newEvaluator()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val actions = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    @Volatile private var closing = false
    private val pulse = object : Runnable {
        override fun run() {
            if (closing) return
            tick()
            onChanged?.invoke(state.value)
            main.postDelayed(this, if (evaluator.active) 16 else 250)
        }
    }
    var onChanged: ((CareUiState) -> Unit)? = null
    private fun post(block: () -> Unit) { main.post { if (!closing) block() } }
    private fun work(block: () -> Unit) { if (!closing) actions.execute {
        try { block() } catch (e: Exception) { if (!closing) message(e.message ?: "작업 실패") }
    } }
    private var coordinator = coordinator(simulator, true)
    private val spokenAt = mutableMapOf<String, Long>()
    private var lastRecorded: ReactionState? = null
    private var startupOffered = false
    private var ventilationUntil = Long.MIN_VALUE
    private val voice = VoiceGuide(app) { status -> mutable.update { it.copy(voiceStatus = status) } }

    init {
        main.post(pulse)
        work { refreshCapabilities(simulator) }
        actions.scheduleWithFixedDelay({
            if (!closing) runCatching {
                val s = state.value
                coordinator.reconcile(if (s.enabled && s.foreground && !s.busy) s.plan.desired else emptyMap(), now()) { c, v ->
                    val current = state.value
                    !closing && current.enabled && current.foreground && !current.busy && current.plan.desired[c] == v
                }
                mutable.update { it.copy(statuses = coordinator.statuses,
                    values = if (it.demo) simulator.values.toMap() else emptyMap()) }
            }.onFailure { message(it.message ?: "차량 제어 실패") }
        }, 0, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun newEvaluator() = ReactionEvaluator {
        store.reactions().filter { it.demo == state.value.demo }.mapNotNull { it.durationMs }
    }
    private fun coordinator(g: VehicleGateway, demo: Boolean) = ActionCoordinator(g, store.ownership(demo)) { title, detail ->
        record(title, detail, demo)
        val control = Control.entries.firstOrNull { it.label == title }
        if (control != null && state.value.demo == demo) mutable.update { it.copy(statuses = it.statuses + (control to detail)) }
    }
    private fun record(title: String, detail: String, demo: Boolean = state.value.demo) {
        store.addEvent(CareEvent(System.currentTimeMillis(), title, detail, demo))
        mutable.update { it.copy(events = store.events()) }
    }
    private fun refreshCapabilities(g: VehicleGateway) {
        for (c in Control.entries) {
            val cap = try { g.capability(c) }
            catch (e: java.util.concurrent.TimeoutException) { Capability(false, reason = "응답 시간 초과") }
            catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (e: Exception) { Capability(false, reason = e.message ?: "지원 확인 실패") }
            mutable.update { it.copy(capabilities = it.capabilities + (c to cap)) }
        }
    }
    private fun tick() {
        val at = now()
        val s = state.value
        val snapshot = if (s.demo) simulator.snapshot(at) else live?.state?.value ?: VehicleSnapshot()
        evaluator.tick(snapshot, at, s.foreground)
        val reaction = evaluator.state
        var enhanced = s.enhanced
        if (reaction != lastRecorded && reaction.phase in setOf(ReactionPhase.COMPLETE, ReactionPhase.TIMEOUT)) {
            lastRecorded = reaction
            enhanced = reaction.delayed
            ventilationUntil = if (reaction.delayed) at + 20_000 else Long.MIN_VALUE
            store.addReaction(ReactionRecord(System.currentTimeMillis(), reaction.reactionMs, reaction.delayed, s.demo))
            record("세이프티 핏", reaction.message)
            if (s.settings.voice && s.foreground) voice.speak(reaction.message)
        }
        if (!s.demo && s.foreground && !startupOffered && !s.busy && snapshot.reactionReady(at)) {
            startupOffered = true
            mutable.update { it.copy(startupPrompt = true) }
        }
        val settings = if (s.demo) s.settings.copy(glassLevel = 2) else s.settings
        val plan = engine.evaluate(snapshot, settings, at, enhanced, at < ventilationUntil)
        mutable.update { it.copy(now = at, snapshot = snapshot, plan = plan, reaction = reaction,
            enhanced = enhanced, showStimulus = evaluator.readyToShow(at) || reaction.phase == ReactionPhase.STIMULUS,
            problems = if (s.demo) emptyMap() else live?.problems?.value ?: mapOf("연결" to "Pleos 차량 환경에서 연결해 주세요"),
            reactions = if (reaction != s.reaction) store.reactions() else it.reactions) }
        if (s.enabled && s.foreground) {
            val alert = plan.alerts.firstOrNull()
            if (alert != null) {
                val interval = if (alert.urgent && enhanced) 15_000 else 30_000
                if (at - (spokenAt["${s.demo}:${alert.id}"] ?: -60_000L) >= interval) {
                    spokenAt["${s.demo}:${alert.id}"] = at
                    record(alert.title, alert.detail)
                    if (s.settings.voice) voice.speak("${alert.title}. ${alert.detail}")
                }
            }
            // Keep the cooldown even when a fluctuating input temporarily removes an alert.
        }
    }
    fun foreground(value: Boolean) {
        mutable.update { it.copy(foreground = value) }
        if (!value) { evaluator.cancel("화면을 벗어나 측정을 중단했습니다"); voice.stop() }
    }
    fun enable(value: Boolean) {
        val s = state.value
        if (value && !s.demo && !s.canConfigure) { message("자동 케어는 P단·주차 브레이크·정차 확인 후 켤 수 있습니다"); return }
        if (value && !s.demo && coordinator.pendingRestoration) { message("이전 설정 복원을 먼저 완료해 주세요"); return }
        mutable.update { it.copy(enabled = value) }
        if (!value) voice.stop()
        record(if (value) "자동 케어 시작" else "자동 케어 중지", if (value) "앱 화면이 표시되는 동안 동작합니다" else "앱이 변경한 설정을 복원합니다")
    }
    fun switchMode(demo: Boolean) {
        val s = state.value
        if (demo == s.demo || s.busy || closing) return
        if (!s.demo && s.snapshot.moving(s.now)) { message("주행 중에는 모드를 바꿀 수 없습니다"); return }
        mutable.update { it.copy(busy = true, enabled = false) }
        evaluator.cancel(); voice.stop()
        work {
            try {
                coordinator.reconcile(emptyMap(), now())
                if (coordinator.pendingRestoration) {
                    message("차량 설정 복원이 대기 중입니다. 연결을 유지한 뒤 다시 전환해 주세요")
                    mutable.update { it.copy(busy = false) }
                } else {
                    // Release the previous SDK instance before constructing its replacement.
                    live?.onBrake = null
                    live?.close()
                    post { finishSwitch(demo) }
                }
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false) }; message(e.message ?: "연결 전환 실패")
            }
        }
    }
    private fun finishSwitch(demo: Boolean) {
        live = null
        engine = ContextEngine(); startupOffered = false; spokenAt.clear(); lastRecorded = null; ventilationUntil = Long.MIN_VALUE
        mutable.update { it.copy(demo = demo, enabled = demo, enhanced = false, busy = true,
            startupPrompt = false, statuses = emptyMap(), capabilities = emptyMap(), problems = emptyMap(), snapshot = VehicleSnapshot(),
            settings = it.settings.copy(glassLevel = null), plan = CarePlan(), message = null, scenario = DemoScenario.PARKED) }
        evaluator = newEvaluator()
        store.saveInputMode(demo)
        if (demo) {
            simulator.scenario = DemoScenario.PARKED; simulator.brakePressed = false
            work { coordinator = coordinator(simulator, true); refreshCapabilities(simulator); mutable.update { it.copy(busy = false) } }
        } else if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            val connection = LiveVehicle(app)
            live = connection
            connection.onBrake = { snapshot ->
                // Capture every event snapshot before posting; fast press/release pairs must not collapse.
                post { if (live === connection && !state.value.demo) {
                    evaluator.tick(snapshot, snapshot.brake?.atMs ?: now(), state.value.foreground)
                    tick()
                } }
            }
            connection.start(app)
            work {
                try {
                    coordinator = coordinator(connection.gateway, false)
                    coordinator.reconcile(emptyMap(), now())
                    refreshCapabilities(connection.gateway)
                } finally { mutable.update { it.copy(busy = false) } }
            }
        } else {
            work { coordinator = coordinator(simulator, true); mutable.update { it.copy(busy = false) } }
            message("이 기기에는 차량 서비스가 없습니다. 시뮬레이션으로 기능을 확인할 수 있습니다")
        }
    }
    fun refreshConnection() {
        if (!state.value.demo && !state.value.busy) work { live?.let { refreshCapabilities(it.gateway) } }
    }
    fun scenario(value: DemoScenario) {
        if (!state.value.demo) return
        evaluator.cancel("주행 시나리오가 바뀌어 측정을 중단했습니다")
        simulator.scenario = value
        simulator.brakePressed = false
        simulator.headlights = value !in setOf(DemoScenario.NIGHT, DemoScenario.RAIN)
        mutable.update { it.copy(scenario = value) }
        tick()
    }
    fun demoBrake(pressed: Boolean) { if (state.value.demo) { simulator.brakePressed = pressed; tick() } }
    fun demoHeadlights(on: Boolean) { if (state.value.demo) { simulator.headlights = on; tick() } }
    fun demoFailure(failed: Boolean) { if (state.value.demo) { simulator.failure = failed; message(if (failed) "통신 실패 시뮬레이션 시작" else "통신 복구 · 최대 15초 후 재시도") } }
    fun demoManualOverride() {
        if (state.value.demo) { simulator.values[Control.AMBIENT] = 70; record("수동 조작 시연", "실내 조명을 운전자가 70으로 변경했습니다") }
    }
    fun dismissStartup() { mutable.update { it.copy(startupPrompt = false) } }
    fun retryVoice() { voice.retry() }
    fun testVoice() { if (state.value.canConfigure) voice.speak("꼼짝마차 음성 안내입니다. 전방을 살피고 여유 있게 출발하세요.") }
    fun prepareReaction() {
        dismissStartup()
        // Only the synthetic vehicle changes scenario; live parking signals are never overridden.
        if (state.value.demo && !evaluator.active) scenario(DemoScenario.PARKED)
    }
    fun startReaction() {
        dismissStartup()
        val s = state.value
        if (s.busy || !s.foreground || !evaluator.start(s.snapshot, now(), Random.nextLong(1_500, 3_501))) {
            message(if (s.busy) "연결과 설정 복원을 마친 뒤 시작할 수 있습니다"
                else s.snapshot.reactionStartBlocker(now()) ?: "현재 측정이 끝난 뒤 다시 시작하세요")
        } else { lastRecorded = null; tick() }
    }
    fun stimulusVisible(drawnAt: Long) {
        val s = state.value
        val at = now()
        if (s.foreground && drawnAt in (at - 500)..at && s.snapshot.reactionReady(at))
            evaluator.stimulusVisible(drawnAt)
        tick()
    }
    fun cancelReaction() { evaluator.cancel(); tick() }
    fun settings(value: CareSettings) {
        if (!state.value.canConfigure) return
        store.saveSettings(value)
        mutable.update { it.copy(settings = value) }
        if (!value.voice) voice.stop()
    }
    fun clearHistory() {
        if (!state.value.canConfigure) return
        store.clearHistory(state.value.demo)
        mutable.update { it.copy(events = store.events(), reactions = store.reactions()) }
    }
    fun saveReport(uri: Uri, csv: Boolean = false) {
        if (!state.value.canConfigure) return
        val json = if (csv) store.exportCsv(state.value.demo) else store.export(state.value.demo)
        work {
            val result = runCatching {
                requireNotNull(app.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(json) }
            }
            message(if (result.isSuccess) "기록을 저장했습니다" else "저장 실패: ${result.exceptionOrNull()?.message}")
        }
    }
    fun shareReport(): String = CareReports.shareText(store.reactions(), store.events(), state.value.demo)
    fun message(text: String?) { mutable.update { it.copy(message = text) } }
    fun close() {
        if (closing) return
        foreground(false)
        closing = true
        onChanged = null
        main.removeCallbacksAndMessages(null)
        voice.close()
        // Finish restoration on the serialized worker, then release connections. Journal survives process death.
        actions.execute {
            try { coordinator.reconcile(emptyMap(), now()) }
            finally { live?.close(); actions.shutdown() }
        }
    }
}
