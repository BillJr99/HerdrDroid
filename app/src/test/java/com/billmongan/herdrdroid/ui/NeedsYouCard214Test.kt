package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.IdleMainLooperRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.herdr.withNeedsYouCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 2.1.4: the card a pane needing you gets when its question couldn't be read (Bill: "it showed the
 * orange you know waiting on you icon but didn't actually render the question in chat"), and the
 * parsed card for a helper's hook prompt, on screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1000dp")
class NeedsYouCard214Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    @Test fun theUnreadQuestionCardSaysSoAndOnlyOpensTheTerminal() {
        val p = checkNotNull(withNeedsYouCard(Status.NEEDS_YOU, null, suppressed = false, agent = "Claude"))
        var shown = 0
        val sent = mutableListOf<String>()
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                PromptQuestion(p, agent = "Claude")
                PromptCard(p, agent = "Claude", onChoice = { sent += it.keys }, onKey = { sent += it }, onTypeAnswer = { sent += "type" },
                    onShowTerminal = { shown++ })
            }
        }
        rule.onNodeWithText("Claude is waiting for you").assertIsDisplayed()
        rule.onNodeWithText("The app couldn't read this question from the screen", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Enter").assertDoesNotExist()
        rule.onNodeWithText("Show terminal").performClick()
        assertEquals(1, shown)
        assertEquals(emptyList<String>(), sent)
    }

    @Test fun aHelpersHookPromptShowsItsOptions() {
        val s = requireNotNull(javaClass.getResource("/screens/claude/derived-2.1.329-hook/hook-subagent-agent-list.txt")).readText()
        val p = requireNotNull(detectPrompt(s, true, "claude"))
        val picked = mutableListOf<String>()
        rule.setContent {
            PromptCard(withNeedsYouCard(Status.NEEDS_YOU, p, false, "Claude")!!, agent = "Claude",
                onChoice = { picked += it.keys }, onKey = {}, onTypeAnswer = {})
        }
        rule.onNodeWithText("Your answer").assertIsDisplayed()
        rule.onNodeWithText("No").performClick()
        assertEquals(listOf("2"), picked)
    }
}
