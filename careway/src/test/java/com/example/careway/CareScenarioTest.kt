package com.example.careway

import com.example.careway.core.*
import com.example.careway.vehicle.DemoVehicle
import org.junit.Assert.*
import org.junit.Test

/** Scenario triggers induce inputs only; these tests drive the real engine and coordinator with them. */
class CareScenarioTest {
    private class Memory : OwnershipStore {
        var entries = emptyMap<Control, Ownership>()
        override fun load() = entries
        override fun save(value: Map<Control, Ownership>) { entries = value.toMap() }
    }
    private class Bench(private val settings: CareSettings = CareSettings()) {
        val vehicle = DemoVehicle()
        val original = vehicle.values.toMap()
        val engine = ContextEngine()
        val coordinator = ActionCoordinator(vehicle, Memory()) { _, _ -> }
        var situation = Situation()
        fun press(trigger: Trigger, at: Long) { situation = situation.after(trigger, at) }
        fun step(at: Long): CarePlan {
            situation = situation.settled(at)
            val plan = engine.evaluate(situation.apply(vehicle.snapshot(at), at), settings, at)
            coordinator.reconcile(plan.desired, at)
            return plan
        }
    }

    @Test fun nightDriveWithoutHeadlightsWarnsAndHeadlightTriggerClearsIt() {
        val b = Bench()
        b.press(Trigger.NIGHT_DRIVE, 0)
        // The vehicle is still pulling away: below the moving threshold there is no warning yet.
        assertTrue(b.step(0).alerts.none { it.urgent })
        val warned = b.step(Situation.RAMP_MS)
        assertEquals("headlights", warned.alerts.first().id)
        assertTrue(warned.alerts.first().urgent)
        assertEquals(0, b.vehicle.values[Control.AMBIENT])
        b.press(Trigger.HEADLIGHT_ON, 4000)
        assertTrue(b.step(4000).alerts.none { it.urgent })
        b.press(Trigger.RESET, 5000); b.step(5000)
        assertEquals(b.original, b.vehicle.values)
    }

    @Test fun vehicleLowBeamThatIsReallyOnWinsOverTheInducedOffState() {
        val vehicle = DemoVehicle().apply { headlights = true }
        val induced = Situation().after(Trigger.NIGHT_DRIVE, 0).apply(vehicle.snapshot(5000), 5000)
        assertEquals(true, induced.lowBeam?.value)
        assertTrue(ContextEngine().evaluate(induced, CareSettings(), 5000).alerts.none { it.urgent })
    }

    @Test fun rainStopRestoresOriginalValuesAfterTenSeconds() {
        val b = Bench()
        b.press(Trigger.RAIN_START, 0)
        val rain = b.step(0)
        assertTrue(rain.raining)
        assertTrue("rain scenario drives with headlights on", rain.alerts.none { it.urgent })
        assertEquals(1, b.vehicle.values[Control.DEFROST])
        assertEquals(0, b.vehicle.values[Control.RECIRCULATION])
        assertEquals(1, b.vehicle.values[Control.MIRROR_LEFT])
        assertEquals(1, b.vehicle.values[Control.MIRROR_RIGHT])
        assertEquals(0, b.vehicle.values[Control.AMBIENT])
        b.press(Trigger.RAIN_STOP, 1000)
        assertTrue(b.step(1000).raining)
        assertTrue(b.step(9999).raining)
        assertFalse(b.step(11001).raining)
        assertEquals(0, b.vehicle.values[Control.DEFROST])
        assertEquals(0, b.vehicle.values[Control.MIRROR_LEFT])
        b.press(Trigger.RESET, 12000); b.step(12000)
        assertEquals(b.original, b.vehicle.values)
    }

    @Test fun tunnelDriveSwitchesAirAtOneHundredMetersHoldsAfterEntranceAndRestores() {
        val b = Bench()
        b.press(Trigger.TUNNEL_DRIVE, 0)
        // 250 m before the entrance: announced by navigation but outside the 100 m window.
        assertFalse(b.step(0).tunnelActive)
        assertEquals(0, b.vehicle.values[Control.RECIRCULATION])
        var at = 0L; var sawWaiting = false; var sawRecirculationBeforeEntrance = false; var enteredAt = -1L
        while (at < 60_000) {
            at += 250
            val plan = b.step(at)
            val tunnel = b.situation.apply(b.vehicle.snapshot(at), at).tunnel?.value
            if (tunnel?.phase == TunnelPhase.APPROACH) {
                if (tunnel.distanceM!! > 100.0) { assertFalse(plan.tunnelActive); sawWaiting = true }
                else { assertEquals(1, b.vehicle.values[Control.RECIRCULATION]); sawRecirculationBeforeEntrance = true }
            } else if (enteredAt < 0) enteredAt = at
            assertTrue("tunnel scenario never raises the headlight warning", plan.alerts.none { it.urgent })
            if (enteredAt >= 0 && !plan.tunnelActive) break
            if (enteredAt >= 0) assertEquals("held after the entrance", 1, b.vehicle.values[Control.RECIRCULATION])
        }
        assertTrue(sawWaiting); assertTrue(sawRecirculationBeforeEntrance)
        assertNull("guidance ends at the entrance", b.situation.tunnelSince)
        assertTrue("released about 30 s after the entrance", at - enteredAt in ContextEngine.TUNNEL_HOLD_MS - 500..ContextEngine.TUNNEL_HOLD_MS + 500)
        assertEquals(b.original, b.vehicle.values)
    }

    @Test fun rainHasPriorityOverTunnelAndManualDefrostIsPreserved() {
        val b = Bench()
        b.press(Trigger.TUNNEL_DRIVE, 0); b.press(Trigger.RAIN_START, 0)
        val at = 8_000L // about 70 m before the entrance
        val plan = b.step(at)
        assertTrue(plan.raining && plan.tunnelActive)
        assertEquals(0, plan.desired[Control.RECIRCULATION])
        assertEquals("rain_tunnel", plan.alerts.first().id)
        b.vehicle.values[Control.DEFROST] = 0
        b.step(at + 1000); b.step(at + 2000)
        assertEquals(0, b.vehicle.values[Control.DEFROST])
        assertTrue(b.coordinator.statuses.getValue(Control.DEFROST).startsWith("운전자 설정 우선"))
    }

    @Test fun triggersNeverReplacePedalOrInterlockSignals() {
        val source = DemoVehicle().snapshot(0)
        var situation = Situation()
        for (trigger in Trigger.entries) {
            situation = situation.after(trigger, 50)
            val result = situation.apply(source, 100)
            assertEquals(source.brake, result.brake)
            assertEquals(source.brakeFromEvent, result.brakeFromEvent)
            assertEquals(source.uxRestricted, result.uxRestricted)
        }
        // Departure check and reset induce nothing: the vehicle's own speed, gear and parking signals decide.
        for (trigger in listOf(Trigger.IGNITION, Trigger.RESET)) {
            val cleared = Situation().after(Trigger.NIGHT_DRIVE, 0).after(trigger, 10)
            assertFalse(cleared.active)
            assertSame(source, cleared.apply(source, 100))
        }
    }

    @Test fun inducedDrivingNeverSatisfiesTheReactionInterlock() {
        val parked = DemoVehicle().snapshot(100)
        assertTrue(parked.reactionReady(100))
        for (trigger in listOf(Trigger.NIGHT_DRIVE, Trigger.RAIN_START, Trigger.TUNNEL_DRIVE)) {
            val driving = Situation().after(trigger, 0).apply(parked, 100)
            assertFalse(driving.reactionReady(100))
            assertFalse(ReactionEvaluator().start(driving, 100, 1_500))
        }
    }

    @Test fun tunnelGuidanceCountsDownToTheEntranceAndIsDescribed() {
        val steps = (0..20_000L step 250).map { Situation.tunnelAt(it) }
        assertEquals(Situation.APPROACH_M, steps.first().tunnel.distanceM!!, 0.001)
        val approach = steps.takeWhile { it.tunnel.phase == TunnelPhase.APPROACH }.map { it.tunnel.distanceM!! }
        assertEquals(approach.sortedDescending(), approach)
        assertTrue(approach.any { it <= 100.0 })
        assertTrue(steps.dropWhile { it.tunnel.phase == TunnelPhase.APPROACH }.all { it.finished && it.tunnel.phase == TunnelPhase.CLEAR })
        assertTrue(Situation().after(Trigger.TUNNEL_DRIVE, 0).describe(0).contains("터널 입구 250m 전"))
        assertEquals("", Situation().describe(0))
    }
}
