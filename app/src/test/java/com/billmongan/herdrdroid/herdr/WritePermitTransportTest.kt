package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.ConnectionChanged
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/**
 * 2.0.1 review finding 4: a revocable permit is checked at the transport boundary, after the
 * transport holds its lock, and an outcome after submission is uncertain and never retried.
 * The transport is an inert fake that mirrors HerdrSsh: take the lock, admit the permit, write.
 */
class WritePermitTransportTest {
    private val pane = Pane("pane-1", "tab-1", "ws-1", "codex", Status.IDLE, "Codex", "/project", scrollOffset = 0, terminalId = "term-1", sessionId = "session-1")

    private class Transport(val pane: Pane) : PromptRelayPort {
        val connection = Any()
        val sshLock = Mutex()
        val writes = mutableListOf<String>()
        var sendCalls = 0
        var cancelAfterAccept = false
        val waiting = CompletableDeferred<Unit>()
        override val connectionIdentity get() = connection
        override suspend fun pane(id: String, connection: Any) = pane.takeIf { it.id == id }
        override suspend fun hasWork(pane: Pane, connection: Any) = false
        override suspend fun viewport(id: String, connection: Any) = "❯ "
        override suspend fun send(pane: Pane, text: String, connection: Any) = error("the permitted overload is used")
        override suspend fun send(pane: Pane, text: String, connection: Any, permit: WritePermit) {
            sendCalls++
            waiting.complete(Unit)
            sshLock.withLock {
                permit.admit()
                writes += text
            }
            if (cancelAfterAccept) throw CancellationException("cancelled after the command was accepted")
        }
    }

    private val transport = Transport(pane)
    private val gate = WriteGate()
    private val relay = PromptRelay(PaneDispatch(), { transport }, gate) { _, _ -> true }

    @Test fun `revoked while held after the UI gate means zero writes`() = runBlocking {
        val target = checkNotNull(relay.bind("remote", pane))
        transport.sshLock.lock() // another command holds the SSH lock
        val sending = async { relay.send(target, "deploy") { true } }
        transport.waiting.await()
        yield()
        gate.revokeAll() // the app locked, or hands-free ended
        transport.sshLock.unlock()
        assertEquals(RelayResult.REFUSED, sending.await())
        assertTrue(transport.writes.isEmpty())
    }

    @Test fun `cancelled after acceptance is one write, uncertain, not retried`() = runBlocking {
        val target = checkNotNull(relay.bind("remote", pane))
        transport.cancelAfterAccept = true
        assertEquals(RelayResult.UNCERTAIN, relay.send(target, "deploy") { true })
        assertEquals(listOf("deploy"), transport.writes)
        assertEquals(1, transport.sendCalls)
    }

    @Test fun `cancelled while waiting before submission sends nothing`() = runBlocking {
        val target = checkNotNull(relay.bind("remote", pane))
        transport.sshLock.lock()
        val sending = async { relay.send(target, "deploy") { true } }
        transport.waiting.await()
        sending.cancel()
        transport.sshLock.unlock()
        try { sending.await(); fail("expected cancellation") } catch (_: CancellationException) {}
        assertTrue(transport.writes.isEmpty())
    }

    /** The production Herdr and HerdrSsh, never connected: nothing is opened or sent. */
    private fun inertHerdr() = Herdr(HerdrSsh("127.0.0.1", 9, "nobody", Credentials(null, null), null, { null }), "herdr")

    @Test fun `the production send path carries the permit to the transport`() = runBlocking {
        val port = inertHerdr().relayPort(ByteArray(0))
        val revoked = WritePermit().also { it.revoke() }
        try { port.send(pane, "deploy", Any(), revoked); fail("expected WriteRevoked") } catch (_: WriteRevoked) {}
        // An open permit is not claimed before the transport holds the expected connection.
        val open = WritePermit()
        try { port.send(pane, "deploy", Any(), open); fail("expected ConnectionChanged") } catch (_: ConnectionChanged) {}
        assertFalse(open.submitted)
        assertFalse(open.revoked)
    }

    @Test fun `permit states are one-way`() {
        val p = WritePermit()
        assertTrue(p.claim())
        assertFalse("a submitted write cannot be revoked", p.revoke())
        assertTrue(p.submitted)
        val q = WritePermit()
        assertTrue(q.revoke())
        assertFalse(q.claim())
        assertThrows(WriteRevoked::class.java) { q.admit() }
        (null as WritePermit?).admit() // no permit: unchanged behaviour
    }

    @Test fun `revokeAll touches only open permits and the gate refuses closing panes`() {
        val g = WriteGate()
        val sent = checkNotNull(g.open("s" to "p"))
        val open = checkNotNull(g.open("s" to "p"))
        assertTrue(sent.claim())
        g.revokeAll()
        assertTrue(sent.submitted)
        assertTrue(open.revoked)
        g.beginClose(listOf("s" to "p"))
        assertNull(g.open("s" to "p"))
        g.endClose(listOf("s" to "p"))
        assertNotNull(g.open("s" to "p"))
    }
}
