package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.IdleMainLooperRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.herdr.stripAnsi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 2.2.0: Claude's question pills as buttons that move the dialog, and as display only without a handler. */
@RunWith(AndroidJUnit4::class)
class QuestionPillsUi220Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private val screen = stripAnsi(checkNotNull(javaClass.getResource("/screens/claude/captured-2.1.329/styled/mq-moved-right1.ansi")).readText())
    private val p = checkNotNull(detectPrompt(screen, true, "claude"))

    @Test fun everyPillButTheCurrentOneIsAButton() {
        val taps = mutableListOf<Pair<Int, String>>()
        rule.setContent { PromptQuestion(p, agent = "Claude", screen = screen, onStep = { i, t -> taps += i to t.title }) }
        // Restart is current; Backup, Follow-up and Submit can be tapped.
        rule.onAllNodesWithTag("question-pill-tappable").assertCountEquals(3)
        rule.onAllNodesWithTag("question-pill").assertCountEquals(1)
        rule.onNodeWithText("Follow-up").performClick()
        rule.onNodeWithText("Submit").performClick()
        rule.onNodeWithText("Backup").performClick()
        // The current pill does nothing.
        rule.onNodeWithText("Restart").performClick()
        assertEquals(listOf(2 to "Follow-up", 3 to "Submit", 0 to "Backup"), taps)
    }

    @Test fun withoutAHandlerThePillsAreDisplayOnly() {
        rule.setContent { PromptQuestion(p, agent = "Claude", screen = screen) }
        rule.onAllNodesWithTag("question-pill-tappable").assertCountEquals(0)
        rule.onAllNodesWithTag("question-pill").assertCountEquals(4)
    }
}
