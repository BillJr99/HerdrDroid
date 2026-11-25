package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeReviewPreview228Test {
    private fun capture(shape: String): String =
        checkNotNull(javaClass.getResource("/screens/claude/captured-2.1.332-$shape/screen.ansi")).readText()

    @Test fun capturedQuestionsExposeTheirTextEvenWithoutAnActionableParse() {
        val examples = listOf(
            Triple("single", "Which color should the sample use?", listOf("Blue", "Gold")),
            Triple("tabbed", "Which color?", listOf("Blue", "Gold")),
            Triple("multiselect", "Which toppings?", listOf("Salt", "Pepper")),
        )
        for ((shape, question, options) in examples) {
            val screen = capture(shape)
            val parsed = checkNotNull(detectPrompt(screen, true, "claude", "2.1.332"))
            assertTrue(shape, parsed.question.contains(question))
            assertTrue(shape, parsed.choices.any { it.label.contains("Type something") })

            val preview = checkNotNull(claudeReviewPreview(screen, null))
            assertEquals(shape, question, preview.question)
            assertTrue(shape, preview.terminalReview)
            assertTrue(shape, preview.choices.isEmpty())
            assertTrue(shape, preview.shownParts().details.any { it.contains("Type something") })
            assertTrue(shape, options.all { label -> preview.shownParts().details.any { it.contains(label) } })
            assertFalse(shape, cardAnswerStillFits(preview, parsed))
            assertNull(shape, menuKeys(preview, parsed.choices.first()))
        }
    }

    @Test fun aClosedOrUnframedDialogDoesNotProduceAPreview() {
        val screen = capture("tabbed")
        assertNull(claudeReviewPreview(screen + "\n❯ A new request", null))
        assertNull(claudeReviewPreview("An ordinary list\n 1. Blue\n 2. Gold\nEsc to cancel", null))
    }
}
