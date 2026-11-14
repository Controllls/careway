package com.example.careway

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.pleos.playground.vehicle.Vehicle
import com.example.careway.core.*
import com.example.careway.data.CareStore
import com.example.careway.vehicle.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit emulator integration probe; never issues control requests on a physical vehicle. */
@RunWith(AndroidJUnit4::class)
class SdkIntegrationTest {
    @Test fun inspectOfficialInputsSpeechAndRecoverControls() = runBlocking {
        assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish") || Build.MODEL.contains("emulator", true))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = JSONObject().put("emulator_only", true).put("version", "3.0.0")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val live = withContext(Dispatchers.Main) { LiveVehicle(context, scope).also { it.start(context) } }
        try {
            delay(7_000)
            val now = SystemClock.elapsedRealtime()
            val s = live.state.value
            output.put("inputs", JSONObject().put("speed_mps", s.speedMps?.fresh(now))
                .put("gear", s.gear?.fresh(now)?.name).put("parking_brake", s.parkingBrake?.fresh(now))
                .put("brake", s.brake?.fresh(now)).put("brake_event_source", s.brakeFromEvent)
                .put("night", s.dark?.fresh(now)).put("low_beam", s.lowBeam?.fresh(now))
                .put("wiper", s.wiperLevel?.fresh(now)).put("ux_restricted", s.uxRestricted?.fresh(now))
                .put("route", s.tunnel?.fresh(now)?.toString()).put("reaction_ready", s.reactionReady(now)))
            output.put("input_problems", JSONObject(live.problems.value))
        } finally { withContext(Dispatchers.Main) { live.close() }; scope.cancel() }

        val speech = java.util.concurrent.CopyOnWriteArrayList<String>()
        val voice = withContext(Dispatchers.Main) { VoiceGuide(context) { speech += it } }
        try {
            delay(5_000)
            withContext(Dispatchers.Main) { voice.speak("꼼짝마차 음성 연결 확인입니다.") }
            delay(8_000)
        } finally { withContext(Dispatchers.Main) { voice.close() } }
        output.put("tts_events", JSONArray(speech))

        val vehicle = withContext(Dispatchers.Main) { Vehicle(context).also { it.initialize() } }
        val gateway = PleosGateway(vehicle)
        val journal = CareStore(context).ownership(false)
        val coordinator = ActionCoordinator(gateway, journal) { _, _ -> }
        val controls = JSONObject()
        val failures = mutableListOf<String>()
        try {
            withContext(Dispatchers.Main) { coordinator.reconcile(emptyMap(), SystemClock.elapsedRealtime()) }
            check(!coordinator.pendingRestoration) { "Existing restoration must complete before integration probe" }
            for (c in Control.entries) {
                val result = JSONObject()
                controls.put(c.name, result)
                try {
                    val cap = withContext(Dispatchers.Main) { gateway.capability(c) }
                    result.put("supported", cap.supported).put("reason", cap.reason).put("min", cap.min).put("max", cap.max)
                    if (!cap.supported || c in listOf(Control.GLASS_FRONT, Control.GLASS_ROOF)) continue
                    val original = withContext(Dispatchers.Main) { gateway.read(c) }
                    val target = if (original == cap.min) minOf(cap.max, cap.min + 1) else cap.min
                    result.put("original", original).put("target", target)
                    try {
                        withContext(Dispatchers.Main) { coordinator.reconcile(mapOf(c to target), SystemClock.elapsedRealtime()) }
                        val actual = withContext(Dispatchers.Main) { gateway.read(c) }
                        result.put("observed", actual).put("verified", actual == target)
                        result.put("status", coordinator.statuses[c])
                    } finally {
                        withContext(Dispatchers.Main) { coordinator.reconcile(emptyMap(), SystemClock.elapsedRealtime() + 60_000) }
                        val restored = withContext(Dispatchers.Main) { gateway.read(c) }
                        result.put("restored", restored)
                        if (restored != original) failures += "${c.name}: $restored != $original"
                    }
                } catch (e: Exception) { result.put("error", e.message) }
            }
        } finally {
            output.put("controls", controls).put("pending_restoration", coordinator.pendingRestoration)
                .put("restoration_errors", JSONArray(failures))
            context.filesDir.resolve("careway-v3-live.json").writeText(output.toString(2))
            withContext(Dispatchers.Main) { vehicle.release() }
        }
        assertTrue("Original settings must be restored: $failures", failures.isEmpty())
        assertFalse("Pending restoration is retained and must be investigated", coordinator.pendingRestoration)
    }
}
