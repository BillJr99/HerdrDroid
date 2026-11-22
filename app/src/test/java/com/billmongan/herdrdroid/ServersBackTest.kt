package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.1.3, Bill: the server list opened from a connected app had no way back out; Back left the app
 * and opening it again landed on the list. Back now returns to where the list was opened from.
 */
@RunWith(AndroidJUnit4::class)
class ServersBackTest {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val server = Server(id = "fixture-a", name = "FixtureA", host = "a.invalid", port = 22, user = "fixa", herdrPath = "/fixture/herdr")
    private val codex = Pane("w1:p1", "w1:t1", "w1", "codex", Status.IDLE, "Codex · 1", "/fixture", scrollOffset = 0, terminalId = "t1")

    @After fun close() {
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun connected() {
        state("current", server)
        state("panes", listOf(codex))
    }

    @Test fun backFromServersOpenedOnAnAgentReturnsToThatAgent() {
        connected()
        vm.screen = Screen.Agent(codex.id)
        vm.openServers()
        assertEquals(Screen.Servers, vm.screen)
        assertEquals(Screen.Agent(codex.id), vm.serversBack)
        assertTrue(vm.leaveServers())
        assertEquals(Screen.Agent(codex.id), vm.screen)
    }

    @Test fun backFromServersOpenedFromSettingsReturnsToSettings() {
        connected()
        vm.screen = Screen.Settings(Screen.Agent(codex.id))
        vm.openServers()
        assertTrue(vm.leaveServers())
        assertEquals(Screen.Settings(Screen.Agent(codex.id)), vm.screen)
    }

    @Test fun theFormAndSettingsFromTheListKeepWhereBackGoes() {
        connected()
        vm.screen = Screen.Tabs
        vm.openServers()
        // A server's form: Back (or Cancel) closes it to the list first.
        vm.screen = Screen.EditServer(server)
        vm.closeServerForm(null)
        assertEquals(Screen.Servers, vm.screen)
        // Settings from the list's gear, and back.
        vm.screen = Screen.Settings(Screen.Servers)
        vm.screen = Screen.Servers
        assertTrue(vm.leaveServers())
        assertEquals(Screen.Tabs, vm.screen)
    }

    @Test fun serversShownWithoutAnOriginGoBackToTheMainScreen() {
        connected()
        vm.screen = Screen.Agent(codex.id)
        vm.screen = Screen.Tabs
        // Shown directly (a locked server picked in the drawer, say): back to this server's main screen.
        vm.screen = Screen.Servers
        assertEquals(Screen.Tabs, vm.serversBack)
        // A remembered agent that has since closed is not returned to either.
        vm.screen = Screen.Agent("w9:p9")
        vm.openServers()
        state("panes", listOf(codex))
        assertEquals(Screen.Tabs, vm.serversBack)
    }

    @Test fun anOriginIsForgottenOnceTheUserGoesElsewhere() {
        connected()
        vm.screen = Screen.Agent(codex.id)
        vm.openServers()
        vm.screen = Screen.Usage
        vm.screen = Screen.Servers
        assertEquals(Screen.Tabs, vm.serversBack)
    }

    @Test fun withNoServerOpenTheListIsTheMainScreenAndBackLeavesTheApp() {
        assertEquals(Screen.Servers, vm.screen)
        assertNull(vm.serversBack)
        assertFalse(vm.leaveServers())
        assertEquals(Screen.Servers, vm.screen)
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        (field(vm, "$name\$delegate").get(vm) as MutableState<Any?>).value = value
    }
}
