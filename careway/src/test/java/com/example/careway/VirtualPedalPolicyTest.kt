package com.example.careway

import com.example.careway.core.*
import com.example.careway.vehicle.DemoVehicle
import org.junit.Assert.*
import org.junit.Test

class VirtualPedalPolicyTest {
    @Test fun blockedStartExplainsConditionAndNeverBypassesLiveInterlock() {
        val valid = DemoVehicle().snapshot(0)
        assertNull(valid.reactionStartBlocker(0))
        for (s in listOf(valid.copy(gear = Reading(Gear.DRIVE, 0)), valid.copy(speedMps = null),
            valid.copy(uxRestricted = null), valid.copy(brakeFromEvent = false),
            valid.copy(brake = null), valid.copy(brake = Reading(true, 0)))) {
            assertNotNull(s.reactionStartBlocker(0))
            assertFalse(ReactionEvaluator().start(s, 0, 1500))
        }
        assertNotNull(valid.reactionStartBlocker(3001))
    }
    @Test fun allLiveInputsRequireUxAndEvents() {
        val valid = DemoVehicle().snapshot(0)
        for (s in listOf(valid.copy(uxRestricted = null), valid.copy(uxRestricted = Reading(true, 0)),
            valid.copy(brakeFromEvent = false))) {
            assertFalse(ReactionEvaluator().start(s, 0, 1500))
        }
    }
    @Test fun losingEventInputCancelsInsteadOfRecordingPollingLatency() {
        val r = ReactionEvaluator()
        assertTrue(r.start(DemoVehicle().snapshot(0), 0, 1500))
        r.stimulusVisible(1500)
        r.tick(DemoVehicle().snapshot(1800).copy(brake = Reading(true, 1800), brakeFromEvent = false), 1800)
        assertEquals(ReactionPhase.CANCELLED, r.state.phase)
        assertNull(r.state.reactionMs)
    }
    @Test fun uxRestrictionCancelsImmediately() {
        val r = ReactionEvaluator()
        r.start(DemoVehicle().snapshot(0), 0, 1500)
        r.tick(DemoVehicle().snapshot(200).copy(uxRestricted = Reading(true, 200)), 200)
        assertEquals(ReactionPhase.CANCELLED, r.state.phase)
    }
}
