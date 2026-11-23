package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.idleMainLooperRule
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2.1.3: system Back and the back arrow leave the server list for where it was opened from, and
 * Back on a server's form closes it to the list. 2.2.7: with no server open, the arrow shows too and
 * Back goes to the main screen, never out of the app.
 */
@RunWith(AndroidJUnit4::class)
class ServersBackUiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = idleMainLooperRule()
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val vm: AppViewModel by lazy { rule.runOnUiThread { AppViewModel(rule.activity.application) } }
    private val server = Server(id = "hd213-back", name = "BackFixture", host = "back.invalid", port = 22, user = "fix", herdrPath = "/fixture/herdr")
    private val codex = Pane("w1:p1", "w1:t1", "w1", "codex", Status.IDLE, "Codex · 1", "/fixture", scrollOffset = 0, terminalId = "t1")

    @After fun close() {
        rule.runOnUiThread { vm.viewModelScope.cancel() }
    }

    private fun openFromAgent() = rule.runOnUiThread {
        state("current", server)
        state("panes", listOf(codex))
        vm.screen = Screen.Agent(codex.id)
        vm.openServers()
    }

    private fun pressBack() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun systemBackLeavesTheServerListForTheAgentItWasOpenedFrom() {
        openFromAgent()
        rule.setContent { ServersScreen(vm) }
        rule.onNodeWithText("Pick the computer running your agents.").assertIsDisplayed()
        pressBack()
        rule.runOnIdle { assertEquals(Screen.Agent(codex.id), vm.screen) }
    }

    @Test fun theBackArrowLeavesTheServerListToo() {
        openFromAgent()
        rule.setContent { ServersScreen(vm) }
        rule.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(Screen.Agent(codex.id), vm.screen) }
    }

    @Test fun backOnAServersFormClosesItToTheList() {
        openFromAgent()
        rule.runOnUiThread { vm.screen = Screen.EditServer(server) }
        rule.setContent { EditServerScreen(vm, server) }
        rule.onNodeWithText("Edit server").assertIsDisplayed()
        pressBack()
        rule.runOnIdle { assertEquals(Screen.Servers, vm.screen) }
    }

    // 2.2.7: with no server open the list has its arrow too, and Back stays in the app.
    @Test fun withNoServerOpenTheArrowAndBackGoToTheMainScreen() {
        rule.runOnUiThread { vm.screen = Screen.Servers }
        rule.setContent { ServersScreen(vm) }
        rule.onNodeWithText("Pick the computer running your agents.").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("Back").assertCountEquals(1)
        rule.runOnIdle { assertTrue(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
        pressBack()
        rule.runOnIdle {
            assertEquals(Screen.Home, vm.screen)
            assertFalse(rule.activity.isFinishing)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        val f = vm.javaClass.getDeclaredField("$name\$delegate").apply { isAccessible = true }
        (f.get(vm) as MutableState<Any?>).value = value
    }
}
