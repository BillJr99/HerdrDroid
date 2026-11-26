package com.billmongan.herdrdroid.ssh

import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFollowCancellation228Test {
    @Test
    fun silentChannelReadIsInterruptedAndFinallyClosesIt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int = error("Expected a buffered read")

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                started.complete(Unit)
                try {
                    released.await()
                    return -1
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException().apply { initCause(e) }
                }
            }

            override fun close() {
                stopped.complete(Unit)
            }
        }

        val job = launch(Dispatchers.IO) {
            try {
                readFollowLines(input) { error("A silent channel emitted text") }
            } finally {
                input.close()
            }
        }
        try {
            withTimeout(5_000) { started.await() }
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(stopped.isCompleted)
        } finally {
            released.countDown()
            job.cancel()
        }
    }

    @Test
    fun readsCompleteLinesAndSkipsBlankRows() = runBlocking {
        val seen = mutableListOf<String>()
        readFollowLines("first\n\nsecond\r\nlast".byteInputStream()) { seen.add(it) }
        assertEquals(listOf("first", "second", "last"), seen)
    }
}
