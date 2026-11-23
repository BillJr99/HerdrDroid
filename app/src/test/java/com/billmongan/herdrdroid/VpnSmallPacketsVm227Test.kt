package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.diag.DiagLog
import com.billmongan.herdrdroid.diag.NetworkWatch
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ssh.LinkDown
import com.billmongan.herdrdroid.ssh.MssChoice
import com.billmongan.herdrdroid.ssh.VpnSmallPackets
import com.billmongan.herdrdroid.store.AppPrefs
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** 2.2.7: the global "Small packets on VPN (automatic)" setting and how it meets the 2.2.6 fallback. */
@RunWith(AndroidJUnit4::class)
class VpnSmallPacketsVm227Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val vms = mutableListOf<AppViewModel>()
    private val a = Server(id = "fixture-vpn-a", name = "FixtureA", host = "a.invalid", port = 22, user = "u", herdrPath = "herdr")
    private var now = 10_000L

    private fun newVm() = AppViewModel(app).also { vms += it }

    @After fun close() {
        vms.forEach { it.viewModelScope.cancel() }
        shadowOf(Looper.getMainLooper()).idle()
        VpnSmallPackets.enabled = true
    }

    @Test fun onByDefaultForAnExistingInstall() {
        // An install from before 2.2.7 has other prefs saved but never this key.
        AppPrefs(app).alertUsage = false
        val vm = newVm()
        assertTrue(vm.smallPacketsOnVpn)
        assertTrue(AppPrefs(app).smallPacketsOnVpn)
        assertTrue(VpnSmallPackets.enabled)
    }

    @Test fun theSettingPersistsAndDrivesEveryConnect() {
        val vm = newVm()
        vm.changeSmallPacketsOnVpn(false)
        assertFalse(AppPrefs(app).smallPacketsOnVpn)
        assertFalse(VpnSmallPackets.enabled)
        // A connection built the normal way (default providers) reads the global setting at connect.
        val s = HerdrSsh("127.0.0.1", 22, "u", Credentials(null, null), null, { null }, networkType = { "vpn" })
        assertNull(s.activeMss)

        VpnSmallPackets.enabled = true // as if the process restarted
        val again = newVm()
        assertFalse("restored from the saved setting", again.smallPacketsOnVpn)
        assertFalse(VpnSmallPackets.enabled)
        again.changeSmallPacketsOnVpn(true)
        assertTrue(newVm().smallPacketsOnVpn)
        assertEquals(1200, s.activeMss)
    }

    private fun signedIn(vm: AppViewModel, ssh: HerdrSsh) {
        vm.saveServer(a)
        state(vm, "current", a)
        field("ssh").set(vm, ssh)
        vm.smallPacketClock = { now }
        // The production rule at sign-in (AppViewModel's arm call).
        vm.smallPacketFallback.arm(a.id, !ssh.overWebSocket && ssh.activeMss == null && ssh.lastMssChoice?.mss == null, now, NetworkWatch.changes)
    }

    @Test fun theFallbackNeverFiresWhenVpnAutoWasInEffect() {
        val vm = newVm()
        DiagLog.clear()
        val ssh = HerdrSsh(a.host, a.port, a.user, Credentials(null, null), null, { null }, networkType = { "vpn" })
        assertEquals(MssChoice.VPN_AUTO, ssh.mssChoice().reason)
        signedIn(vm, ssh)
        now += 5_000
        assertFalse("not armed: small packets already on", vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("stall")), ssh))
        // Even if it had been armed (a race at sign-in), the vpn-auto guard skips it and says so.
        vm.smallPacketFallback.arm(a.id, true, now, NetworkWatch.changes)
        assertFalse(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("stall")), ssh))
        assertTrue(DiagLog.text().contains("fallback=skipped reason=vpn-auto trigger=link-drop"))
        assertNull(vm.smallPacketOffer)
        assertEquals(MssChoice(1200, MssChoice.VPN_AUTO, "vpn"), ssh.mssChoice())
    }

    @Test fun withTheToggleOffTheFallbackWorksOnAVpnAsIn226() {
        val vm = newVm()
        vm.changeSmallPacketsOnVpn(false)
        val ssh = HerdrSsh(a.host, a.port, a.user, Credentials(null, null), null, { null }, networkType = { "vpn" })
        signedIn(vm, ssh)
        now += 5_000
        assertTrue(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("stall")), ssh))
        assertEquals(MssChoice(1200, MssChoice.FALLBACK, "vpn"), ssh.mssChoice())
        assertEquals(a.id, vm.smallPacketOffer)
    }

    @Test fun offAVpnTheFallbackIsUnchanged() {
        val vm = newVm()
        val ssh = HerdrSsh(a.host, a.port, a.user, Credentials(null, null), null, { null }, networkType = { "wifi" })
        signedIn(vm, ssh)
        now += 5_000
        assertTrue(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("stall")), ssh))
        assertEquals(MssChoice.FALLBACK, ssh.mssChoice().reason)
    }

    private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun state(vm: AppViewModel, name: String, value: Any?) {
        (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value
    }
}
