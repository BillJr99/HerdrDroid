package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

class VibeRelease2211Test {
    private val capture: String
        get() = checkNotNull(javaClass.getResource("/screens/vibe/derived-2.2.11/release.ansi")).readText()

    @Test fun unclassifiedPaneShowsReleaseAndLiveFocusSetsExactArrowKeys() {
        val raw = capture
        val prompt = checkNotNull(detectPrompt(raw, blocked = false))
        assertEquals("A new Vibe release is available\n3.1.2 to 3.1.5", prompt.question)
        assertEquals(listOf("Update now", "Continue with current version"), prompt.choices.map(Choice::label))
        assertEquals(listOf("enter"), menuKeys(prompt, prompt.choices.first()))
        assertEquals(listOf("right", "enter"), menuKeys(prompt, prompt.choices.last()))
        val opposite = raw.replace("\u001B[48;5;7m Update now      \u001B[0mContinue with current version",
            " Update now      \u001B[48;5;7mContinue with current version\u001B[0m")
        val changed = checkNotNull(detectPrompt(opposite, blocked = false))
        assertEquals(listOf("left", "enter"), menuKeys(changed, changed.choices.first()))
        assertEquals(listOf("enter"), menuKeys(changed, changed.choices.last()))
        val plain = checkNotNull(detectPrompt(stripAnsi(raw), blocked = false))
        assertNull(menuKeys(plain, plain.choices.first()))
        assertNull(detectPrompt("$raw\n> ", blocked = false))
    }

    @Test fun differingControlSequenceInterpretationsCannotAuthorizeKeys() {
        val controls = listOf(
            "\u001B(B",
            "\u001B]8;;https://example.invalid\u001B\\extra\u001B]8;;\u0007",
        )
        for (control in controls) {
            val screen = capture.lineSequence().joinToString("\n") { row ->
                if ("Update now" in row) row + control else row
            }
            val prompt = checkNotNull(detectPrompt(screen, blocked = false))
            assertNull(prompt.selected)
            assertNull(menuKeys(prompt, prompt.choices.first()))
            assertNull(menuKeys(prompt, prompt.choices.last()))
        }
    }

    @Test fun unknownModalShowsTerminalReviewWithoutInventingFocus() {
        val prompt = checkNotNull(detectPrompt(capture.replace("Vibe release", "Tool choice "), blocked = false))
        assertTrue(prompt.terminalReview)
        assertTrue(prompt.choices.isEmpty())
    }

    @Test fun unknownStatusFallsBackToScreenDialogAndClearsWhenInputReturns() {
        val pane = Pane("p", "t", "w", "vibe", Status.UNKNOWN, "Shell", "/project", tabLabel = "vibe")
        assertEquals(Status.NEEDS_YOU, markScreenNeedsYou(listOf(pane), mapOf("p" to capture)).single().status)
        assertEquals(Status.UNKNOWN, markScreenNeedsYou(listOf(pane), mapOf("p" to "$capture\n> ")).single().status)
    }
}
