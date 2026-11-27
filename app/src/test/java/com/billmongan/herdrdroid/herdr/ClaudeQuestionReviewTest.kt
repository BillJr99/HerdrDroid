package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

/** Neutral synthetic layouts for the question-boundary review findings. */
class ClaudeQuestionReviewTest {
    private val old = """
        ←  ☐ Color  ✔ Submit  →
        Which color should the sample use?
        ❯ 1. Blue
          2. Orange
          3. Type something.
        Enter to select · Tab/Arrow keys to navigate · Esc to cancel
    """.trimIndent()
    private val echo = "❯ 1. Review the sample.\n  2. Save the draft."
    private val approval = """
        Bash command
          echo sample
        Do you want to proceed?
        ❯ 1. Yes
          2. No (esc)
        Esc to cancel
    """.trimIndent()

    @Test fun clippedSingleQuestionWithOnlyNumberedDescriptionDoesNotCrash() {
        val screen = listOf(
            "     First line of the option description.",
            "     2. Nested step",
            "     Final line of the option description.",
            "  3. Type something.",
            "────────────────────────────────",
            "  4. Chat about this",
            "↑ 10 more lines",
            "Enter to select · Tab/Arrow keys to navigate · Esc to cancel",
        ).joinToString("\n")
        assertNull(claudeClippedAskReview(screen))
    }

    @Test fun clippedSingleQuestionPreservesNumberedDescriptionRows() {
        val nestedStep = "     2. Nested step"
        val screen = listOf(
            "     Tail of the previous option description.",
            nestedStep,
            "❯ 2. Keep the sample",
            nestedStep,
            "  3. Type something.",
            "────────────────────────────────",
            "  4. Chat about this",
            "↑ 10 more lines",
            "Enter to select · Tab/Arrow keys to navigate · Esc to cancel",
        ).joinToString("\n")
        val prompt = requireNotNull(claudeClippedAskReview(screen))
        assertTrue(prompt.terminalReview)
        assertEquals(2, requireNotNull(prompt.region).count { it == nestedStep })
        assertTrue(prompt.choices.isEmpty())
    }

    @Test fun highLiveApprovalBelowAnOldDialogIsStillDetected() {
        for (agent in listOf("claude", "copilot", null)) {
            val prompt = detectPrompt(old + "\n● Finished the earlier step.\n" + approval, true, agent)!!
            assertEquals(listOf("Yes", "No (esc)"), prompt.choices.map { it.label })
            assertEquals("Do you want to proceed?", prompt.shownParts().heading)
        }
    }

    @Test fun highNewQuestionTabsBelowAnOldDialogStayLive() {
        val fresh = old.replace("Color", "Size").replace("Which color should the sample use?", "Which size should the sample use?")
            .replace("Blue", "Small").replace("Orange", "Large")
        val prompt = detectPrompt(old + "\n" + echo + "\n" + fresh, true, "claude")!!
        assertEquals(listOf("Small", "Large", "Type something."), prompt.choices.map { it.label })
    }

    @Test fun mediumAnOrphanedFooterDoesNotAuthorizeAnEcho() {
        val footer = "Enter to select · Tab/Arrow keys to navigate · Esc to cancel"
        for (screen in listOf(footer + "\n" + echo, echo + "\n" + footer)) {
            for (blocked in listOf(false, true)) assertNull(detectPrompt(screen, blocked, "claude"))
        }
    }

    @Test fun mediumEscOnlyFootersEndTheOldDialogToo() {
        for (footer in listOf("Esc to cancel", "ESC TO REJECT")) {
            val screen = old.substringBefore("Enter to select") + footer + "\n" + echo
            assertNull(detectPrompt(screen, true, "claude"))
            val prompt = detectPrompt(screen + "\n" + approval, true, "claude")!!
            assertEquals(listOf("Yes", "No (esc)"), prompt.choices.map { it.label })
        }
    }

    @Test fun mediumLaterUnnumberedMenuSurvivesAnOlderEcho() {
        val screen = echo + "\nChoose a delivery mode?\n❯ Automatic\n  Manual\n"
        val prompt = detectPrompt(screen, true, "claude")!!
        assertEquals(listOf("Automatic", "Manual"), prompt.choices.map { it.label })
    }

    @Test fun mediumLaterButtonRowSurvivesAnOlderEcho() {
        val screen = echo + "\nAllow this command?\nAllow once   Always allow   Reject\n⇆ select\n"
        val prompt = detectPrompt(screen, true, "claude")!!
        assertEquals(listOf("Allow once", "Always allow", "Reject"), prompt.choices.map { it.label })
    }

    @Test fun mediumNonClaudeHeaderlessMenuIsNotRejectedAsAnEcho() {
        val prompt = detectPrompt("❯ 1. Inspect\n  2. Continue", true, "grok")!!
        assertEquals(listOf("Inspect", "Continue"), prompt.choices.map { it.label })
    }

    @Test fun mediumVisibleQuestionBelowAnEchoGetsTheQuestionFallback() {
        val prompt = detectPrompt(echo + "\nWhich sample should be used?", true, "claude")!!
        assertEquals("Which sample should be used?", prompt.question)
        assertTrue(prompt.choices.isEmpty())
    }

    @Test fun mediumVisibleApprovalHeaderAboveAListNeedsNoFooter() {
        val prompt = detectPrompt(approval.substringBefore("Esc to cancel"), true, "claude")!!
        assertEquals(listOf("Yes", "No (esc)"), prompt.choices.map { it.label })
    }
}
