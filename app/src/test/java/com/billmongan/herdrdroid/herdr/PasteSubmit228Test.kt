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
        assertPasteBeforeEnter(host.writes.single().first)
    }

    @Test fun plainPaneFallbackAlsoUsesOnePinnedDispatch() = runBlocking {
        val host = Host()
        val shell = Pane("p1", "t1", "w1", null, Status.IDLE, "", "/fixture")
        host.herdr.sendMessage(shell, "/exit", expectedConnection = host.connection)
        assertEquals(1, host.writes.size)
        assertSame(host.connection, host.writes.single().second)
        assertPasteBeforeEnter(host.writes.single().first)
    }

    @Test fun failureAfterPasteIsUncertainAndNeverClearsOrRetries() = runBlocking {
        val host = Host(ExecResult(1, "@@herdrdroid-text-pasted@@\n", "fixture submit failed"))
        val result = runCatching { host.herdr.typeAndEnter("p1", "/exit", host.connection) }
        assertTrue(result.exceptionOrNull() is DeliveryUncertain)
        assertEquals(1, host.writes.size)
        assertPasteBeforeEnter(host.writes.single().first)
    }

    @Test fun aPasteRefusalDoesNotPretendItSubmitted() = runBlocking {
        val host = Host(ExecResult(3, "", "fixture paste refused"))
        val error = runCatching { host.herdr.typeAndEnter("p1", "/exit", host.connection) }.exceptionOrNull()
        assertEquals(3, (error as HerdrError).exit)
        assertEquals(1, host.writes.size)
        assertPasteBeforeEnter(host.writes.single().first)
    }

    @Test fun theSpecialSettlingDelayStaysInsideTheDispatch() = runBlocking {
        val host = Host()
        host.herdr.typeAndEnter("p1", "/privacy", host.connection, settleMillis = 700)
        val whole = host.writes.single().first
        assertTrue(whole, whole.contains(" && sleep 0.700 && "))
        assertPasteBeforeEnter(whole)
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
