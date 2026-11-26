package com.billmongan.herdrdroid.fleet

import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    @Test fun aSlowWatcherDoesNotBlockAnotherServerEvenOnLaterPolls() = runBlocking {
        val slow = FakeEndpoint("slow")
        val fast = FakeEndpoint("fast")
        slow.pause = CompletableDeferred()
        val fastSeenTwice = CompletableDeferred<Unit>()
        val fleet = Fleet(this, { Credentials(null, null) }, { null }, {
            if (it.server.id == fast.server.id && fast.reads == 2) fastSeenTwice.complete(Unit)
        }).apply { everyMs = 10L }
        val clients = Fleet::class.java.getDeclaredField("clients").apply { isAccessible = true }
        clients.set(fleet, linkedMapOf(slow.server.id to (slow.ssh to slow.herdr),
            fast.server.id to (fast.ssh to fast.herdr)))

        try {
            fleet.watch(listOf(slow.server, fast.server))
            withTimeout(2_000) { slow.entered.await(); fastSeenTwice.await() }
            assertEquals("Only one poll may be in flight for a server", 1, slow.reads)
        } finally {
            fleet.stop()
        }
    }

    @Test fun aTakenBrowserBridgeDoesNotPublishAnInFlightWatcherRead() = runBlocking {
        val taken = FakeEndpoint("taken")
        val resume = CompletableDeferred<Unit>()
        val sweepEntered = CompletableDeferred<Unit>()
        val sweepExited = CompletableDeferred<Unit>()
        var published = 0
        val fleet = Fleet(this, { Credentials(null, null) }, { null }, { published++ },
            busyOf = {
                // Model a command that completes after ownership moved to the foreground.
                withContext(NonCancellable) {
                    sweepEntered.complete(Unit)
                    resume.await()
                    sweepExited.complete(Unit)
                    emptyMap()
                }
            })
        val clients = Fleet::class.java.getDeclaredField("clients").apply { isAccessible = true }
        clients.set(fleet, linkedMapOf(taken.server.id to (taken.ssh to taken.herdr)))

        try {
            fleet.watch(listOf(taken.server))
            withTimeout(2_000) { sweepEntered.await() }
            assertSame(taken.ssh, fleet.take(taken.server.id)?.first)
            resume.complete(Unit)
            withTimeout(2_000) { sweepExited.await() }
            yield()
            assertEquals("The watcher must leave a handed-off bridge to its new owner", 1, taken.reads)
            assertEquals("A handed-off watcher's late result must not be published", 0, published)
        } finally {
            resume.complete(Unit)
            fleet.stop()
            taken.ssh.close()
        }
    }
}
