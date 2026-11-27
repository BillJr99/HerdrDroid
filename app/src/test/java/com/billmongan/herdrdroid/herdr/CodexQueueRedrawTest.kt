package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test fun knownQueueAndBackgroundActivityDoNotDrawAnUnreadCardFromStaleStatus() {
        val screen = capture("visible")
        val composer = screen.substringAfterLast("shift+← edit last queued message").trimStart()
        val status = screen.lineSequence().single { it.contains("background terminal running") }
        val interaction = screen.lineSequence().single { it.contains("Interacted with") }
        val titleOnly = "• Queued follow-up inputs"
        val counterOnly = "1 background terminal running · /ps to view"
        for (frame in listOf(screen, "$status\n$composer", "$interaction\n$composer",
            "$titleOnly\n\n$composer", "$composer\n\n$counterOnly")) {
            assertNull(detectPrompt(frame, true, "codex"))
            assertNull(withNeedsYouCard(Status.NEEDS_YOU, null, false, "Codex", frame))
        }

        // Keep the fallback when there is no live evidence, and keep a parsed real prompt.
        assertNotNull(withNeedsYouCard(Status.NEEDS_YOU, null, false, "Codex"))
        assertNotNull(withNeedsYouCard(Status.NEEDS_YOU, null, false, "Codex", "\n"))
        val question = checkNotNull(detectPrompt(codexFreeformQuestion, true, "codex"))
        assertSame(question, withNeedsYouCard(Status.NEEDS_YOU, question, false, "Codex", screen))
    }

    @Test fun unmarkedQueuedContinuationCannotBecomeAConfirmationDuringRedraw() {
        val beforeQueue = capture("visible").substringBefore("• Queued follow-up inputs")
        val endsDuringRedraw = "• Queued follow-up inputs\n\n    Submit with unanswered questions?"
        val composer = "› Ask Codex to do anything"
        for (screen in listOf("$beforeQueue$endsDuringRedraw", "$beforeQueue$composer\n\n$endsDuringRedraw")) {
            assertNull(detectPrompt(screen, true, "codex"))
        }
    }

    @Test fun clippedQueueOnlyViewportIsNotAnUnreadableScreen() {
        val block = "• Queued follow-up inputs" + capture("visible").substringAfter("• Queued follow-up inputs")
            .substringBefore("› Ask Codex")
        assertNull(detectPrompt(block, true, "codex"))
        assertNull(detectPrompt(block.substringAfter('\n'), true, "codex"))
        assertTrue(detectPrompt("", true, "codex")?.region == listOf(UNREAD_SCREEN))
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
