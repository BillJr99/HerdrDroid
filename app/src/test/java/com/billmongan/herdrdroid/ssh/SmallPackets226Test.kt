package com.billmongan.herdrdroid.ssh

import com.billmongan.herdrdroid.diag.LinkDiag
import com.billmongan.herdrdroid.herdr.HerdrError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/** 2.2.6: small packets (a TCP MSS set before connect) and the once-only fallback. */
class SmallPackets226Test {
    /** Records each call and whether the socket was connected at the time. */
    private class FakeOps(val fail: Throwable? = null, val effective: Int? = 1188) : MssOps {
        val sets = Collections.synchronizedList(mutableListOf<Pair<Int, Boolean>>())
        val gets = Collections.synchronizedList(mutableListOf<Boolean>())
        override fun set(socket: Socket, mss: Int): String {
            sets += mss to socket.isConnected
            fail?.let { throw it }
            return "fake"
        }
        override fun get(socket: Socket): Int? {
            gets += socket.isConnected
            return effective
        }
    }

    private fun <T> withListener(block: (Int) -> T): T {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val acceptor = thread(isDaemon = true) {
            runCatching { while (true) server.accept().close() }
        }
        try {
            return block(server.localPort)
        } finally {
            server.close()
            acceptor.join(2_000)
        }
    }

    @Test fun theOptionIsSetBeforeConnectAndReadBackAfter() = withListener { port ->
        val ops = FakeOps()
        val reports = mutableListOf<MssReport>()
        val socket = MssSocketFactory(1200, ops, report = { reports += it }).createSocket()
        assertFalse(socket.isConnected)
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
        socket.use { assertTrue(it.isConnected) }
        assertEquals(listOf(1200 to false), ops.sets.toList())
        assertEquals(listOf(true), ops.gets.toList())
        assertEquals(MssReport(1200, true, "fake", null, 1188), reports.single())
    }

    @Test fun aFailedOptionStillConnectsWithTheNormalMss() = withListener { port ->
        for (failure in listOf<Throwable>(IOException("refused"), UnsatisfiedLinkError("no native"))) {
            val ops = FakeOps(fail = failure)
            val reports = mutableListOf<MssReport>()
            MssSocketFactory(1200, ops, report = { reports += it }).createSocket(InetAddress.getLoopbackAddress(), port).use {
                assertTrue(it.isConnected)
            }
            assertEquals(1, ops.sets.size)
            assertTrue("no read-back after a failed set", ops.gets.isEmpty())
            val r = reports.single()
            assertFalse(r.applied)
            assertEquals(failure.javaClass.simpleName, r.error)
            assertNull(r.effective)
        }
    }

    @Test fun aReportThatThrowsDoesNotFailTheConnection() = withListener { port ->
        MssSocketFactory(1200, FakeOps(), report = { error("diag broke") }, facts = { _, _ -> error("diag broke") }).createSocket("127.0.0.1", port).use { assertTrue(it.isConnected) }
    }

    @Test fun theMssIsBounded() {
        assertEquals(SmallPackets.MIN, MssSocketFactory(100, FakeOps()).mss)
        assertEquals(SmallPackets.MAX, MssSocketFactory(9000, FakeOps()).mss)
        assertEquals(1300, MssSocketFactory(1300, FakeOps()).mss)
        assertEquals(536, SmallPackets.MIN)
        assertEquals(1460, SmallPackets.MAX)
        assertEquals(1200, SmallPackets.DEFAULT)
    }

    @Test fun sshjUsesTheFactoryForADirectTcpServer() = withListener { port ->
        val ops = FakeOps()
        val ssh = HerdrSsh("127.0.0.1", port, "nobody", Credentials(null, null), null, { null }, null, 1200, "fixture", ops)
        // The listener closes at once, so the key exchange fails, but the socket was made by the factory.
        val failure = runCatching { runBlocking { ssh.exec("true") } }.exceptionOrNull()
        assertTrue("expected a lost link, got $failure", failure is LinkDown)
        assertTrue(ops.sets.isNotEmpty())
        assertTrue("every set happened before connect", ops.sets.all { (mss, connected) -> mss == 1200 && !connected })
    }

    /**
     * Pre-review M2: the fallback's reset can drop a send's connection. A permitted send (the send
     * lane) is then reported, never sent again, and the send counter the fallback waits on is
     * released; an unpermitted read gets its one retry.
     */
    @Test fun aPermittedSendIsNeverReplayedAfterTheLinkGoes() = withListener { port ->
        val sendOps = FakeOps()
        val send = HerdrSsh("127.0.0.1", port, "nobody", Credentials(null, null), null, { null }, null, 1200, "fixture", sendOps)
        val trace = SendTrace()
        val sent = runCatching { runBlocking(trace) { send.execNow("send-keys", 5, null, com.billmongan.herdrdroid.herdr.WritePermit()) } }.exceptionOrNull()
        assertTrue("expected a lost link, got $sent", sent is LinkDown)
        assertFalse("the connection failed before any exec, so the action may be queued", trace.started)
        assertEquals("one connection, no replay", 1, sendOps.sets.size)
        assertEquals(0, send.sendsRunning)

        val readOps = FakeOps()
        val read = HerdrSsh("127.0.0.1", port, "nobody", Credentials(null, null), null, { null }, null, 1200, "fixture", readOps)
        runCatching { runBlocking { read.exec("read") } }
        assertEquals("a read is retried once", 2, readOps.sets.size)
    }

    @Test fun offOrOverAWebSocketTheDefaultFactoryIsUsed() {
        val off = HerdrSsh("127.0.0.1", 22, "u", Credentials(null, null), null, { null })
        assertNull(off.activeMss)
        // 2.2.6 diagnostics: direct TCP always gets the timing factory; it sets no MSS when off.
        assertNull(off.socketFactoryForNext()!!.mss)

        val ws = HerdrSsh("unused", 0, "u", Credentials(null, null), null, { null }, "wss://bridge.invalid/ssh", 1200, "fixture", FakeOps())
        assertNull(ws.activeMss)
        assertNull(ws.socketFactoryForNext())
        ws.useSmallPackets(1200)
        assertNull("the fallback never applies over a WebSocket", ws.socketFactoryForNext())

        val tcp = HerdrSsh("127.0.0.1", 22, "u", Credentials(null, null), null, { null }, null, 99999, "fixture", FakeOps())
        assertEquals(SmallPackets.MAX, tcp.activeMss)
        assertEquals(SmallPackets.MAX, tcp.socketFactoryForNext()!!.mss)
    }

    @Test fun theSessionFallbackSwitchesTheNextConnection() {
        val ssh = HerdrSsh("127.0.0.1", 22, "u", Credentials(null, null), null, { null }, null, null, "fixture", FakeOps())
        assertNull(ssh.socketFactoryForNext()!!.mss)
        ssh.useSmallPackets(1200)
        assertEquals(1200, ssh.activeMss)
        assertEquals(1200, ssh.socketFactoryForNext()!!.mss)
    }

    @Test fun theFallbackFiresOnceAndOnlyOnce() {
        val f = SmallPacketFallback(windowMs = 60_000)
        f.arm("a", eligible = true, now = 1_000)
        assertEquals(1200, f.fire("a", "link-drop", 5_000))
        assertNull(f.fire("a", "link-drop", 6_000))
        assertNull(f.fire("a", "read-timeout", 7_000))
    }

    @Test fun theFallbackNeedsATriggerTheServerTheWindowAndEligibility() {
        val f = SmallPacketFallback(windowMs = 60_000)
        assertNull("not armed", f.fire("a", "link-drop", 0))
        f.arm("a", eligible = true, now = 0)
        assertNull(f.fire("a", null, 1_000))
        assertNull(f.fire("b", "link-drop", 1_000))
        assertNull(f.fire("a", "link-drop", 60_001))
        f.arm("a", eligible = false, now = 0)
        assertNull("small packets already on, or a WebSocket", f.fire("a", "link-drop", 1_000))
        f.arm("a", eligible = true, now = 0)
        f.disarm()
        assertNull(f.fire("a", "link-drop", 1_000))
        // A new sign-in arms it again: one fallback per session.
        f.arm("a", eligible = true, now = 100_000)
        assertEquals(1200, f.fire("a", "read-timeout", 101_000))
    }

    @Test fun triggersAreDropsAndTimeoutsOnly() {
        assertEquals("link-drop", SmallPacketFallback.reasonFor(LinkDown(IOException("x"))))
        assertEquals("read-timeout", SmallPacketFallback.reasonFor(CommandTimeout(30)))
        assertEquals("read-timeout", SmallPacketFallback.reasonFor(HerdrError(CommandTimeout(30).message!!, -2)))
        assertNull(SmallPacketFallback.reasonFor(HerdrError("the command printed more than 8 MB", -2)))
        assertNull(SmallPacketFallback.reasonFor(HerdrError("no such pane", 1)))
        assertNull(SmallPacketFallback.reasonFor(AuthRejected("no")))
        assertNull(SmallPacketFallback.reasonFor(null))
    }

    @Test fun diagnosticLinesHoldNoAddress() {
        val ok = LinkDiag.mssLine("server-id-1", MssReport(1200, true, "pfd-dup", null, 1188))
        assertEquals("server=${LinkDiag.serverTag("server-id-1")} mss requested=1200 set=ok route=pfd-dup effective=1188", ok)
        val failed = LinkDiag.mssLine("server-id-1", MssReport(1200, false, null, "ErrnoException", null))
        assertTrue(failed.endsWith("set=failed error=ErrnoException effective=unknown"))
        val fb = LinkDiag.fallbackLine("server-id-1", "read-timeout", 1200, 4_000)
        assertEquals("server=${LinkDiag.serverTag("server-id-1")} fallback=small-packets reason=read-timeout mss=1200 after_ms=4000", fb)
        listOf(ok, failed, fb).forEach { assertFalse(it.contains("server-id-1")) }
    }
}
