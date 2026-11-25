package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

/**
 * 2.2.8 (Bill, 2026-11-24: "Hermes will ask a question occasionally that does not render in chat for
 * you, so please fix that"): Hermes's classic CLI prompts, as its pane draws them, from
 * screens/hermes/derived-2.2.8-question (derived from hermes_cli's own program text; demo content).
 * Every state of the clarify panel and the dangerous-command approval, and the lines they leave once
 * answered.
 */
class HermesQuestion228Test {
    private fun screen(name: String) = checkNotNull(javaClass.getResourceAsStream("/screens/hermes/derived-2.2.8-question/$name.txt")) { name }
        .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun card(name: String) = detectPrompt(screen(name), blocked = false, agent = "hermes")

    @Test fun aSingleChoiceQuestionIsAGuardedCardAnsweredByItsDigit() {
        for ((name, focus) in listOf("clarify-single-first" to "1", "clarify-single-first-cr" to "1", "clarify-single-second" to "2", "clarify-single-other" to "3",
            "clarify-single-no-deadline" to "2")) {
            val p = checkNotNull(card(name)) { name }
            assertEquals(name, "Which branch should the demo build use?", p.question)
            assertEquals(name, listOf("main", "release/demo"), p.choices.map { it.label })
            assertEquals(name, listOf("1", "2"), p.choices.map { it.keys })
            assertEquals(name, focus, p.selected)
            assertTrue(name, p.hermesClarify && p.numberKeys && !p.terminalReview)
            // A digit locks the choice at once in Hermes: the card types only that digit.
            assertEquals(name, listOf("2"), menuKeys(p, p.choices[1]))
            assertTrue("$name waits whatever herdr says", hermesScreenWaiting(screen(name)))
        }
    }

    @Test fun theWholeQuestionShowsAndOtherIsLeftToTerminal() {
        val p = checkNotNull(card("clarify-long-question"))
        assertEquals("Before I publish the demo notes, which of the two prepared summaries should go at the top of the page that readers see first?",
            p.question)
        assertEquals(listOf("The short summary", "The detailed summary"), p.choices.map { it.label })
        assertTrue(p.choices.none { isTypedAnswerChoice(it.label) })
    }

    @Test fun inABatchOnlyTheActiveQuestionIsAsked() {
        val p = checkNotNull(card("clarify-batch-second"))
        assertEquals("Run the demo tests first?", p.question)
        assertEquals(listOf("Yes", "No"), p.choices.map { it.label })
        assertEquals("1", p.selected)
    }

    @Test fun typingMultiSelectAndOpenQuestionsShowTheQuestionAndLeaveTheAnswerToTerminal() {
        for ((name, q) in listOf("clarify-freetext" to "What should the demo release be called?",
            "clarify-single-other-typing" to "Which branch should the demo build use?",
            "clarify-multi-select" to "Which demo checks should run?")) {
            val p = checkNotNull(card(name)) { "$name renders a card" }
            assertEquals(name, q, p.question)
            assertTrue(name, p.choices.isEmpty() && p.terminalReview && p.hermesClarify)
            assertNull("$name: no keys from the card", menuKeys(p, Choice("1", "1", false)))
        }
    }

    @Test fun aClippedPanelNeverOffersKeys() {
        // The top of the body scrolled away: the header says two questions, one shows.
        val clipped = screen("clarify-batch-second").replace(Regex("""│ ✓ Which branch[^\n]*\n│     main[^\n]*\n"""), "")
        assertNotEquals(screen("clarify-batch-second"), clipped)
        val p = checkNotNull(detectPrompt(clipped, blocked = false, agent = "hermes"))
        assertTrue(p.choices.isEmpty() && p.terminalReview)
        // Options out of order, or two highlighted, are not a proven run either.
        val twice = screen("clarify-single-first").replace("│     2. release/demo", "│   ❯ 2. release/demo")
        assertTrue(checkNotNull(detectPrompt(twice, blocked = false, agent = "hermes")).choices.isEmpty())
    }

    @Test fun everyApprovalHighlightIsTheSameCard() {
        val all = listOf("Allow once", "Allow for this session", "Add to permanent allowlist", "Deny")
        for ((i, c) in listOf("once", "session", "always", "deny").withIndex()) {
            val p = checkNotNull(card("approval-selected-$c")) { c }
            assertEquals(c, all, p.choices.map { it.label })
            assertEquals(c, "${i + 1}", p.selected)
            assertTrue(hermesScreenWaiting(screen("approval-selected-$c")))
        }
        assertEquals(listOf("Allow once", "Deny"), checkNotNull(card("approval-once-deny")).choices.map { it.label })
    }

    @Test fun answeredPromptsLeaveNoCard() {
        for (name in listOf("resolved-approval-once", "resolved-approval-wrapped-word", "resolved-approval-denied-wrapped", "resolved-approval-timeout",
            "resolved-clarify", "resolved-clarify-timeout")) {
            assertNull(name, detectPrompt(screen(name), blocked = false, agent = "hermes"))
            assertNull("$name even when herdr still says blocked", detectPrompt(screen(name), blocked = true, agent = "hermes")
                ?.takeIf { it.choices.isNotEmpty() || it.hermesClarify })
            assertFalse(name, hermesScreenWaiting(screen(name)))
        }
    }

    @Test fun thePanelAloneWithoutItsHintAndInputRowIsNotTheClassicCard() {
        // The 1.4.9 audit's panel (no hint row, no composer) keeps its generic reading.
        val panel = screen("clarify-single-first").lines().filter { it.startsWith("╭") || it.startsWith("│") || it.startsWith("╰") }
            .joinToString("\n")
        assertNull(hermesClassicClarify(panel))
    }
}
