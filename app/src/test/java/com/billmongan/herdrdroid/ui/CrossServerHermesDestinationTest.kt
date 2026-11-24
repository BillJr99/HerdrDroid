package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.IdleMainLooperRule
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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
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
 * 2.1.5: a frame naming Hermes resolves to it. 2.2.0: Yes on the phone's dialog (or Approve on the
 * card) sends it; nobody types the code.
 */
@RunWith(AndroidJUnit4::class)
class CrossServerHermesDestinationTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: AppViewModel
    private val source = Source()
    private val laptop2 = Laptop2()
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

    @Test fun aFrameNamingHermesResolvesToHermesOnTheOtherServer() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame("Hermes on laptop2")
        val chosen = checkNotNull(card.choice)
        assertEquals(laptop2.server.id, chosen.server.id)
        assertEquals("w2:p9", chosen.pane.id)
        assertTrue(vm.crossServerChoices(card).isEmpty())
        // A working agent is still reachable by name; only sending waits for it to be idle.
        val names = vm.crossServerReachable(card).map { vm.agentFacingName(it) }
        assertTrue(names.toString(), "Hermes on laptop2" in names && "Claude on laptop2" in names)
        assertTrue(names.none { it.contains("127.0.0.1") || it.contains(":1") || it.contains(":2") })
        assertTrue(source.writes.isEmpty() && laptop2.writes.isEmpty())
    }

    /** Grok's RC review 4: agents call an unnamed server "server 2", with no account, host or port. */
    @Test fun anUnnamedServerIsNamedOnlyByItsPlaceForAgents() = runBlocking {
        unnameLaptop2()
        vm.changeAgentMessaging(true)
        val card = frame("Hermes on server 2")
        assertEquals("w2:p9", checkNotNull(card.choice).pane.id)
        for (name in vm.crossServerReachable(card).map { vm.agentFacingName(it) }) {
            assertFalse(name, name.contains("fixture") || name.contains("127.0.0.1") || name.contains(":2") || name.contains("@"))
        }
    }

    /** The card is seen only by the phone user: it keeps the full label of an unnamed server. */
    @Test fun theCardKeepsTheFullLabelOfAnUnnamedServer() {
        unnameLaptop2()
        vm.changeAgentMessaging(true)
        runBlocking { frame("Somebody") }
        rule.setContent { CrossServerPanel(vm, "p1") }
        rule.onNodeWithText("Hermes on fixture@127.0.0.1 · 127.0.0.1:2 · w2:p9").performScrollTo().assertIsDisplayed()
        assertTrue(laptop2.writes.isEmpty())
    }

    /** 2.2.0: the frame opens a Yes/No dialog with the whole message; Yes delivers it to Hermes once, nothing typed anywhere. */
    @Test fun theDialogShowsTheWholeMessageAndYesSendsItOnce() {
        vm.changeAgentMessaging(true)
        runBlocking { frame("Hermes on laptop2") }
        rule.setContent { CrossServerPanel(vm, "p1") }
        rule.onNodeWithContentDescription("Approve cross-server message").assertIsDisplayed()
        rule.onNodeWithText("Send this message to another server?").assertIsDisplayed()
        rule.onAllNodesWithText("From: Claude on laptop").assertCountEquals(2)
        rule.onAllNodesWithText("To: Hermes on laptop2").assertCountEquals(2)
        rule.onAllNodesWithText(exact).assertCountEquals(2)
        rule.onNodeWithText("Yes, send").assertIsDisplayed()
        rule.onNodeWithText("No").assertIsDisplayed()
        assertTrue(laptop2.writes.isEmpty())
        rule.onNodeWithText("Yes, send").performClick()
        rule.waitUntil(5_000) { vm.crossServerCardsFor("p1").single().state == AppViewModel.CrossServerState.SENT }
        rule.onNodeWithContentDescription("Approve cross-server message").assertDoesNotExist()
        rule.onNodeWithText(RelayResult.SENT.feedback).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Close").performScrollTo().assertIsDisplayed()
        assertEquals(1, laptop2.writes.size)
        assertTrue(laptop2.writes.single().contains("'agent' 'prompt' 'w2:p9'"))
        assertFalse(laptop2.writes.single().contains(code))
        assertTrue(source.writes.isEmpty())
    }

    @Test fun noOnTheDialogCancelsAndSendsNothing() {
        vm.changeAgentMessaging(true)
        runBlocking { frame("Hermes on laptop2") }
        rule.setContent { CrossServerPanel(vm, "p1") }
        rule.onNodeWithText("No").performClick()
        rule.onNodeWithContentDescription("Approve cross-server message").assertDoesNotExist()
        rule.onNodeWithText("Cancelled. Nothing was sent.").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals(AppViewModel.CrossServerState.CANCELLED, vm.crossServerCardsFor("p1").single().state) }
        assertTrue(laptop2.writes.isEmpty() && source.writes.isEmpty())
    }

    /** Back leaves the proposal on its card; Approve there sends it once. */
    @Test fun approveOnTheCardSendsOnceAfterTheDialogIsPutAside() {
        vm.changeAgentMessaging(true)
        runBlocking { frame("Hermes on laptop2") }
        rule.setContent { CrossServerPanel(vm, "p1") }
        rule.runOnIdle { vm.deferCrossServerDialog(vm.crossServerCardsFor("p1").single()) }
        rule.onNodeWithContentDescription("Approve cross-server message").assertDoesNotExist()
        rule.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Approve").performScrollTo().performClick()
        rule.waitUntil(5_000) { vm.crossServerCardsFor("p1").single().state == AppViewModel.CrossServerState.SENT }
        assertEquals(1, laptop2.writes.size)
        assertTrue(source.writes.isEmpty())
    }

    @Test fun cancelOnTheCardSendsNothing() {
        vm.changeAgentMessaging(true)
        runBlocking { frame("Hermes on laptop2") }
        rule.setContent { CrossServerPanel(vm, "p1") }
        rule.runOnIdle { vm.deferCrossServerDialog(vm.crossServerCardsFor("p1").single()) }
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        rule.onNodeWithText("Cancelled. Nothing was sent.").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertNull(vm.crossServerApprovalOf(vm.crossServerCardsFor("p1").single())) }
        assertTrue(laptop2.writes.isEmpty() && source.writes.isEmpty())
    }

    /**
     * 2.2.0 step 1: in a window about 470 dp tall (a phone with the keyboard up; this is
     * Robolectric's default) a tall card left the message box no height at all: its Send button
     * measured 0 by 0 in 2.1.5. The cards now take a share of the window and the box always fits.
     */
    @Test fun theMessageBoxStillFitsUnderATallCardInAShortWindow() {
        vm.changeAgentMessaging(true)
        val long = (1..14).joinToString("\n") { "Line $it of a longer cross-server message for Hermes." }
        runBlocking { frame("Hermes on laptop2", long) }
        rule.runOnUiThread {
            vm.deferCrossServerDialog(vm.crossServerCardsFor("p1").single())
            // AgentScreen's own loops (the work list every 5 s, the chat log, the pane list and the
            // previews) read over SSH itself, past the fixture's herdr commands, so they dial the
            // fixture host. That failure is a lost link, which rightly clears the cross-server cards;
            // with an .invalid host this only passed while the name lookup was still pending (2.2.7,
            // GitHub CI). The layout under test needs no reads, so the screen opens with the app's
            // polls paused (its in-front flag off); cross-server messaging stays on, so the card stays.
            state(vm, "foreground", false)
        }
        rule.setContent { AgentScreen(vm, "p1", readScreen = { }) }
        rule.onNodeWithContentDescription("Cross-server message card").assertExists()
        val window = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val send = rule.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot
        assertTrue("Send button $send in $window", send.height >= 30f && send.bottom <= window.bottom + 1f)
        rule.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test fun anUnmatchedDestinationOffersTheReachableAgentsToChooseFrom() {
        vm.changeAgentMessaging(true)
        runBlocking { frame("Somebody") }
        rule.setContent { CrossServerPanel(vm, "p1") }
        rule.onNodeWithText("Choose a destination:").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Claude on laptop2 · 127.0.0.1:2 · w2:p5").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Approve cross-server message").assertDoesNotExist()
        rule.onNodeWithText("Hermes on laptop2 · 127.0.0.1:2 · w2:p9").performScrollTo().performClick()
        // The pick shows on the card and opens the Yes/No dialog for it; nothing is sent yet.
        rule.onAllNodesWithText("To: Hermes on laptop2").assertCountEquals(2)
        rule.onNodeWithContentDescription("Approve cross-server message").assertIsDisplayed()
        assertTrue(laptop2.writes.isEmpty())
    }


    private fun unnameLaptop2() {
        val unnamed = laptop2.server.copy(name = "")
        rule.runOnUiThread {
            val watched = runBlocking { laptop2.client.panes() }
            state(vm, "servers", listOf(source.server, unnamed))
            state(field(vm, "fleet").get(vm) as Fleet, "states", mapOf(unnamed.id to FleetState(unnamed, watched, null, 0)))
        }
    }

    private suspend fun frame(to: String, text: String = exact): AppViewModel.CrossServerCard {
        vm.readPane("p1")
        source.frame = fixtureMessageFrame(code, text, to)
        vm.readPane("p1")
        return vm.crossServerCardsFor("p1").single().also { assertEquals(text, it.text) }
    }

    /** Claude on the server the phone has open: the source of the proposal. */
    private class Source : Herdr.Commands {
        val server = Server(id = "laptop", name = "laptop", host = "127.0.0.1", port = 1, user = "fixture", herdrPath = "/fixture/herdr")
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
        val server = Server(id = "laptop2", name = "laptop2", host = "127.0.0.1", port = 2, user = "fixture", herdrPath = "/fixture/herdr")
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
