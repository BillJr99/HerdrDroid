package com.billmongan.herdrdroid

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.ui.HomeScreen
import com.billmongan.herdrdroid.ui.ServersScreen
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2.2.7, Bill: "when I hit the back button on my phone it's still exits the app back to the desktop
 * rather than going back to the app main screen". Every way the server list opens, system Back and
 * the list's back arrow land on the main screen (the open server's last agent, else Home with its
 * drawer), and the activity is never finished.
 */
@RunWith(AndroidJUnit4::class)
class ServersBackToMain227Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val vm: AppViewModel by lazy { rule.runOnUiThread { AppViewModel(rule.activity.application) } }
    private val server = Server(id = "hd227-back", name = "BackFixture", host = "127.0.0.1", port = 1, user = "fix", herdrPath = "/fixture/herdr")
    private val claude = Pane("w1:p1", "w1:t1", "w1", "claude", Status.IDLE, "Claude", "/fixture", scrollOffset = 0, terminalId = "t1")

    @After fun close() { rule.runOnUiThread { vm.viewModelScope.cancel() } }

    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        val f = vm.javaClass.getDeclaredField("$name\$delegate").apply { isAccessible = true }
        (f.get(vm) as MutableState<Any?>).value = value
    }

    private fun connected() { state("current", server); state("panes", listOf(claude)) }

    /** Opens the list with [open], then checks system Back; opens it again and checks the arrow. */
    private fun backAndArrowLandOn(expected: () -> Screen, open: () -> Unit) {
        rule.runOnUiThread(open)
        rule.setContent { ServersScreen(vm) }
        rule.runOnIdle { assertEquals(Screen.Servers, vm.screen) }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle {
            assertEquals("system Back", expected(), vm.screen)
            assertFalse("Back never leaves the app from the list", rule.activity.isFinishing)
        }
        rule.runOnUiThread(open)
        rule.runOnIdle { assertEquals(Screen.Servers, vm.screen) }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.runOnIdle {
            assertEquals("the back arrow", expected(), vm.screen)
            assertFalse(rule.activity.isFinishing)
        }
    }

    @Test fun atStartupWithNoServerOpen() = backAndArrowLandOn({ Screen.Home }) { vm.screen = Screen.Servers }

    @Test fun fromTheDrawersServersLink() = backAndArrowLandOn({ Screen.Agent(claude.id) }) {
        connected()
        vm.screen = Screen.Agent(claude.id)
        vm.openServers()
    }

    @Test fun fromTheMachineMenusAllServers() = backAndArrowLandOn({ Screen.Agent(claude.id) }) {
        connected()
        vm.openPane(claude.id) // the agent last open on this server
        vm.screen = Screen.Tabs
        vm.showAllServers()
    }

    @Test fun afterTheTopBarsBackToServersDisconnect() = backAndArrowLandOn({ Screen.Home }) {
        connected()
        vm.screen = Screen.Tabs
        vm.disconnect()
    }

    @Test fun afterAFailedConnect() = backAndArrowLandOn({ Screen.Home }) {
        // A connect that fails leaves no server open and the list showing with its error.
        vm.error = "BackFixture didn't answer"
        vm.screen = Screen.Servers
    }

    @Test fun fromANotificationForALockedServer() = backAndArrowLandOn({ Screen.Agent(claude.id) }) {
        connected()
        vm.openPane(claude.id)
        val locked = Server(id = "hd227-locked", name = "Locked", host = "127.0.0.1", port = 2, user = "fix", herdrPath = "/fixture/herdr")
        vm.saveServer(locked)
        vm.screen = Screen.Agent(claude.id)
        // It can't sign in without asking, so the notification opens the server list.
        vm.openFromNotification(locked.id, "w2:p1")
    }

    @Test fun theMainScreenWithNoAgentOffersTheDrawerAndServers() {
        rule.setContent { HomeScreen(vm) }
        rule.onNodeWithText("No server is open").assertExists()
        rule.onNodeWithText("Show my panes").performClick()
        rule.runOnIdle { assertTrue(vm.drawerOpen) }
        rule.onNodeWithText("Servers").performClick()
        rule.runOnIdle { assertEquals(Screen.Servers, vm.screen) }
    }
}
