package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.IdleMainLooperRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.PaneControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 2.2.2: the mode dialog for command-based switches (Codex, Copilot's Approvals). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1200dp")
class PaneControlDialogs222Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    @Test fun codexOffersItsProfilesWithoutKnowingTheCurrentOne() {
        val picked = mutableListOf<String>()
        rule.setContent { ModeDialog("Codex", PaneControls.modes("codex")!!, null, idle = true, onPick = { picked += it }, onDismiss = {}) }
        rule.onNodeWithText("Now: not shown").assertIsDisplayed()
        rule.onNodeWithText("Full Access (approves everything)").assertDoesNotExist()
        rule.onNodeWithText("Read Only (asks before edits and internet)").performClick()
        rule.runOnIdle { assertEquals(listOf(":read-only"), picked) }
    }

    @Test fun copilotApprovalsHasItsOwnTitleAndNoAllowAll() {
        val picked = mutableListOf<String>()
        rule.setContent { ModeDialog("Copilot", PaneControls.approvals("copilot")!!, "manual", idle = true, onPick = { picked += it }, onDismiss = {}) }
        rule.onNodeWithText("Approvals for Copilot").assertIsDisplayed()
        rule.onNodeWithText("Allow All (approves every request)").assertDoesNotExist()
        rule.onNodeWithText("The app types the agent's own command and reads the screen for the change.").assertIsDisplayed()
        rule.onNodeWithText("Assisted Permissions (a safety check approves safe requests)").performClick()
        rule.runOnIdle { assertEquals(listOf("assisted"), picked) }
    }

    @Test fun copilotModeGoesFromPlanBackToInteractiveOnly() {
        val picked = mutableListOf<String>()
        rule.setContent { ModeDialog("Copilot", PaneControls.modes("copilot")!!, "plan", idle = true, onPick = { picked += it }, onDismiss = {}) }
        rule.onNodeWithText("Plan  (now)").assertIsDisplayed()
        rule.onNodeWithText("Autopilot (keeps going on its own)").assertDoesNotExist()
        rule.onNodeWithText("Interactive").performClick()
        rule.runOnIdle { assertEquals(listOf("interactive"), picked) }
    }

    @Test fun copilotInAutopilotIsNotSwitchedFromTheMenu() {
        rule.setContent { ModeDialog("Copilot", PaneControls.modes("copilot")!!, "autopilot", idle = true, onPick = {}, onDismiss = {}) }
        rule.onNodeWithText("The app doesn't switch away from this setting; change it at the computer.").assertIsDisplayed()
    }
}
