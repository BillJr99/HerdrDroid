package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.1 review: a relayed message is never typed into a panel, even one the app only partly
 * identifies (so it won't close it) with the empty composer still drawn under it.
 */
class PanelRelay221Test {
    private class Host(var pane: Pane) : PromptRelayPort {
        val connection: Any = Any()
        var viewport = "❯ "
        val writes = mutableListOf<String>()
        override val connectionIdentity get() = connection
        override suspend fun pane(id: String, connection: Any) = pane.takeIf { it.id == id && this.connection === connection }
        override suspend fun hasWork(pane: Pane, connection: Any) = false
        override suspend fun viewport(id: String, connection: Any) = viewport
        override suspend fun send(pane: Pane, text: String, connection: Any) { writes += text }
    }

    private fun boxed(title: String, footer: String) = buildString {
        appendLine("  ┌──────────────────────────────────────────────────────────────────────┐")
        appendLine("  │ ${title.padEnd(68)} │")
        appendLine("  │ ${"Model      sample".padEnd(68)} │")
        appendLine("  │ ${footer.padEnd(68)} │")
        appendLine("  └──────────────────────────────────────────────────────────────────────┘")
        appendLine()
        appendLine("❯ ")
    }

    @Test fun aRelayRefusesAnyPanelKnownOrPartial() = runBlocking {
        for (agent in listOf("claude", "codex", "kiro", "pi", "omp", "vibe", "hermes", "copilot", "muse")) {
            val pane = Pane("pane-1", "tab-1", "ws-1", agent, Status.IDLE, agent, "/project", scrollOffset = 0, terminalId = "term-1", sessionId = "session-1")
            val host = Host(pane)
            val relay = PromptRelay(PaneDispatch(), { if (it == "remote") host else null }) { _, _ -> true }
            val target = checkNotNull(relay.bind("remote", pane))
            for (screen in listOf(boxed("Status", "Type to search · Esc close"), boxed("Select model", "Enter to confirm · Esc to exit"),
                    boxed("Sample", "Esc close"), boxed("Status", "Esc close"))) {
                host.viewport = screen
                assertTrue("$agent: a panel shows", infoPanelOn(agent, screen) != null)
                assertEquals("$agent:\n$screen", RelayResult.REFUSED, relay.send(target, "test") { true })
            }
            assertTrue(agent, host.writes.isEmpty())
            // With the panel gone, the same relay goes (Codex: the composer the existing relay tests use).
            if (agent == "codex") {
                host.viewport = "❯ "
                assertEquals(RelayResult.SENT, relay.send(target, "test") { true })
            }
        }
    }
}
