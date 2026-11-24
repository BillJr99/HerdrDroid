package com.billmongan.herdrdroid

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
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
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 2.2.7, Bill: "unsent text typed into the message box can disappear when switching screens
 * briefly and returning". The draft is kept by server and pane, survives navigation and a new
 * process, never shows elsewhere, and ends only once delivered (or cleared).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1000dp")
class ComposerDraft227Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val vms = mutableListOf<AppViewModel>()
    private val a = Server(id = "draft-a", name = "A", host = "127.0.0.1", port = 1, user = "u", herdrPath = "herdr")
    private val b = Server(id = "draft-b", name = "B", host = "127.0.0.1", port = 2, user = "u", herdrPath = "herdr")

    @Before fun plainSeal() {
        // The Android Keystore is not in Robolectric: seal as is, to test the keeping, not the sealing.
        DraftSeal.seal = { it }
        DraftSeal.open = { it }
    }

    @After fun close() { vms.forEach { v -> rule.runOnUiThread { v.viewModelScope.cancel() } } }

    private class Quiet(var refuse: Boolean = false) : Herdr.Commands {
        override val connection: Any? = Any()
        val prompts = mutableListOf<String>()
        private val pane = """{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"claude","agent_status":"idle","cwd":"/fixture","terminal_id":"t1","scroll":{"offset_from_bottom":0}}"""
        override suspend fun bulk(command: String) = ExecResult(0, """{"result":{"panes":[$pane]}}
            |@@herdrdroid-tabs@@
            |{"result":{"tabs":[]}}
        """.trimMargin(), "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = when {
            command.endsWith("'pane' 'get' 'p1'") -> ExecResult(0, """{"result":{"pane":$pane}}""", "")
            // [refuse]: someone is typing at the computer, so the send is held and never goes.
            command.contains("'pane' 'read'") -> ExecResult(0, if (refuse) "──\n❯ typed at the computer\n──" else "──\n❯ \n──", "")
            command.contains("'agent' 'prompt'") || command.contains("'pane' 'send-text'") -> {
                prompts += command.substringAfter("'p1' ").removeSurrounding("'"); ExecResult(0, "", "")
            }
            else -> ExecResult(0, "", "")
        }
    }

    private fun newVm(commands: Quiet = Quiet()): AppViewModel = rule.runOnUiThread {
        AppViewModel(app).also { vm ->
            vms += vm
            field(vm, "herdr").set(vm, Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), "herdr", Backend.TUI, null, commands))
            state(vm, "current", a)
            state(vm, "link", Link.CONNECTED)
            state(vm, "panes", listOf(Pane("p1", "tab", "w1", "claude", Status.IDLE, "", "/fixture", terminalId = "t1"),
                Pane("p2", "tab2", "w1", "codex", Status.IDLE, "", "/fixture", terminalId = "t2")))
        }
    }

    private fun field(vm: AppViewModel, name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state(vm: AppViewModel, name: String, value: Any?) { (field(vm, "$name\$delegate").get(vm) as MutableState<Any?>).value = value }

    @Test fun theDraftSurvivesSettingsAndAnotherPaneAndNeverLeaks() {
        val vm = newVm()
        rule.runOnUiThread { vm.openPane("p1") }
        rule.setContent {
            when (val s = vm.screen) {
                is Screen.Agent -> AgentScreen(vm, s.paneId, readScreen = {})
                else -> Text("Elsewhere")
            }
        }
        rule.onNode(hasSetTextAction()).performTextInput("half a thought")
        rule.runOnIdle { assertEquals("half a thought", vm.composerDraft("p1")) }
        // Settings and back.
        rule.runOnUiThread { vm.screen = Screen.Settings(Screen.Agent("p1")) }
        rule.onNodeWithText("Elsewhere").assertExists()
        rule.runOnUiThread { vm.screen = Screen.Agent("p1") }
        rule.onNodeWithText("half a thought").assertExists()
        // Another pane: empty there; back to the first: restored.
        rule.runOnUiThread { vm.openPane("p2") }
        rule.onNodeWithText("half a thought").assertDoesNotExist()
        rule.runOnIdle { assertEquals("", vm.composerDraft("p2")) }
        rule.runOnUiThread { vm.openPane("p1") }
        rule.onNodeWithText("half a thought").assertExists()
        // Another server with the same pane id: not leaked; back: still there.
        rule.runOnUiThread { state(vm, "current", b) }
        rule.runOnIdle { assertEquals("", vm.composerDraft("p1")) }
        rule.onNodeWithText("half a thought").assertDoesNotExist()
        rule.runOnUiThread { state(vm, "current", a) }
        rule.runOnIdle { assertEquals("half a thought", vm.composerDraft("p1")) }
    }

    @Test fun theDraftSurvivesANewProcess() {
        val vm = newVm()
        rule.runOnUiThread { vm.setComposerDraft("p1", "kept across a restart") }
        // The save is a moment later, off the main thread.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            app.getSharedPreferences("composer-drafts", android.content.Context.MODE_PRIVATE).getString("sealed", null) == null) {
            rule.mainClock.advanceTimeBy(100); Thread.sleep(20)
        }
        val again = newVm()
        rule.runOnIdle { assertEquals("kept across a restart", again.composerDraft("p1")) }
        rule.runOnUiThread { state(again, "current", b) }
        rule.runOnIdle { assertEquals("", again.composerDraft("p1")) }
    }

    @Test fun aDeliveredSendClearsItAndAFailedOneKeepsIt() {
        val ok = Quiet()
        val vm = newVm(ok)
        rule.runOnUiThread {
            vm.setComposerDraft("p1", "goes out")
            vm.sendFromComposer(checkNotNull(vm.pane("p1")), "goes out")
            assertEquals("out of the box at once", "", vm.composerDraft("p1"))
        }
        waitFor { ok.prompts.isNotEmpty() }
        rule.runOnIdle { vm.removableSendsFor("p1").forEach { vm.dismissPending(it.id) } }
        rule.runOnIdle { assertEquals("delivered: gone for good", "", vm.composerDraft("p1")) }

        val refusing = Quiet(refuse = true)
        val vm2 = newVm(refusing)
        rule.runOnUiThread {
            vm2.setComposerDraft("p1", "not delivered")
            vm2.sendFromComposer(checkNotNull(vm2.pane("p1")), "not delivered")
        }
        waitFor { vm2.removableSendsFor("p1").any { it.waiting } }
        // While its Resend row is up the text is not in the box too; removed, it comes back.
        rule.runOnIdle { assertEquals("", vm2.composerDraft("p1")) }
        rule.runOnIdle { vm2.removableSendsFor("p1").forEach { vm2.dismissPending(it.id) } }
        rule.runOnIdle { assertEquals("not delivered", vm2.composerDraft("p1")) }
        assertTrue("never sent by itself", refusing.prompts.isEmpty())
    }

    private fun waitFor(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            rule.mainClock.advanceTimeBy(50)
            if (rule.runOnIdle { done() }) return
            Thread.sleep(10)
        }
        fail("timed out")
    }
}
