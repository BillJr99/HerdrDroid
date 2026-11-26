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

    @Test fun `rule-like rows typed in a draft are text, not the box's edge`() {
        val plan = claude("❯ Plan:", "  ----", "  rm the old backups")
        assertEquals(ComposerDraft("Plan:\n----\nrm the old backups", true), composerDraftOf(plan, "claude"))
        val quote = hermes("❯ intro", "----", "> quote")
        assertEquals(ComposerDraft("intro\n----\n> quote", true), composerDraftOf(quote, "hermes"))
        // A shorter box-drawing line inside the draft is not the closing rule either.
        val short = hermes("❯ intro", "─".repeat(30), "more")
        assertEquals("intro\n${"─".repeat(30)}\nmore", composerDraftOf(short, "hermes")?.text)
        // A full-width rule further down is not the box's edge when more than a footer follows it.
        val tall = (listOf(rule, "❯ old", rule) + List(8) { "  output $it" } + listOf(rule, "❯ ", rule, "  ? for shortcuts")).joinToString("\n")
        assertNull(composerDraftOf(tall, "claude"))
    }

    @Test fun `a typed line of dashes as wide as the box makes the end unknown`() {
        val dashes = "─".repeat(60)
        val boxed = hermes("❯ Please ship it", dashes, "now")
        assertEquals(ComposerDraft("Please ship it\n$dashes\nnow", false), composerDraftOf(boxed, "hermes"))
        assertEquals(false, composerDraftOf(claude("❯ Please ship it", dashes, "  now"), "claude")?.bounded)
        // The same in a frame whose top edge is short: a full-width divider inside it.
        val top = "╭" + "─".repeat(10) + "╮"
        val framed = "out\n$top\n│ ❯ Please ship it   │\n├${"─".repeat(60)}┤\n│   now              │\n╰${"─".repeat(60)}╯\n  Shift+Tab:mode"
        assertEquals(false, composerDraftOf(framed, "grok")?.bounded)
        // One rule of that width, the box's own: as before.
        assertEquals(ComposerDraft("Please ship it\nnow", true), composerDraftOf(hermes("❯ Please ship it", "now"), "hermes"))
        val plainFrame = "out\n$top\n│ ❯ Please ship it   │\n│   now              │\n╰${"─".repeat(60)}╯\n  Shift+Tab:mode"
        assertEquals(ComposerDraft("Please ship it\nnow", true), composerDraftOf(plainFrame, "grok"))
    }

    @Test fun `unboxed prompt rows need the glyph at column zero and a clear row above`() {
        assertEquals(ComposerDraft("first\n> quoted", true), composerDraftOf(codex("› first", "  > quoted"), "codex"))
        // Text right above the prompt row: it may be part of the output, so it is shown but not acted on.
        val joined = "• Ran the tests.\n› quoted from the agent\n\n  GPT-6.1-Sol default · ~/agents/codex"
        assertEquals(false, composerDraftOf(joined, "codex")?.bounded)
        assertNull(composerDraftOf("output\n  > indented quote\n", "kiro"))
    }

    @Test fun `a ghost suggestion after the typed text is left out`() {
        assertEquals("fix the", composerDraftOf(claude("$e[0m$e[38;2;153;153;153m❯ $e[0mfix the$e[2m failing test$e[0m"), "claude")?.text)
        assertEquals("check", composerDraftOf(hermes("$e[0m❯ $e[0mcheck$e[3m$e[38;5;136m the cron$e[0m"), "hermes")?.text)
    }

    @Test fun `Send draft is off where Enter could pick a command or the agent is busy`() {
        fun block(text: String, status: Status = Status.IDLE, menu: Boolean = false) =
            draftSendBlock(ComposerDraft(text, true, menu), status, "Claude")
        assertNull(block("fix the build"))
        assertNull(block("fix the build", Status.DONE))
        for (t in listOf("/cl", "/clear", "!ls", "look at @src", "open /tmp")) assertTrue(t, block(t)!!.contains("command"))
        assertTrue(block("fix it", menu = true)!!.contains("command"))
        assertTrue(block("fix it", Status.WORKING)!!.contains("idle"))
        // A command list under the box marks the draft.
        val menu = claude("❯ /c", footer = "  /clear              Clear conversation history\n  /compact            Compact the conversation")
        assertEquals(true, composerDraftOf(menu, "claude")?.menu)
        assertEquals(false, composerDraftOf(claude("❯ fix it"), "claude")?.menu)
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
        private fun recordKeys(keys: List<String>, permit: WritePermit) {
            permit.admit()
            writes += keys
        }
        override suspend fun checkedKeys(id: String, keys: List<String>, viewport: String, permit: WritePermit): Boolean {
            recordKeys(keys, permit)
            return true
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

    @Test fun `send refuses a slash command, a command list or a busy agent without typing`() = runBlocking {
        for ((pane, screen, text) in listOf(
            Triple(shown, hermes("❯ /clear"), "/clear"),
            Triple(shown, hermes("❯ look at @src"), "look at @src"),
            Triple(shown.copy(status = Status.WORKING), draftScreen, draftText),
        )) {
            val host = Host(pane, listOf(screen, emptyScreen))
            assertEquals(text, PaneDraftAction.Outcome.NOT_SENDABLE, action().send("srv", shown, text, { true }, host))
            assertTrue(host.writes.isEmpty())
        }
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

    /**
     * A Hermes box over a real buffer: Backspace deletes from its end, [drop] keys of the first
     * write are lost on the way, and [extra] more characters than were sent vanish on the first one.
     */
    private inner class Buffer(var text: String, val drop: Int = 0, val extra: Int = 0) : PaneDraftAction.Port {
        val writes = mutableListOf<List<String>>()
        override suspend fun pane(id: String) = shown
        override suspend fun viewport(id: String): String {
            val lines = text.split("\n")
            return hermes(*(listOf("❯ " + lines.first()) + lines.drop(1)).toTypedArray())
        }
        private fun recordKeys(keys: List<String>, permit: WritePermit) {
            permit.admit()
            writes += keys
            val n = keys.size - (if (writes.size == 1) drop else 0) + (if (writes.size == 1) extra else 0)
            text = text.dropLast(n.coerceAtMost(text.length))
        }
        override suspend fun checkedKeys(id: String, keys: List<String>, viewport: String, permit: WritePermit): Boolean {
            recordKeys(keys, permit)
            return true
        }
        override suspend fun pause(ms: Long) {}
    }

    @Test fun `clear probes with a few Backspaces, reads back, then sends the rest`() = runBlocking {
        val buffer = Buffer(draftText)
        assertEquals(PaneDraftAction.Outcome.CLEARED, action().clear("srv", shown, draftText, { true }, buffer))
        assertEquals(listOf(PaneDraftAction.CLEAR_PROBE, draftText.length - PaneDraftAction.CLEAR_PROBE), buffer.writes.map { it.size })
        assertTrue(buffer.writes.flatten().all { it == "backspace" })
        // A short draft: the probe is the whole of it.
        val short = Buffer("hi")
        assertEquals(PaneDraftAction.Outcome.CLEARED, action().clear("srv", shown, "hi", { true }, short))
        assertEquals(listOf(2), short.writes.map { it.size })
    }

    @Test fun `clear goes on after dropped keys but stops on anything else`() = runBlocking {
        // Three keys of the probe were lost: the rest is still a start of the draft, so it goes on.
        val dropped = Buffer(draftText, drop = 3)
        assertEquals(PaneDraftAction.Outcome.CLEARED, action().clear("srv", shown, draftText, { true }, dropped))
        assertEquals(listOf(8, draftText.length - 5), dropped.writes.map { it.size })

        // More went than was sent (text off screen, the cursor elsewhere): it stops after the probe.
        val more = Buffer(draftText, extra = 4)
        assertEquals(PaneDraftAction.Outcome.PARTIAL, action().clear("srv", shown, draftText, { true }, more))
        assertEquals(listOf(8), more.writes.map { it.size })

        // Seven of eight went: still the start of the draft, so the rest follows.
        val midText = Host(shown, listOf(draftScreen, hermes("❯ Please draft the reply", "about the vau"), emptyScreen))
        assertEquals(PaneDraftAction.Outcome.CLEARED, action().clear("srv", shown, draftText, { true }, midText))
        // The cursor was mid-text: what is left is not the start of what was shown.
        val cut = Host(shown, listOf(draftScreen, hermes("❯ ease draft the reply", "about the vault push")))
        assertEquals(PaneDraftAction.Outcome.PARTIAL, action().clear("srv", shown, draftText, { true }, cut))
        assertEquals(1, cut.writes.size)

        // Backspace that deletes nothing: one round, then it stops and says so.
        val stuck = Host(shown, listOf(draftScreen))
        assertEquals(PaneDraftAction.Outcome.PARTIAL, action().clear("srv", shown, draftText, { true }, stuck))
        assertEquals(listOf(8), stuck.writes.map { it.size })

        // Always a little shorter, never empty: at most MAX_CLEAR_ROUNDS writes.
        val long = "x".repeat(40)
        val slow = Host(shown, (0..20).map { hermes("❯ " + long.dropLast(it)) })
        assertEquals(PaneDraftAction.Outcome.PARTIAL, action().clear("srv", shown, long, { true }, slow))
        assertEquals(PaneDraftAction.MAX_CLEAR_ROUNDS, slow.writes.size)

        for (h in listOf(midText, cut, stuck, slow)) assertFalse("enter" in h.writes.flatten())
        for (b in listOf(dropped, more)) assertFalse("enter" in b.writes.flatten())
        assertTrue(listOf(cut, stuck, slow).all { h -> h.writes.all { it.size <= PaneDraftAction.MAX_KEYS_PER_ROUND } })
    }

    @Test fun `clear goes on when the probe ends at a space the screen doesn't show`() = runBlocking {
        // 2.1.1 acceptance D4: eight Backspaces leave "HD211 draft " and the box shows "HD211 draft".
        val text = "HD211 draft clear rc"
        val buffer = Buffer(text)
        assertEquals(PaneDraftAction.Outcome.CLEARED, action().clear("srv", shown, text, { true }, buffer))
        assertEquals(listOf(8, 12), buffer.writes.map { it.size })
        // Read back with the trailing space trimmed by the render, as on the emulator.
        val trimmed = Host(shown, listOf(hermes("❯ $text"), hermes("❯ HD211 draft"), emptyScreen))
        assertEquals(PaneDraftAction.Outcome.CLEARED, action().clear("srv", shown, text, { true }, trimmed))
        assertEquals(listOf(8, 12), trimmed.writes.map { it.size })
        // Several spaces at the cut: still never more Backspaces than the box can hold.
        val spaces = "fix it      clear"
        val wide = Host(shown, listOf(hermes("❯ $spaces"), hermes("❯ fix it"), emptyScreen))
        assertEquals(PaneDraftAction.Outcome.CLEARED, action().clear("srv", shown, spaces, { true }, wide))
        assertEquals(listOf(8, 9), wide.writes.map { it.size })
        // A cut past the space is more than was sent: it stops after the probe.
        val over = Host(shown, listOf(hermes("❯ $text"), hermes("❯ HD211 draf")))
        assertEquals(PaneDraftAction.Outcome.PARTIAL, action().clear("srv", shown, text, { true }, over))
        assertEquals(listOf(8), over.writes.map { it.size })
        // A remainder that isn't the start of the draft still stops it.
        val mid = Host(shown, listOf(hermes("❯ $text"), hermes("❯ D211 draft")))
        assertEquals(PaneDraftAction.Outcome.PARTIAL, action().clear("srv", shown, text, { true }, mid))
        for (h in listOf(trimmed, wide, over, mid)) assertFalse("enter" in h.writes.flatten())
        assertFalse("enter" in buffer.writes.flatten())
    }

    @Test fun `a suggestion list is not draft text and turns Send and Clear off`() = runBlocking {
        val screen = codex("› open @src", "  @src/App.kt", "  @src/main.kt")
        assertEquals(ComposerDraft("open @src", true, menu = true), composerDraftOf(screen, "codex"))
        val codexPane = shown.copy(agent = "codex")
        val host = Host(codexPane, listOf(screen, codex("› ")))
        assertEquals(PaneDraftAction.Outcome.NOT_SENDABLE, action().clear("srv", codexPane, "open @src", { true }, host))
        assertEquals(PaneDraftAction.Outcome.NOT_SENDABLE, action().send("srv", codexPane, "open @src", { true }, host))
        assertTrue(host.writes.isEmpty())
        assertTrue(draftOutcomeText(PaneDraftAction.Outcome.NOT_SENDABLE, "Codex", clear = true).startsWith("Not cleared"))
    }

    @Test fun `clear is refused without a known safe clear or for a changed occupant`() = runBlocking {
        // 2.2.8: pi's Backspace was checked live; an agent with no checked key (gemini) still gets no Clear.
        val pi = shown.copy(agent = "gemini")
        val unknown = Host(pi, listOf(draftScreen, emptyScreen))
        assertFalse(canClearDraft("gemini"))
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
