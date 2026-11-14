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
        speedMps = Reading(15f, t), gear = Reading(Gear.DRIVE, t), parkingBrake = Reading(false, t),
        dark = Reading(dark, t), lowBeam = Reading(false, t), rain = Reading(rain, t))

    @Test fun `stale missing negative nonfinite and moving inputs never pass parking interlock`() {
        assertTrue(parked().parked(at))
        listOf(VehicleSnapshot(), parked().copy(speedMps = null), parked().copy(gear = null),
            parked().copy(parkingBrake = Reading(false, at)), parked().copy(speedMps = Reading(-2f, at)),
            parked().copy(speedMps = Reading(Float.NaN, at)), parked().copy(speedMps = Reading(Float.POSITIVE_INFINITY, at)),
            parked().copy(gear = Reading(Gear.NEUTRAL, at)), parked(at - 3_001), parked(at + 1))
            .forEach { assertFalse(it.parked(at)) }
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

    @Test fun `tunnel threshold is 200m and stale route does not retain control`() {
        val e = ContextEngine()
        fun snap(m: Double) = driving(dark = false).copy(tunnel = Reading(Tunnel(TunnelPhase.APPROACH, m), at))
        assertFalse(e.evaluate(snap(201.0), CareSettings(), at).tunnelActive)
        assertEquals(1, e.evaluate(snap(200.0), CareSettings(), at).desired[Control.RECIRCULATION])
        assertFalse(e.evaluate(snap(-1.0), CareSettings(), at).tunnelActive)
        assertFalse(e.evaluate(snap(Double.NaN), CareSettings(), at).tunnelActive)
        assertFalse(e.evaluate(snap(100.0), CareSettings(), at + 10_001).tunnelActive)
    }

    @Test fun `rain takes precedence over tunnel recirculation`() {
        val s = driving(rain = true).copy(tunnel = Reading(Tunnel(TunnelPhase.INSIDE), at))
        val p = ContextEngine().evaluate(s, CareSettings(), at)
        assertEquals(0, p.desired[Control.RECIRCULATION])
        assertTrue(p.alerts.any { it.id == "rain_tunnel" })
    }

    @Test fun `exit tint requires daylight approved level and at most 100m`() {
        fun snap(m: Double, dark: Boolean? = false) = driving(dark = false).copy(
            tunnel = Reading(Tunnel(TunnelPhase.EXIT, m), at), dark = dark?.let { Reading(it, at) })
        val e = ContextEngine()
        assertFalse(Control.GLASS_FRONT in e.evaluate(snap(80.0), CareSettings(), at).desired)
        assertFalse(Control.GLASS_FRONT in e.evaluate(snap(101.0), CareSettings(glassLevel = 2), at).desired)
        assertEquals(2, e.evaluate(snap(100.0), CareSettings(glassLevel = 2), at).desired[Control.GLASS_FRONT])
        assertFalse(Control.GLASS_FRONT in e.evaluate(snap(80.0, true), CareSettings(glassLevel = 2), at).desired)
        assertFalse(Control.GLASS_FRONT in e.evaluate(snap(80.0, null), CareSettings(glassLevel = 2), at).desired)
    }

    @Test fun `stuck glare stops tint after 15 seconds until condition clears`() {
        val e = ContextEngine(); val settings = CareSettings(glassLevel = 2)
        fun glare(t: Long, on: Boolean = true) = driving(t, dark = false).copy(glare = Reading(on, t))
        assertTrue(e.evaluate(glare(at), settings, at).glareActive)
        assertFalse(e.evaluate(glare(at + 15_001), settings, at + 15_001).glareActive)
        assertFalse(e.evaluate(glare(at + 16_000), settings, at + 16_000).glareActive)
        e.evaluate(glare(at + 17_000, false), settings, at + 17_000)
        assertTrue(e.evaluate(glare(at + 18_000), settings, at + 18_000).glareActive)
    }

    @Test fun `disabled features produce no corresponding commands`() {
        val s = driving(rain = true).copy(tunnel = Reading(Tunnel(TunnelPhase.INSIDE), at))
        val p = ContextEngine().evaluate(s, CareSettings(night = false, rain = false, tunnel = false), at)
        assertTrue(p.desired.isEmpty()); assertTrue(p.alerts.isEmpty())
    }

    @Test fun `reaction timer begins only after stimulus display acknowledgment`() {
        val r = ReactionEvaluator { emptyList() }
        assertTrue(r.start(parked(), at, 1_500))
        r.tick(parked(at + 1_500), at + 1_500)
        assertEquals(ReactionPhase.WAITING, r.state.phase)
        r.stimulusVisible(at + 1_700)
        r.tick(parked(at + 2_100).copy(brake = Reading(true, at + 2_100)), at + 2_100)
        assertEquals(400L, r.state.reactionMs)
        assertEquals(ReactionPhase.COMPLETE, r.state.phase)
    }

    @Test fun `reaction rejects brake held or early press and cannot double start`() {
        val r = ReactionEvaluator { emptyList() }
        assertFalse(r.start(parked().copy(brake = Reading(true, at)), at, 1_500))
        assertTrue(r.start(parked(), at, 1_500)); assertFalse(r.start(parked(), at, 1_500))
        r.tick(parked(at + 100).copy(brake = Reading(true, at + 100)), at + 100)
        assertEquals(ReactionPhase.EARLY, r.state.phase)
        assertNull(r.state.reactionMs)
    }

    @Test fun `reaction uses sensor receipt time instead of later UI refresh time`() {
        val r = ReactionEvaluator { emptyList() }
        r.start(parked(), at, 1_500); r.stimulusVisible(at + 1_500)
        r.tick(parked(at + 2_000).copy(brake = Reading(true, at + 1_850)), at + 2_000)
        assertEquals(350L, r.state.reactionMs)
    }

    @Test fun `post check ventilation never runs after moving or while tunnel air is isolated`() {
        val e = ContextEngine()
        assertEquals(1, e.evaluate(parked(), CareSettings(), at, ventilationRequested = true).desired[Control.HVAC_POWER])
        assertFalse(Control.HVAC_POWER in e.evaluate(driving(), CareSettings(), at, ventilationRequested = true).desired)
        val tunnel = parked().copy(tunnel = Reading(Tunnel(TunnelPhase.INSIDE), at))
        assertEquals(1, e.evaluate(tunnel, CareSettings(), at, ventilationRequested = true).desired[Control.RECIRCULATION])
    }

    @Test fun `interlock loss or background immediately cancels reaction`() {
        listOf(parked().copy(gear = Reading(Gear.DRIVE, at)), parked().copy(speedMps = Reading(.2f, at)),
            parked().copy(parkingBrake = Reading(false, at)), parked().copy(brake = null)).forEach { changed ->
            val r = ReactionEvaluator { emptyList() }; r.start(parked(), at, 1_500); r.tick(changed, at)
            assertEquals(ReactionPhase.CANCELLED, r.state.phase)
        }
        val r = ReactionEvaluator { emptyList() }; r.start(parked(), at, 1_500); r.tick(parked(), at, false)
        assertEquals(ReactionPhase.CANCELLED, r.state.phase)
    }

    @Test fun `reaction timeout is no response not an invented measurement`() {
        val r = ReactionEvaluator { emptyList() }; r.start(parked(), at, 1_500); r.stimulusVisible(at + 1_500)
        r.tick(parked(at + 6_500), at + 6_500)
        assertEquals(ReactionPhase.TIMEOUT, r.state.phase); assertNull(r.state.reactionMs); assertTrue(r.state.delayed)
    }

    @Test fun `personal baseline adjusts alert without blocking vehicle`() {
        val r = ReactionEvaluator { listOf(1_100, 1_200, 1_300) }
        r.start(parked(), at, 1_500); r.stimulusVisible(at + 1_500)
        r.tick(parked(at + 3_200).copy(brake = Reading(true, at + 3_200)), at + 3_200)
        assertEquals(1_700L, r.state.reactionMs); assertFalse(r.state.delayed)
    }

    @Test fun `no display acknowledgment cancels instead of fabricating reaction`() {
        val r = ReactionEvaluator { emptyList() }; r.start(parked(), at, 1_500)
        r.tick(parked(at + 3_501), at + 3_501)
        assertEquals(ReactionPhase.CANCELLED, r.state.phase)
    }

    @Test fun `simulation presets cover all four planned care situations`() {
        val d = DemoVehicle(); val e = ContextEngine()
        d.scenario = DemoScenario.NIGHT
        assertTrue(e.evaluate(d.snapshot(at), CareSettings(), at).alerts.any { it.urgent })
        d.scenario = DemoScenario.RAIN
        assertTrue(e.evaluate(d.snapshot(at), CareSettings(), at).raining)
        d.scenario = DemoScenario.TUNNEL_ENTRY
        assertTrue(e.evaluate(d.snapshot(at), CareSettings(), at).tunnelActive)
        d.scenario = DemoScenario.TUNNEL_EXIT
        assertTrue(e.evaluate(d.snapshot(at), CareSettings(glassLevel = 2), at).glareActive)
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
        val a = ActionCoordinator(g, MemoryStore()) { _, _ -> }
        a.reconcile(mapOf(c to 0), 0)
        assertTrue(a.statuses.getValue(c).startsWith("제어 보류"))
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
