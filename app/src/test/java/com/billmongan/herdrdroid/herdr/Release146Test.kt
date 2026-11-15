package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.6: an agent's placeholder in an empty input line is told from typing by how it is drawn.
 * The rows are the live composer rows read with `herdr pane read --format ansi` on 2026-11-15.
 */
class Release146Test {
    private val e = "\u001B"
    private val rule = "─".repeat(100)
    private fun boxed(row: String, footer: String = "  ? for shortcuts") = "output\r\n$rule\r\n$row\n$rule\r\n$footer\r\n"

    // Claude Code: a grey "❯" and a no-break space, then the hint in SGR 2.
    private val claudeHint = "$e[0m$e[38;2;153;153;153m❯ $e[0m$e[2mPress up to edit queued messages$e[0m\r"
    // Codex: a bold "› ", then the hint in SGR 2.
    private fun codexRow(text: String) = "$e[0m$e[1m› $e[0m$e[2m$text$e[0m\r"
    // Kiro: "›", the cursor (inverse space), then the hint in grey 158,158,158.
    private val kiroHint = "›  $e[0m$e[7m $e[0m$e[38;2;158;158;158mask a question or describe a task ↵$e[0m"
    // Hermes: its example prompt in gold italic.
    private val hermesHint = "$e[0m$e[38;5;230m❯ $e[0m$e[3m$e[38;5;136mFind and fix a failing test$e[0m\r"

    @Test fun dimPlaceholdersAreAnEmptyLine() {
        assertEquals("", inputLineOf(boxed(claudeHint), "claude"))
        assertEquals("", inputLineOf(boxed(codexRow("Ask Codex to do anything")), "codex"))
        // Bill's 1.4.6 report: any of Codex's suggestions, not only the ones the app knows by name.
        assertEquals("", inputLineOf(boxed(codexRow("Explain this codebase")), "codex"))
        assertEquals("", inputLineOf(boxed(codexRow("Find and fix a bug in @filename")), "codex"))
        assertEquals("", inputLineOf("$rule\nbill_default · auto · ◔ 2%   ~/agents/kiro\n$kiroHint", "kiro"))
        assertEquals("", inputLineOf(boxed(hermesHint), "hermes"))
        // With no agent known, a faint placeholder is still a placeholder.
        assertEquals("", inputLineOf(boxed(codexRow("Explain this codebase")), null))
    }

    @Test fun typedTextStillHolds() {
        // Normal type after Claude's prompt.
        assertEquals("half typed", inputLineOf(boxed("$e[0m$e[38;2;153;153;153m❯ $e[0mhalf typed$e[0m\r"), "claude"))
        // Typing followed by a dim suggestion (ghost text): typed.
        assertEquals("fix the failing test", inputLineOf(boxed("$e[0m$e[38;2;153;153;153m❯ $e[0mfix the$e[2m failing test$e[0m\r"), "claude"))
        // Muse's typed "/exit" in its blue.
        assertEquals("/exit", inputLineOf(boxed("$e[0m$e[38;2;90;160;255m❯ /exit$e[0m\r", "  muse-wonder-full-1.0 · high · ~/agents/muse"), "muse"))
        // Grok's typed text in its light grey (225), inside its frame.
        assertEquals("hello", inputLineOf("out\n╭$rule╮\n│ ❯ $e[38;2;225;225;225mhello$e[0m │\n╰$rule╯\n  Shift+Tab:mode", "grok"))
        // Italic counts as a placeholder only for Hermes.
        assertEquals("Find and fix a failing test", inputLineOf(boxed(hermesHint), "claude"))
        // Faint text that was reset to normal before the end: typed.
        assertEquals("Explain this codebase now", inputLineOf(boxed("$e[1m› $e[0m$e[2mExplain this codebase$e[22m now$e[0m"), "codex"))
    }

    @Test fun plainScreensKeepTheKnownHints() {
        assertEquals("", inputLineOf(boxed("❯ Press up to edit queued messages"), "claude"))
        assertEquals("Explain this codebase", inputLineOf(boxed("› Explain this codebase"), "codex"))
        assertFalse(isPlaceholderText("› Explain this codebase", "Explain this codebase", "codex"))
        assertTrue(isPlaceholderText(codexRow("Explain this codebase"), "Explain this codebase", "codex"))
    }
}
