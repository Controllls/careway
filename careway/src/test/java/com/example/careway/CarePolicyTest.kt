package com.example.careway

import com.example.careway.core.*
import com.example.careway.vehicle.DemoScenario
import com.example.careway.vehicle.DemoVehicle
import org.junit.Assert.*
import org.junit.Test

class CarePolicyTest {
    private val at = 10_000L
    private fun parked(t: Long = at) = DemoVehicle().snapshot(t)
    private fun driving(t: Long = at, dark: Boolean = true, rain: Boolean = false) = parked(t).copy(
        speedMps = Reading(15f, t), gear = Reading(Gear.DRIVE, t),
        dark = Reading(dark, t), lowBeam = Reading(false, t), rain = Reading(rain, t))

    @Test fun `stale missing negative nonfinite and moving inputs never pass stationary interlock`() {
        assertTrue(parked().stationaryInPark(at))
        listOf(VehicleSnapshot(), parked().copy(speedMps = null), parked().copy(gear = null),
            parked().copy(speedMps = Reading(-2f, at)),
            parked().copy(speedMps = Reading(Float.NaN, at)), parked().copy(speedMps = Reading(Float.POSITIVE_INFINITY, at)),
            parked().copy(gear = Reading(Gear.NEUTRAL, at)), parked(at - 3_001), parked(at + 1))
            .forEach { assertFalse(it.stationaryInPark(at)) }
    }

    @Test fun `headlight alert requires motion darkness and confirmed off state`() {
        val e = ContextEngine(); val config = CareSettings()
        assertTrue(e.evaluate(driving(), config, at).alerts.any { it.id == "headlights" })
        listOf(parked(), driving(dark = false), driving().copy(lowBeam = null),
            driving().copy(lowBeam = Reading(true, at)), driving().copy(dark = null), driving(at - 3_001))
            .forEach { assertFalse(e.evaluate(it, config, at).alerts.any { a -> a.id == "headlights" }) }
        assertTrue(e.evaluate(driving().copy(speedMps = Reading(-4f, at)), config, at).alerts.any { it.id == "headlights" })
    }

    @Test fun `rain activates front and both side visibility controls`() {
        val p = ContextEngine().evaluate(driving(rain = true), CareSettings(), at)
        assertEquals(1, p.desired[Control.DEFROST]); assertEquals(0, p.desired[Control.RECIRCULATION])
        assertEquals(1, p.desired[Control.MIRROR_LEFT]); assertEquals(1, p.desired[Control.MIRROR_RIGHT])
    }

    @Test fun `rain debounce holds through brief wiper pause but not loss of data`() {
        val e = ContextEngine()
        e.evaluate(driving(rain = true), CareSettings(), at)
        assertTrue(e.evaluate(driving(at + 5_000, rain = false), CareSettings(), at + 5_000).raining)
        assertFalse(e.evaluate(driving(at + 5_100).copy(rain = null), CareSettings(), at + 5_100).raining)
        assertFalse(e.evaluate(driving(at + 10_001, rain = false), CareSettings(), at + 10_001).raining)
    }

    @Test fun `tunnel care starts 100m before the entrance and ignores invalid or stale distances`() {
        fun snap(m: Double) = driving(dark = false).copy(tunnel = Reading(Tunnel(TunnelPhase.APPROACH, m), at))
        assertFalse(ContextEngine().evaluate(snap(100.1), CareSettings(), at).tunnelActive)
        assertEquals(1, ContextEngine().evaluate(snap(100.0), CareSettings(), at).desired[Control.RECIRCULATION])
        assertEquals(1, ContextEngine().evaluate(snap(0.0), CareSettings(), at).desired[Control.RECIRCULATION])
        assertFalse(ContextEngine().evaluate(snap(-1.0), CareSettings(), at).tunnelActive)
        assertFalse(ContextEngine().evaluate(snap(Double.NaN), CareSettings(), at).tunnelActive)
        // A stale announcement never starts care.
        assertFalse(ContextEngine().evaluate(snap(50.0), CareSettings(), at + 10_001).tunnelActive)
    }

    @Test fun `tunnel care is held 30 seconds after the last entrance announcement then released`() {
        val e = ContextEngine()
        fun near(t: Long) = driving(t, dark = false).copy(lowBeam = Reading(true, t), tunnel = Reading(Tunnel(TunnelPhase.APPROACH, 40.0), t))
        fun passed(t: Long) = driving(t, dark = false).copy(lowBeam = Reading(true, t), tunnel = Reading(Tunnel(TunnelPhase.CLEAR), t))
        assertEquals("터널이 가까워지고 있어요", e.evaluate(near(at), CareSettings(), at).alerts.first().title)
        val inside = e.evaluate(passed(at + 1_000), CareSettings(), at + 1_000)
        assertEquals(1, inside.desired[Control.RECIRCULATION])
        assertEquals("터널을 지나고 있어요", inside.alerts.first().title)
        // Guidance lost inside the tunnel does not end the hold early.
        assertTrue(e.evaluate(passed(at + 20_000).copy(tunnel = null), CareSettings(), at + 20_000).tunnelActive)
        assertTrue(e.evaluate(passed(at + ContextEngine.TUNNEL_HOLD_MS), CareSettings(), at + ContextEngine.TUNNEL_HOLD_MS).tunnelActive)
        val after = e.evaluate(passed(at + ContextEngine.TUNNEL_HOLD_MS + 1), CareSettings(), at + ContextEngine.TUNNEL_HOLD_MS + 1)
        assertFalse(after.tunnelActive); assertFalse(Control.RECIRCULATION in after.desired)
    }

    @Test fun `rain takes precedence over tunnel recirculation`() {
        val s = driving(rain = true).copy(tunnel = Reading(Tunnel(TunnelPhase.APPROACH, 80.0), at))
        val p = ContextEngine().evaluate(s, CareSettings(), at)
        assertEquals(0, p.desired[Control.RECIRCULATION])
        assertTrue(p.alerts.any { it.id == "rain_tunnel" })
    }

    @Test fun `disabled features produce no corresponding commands`() {
        val s = driving(rain = true).copy(tunnel = Reading(Tunnel(TunnelPhase.APPROACH, 80.0), at))
        val p = ContextEngine().evaluate(s, CareSettings(night = false, rain = false, tunnel = false), at)
        assertTrue(p.desired.isEmpty()); assertTrue(p.alerts.isEmpty())
    }

    @Test fun `reaction timer begins only after stimulus display acknowledgment`() {
        val r = ReactionEvaluator()
        assertTrue(r.start(parked(), at, 1_500))
        r.tick(parked(at + 1_500), at + 1_500)
        assertEquals(ReactionPhase.WAITING, r.state.phase)
        r.stimulusVisible(at + 1_700)
        r.tick(parked(at + 2_100).copy(brake = Reading(true, at + 2_100)), at + 2_100)
        assertEquals(400L, r.state.reactionMs)
        assertEquals(ReactionPhase.REST, r.state.phase)
        assertEquals(listOf(400L), r.state.samples)
    }

    @Test fun `reaction rejects brake held or early press and cannot double start`() {
        val r = ReactionEvaluator()
        assertFalse(r.start(parked().copy(brake = Reading(true, at)), at, 1_500))
        assertTrue(r.start(parked(), at, 1_500)); assertFalse(r.start(parked(), at, 1_500))
        r.tick(parked(at + 100).copy(brake = Reading(true, at + 100)), at + 100)
        assertEquals(ReactionPhase.EARLY, r.state.phase)
        assertNull(r.state.reactionMs)
    }

    @Test fun `reaction uses sensor receipt time instead of later UI refresh time`() {
        val r = ReactionEvaluator()
        r.start(parked(), at, 1_500); r.stimulusVisible(at + 1_500)
        r.tick(parked(at + 2_000).copy(brake = Reading(true, at + 1_850)), at + 2_000)
        assertEquals(350L, r.state.reactionMs)
    }

    @Test fun `post check ventilation never runs after moving or while tunnel air is isolated`() {
        val e = ContextEngine()
        assertEquals(1, e.evaluate(parked(), CareSettings(), at, ventilationRequested = true).desired[Control.HVAC_POWER])
        assertFalse(Control.HVAC_POWER in e.evaluate(driving(), CareSettings(), at, ventilationRequested = true).desired)
        val tunnel = parked().copy(tunnel = Reading(Tunnel(TunnelPhase.APPROACH, 50.0), at))
        assertEquals(1, e.evaluate(tunnel, CareSettings(), at, ventilationRequested = true).desired[Control.RECIRCULATION])
    }

    @Test fun `interlock loss or background immediately cancels reaction`() {
        listOf(parked().copy(gear = Reading(Gear.DRIVE, at)), parked().copy(speedMps = Reading(.2f, at)),
            parked().copy(brake = null)).forEach { changed ->
            val r = ReactionEvaluator(); r.start(parked(), at, 1_500); r.tick(changed, at)
            assertEquals(ReactionPhase.CANCELLED, r.state.phase)
        }
        val r = ReactionEvaluator(); r.start(parked(), at, 1_500); r.tick(parked(), at, false)
        assertEquals(ReactionPhase.CANCELLED, r.state.phase)
    }

    @Test fun `reaction timeout is no response not an invented measurement`() {
        val r = ReactionEvaluator(); r.start(parked(), at, 1_500); r.stimulusVisible(at + 1_500)
        r.tick(parked(at + 6_500), at + 6_500)
        assertEquals(ReactionPhase.TIMEOUT, r.state.phase); assertNull(r.state.reactionMs); assertTrue(r.state.delayed)
    }

    /** Runs one full check with the given reaction times and returns the final state. */
    private fun session(vararg reactions: Long): ReactionState {
        val r = ReactionEvaluator { 1_500L }
        var t = at
        assertTrue(r.start(parked(t), t, 1_500))
        reactions.forEachIndexed { i, ms ->
            t += 1_500; r.tick(parked(t), t); r.stimulusVisible(t)
            assertEquals(ReactionPhase.STIMULUS, r.state.phase); assertEquals(i + 1, r.state.trial)
            t += ms; r.tick(parked(t).copy(brake = Reading(true, t)), t)
            if (i < reactions.lastIndex) {
                assertEquals(ReactionPhase.REST, r.state.phase)
                // Still pressed, or released too early: the next trial is not armed yet.
                t += 500; r.tick(parked(t).copy(brake = Reading(true, t)), t); assertEquals(ReactionPhase.REST, r.state.phase)
                t += 100; r.tick(parked(t), t); assertEquals(ReactionPhase.REST, r.state.phase)
                t += 500; r.tick(parked(t), t); assertEquals(ReactionPhase.WAITING, r.state.phase)
            }
        }
        return r.state
    }

    @Test fun `three reactions are averaged and 0_5 seconds or less is normal`() {
        val s = session(400, 500, 600)
        assertEquals(ReactionPhase.COMPLETE, s.phase)
        assertEquals(listOf(400L, 500L, 600L), s.samples)
        assertEquals(500L, s.reactionMs); assertEquals(Condition.NORMAL, s.condition); assertFalse(s.delayed)
        assertTrue(s.message.contains("0.50초"))
    }

    @Test fun `average between the two limits is caution and does not strengthen alerts`() {
        val s = session(450, 501, 552)
        assertEquals(501L, s.reactionMs); assertEquals(Condition.CAUTION, s.condition); assertFalse(s.delayed)
        assertEquals(Condition.CAUTION, session(900, 999, 1_098).condition)
    }

    @Test fun `average of one second or more is slow and strengthens alerts`() {
        val s = session(900, 1_000, 1_100)
        assertEquals(1_000L, s.reactionMs); assertEquals(Condition.SLOW, s.condition); assertTrue(s.delayed)
        assertTrue(s.message.contains("전방 주의 알림 모드를 강화합니다"))
    }

    @Test fun `early press or lost interlock between trials never yields a result`() {
        val early = ReactionEvaluator { 1_500L }
        early.start(parked(), at, 1_500); early.stimulusVisible(at + 1_500)
        early.tick(parked(at + 1_900).copy(brake = Reading(true, at + 1_900)), at + 1_900)
        early.tick(parked(at + 3_000), at + 3_000)
        assertEquals(ReactionPhase.WAITING, early.state.phase); assertEquals(2, early.state.trial)
        early.tick(parked(at + 3_100).copy(brake = Reading(true, at + 3_100)), at + 3_100)
        assertEquals(ReactionPhase.EARLY, early.state.phase); assertNull(early.state.condition)

        val moved = ReactionEvaluator { 1_500L }
        moved.start(parked(), at, 1_500); moved.stimulusVisible(at + 1_500)
        moved.tick(parked(at + 1_900).copy(brake = Reading(true, at + 1_900)), at + 1_900)
        assertEquals(ReactionPhase.REST, moved.state.phase)
        moved.tick(parked(at + 2_500).copy(gear = Reading(Gear.DRIVE, at + 2_500)), at + 2_500)
        assertEquals(ReactionPhase.CANCELLED, moved.state.phase); assertNull(moved.state.reactionMs)
    }

    @Test fun `no display acknowledgment cancels instead of fabricating reaction`() {
        val r = ReactionEvaluator(); r.start(parked(), at, 1_500)
        r.tick(parked(at + 3_501), at + 3_501)
        assertEquals(ReactionPhase.CANCELLED, r.state.phase)
    }

    @Test fun `simulation presets cover the planned care situations`() {
        val d = DemoVehicle(); val e = ContextEngine()
        d.scenario = DemoScenario.NIGHT
        assertTrue(e.evaluate(d.snapshot(at), CareSettings(), at).alerts.any { it.urgent })
        d.scenario = DemoScenario.RAIN
        assertTrue(e.evaluate(d.snapshot(at), CareSettings(), at).raining)
        d.scenario = DemoScenario.TUNNEL_ENTRY
        assertTrue(e.evaluate(d.snapshot(at), CareSettings(), at).tunnelActive)
    }
}

class ActionCoordinatorTest {
    private class MemoryStore : OwnershipStore {
        var data = emptyMap<Control, Ownership>()
        override fun load() = data
        override fun save(value: Map<Control, Ownership>) { data = value.toMap() }
    }
    private class Gateway : VehicleGateway {
        var value = 65; var supported = true; var fail = false; var acknowledgeWithoutApplying = false
        val writes = mutableListOf<Int>(); var reads = 0
        override fun capability(control: Control) = Capability(supported, 0, 100, "unsupported")
        override fun read(control: Control): Int { reads++; if (fail) error("offline"); return value }
        override fun write(control: Control, value: Int) {
            if (fail) error("offline")
            writes += value
            if (!acknowledgeWithoutApplying) this.value = value
        }
    }
    private val c = Control.AMBIENT
    @Test fun `one write for repeated input then restores original`() = run {
        val g = Gateway(); val store = MemoryStore(); val a = ActionCoordinator(g, store) { _, _ -> }
        repeat(5) { a.reconcile(mapOf(c to 0), it * 1_000L) }
        assertEquals(listOf(0), g.writes); assertEquals(Ownership(65, 0, true), store.data[c])
        a.reconcile(emptyMap(), 6_000)
        assertEquals(listOf(0, 65), g.writes); assertTrue(store.data.isEmpty())
    }
    @Test fun `manual override is neither fought nor restored away`() = run {
        val g = Gateway(); val a = ActionCoordinator(g, MemoryStore()) { _, _ -> }
        a.reconcile(mapOf(c to 0), 0); g.value = 80
        a.reconcile(mapOf(c to 0), 1_000); a.reconcile(mapOf(c to 0), 2_000); a.reconcile(emptyMap(), 3_000)
        assertEquals(listOf(0), g.writes); assertEquals(80, g.value)
        a.reconcile(mapOf(c to 0), 4_000)
        assertEquals(listOf(0, 0), g.writes)
    }
    @Test fun `unavailable unsupported and invalid values never send writes`() = run {
        val g = Gateway(); val a = ActionCoordinator(g, MemoryStore()) { _, _ -> }
        g.supported = false; a.reconcile(mapOf(c to 0), 0)
        g.supported = true; a.reconcile(mapOf(c to 101), 16_000)
        g.fail = true; a.reconcile(mapOf(c to 0), 32_000)
        assertTrue(g.writes.isEmpty())
    }
    @Test fun `failures back off and recover without duplicate spam`() = run {
        val g = Gateway(); val a = ActionCoordinator(g, MemoryStore()) { _, _ -> }
        g.fail = true; a.reconcile(mapOf(c to 0), 0)
        g.fail = false; a.reconcile(mapOf(c to 0), 14_999)
        assertTrue(g.writes.isEmpty())
        a.reconcile(mapOf(c to 0), 15_000); assertEquals(listOf(0), g.writes)
    }
    @Test fun `process restart recovers previously changed settings`() = run {
        val g = Gateway(); val store = MemoryStore()
        ActionCoordinator(g, store) { _, _ -> }.reconcile(mapOf(c to 0), 0)
        val restarted = ActionCoordinator(g, store) { _, _ -> }
        restarted.reconcile(emptyMap(), 1_000)
        assertEquals(65, g.value); assertFalse(restarted.pendingRestoration)
    }
    @Test fun `readback mismatch is reported as failure not successful control`() = run {
        val g = Gateway(); g.acknowledgeWithoutApplying = true
        val store = MemoryStore(); val a = ActionCoordinator(g, store) { _, _ -> }
        a.reconcile(mapOf(c to 0), 0)
        assertTrue(a.statuses.getValue(c).startsWith("반영 대기"))
        assertFalse(store.data.getValue(c).confirmed)
    }
    @Test fun `slow vehicle is confirmed by later readback without resending or blocking`() = run {
        val g = Gateway(); g.acknowledgeWithoutApplying = true
        val store = MemoryStore(); val a = ActionCoordinator(g, store) { _, _ -> }
        a.reconcile(mapOf(c to 0), 0)
        // Within the resend window the request is only watched, never repeated.
        for (t in 1_000L..14_000L step 1_000) a.reconcile(mapOf(c to 0), t)
        assertEquals(listOf(0), g.writes)
        g.value = 0 // the vehicle applies the request late
        a.reconcile(mapOf(c to 0), 15_000)
        assertTrue(store.data.getValue(c).confirmed)
        assertTrue(a.statuses.getValue(c).startsWith("적용 확인"))
        assertEquals(listOf(0), g.writes)
    }
    @Test fun `unapplied request is sent again after the resend window`() = run {
        val g = Gateway(); g.acknowledgeWithoutApplying = true
        val a = ActionCoordinator(g, MemoryStore()) { _, _ -> }
        a.reconcile(mapOf(c to 0), 0); a.reconcile(mapOf(c to 0), 14_000)
        assertEquals(listOf(0), g.writes)
        a.reconcile(mapOf(c to 0), 15_000)
        assertEquals(listOf(0, 0), g.writes)
        assertTrue(a.statuses.getValue(c).contains("2회"))
    }
    @Test fun `slow restoration completes by readback and is not mistaken for driver override`() = run {
        val g = Gateway(); val store = MemoryStore(); val a = ActionCoordinator(g, store) { _, _ -> }
        a.reconcile(mapOf(c to 0), 0)
        g.acknowledgeWithoutApplying = true
        a.reconcile(emptyMap(), 1_000)
        assertTrue(a.statuses.getValue(c).startsWith("복원 대기")); assertTrue(a.pendingRestoration)
        a.reconcile(emptyMap(), 2_000)
        assertEquals(listOf(0, 65), g.writes)
        g.value = 65 // the vehicle restores late
        a.reconcile(emptyMap(), 3_000)
        assertFalse(a.pendingRestoration)
        assertTrue(a.statuses.getValue(c).startsWith("복원 완료"))
    }
    @Test fun `cancelled pending request queues the original value behind it`() = run {
        val g = Gateway(); g.acknowledgeWithoutApplying = true
        val a = ActionCoordinator(g, MemoryStore()) { _, _ -> }
        a.reconcile(mapOf(c to 0), 0)
        a.reconcile(emptyMap(), 2_000)
        assertEquals(listOf(0, 65), g.writes); assertFalse(a.pendingRestoration)
    }
    @Test fun `foreground or condition changes during read prevent new writes`() = run {
        val g = Gateway(); val a = ActionCoordinator(g, MemoryStore()) { _, _ -> }
        a.reconcile(mapOf(c to 0), 0) { _, _ -> false }
        assertTrue(g.writes.isEmpty()); assertFalse(a.pendingRestoration)
    }
    @Test fun `already correct setting is not claimed and is not later restored`() = run {
        val g = Gateway(); g.value = 0
        val a = ActionCoordinator(g, MemoryStore()) { _, _ -> }
        a.reconcile(mapOf(c to 0), 0); a.reconcile(emptyMap(), 1_000)
        assertTrue(g.writes.isEmpty()); assertFalse(a.pendingRestoration)
    }

    @Test fun `acknowledged but unapplied write retries rather than being mistaken for driver override`() = run {
        val g = Gateway(); g.acknowledgeWithoutApplying = true
        val store = MemoryStore(); val a = ActionCoordinator(g, store) { _, _ -> }
        a.reconcile(mapOf(c to 0), 0)
        assertFalse(store.data.getValue(c).confirmed)
        g.acknowledgeWithoutApplying = false
        a.reconcile(mapOf(c to 0), 15_000)
        assertEquals(0, g.value); assertTrue(store.data.getValue(c).confirmed)
        assertFalse(a.statuses.getValue(c).contains("운전자 설정"))
    }

    @Test fun `unapplied pending write is cleared without touching original on restart`() = run {
        val store = MemoryStore(); store.data = mapOf(c to Ownership(65, 0, false))
        val g = Gateway(); val a = ActionCoordinator(g, store) { _, _ -> }
        a.reconcile(emptyMap(), 0)
        assertTrue(g.writes.isEmpty()); assertFalse(a.pendingRestoration)
    }
}
