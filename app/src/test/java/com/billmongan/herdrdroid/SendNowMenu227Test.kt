package com.billmongan.herdrdroid

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.ui.AgentScreen
import com.billmongan.herdrdroid.ui.PendingMessageRow
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 2.2.7: the long-press Send now on a queued message, and only there. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1000dp")
class SendNowMenu227Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private class Quiet : Herdr.Commands {
        override val connection: Any? = Any()
        val writes = mutableListOf<String>()
        /** The pane list keeps the working Claude pane listed, so a refresh never drops it. */
        private val pane = """{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"claude","agent_status":"working","cwd":"/fixture","terminal_id":"t1","scroll":{"offset_from_bottom":0}}"""
        override suspend fun bulk(command: String) = ExecResult(0, """{"result":{"panes":[$pane]}}
            |@@herdrdroid-tabs@@
            |{"result":{"tabs":[]}}
        """.trimMargin(), "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            if (command.contains("send-") || command.contains("'agent' 'prompt'")) writes += command
            if (command.endsWith("'pane' 'get' 'p1'")) return ExecResult(0, """{"result":{"pane":$pane}}""", "")
            return ExecResult(0, "", "")
        }
        override suspend fun input(command: String, input: ByteArray, expectedConnection: Any) = ExecResult(0, "", "")
    }

    @Test fun aQueuedMessageOffersSendNowWhichAsksForceSendForAWorkingClaude() {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val server = Server(id = "fixture", name = "Fixture", host = "unused.invalid", port = 22, user = "u", herdrPath = "herdr")
        val claude = Pane("p1", "tab", "w1", "claude", Status.WORKING, "", "/fixture", terminalId = "t1")
        // Notices from Send now show here, so a refusal is visible in the failure message.
        val commands = Quiet()
        fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        fun <T> state(name: String) = field("$name\$delegate").get(vm) as MutableState<T>
        try {
            rule.runOnUiThread {
                field("herdr").set(vm, Herdr(HerdrSsh("unused.invalid", 22, "u", Credentials(null, null), null, { null }), "herdr", Backend.TUI, null, commands))
                state<Server?>("current").value = server
                state<Link>("link").value = Link.CONNECTED
                state<List<Pane>>("panes").value = listOf(claude)
                // No background sweeps: they would go past the fake to a real connection and drop the link.
                field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
                field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
                // Nor the chat log read, which goes past the fake too (to a real connection that fails).
                field("readers").set(vm, emptySet<String>())
                vm.openPane("p1")
                vm.enqueue("p1", "a queued hello")
            }
            rule.setContent { AgentScreen(vm, "p1", readScreen = {}) }
            rule.onNodeWithText("Queued until idle").assertExists()
            rule.onNodeWithText("a queued hello").performTouchInput { longClick() }
            rule.onNodeWithText("Send now").assertExists()
            rule.onNodeWithText("Copy").assertExists()
            // Some reads (agent versions, quotas) still go past the fake to a connection that can't
            // resolve and mark the link lost; the dialog only needs the link up when Send now is tapped.
            rule.runOnUiThread { field("reconnectJob").let { f -> (f.get(vm) as? kotlinx.coroutines.Job)?.cancel() }; state<Link>("link").value = Link.CONNECTED }
            rule.onNodeWithText("Send now").performClick()
            // Claude is working and its screen shows no background hint: the Force Send question.
            rule.onNodeWithText("Force send?").assertExists()
            rule.onNodeWithText("Send anyway").assertExists()
            rule.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
            rule.runOnIdle {
                assertNull(vm.sendNowConfirm)
                assertEquals(listOf("a queued hello"), vm.queued.map { it.text })
                assertTrue(commands.writes.isEmpty())
            }
        } finally {
            rule.runOnUiThread { vm.viewModelScope.cancel() }
        }
    }

    @Test fun anUnsentMessagesMenuHasNoSendNow() {
        rule.setContent {
            PendingMessageRow(title = "Waiting for the input line", text = "A pending message", onRemove = {},
                sendLabel = null, onSend = {}, forceSendMenu = true)
        }
        rule.onNodeWithText("A pending message").performTouchInput { longClick() }
        rule.onNodeWithText("Force send immediately").assertExists()
        rule.onNodeWithText("Send now").assertDoesNotExist()
    }
}
