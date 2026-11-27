package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexQueueRedrawTest {
    @Test fun capturedViewportAndRecentTextDoNotParseAsAQuestion() {
        for (source in listOf("visible", "visible-ansi", "recent")) {
            val screen = capture(source)
            for (blocked in listOf(false, true)) {
                assertNull("$source, blocked=$blocked", detectPrompt(screen, blocked, "codex"))
            }
        }
    }

    @Test fun unmarkedQueuedContinuationCannotBecomeAConfirmationDuringRedraw() {
        val beforeQueue = capture("visible").substringBefore("• Queued follow-up inputs")
        val endsDuringRedraw = "• Queued follow-up inputs\n\n    Submit with unanswered questions?"
        val composer = "› Ask Codex to do anything"
        for (screen in listOf("$beforeQueue$endsDuringRedraw", "$beforeQueue$composer\n\n$endsDuringRedraw")) {
            assertNull(detectPrompt(screen, true, "codex"))
        }
    }

    @Test fun nativeDialogsStillParseOnEitherSideOfQueueActivity() {
        val queue = capture("visible").substringAfter("• Queued follow-up inputs")
            .substringBefore("› Ask Codex")
            .let { "• Queued follow-up inputs$it" }
        val approval = resource("/screens/codex/synthetic-2.0/multiline-command-approval.txt")
        for (screen in listOf("$queue\n$approval", "$approval\n$queue")) {
            assertTrue(detectPrompt(screen, true, "codex")?.choices?.isNotEmpty() == true)
        }
        for (screen in listOf("$queue\n$codexFreeformQuestion", "$codexFreeformQuestion\n$queue")) {
            assertTrue(detectPrompt(screen, true, "codex")?.codexQuestion == true)
        }
    }

    private fun capture(source: String) = resource("/screens/codex/derived-0.172.0-queued-input/$source.txt")

    private fun resource(path: String) = checkNotNull(javaClass.getResource(path)).readText()
}
