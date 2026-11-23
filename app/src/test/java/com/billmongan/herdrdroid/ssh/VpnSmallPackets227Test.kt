package com.billmongan.herdrdroid.ssh

import com.billmongan.herdrdroid.diag.LinkStats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/** 2.2.7: small packets chosen automatically when the phone's network is a VPN at connect time. */
class VpnSmallPackets227Test {
    private class FakeOps : MssOps {
        val sets = Collections.synchronizedList(mutableListOf<Int>())
        override fun set(socket: Socket, mss: Int): String { sets += mss; return "fake" }
        override fun get(socket: Socket): Int? = 1188
    }

    private fun ssh(net: () -> String, auto: Boolean = true, serverMss: Int? = null, ws: String? = null, ops: MssOps = FakeOps(), port: Int = 22) =
        HerdrSsh("127.0.0.1", port, "u", Credentials(null, null), null, { null }, ws, serverMss, "fixture", ops, { auto }, net)

    @Test fun aVpnAtConnectGivesMss1200ForTheReasonVpnAuto() {
        val s = ssh({ "vpn" })
        assertEquals(MssChoice(1200, MssChoice.VPN_AUTO, "vpn"), s.mssChoice())
        assertEquals(1200, s.activeMss)
        assertEquals(1200, s.socketFactoryForNext()!!.mss)
    }

    @Test fun onAVpnTheServersOwnSettingWins() {
        val s = ssh({ "vpn" }, serverMss = 900)
        assertEquals(MssChoice(900, MssChoice.SETTING, "vpn"), s.mssChoice())
        assertEquals(900, s.socketFactoryForNext()!!.mss)
    }

    @Test fun theGlobalToggleOffGivesThe226Behaviour() {
        val off = ssh({ "vpn" }, auto = false)
        assertEquals(MssChoice(null, MssChoice.NONE, "vpn"), off.mssChoice())
        assertNull(off.socketFactoryForNext()!!.mss)
        // As in 2.2.6, the per-server setting and the fallback still apply.
        assertEquals(1000, ssh({ "vpn" }, auto = false, serverMss = 1000).activeMss)
        off.useSmallPackets(1200)
        assertEquals(MssChoice(1200, MssChoice.FALLBACK, "vpn"), off.mssChoice())
    }

    @Test fun offAVpnNothingChanges() {
        for (net in listOf("wifi", "cellular", "ethernet", "other", "none", "unknown")) {
            val s = ssh({ net })
            assertEquals(net, MssChoice(null, MssChoice.NONE, net), s.mssChoice())
            assertNull(s.socketFactoryForNext()!!.mss)
            assertEquals(MssChoice(1100, MssChoice.SETTING, net), ssh({ net }, serverMss = 1100).mssChoice())
        }
        val fb = ssh({ "wifi" })
        fb.useSmallPackets(1200)
        assertEquals(MssChoice(1200, MssChoice.FALLBACK, "wifi"), fb.mssChoice())
    }

    private fun cell(net: String, cellular: Boolean, vpnOn: Boolean, cellOn: Boolean, serverMss: Int? = null, ws: String? = null) =
        HerdrSsh("127.0.0.1", 22, "u", Credentials(null, null), null, { null }, ws, serverMss, "fixture", FakeOps(),
            { vpnOn }, { net }, cellularAuto = { cellOn }, overCellular = { cellular })

    @Test fun cellularAtConnectFollowsTheCellularSetting() {
        assertEquals(MssChoice(1200, MssChoice.CELLULAR_AUTO, "cellular"), cell("cellular", true, vpnOn = false, cellOn = true).mssChoice())
        assertEquals(1200, cell("cellular", true, vpnOn = false, cellOn = true).socketFactoryForNext()!!.mss)
        assertEquals(MssChoice(null, MssChoice.NONE, "cellular"), cell("cellular", true, vpnOn = false, cellOn = false).mssChoice())
        // The VPN setting alone does nothing on plain cellular.
        assertEquals(MssChoice.NONE, cell("cellular", true, vpnOn = true, cellOn = false).mssChoice().reason)
        // The server's own setting still wins, with its value.
        assertEquals(MssChoice(900, MssChoice.SETTING, "cellular"), cell("cellular", true, vpnOn = false, cellOn = true, serverMss = 900).mssChoice())
        // Wi-Fi is never cellular.
        assertEquals(MssChoice.NONE, cell("wifi", false, vpnOn = true, cellOn = true).mssChoice().reason)
    }

    @Test fun aVpnOverCellularAsksTheVpnSettingFirstAndEitherOneMeansSmallPackets() {
        assertEquals(MssChoice.VPN_AUTO, cell("vpn", true, vpnOn = true, cellOn = true).mssChoice().reason)
        assertEquals(MssChoice.VPN_AUTO, cell("vpn", true, vpnOn = true, cellOn = false).mssChoice().reason)
        assertEquals(MssChoice(1200, MssChoice.CELLULAR_AUTO, "vpn"), cell("vpn", true, vpnOn = false, cellOn = true).mssChoice())
        assertEquals(MssChoice.NONE, cell("vpn", true, vpnOn = false, cellOn = false).mssChoice().reason)
        // A VPN over Wi-Fi is not cellular.
        assertEquals(MssChoice.NONE, cell("vpn", false, vpnOn = false, cellOn = true).mssChoice().reason)
    }

    @Test fun cellularLeavesAWebSocketAlone() {
        val ws = cell("cellular", true, vpnOn = true, cellOn = true, ws = "wss://bridge.invalid/ssh")
        assertNull(ws.activeMss)
        assertNull(ws.socketFactoryForNext())
    }

    @Test fun aSessionFallbackCarriesIntoANewConnectionUntilCleared() {
        val s = HerdrSsh("127.0.0.1", 22, "u", Credentials(null, null), null, { null }, null, null, "fixture", FakeOps(),
            { false }, { "vpn" }, sessionFallbackMss = 1200)
        assertEquals(MssChoice(1200, MssChoice.FALLBACK, "vpn"), s.mssChoice())
        s.clearSmallPackets()
        assertEquals(MssChoice.NONE, s.mssChoice().reason)
    }

    @Test fun aReadWithNoFirstByteIsReportedOnceAfterTheThreshold() {
        var stalls = 0
        val w = FirstByteWatch(SmallPacketFallback.FIRST_BYTE_STALL_MS, 1_000L) { stalls++ }
        w.tick(1_000L + SmallPacketFallback.FIRST_BYTE_STALL_MS - 1, 0)
        assertEquals(0, stalls)
        w.tick(1_000L + SmallPacketFallback.FIRST_BYTE_STALL_MS, 0)
        w.tick(1_000L + 26_522, 0)
        assertEquals("once per read", 1, stalls)
        val fast = FirstByteWatch(SmallPacketFallback.FIRST_BYTE_STALL_MS, 0L) { stalls++ }
        fast.tick(30_000L, 17)
        assertEquals("a read that got its first byte is never a stall", 1, stalls)
        assertEquals("no-first-byte", SmallPacketFallback.reasonFor(FirstByteStall()))
        assertTrue(SmallPacketFallback.FIRST_BYTE_STALL_MS in 5_000L..10_000L)
    }

    @Test fun aWebSocketIsUnaffected() {
        val ws = ssh({ "vpn" }, ws = "wss://bridge.invalid/ssh")
        assertNull(ws.activeMss)
        assertEquals(MssChoice.NONE, ws.mssChoice().reason)
        assertNull(ws.socketFactoryForNext())
    }

    @Test fun aBrokenProviderNeverBreaksTheConnect() {
        val s = HerdrSsh("127.0.0.1", 22, "u", Credentials(null, null), null, { null }, null, null, "fixture", FakeOps(),
            { error("prefs") }, { error("no manager") })
        assertEquals(MssChoice(null, MssChoice.NONE, "unknown"), s.mssChoice())
    }

    /** A network change mid-session touches nothing; the next connect (a reconnect) picks the new MSS. */
    @Test fun aNetworkChangeTakesEffectOnTheNextReconnect() {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val acceptor = thread(isDaemon = true) { runCatching { while (true) server.accept().close() } }
        try {
            var net = "cellular"
            val ops = FakeOps()
            val s = ssh({ net }, ops = ops, port = server.localPort)
            runCatching { runBlocking { s.exec("read") } }
            assertTrue("no MSS set off a VPN", ops.sets.isEmpty())
            assertEquals(MssChoice.NONE, s.lastMssChoice!!.reason)
            assertEquals("cellular", s.lastMssChoice!!.net)

            net = "vpn"
            assertTrue("changing the network alone opens nothing", ops.sets.isEmpty())
            assertEquals(MssChoice.NONE, s.lastMssChoice!!.reason)

            runCatching { runBlocking { s.exec("read") } }
            assertTrue(ops.sets.isNotEmpty())
            assertTrue(ops.sets.all { it == 1200 })
            assertEquals(MssChoice(1200, MssChoice.VPN_AUTO, "vpn"), s.lastMssChoice)

            net = "wifi"
            val before = ops.sets.size
            runCatching { runBlocking { s.exec("read") } }
            assertEquals("the VPN dropped: back to the normal MSS", before, ops.sets.size)
            assertEquals(MssChoice.NONE, s.lastMssChoice!!.reason)
        } finally {
            server.close()
            acceptor.join(2_000)
        }
    }

    @Test fun theConnectLineRecordsTheReasonNetworkAndSetsockopt() {
        val t = LinkStats.ConnectTimes(5, 6, 7, 8, 9)
        val auto = LinkStats.connectLine("fixture", "tcp", t, null, "vpn", 2, 15, MssChoice.VPN_AUTO, 1200, true)
        assertTrue(auto, auto.endsWith(" net=vpn net_changes=2 mss=1200 mss_reason=vpn-auto mss_set=yes"))
        val failed = LinkStats.connectLine("fixture", "tcp", t, null, "vpn", 2, 15, MssChoice.SETTING, 900, false)
        assertTrue(failed, failed.endsWith(" mss=900 mss_reason=setting mss_set=no"))
        val none = LinkStats.connectLine("fixture", "tcp", t, null, "wifi", 0, 15, MssChoice.NONE, null, null)
        assertTrue(none, none.endsWith(" net=wifi net_changes=0 mss=none mss_reason=none mss_set=n/a"))
        val fb = LinkStats.connectLine("fixture", "tcp", t, null, "wifi", 0, 15, MssChoice.FALLBACK, 1200, true)
        assertTrue(fb, fb.contains(" mss_reason=fallback "))
        // Callers that pass no reason get the 2.2.6 line unchanged.
        assertTrue(LinkStats.connectLine("fixture", "ws", t, null, "wifi", 0).endsWith(" net=wifi net_changes=0"))
        for (line in listOf(auto, failed, none, fb)) assertFalse(line, line.contains("127.0.0.1"))
    }
}
