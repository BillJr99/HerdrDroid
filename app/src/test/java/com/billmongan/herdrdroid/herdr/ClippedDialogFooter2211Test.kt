package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClippedDialogFooter2211Test {
    @Test fun eachNativeFooterCanRequestTheMissingStart() {
        val options = "❯ 1. First\n  2. Second"
        val examples = mapOf(
            "claude" to "$options\nEnter to select · Tab/Arrow keys to navigate · Esc to cancel",
            "codex" to "$options\nPress enter to confirm or esc to cancel",
            "copilot" to "$options\n1–3 to select · ↑/↓ to navigate · enter to confirm · esc to cancel",
            "kiro" to "$options\ny allow once · n deny once · t create allow rule · d create deny rule",
            "vibe" to "│ Update now   Continue with current version │\n│ ←→ navigate Enter select │\n└───────────────────────────┘",
            "opencode" to "┃  Allow once   Always allow   Reject     ⇆ select  enter confirm",
            "agy" to "$options\n  ↑/↓ Navigate\nesc to cancel              Plan · Example",
            "grok" to "$options\n2/2:select",
            "hermes" to "↑/↓ to select, Enter to lock, Tab next question  (21s)\n? ❯",
            "omp" to "$options\n↑/↓ choose · ⏎ select · ⎋ deny",
        )
        examples.forEach { (agent, frame) ->
            assertTrue(agent, clippedDialogFooter(frame, agent))
            assertTrue(agent, checkNotNull(clippedDialogReview(frame, agent)).historyReviewOnly)
            assertFalse(agent, clippedDialogFooter("$frame\n> New input", agent))
        }
    }
}
