package com.billmongan.herdrdroid.ssh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoopbackForward228Test {
    @Test fun startupFailureClosesTheBoundSocketWithoutReturningAPort() = runBlocking {
        val failure = IOException("fixture listener failed to start")
        lateinit var bound: ServerSocket
        val result = runCatching {
            startLoopbackForward { socket ->
                bound = socket
                assertEquals("127.0.0.1", socket.inetAddress.hostAddress)
                throw failure
            }
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertTrue(bound.isClosed)
    }

    @Test fun closingTheForwardStopsTheListenerAndReleasesTheLoopbackPort() = runBlocking {
        val stopped = CountDownLatch(1)
        val forward = startLoopbackForward { socket ->
            try {
                while (!Thread.currentThread().isInterrupted) {
                    socket.accept().use { it.getOutputStream().write(37) }
                }
            } finally {
                stopped.countDown()
            }
        }
        try {
            Socket().use {
                it.connect(InetSocketAddress("127.0.0.1", forward.localPort), 1_000)
                it.soTimeout = 1_000
                assertEquals(37, it.getInputStream().read())
            }
        } finally {
            forward.close()
        }
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        Socket().use {
            assertTrue(runCatching { it.connect(InetSocketAddress("127.0.0.1", forward.localPort), 500) }.isFailure)
        }
    }
}
