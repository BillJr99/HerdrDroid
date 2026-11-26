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

    @Test fun aSideBySidePreviewKeepsTheLabelsSeparateFromTheSample() {
        val screen = capture("preview")
        val parsed = checkNotNull(detectPrompt(screen, true, "claude", "2.1.332"))
        assertEquals(listOf("Wide", "Tall"), parsed.choices.map { it.label }.take(2))
        val preview = checkNotNull(claudeReviewPreview(screen, null))
        assertEquals("Which neutral sample layout should we use?", preview.question)
        assertTrue(preview.shownParts().details.containsAll(listOf("Wide", "Tall")))
        assertFalse(preview.shownParts().details.any { it.contains('┌') || it.contains('│') })
        assertTrue(preview.terminalReview)
        assertTrue(preview.choices.isEmpty())
    }

    @Test fun aPartialParseDoesNotHideVisibleOptionsOrWrappedQuestionText() {
        val screen = """
            ☐ Sample
            Which color should the sample use?
            It will appear in the header.
            ❯ 1. Blue
              2. Gold
            Enter to select · ↑/↓ to navigate · Esc to cancel
        """.trimIndent()
        val partial = Prompt("Which color should the sample use?", listOf(Choice("Blue", "1", true)))
        val preview = checkNotNull(claudeReviewPreview(screen, partial))
        val details = preview.shownParts().details.joinToString("\n")
        assertTrue(details.contains("It will appear in the header."))
        assertTrue(details.contains("Gold"))
        assertTrue(preview.terminalReview)
        assertTrue(preview.choices.isEmpty())
    }

    @Test fun aClippedDialogKeepsItsVisibleQuestionWithoutOfferableChoices() {
        val screen = """
            ☐ Sample
            Which color should the sample use?
            Enter to select · ↑/↓ to navigate · Esc to cancel
        """.trimIndent()
        val preview = checkNotNull(claudeReviewPreview(screen, null))
        assertEquals("Which color should the sample use?", preview.question)
        assertTrue(preview.terminalReview)
        assertTrue(preview.choices.isEmpty())
    }

    private val clippedOptions = """
          2. Blue
             With a wrapped description
        ❯ 3. Gold
          4. Type something.
        Enter to select · ↑/↓ to navigate · Esc to cancel
    """.trimIndent()

    @Test fun visibleOptionsWithoutTheTopOfTheAskDialogAreTerminalOnly() {
        val prompt = checkNotNull(detectPrompt(clippedOptions, true, "claude", "2.1.332"))
        assertTrue(prompt.terminalReview)
        assertTrue(prompt.choices.isEmpty())
        assertTrue(prompt.shownParts().details.any { it.contains("wrapped description") })
        assertTrue(prompt.shownParts().details.any { it.contains("Gold") })
        assertTrue(prompt.region.orEmpty().any { it.contains("Type something") })
        assertNull(menuKeys(prompt, Choice("Gold", "3", true)))
    }

    @Test fun staleClippedOptionsAndEchoesDoNotProduceAPreview() {
        assertNull(claudeReviewPreview(clippedOptions + "\n❯ A new request", null))
        assertNull(claudeReviewPreview("❯ A pasted list\n" + clippedOptions, null))
        assertNull(claudeReviewPreview(clippedOptions.replace("❯ 3.", "  3."), null))
        assertNull(claudeReviewPreview(clippedOptions.replace("❯ 3.", "❯ 5."), null))
        assertNull(claudeReviewPreview(clippedOptions.replaceFirst("2. Blue", "1. Blue"), null))
        val answered = capture("tabbed") + "\n" + clippedOptions
        assertNull(claudeClippedAskReview(answered))
    }

    @Test fun aClosedOrUnframedDialogDoesNotProduceAPreview() {
        val screen = capture("tabbed")
        assertNull(claudeReviewPreview(screen + "\n❯ A new request", null))
        assertNull(claudeReviewPreview("An ordinary list\n 1. Blue\n 2. Gold\nEsc to cancel", null))
    }
}
