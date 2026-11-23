package com.billmongan.herdrdroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.readingQuestionCard
import com.billmongan.herdrdroid.herdr.withNeedsYouCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 2.2.2: the card shown while a question is still being read (its dialog not finished drawing, or
 * not yet read the same twice): it says so, offers Show terminal, and has no answers and no keys.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1000dp")
class ReadingQuestionCard222Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    @Test fun theReadingCardSaysSoAndOnlyOpensTheTerminal() {
        val p = checkNotNull(withNeedsYouCard(Status.NEEDS_YOU, readingQuestionCard("Claude"), suppressed = false, agent = "Claude"))
        var shown = 0
        val sent = mutableListOf<String>()
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                PromptQuestion(p, agent = "Claude")
                PromptCard(p, agent = "Claude", onChoice = { sent += it.keys }, onKey = { sent += it }, onTypeAnswer = { sent += "type" },
                    onTypedChoice = { sent += "typed" }, onShowTerminal = { shown++ })
            }
        }
        rule.onNodeWithText("Claude is asking a question", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Its answers show here once the question has finished drawing", substring = true).assertIsDisplayed()
        for (key in listOf("Enter", "Esc", "↑", "↓", "Space", "Type your answer")) rule.onNodeWithText(key).assertDoesNotExist()
        rule.onNodeWithText("Show terminal").performClick()
        assertEquals(1, shown)
        assertEquals(emptyList<String>(), sent)
    }
}
