package com.billmongan.herdrdroid

import com.billmongan.herdrdroid.herdr.codexFollowUpRows
import com.billmongan.herdrdroid.herdr.codexFreeformQuestion
import com.billmongan.herdrdroid.herdr.detectPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexQueuedInput228Test {
    @Test fun runningCodexQueueIsNeverAQuestion() {
        for (blocked in listOf(false, true)) {
            assertNull(detectPrompt(codexQueuedInputViewport, blocked, "codex"))
        }
    }

    @Test fun queuedTextCannotSupplyQuestionChromeOrOptions() {
        val screen = codexQueuedInputViewport.replace(
            "    1 of the README; the full test suite is allowed. Redo the report as it says.",
            "    › 1. Choose the first sample\n    2. Choose the second sample\n    Press enter to confirm or esc to cancel",
        )
        assertNull(detectPrompt(screen, true, "codex"))
    }

    @Test fun standaloneArrowIsNotAQueueAndClippedQueueUsesItsFooter() {
        assertTrue(codexFollowUpRows("  ↳ command text\n    more text").isEmpty())
        val clipped = codexQueuedInputViewport.substringAfter("• Queued follow-up inputs\n")
        assertTrue(codexFollowUpRows(clipped).containsAll((0..5).toList()))
        assertNull(detectPrompt(clipped, true, "codex"))
    }

    @Test fun realQuestionsAndApprovalsOnEitherSideOfTheQueueStillParse() {
        val queue = codexQueuedInputViewport.substringAfter("• Queued follow-up inputs").substringBefore("› Ask Codex")
            .let { "• Queued follow-up inputs$it" }
        val approval = checkNotNull(javaClass.getResource("/screens/codex/synthetic-2.0/multiline-command-approval.txt")).readText()
        for (screen in listOf("$queue\n$codexFreeformQuestion", "$codexFreeformQuestion\n$queue")) {
            assertTrue(detectPrompt(screen, false, "codex")?.codexQuestion == true)
        }
        for (screen in listOf("$queue\n$approval", "$approval\n$queue")) {
            val expected = checkNotNull(detectPrompt(approval, false, "codex"))
            assertEquals(expected.choices, detectPrompt(screen, false, "codex")?.choices)
            assertTrue(detectPrompt(screen, false, "codex")?.approvalIdentity == expected.approvalIdentity)
        }
    }
}

internal val codexQueuedInputViewport: String
    get() = checkNotNull(CodexQueuedInput228Test::class.java.getResource(
        "/screens/codex/derived-2.2.8-queued-inputs/queued-inputs.txt"
    )).readText()
