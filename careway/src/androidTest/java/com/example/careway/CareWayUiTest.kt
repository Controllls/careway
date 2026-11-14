package com.example.careway

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context

@RunWith(AndroidJUnit4::class)
class CareWayUiTest {
    // Set the scenario-test mode before Activity creation; retain the user's settings afterwards.
    @get:Rule(order = 0) val simulation = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("careway", Context.MODE_PRIVATE)
            val keys = listOf("live_input", "night", "rain", "tunnel", "voice")
            val previous = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
            prefs.edit().putBoolean("live_input", false).putBoolean("night", true).putBoolean("rain", true)
                .putBoolean("tunnel", true).putBoolean("voice", false).commit()
            try { base.evaluate() } finally {
                prefs.edit().apply { previous.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) } }.commit()
            }
        }
    } }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun useSimulationForScenarioTests() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("scenario_PARKED").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasTestTag("use_demo") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithTag("use_demo").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("use_demo").performClick()
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("scenario_PARKED").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun nightWarningCanBeResolvedAndRainScenarioAppliesControls() {
        rule.onNodeWithTag("scenario_NIGHT").performScrollTo().performClick()
        rule.onNodeWithTag("headlight_warning").assertIsDisplayed()
        rule.onNodeWithText("시연: 전조등 켜기").performClick()
        rule.onNodeWithTag("headlight_warning").assertDoesNotExist()
        rule.onNodeWithTag("scenario_RAIN").performScrollTo().performClick()
        rule.onNodeWithText("시연: 전조등 켜기").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("앞유리 김서림 제거 1", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("scenario_TUNNEL_EXIT").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("앞유리 스마트 글래스 2", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("scenario_CLEAR").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("앞유리 스마트 글래스 0", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun reactionIsMeasuredFromDisplayedStimulusAndAppearsInHistory() {
        // Reproduce the reported path: a driving demo previously disabled reaction start forever.
        rule.onNodeWithTag("scenario_NIGHT").performScrollTo().performClick()
        rule.onNodeWithText("확인 · 주행 화면").performClick()
        rule.onNodeWithText("세이프티 핏").performClick()
        rule.onNodeWithText("P단 확인").assertExists()
        rule.onNodeWithTag("reaction_idle").assertIsDisplayed()
        rule.onNodeWithTag("start_reaction").assertIsEnabled()
        rule.onNodeWithTag("animal_crossing_scene").assertIsDisplayed()
        rule.onNodeWithTag("stimulus").assertDoesNotExist()
        rule.onNodeWithTag("start_reaction").performClick()
        rule.waitUntil(6_000) { rule.onAllNodesWithTag("stimulus").fetchSemanticsNodes().isNotEmpty() }
        // A physical response cannot precede display; allow a realistic 250ms reaction in this UI test.
        Thread.sleep(250)
        rule.onNodeWithTag("demo_brake").performClick()
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("reaction_result").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("animal_crossing_scene").assertIsDisplayed()
        rule.onNodeWithTag("stimulus").assertIsDisplayed()
        rule.onNodeWithText("케어 기록").performClick()
        rule.onNodeWithText("시뮬레이션 반응 기록").assertIsDisplayed()
        rule.onAllNodesWithText("세이프티 핏", useUnmergedTree = true).onFirst().assertExists()
    }

    @Test fun dashboardStartPreparesParkedDemoAndStartsWithoutSecondClick() {
        rule.onNodeWithTag("scenario_DISCONNECTED").performScrollTo().performClick()
        rule.onNodeWithTag("open_start_reaction").performScrollTo().performClick()
        // Waiting is brief; on a loaded emulator it may already have advanced to the stimulus.
        rule.onNodeWithTag("start_reaction").assertIsNotEnabled()
        rule.onNodeWithTag("reaction_idle").assertDoesNotExist()
        rule.waitUntil(6_000) { rule.onAllNodesWithTag("stimulus").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(250)
        rule.onNodeWithTag("demo_brake").performClick()
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("reaction_result").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("reaction_blocker").assertTextContains("브레이크에서 발을 뗀 뒤", substring = true)
        rule.onNodeWithTag("start_reaction").assertIsNotEnabled()
        rule.onNodeWithTag("demo_brake").performClick()
        rule.onNodeWithTag("start_reaction").assertIsEnabled()
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            instrumentation.targetContext.filesDir.resolve("reaction-fixed.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun settingsAndReportDoNotRequireNetwork() {
        rule.onNodeWithText("설정", useUnmergedTree = true).performClick()
        rule.onNodeWithText("나에게 맞는 케어").assertIsDisplayed()
        rule.onNodeWithText("한국어 음성 안내").assertExists()
        rule.onNodeWithText("케어 기록").performClick()
        rule.onNodeWithText("기록 저장").assertIsDisplayed()
        rule.onNodeWithText("CSV 저장").assertExists()
        rule.onNodeWithText("리포트 공유").assertExists()
    }
}
