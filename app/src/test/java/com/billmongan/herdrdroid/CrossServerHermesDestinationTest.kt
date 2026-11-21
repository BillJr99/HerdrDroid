package com.billmongan.herdrdroid

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.fleet.Fleet
import com.billmongan.herdrdroid.fleet.FleetState
import com.billmongan.herdrdroid.herdr.*
import com.billmongan.herdrdroid.ssh.*
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.ui.AgentMessagePanel
import com.billmongan.herdrdroid.ui.destinationLabels
import com.billmongan.herdrdroid.ui.proposalInstructions
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.1.4 (Bill: "Claude on another host doesn't think it can see hermes here via cross server
 * herdrdroid"). Claude is the source on one server; laptop2 runs Hermes with the row herdr really
 * gives it: a `local-` agent session, an idle status, and a cwd that differs from foreground_cwd.
 * Hermes is named in the instructions Claude is given and offered in the review.
 */
@RunWith(AndroidJUnit4::class)
class CrossServerHermesDestinationTest {
    @get:Rule val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: AppViewModel
    private val source = Source()
    private val laptop2 = Laptop2()
    private val exact = "Hermes, please check tonight's cron run."

    // The view model is made inside the compose rule, on the UI thread, as ComposeUiTest and
    // ServersBackUiTest make theirs. Made in the test's constructor, it started its coroutines and
    // wrote its Compose state before the rule's test environment existed; this was one of only two
    // Compose test classes that did so, and with it the full suite's later Compose tests timed out
    // with AppNotIdleException (CardBodyTest, ComposeUiTest, PaneDraftCardTest and others).
    @Before fun open() {
        rule.runOnUiThread {
            vm = AppViewModel(app)
            vm.onForeground()
            field(vm, "herdr").set(vm, source.client)
            state(vm, "servers", listOf(source.server, laptop2.server))
            state(vm, "current", source.server)
            state(vm, "panes", listOf(source.pane))
            vm.screen = Screen.Agent("p1")
            val fleet = Fleet(vm.viewModelScope, { Credentials(null, null) }, { null }, {})
            // The watcher's panes come from the real herdr parse of laptop2's pane and tab lists.
            val watched = runBlocking { laptop2.client.panes() }
            state(fleet, "states", mapOf(laptop2.server.id to FleetState(laptop2.server, watched, null, 0)))
            field(fleet, "clients").set(fleet, mutableMapOf(laptop2.server.id to (laptop2.ssh to laptop2.client)))
            field(vm, "fleet").set(vm, fleet)
        }
    }

    @After fun close() {
        rule.runOnUiThread { vm.viewModelScope.cancel() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun realHermesRowIsAnIdentifiableIdleAgent() = runBlocking {
        val hermes = laptop2.client.panes().single { it.id == "w2:p9" }
        assertEquals("hermes", hermes.agent)
        assertEquals("hermes", hermes.tabLabel)
        assertEquals(Status.IDLE, hermes.status)
        assertEquals("/home/bill/agents/hermes", hermes.cwd)
        assertTrue(checkNotNull(hermes.sessionId).startsWith("local-"))
        assertTrue(hasStableOccupant(hermes))
    }

    @Test fun instructionsGivenToTheRemoteAgentNameHermes() = runBlocking {
        vm.changeAgentMessaging(true)
        assertTrue(vm.armAgentMessage("p1"))
        val armed = checkNotNull(vm.agentMessage("p1"))
        val reachable = vm.agentMessageReachable(armed)
        assertTrue(reachable.none { it.server.id == source.server.id })
        val names = reachable.map { "${com.billmongan.herdrdroid.ui.sessionName(it.pane)} on ${it.server.label}" }
        assertTrue(names.toString(), "Hermes on laptop2" in names)
        // A working agent is still reachable by name; only the review waits for it to be idle.
        assertTrue(names.toString(), "Claude on laptop2" in names)
        val text = proposalInstructions(armed.code, names)
        assertTrue(text, text.contains("\n- Hermes on laptop2"))
        assertTrue(text.contains("HDMSG1 ${armed.code}") && text.contains("HDEND ${armed.code}"))
        assertTrue(text.contains("The phone user will choose the destination"))
        assertFalse(text.contains("laptop2.invalid"))
        assertTrue(proposalInstructions(armed.code, emptyList()).contains("sees no agent on another server"))
    }

    @Test fun copiedInstructionsFromThePanelNameHermes() {
        vm.changeAgentMessaging(true)
        assertTrue(vm.armAgentMessage("p1"))
        rule.setContent { AgentMessagePanel(vm, "p1") }
        rule.onNodeWithText("Copy proposal instructions").performClick()
        rule.waitForIdle()
        val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val copied = checkNotNull(clip).getItemAt(0).text.toString()
        assertTrue(copied, copied.contains("\n- Hermes on laptop2"))
    }

    @Test fun reviewListsHermesOnTheOtherServerAndBindsIt() = runBlocking {
        vm.changeAgentMessaging(true)
        val request = propose()
        val labels = destinationLabels(vm.agentMessageDestinations(request))
        val hermes = "Hermes on laptop2 · laptop2.invalid:22 · w2:p9"
        assertEquals(labels.keys.toString(), setOf(hermes), labels.keys)
        // Binding reads `pane get`, whose row must still be the same occupant as the list's.
        assertNotNull(vm.bindAgentMessage(request, checkNotNull(labels[hermes])))
        assertTrue(source.writes.isEmpty() && laptop2.writes.isEmpty())
    }

    @Test fun consentDialogShowsHermes() {
        vm.changeAgentMessaging(true)
        runBlocking { propose() }
        rule.setContent { AgentMessagePanel(vm, "p1") }
        rule.onNodeWithText("Review agent proposal").performClick()
        rule.onNodeWithText("Choose a destination on another server:").assertIsDisplayed()
        rule.onNodeWithText("Hermes on laptop2 · laptop2.invalid:22 · w2:p9").performScrollTo().assertIsDisplayed()
        assertTrue(laptop2.writes.isEmpty())
    }

    private suspend fun propose(): AppViewModel.AgentMessage {
        assertTrue(vm.armAgentMessage("p1"))
        val armed = checkNotNull(vm.agentMessage("p1"))
        vm.readPane("p1")
        source.frame = fixtureMessageFrame(armed.code, exact)
        vm.readPane("p1")
        return checkNotNull(vm.agentMessage("p1")).also { assertEquals(exact, it.text) }
    }

    /** Claude on the server the phone has open: the source of the proposal. */
    private class Source : Herdr.Commands {
        val server = Server(id = "laptop", name = "laptop", host = "laptop.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        val client = Herdr(ssh, server.herdrPath, Backend.TUI, null, this)
        override var connection: Any? = Any()
        var frame = "Normal source output"
        val writes = mutableListOf<String>()
        val pane get() = Pane("p1", "tab", "w1", "claude", Status.IDLE, "", "/fixture", scrollOffset = 0, terminalId = "claude-term")
        override suspend fun bulk(command: String): ExecResult = error("Unexpected bulk command")
        override suspend fun input(command: String, input: ByteArray, expectedConnection: Any): ExecResult =
            ExecResult(0, """{"ok":true,"busy":{"p1":0}}""", "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = when {
            command.endsWith("'pane' 'get' 'p1'") -> ExecResult(0, """{"result":{"pane":{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"claude","agent_status":"idle","cwd":"/fixture","terminal_id":"claude-term","scroll":{"offset_from_bottom":0}}}}""", "")
            "'--source' 'recent'" in command -> ExecResult(0, frame, "")
            "'--source' 'visible'" in command -> ExecResult(0, "❯ ", "")
            "'agent' 'prompt'" in command -> { writes += command; ExecResult(0, "", "") }
            else -> error("Unexpected source command: $command")
        }
    }

    /** laptop2, watched by Fleet, with the rows `herdr pane list` and `herdr tab list` give there. */
    private class Laptop2 : Herdr.Commands {
        val server = Server(id = "laptop2", name = "laptop2", host = "laptop2.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        val client = Herdr(ssh, server.herdrPath, Backend.TUI, null, this)
        override var connection: Any? = Any()
        val writes = mutableListOf<String>()
        override suspend fun bulk(command: String): ExecResult =
            if ("'pane' 'list'" in command) ExecResult(0, """{"id":"cli:pane:list","result":{"panes":[$CLAUDE,$HERMES]}}
@@herdrdroid-tabs@@
{"id":"cli:tab:list","result":{"tabs":[{"tab_id":"w2:t2","label":"claude"},{"tab_id":"w2:t6","label":"hermes"}]}}""", "")
            else ExecResult(1, "", "not in this fixture")
        override suspend fun input(command: String, input: ByteArray, expectedConnection: Any): ExecResult =
            ExecResult(0, """{"ok":true,"busy":{"w2:p9":0,"w2:p5":0}}""", "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = when {
            command.endsWith("'pane' 'get' 'w2:p9'") -> ExecResult(0, """{"id":"cli:pane:get","result":{"pane":$HERMES,"type":"pane_info"}}""", "")
            "'agent' 'prompt'" in command -> { writes += command; ExecResult(0, "", "") }
            else -> error("Unexpected laptop2 command: $command")
        }

        companion object {
            const val HERMES = """{"agent":"hermes","agent_session":{"agent":"hermes","kind":"id","source":"herdr:hermes","value":"local-0a90b0f4004e689564430e279fe241242bfcd4c6ec8b3d6403e31424e3e98b0f"},"agent_status":"idle","cwd":"/home/bill","focused":false,"foreground_cwd":"/home/bill/agents/hermes","pane_id":"w2:p9","revision":1,"scroll":{"max_offset_from_bottom":151,"offset_from_bottom":0,"viewport_rows":47},"tab_id":"w2:t6","terminal_id":"term_6603a9992f0f05","terminal_title":"bill@laptop2: ~","terminal_title_stripped":"bill@laptop2: ~","workspace_id":"w2"}"""
            const val CLAUDE = """{"agent":"claude","agent_session":{"agent":"claude","kind":"id","source":"herdr:claude","value":"9989db43-2c5c-42c9-b31c-eab9c4663226"},"agent_status":"working","cwd":"/home/bill","focused":false,"foreground_cwd":"/home/bill/agents/claude","pane_id":"w2:p5","revision":2,"scroll":{"max_offset_from_bottom":0,"offset_from_bottom":0,"viewport_rows":47},"tab_id":"w2:t2","terminal_id":"term_6603a9992d9361","terminal_title":"Laptop2","terminal_title_stripped":"Laptop2","workspace_id":"w2"}"""
        }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state(owner: Any, name: String, value: Any?) {
        (field(owner, "$name\$delegate").get(owner) as MutableState<Any?>).value = value
    }
}
