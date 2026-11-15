package com.example.careway

import android.app.Instrumentation
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import com.example.careway.core.*
import com.example.careway.vehicle.*
import com.example.careway.data.*
import org.json.JSONObject
import org.json.JSONArray

/** Framework-only device test runner, launched with adb am instrument. */
class NativeTestRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private lateinit var activity: MainActivity
    private var count=0
    private fun ui(block:()->Unit)=runOnMainSync(block)
    private fun await(message:String,timeout:Long=6000, condition:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<until) {
            var okay=false; ui { okay=condition() }; if(okay)return; Thread.sleep(25)
        }
        error(message)
    }
    private fun click(tag:String) { ui {
        val v=activity.screen.findViewWithTag<View>(tag) ?: error("Missing view: $tag")
        check(v.isEnabled) { "Disabled view: $tag" }; check(v.performClick())
    } }
    /** Presses the virtual pedal on each of the three stimuli, releasing it between trials. */
    private fun completeThreeTrials() {
        fun phase()=activity.model.state.value.reaction.phase
        repeat(3) { i ->
            await("Animal draw must acknowledge stimulus ${i+1}",9000) { phase()==ReactionPhase.STIMULUS && activity.model.state.value.reaction.trial==i+1 }
            Thread.sleep(250); click("demo_brake")
            if(i<2) { await("Trial ${i+1} recorded") { phase()==ReactionPhase.REST }; click("demo_brake") }
        }
        await("Three-trial completion") { phase()==ReactionPhase.COMPLETE }
    }
    private fun passed(name:String) { count++; sendStatus(0,Bundle().apply { putString("stream","PASS $name\n") }) }
    override fun onStart() {
        var code=Activity.RESULT_OK
        val output=Bundle()
        val prefs=targetContext.getSharedPreferences("careway",Context.MODE_PRIVATE)
        val keys=listOf("live_input","voice","night","rain","tunnel")
        val saved=keys.associateWith { if(prefs.contains(it)) prefs.getBoolean(it,false) else null }
        try {
            prefs.edit().putBoolean("live_input",false).putBoolean("voice",false).putBoolean("night",true).putBoolean("rain",true).putBoolean("tunnel",true).commit()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            await("Activity ready") { activity.hasWindowFocus() && activity.model.state.value.foreground && activity.model.state.value.capabilities.size==Control.entries.size }
            // Start from driving/disconnected simulation, then use the actual dashboard button.
            ui { activity.model.scenario(DemoScenario.DISCONNECTED) }
            click("open_start_reaction")
            completeThreeTrials()
            ui {
                val result=activity.model.state.value.reaction
                check(result.samples.size==3 && result.samples.all { it in 100L..5000L })
                check(result.reactionMs==result.samples.sum()/3 && result.condition==Condition.of(result.reactionMs!!))
                check(!activity.screen.findViewWithTag<Button>("start_reaction").isEnabled)
            }
            click("demo_brake")
            await("Release enables restart") { activity.screen.findViewWithTag<Button>("start_reaction").isEnabled }
            uiAutomation.takeScreenshot()?.let { bitmap ->
                targetContext.filesDir.resolve("native-reaction.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            passed("reaction draw / pedal / result / restart")

            click("start_reaction"); click("demo_brake")
            await("Early input rejected") { activity.model.state.value.reaction.phase==ReactionPhase.EARLY }
            click("demo_brake"); click("start_reaction")
            ui { activity.model.foreground(false) }
            await("Background cancels") { activity.model.state.value.reaction.phase==ReactionPhase.CANCELLED }
            ui { activity.model.foreground(true) }
            passed("early input and foreground cancellation")

            ui { activity.screen.selectPage(0); activity.model.scenario(DemoScenario.RAIN); activity.model.demoHeadlights(true) }
            await("Rain applied",16000) { activity.model.state.value.values[Control.DEFROST]==1 }
            ui { activity.model.scenario(DemoScenario.RAIN_TUNNEL); activity.model.demoHeadlights(true) }
            await("Rain keeps fresh air near tunnel") { activity.model.state.value.plan.tunnelActive && activity.model.state.value.values[Control.RECIRCULATION]==0 }
            ui { activity.model.scenario(DemoScenario.CLEAR) }
            // Rain care deliberately remains active for 10 seconds after the last wet signal.
            await("Controls restored after rain hold",16000) { activity.model.state.value.values[Control.DEFROST]==0 && activity.model.state.value.values[Control.MIRROR_LEFT]==0 }
            passed("rain / tunnel priority / restoration")

            // Scenario triggers induce the situation only; warning, control and restoration run on the feature path.
            fun state()=activity.model.state.value
            click("open_scenarios"); click("trigger_NIGHT_DRIVE")
            await("Full-screen headlight warning",8000) { activity.screen.warningView?.findViewWithTag<View>("headlight_warning")?.isShown==true }
            ui { check(state().situation.active && state().plan.alerts.first().id=="headlights") }
            await("Night ambient dimming",8000) { state().values[Control.AMBIENT]==0 }
            uiAutomation.takeScreenshot()?.let { bitmap ->
                targetContext.filesDir.resolve("native-headlight-warning.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            ui { check(activity.screen.warningView!!.findViewWithTag<View>("warning_headlight_on").performClick()) }
            await("Headlight trigger clears warning") { activity.screen.warningView==null && state().plan.alerts.none { it.urgent } }
            click("trigger_RAIN_START")
            await("Rain trigger controls",16000) { val v=state().values
                v[Control.DEFROST]==1 && v[Control.RECIRCULATION]==0 && v[Control.MIRROR_LEFT]==1 && v[Control.MIRROR_RIGHT]==1 && v[Control.AMBIENT]==0 }
            click("trigger_TUNNEL_DRIVE")
            await("Rain keeps fresh air inside tunnel window",16000) { state().plan.raining && state().plan.tunnelActive && state().values[Control.RECIRCULATION]==0 }
            click("trigger_RESET"); click("trigger_TUNNEL_DRIVE")
            await("Recirculation 100 m before tunnel entrance",16000) { state().plan.tunnelActive && state().values[Control.RECIRCULATION]==1 &&
                state().snapshot.tunnel?.value?.let { it.phase==TunnelPhase.APPROACH && it.distanceM!!<=100.0 }==true }
            await("Recirculation held after the entrance",16000) { state().situation.tunnelSince==null && state().plan.tunnelActive && state().values[Control.RECIRCULATION]==1 }
            uiAutomation.takeScreenshot()?.let { bitmap ->
                targetContext.filesDir.resolve("native-scenarios.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            await("Tunnel hold ends and restores settings",40000) { !state().plan.tunnelActive && state().values[Control.RECIRCULATION]==0 && state().values[Control.DEFROST]==0 }
            click("trigger_RESET")
            await("Reset returns to vehicle signals",8000) { !state().situation.active && state().values[Control.AMBIENT]==65 }
            click("trigger_IGNITION")
            await("Departure popup") { activity.screen.startupDialog?.isShowing==true }
            ui { check(activity.screen.startupDialog!!.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()) }
            await("Popup starts measurement") { activity.screen.page==1 && state().reaction.phase in listOf(ReactionPhase.WAITING,ReactionPhase.STIMULUS) }
            completeThreeTrials()
            click("demo_brake")
            passed("scenario triggers: headlight warning / rain / tunnel entrance / departure check")

            ui { activity.screen.selectPage(2) }
            check(CareStore(targetContext).reactions().any { it.demo && it.durationMs!=null })
            ui { activity.screen.selectPage(3) }
            storeMigration()
            passed("history settings and isolated store migration/export")

            val controller=activity.model
            val monitor=addMonitor(MainActivity::class.java.name,null,false)
            ui { activity.screen.selectPage(2); activity.recreate() }
            activity=waitForMonitorWithTimeout(monitor,8000) as? MainActivity ?: error("Activity recreation failed")
            removeMonitor(monitor)
            await("Retained state after recreation") { activity.model===controller && activity.screen.page==2 && activity.model.state.value.foreground }
            passed("Activity recreation retains controller and page")

            // Verify the rewritten SDK worker path using read-only live inputs.
            ui { activity.model.switchMode(false) }
                await("AAOS and Pleos reads",40000) {
                    val s=activity.model.state.value.snapshot; val at=SystemClock.elapsedRealtime()
                    !activity.model.state.value.busy && !activity.model.state.value.demo &&
                    s.speedMps?.fresh(at)!=null && s.gear?.fresh(at)!=null && s.brake?.fresh(at)!=null
                }
                val s=activity.model.state.value.snapshot
                // Care starts only after settings left by an earlier session are restored, which a slow vehicle can delay.
                await("Vehicle connection starts care by itself",60000) { activity.model.state.value.enabled }
                ui { activity.model.enable(false); check(!activity.model.state.value.enabled) { "Explicit stop must apply" } }
                Thread.sleep(1500)
                ui { check(!activity.model.state.value.enabled) { "Automatic start must run once per connection" } }
                targetContext.filesDir.resolve("native-live.json").writeText(JSONObject().put("speed",s.speedMps?.value)
                    .put("gear",s.gear?.value?.name).put("brake",s.brake?.value)
                    .put("problems",JSONObject(activity.model.state.value.problems)).toString(2))
            ui { activity.model.switchMode(true) }
            await("Return to demo releases old SDK connection",16000) { activity.model.state.value.demo && !activity.model.state.value.busy }
            passed("AAOS / Pleos worker reads and mode switch")
            ui { activity.screen.selectPage(0) }
            uiAutomation.takeScreenshot()?.let { bitmap ->
                targetContext.filesDir.resolve("native-dashboard.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            output.putString("stream","OK ($count tests)\n")
        } catch(e:Throwable) {
            code=Activity.RESULT_CANCELED
            var diagnostic=""
            if(::activity.isInitialized) ui { diagnostic="focus=${activity.hasWindowFocus()}, foreground=${activity.model.state.value.foreground}, reaction=${activity.model.state.value.reaction}" }
            output.putString("stream","FAILURES!!!\n$diagnostic\n${e.stackTraceToString()}")
        } finally {
            if(::activity.isInitialized) ui { activity.finish() }
            prefs.edit().apply { saved.forEach { (k,v)->if(v==null)remove(k) else putBoolean(k,v) } }.commit()
        }
        finish(code,output)
    }
    private fun storeMigration() {
        val context=object:ContextWrapper(targetContext) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("native_test_$name",mode)
        }
        fun clear() { for(name in listOf("careway","careway_state_v2")) context.getSharedPreferences(name,0).edit().clear().commit() }
        clear()
        try {
            val at=System.currentTimeMillis()
            val event=JSONObject().put("at",at).put("title","v2 preserved").put("detail","migration").put("demo",false)
            context.getSharedPreferences("careway_state_v2",0).edit().putString("events",JSONArray().put(event).toString()).commit()
            val store=CareStore(context); check(store.events().size==1); check(CareStore(context).events().size==1)
            store.addReaction(ReactionRecord(at,450,false,false)); store.addReaction(ReactionRecord(at,900,false,true))
            store.addEvent(CareEvent(at,"demo only","",true))
            val export=JSONObject(store.export(false)); check(export.getJSONArray("events").length()==1)
            check(export.getJSONArray("reactions").getJSONObject(0).getInt("ms")==450)
            check(!store.exportCsv(false).contains("900")); store.clearHistory(true)
            check(store.reactions().size==1 && store.events().single().title=="v2 preserved")
        } finally { clear() }
    }
}
