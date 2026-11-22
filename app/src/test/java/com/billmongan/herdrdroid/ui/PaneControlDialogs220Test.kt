package com.billmongan.herdrdroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.PaneControls
import com.billmongan.herdrdroid.herdr.PastSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 2.2.0: the Mode, Thinking and Sessions dialogs on screen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1200dp")
class PaneControlDialogs220Test {
    @get:Rule val rule = createComposeRule()

    @Test fun claudesBypassAsksFirst() {
        val picked = mutableListOf<String>()
        rule.setContent { ModeDialog("Claude", PaneControls.modes("claude")!!, "default", idle = true, onPick = { picked += it }, onDismiss = {}) }
        rule.onNodeWithText("Default (asks before edits and commands)  (now)").assertIsDisplayed()
        rule.onNodeWithText("Plan mode").performClick()
        rule.runOnIdle { assertEquals(listOf("plan"), picked) }
    }

    @Test fun bypassNeedsTheConfirmation() {
        val picked = mutableListOf<String>()
        rule.setContent { ModeDialog("Claude", PaneControls.modes("claude")!!, "default", idle = true, onPick = { picked += it }, onDismiss = {}) }
        rule.onNodeWithText("Bypass permissions (approves every tool)").performClick()
        rule.runOnIdle { assertEquals(emptyList<String>(), picked) }
        rule.onNodeWithText("Turn it on").performClick()
        rule.runOnIdle { assertEquals(listOf("bypass"), picked) }
    }

    @Test fun vibeNeverListsAutoApprove() {
        rule.setContent { ModeDialog("Vibe", PaneControls.modes("vibe")!!, "default", idle = true, onPick = {}, onDismiss = {}) }
        rule.onNodeWithText("Accept edits").assertIsDisplayed()
        rule.onNodeWithText("Auto approve (approves every tool)").assertDoesNotExist()
    }

    @Test fun thinkingSendsTheLevel() {
        val picked = mutableListOf<String>()
        rule.setContent { ThinkingDialog("Grok", PaneControls.thinking("grok")!!, "high", idle = true, onPick = { picked += it }, onDismiss = {}) }
        rule.onNodeWithText("Now: high").assertIsDisplayed()
        rule.onNodeWithText("xhigh").performClick()
        rule.runOnIdle { assertEquals(listOf("xhigh"), picked) }
    }

    @Test fun sessionsMarkTheTerminalsOneAndOfferANewSession() {
        val a = PastSession("/p/a.jsonl", null, 1, "Fix the parser", 1)
        val b = PastSession("/p/b.jsonl", null, 2, "Write the docs", 1)
        val events = mutableListOf<String>()
        rule.setContent {
            SessionsDialog("Claude", listOf(b, a), null, isCurrent = { it == a }, canSwitch = true, switchNote = null,
                herdrOutOfDate = true, idle = true, onNew = { events += "new" }, onPick = { events += it.ref }, onPicker = null, onDismiss = {})
        }
        rule.onNodeWithText("Fix the parser  (now)").assertIsDisplayed()
        rule.onNodeWithText("herdr out of date", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Write the docs").performClick()
        rule.onNodeWithText("＋ New session").performClick()
        rule.runOnIdle { assertEquals(listOf("/p/b.jsonl", "new"), events) }
    }
}
