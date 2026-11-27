package com.billmongan.herdrdroid.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanOrchestration229Test {
    @Test fun unavailableNetworkDoesNotDiscoverOrProbe() = runBlocking {
        suspend fun verify(network: () -> Pair<String, Int>?) {
            scanLan(network, { error("discover") }, { error("probe") }, { error("reverse") },
                { _, _ -> error("progress") }, { error("found") })
        }
        verify { null }
        verify { throw SecurityException("network state denied") }
    }

    @Test fun mdnsAddressIsReportedOnceWithoutReverseLookup() = runBlocking {
        val found = mutableListOf<LanHost>()
        val progress = mutableListOf<Pair<Int, Int>>()
        scanLan(
            network = { "192.0.2.1" to 30 },
            discover = { mapOf("192.0.2.2" to "first", "192.0.2.2" to "server") },
            probe = { "SSH-2.0-OpenSSH_9.6" },
            reverse = { error("mDNS already named this host") },
            onProgress = { done, total -> progress += done to total },
            onFound = { found += it },
        )
        assertEquals(listOf(1 to 1), progress)
        assertEquals(listOf(LanHost("192.0.2.2", "server", "OpenSSH 9.6")), found)
    }

    @Test fun cancelledLookupCannotPublishAHostAfterReplacementScan() = runBlocking {
        val pending = CompletableDeferred<String?>()
        val entered = CompletableDeferred<Unit>()
        val found = mutableListOf<LanHost>()
        suspend fun scan(reverse: suspend (String) -> String?) = scanLan(
            network = { "192.0.2.1" to 30 },
            discover = { emptyMap() },
            probe = { "SSH-2.0-test" },
            reverse = reverse,
            onProgress = { _, _ -> },
            onFound = { found += it },
        )
        val old = async {
            scan {
                entered.complete(Unit)
                pending.await()
            }
        }
        withTimeout(2_000) { entered.await() }
        old.cancelAndJoin()
        scan { "replacement" }
        pending.complete("stale")
        assertEquals(listOf(LanHost("192.0.2.2", "replacement", "test")), found)
    }

    @Test fun blockedResolverCannotHoldUpTheScanOrStartAnUnboundedQueue() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val lookup = LanReverseLookup(threads = 1, timeoutMs = 300) {
            started.complete(Unit)
            try {
                while (true) {
                    try {
                        check(release.await(5, TimeUnit.SECONDS))
                        break
                    } catch (_: InterruptedException) {
                        // Platform DNS may keep blocking after interruption.
                    }
                }
                "late-name"
            } finally {
                finished.countDown()
            }
        }
        val scan = async { lookup.name("192.0.2.2") }
        try {
            withTimeout(2_000) { started.await() }
            assertNull(withTimeout(2_000) { scan.await() })
            assertEquals(1L, finished.count)
            assertNull(withTimeout(2_000) { lookup.name("192.0.2.3") })
            assertEquals(1L, finished.count)
        } finally {
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun lateReverseLookupAfterTimeoutLeavesWorkerUsable() =
        verifiesLateReverseLookup(cancel = false)

    @Test fun lateReverseLookupAfterCancellationLeavesWorkerUsable() =
        verifiesLateReverseLookup(cancel = true)

    private fun verifiesLateReverseLookup(cancel: Boolean) = runBlocking {
        val originalWorker = CompletableDeferred<Thread>()
        val replacementWorker = AtomicReference<Thread>()
        val unhandled = AtomicReference<Throwable>()
        val release = CountDownLatch(1)
        val lookup = LanReverseLookup(threads = 1, timeoutMs = if (cancel) 5_000 else 300) { ip ->
            if (ip == "192.0.2.2") {
                val thread = Thread.currentThread()
                thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                    unhandled.set(error)
                }
                originalWorker.complete(thread)
                while (true) {
                    try {
                        check(release.await(5, TimeUnit.SECONDS))
                        break
                    } catch (_: InterruptedException) {
                        // An interrupted platform DNS lookup can still return a result.
                    }
                }
                "late-name"
            } else {
                replacementWorker.set(Thread.currentThread())
                "fresh-name"
            }
        }
        val first = async { lookup.name("192.0.2.2") }
        try {
            withTimeout(2_000) { originalWorker.await() }
            if (cancel) first.cancelAndJoin() else assertNull(withTimeout(2_000) { first.await() })
        } finally {
            release.countDown()
        }
        val fresh = withTimeout(2_000) {
            var name = lookup.name("192.0.2.3")
            while (name == null) {
                delay(10)
                name = lookup.name("192.0.2.3")
            }
            name
        }
        assertEquals("fresh-name", fresh)
        assertEquals(originalWorker.await(), replacementWorker.get())
        assertNull(unhandled.get())
    }
}
