package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

/** Synthetic screens for standalone hints, typed answers, and wrapped user questions. */
class ClaudeQuestionFooterReviewTest {
    private val card = """
        ←  ☐ Color  ✔ Submit  →
        Which color should the sample use?
        ❯ 1. Blue
          2. Orange
          3. Type something.
        Enter to select · Tab/Arrow keys to navigate · Esc to cancel
    """.trimIndent()
    private val echo = "❯ 1. Review the sample.\n  2. Save the draft."

    @Test fun ordinarySentencesMentioningHintActionsSettleTheOldCard() {
        for (action in listOf("select", "edit", "cancel", "confirm", "submit", "navigate")) {
            for (line in listOf("The sample is ready to $action.", "Enter to $action the next sample.")) {
                for (agent in listOf("claude", null)) {
                    assertNull(line, detectPrompt(card + "\n" + line, true, agent))
                }
            }
        }
        assertNull(detectPrompt(card + "\nEsc to cancel the sample.", true, "claude"))
    }

    @Test fun standaloneKeyboardHintsKeepTheLiveCard() {
        for (hint in listOf("Ctrl+g to edit in editor", "Esc to cancel", "Tab/Arrow keys to navigate · Esc to cancel")) {
            val prompt = detectPrompt(card + "\n" + hint, true, "claude")!!
            assertEquals(listOf("Blue", "Orange", "Type something."), prompt.choices.map { it.label })
        }
    }

    @Test fun typedAnswerRowsBelowTheFooterKeepTheLiveCard() {
        for (row in listOf("❯ Type your answer", "› Other: custom color")) {
            for (agent in listOf("claude", null)) {
                val prompt = detectPrompt(card + "\n" + row, true, agent)!!
                assertEquals(listOf("Blue", "Orange", "Type something."), prompt.choices.map { it.label })
            }
        }
    }

    @Test fun aWrappedQuestionInANumberedEchoDoesNotCreateAnEmptyCard() {
        for (screen in listOf(echo + "\n     Which sample should be used?", "❯ 1. Which sample should be used?\n  2. Keep the draft.")) {
            assertNull(detectPrompt(screen, true, "claude"))
        }
    }

    @Test fun aLaterQuestionStillGetsTheFallback() {
        for (question in listOf("Which sample should be used?", "  Which sample should be used?")) {
            val prompt = detectPrompt(echo + "\n\n" + question, true, "claude")!!
            assertEquals("Which sample should be used?", prompt.question)
            assertTrue(prompt.choices.isEmpty())
        }
    }

    @Test fun proseEndsTheEchoBeforeALaterIndentedQuestion() {
        for (prose in listOf("The sample is ready.", "  The sample is ready.")) {
            val question = "Which sample should be used?"
            val prompt = detectPrompt(echo + "\n" + prose + "\n     " + question, true, "claude")!!
            assertEquals(question, prompt.question)
            assertTrue(prompt.choices.isEmpty())
        }
    }

    @Test fun aBlankLineEndsTheEchoBeforeALaterNumberedQuestion() {
        val question = "1. Which sample should be used?"
        val prompt = detectPrompt(echo + "\n\n" + question, true, "claude")!!
        assertEquals(question, prompt.question)
        assertTrue(prompt.choices.isEmpty())
    }

}
