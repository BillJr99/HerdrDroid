package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ClippedDialogFooter2211Test {
    @Test fun eachNativeFooterCanRequestTheMissingStart() {
        val options = "❯ 1. First\n  2. Second"
        val examples = mapOf(
            "claude" to "❯ 2. First\n  3. Second\nEnter to select · Tab/Arrow keys to navigate · Esc to cancel",
            "codex" to "$options\nPress enter to confirm or esc to cancel",
            "copilot" to "$options\n1–3 to select · ↑/↓ to navigate · enter to confirm · esc to cancel",
            "kiro" to "$options\ny allow once · n deny once · t create allow rule · d create deny rule",
            "vibe" to "│ Update now   Continue with current version │\n│ ←→ navigate Enter select │\n└───────────────────────────┘",
            "opencode" to "┃  Allow once   Always allow   Reject     ⇆ select  enter confirm\n┃",
            "agy" to "$options\n  ↑/↓ Navigate\nesc to cancel              Plan · Example",
            "grok" to "$options\n2/2:select",
            "hermes" to "↑/↓ to select, Enter to lock, Tab next question  (21s)\n? ❯",
            "muse" to "$options\nChange approval defaults in /permissions",
            "omp" to "$options\n↑/↓ choose · ⏎ select · ⎋ deny",
        )
        examples.forEach { (agent, frame) ->
            assertTrue(agent, clippedDialogFooter(frame, agent))
            assertTrue(agent, checkNotNull(clippedDialogReview(frame, agent)).historyReviewOnly)
            assertFalse(agent, clippedDialogFooter("$frame\n> New input", agent))
        }
    }

    @Test fun grokPlanAndNumberedQuestionAlreadyHaveProvenStarts() = runBlocking {
        val plan = checkNotNull(javaClass.getResource("/screens/grok/derived-1.4.9/0306-plan-approval.txt")).readText()
        val numbered = "Which color should the sample use?\n❯ 1. Blue\n  2. Green\n1/2:select"
        for (frame in listOf(plan, numbered)) {
            assertTrue(clippedDialogFooter(frame, "grok"))
            assertFalse(isClippedDialogViewport(frame, "grok"))
            assertTrue(checkNotNull(detectPrompt(frame, true, "grok")).choices.isNotEmpty())
            completeClippedDialog(frame, true, "grok", null) { error("A complete dialog does not need history") }
        }
        assertTrue(isClippedDialogViewport(numbered.substringAfter('\n'), "grok"))
    }

    @Test fun clippedMuseQuotaKeepsItsNativeClosingRows() {
        val file = checkNotNull(javaClass.getResource("/screens/muse/derived-2.1.0/quota-fast-full.txt")).readText()
        val clipped = file.lines().dropWhile { !it.trimStart().startsWith("Switch to Spark") }.joinToString("\n")
        assertTrue(clippedDialogFooter(clipped, "muse"))
        assertTrue(isClippedDialogViewport(clipped, "muse"))
        assertFalse(isClippedDialogViewport(file, "muse"))
        assertFalse(clippedDialogFooter("$clipped\n> New input", "muse"))
    }

}
