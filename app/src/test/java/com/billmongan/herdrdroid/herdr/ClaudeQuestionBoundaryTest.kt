package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

/** Synthetic conversation text; layouts follow the captured Claude question screens. */
class ClaudeQuestionBoundaryTest {
    private fun screen(name: String) = javaClass.getResource("/screens/claude/synthetic-question-boundaries/$name.txt")!!.readText()
    private val live get() = screen("message-and-question")

    @Test fun questionOptionsComeOnlyFromTheBottomDialog() {
        for (agent in listOf("claude", null)) for (blocked in listOf(false, true)) {
            val prompt = detectPrompt(live, blocked, agent)!!
            assertEquals(listOf("Blue", "Orange", "Type something."), prompt.choices.map { it.label })
            assertEquals("Which color should the sample use?", prompt.shownParts().heading)
            assertFalse(prompt.question.contains("draft"))
            assertEquals("1", prompt.selected)
            assertTrue(isTypedAnswerChoice(prompt.choices.last().label))
        }
    }

    @Test fun numberedUserMessageAloneIsNotAQuestion() {
        for (agent in listOf("claude", null)) for (blocked in listOf(false, true)) {
            assertNull(detectPrompt(screen("message-only"), blocked, agent))
        }
    }

    @Test fun answeredDialogWithANewNumberedMessageIsNotLive() {
        val after = live + "\n❯ 1. Keep the draft.\n  2. Save the sample.\n"
        for (agent in listOf("claude", null)) for (blocked in listOf(false, true)) {
            assertNull(detectPrompt(after, blocked, agent))
        }
    }

    @Test fun aPastedFooterCannotReviveAnAnsweredDialog() {
        val after = live + "\n❯ 1. Keep the draft.\n  2. Save the sample.\n" +
            "Enter to select · Tab/Arrow keys to navigate · Esc to cancel\n"
        for (agent in listOf("claude", null)) {
            assertNull(detectPrompt(after, true, agent))
        }
    }

    @Test fun answeredDialogWithAgentOutputIsNotLive() {
        for (blocked in listOf(false, true)) {
            assertNull(detectPrompt(live + "\n● Updated the sample.\n❯ \n", blocked, "claude"))
        }
    }

    @Test fun unreadOptionsCannotBeBorrowedFromTheConversation() {
        val noOptions = live.substringBefore("❯ 1. Blue") +
            "Enter to select · Tab/Arrow keys to navigate · Esc to cancel\n"
        val prompt = detectPrompt(noOptions, false, "claude")
        assertTrue(prompt == null || prompt.choices.isEmpty())
    }
}
