package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.4: agents' empty-line hints are not typing, and a held send is retried quietly. */
class Release144Test {
    private val rule = "─".repeat(100)

    /** Bill's screen: Claude Code with messages queued shows its hint in the empty input line. */
    private val claudeQueued = """
        ● Reading the 1.4.3 section of the vault page.
          ⎿  Read 140 lines

        ✻ Thinking… (12s · esc to interrupt)

          ❯ add the queued note too
        $rule
        ❯ Press up to edit queued messages
        $rule
          ⏵⏵ auto mode on (shift+tab to cycle)
    """.trimIndent()

    @Test fun claudesQueuedHintIsAnEmptyInputLine() {
        assertEquals("", inputLineOf(claudeQueued, "claude"))
        assertEquals("", inputLineOf(claudeQueued)) // agent not known yet
        // Claude's other queued-message hints (Claude Code 2.1.325).
        for (h in listOf("Press up to edit queued messages, Enter to send them immediately",
                "Press up to select a queued message to edit, or Enter to send them now",
                "Press up to select a queued message, then Enter to edit it"))
            assertEquals(h, "", inputLineOf("out\n$rule\n❯ $h\n$rule\n  ? for shortcuts", "claude"))
        // Cut short with an ellipsis on a narrow screen.
        assertEquals("", inputLineOf("out\n$rule\n❯ Press up to edit queued…\n$rule\n  footer", "claude"))
    }

    @Test fun realTypingIsStillTyping() {
        // Something typed at the computer, the hint's words included, is still typed (the 1.4.0 rule).
        assertEquals("Press up to", inputLineOf("out\n$rule\n❯ Press up to\n$rule\n  footer", "claude"))
        assertEquals("Press up to edit queued messages please", inputLineOf("out\n$rule\n❯ Press up to edit queued messages please\n$rule\n  footer", "claude"))
        assertEquals("Press up to edit queued", inputLineOf("out\n$rule\n❯ Press up to edit queued\n$rule\n  footer", "claude"))
        // Another agent's hint typed into Claude is typing.
        assertEquals("Find and fix a failing test", inputLineOf("out\n$rule\n❯ Find and fix a failing test\n$rule\n  footer", "claude"))
        assertEquals("half a message", inputLineOf("out\n$rule\n❯ half a message\n$rule\n  footer", "claude"))
    }

    @Test fun otherAgentsHintsFromLivePanes() {
        // Codex 0.169's empty composer.
        assertEquals("", inputLineOf("  Weekly limit: 82% left\n› Ask Codex to do anything\n  GPT-6.1-Sol default · ~/agents/codex\n  ← for agents · ? for shortcuts", "codex"))
        // Kiro: the hint is the last row, after its status lines.
        assertEquals("", inputLineOf("▸ Credits: turn 0.18 • session 0.28 | Time: 11s\n$rule\n Trust All Tools active, confirmations are off · /quit to exit\n$rule\nbill_default · auto · ◔ 2%   ~/agents/kiro\n›  ask a question or describe a task ↵", "kiro"))
        // Hermes's example prompts (its locales/en.yaml).
        for (h in listOf("Find and fix a failing test", "What changed in this repo recently?", "Ask anything, or type / for commands…"))
            assertEquals(h, "", inputLineOf("  Usage: /reasoning\n$rule\n❯ $h\n$rule\n ☤ gpt-6.1-sol · 295K/500K", "hermes"))
        assertTrue(isComposerHint("codex", "Ask Codex to do anything"))
        assertFalse(isComposerHint("codex", "Ask Codex to do anything about the tests"))
        assertFalse(isComposerHint("grok", "Ask Codex to do anything"))
    }

    /** A clock that moves only when the retry sleeps, so two minutes take no time. */
    private class FakeClock { var t = 1_000_000L }

    private fun retry(clock: FakeClock) = HeldSendRetry(windowMs = 120_000L, everyMs = 2_000L, now = { clock.t }, sleep = { clock.t += it })

    @Test fun aHeldSendThatClearsIsDeliveredWithoutAReport() = runBlocking {
        val clock = FakeClock()
        val start = clock.t
        // The pane reads with text typed for three checks, then the line is clear.
        val screens = ArrayDeque(listOf("draft", "draft more", "draft more", ""))
        var sent = 0
        var typedWhenSent: String? = null
        var line = "draft"
        val logs = mutableListOf<String>()
        val out = retry(clock).run(
            startedAt = start, firstWhy = "something is already typed",
            cancelled = { null },
            check = { line = screens.removeFirstOrNull() ?: ""; if (line.isNotEmpty()) "something is already typed (\"$line\")" else null },
            send = { sent++; typedWhenSent = line },
            log = { logs += it },
        )
        assertTrue(out is HeldOutcome.Delivered)
        assertEquals(1, sent)
        assertEquals("", typedWhenSent) // never typed over the text at the computer
        assertEquals(4, (out as HeldOutcome.Delivered).checks)
        assertEquals(8_000L, out.afterMs)
        assertTrue(logs.any { it.contains("sent") })
    }

    @Test fun aSendThatNeverClearsIsReportedAtTheDeadline() = runBlocking {
        val clock = FakeClock()
        var sent = 0
        val out = retry(clock).run(
            startedAt = clock.t, firstWhy = "something is already typed",
            cancelled = { null },
            check = { "something is already typed (\"a long note\")" },
            send = { sent++ },
        )
        assertTrue(out is HeldOutcome.TimedOut)
        assertEquals(0, sent)
        assertEquals("something is already typed (\"a long note\")", (out as HeldOutcome.TimedOut).why)
        assertEquals(60, out.checks)
    }

    @Test fun aFailedSendIsReportedAndACancelSendsNothing() = runBlocking {
        val clock = FakeClock()
        val failed = retry(clock).run(clock.t, "held", { null }, { null }, { throw IllegalStateException("link down") })
        assertTrue(failed is HeldOutcome.Failed)
        // Dismissed while the check was reading the pane: nothing is sent.
        var dismissed = false
        var sent = 0
        val cancelled = retry(clock).run(clock.t, "held", { if (dismissed) "dismissed" else null }, { dismissed = true; null }, { sent++ })
        assertTrue(cancelled is HeldOutcome.Cancelled)
        assertEquals(0, sent)
        // A screen that can't be read keeps it held; it isn't sent blind.
        var reads = 0
        val unreadable = retry(clock).run(clock.t, "held", { null }, { reads++; throw RuntimeException("read failed") }, { sent++ })
        assertTrue(unreadable is HeldOutcome.TimedOut)
        assertEquals(0, sent)
        assertTrue(reads > 0)
    }
}
