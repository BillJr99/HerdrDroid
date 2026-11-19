package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerDraftBarTest {
    @Test fun unframedComposerPreservesLiteralBars() {
        for (bar in listOf("|", "│", "┃")) {
            assertEquals(bar, inputLineOf("❯ $bar", "codex"))
        }
        assertEquals("command |", inputLineOf("────\n❯ command |\n────", "codex"))
        assertEquals("", inputLineOf("❯ ", "codex"))
    }

    @Test fun framedComposerRemovesOnlyTheClosingBorder() {
        for (bar in listOf("|", "│", "┃")) {
            assertEquals(bar, inputLineOf("$bar ❯ $bar $bar", "codex"))
            assertEquals("", inputLineOf("$bar ❯     $bar", "codex"))
        }
        assertEquals("|", inputLineOf("│ ❯ |│", "codex"))
        assertEquals("|", inputLineOf("│ ❯ |", "codex"))
    }
}
