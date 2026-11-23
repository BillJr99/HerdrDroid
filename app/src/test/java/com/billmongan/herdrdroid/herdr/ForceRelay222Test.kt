package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.2: a forced relay (Force Send on a cross-server card) skips only the idle and background-work
 * checks. Every other check still holds it on the fresh read.
 */
class ForceRelay222Test {
    private class Host(var pane: Pane) : PromptRelayPort {
        val connection: Any = Any()
        var viewport = "❯ "
        /** Screens the next viewport reads return, in turn, before [viewport]. */
        val next = ArrayDeque<String>()
        var reads = 0
        var error: Exception? = null
        var work = true
        val writes = mutableListOf<String>()
        override val connectionIdentity get() = connection
        override suspend fun pane(id: String, connection: Any) = pane.takeIf { it.id == id && this.connection === connection }
        override suspend fun hasWork(pane: Pane, connection: Any) = work
        override suspend fun viewport(id: String, connection: Any): String { reads++; return next.removeFirstOrNull() ?: viewport }
        override suspend fun send(pane: Pane, text: String, connection: Any) { error?.let { throw it }; writes += text }
    }

    private val working = """
        * Vibing… (8m 1s · ↓ 31.1k tokens)

        ────────────────────────────────────────
        ❯
        ────────────────────────────────────────
          ⏵⏵ auto mode on · 3 shells · esc to interrupt · ↓ to manage
    """.trimIndent()

    private fun pane(agent: String, status: Status = Status.WORKING, scroll: Int = 0) =
        Pane("pane-1", "tab-1", "ws-1", agent, status, agent, "/project", scrollOffset = scroll, terminalId = "term-1", sessionId = "session-1")

    private fun send(host: Host, force: Boolean, lane: Boolean = true, idleLane: Boolean = false): Pair<RelayResult, RelayRefusal?> = runBlocking {
        val relay = PromptRelay(PaneDispatch(), { if (it == "remote") host else null }, laneClear = { _, _ -> lane }) { _, _ -> idleLane }
        val target = checkNotNull(relay.bind("remote", host.pane))
        var why: RelayRefusal? = null
        relay.send(target, "hello", refused = { why = it }, force = force) { true } to why
    }

    @Test fun aWorkingClaudeIsRefusedUnforcedAndTypedIntoOnceForced() {
        val host = Host(pane("claude", Status.WORKING).copy(backgroundBusy = true)).apply { viewport = working }
        assertEquals(RelayResult.REFUSED to RelayRefusal.LANE, send(host, force = false))
        assertEquals(RelayResult.SENT to null, send(host, force = true))
        assertEquals(listOf("hello"), host.writes)
    }

    /** Force is for Claude Code only: for any other agent a forced send is an ordinary one. */
    @Test fun aForcedSendToAnotherAgentIsAnOrdinarySend() {
        val hermes = Host(pane("hermes", Status.IDLE).copy(backgroundBusy = true))
        assertEquals(RelayResult.REFUSED to RelayRefusal.BACKGROUND, send(hermes, force = true, idleLane = true))
        val opencode = Host(pane("opencode", Status.WORKING))
        assertEquals(RelayResult.REFUSED to RelayRefusal.WORKING, send(opencode, force = true, idleLane = true))
        assertTrue(hermes.writes.isEmpty() && opencode.writes.isEmpty())
    }

    /** A dialog that opens after the first screen read is caught by the read right before the write. */
    @Test fun aForcedSendReadsTheScreenAgainRightBeforeTyping() {
        val question = """
            Do you want to proceed?
            ❯ 1. Yes
              2. No

            Esc to cancel
        """.trimIndent()
        val host = Host(pane("claude")).apply { viewport = question; next.addLast(working) }
        assertEquals(RelayResult.REFUSED to RelayRefusal.QUESTION, send(host, force = true))
        assertEquals(2, host.reads)
        assertTrue(host.writes.isEmpty())
        // Both reads clean: typed once.
        val clean = Host(pane("claude")).apply { viewport = working }
        assertEquals(RelayResult.SENT to null, send(clean, force = true))
        assertEquals(2, clean.reads)
        assertEquals(1, clean.writes.size)
    }

    /** herdr's agent_blocked comes before any input is sent: a transient QUESTION, not "could not confirm". */
    @Test fun herdrsAgentBlockedIsAQuestionRefusal() {
        val host = Host(pane("claude")).apply { viewport = working; error = HerdrError("error: agent_blocked", 1) }
        assertEquals(RelayResult.REFUSED to RelayRefusal.QUESTION, send(host, force = true))
        host.error = HerdrError("connection reset", 1)
        assertEquals(RelayResult.UNCERTAIN to null, send(host, force = true))
    }

    @Test fun aForcedSendStillHonoursEveryOtherCheck() {
        val panel = buildString {
            appendLine("  ┌──────────────────────────────────────────────────────────────────────┐")
            appendLine("  │ ${"Status".padEnd(68)} │")
            appendLine("  │ ${"Model      sample".padEnd(68)} │")
            appendLine("  │ ${"Esc close".padEnd(68)} │")
            appendLine("  └──────────────────────────────────────────────────────────────────────┘")
            appendLine()
            appendLine("❯ ")
        }
        val host = Host(pane("claude"))
        for ((screen, why) in listOf(
            "❯ unsent words" to RelayRefusal.DRAFT,
            "Output only, the input box is not drawn" to RelayRefusal.NO_INPUT,
            panel to RelayRefusal.USAGE,
        )) {
            host.viewport = screen
            assertEquals(why.name, why, send(host, force = true).second)
        }
        host.viewport = "❯ "
        assertEquals(RelayRefusal.LANE, send(host, force = true, lane = false).second)
        host.pane = pane("claude", Status.NEEDS_YOU)
        assertEquals("herdr says it is waiting on you", RelayRefusal.QUESTION, send(host, force = true).second)
        host.pane = pane("claude", scroll = 3)
        assertEquals("scrolled back hides the input line", RelayRefusal.SCROLLED, send(Host(host.pane), force = true).second)
        assertTrue(host.writes.isEmpty())
    }
}
