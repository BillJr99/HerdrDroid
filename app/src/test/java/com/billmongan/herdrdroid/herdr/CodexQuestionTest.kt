package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

// From rust-v0.169.0 request_user_input snapshots (right padding omitted), not an incident capture:
// https://github.com/openai/codex/tree/rust-v0.169.0/codex-rs/tui/src/bottom_pane/request_user_input/snapshots
internal val codexFreeformQuestion = """
    Question 1/1 (1 unanswered)
    Share details.

    › Type your answer (optional)

    enter to submit answer | esc to interrupt
""".trimIndent()

internal val codexWrappedQuestion = """
    Question 1/2 (2 unanswered)
    Choose an option.

      1. Option 1  First choice.
    › 2. Option 2  Second choice.
      3. Option 3  Third choice.

    tab to add notes | enter to submit answer
    ←/→ to navigate questions | esc to interrupt
""".trimIndent()

class CodexQuestionTest {
    @Test fun nativeQuestionIsVisibleWithoutBlockedStatusAndDoesNotOfferKeys() {
        for ((frame, question) in listOf(codexFreeformQuestion to "Share details.", codexWrappedQuestion to "Choose an option.")) {
            val card = detectPrompt(frame, false, "codex")
            assertNotNull("native Codex question was missing in Chat", card)
            assertEquals(question, card!!.question)
            assertTrue(card.terminalReview)
            assertTrue(card.choices.isEmpty())
            assertNull(menuKeys(card, Choice("Option 2", "2", false)))
        }
    }

    @Test fun oldNativeQuestionDoesNotHideNewerApprovalOrBecomeActionableWithoutItsFooter() {
        val approval = checkNotNull(javaClass.getResource(
            "/screens/codex/synthetic-2.0/multiline-command-approval.txt")).readText()
        val expected = checkNotNull(detectPrompt(approval, false, "codex"))
        assertTrue(expected.choices.isNotEmpty())
        for (old in listOf(codexWrappedQuestion, codexWrappedQuestion.substringBefore("\ntab to add notes"))) {
            assertEquals(expected, detectPrompt("$old\n\n$approval", false, "codex"))
            assertNull(detectPrompt("$old\n\n› Next request", false, "codex"))
        }
        assertTrue(detectPrompt(codexWrappedQuestion.substringBefore("\ntab to add notes"), false, "codex")
            ?.choices.orEmpty().isEmpty())
    }
}