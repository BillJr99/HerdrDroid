package com.billmongan.herdrdroid.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
    @get:Rule val rule = createComposeRule()

    private var sends = 0
    private var clears = 0
    private val cleared = mutableListOf<String>()
    private val text = androidx.compose.runtime.mutableStateOf("first line\nsecond line")

    private fun show(canAct: Boolean = true, canClear: Boolean = true, sendNote: String? = null) = rule.setContent {
        val t = text.value
        PaneDraftCard("Hermes", draft = t, text = t, canAct = canAct, canClear = canClear, busy = null, sendNote = sendNote,
            onSend = { sends++ }, onClear = { clears++; cleared += it }, onShowTerminal = {})
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

    @Test fun aSendNoteReplacesSendDraft() {
        show(sendNote = "Send draft is off: Enter could pick a highlighted command or suggestion instead.")
        rule.onAllNodesWithText("Send draft").assertCountEquals(0)
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
        rule.onAllNodesWithText("Clear draft").assertCountEquals(0)
    }

    @Test fun anUnboundedDraftOffersOnlyTheTerminal() {
        show(canAct = false)
        rule.onNodeWithText("Show terminal").assertIsDisplayed()
        rule.onAllNodesWithText("Send draft").assertCountEquals(0)
        rule.onAllNodesWithText("Clear draft").assertCountEquals(0)
    }
}
