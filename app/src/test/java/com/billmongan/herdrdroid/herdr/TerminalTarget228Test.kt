package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TerminalTarget228Test {
    private val selected = Pane("w1:p2", "w1:t2", "w1", "claude", Status.NEEDS_YOU, "", "/fixture",
        sessionId = "session-a", terminalId = "terminal-a")
    private val moved = selected.copy(id = "w2:p9", tabId = "w2:t9", workspaceId = "w2")
    private val replacement = selected.copy(terminalId = "terminal-b")

    private fun json(p: Pane) = """{"pane_id":"${p.id}","tab_id":"${p.tabId}","workspace_id":"${p.workspaceId}","agent":"claude","cwd":"/fixture","terminal_id":"${p.terminalId}","agent_session":{"agent":"claude","kind":"id","value":"${p.sessionId}"},"scroll":{"offset_from_bottom":17}}"""
    private fun missing(id: String) = ExecResult(1, "", """{"error":{"code":"pane_not_found","message":"pane $id not found"},"id":"cli:request"}""")

    private inner class Remote : Herdr.Commands {
        override var connection: Any = Any()
        var panes = listOf(selected)
        var beforeWrite: (String) -> ExecResult = { ExecResult(0, OCCUPANT_PASSED_MARK, "") }
        var afterLookup: () -> Unit = {}
        val sends = mutableListOf<String>()
        var lists = 0
        var reads = 0
        val client = Herdr(HerdrSsh("127.0.0.1", 1, "fixture", Credentials(null, null), null, { null }),
            "/fixture/herdr", Backend.TUI, null, this)

        override suspend fun bulk(command: String): ExecResult = error(command)

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            assertSame(connection, expectedConnection)
            if (command.endsWith("'pane' 'list'")) {
                lists++
                return ExecResult(0, """{"result":{"panes":[${panes.joinToString { json(it) }}]}}""", "")
            }
            if (command.contains("'pane' 'get'")) {
                val id = command.substringAfter("'pane' 'get' '").substringBefore("'")
                val pane = panes.singleOrNull { it.id == id } ?: return missing(id)
                afterLookup()
                return ExecResult(0, """{"result":{"pane":${json(pane)}}}""", "")
            }
            if (command.contains("'pane' 'read'")) {
                reads++
                val id = command.substringAfter("'pane' 'read' '").substringBefore("'")
                return if (panes.any { it.id == id }) ExecResult(0, "Current question", "") else missing(id)
            }
            assertTrue(command, " && echo $OCCUPANT_PASSED_MARK && " in command)
            val id = command.substringAfterLast("'pane' 'send-keys' '").substringBefore("'")
            assertTrue(command.endsWith("'down'"))
            sends += id
            return beforeWrite(id)
        }
    }

    @Test fun aReusedPublicIdPaintsOnlyTheVerifiedOriginalTerminal() = runBlocking {
        val remote = Remote().apply { panes = listOf(replacement, moved) }
        val read = mutableListOf<String>()
        val (target, text) = remote.client.readTerminalFrame(selected, remote.connection) { pane ->
            read += pane.id
            "original screen"
        }
        assertEquals(listOf(moved.id), read)
        assertEquals(moved.id, target.id)
        assertEquals("original screen", text)
        remote.client.sendTerminalKeys(target, listOf("down"), remote.connection) { true }
        assertEquals(listOf(moved.id), remote.sends)
    }

    @Test fun anOccupantChangedDuringReadCanNeverSupplyAPaintedFrame() = runBlocking {
        val remote = Remote()
        val failure = runCatching {
            remote.client.readTerminalFrame(selected, remote.connection) {
                remote.panes = listOf(replacement)
                "replacement screen"
            }
        }.exceptionOrNull()
        assertTrue(failure is HerdrError)
        assertTrue(remote.sends.isEmpty())
    }

    @Test fun theWritePermitRefusesAVisitChangeAtAdmission() = runBlocking {
        val remote = Remote()
        var validations = 0
        val failure = runCatching {
            remote.client.sendTerminalKeys(selected, listOf("down"), remote.connection) { ++validations < 3 }
        }.exceptionOrNull()
        assertTrue(failure is WriteRevoked)
        assertTrue(remote.sends.isEmpty())
    }

    @Test fun terminalKeysResolveAwayFromAReusedPaneId() = runBlocking {
        val remote = Remote().apply { panes = listOf(replacement, moved) }
        remote.client.sendTerminalKeys(selected, listOf("down"), remote.connection) { true }
        assertEquals(listOf(moved.id), remote.sends)
        assertEquals(1, remote.lists)
    }

    @Test fun explicitLookupFailureAtSendRetriesOnlyOnceOnTheSameTerminal() = runBlocking {
        val remote = Remote()
        remote.beforeWrite = { id ->
            if (id == selected.id) {
                remote.panes = listOf(replacement, moved)
                missing(id).let { result -> result.copy(out = "$OCCUPANT_PASSED_MARK${'\n'}${result.err}", err = "fixture warning") }
            } else ExecResult(0, OCCUPANT_PASSED_MARK, "")
        }
        remote.client.sendTerminalKeys(selected, listOf("down"), remote.connection) { true }
        assertEquals(listOf(selected.id, moved.id), remote.sends)
        assertEquals(1, remote.lists)
    }

    @Test fun ambiguousDeliveryAndChangedConnectionNeverRetry() = runBlocking {
        val ambiguous = Remote().apply { beforeWrite = { ExecResult(1, OCCUPANT_PASSED_MARK, "pane send failed") } }
        assertTrue(runCatching {
            ambiguous.client.sendTerminalKeys(selected, listOf("down"), ambiguous.connection) { true }
        }.exceptionOrNull() is DeliveryUncertain)
        assertEquals(listOf(selected.id), ambiguous.sends)
        assertEquals(0, ambiguous.lists)

        val reconnected = Remote()
        reconnected.afterLookup = { reconnected.connection = Any() }
        assertTrue(runCatching {
            reconnected.client.sendTerminalKeys(selected, listOf("down"), reconnected.connection) { true }
        }.exceptionOrNull() is HerdrError)
        assertTrue(reconnected.sends.isEmpty())
    }

    @Test fun paneRecoveryRequiresExactlyOneUnchangedTerminalAndPromotesNoReplacement() = runBlocking {
        val remote = Remote().apply { panes = listOf(replacement, moved, moved.copy(id = "w2:p8")) }
        assertTrue(runCatching { remote.client.resolveTerminalPane(selected, remote.connection) }.exceptionOrNull() is HerdrError)
        remote.panes = listOf(replacement, moved.copy(sessionId = "session-b"))
        assertTrue(runCatching { remote.client.resolveTerminalPane(selected, remote.connection) }.exceptionOrNull() is HerdrError)
        assertTrue(remote.sends.isEmpty())
    }

    @Test fun anExplicitMissingReadDoesNotRetryTheStalePublicId() = runBlocking {
        val remote = Remote().apply { panes = listOf(moved) }
        val failure = runCatching {
            recoverPaneRead(
                recent = { remote.client.read(selected.id, expectedConnection = remote.connection) },
                visible = { error("must re-resolve instead of rereading the stale ID") },
                shortRecent = { error("must re-resolve instead of rereading the stale ID") },
                stopOnMissing = selected.id,
            )
        }.exceptionOrNull() as HerdrError
        assertTrue(failure.isMissingPane(selected.id))
        val live = remote.client.resolveTerminalPane(selected, remote.connection)
        assertEquals("Current question", remote.client.read(live.id, expectedConnection = remote.connection))
        assertEquals(2, remote.reads)
    }
}
