package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardRelay2210Test {
    private val selected = Pane("pane", "tab", "workspace", "codex", Status.IDLE, "Agent", "/project",
        scrollOffset = 0, terminalId = "terminal", sessionId = "session")
    private val port = object : PromptRelayPort {
        override val connectionIdentity = Any()
        var live = selected
        var beforeRead: (Int) -> String = { "❯ " }
        var viewportReads = 0
        val writes = mutableListOf<String>()
        override suspend fun pane(id: String, connection: Any) = live
        override suspend fun hasWork(pane: Pane, connection: Any) = false
        override suspend fun viewport(id: String, connection: Any) = beforeRead(++viewportReads)
        override suspend fun send(pane: Pane, text: String, connection: Any) = error("Unchecked send")
        override suspend fun sendBoardRequest(pane: Pane, text: String, viewport: String, connection: Any, permit: WritePermit) {
            permit.admit()
            writes.add(text)
        }
    }
    private val relay = PromptRelay(PaneDispatch(), { port }) { _, _ -> true }

    @Test fun busyRequestCanWaitUntilThePaneIsIdle() = runBlocking {
        val target = checkNotNull(relay.bind("server", selected))
        port.live = selected.copy(status = Status.WORKING)
        assertEquals(RelayResult.REFUSED, relay.send(target, "board request", boardRequest = true) { true })
        assertTrue(port.writes.isEmpty())
        port.live = selected
        assertEquals(RelayResult.SENT, relay.send(target, "board request", boardRequest = true) { true })
        assertEquals(listOf("board request"), port.writes)
    }

    @Test fun draftOnFinalReadIsNeverOverwritten() = runBlocking {
        val target = checkNotNull(relay.bind("server", selected))
        port.beforeRead = { read -> if (read == 1) "❯ " else "❯ draft" }
        assertEquals(RelayResult.DRAFT, relay.send(target, "board request", boardRequest = true) { true })
        assertTrue(port.writes.isEmpty())
    }
}
