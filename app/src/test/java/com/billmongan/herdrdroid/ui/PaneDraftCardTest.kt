package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.IdleMainLooperRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 2.1.0: the draft card sends only on a tap and clears only after a confirmation. */
@RunWith(AndroidJUnit4::class)
class PaneDraftCardTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private var sends = 0
    private var clears = 0
    private val cleared = mutableListOf<String>()
    private val text = androidx.compose.runtime.mutableStateOf("first line\nsecond line")

    private fun show(canAct: Boolean = true, canClear: Boolean = true, sendNote: String? = null,
                     verified: Boolean = true, holdNote: String? = null, blockedNote: String? = null) = rule.setContent {
        val t = text.value.takeIf { verified }
        PaneDraftCard("Hermes", draft = t, text = t, canAct = canAct, canClear = canClear, busy = null, sendNote = sendNote,
            onSend = { sends++ }, onClear = { clears++; cleared += it }, onShowTerminal = {}, holdNote = holdNote, blockedNote = blockedNote)
    }

    @Test fun confirmClearsTheCapturedDraftAndADraftChangeDismissesIt() {
        show()
        rule.onNodeWithTag("pane-draft-clear").performClick()
        rule.onNodeWithTag("pane-draft-confirm-text").assertIsDisplayed()
        rule.onNodeWithTag("pane-draft-confirm-clear").performClick()
        assertEquals(listOf("first line\nsecond line"), cleared)

        rule.onNodeWithTag("pane-draft-clear").performClick()
        rule.onNodeWithText("Clear the draft?").assertIsDisplayed()
        text.value = "something else"
        rule.waitForIdle()
        rule.onAllNodesWithText("Clear the draft?").assertCountEquals(0)
        assertEquals(1, clears)
    }

    @Test fun aSendNoteDisablesSendDraft() {
        show(sendNote = "Send draft is off: Enter could pick a highlighted command or suggestion instead.")
        rule.onNodeWithTag("pane-draft-send").assertIsNotEnabled()
        rule.onNodeWithTag("pane-draft-clear").assertIsDisplayed()
    }

    @Test fun clearAsksFirstAndKeepItDoesNothing() {
        show()
        rule.onNodeWithText("Draft in the pane").assertIsDisplayed()
        rule.onNodeWithText("first line\nsecond line").assertIsDisplayed()
        rule.onNodeWithTag("pane-draft-clear").performClick()
        rule.onNodeWithText("Clear the draft?").assertIsDisplayed()
        assertEquals(0, clears)
        rule.onNodeWithText("Keep it").performClick()
        assertEquals(0, clears)
        rule.onNodeWithTag("pane-draft-clear").performClick()
        rule.onNodeWithTag("pane-draft-confirm-clear").performClick()
        assertEquals(1, clears)
        assertEquals(0, sends)
        rule.onNodeWithText("Send draft").performClick()
        assertEquals(1, sends)
    }

    @Test fun noClearWithoutAKnownSafeClear() {
        show(canClear = false)
        rule.onNodeWithText("Send draft").assertIsDisplayed()
        rule.onNodeWithTag("pane-draft-clear").assertIsNotEnabled()
    }

    @Test fun anUnboundedDraftOffersDisabledActionsAndTheTerminal() {
        show(canAct = false)
        rule.onNodeWithText("Show terminal").assertIsDisplayed()
        rule.onNodeWithTag("pane-draft-send").assertIsNotEnabled()
        rule.onNodeWithTag("pane-draft-clear").assertIsNotEnabled()
    }

    @Test fun aHeldSendShowsActionsDisabledWhileTheDraftIsUnverified() {
        show(verified = false, holdNote = "A held message is waiting for this draft.")
        rule.onNodeWithText("A held message is waiting for this draft.").assertIsDisplayed()
        rule.onNodeWithText("Waiting for a verified view of the draft.").assertIsDisplayed()
        rule.onNodeWithTag("pane-draft-send").assertIsNotEnabled()
        rule.onNodeWithTag("pane-draft-clear").assertIsNotEnabled()
    }

    @Test fun aQueuedMessageOffersTheVerifiedDraftActions() {
        show(holdNote = "A queued message is waiting for this draft.")
        rule.onNodeWithText("A queued message is waiting for this draft.").assertIsDisplayed()
        rule.onNodeWithTag("pane-draft-send").performClick()
        rule.onNodeWithTag("pane-draft-clear").performClick()
        rule.onNodeWithTag("pane-draft-confirm-clear").performClick()
        assertEquals(1, sends)
        assertEquals(1, clears)
    }

    @Test fun anOpenQuestionShowsBothActionsButNeverTypesIntoIt() {
        show(verified = false, blockedNote = "Answer the open question before sending or clearing the draft.")
        rule.onNodeWithTag("pane-draft-send").assertIsNotEnabled()
        rule.onNodeWithTag("pane-draft-clear").assertIsNotEnabled()
        rule.onNodeWithText("Answer the open question before sending or clearing the draft.").assertIsDisplayed()
        rule.onAllNodesWithText("Clear the draft?").assertCountEquals(0)
        assertEquals(0, sends + clears)
    }
}
