package com.billmongan.herdrdroid.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LanDiscovery228Test {
    @Test fun pointToPointPrefixIncludesOnlyTheOtherEndpoint() {
        assertEquals(listOf("192.0.2.1"), subnetHosts("192.0.2.0", 31))
        assertEquals(listOf("192.0.2.0"), subnetHosts("192.0.2.1", 31))
        assertEquals(listOf("192.0.2.2"), subnetHosts("192.0.2.1", 30))
    }

    @Test fun hostOnlyAndInvalidPrefixesHaveNoNeighbors() {
        for (prefix in listOf(32, -1, 33)) {
            assertEquals(emptyList<String>(), subnetHosts("192.0.2.1", prefix))
        }
    }

    @Test fun malformedIpv4NeverProducesHostCandidates() {
        for (address in listOf("192.bad.0.2.1", "192..0.2.1", "192.0.2.+1", "192.0.2.256")) {
            assertEquals(emptyList<String>(), subnetHosts(address, 24))
        }
    }

    @Test fun discoveryStopsWhenTheCollectionWindowEnds() = runBlocking {
        val calls = mutableListOf<String>()
        collectLanDiscovery({ calls.add("start") }, { calls.add("wait") }, { calls.add("stop") })
        assertEquals(listOf("start", "wait", "stop"), calls)
    }

    @Test fun cancellingTheScannerStopsDiscoveryAndDoesNotContinue() = runBlocking {
        val collecting = CompletableDeferred<Unit>()
        var stopped = 0
        var continued = false
        val scan = launch {
            collectLanDiscovery({}, {
                collecting.complete(Unit)
                awaitCancellation()
            }, { stopped++ })
            continued = true
        }
        collecting.await()
        scan.cancelAndJoin()
        assertEquals(1, stopped)
        assertFalse(continued)
    }

    @Test fun aFailedStartDoesNotAttemptToStopAnUnregisteredListener() = runBlocking {
        val calls = mutableListOf<String>()
        val failure = runCatching {
            collectLanDiscovery(
                { throw IllegalStateException("registration failed") },
                { calls.add("wait") },
                { calls.add("stop") },
            )
        }.exceptionOrNull()
        assertEquals("registration failed", failure?.message)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun aStopErrorDoesNotReplaceCancellation() = runBlocking {
        val failure = runCatching {
            collectLanDiscovery({}, { throw CancellationException("scan cancelled") }, {
                throw IllegalStateException("already stopped")
            })
        }.exceptionOrNull()
        assertEquals("scan cancelled", failure?.message)
    }
}
