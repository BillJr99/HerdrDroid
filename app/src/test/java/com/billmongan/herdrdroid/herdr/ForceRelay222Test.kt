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
        var work = true
        val writes = mutableListOf<String>()
        override val connectionIdentity get() = connection
        override suspend fun pane(id: String, connection: Any) = pane.takeIf { it.id == id && this.connection === connection }
        override suspend fun hasWork(pane: Pane, connection: Any) = work
        override suspend fun viewport(id: String, connection: Any) = viewport
        override suspend fun send(pane: Pane, text: String, connection: Any) { writes += text }
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

    @Test fun aForcedSendSkipsBackgroundWorkForAnyAgent() {
        val host = Host(pane("hermes", Status.IDLE).copy(backgroundBusy = true))
        assertEquals(RelayResult.REFUSED to RelayRefusal.BACKGROUND, send(host, force = false, idleLane = true))
        assertEquals(RelayResult.SENT to null, send(host, force = true))
        assertEquals(1, host.writes.size)
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
        assertEquals("scrolled back hides the input line", RelayRefusal.NO_INPUT, send(Host(host.pane), force = true).second)
        assertTrue(host.writes.isEmpty())
    }
}
