package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteSubmit228Test {
    @Test fun pasteAndEnterUseOnePinnedDispatch() = runBlocking {
        val host = Host()
        host.herdr.typeAndEnter("p1", "/exit", host.connection)
        assertEquals(1, host.writes.size)
        assertSame(host.connection, host.writes.single().second)
        assertNativeSubmit(host.writes.single().first, "/exit")
    }

    @Test fun plainPaneFallbackAlsoUsesOnePinnedDispatch() = runBlocking {
        val host = Host()
        val shell = Pane("p1", "t1", "w1", null, Status.IDLE, "", "/fixture")
        host.herdr.sendMessage(shell, "/exit", expectedConnection = host.connection)
        assertEquals(1, host.writes.size)
        assertSame(host.connection, host.writes.single().second)
        assertNativeSubmit(host.writes.single().first, "/exit")
    }

    @Test fun uncertainNativeFailureDoesNotSplitRetryOrClear() = runBlocking {
        for (failed in listOf(ExecResult(1, "", "fixture lost acknowledgement"),
            ExecResult(2, "", "fixture native command unsupported"),
            ExecResult(-2, "", "fixture timed out"))) {
            val host = Host(failed)
            val result = runCatching { host.herdr.typeAndEnter("p1", "/exit", host.connection) }
            assertTrue(result.exceptionOrNull() is DeliveryUncertain)
            assertEquals(1, host.writes.size)
            assertNativeSubmit(host.writes.single().first, "/exit")
        }
    }

    @Test fun aMissingPaneIsAConfirmedRefusal() = runBlocking {
        val failure = """{"error":{"code":"pane_not_found","message":"pane p1 not found"},"id":"cli:request"}"""
        val host = Host(ExecResult(1, "", failure))
        val error = runCatching { host.herdr.typeAndEnter("p1", "/exit", host.connection) }.exceptionOrNull()
        assertTrue(error is HerdrError && error.isMissingPane("p1"))
        assertNativeSubmit(host.writes.single().first, "/exit")
    }

    @Test fun aBoundTerminalSubmissionKeepsTheOccupantGuard() = runBlocking {
        val host = Host()
        val shell = Pane("p1", "t1", "w1", null, Status.IDLE, "", "/fixture", terminalId = "fixture-terminal")
        host.herdr.sendMessage(shell, "/exit", expectedConnection = host.connection, occupant = "fixture-occupant")
        val command = host.writes.single().first
        assertTrue(command, "'fixture-occupant' '-'" in command)
        assertTrue(command, " && echo $OCCUPANT_PASSED_MARK && " in command)
        assertNativeSubmit(command, "/exit")
    }

    @Test fun explicitSettlingFailureIsUncertainAndNeverClearsOrRetries() = runBlocking {
        val host = Host(ExecResult(1, "@@herdrdroid-text-pasted@@\n", "fixture submit failed"))
        val result = runCatching { host.herdr.typeAndEnter("p1", "/privacy", host.connection, settleMillis = 700) }
        assertTrue(result.exceptionOrNull() is DeliveryUncertain)
        assertPasteBeforeEnter(host.writes.single().first)
    }

    @Test fun theSpecialSettlingDelayStaysInsideTheDispatch() = runBlocking {
        val host = Host()
        host.herdr.typeAndEnter("p1", "/privacy", host.connection, settleMillis = 700)
        val whole = host.writes.single().first
        assertTrue(whole, whole.contains(" && sleep 0.700 && "))
        assertPasteBeforeEnter(whole)
    }

    private fun assertNativeSubmit(command: String, text: String) {
        assertTrue(command, command.endsWith("'pane' 'run' 'p1' '$text'"))
        assertTrue(command, listOf("'send-text'", "'send-keys'", "'ctrl+u'", "'backspace'", "sleep ").none(command::contains))
    }

    private fun assertPasteBeforeEnter(command: String) {
        val paste = command.indexOf("'send-text' 'p1'")
        val submit = command.indexOf("'send-keys' 'p1' 'enter'")
        assertTrue(command, paste >= 0 && submit > paste)
        assertTrue(command, command.substring(paste, submit).contains(" && echo @@herdrdroid-text-pasted@@ && "))
        assertTrue(command, listOf("backspace", "ctrl+u", "ctrl+e").none(command::contains))
    }

    private class Host(val result: ExecResult = ExecResult(0, "", "")) : Herdr.Commands {
        override val connection = Any()
        val writes = mutableListOf<Pair<String, Any?>>()
        val herdr = Herdr(HerdrSsh("127.0.0.1", 1, "fixture", Credentials(null, null), null, { null }),
            "/fixture/herdr", Backend.TUI, null, this)

        override suspend fun bulk(command: String): ExecResult = error("Unexpected bulk write")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            writes += command to expectedConnection
            return result
        }
    }
}
