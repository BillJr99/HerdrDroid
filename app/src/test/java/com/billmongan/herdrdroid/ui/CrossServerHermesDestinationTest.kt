package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.*

import android.app.Application
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
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
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
 * herdrdroid"). Claude is the source on one server; minipc2 runs Hermes with the row herdr really
 * gives it: a `local-` agent session, an idle status, and a cwd that differs from foreground_cwd.
 * 2.1.5: a frame naming Hermes resolves to it, and typing the card's code in the composer sends it.
 */
@RunWith(AndroidJUnit4::class)
class CrossServerHermesDestinationTest {
    @get:Rule val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: AppViewModel
    private val source = Source()
    private val minipc2 = Minipc2()
    private val exact = "Hermes, please check tonight's cron run."
    private val code = "H3RM2S"

    // This class renders Compose, so it lives in ui with the suite's other Compose classes. In the
    // root package it ran before GrokRcReviewFixesTest, OpenCodeAlert212Test and the other view
    // model tests there; Compose started that early left every later Compose test unable to go
    // idle (AppNotIdleException from PanePrefetchTest on). The view model is made inside the rule,
    // on the UI thread, as ComposeUiTest makes its own.
    @Before fun open() {
        rule.runOnUiThread {
            vm = AppViewModel(app)
            // Only the proposal path here; the 2.1.4 notice has its own tests.
            vm.changeCrossServerNotice(false)
            vm.onForeground()
            field(vm, "herdr").set(vm, source.client)
            state(vm, "servers", listOf(source.server, minipc2.server))
            state(vm, "current", source.server)
            state(vm, "panes", listOf(source.pane))
            vm.screen = Screen.Agent("p1")
            val fleet = Fleet(vm.viewModelScope, { Credentials(null, null) }, { null }, {})
            // The watcher's panes come from the real herdr parse of minipc2's pane and tab lists.
            val watched = runBlocking { minipc2.client.panes() }
            state(fleet, "states", mapOf(minipc2.server.id to FleetState(minipc2.server, watched, null, 0)))
            field(fleet, "clients").set(fleet, mutableMapOf(minipc2.server.id to (minipc2.ssh to minipc2.client)))
            field(vm, "fleet").set(vm, fleet)
        }
    }

    @After fun close() {
        rule.runOnUiThread { vm.viewModelScope.cancel() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun realHermesRowIsAnIdentifiableIdleAgent() = runBlocking {
        val hermes = minipc2.client.panes().single { it.id == "w2:p9" }
        assertEquals("hermes", hermes.agent)
        assertEquals("hermes", hermes.tabLabel)
        assertEquals(Status.IDLE, hermes.status)
        assertEquals("/home/bill/agents/hermes", hermes.cwd)
        assertTrue(checkNotNull(hermes.sessionId).startsWith("local-"))
        assertTrue(hasStableOccupant(hermes))
    }

    @Test fun aFrameNamingHermesResolvesToHermesOnTheOtherServer() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame("Hermes on minipc2")
        val chosen = checkNotNull(card.choice)
        assertEquals(minipc2.server.id, chosen.server.id)
        assertEquals("w2:p9", chosen.pane.id)
        assertTrue(vm.crossServerChoices(card).isEmpty())
        // A working agent is still reachable by name; only sending waits for it to be idle.
        val names = vm.crossServerReachable(card).map { vm.agentFacingName(it) }
        assertTrue(names.toString(), "Hermes on minipc2" in names && "Claude on minipc2" in names)
        assertTrue(names.none { it.contains("minipc.invalid") || it.contains(":22") })
        assertTrue(source.writes.isEmpty() && minipc2.writes.isEmpty())
    }

    /** Grok's RC review 4: agents call an unnamed server "server 2", with no account, host or port. */
    @Test fun anUnnamedServerIsNamedOnlyByItsPlaceForAgents() = runBlocking {
        unnameMinipc2()
        vm.changeAgentMessaging(true)
        val card = frame("Hermes on server 2")
        assertEquals("w2:p9", checkNotNull(card.choice).pane.id)
        for (name in vm.crossServerReachable(card).map { vm.agentFacingName(it) }) {
            assertFalse(name, name.contains("fixture") || name.contains(".invalid") || name.contains(":22") || name.contains("@"))
        }
    }

    /** The card is seen only by the phone user: it keeps the full label of an unnamed server. */
    @Test fun theCardKeepsTheFullLabelOfAnUnnamedServer() {
        unnameMinipc2()
        vm.changeAgentMessaging(true)
        runBlocking { frame("Somebody") }
        rule.setContent { CrossServerPanel(vm, "p1") }
        rule.onNodeWithText("Hermes on fixture@minipc2.invalid · minipc2.invalid:22 · w2:p9").performScrollTo().assertIsDisplayed()
        assertTrue(minipc2.writes.isEmpty())
    }

    /** The card, then the confirmation typed in the pane's own composer: delivered to Hermes, nothing typed into the source. */
    @Test fun theCardShowsTheMessageAndTypingItsCodeInTheComposerSendsIt() {
        vm.changeAgentMessaging(true)
        runBlocking { frame("Hermes on minipc2") }
        val passedOn = mutableListOf<String>()
        rule.setContent {
            Column {
                CrossServerPanel(vm, "p1")
                var text by remember { mutableStateOf("") }
                ChatComposer(value = text, onValueChange = { text = it }, placeholder = "Message Claude",
                    canSend = text.isNotBlank(), onSend = { composerSend(vm, "p1", text.trim()) { passedOn += it }; text = "" })
            }
        }
        rule.onNodeWithContentDescription("Cross-server message card").assertIsDisplayed()
        rule.onNodeWithText("Cross-server message").assertIsDisplayed()
        rule.onNodeWithText("From: Claude on minipc").assertIsDisplayed()
        rule.onNodeWithText("To: Hermes on minipc2").assertIsDisplayed()
        rule.onNodeWithText(exact).assertIsDisplayed()
        rule.onNodeWithText("Code: $code").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        // Other text goes to the pane as usual and the card stays.
        rule.onNode(hasSetTextAction()).performTextInput("thanks")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.runOnIdle { assertEquals(listOf("thanks"), passedOn) }
        rule.onNodeWithText("Code: $code").assertIsDisplayed()
        assertTrue(minipc2.writes.isEmpty())
        rule.onNode(hasSetTextAction()).performTextInput(code.lowercase())
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(5_000) { vm.crossServerCardsFor("p1").single().state == AppViewModel.CrossServerState.SENT }
        rule.onNodeWithText(RelayResult.SENT.feedback).assertIsDisplayed()
        rule.onNodeWithText("Close").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf("thanks"), passedOn) }
        assertEquals(1, minipc2.writes.size)
        assertTrue(minipc2.writes.single().contains("'agent' 'prompt' 'w2:p9'"))
        assertTrue(source.writes.isEmpty())
    }

    @Test fun cancelOnTheCardSendsNothing() {
        vm.changeAgentMessaging(true)
        runBlocking { frame("Hermes on minipc2") }
        rule.setContent { CrossServerPanel(vm, "p1") }
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Cancelled. Nothing was sent.").assertIsDisplayed()
        rule.runOnIdle { assertTrue(vm.crossServerCodeReply("p1", code)) }
        assertTrue(minipc2.writes.isEmpty() && source.writes.isEmpty())
    }

    @Test fun anUnmatchedDestinationOffersTheReachableAgentsToChooseFrom() {
        vm.changeAgentMessaging(true)
        runBlocking { frame("Somebody") }
        rule.setContent { CrossServerPanel(vm, "p1") }
        rule.onNodeWithText("Choose a destination:").assertIsDisplayed()
        rule.onNodeWithText("Claude on minipc2 · minipc2.invalid:22 · w2:p5").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Hermes on minipc2 · minipc2.invalid:22 · w2:p9").performScrollTo().performClick()
        rule.onNodeWithText("To: Hermes on minipc2").assertIsDisplayed()
        assertTrue(minipc2.writes.isEmpty())
    }

    private fun unnameMinipc2() {
        val unnamed = minipc2.server.copy(name = "")
        rule.runOnUiThread {
            val watched = runBlocking { minipc2.client.panes() }
            state(vm, "servers", listOf(source.server, unnamed))
            state(field(vm, "fleet").get(vm) as Fleet, "states", mapOf(unnamed.id to FleetState(unnamed, watched, null, 0)))
        }
    }

    private suspend fun frame(to: String): AppViewModel.CrossServerCard {
        vm.readPane("p1")
        source.frame = fixtureMessageFrame(code, exact, to)
        vm.readPane("p1")
        return vm.crossServerCardsFor("p1").single().also { assertEquals(exact, it.text) }
    }

    /** Claude on the server the phone has open: the source of the proposal. */
    private class Source : Herdr.Commands {
        val server = Server(id = "minipc", name = "minipc", host = "minipc.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
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

    /** minipc2, watched by Fleet, with the rows `herdr pane list` and `herdr tab list` give there. */
    private class Minipc2 : Herdr.Commands {
        val server = Server(id = "minipc2", name = "minipc2", host = "minipc2.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
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
            "'--source' 'visible'" in command -> ExecResult(0, "❯ ", "")
            "'agent' 'prompt'" in command -> { writes += command; ExecResult(0, "", "") }
            else -> error("Unexpected minipc2 command: $command")
        }

        companion object {
            const val HERMES = """{"agent":"hermes","agent_session":{"agent":"hermes","kind":"id","source":"herdr:hermes","value":"local-0a90b0f4004e689564430e279fe241242bfcd4c6ec8b3d6403e31424e3e98b0f"},"agent_status":"idle","cwd":"/home/bill","focused":false,"foreground_cwd":"/home/bill/agents/hermes","pane_id":"w2:p9","revision":1,"scroll":{"max_offset_from_bottom":151,"offset_from_bottom":0,"viewport_rows":47},"tab_id":"w2:t6","terminal_id":"term_6603a9992f0f05","terminal_title":"bill@minipc2: ~","terminal_title_stripped":"bill@minipc2: ~","workspace_id":"w2"}"""
            const val CLAUDE = """{"agent":"claude","agent_session":{"agent":"claude","kind":"id","source":"herdr:claude","value":"9989db43-2c5c-42c9-b31c-eab9c4663226"},"agent_status":"working","cwd":"/home/bill","focused":false,"foreground_cwd":"/home/bill/agents/claude","pane_id":"w2:p5","revision":2,"scroll":{"max_offset_from_bottom":0,"offset_from_bottom":0,"viewport_rows":47},"tab_id":"w2:t2","terminal_id":"term_6603a9992d9361","terminal_title":"MiniPC2","terminal_title_stripped":"MiniPC2","workspace_id":"w2"}"""
        }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state(owner: Any, name: String, value: Any?) {
        (field(owner, "$name\$delegate").get(owner) as MutableState<Any?>).value = value
    }
}
