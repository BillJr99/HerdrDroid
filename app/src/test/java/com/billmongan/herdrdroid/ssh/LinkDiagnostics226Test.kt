package com.billmongan.herdrdroid.ssh

import com.billmongan.herdrdroid.diag.DiagLog
import com.billmongan.herdrdroid.diag.LinkStats
import com.billmongan.herdrdroid.diag.NetworkWatch
import com.billmongan.herdrdroid.diag.RateLimit
import com.billmongan.herdrdroid.diag.ReadLabel
import com.billmongan.herdrdroid.diag.ReadMeter
import com.billmongan.herdrdroid.diag.ReadStats
import com.billmongan.herdrdroid.diag.errorChain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** 2.2.6: the extra `[link]` diagnostics: per-read bytes and timings, and nothing secret in any line. */
class LinkDiagnostics226Test {
    private var now = 1_000L
    private val clock = { now }

    /** A fake channel: each read advances the clock by its delay and returns its chunk; then EOF. */
    private inner class FakeChannel(private val chunks: List<Pair<Long, Int>>) : InputStream() {
        private var i = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (i >= chunks.size) return -1
            val (delay, n) = chunks[i++]
            now += delay
            return n
        }
    }

    @Test fun aReadCountsItsBytesFirstByteAndTotalTime() {
        val meter = ReadMeter(clock)
        now += 7
        meter.channelOpen()
        Capped(1 shl 20, meter).drain(FakeChannel(listOf(40L to 100, 10L to 250, 5L to 50)))
        now += 3
        val r = meter.finish("list-read", 0, timedOut = false)
        assertEquals(ReadStats("list-read", 400, 47, 65, 7, 0, "no"), r)
        assertEquals(
            "server=${com.billmongan.herdrdroid.diag.LinkDiag.serverTag("x")} read=list-read bytes=400 ttfb_ms=47 total_ms=65 chan_ms=7 exit=0 timeout=no",
            LinkStats.readLine("x", r),
        )
    }

    @Test fun stdoutAndStderrBothCount() {
        val meter = ReadMeter(clock)
        Capped(1 shl 20, meter).drain(FakeChannel(listOf(5L to 10)))
        Capped(1 shl 20, meter).drain(FakeChannel(listOf(5L to 4)))
        assertEquals(14, meter.finish("probe", 0, false).bytes)
    }

    @Test fun aTimeoutSaysWhetherAnyByteCameAndHowManyBeforeTheStall() {
        val none = ReadMeter(clock).also { now += 30_000 }.finish("log-read", null, timedOut = true)
        assertEquals("no-first-byte", none.timeout)
        assertNull(none.firstByteMs)
        assertTrue(LinkStats.readLine("x", none).contains("ttfb_ms=none total_ms=30000 exit=none timeout=no-first-byte"))

        val meter = ReadMeter(clock)
        Capped(1 shl 20, meter).drain(FakeChannel(listOf(100L to 1400, 0L to 600)))
        now += 33_000
        val partial = meter.finish("log-read", null, timedOut = true)
        assertEquals("partial", partial.timeout)
        assertTrue(LinkStats.readLine("x", partial).endsWith("timeout=partial stalled_after_bytes=2000"))
    }

    @Test fun repeatedLinesAreHeldBackAndCounted() {
        val limit = RateLimit(30_000, clock)
        assertEquals(0, limit.admit("k"))
        now += 1_000; assertNull(limit.admit("k"))
        now += 1_000; assertNull(limit.admit("k"))
        assertEquals(0, limit.admit("other"))
        now += 30_000; assertEquals(2, limit.admit("k"))
    }

    @Test fun anErrorChainIsClassesOnly() {
        val e = LinkDown(IOException("connect to 192.0.2.19:22 on laptop2.secret.lan", java.net.SocketTimeoutException("wss://bridge.secret/ssh")))
        assertEquals("LinkDown<IOException<SocketTimeoutException", errorChain(e))
    }

    private val secrets = listOf("laptop2.secret.lan", "192.0.2.19", "127.0.0.1", "wss://bridge.secret", "BillsHomeWiFi", "aa:bb:cc:dd:ee:ff", "SECRET_CMD", "hunter2", "server-id-raw")

    private fun assertClean(lines: List<String>) {
        assertTrue(lines.isNotEmpty())
        for (l in lines) for (s in secrets) assertFalse("'$s' leaked into: $l", l.contains(s, ignoreCase = true))
    }

    @Test fun noNewLineCarriesAHostAddressUrlNetworkNameOrCommand() {
        val boom = LinkDown(IOException("192.0.2.19 laptop2.secret.lan wss://bridge.secret/ssh SECRET_CMD hunter2"))
        val session = LinkStats.Session("server-id-raw", 0, 0).apply { reconnectAttempts = 3; reconnectSuccesses = 1; fallbackUsed = true }
        val lines = listOf(
            LinkStats.connectLine("server-id-raw", "tcp", LinkStats.ConnectTimes(12, 30, 900, 4, 80),
                "kex=curve25519-sha256 hostkey=ssh-ed25519 cipher=aes128-ctr mac=hmac-sha2-256 comp=none", "wifi", 2, 15),
            LinkStats.socketLine("server-id-raw", LinkStats.SocketFacts(1200, 1188, 87040, 131072, true, false)),
            LinkStats.readLine("server-id-raw", ReadStats("list-read", 5000, 20, 33_000, 3, null, "partial")),
            LinkStats.lostLine("server-id-raw", boom, 31_000, 15, 123456),
            LinkStats.reconnectLine("server-id-raw", 2, 2_000, "link-lost", "failed", 8_000, boom),
            LinkStats.summaryLine(session, 4, 99999, 3, 60_000, "disconnect"),
            com.billmongan.herdrdroid.diag.LinkDiag.mssLine("server-id-raw", MssReport(1200, false, null, "ErrnoException", null)),
            com.billmongan.herdrdroid.diag.LinkDiag.fallbackLine("server-id-raw", "read-timeout", 1200, 5_000),
        )
        assertClean(lines)
        assertTrue(lines[0].contains("tcp_ms=12 ident_kexinit_ms=30 kex_reply_ms=900 newkeys_ms=4 auth_ms=80"))
        assertTrue(lines[0].contains("comp=none keepalive_s=15 net=wifi net_changes=2"))
        assertTrue(lines[1].endsWith("socket mss_requested=1200 mss_effective=1188 sndbuf=87040 rcvbuf=131072 nodelay=true so_keepalive=false"))
        assertTrue(lines[3].contains("lost error=LinkDown<IOException since_last_byte_ms=31000 keepalive_s=15 silent_keepalives=2 bytes_in=123456"))
        assertTrue(lines[4].contains("reconnect attempt=2 backoff_ms=2000 reason=link-lost result=failed ms=8000"))
        assertTrue(lines[5].contains("session-summary end=disconnect duration_s=60 reconnect_attempts=3 reconnect_successes=1 stalls=4 bytes_in=99999 fallback=yes net_changes=3"))
    }

    @Test fun theNetworkLineIsTheTypeAndACounterOnly() {
        DiagLog.clear()
        val net = object { override fun toString() = "BillsHomeWiFi aa:bb:cc:dd:ee:ff 192.0.2.19" }
        NetworkWatch.observe(net, "wifi")
        NetworkWatch.observe(net, "wifi")
        NetworkWatch.observe(Any(), "cellular")
        val lines = DiagLog.text().lines().filter { it.contains("[link] network=") }
        assertClean(lines)
        assertTrue(lines.last().contains("network=cellular changes="))
        assertEquals("vpn", NetworkWatch.typeOf(vpn = true, wifi = true, cellular = false, ethernet = false))
    }

    @Test fun aRealConnectLogsSocketFactsWithoutTheAddressOrCommand() {
        DiagLog.clear()
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val acceptor = thread(isDaemon = true) { runCatching { while (true) server.accept().close() } }
        val ssh = HerdrSsh("127.0.0.1", server.localPort, "nobody", Credentials(null, "hunter2"), null, { null }, null, null, "server-id-raw",
            object : MssOps {
                override fun set(socket: java.net.Socket, mss: Int) = "fake"
                override fun get(socket: java.net.Socket): Int? = 1448
            })
        try {
            runCatching { runBlocking { withContext(ReadLabel("list-read")) { ssh.exec("herdr pane list SECRET_CMD") } } }
        } finally {
            ssh.close(); server.close(); acceptor.join(2_000)
        }
        val lines = DiagLog.text().lines().filter { it.contains(" [link] ") }
        assertClean(lines)
        assertTrue(lines.any { it.contains("socket mss_requested=none mss_effective=1448") })
    }
}
