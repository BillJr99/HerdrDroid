package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeFourQuestions228Test {
    private fun screen(name: String): String = checkNotNull(javaClass.getResource(
        "/screens/claude/captured-2.1.332-four-questions/$name.txt"
    )).readText()

    private fun card(name: String): Prompt =
        checkNotNull(detectPrompt(screen(name), blocked = true, agent = "claude"))

    @Test fun narrowNativeFrameKeepsTheWholeQuestionAndProvesItsDialog() {
        val narrow = card("narrow-q1")
        val wide = card("q1")
        assertEquals(wide.question, narrow.question)
        assertEquals(wide.choices, narrow.choices)
        assertTrue(narrow.question.endsWith("This invented scenario compares?"))
        assertTrue(questionRead(screen("narrow-q1"), narrow, "claude", null, blocked = true).complete)
    }

    @Test fun nativeFourQuestionFlowKeepsCheckboxesAndReviewDistinct() {
        for (n in 1..3) {
            val question = card("q$n")
            assertFalse(question.multi)
            assertTrue(question.question.contains("Question $n"))
            assertEquals(5, question.shownParts().tabs.size)
            assertEquals(listOf("enter"), menuKeys(question, question.choices.first()))
        }

        val multi = card("q4")
        assertTrue(multi.multi)
        assertTrue(multi.question.contains("Question 4"))
        assertFalse(multi.terminalReview)
        assertEquals(5, multi.shownParts().tabs.size)
        val second = multi.choices.single { it.label == "[ ] Option 4.2" }
        assertEquals(listOf("down", "space"), menuKeys(multi, second))

        val toggled = card("q4-toggled")
        val checked = toggled.choices.single { it.label == "[✔] Option 4.2" }
        assertEquals(listOf("space"), menuKeys(toggled, checked))
        val next = checkNotNull(toggled.claudeCheckboxAction)
        assertEquals("Submit", next.label)
        assertEquals(listOf("down", "down", "down", "down", "enter"), menuKeys(toggled, next))
        val focused = card("q4-submit")
        assertEquals(listOf("enter"), menuKeys(focused, checkNotNull(focused.claudeCheckboxAction)))
        assertTrue(cardAnswerStillFits(toggled, focused))
        assertFalse(cardAnswerStillFits(multi, toggled))

        val review = card("review")
        val submit = review.choices.single { it.label == "Submit answers" }
        assertEquals(listOf("enter"), menuKeys(review, submit))
        assertFalse(cardAnswerStillFits(focused, review))
    }
}
