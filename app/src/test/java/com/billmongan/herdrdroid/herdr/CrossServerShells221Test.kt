package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * 2.2.1 (Bill: "it does ask me if I want to send the confirmation code back ... but then it pops up
 * a card saying that the message was not sent and nothing happens"). The destination, CLAUDE2 on
 * the other server (Claude Code 2.1.330), keeps background shells running ("3 shells" in its
 * footer) after its turn ends. The app's work sweep then marks it busy, and the relay refused it as
 * if typing could interrupt it, although Claude's input box takes a message while those run.
 *
 * The fixtures keep the capture's composer box and footer as drawn (rules, the "❯" row, the chips)
 * with neutral text above it in place of the tool output.
 */
class CrossServerShells221Test {
    private fun fixture(name: String) =
        checkNotNull(javaClass.getResource("/screens/claude/derived-2.1.330-shells/$name")).readText()

    private val idle = fixture("idle-3-shells.ansi")
    private val working = fixture("working-3-shells.ansi")
    private val question = checkNotNull(javaClass.getResource("/screens/claude/captured-2.1.329/single-question.txt")).readText()

    @Test fun claude2130IdleComposerWithShellsParsesAsAnEmptyBoxWithNoQuestion() {
        assertEquals("", inputLineOf(idle, "claude"))
        assertNull(composerDraftOf(idle, "claude"))
        assertNull(detectPrompt(forPhone(idle), false, "claude"))
        assertEquals(listOf("⏵⏵ auto mode on", "3 shells", "↓ to manage"), composerFooterChips(idle))
        assertFalse(composerFooterSaysWorking(idle))
    }

    @Test fun claude2130WorkingFooterSaysWorking() {
        assertEquals("", inputLineOf(working, "claude"))
        assertTrue(composerFooterSaysWorking(working))
    }

    /** Synthetic footers: the chips found by structure, in any order and wording close to today's. */
    @Test fun footerChipsAreReadByStructureNotWording() {
        val rule = "─".repeat(60)
        fun screen(footer: String, titled: Boolean = false) =
            "● Done.\n\n${if (titled) "─".repeat(50) + " CLAUDE2 ─" else rule}\n❯ \n$rule\n$footer\n"
        val idleFooters = listOf(
            "  ⏵⏵ auto mode on · 1 shell · ↓ to manage",
            "  3 shells · ⏵⏵ auto mode on · ↓ to manage",
            "  ⏵⏵ accept edits on · 2 subagents · 1 background task · ↓ to manage",
            "  ⏸ plan mode on · 4 background tasks",
            "  ? for shortcuts",
            "  2 shells • ↓ to view",
        )
        for (f in idleFooters) for (titled in listOf(false, true)) {
            val s = screen(f, titled)
            assertEquals(f, "", inputLineOf(s, "claude"))
            assertNull(f, composerDraftOf(s, "claude"))
            assertTrue(f, composerFooterChips(s).isNotEmpty())
            assertFalse(f, composerFooterSaysWorking(s))
        }
        for (f in listOf(
            "  ⏵⏵ auto mode on · 3 shells · esc to interrupt · ↓ to manage",
            "  esc to interrupt · 1 shell",
            "  ⏵⏵ auto mode on · Esc to interrupt",
            "  2 subagents · press esc to interrupt",
        )) assertTrue(f, composerFooterSaysWorking(screen(f)))
        // No box, no chips: nothing is read into a screen whose layout is not the boxed composer.
        assertTrue(composerFooterChips("❯ \n  3 shells · esc to interrupt").isEmpty())
    }

    @Test fun onlyAgentsThatTakeInputMeanwhileIgnoreBackgroundWork() {
        val claude = Pane("p1", "t", "w", "claude", Status.IDLE, "", "/x", scrollOffset = 0, terminalId = "t1")
        // The app's own derivation (Fleet's sweep and the open server's): idle plus busy is "Busy".
        val swept = withBusy(listOf(claude), mapOf("p1" to 3)).single()
        assertEquals(Status.WORKING, swept.status)
        assertTrue(swept.backgroundBusy)
        assertTrue(idleForRelay(swept))
        for (agent in listOf("codex", "hermes", "opencode", "agy", "grok", "kiro", "pi")) {
            assertFalse(agent, idleForRelay(withBusy(listOf(claude.copy(agent = agent)), mapOf("p1" to 3)).single()))
            assertTrue(agent, idleForRelay(claude.copy(agent = agent)))
        }
        assertFalse(idleForRelay(claude.copy(status = Status.WORKING)))
        assertFalse(idleForRelay(claude.copy(status = Status.NEEDS_YOU)))
        assertFalse(idleForRelay(claude.copy(status = Status.UNKNOWN)))
    }

    private val claude = Pane("p1", "t", "w", "claude", Status.IDLE, "", "/x", scrollOffset = 0, terminalId = "t1")
    private class Host(var pane: Pane?) : PromptRelayPort {
        val connection = Any()
        var viewport = ""
        var busy = true
        var hasWorkCalls = 0
        val writes = mutableListOf<String>()
        override val connectionIdentity get() = connection
        override suspend fun pane(id: String, connection: Any) = pane
        override suspend fun hasWork(pane: Pane, connection: Any): Boolean { hasWorkCalls++; return busy }
        override suspend fun viewport(id: String, connection: Any) = viewport
        override suspend fun send(pane: Pane, text: String, connection: Any) { writes += text }
    }

    private suspend fun relayTo(host: Host, clear: Boolean = true): Pair<RelayResult, RelayRefusal?> {
        val relay = PromptRelay(PaneDispatch(), mapOf("remote" to host)::get) { _, _ -> clear }
        val target = checkNotNull(relay.bind("remote", checkNotNull(host.pane)))
        var why: RelayRefusal? = null
        return relay.send(target, "Please review the build.", refused = { why = it }) { true } to why
    }

    @Test fun aClaudeDestinationWithBackgroundShellsGetsTheMessageOnce() = runBlocking {
        val host = Host(claude).apply { viewport = idle }
        assertEquals(RelayResult.SENT to null, relayTo(host))
        assertEquals(listOf("Please review the build."), host.writes)
        assertEquals("Claude's background work is not asked about", 0, host.hasWorkCalls)
    }

    @Test fun otherAgentsStillWaitForTheirBackgroundWorkAndSaySo() = runBlocking {
        val host = Host(claude.copy(agent = "codex")).apply { viewport = "❯ " }
        assertEquals(RelayResult.REFUSED to RelayRefusal.BACKGROUND, relayTo(host))
        host.busy = false
        assertEquals(RelayResult.SENT to null, relayTo(host))
        assertEquals(1, host.writes.size)
    }

    /** Every way a relay ends in "not sent" names its check, and nothing is typed. */
    @Test fun eachRefusalNamesItsCheck() = runBlocking {
        fun host(viewport: String = idle, pane: Pane? = claude) = Host(pane).apply { this.viewport = viewport }
        assertEquals(RelayRefusal.NOT_IDLE, relayTo(host(working)).second)
        assertEquals(RelayRefusal.NOT_IDLE, relayTo(host(pane = claude.copy(status = Status.WORKING))).second)
        assertEquals(RelayRefusal.NOT_IDLE, relayTo(host(pane = claude.copy(status = Status.UNKNOWN))).second)
        assertEquals(RelayRefusal.QUESTION, relayTo(host(question)).second)
        assertEquals(RelayRefusal.NO_INPUT, relayTo(host("Just output, no input box")).second)
        assertEquals(RelayResult.DRAFT to RelayRefusal.DRAFT, relayTo(host(idle.replace("❯\u00A0\u001B[0m", "❯\u00A0\u001B[0mhalf typed"))))
        assertEquals(RelayRefusal.LANE, relayTo(host(), clear = false).second)
        // Replaced after binding: the occupant check names it.
        val h = host()
        val relay = PromptRelay(PaneDispatch(), mapOf("remote" to h)::get) { _, _ -> true }
        val target = checkNotNull(relay.bind("remote", claude))
        h.pane = claude.copy(terminalId = "replacement")
        var why: RelayRefusal? = null
        assertEquals(RelayResult.REFUSED, relay.send(target, "x", refused = { why = it }) { true })
        assertEquals(RelayRefusal.REPLACED, why)
        why = null
        h.pane = claude
        assertEquals(RelayResult.REFUSED, relay.send(target, "bad\u0007text", refused = { why = it }) { true })
        assertEquals(RelayRefusal.TEXT, why)
        assertFalse(RelayRefusal.TEXT.transient)
        assertFalse(RelayRefusal.REPLACED.transient)
        assertTrue(RelayRefusal.NOT_IDLE.transient && RelayRefusal.BACKGROUND.transient && RelayRefusal.LINK.transient)
        assertEquals(RelayResult.REFUSED, relay.send(target, "x", refused = { why = it }) { false })
        assertEquals(RelayRefusal.STOPPED, why)
    }
}
