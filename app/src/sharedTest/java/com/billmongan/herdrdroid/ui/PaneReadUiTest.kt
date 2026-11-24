package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.idleMainLooperRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.PaneCapture
import com.billmongan.herdrdroid.herdr.PaneReadFrame
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 2.2.7: the pane read dialog shows who asks for what, and only Yes reads; Pane reads lists the phone's captures. */
@RunWith(AndroidJUnit4::class)
class PaneReadUiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = idleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private val frame = PaneReadFrame("K7P3QX", "laptop", "codex", 80, "see why it stopped")

    @Test fun theDialogShowsRequesterTargetLinesAndReasonAndOnlyYesReads() {
        var yes = 0
        var no = 0
        var later = 0
        rule.setContent { PaneReadApprovalDialog("Claude on laptop2", frame, { yes++ }, { no++ }, { later++ }) }
        rule.onNodeWithText("Asked by: Claude on laptop2").assertIsDisplayed()
        rule.onNodeWithText("Read: codex on laptop").assertIsDisplayed()
        rule.onNodeWithText("Lines: the last 80 lines").assertIsDisplayed()
        rule.onNodeWithText("Reason: see why it stopped").assertIsDisplayed()
        rule.waitForIdle()
        assertEquals(listOf(0, 0, 0), listOf(yes, no, later))
        rule.onNodeWithText("No").performClick()
        assertEquals(listOf(0, 1, 0), listOf(yes, no, later))
        rule.onNodeWithText("Yes, read").performClick()
        assertEquals(listOf(1, 1, 0), listOf(yes, no, later))
    }

    @Test fun paneReadsListsCapturesOpensOneAndClears() {
        val c = PaneCapture("20261123-120000-K7P3QX.txt", "K7P3QX", "laptop", "codex", "w2:p3", "2026-11-23T12:00:00Z", 2,
            "Claude on laptop2", "s1", "w1:p1", "", "a\nb")
        val opened = mutableListOf<PaneCapture>()
        var cleared = 0
        rule.setContent { PaneReadsList(listOf(c), { opened += it }, { cleared++ }, {}) }
        rule.onNodeWithText("codex on laptop · 2 lines · 2026-11-23T12:00:00Z").performClick()
        assertEquals(listOf(c), opened)
        rule.onNodeWithText("Clear all").performClick()
        assertEquals(1, cleared)
    }

    @Test fun clearIsOffWithNoCaptures() {
        rule.setContent { PaneReadsList(emptyList(), {}, {}, {}) }
        rule.onNodeWithText("No pane reads on this phone.").assertIsDisplayed()
        rule.onNodeWithText("Clear all").assertIsNotEnabled()
    }
}
