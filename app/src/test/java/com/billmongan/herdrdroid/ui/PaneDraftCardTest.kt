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

    private fun show(canAct: Boolean = true, canClear: Boolean = true) = rule.setContent {
        PaneDraftCard("Hermes", "first line\nsecond line", canAct, canClear, busy = null,
            onSend = { sends++ }, onClear = { clears++ }, onShowTerminal = {})
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
