package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.diag.DiagLog
import com.billmongan.herdrdroid.diag.NetworkWatch
import com.billmongan.herdrdroid.fleet.Fleet
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ssh.LinkDown
import com.billmongan.herdrdroid.ssh.MssChoice
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.ui.smallPacketNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.2.7, from Bill's log over cellular plus a VPN: the reconnect signed in, then the pane list read
 * got no byte for 26.5 s before the link was declared lost; with small packets every read came in
 * 1 to 2 s, while the server list still said "Can't reach it" from a check made with normal packets.
 */
@RunWith(AndroidJUnit4::class)
class SmallPacketSession227Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val server = Server(id = "fixture-session", name = "MiniFixture", host = "s.invalid", port = 22, user = "u", herdrPath = "herdr", fingerprint = "SHA256:fixture")
    private val pane = Pane("w2:p5", "w2:t5", "w2", "claude", Status.IDLE, "", "/fixture", terminalId = "t5")
    private var now = 50_000L
    // A scope that runs nothing: only the watcher's connection setup is under test, never its loop
    // (which on another thread raced the test's stop).
    private val fleetScope = CoroutineScope(SupervisorJob().apply { cancel() })

    @After fun close() {
        vm.viewModelScope.cancel()
        fleetScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class Quiet : Herdr.Commands {
        override val connection: Any? = Any()
        override suspend fun bulk(command: String) = ExecResult(0, "", "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?) = ExecResult(0, "", "")
    }

    private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }

    /** Signed in over the VPN with normal packets (the automatic settings are off), as at 17:49:17. */
    private fun signedIn(): HerdrSsh {
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null }, null, null, server.id,
            networkType = { "vpn" }, overCellular = { true })
        vm.saveServer(server)
        state("current", server)
        state("panes", listOf(pane))
        state("link", Link.CONNECTED)
        field("ssh").set(vm, ssh)
        field("herdr").set(vm, Herdr(ssh, server.herdrPath, Backend.TUI, null, Quiet()))
        vm.smallPacketClock = { now }
        vm.smallPacketFallback.arm(server.id, ssh.activeMss == null && ssh.lastMssChoice?.mss == null, now, NetworkWatch.changes)
        return ssh
    }

    @Test fun aListReadWithNoFirstByteFallsBackOnceAndTheSessionKeepsSmallPackets() {
        DiagLog.clear()
        val ssh = signedIn()
        assertEquals(MssChoice(null, MssChoice.NONE, "vpn"), ssh.mssChoice())
        // The list read waits past the threshold with no byte: the stall falls back at once.
        now += 5_000 + com.billmongan.herdrdroid.ssh.SmallPacketFallback.FIRST_BYTE_STALL_MS
        vm.onFirstByteStall(ssh)
        assertEquals(MssChoice(1200, MssChoice.FALLBACK, "vpn"), ssh.mssChoice())
        assertEquals("the reconnect opens with MSS 1200", 1200, ssh.socketFactoryForNext()!!.mss)
        assertTrue(DiagLog.text().contains("fallback=small-packets reason=no-first-byte mss=1200"))
        assertEquals(1200, vm.sessionMssFor(server.id))
        // Told, not asked.
        assertEquals(server.id, vm.smallPacketOffer)
        assertEquals("Switched to small packets for MiniFixture for this session: large replies were stalling.", smallPacketNotice("MiniFixture"))
        // Once only: a second stall, or the link drop that follows, does nothing more.
        val lines = DiagLog.text().lines().count { it.contains("fallback=small-packets") }
        vm.onFirstByteStall(ssh)
        assertFalse(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("dropped")), ssh))
        assertEquals(lines, DiagLog.text().lines().count { it.contains("fallback=small-packets") })
        // Another connection's stall never touches this session.
        val stranger = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        vm.onFirstByteStall(stranger)
        assertNull(stranger.activeMss)
    }

    @Test fun theWatcherAndTheStatusCheckShareTheSessionsSmallPackets() {
        val ssh = signedIn()
        now += 9_000
        vm.onFirstByteStall(ssh)
        // A new connection for this server (a reconnect, the status check) starts with them.
        val check = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null }, null, server.smallPacketsMss, server.id,
            sessionFallbackMss = vm.sessionMssFor(server.id))
        assertEquals(MssChoice.FALLBACK, check.mssChoice().reason)
        assertEquals(1200, check.activeMss)
        // The watcher too, and it follows the session's choice as it changes (pre-review L4).
        val fleet = Fleet(fleetScope, { Credentials(null, null) }, { null }, {}, sessionMss = vm::sessionMssFor)
        fleet.watch(listOf(server))
        assertEquals(1200, fleet.clientMss(server.id))
        vm.onNetworkChanged("wifi")
        assertNull("the session's small packets ended", fleet.clientMss(server.id))
        fleet.stop()
    }

    @Test fun aConnectedSessionIsListedReachableWhateverTheLastCheckSaid() {
        signedIn()
        state("serverHealth", mapOf(server.id to AppViewModel.ServerHealth(AppViewModel.Health.DOWN, "Can't reach it", 1L)))
        assertEquals(AppViewModel.Health.UP, vm.healthFor(server.id)?.health)
        assertEquals("Up · agents idle", vm.healthFor(server.id)?.line)
        // While it reconnects the last check shows again; back connected, reachable at once.
        state("link", Link.RECONNECTING)
        assertEquals(AppViewModel.Health.DOWN, vm.healthFor(server.id)?.health)
        state("link", Link.CONNECTED)
        assertEquals(AppViewModel.Health.UP, vm.healthFor(server.id)?.health)
        // Another server keeps its own light.
        assertNull(vm.healthFor("another"))
    }

    @Test fun aNetworkChangeEndsTheSessionsSmallPacketsAndArmsTheFallbackAgain() {
        DiagLog.clear()
        val ssh = signedIn()
        now += 9_000
        vm.onFirstByteStall(ssh)
        assertEquals(1200, ssh.activeMss)
        // Back on Wi-Fi: the next connect starts with normal packets again.
        vm.onNetworkChanged("wifi")
        assertNull(vm.sessionMssFor(server.id))
        assertEquals(MssChoice.NONE, ssh.mssChoice().reason)
        assertTrue(DiagLog.text().contains("small-packets=reset reason=network-change net=wifi"))
        // A drop or failed connect right after the change is not a stall (pre-review M5)...
        now += 1_000
        assertFalse(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("the switch itself")), ssh))
        assertNull(ssh.activeMss)
        // ...but once signed in on the new network, a drop there falls back again.
        vm.rearmAfterSignIn()
        now += 1_000
        assertTrue(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("drop on the new network")), ssh))
        assertEquals(1200, ssh.activeMss)
        // The server list is checked again a moment later (logged with its reason).
        state("foreground", true)
        vm.onNetworkChanged("cellular")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        assertTrue(DiagLog.text().contains("[check] recheck reason=network-change net=cellular"))
    }
}
