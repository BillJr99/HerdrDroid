package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.1.1 (Bill, 2026-11-20: "don't see the grey suggestion template text as active text"): every
 * agent pane on laptop2 read with its styles while its input box was empty. None of them is a draft,
 * and none reads as typing; the same boxes with text typed in them are drafts.
 */
class PlaceholderCaptureTest {
    private val e = "\u001B"
    private fun screen(path: String) = checkNotNull(javaClass.getResource("/screens/$path")).readText()
    private fun live(agent: String) = screen("$agent/live-2026-11-20-composer/empty.ansi")

    /** Herdr's agent name for each capture (opencode-ursinus is herdr's "opencode"). */
    private val captures = mapOf(
        "claude" to live("claude"), "codex" to live("codex"), "agy" to live("agy"), "grok" to live("grok"),
        "hermes" to live("hermes"), "copilot" to live("copilot"), "pi" to live("pi"), "omp" to live("omp"),
        "kiro" to live("kiro"), "vibe" to live("vibe"), "muse" to live("muse"), "opencode" to live("opencode"),
    )

    @Test fun `no empty box captured live is a draft or typing`() {
        val all = captures.entries.map { it.key to it.value } + ("opencode" to screen("opencode/live-2026-11-20-second/empty.ansi"))
        for ((agent, s) in all) {
            assertNull(agent, composerDraftOf(s, agent))
            // 2.2.2: a blank Oh My Pi or Copilot box may hold typed spaces (no cursor is drawn), so a normal
            // send holds on it; a menu write takes it as empty and clears it with Ctrl+U first.
            val line = inputLineOf(s, agent, menuWrite = AgentComposers.clearKey(agent) != null)
            assertTrue("$agent reads as typing: $line", line == null || line == "")
        }
    }

    @Test fun `the placeholders are drawn the way the captures show`() {
        assertTrue(live("hermes").contains("$e[3m$e[38;5;136mPlan a feature, then build it step by step"))
        assertTrue(live("codex").contains("$e[2mAsk Codex to do anything"))
        assertTrue(live("kiro").contains("$e[38;2;158;158;158mask a question or describe a task ↵"))
        // Claude puts a no-break space after its "❯".
        assertTrue(live("claude").contains("❯\u00A0$e[0m$e[2mstatus of the kanban cards and eta?"))
    }

    /** The capture with [typed] in place of the placeholder, in the terminal's default colour. */
    private fun typedIn(agent: String, placeholder: String, typed: String, style: String = "$e[0m") =
        live(agent).lines().joinToString("\n") { row ->
            val at = row.indexOf(placeholder)
            if (at < 0) row else {
                // Drop the placeholder's own style codes right before it.
                var cut = at
                while (cut > 0 && row.lastIndexOf("$e[", cut - 1).let { it >= 0 && row.substring(it, cut).matches(Regex("""\u001B\[[0-9;]*m""")) }) cut = row.lastIndexOf("$e[", cut - 1)
                row.substring(0, cut) + style + typed + "$e[0m" + row.substring(at + placeholder.length)
            }
        }

    @Test fun `typed text in the captured boxes is a draft`() {
        assertEquals("fix the build", composerDraftOf(typedIn("hermes", "Plan a feature, then build it step by step", "fix the build"), "hermes")?.text)
        assertEquals("fix the build", composerDraftOf(typedIn("codex", "Ask Codex to do anything", "fix the build"), "codex")?.text)
        assertEquals("fix the build", composerDraftOf(typedIn("kiro", "ask a question or describe a task ↵", "fix the build"), "kiro")?.text)
        // Claude's box here has the session title in its top rule and five footer rows (its agent
        // list), so the draft reader can't place the box's end; the one-row reader still sees the typing.
        assertEquals("fix the build", inputLineOf(typedIn("claude", "status of the kanban cards and eta?", "fix the build"), "claude"))
        assertEquals("fix the build", inputLineOf(typedIn("kiro", "ask a question or describe a task ↵", "fix the build"), "kiro"))
    }

    @Test fun `a known hint in the default colour is typing`() {
        val words = "Plan a feature, then build it step by step"
        assertEquals(words, composerDraftOf(typedIn("hermes", words, words), "hermes")?.text)
        assertEquals("Ask Codex to do anything", composerDraftOf(typedIn("codex", "Ask Codex to do anything", "Ask Codex to do anything"), "codex")?.text)
    }

    @Test fun `hermes examples in a skin without italic are still empty`() {
        val plain = live("hermes").replace("$e[3m$e[38;5;136m", "$e[38;5;136m")
        assertNull(composerDraftOf(plain, "hermes"))
        assertEquals("", inputLineOf(plain, "hermes"))
        val other = plain.replace("Plan a feature, then build it step by step", "Find and fix a failing test")
        assertNull(composerDraftOf(other, "hermes"))
        // Words that are not one of its examples, in that colour, are typing.
        assertEquals("deploy the site", composerDraftOf(plain.replace("Plan a feature, then build it step by step", "deploy the site"), "hermes")?.text)
        // Another agent's box with Hermes's words in a colour is typing.
        assertNotEquals(null, composerDraftOf(typedIn("kiro", "ask a question or describe a task ↵", "Find and fix a failing test", "$e[38;5;136m"), "kiro"))
    }

    @Test fun `plain reads know the hints that are not requests`() {
        val rule = "─".repeat(60)
        fun hermesPlain(text: String) = "☤ Hermes\n$rule\n❯ $text\n$rule\n ☤ gpt-6.2-sol · 163K/500K"
        assertEquals("", inputLineOf(hermesPlain("Ask anything, or type / for commands…"), "hermes"))
        assertNull(composerDraftOf(hermesPlain("Type / to browse commands, or Ctrl+P for the palette"), "hermes"))
        // Its example prompts read like requests: on a plain read they stay typing.
        assertEquals("Find and fix a failing test", inputLineOf(hermesPlain("Find and fix a failing test"), "hermes"))
        val codex = "• Ran the tests.\n\n› Ask a follow-up question\n\n  GPT-6.2-Sol default · ~/agents/codex\n  ← for agents · ? for shortcuts"
        assertNull(composerDraftOf(codex, "codex"))
        assertNull(composerDraftOf(screen("claude/captured-2.1.329/background-awaiting-input.txt"), "claude"))
    }
}
