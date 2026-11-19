package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HeldSendDeadlineTest {
    @Test fun deadlineCancelsAnInFlightReadAndReleasesTheLane() = runBlocking {
        val dispatch = PaneDispatch()
        val key = "server" to "pane"
        var now = 0L
        var reading = false
        var readCancelled = false
        var sends = 0
        val result = withTimeout(2_000) {
            HeldSendRetry(windowMs = 100L, everyMs = 10L, now = { now }, sleep = { now += it }).run(
                0L, "draft", cancelled = { null },
                check = {
                    reading = true
                    assertNull(dispatch.tryAcquire(key))
                    try { awaitCancellation() } finally { readCancelled = true }
                },
                send = { sends++ }, acquire = { dispatch.acquire(key) },
            )
        }
        assertEquals(HeldOutcome.TimedOut("draft", 0), result)
        assertTrue(reading)
        assertTrue(readCancelled)
        assertEquals(0, sends)
        dispatch.tryAcquire(key)!!.invoke()
    }

    @Test fun dismissalWhileWaitingForTheLaneIsNotReportedAsATimeout() = runBlocking {
        val dispatch = PaneDispatch()
        val key = "server" to "pane"
        val release = dispatch.tryAcquire(key)!!
        var now = 0L
        var waitingForLane = false
        var dismissed = false
        var checks = 0
        var sends = 0
        val logs = mutableListOf<String>()
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                HeldSendRetry(windowMs = 100L, everyMs = 10L, now = { now }, sleep = { now += it }).run(
                    0L, "draft", cancelled = { if (dismissed) "dismissed" else null },
                    check = { checks++; null }, send = { sends++ }, log = { logs += it },
                    acquire = { waitingForLane = true; dispatch.acquire(key) },
                )
            }
            assertTrue(waitingForLane)
            dismissed = true
            assertEquals(HeldOutcome.Cancelled("dismissed"), withTimeout(2_000) { pending.await() })
            assertEquals(listOf("retry stopped: dismissed"), logs)
            assertEquals(0, checks)
            assertEquals(0, sends)
            assertNull(dispatch.tryAcquire(key))
        } finally {
            release()
        }
        dispatch.tryAcquire(key)!!.invoke()
    }

    @Test fun waitForBusyPaneEndsEvenIfTheOwnerDoesNotReleaseIt() = runBlocking {
        val dispatch = PaneDispatch()
        val key = "server" to "pane"
        val release = dispatch.tryAcquire(key)!!
        var budget = 0L
        var checks = 0
        var sends = 0
        try {
            // The outer timeout is a watchdog, not the deadline being tested.
            val result = withTimeout(2_000) {
                HeldSendRetry(windowMs = 50L, everyMs = 10L, now = { budget }, sleep = { budget += it }).run(
                    0L, "busy", cancelled = { null }, check = { checks++; null }, send = { sends++ },
                    acquire = { dispatch.acquire(key) },
                )
            }
            assertEquals(HeldOutcome.TimedOut("busy", 0), result)
            assertEquals(0, checks)
            assertEquals(0, sends)
            assertNull(dispatch.tryAcquire(key))
        } finally {
            release()
        }
        dispatch.tryAcquire(key)!!.invoke()
    }

    @Test fun wakingAtTheDeadlineDoesNotAcquireTheLane() = runBlocking {
        var now = 0L
        var acquisitions = 0
        val result = HeldSendRetry(windowMs = 20L, everyMs = 30L, now = { now }, sleep = { now += it }).run(
            0L, "busy", cancelled = { null }, check = { null }, send = {}, acquire = { acquisitions++; {} },
        )
        assertEquals(HeldOutcome.TimedOut("busy", 0), result)
        assertEquals(0, acquisitions)
        assertEquals(20L, now)
    }

    @Test fun aPaneLockGrantedAfterTheDeadlineIsReleasedWithoutCheckingOrSending() = runBlocking {
        val dispatch = PaneDispatch()
        val key = "server" to "pane"
        val release = dispatch.tryAcquire(key)!!
        var now = 118_000L
        var checks = 0
        var sends = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            HeldSendRetry(everyMs = 1_000L, now = { now }, sleep = { now += it }).run(
                0L, "busy", cancelled = { null }, check = { checks++; null }, send = { sends++ },
                acquire = { dispatch.acquire(key) },
            )
        }
        assertFalse(pending.isCompleted)
        now = 125_000L
        release()
        assertEquals(HeldOutcome.TimedOut("busy", 0), pending.await())
        assertEquals(0, checks)
        assertEquals(0, sends)
        dispatch.tryAcquire(key)!!.invoke()
    }

    @Test fun aClearScreenThatArrivesAtTheDeadlineDoesNotSend() = runBlocking {
        val dispatch = PaneDispatch()
        val key = "server" to "pane"
        var now = 117_000L
        var sends = 0
        val result = HeldSendRetry(now = { now }, sleep = { now += it }).run(
            0L, "draft", cancelled = { null }, check = { now = 120_000L; null }, send = { sends++ },
            acquire = { dispatch.acquire(key) },
        )
        assertEquals(HeldOutcome.TimedOut("draft", 1), result)
        assertEquals(0, sends)
        dispatch.tryAcquire(key)!!.invoke()
    }
}
