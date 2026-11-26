package com.billmongan.herdrdroid.fleet

import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The watcher must release ownership of a connection it hands to the foreground. */
class FleetHandoff228Test {
    private class FakeEndpoint(id: String) : Herdr.Commands {
        val server = Server(id = id, name = id, host = "fixture.invalid", port = 22,
            user = "fixture", herdrPath = "herdr", fingerprint = "fixture", backend = "core",
            wsUrl = "wss://fixture.invalid/ssh")
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null),
            server.fingerprint, { null }, server.wsUrl)
        val herdr = Herdr(ssh, server.herdrPath, Backend.CORE, null, this)
        override val connection = Any()
        var reads = 0
        val entered = CompletableDeferred<Unit>()
        var pause: CompletableDeferred<Unit>? = null

        override suspend fun bulk(command: String): ExecResult {
            reads++
            entered.complete(Unit)
            pause?.await()
            return ExecResult(0, """{"terminals":[]}""", "")
        }

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult =
            error("Unexpected immediate command")
    }

    @Test fun aTakenBrowserBridgeIsNotPolledFromAnOlderWatchRound() = runBlocking {
        val first = FakeEndpoint("first")
        val taken = FakeEndpoint("taken")
        val resume = CompletableDeferred<Unit>().also { first.pause = it }
        val seen = CompletableDeferred<Unit>()
        val fleet = Fleet(this, { Credentials(null, null) }, { null }, {
            if (it.server.id == first.server.id) seen.complete(Unit)
        })
        val clients = Fleet::class.java.getDeclaredField("clients").apply { isAccessible = true }
        clients.set(fleet, linkedMapOf(first.server.id to (first.ssh to first.herdr),
            taken.server.id to (taken.ssh to taken.herdr)))

        try {
            fleet.watch(listOf(first.server, taken.server))
            withTimeout(2_000) { first.entered.await() }
            assertSame(taken.ssh, fleet.take(taken.server.id)?.first)
            resume.complete(Unit)
            withTimeout(2_000) { seen.await() }
            yield()
            assertEquals("The watcher must leave a handed-off bridge to its new owner", 0, taken.reads)
        } finally {
            fleet.stop()
            taken.ssh.close()
        }
    }
}
