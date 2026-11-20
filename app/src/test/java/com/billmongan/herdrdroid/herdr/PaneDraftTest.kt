package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.1.0: a draft in the pane's input box, shown in the chat with Send draft and Clear draft. */
class PaneDraftTest {
    private val e = "\u001B"
    private val rule = "─".repeat(60)

    private fun claude(vararg rows: String, footer: String = "  ? for shortcuts") =
        (listOf("● Done.", "", rule) + rows + listOf(rule, footer)).joinToString("\n")

    private fun hermes(vararg rows: String) =
        (listOf("  ┊ review done", rule) + rows + listOf(rule, " ☤ gpt-6.1-sol · 295K/500K")).joinToString("\n")

    private fun codex(vararg rows: String) =
        (listOf("• Ran the tests.", "") + rows + listOf("", "  GPT-6.1-Sol default · ~/agents/codex", "  ← for agents · ? for shortcuts")).joinToString("\n")

    // ---- Reading the draft ------------------------------------------------------------------

    @Test fun `claude drafts of one and several lines`() {
        assertEquals(ComposerDraft("fix the build", true), composerDraftOf(claude("❯ fix the build"), "claude"))
        val many = claude("❯ Please look at the failing test", "  and then run the whole suite", "    with --verbose", "", "  thanks")
        assertEquals(ComposerDraft("Please look at the failing test\nand then run the whole suite\n  with --verbose\n\nthanks", true),
            composerDraftOf(many, "claude"))
        // Six rows: the one-row reader can't place it, the draft reader takes all of it.
        val six = claude("❯ one", "  two", "  three", "  four", "  five", "  six")
        assertNull(inputLineOf(six, "claude"))
        assertEquals("one\ntwo\nthree\nfour\nfive\nsix", composerDraftOf(six, "claude")?.text)
    }

    @Test fun `claude empty box placeholders and queued hints are no draft`() {
        assertNull(composerDraftOf(claude("❯ "), "claude"))
        assertNull(composerDraftOf(claude("❯ Press up to edit queued messages"), "claude"))
        assertNull(composerDraftOf(claude("$e[0m$e[38;2;153;153;153m❯ $e[0m$e[2mTry \"refactor the parser\"$e[0m"), "claude"))
        // Typed text in normal type is a draft, styled or not.
        assertEquals("half typed", composerDraftOf(claude("$e[0m$e[38;2;153;153;153m❯ $e[0mhalf typed$e[0m"), "claude")?.text)
        // A quoted line in the output above an empty box is not a draft.
        assertNull(composerDraftOf("> an earlier message\n● reply\n$rule\n❯ \n$rule\n  ? for shortcuts", "claude"))
    }

    @Test fun `hermes drafts keep their continuation rows from column zero`() {
        assertEquals(ComposerDraft("check the cron", true), composerDraftOf(hermes("❯ check the cron"), "hermes"))
        val multi = hermes("❯ Please draft the reply to Bill's note", "about the vault push, then", "", "list what is left.")
        assertEquals(ComposerDraft("Please draft the reply to Bill's note\nabout the vault push, then\n\nlist what is left.", true),
            composerDraftOf(multi, "hermes"))
        // Its gold italic example prompt is a placeholder.
        assertNull(composerDraftOf(hermes("$e[0m$e[38;5;230m❯ $e[0m$e[3m$e[38;5;136mFind and fix a failing test$e[0m"), "hermes"))
        assertNull(composerDraftOf(hermes("❯ "), "hermes"))
    }

    @Test fun `codex drafts run to the blank row above its footer`() {
        assertEquals(ComposerDraft("fix the build", true), composerDraftOf(codex("› fix the build"), "codex"))
        assertEquals(ComposerDraft("line one\nline two\n\nline four", true),
            composerDraftOf(codex("› line one", "  line two", "", "  line four"), "codex"))
        assertNull(composerDraftOf(codex("$e[0m$e[1m› $e[0m$e[2mExplain this codebase$e[0m"), "codex"))
        assertNull(composerDraftOf(codex("› Ask Codex to do anything"), "codex"))
        // No blank row between the box and the footer: the end can't be seen, so it is shown but not acted on.
        val tight = "• Ran.\n\n› fix it\n  GPT-6.1-Sol default · ~/agents/codex\n  ← for agents · ? for shortcuts"
        assertEquals(ComposerDraft("fix it", false), composerDraftOf(tight, "codex"))
    }

    @Test fun `framed boxes are read to their bottom edge`() {
        val grok = "out\n╭$rule╮\n│ ❯ hello there        │\n│   second line        │\n╰$rule╯\n  Shift+Tab:mode"
        assertEquals(ComposerDraft("hello there\nsecond line", true), composerDraftOf(grok, "grok"))
    }

    // ---- Send and Clear ---------------------------------------------------------------------

    private val shown = Pane("w2:p1", "w2:t1", "w2", "hermes", Status.IDLE, "Hermes", "/home/u", scrollOffset = 0,
        terminalId = "term-1", sessionId = "s-1")

    /** The pane's screen is [screens] indexed by how many writes have gone. */
    private class Host(var pane: Pane?, var screens: List<String>) : PaneDraftAction.Port {
        val writes = mutableListOf<List<String>>()
        var reads = 0
        override suspend fun pane(id: String) = pane
        override suspend fun viewport(id: String): String { reads++; return screens[minOf(writes.size, screens.size - 1)] }
        override suspend fun keys(id: String, keys: List<String>, permit: WritePermit) {
            permit.admit()
            writes += keys
        }
        override suspend fun pause(ms: Long) {}
    }

    private val draftText = "Please draft the reply\nabout the vault push"
    private val draftScreen = hermes("❯ Please draft the reply", "about the vault push")
    private val emptyScreen = hermes("❯ ")
    private fun action() = PaneDraftAction(PaneDispatch(), WriteGate())

    @Test fun `send presses Enter once and reports sent only when the box is seen empty`() = runBlocking {
        val host = Host(shown, listOf(draftScreen, emptyScreen))
        assertEquals(PaneDraftAction.Outcome.SENT, action().send("srv", shown, draftText, { true }, host))
        assertEquals(listOf(listOf("enter")), host.writes)
    }

    @Test fun `send with no confirmed empty box is uncertain and never retried`() = runBlocking {
        val host = Host(shown, listOf(draftScreen))
        assertEquals(PaneDraftAction.Outcome.UNCERTAIN, action().send("srv", shown, draftText, { true }, host))
        assertEquals(listOf(listOf("enter")), host.writes)
    }

    @Test fun `send is refused when the draft or the occupant changed`() = runBlocking {
        val changed = Host(shown, listOf(hermes("❯ Please draft the reply", "about the vault push, and more")))
        assertEquals(PaneDraftAction.Outcome.CHANGED, action().send("srv", shown, draftText, { true }, changed))
        assertTrue(changed.writes.isEmpty())

        val gone = Host(shown, listOf(emptyScreen))
        assertEquals(PaneDraftAction.Outcome.CHANGED, action().send("srv", shown, draftText, { true }, gone))
        assertTrue(gone.writes.isEmpty())

        for (other in listOf(shown.copy(sessionId = "s-2"), shown.copy(terminalId = "term-2"), shown.copy(agent = "claude"),
                shown.copy(status = Status.NEEDS_YOU), shown.copy(scrollOffset = 3), null)) {
            val host = Host(other, listOf(draftScreen, emptyScreen))
            assertEquals(PaneDraftAction.Outcome.REFUSED, action().send("srv", shown, draftText, { true }, host))
            assertTrue(host.writes.isEmpty())
        }
        // A question on screen, or a draft whose end can't be seen.
        val question = Host(shown, listOf("Proceed?\n❯ 1. Yes\n  2. No"))
        assertEquals(PaneDraftAction.Outcome.REFUSED, action().send("srv", shown, draftText, { true }, question))
        val unbounded = Host(shown.copy(agent = "codex"), listOf("• Ran.\n\n› fix it\n  GPT default · ~/x\n  ← for agents"))
        assertEquals(PaneDraftAction.Outcome.REFUSED, action().send("srv", shown.copy(agent = "codex"), "fix it", { true }, unbounded))
        assertTrue(question.writes.isEmpty() && unbounded.writes.isEmpty())
    }

    @Test fun `a busy lane or a withdrawn permission refuses send`() = runBlocking {
        val dispatch = PaneDispatch()
        val release = checkNotNull(dispatch.tryAcquire("srv" to shown.id))
        val host = Host(shown, listOf(draftScreen, emptyScreen))
        assertEquals(PaneDraftAction.Outcome.REFUSED, PaneDraftAction(dispatch, WriteGate()).send("srv", shown, draftText, { true }, host))
        release()
        var allowed = true
        val late = object : PaneDraftAction.Port by host {
            override suspend fun viewport(id: String): String { allowed = false; return host.viewport(id) }
        }
        assertEquals(PaneDraftAction.Outcome.REFUSED, action().send("srv", shown, draftText, { allowed }, late))
        assertTrue(host.writes.isEmpty())
    }

    @Test fun `clear presses only Backspace and stops when the box is empty`() = runBlocking {
        val host = Host(shown, listOf(draftScreen, emptyScreen))
        assertEquals(PaneDraftAction.Outcome.CLEARED, action().clear("srv", shown, draftText, { true }, host))
        assertEquals(1, host.writes.size)
        assertEquals(draftText.length, host.writes.single().size)
        assertTrue(host.writes.flatten().all { it == "backspace" })
    }

    @Test fun `clear repeats only while the draft shrinks and is bounded`() = runBlocking {
        // The cursor was mid-text: the first round leaves the tail, the second empties it.
        val shrinking = Host(shown, listOf(draftScreen, hermes("❯ vault push"), emptyScreen))
        assertEquals(PaneDraftAction.Outcome.CLEARED, action().clear("srv", shown, draftText, { true }, shrinking))
        assertEquals(listOf(draftText.length, "vault push".length), shrinking.writes.map { it.size })

        // Backspace that deletes nothing: one round, then it stops and says so.
        val stuck = Host(shown, listOf(draftScreen))
        assertEquals(PaneDraftAction.Outcome.PARTIAL, action().clear("srv", shown, draftText, { true }, stuck))
        assertEquals(1, stuck.writes.size)

        // Always a little shorter, never empty: at most MAX_CLEAR_ROUNDS writes.
        val long = "x".repeat(40)
        val slow = Host(shown, (0..20).map { hermes("❯ " + long.dropLast(it)) })
        assertEquals(PaneDraftAction.Outcome.PARTIAL, action().clear("srv", shown, long, { true }, slow))
        assertEquals(PaneDraftAction.MAX_CLEAR_ROUNDS, slow.writes.size)

        for (h in listOf(shrinking, stuck, slow)) assertFalse("enter" in h.writes.flatten())
        assertTrue(listOf(shrinking, stuck, slow).all { h -> h.writes.all { it.size <= PaneDraftAction.MAX_KEYS_PER_ROUND } })
    }

    @Test fun `clear is refused without a known safe clear or for a changed occupant`() = runBlocking {
        val pi = shown.copy(agent = "pi")
        val unknown = Host(pi, listOf(draftScreen, emptyScreen))
        assertFalse(canClearDraft("pi"))
        assertEquals(PaneDraftAction.Outcome.REFUSED, action().clear("srv", pi, draftText, { true }, unknown))
        assertTrue(unknown.writes.isEmpty())
        for (agent in listOf("claude", "codex", "hermes", "grok")) assertTrue(canClearDraft(agent))

        val replaced = Host(shown.copy(sessionId = "s-9"), listOf(draftScreen, emptyScreen))
        assertEquals(PaneDraftAction.Outcome.REFUSED, action().clear("srv", shown, draftText, { true }, replaced))
        val changed = Host(shown, listOf(hermes("❯ something else")))
        assertEquals(PaneDraftAction.Outcome.CHANGED, action().clear("srv", shown, draftText, { true }, changed))
        assertTrue(replaced.writes.isEmpty() && changed.writes.isEmpty())
    }

    @Test fun `a relay to a pane with a draft names the draft`() {
        assertTrue(RelayResult.DRAFT.feedback.contains("draft"))
        assertTrue(draftOutcomeText(PaneDraftAction.Outcome.UNCERTAIN, "Hermes", clear = true).contains("Enter was not pressed"))
    }
}
