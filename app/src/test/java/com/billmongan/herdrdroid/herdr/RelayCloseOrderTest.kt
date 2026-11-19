package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.ConnectionChanged
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** 2.0.1 review finding 2: closing a tab orders against relays to its panes. Fake transport only. */
class RelayCloseOrderTest {
    private val pane = Pane("pane-1", "tab-1", "ws-1", "codex", Status.IDLE, "Codex", "/project", scrollOffset = 0, terminalId = "term-1", sessionId = "session-1")
    private val sibling = pane.copy(id = "pane-2", terminalId = "term-2", sessionId = "session-2")

    /** One fake server: relay reads, the prompt write and the tab close all land in [events]. */
    private class Server(var panes: List<Pane>) : PromptRelayPort, PaneClose.Port {
        var connection: Any = Any()
        val events = mutableListOf<String>()
        var paneCalls = 0
        var pauseOnPaneCall = -1
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var pauseInWrite = false
        override val connectionIdentity get() = connection
        override suspend fun pane(id: String, connection: Any): Pane? {
            val result = panes.singleOrNull { it.id == id }?.takeIf { this.connection === connection }
            if (++paneCalls == pauseOnPaneCall) { paused.complete(Unit); resume.await() }
            return result
        }
        override suspend fun hasWork(pane: Pane, connection: Any) = false
        override suspend fun viewport(id: String, connection: Any) = "❯ "
        override suspend fun send(pane: Pane, text: String, connection: Any) { events += "write ${pane.id}" }
        override suspend fun send(pane: Pane, text: String, connection: Any, permit: WritePermit) {
            permit.admit()
            events += "write ${pane.id}"
            if (pauseInWrite) { paused.complete(Unit); resume.await() }
        }
        override suspend fun panes(connection: Any): List<Pane> {
            if (connection !== this.connection) throw ConnectionChanged()
            return panes
        }
        override suspend fun closeTab(tabId: String, connection: Any) {
            if (connection !== this.connection) throw ConnectionChanged()
            events += "close $tabId"
            panes = panes.filter { it.tabId != tabId }
        }
    }

    private val server = Server(listOf(pane, sibling))
    private val dispatch = PaneDispatch()
    private val gate = WriteGate()
    private val relay = PromptRelay(dispatch, { if (it == "remote") server else null }, gate) { _, _ -> true }
    private val closer = PaneClose(dispatch, gate)

    @Test fun `close confirmed after the final lookup stops the write and then closes`() = runBlocking {
        val target = checkNotNull(relay.bind("remote", pane))
        server.paneCalls = 0
        server.pauseOnPaneCall = 2 // the relay's final idle lookup
        val sending = async { relay.send(target, "deploy") { true } }
        server.paused.await()
        val pending = closer.confirm("remote", pane, server.connection, server.panes)
        val closing = async { closer.run(pending, server) }
        yield()
        assertTrue("the close waits for the relay's lane", server.events.isEmpty())
        server.resume.complete(Unit)
        assertEquals(RelayResult.REFUSED, sending.await())
        assertEquals(PaneClose.Outcome.CLOSED, closing.await().outcome)
        assertEquals(listOf("close tab-1"), server.events)
    }

    @Test fun `close confirmed while the write waits on the transport revokes it`() = runBlocking {
        val target = checkNotNull(relay.bind("remote", pane))
        val transport = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val held = object : PromptRelayPort by server {
            override suspend fun send(pane: Pane, text: String, connection: Any, permit: WritePermit) {
                reached.complete(Unit) // past every UI-side check
                transport.await() // waiting for the SSH lock
                server.send(pane, text, connection, permit)
            }
        }
        val heldRelay = PromptRelay(dispatch, { if (it == "remote") held else null }, gate) { _, _ -> true }
        val heldTarget = checkNotNull(heldRelay.bind("remote", pane))
        assertNotNull(target)
        val sending = async { heldRelay.send(heldTarget, "deploy") { true } }
        reached.await()
        val pending = closer.confirm("remote", pane, server.connection, server.panes)
        transport.complete(Unit)
        assertEquals(RelayResult.REFUSED, sending.await())
        assertEquals(PaneClose.Outcome.CLOSED, closer.run(pending, server).outcome)
        assertEquals(listOf("close tab-1"), server.events)
    }

    @Test fun `a write already submitted finishes before the close`() = runBlocking {
        val target = checkNotNull(relay.bind("remote", pane))
        server.pauseInWrite = true
        val sending = async { relay.send(target, "deploy") { true } }
        server.paused.await()
        val pending = closer.confirm("remote", pane, server.connection, server.panes)
        val closing = async { closer.run(pending, server) }
        yield()
        assertEquals(listOf("write pane-1"), server.events)
        server.resume.complete(Unit)
        assertEquals(RelayResult.SENT, sending.await())
        assertEquals(PaneClose.Outcome.CLOSED, closing.await().outcome)
        assertEquals(listOf("write pane-1", "close tab-1"), server.events)
    }

    @Test fun `no new send to any pane of the tab once the close is confirmed`() = runBlocking {
        val first = checkNotNull(relay.bind("remote", pane))
        val second = checkNotNull(relay.bind("remote", sibling))
        val pending = closer.confirm("remote", pane, server.connection, server.panes)
        assertEquals(RelayResult.REFUSED, relay.send(first, "a") { true })
        assertEquals(RelayResult.REFUSED, relay.send(second, "b") { true })
        assertEquals(PaneClose.Outcome.CLOSED, closer.run(pending, server).outcome)
        assertEquals(listOf("close tab-1"), server.events)
    }

    @Test fun `the close is pinned to its connection and occupant`() = runBlocking {
        val pending = closer.confirm("remote", pane, server.connection, server.panes)
        server.connection = Any() // reconnected between confirm and close
        assertEquals(PaneClose.Outcome.CHANGED, closer.run(pending, server).outcome)
        val again = closer.confirm("remote", pane, server.connection, server.panes)
        server.panes = listOf(pane.copy(sessionId = "replacement"), sibling)
        assertEquals(PaneClose.Outcome.CHANGED, closer.run(again, server).outcome)
        assertTrue(server.events.isEmpty())
        // The gate reopens after a refused close.
        server.panes = listOf(pane, sibling)
        val target = checkNotNull(relay.bind("remote", pane))
        assertEquals(RelayResult.SENT, relay.send(target, "ok") { true })
    }

    @Test fun `work that waited through a close sees a new epoch`() = runBlocking {
        val key = "remote" to "pane-1"
        val before = gate.epoch(key)
        val pending = closer.confirm("remote", pane, server.connection, server.panes)
        assertEquals(PaneClose.Outcome.CLOSED, closer.run(pending, server).outcome)
        assertFalse(gate.isClosing(key))
        assertNotEquals("a tap queued before the close is dropped after it", before, gate.epoch(key))
    }

    @Test fun `two closes of one tab with different stale views do not deadlock`() = runBlocking {
        val early = pane.copy(id = "pane-0", terminalId = "term-0", sessionId = "session-0") // sorts first
        server.panes = listOf(early, pane, sibling)
        val stale = closer.confirm("remote", pane, server.connection, listOf(pane, sibling))
        val fresh = closer.confirm("remote", pane, server.connection, listOf(early, pane, sibling))
        // The stale close holds pane-1 and pane-2 and pauses on its pane list; the fresh close
        // then holds pane-0 and waits for pane-1; then the stale close finds pane-0 as an extra.
        val listing = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val pausing = object : PaneClose.Port by server {
            override suspend fun panes(connection: Any): List<Pane> {
                listing.complete(Unit); proceed.await(); return server.panes(connection)
            }
        }
        val results = withTimeout(5_000) {
            val a = async { closer.run(stale, pausing).outcome }
            listing.await()
            val b = async { closer.run(fresh, server).outcome }
            yield(); yield()
            proceed.complete(Unit)
            listOf(a.await(), b.await())
        }
        assertEquals(listOf(PaneClose.Outcome.CHANGED, PaneClose.Outcome.CLOSED), results)
        assertTrue(results.toString(), PaneClose.Outcome.CLOSED in results)
        assertEquals(listOf("close tab-1"), server.events)
        for (id in listOf("pane-0", "pane-1", "pane-2")) assertFalse(gate.isClosing("remote" to id))
    }

    @Test fun `a pane that joined the tab is held and closed too`() = runBlocking {
        val newcomer = pane.copy(id = "pane-3", terminalId = "term-3", sessionId = "session-3")
        val pending = closer.confirm("remote", pane, server.connection, listOf(pane))
        server.panes = listOf(pane, newcomer)
        val target = checkNotNull(relay.bind("remote", newcomer))
        val gateSeen = CompletableDeferred<Boolean>()
        val closing = launch {
            val result = closer.run(pending, object : PaneClose.Port by server {
                override suspend fun closeTab(tabId: String, connection: Any) {
                    gateSeen.complete(gate.isClosing("remote" to "pane-3"))
                    server.closeTab(tabId, connection)
                }
            })
            assertEquals(PaneClose.Outcome.CLOSED, result.outcome)
        }
        assertTrue(gateSeen.await())
        closing.join()
        assertEquals(RelayResult.REFUSED, relay.send(target, "late") { true })
        assertEquals(listOf("close tab-1"), server.events)
    }
}
