package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 2.0.1 review finding 1: a local voice message is bound to the occupant that was read back. Fake transport only. */
class LocalVoiceOccupantTest {
    private val pane = Pane("pane-1", "tab-1", "ws-1", "codex", Status.IDLE, "Codex", "/project", scrollOffset = 0, terminalId = "term-1", sessionId = "session-1")

    private class Fake(var current: Pane?) : LocalPromptSend.Port {
        var afterViewport: () -> Unit = {}
        var reads = 0
        val writes = mutableListOf<Pair<String, String>>()
        override suspend fun pane(id: String): Pane? { reads++; return current?.takeIf { it.id == id } }
        override suspend fun viewport(id: String): String { afterViewport(); return "❯ " }
        override fun blocked(fresh: Pane, viewport: String) = false
        override suspend fun send(fresh: Pane, text: String, permit: WritePermit) {
            permit.admit()
            writes += fresh.id to text
        }
    }

    private val gate = WriteGate()
    private val sender = LocalPromptSend(PaneDispatch(), gate)

    @Test fun `unchanged occupant gets exactly one write`() = runBlocking {
        val fake = Fake(pane)
        val r = sender.send("local", Occupant.of(pane), "Check the build", { true }, fake)
        assertEquals(LocalPromptSend.Outcome.SENT, r.outcome)
        assertEquals(listOf("pane-1" to "Check the build"), fake.writes)
    }

    @Test fun `only the session changes after readback, so nothing is written`() = runBlocking {
        val fake = Fake(pane)
        // SSH, pane ID, agent, folder and navigation stay the same; only the agent session is new.
        fake.afterViewport = { fake.current = pane.copy(sessionId = "session-2") }
        val r = sender.send("local", Occupant.of(pane), "Check the build", { true }, fake)
        assertEquals(LocalPromptSend.Outcome.REFUSED, r.outcome)
        assertTrue(fake.writes.isEmpty())
    }

    @Test fun `a new session before the first read is refused too`() = runBlocking {
        val fake = Fake(pane.copy(sessionId = "session-2"))
        assertEquals(LocalPromptSend.Outcome.REFUSED, sender.send("local", Occupant.of(pane), "x", { true }, fake).outcome)
        val terminal = Fake(pane.copy(terminalId = "term-2"))
        assertEquals(LocalPromptSend.Outcome.REFUSED, sender.send("local", Occupant.of(pane), "x", { true }, terminal).outcome)
        assertTrue(fake.writes.isEmpty() && terminal.writes.isEmpty())
    }

    @Test fun `no stable identity means no send and no reads`() = runBlocking {
        val bare = pane.copy(terminalId = null, sessionId = null)
        val fake = Fake(bare)
        assertFalse(Occupant.of(bare).stable)
        assertEquals(LocalPromptSend.Outcome.REFUSED, sender.send("local", Occupant.of(bare), "x", { true }, fake).outcome)
        assertEquals(0, fake.reads)
        assertTrue(fake.writes.isEmpty())
    }

    @Test fun `the voice target identity changes with the session`() {
        assertNotEquals(Occupant.of(pane), Occupant.of(pane.copy(sessionId = "session-2")))
        // Status and title are not identity: a busy redraw does not invalidate a draft.
        assertEquals(Occupant.of(pane), Occupant.of(pane.copy(status = Status.WORKING, title = "other")))
    }

    @Test fun `a pane being closed refuses before any read`() = runBlocking {
        val fake = Fake(pane)
        gate.beginClose(listOf("local" to "pane-1"))
        assertEquals(LocalPromptSend.Outcome.REFUSED, sender.send("local", Occupant.of(pane), "x", { true }, fake).outcome)
        assertEquals(0, fake.reads)
        gate.endClose(listOf("local" to "pane-1"))
        assertEquals(LocalPromptSend.Outcome.SENT, sender.send("local", Occupant.of(pane), "x", { true }, fake).outcome)
    }
}
