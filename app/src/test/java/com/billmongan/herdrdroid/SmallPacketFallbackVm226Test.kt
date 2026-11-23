package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ssh.LinkDown
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** 2.2.6: the view model's small-packets fallback fires once per sign-in, and one tap keeps it. */
@RunWith(AndroidJUnit4::class)
class SmallPacketFallbackVm226Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val server = Server(id = "fixture-mss", name = "Fixture", host = "fixture.invalid", port = 22, user = "u", herdrPath = "herdr")
    private var now = 10_000L

    @After fun close() {
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun signedIn(ssh: HerdrSsh, s: Server = server) {
        vm.saveServer(s)
        state("current", s)
        field("ssh").set(vm, ssh)
        vm.smallPacketClock = { now }
        vm.smallPacketFallback.arm(s.id, !ssh.overWebSocket && ssh.activeMss == null, now)
    }

    @Test fun aDropSoonAfterSignInSwitchesOnceThenKeepSaves() {
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        signedIn(ssh)
        now += 5_000
        assertTrue(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("stall"))))
        assertEquals(1200, ssh.activeMss)
        assertEquals(server.id, vm.smallPacketOffer)
        now += 1_000
        assertFalse("never a second time", vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("stall"))))
        vm.keepSmallPacketsOn()
        assertNull(vm.smallPacketOffer)
        assertTrue(vm.current!!.smallPackets)
        assertEquals(1200, vm.servers.single { it.id == server.id }.smallPacketsMss)
    }

    @Test fun notAfterTheWindowNorForOtherErrors() {
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        signedIn(ssh)
        assertFalse(vm.maybeSmallPacketFallback(IllegalStateException("parse")))
        now += 10 * 60_000
        assertFalse(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("late"))))
        assertNull(ssh.activeMss)
        assertNull(vm.smallPacketOffer)
    }

    @Test fun neverOverAWebSocket() {
        val ws = server.copy(wsUrl = "wss://bridge.invalid/ssh")
        val ssh = HerdrSsh("unused", 0, ws.user, Credentials(null, null), null, { null }, ws.wsUrl)
        signedIn(ssh, ws)
        assertFalse(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("drop"))))
        assertNull(vm.smallPacketOffer)
    }

    private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value
    }
}
