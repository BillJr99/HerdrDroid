package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.*

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.fleet.Fleet
import com.billmongan.herdrdroid.fleet.FleetState
import com.billmongan.herdrdroid.herdr.*
import com.billmongan.herdrdroid.ssh.*
import com.billmongan.herdrdroid.store.AppPrefs
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.ui.CrossServerNoticeSettings
import kotlinx.coroutines.awaitCancellation
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
 * 2.1.4 (Bill: "Herdrdroid should tell the panes about the instructions and other panes and
 * servers when the cross server feature is enabled"). The phone has minipc open, with Claude in p1,
 * and watches minipc2, with Hermes and a reviewer. Every notice goes through the guarded send paths.
 */
@RunWith(AndroidJUnit4::class)
class CrossServerNoticeDeliveryTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: AppViewModel
    private val minipc = Endpoint("minipc", listOf(Row("p1", "w1:t1", "claude", "claude", "s-claude")))
    private val minipc2 = Endpoint("minipc2", listOf(
        Row("w2:p9", "w2:t6", "hermes", "claude", "s-hermes"),
        Row("w2:p5", "w2:t2", "reviewer", "claude", "s-review"),
    ))
    private lateinit var fleet: Fleet
    private var now = 10_000_000_000L

    // This class renders Compose, so it lives in ui with the suite's other Compose classes. In the
    // root package it ran before GrokRcReviewFixesTest, OpenCodeAlert212Test and the other view
    // model tests there; Compose started that early left every later Compose test unable to go
    // idle (AppNotIdleException from PanePrefetchTest on). The view model is made inside the rule,
    // on the UI thread, as ComposeUiTest makes its own.
    // The loop's wait never sleeps for real here.
    @Before fun open() {
        rule.runOnUiThread {
            vm = AppViewModel(app)
            fleet = Fleet(vm.viewModelScope, { Credentials(null, null) }, { null }, {})
            vm.noticeLoopAllowed = false
            vm.noticeTicker = { awaitCancellation() }
            vm.noticeClock = { now }
            vm.onForeground()
            field(vm, "herdr").set(vm, minipc.client)
            state(vm, "servers", listOf(minipc.server, minipc2.server))
            state(vm, "current", minipc.server)
            state(vm, "panes", minipc.panes())
            vm.screen = Screen.Tabs
            watch()
            field(fleet, "clients").set(fleet, mutableMapOf(minipc2.server.id to (minipc2.ssh to minipc2.client)))
            field(vm, "fleet").set(vm, fleet)
        }
    }

    private fun watch() = state(fleet, "states", mapOf(minipc2.server.id to FleetState(minipc2.server, minipc2.panes(), null, 0)))

    @After fun close() {
        rule.runOnUiThread { vm.viewModelScope.cancel() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tick() = runBlocking { vm.noticeTick() }

    @Test fun enablingTellsEveryAgentOnceWithTheAgentsOnOtherServers() {
        vm.changeAgentMessaging(true)
        tick()
        assertEquals(1, minipc.writes.size)
        assertTrue(minipc.writes[0], minipc.writes[0].contains("Agents on other servers: Claude · hermes on minipc2, Claude · reviewer on minipc2."))
        assertEquals(minipc2.writes.toString(), 2, minipc2.writes.size)
        minipc2.writes.forEach {
            assertTrue(it, it.contains("Agents on other servers: Claude on minipc."))
            assertFalse(it, it.contains("hermes on minipc2"))
            assertFalse(it, it.contains("127.0.0.1") || it.contains(":${minipc.server.port}") || it.contains(":${minipc2.server.port}"))
        }
        tick()
        assertEquals(1, minipc.writes.size)
        assertEquals(2, minipc2.writes.size)
    }

    /** Grok's RC review 4: an unnamed server is "server N" in the notice, with no account, host or port. */
    @Test fun anUnnamedServerIsNamedOnlyByItsPlaceInTheNotice() {
        val unnamed = minipc2.server.copy(name = "")
        assertEquals("fixture@127.0.0.1", unnamed.label)
        state(vm, "servers", listOf(minipc.server, unnamed))
        state(fleet, "states", mapOf(unnamed.id to FleetState(unnamed, minipc2.panes(), null, 0)))
        vm.changeAgentMessaging(true)
        tick()
        assertEquals(1, minipc.writes.size)
        // Only the notice itself: the command around it holds the fixture's herdr path.
        val told = minipc.writes[0].substringAfter("'agent' 'prompt' 'p1' ")
        assertTrue(told, told.contains("Agents on other servers: Claude · hermes on server 2, Claude · reviewer on server 2."))
        assertFalse(told, told.contains("fixture") || told.contains("127.0.0.1") || told.contains(":${minipc2.server.port}") || told.contains("@"))
        // The named server is still named.
        assertEquals(2, minipc2.writes.size)
        minipc2.writes.forEach { assertTrue(it, it.contains("Agents on other servers: Claude on minipc.")) }
    }

    @Test fun enablingRunsTheFirstTickAtOnce() {
        vm.noticeLoopAllowed = true
        vm.changeAgentMessaging(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, minipc.writes.size)
        assertEquals(2, minipc2.writes.size)
    }

    @Test fun switchOffSendsNothing() {
        vm.changeCrossServerNotice(false)
        vm.changeAgentMessaging(true)
        tick()
        vm.tellAgentsNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(minipc.writes.isEmpty() && minipc2.writes.isEmpty())
        // Cross-server messaging off: nothing either, whatever the switch says.
        vm.changeCrossServerNotice(true)
        vm.changeAgentMessaging(false)
        tick()
        assertTrue(minipc.writes.isEmpty() && minipc2.writes.isEmpty())
    }

    @Test fun aReachableSetChangeIsToldButAStatusFlipIsNot() {
        vm.changeAgentMessaging(true)
        tick()
        now += 2 * 3_600_000
        // Status flips: working and back, for every agent on minipc2.
        minipc2.status["w2:p9"] = "working"; watch(); tick()
        minipc2.status["w2:p9"] = "idle"; watch(); tick()
        assertEquals(1, minipc.writes.size)
        assertEquals(2, minipc2.writes.size)
        // A new agent on minipc2: minipc's Claude hears of it; minipc2's agents' list is unchanged.
        minipc2.rows += Row("w2:p7", "w2:t7", "codex", "claude", "s-new")
        watch(); tick()
        assertEquals(2, minipc.writes.size)
        assertTrue(minipc.writes[1].contains("Claude · codex on minipc2"))
        assertEquals(listOf("w2:p9", "w2:p5", "w2:p7"), minipc2.writes.map { it.substringAfter("'prompt' '").substringBefore("'") })
    }

    @Test fun aChangeWithinTheHourWaitsAndTellAgentsNowBypassesOnlyThat() {
        vm.changeAgentMessaging(true)
        tick()
        minipc2.rows += Row("w2:p7", "w2:t7", "codex", "claude", "s-new")
        watch()
        now += 10 * 60_000
        tick()
        assertEquals(1, minipc.writes.size)
        vm.tellAgentsNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, minipc.writes.size)
        // Already told this session about this set: the tap sends nothing more.
        vm.tellAgentsNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, minipc.writes.size)
        assertEquals(3, minipc2.writes.size)
    }

    @Test fun heldWhileWorkingOrWithADialogThenSent() {
        minipc2.status["w2:p9"] = "working"
        minipc2.status["w2:p5"] = "blocked"
        watch()
        vm.changeAgentMessaging(true)
        repeat(8) { tick() }
        assertTrue(minipc2.writes.isEmpty())
        minipc2.status["w2:p9"] = "idle"
        minipc2.status["w2:p5"] = "idle"
        watch(); tick()
        assertEquals(2, minipc2.writes.size)
    }

    private fun shellsScreen(name: String) =
        checkNotNull(javaClass.getResource("/screens/claude/derived-2.1.330-shells/$name")).readText()

    /**
     * 2.2.1 (Grok's review of the Claude exemption): on the open server, a Claude herdr calls idle
     * with background shells is no longer held for that work alone, so a turn its screen shows
     * running ("esc to interrupt" among the footer chips) must still hold the notice. With only
     * shells and an empty box, the notice goes once.
     */
    @Test fun theOpenServersClaudeWithShellsIsHeldWhileItsFooterSaysWorkingThenToldOnce() {
        state(vm, "panes", withBusy(minipc.panes(), mapOf("p1" to 3)))
        minipc.screen["p1"] = shellsScreen("working-3-shells.ansi")
        vm.changeAgentMessaging(true)
        tick()
        assertTrue(minipc.writes.toString(), minipc.writes.isEmpty())
        minipc.screen["p1"] = shellsScreen("idle-3-shells-suggestion.ansi")
        // A refused notice is tried again after the notifier's two-minute gap.
        now += 120_001
        tick()
        assertEquals(1, minipc.writes.size)
        tick()
        assertEquals(1, minipc.writes.size)
    }

    /** The same on a watched server, through the relay: held while the footer says working, then told once. */
    @Test fun aWatchedClaudeWithShellsIsHeldWhileItsFooterSaysWorkingThenToldOnce() {
        state(fleet, "states", mapOf(minipc2.server.id to FleetState(minipc2.server,
            withBusy(minipc2.panes(), mapOf("w2:p9" to 3, "w2:p5" to 0)), null, 0, mapOf("w2:p9" to 3))))
        minipc2.screen["w2:p9"] = shellsScreen("working-3-shells.ansi")
        vm.changeAgentMessaging(true)
        tick()
        assertEquals("only the reviewer is told", 1, minipc2.writes.size)
        assertFalse(minipc2.writes.single().contains("'w2:p9'"))
        minipc2.screen["w2:p9"] = shellsScreen("idle-3-shells.ansi")
        now += 120_001
        tick()
        assertEquals(2, minipc2.writes.size)
        assertTrue(minipc2.writes.last().contains("'w2:p9'"))
        tick()
        assertEquals(2, minipc2.writes.size)
    }

    @Test fun heldForTheOpenChatsDraft() {
        vm.screen = Screen.Agent("p1")
        vm.noteVoiceComposerDraft(minipc.server.id, "p1", true)
        vm.changeAgentMessaging(true)
        tick()
        assertTrue(minipc.writes.isEmpty())
        assertEquals(2, minipc2.writes.size)
        vm.noteVoiceComposerDraft(minipc.server.id, "p1", false)
        tick()
        assertEquals(1, minipc.writes.size)
    }

    @Test fun aDraftAtTheComputerIsRetriedThenGivenUpWithAPaneNotice() {
        minipc2.screen["w2:p9"] = "❯ half typed"
        vm.changeAgentMessaging(true)
        for (i in 1..vm.notifier.maxTries) { tick(); now += 121_000 }
        assertEquals(listOf("w2:p5"), minipc2.writes.map { it.substringAfter("'prompt' '").substringBefore("'") })
        val notice = vm.sendNotices.single { it.serverId == "minipc2" && it.paneId == "w2:p9" }
        assertTrue(notice.text.contains("could not tell this agent"))
        now += 5 * 3_600_000
        tick()
        assertEquals(1, minipc2.writes.size)
    }

    @Test fun agentsOverTheQuotaLimitWaitAndUnknownUsageIsSent() {
        fun reading(p: Double) = QuotaReading(now, listOf(QuotaItem("week", p, null)))
        state(vm, "serverQuotas", mapOf("minipc2" to mapOf("claude" to reading(95.0))))
        vm.changeAgentMessaging(true)
        tick()
        assertTrue(minipc2.writes.isEmpty())
        // minipc's Claude has no reading: it is not skipped.
        assertEquals(1, minipc.writes.size)
        state(vm, "serverQuotas", mapOf("minipc2" to mapOf("claude" to reading(94.0))))
        tick()
        assertEquals(2, minipc2.writes.size)
    }

    @Test fun theThresholdIsASettingThatPersists() {
        assertEquals(95, vm.crossServerNoticeQuota)
        fun reading(p: Double) = QuotaReading(now, listOf(QuotaItem("week", p, null)))
        state(vm, "serverQuotas", mapOf("minipc2" to mapOf("claude" to reading(85.0))))
        vm.changeCrossServerNoticeQuota(80)
        vm.changeAgentMessaging(true)
        tick()
        assertTrue(minipc2.writes.isEmpty())
        vm.changeCrossServerNoticeQuota(100)
        state(vm, "serverQuotas", mapOf("minipc2" to mapOf("claude" to reading(100.0))))
        tick()
        assertEquals(2, minipc2.writes.size)
        vm.changeCrossServerNoticeQuota(80)
        vm.changeCrossServerNotice(false)
        assertEquals(80, AppPrefs(app).crossServerNoticeQuota)
        val restarted = AppViewModel(app)
        assertEquals(80, restarted.crossServerNoticeQuota)
        assertFalse(restarted.crossServerNotice)
        restarted.viewModelScope.cancel()
    }

    @Test fun whatWasSentIsNotSentAgainAfterARestart() {
        vm.changeAgentMessaging(true)
        tick()
        assertEquals(3, minipc.writes.size + minipc2.writes.size)
        now += 5 * 3_600_000
        val restarted = AppViewModel(app)
        restarted.noticeLoopAllowed = false
        restarted.noticeTicker = { awaitCancellation() }
        restarted.noticeClock = { now }
        restarted.onForeground()
        field(restarted, "herdr").set(restarted, minipc.client)
        state(restarted, "servers", listOf(minipc.server, minipc2.server))
        state(restarted, "current", minipc.server)
        state(restarted, "panes", minipc.panes())
        field(restarted, "fleet").set(restarted, fleet)
        runBlocking { restarted.noticeTick() }
        assertEquals(3, minipc.writes.size + minipc2.writes.size)
        restarted.viewModelScope.cancel()
    }

    @Test fun settingsControls() {
        rule.setContent { Column { CrossServerNoticeSettings(vm) } }
        rule.onNodeWithText("Tell agents about cross-server messaging").assertIsNotEnabled()
        rule.onNodeWithText("Tell agents now").assertIsNotEnabled()
        vm.changeAgentMessaging(true)
        rule.waitForIdle()
        rule.onNodeWithText("Tell agents about cross-server messaging").assertIsEnabled().assertIsOn()
        rule.onNodeWithText("Tell agents now").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(3, minipc.writes.size + minipc2.writes.size)
        rule.onNodeWithText("Skip agents at or above 95% of quota").assertExists()
        rule.onNodeWithText("An agent whose usage is unknown is not skipped", substring = true).assertExists()
        rule.onNodeWithContentDescription("Lower quota limit").performClick()
        rule.onNodeWithText("Skip agents at or above 90% of quota").assertExists()
        rule.onNodeWithContentDescription("Raise quota limit").performClick()
        rule.onNodeWithContentDescription("Raise quota limit").performClick()
        rule.onNodeWithText("Skip agents at or above 100% of quota (never skip)").assertExists()
        rule.onNodeWithContentDescription("Raise quota limit").assertIsNotEnabled()
        assertEquals(100, AppPrefs(app).crossServerNoticeQuota)
        rule.onNodeWithText("Tell agents about cross-server messaging").performClick()
        rule.onNodeWithText("Tell agents about cross-server messaging").assertIsOff()
        assertFalse(vm.crossServerNotice)
        rule.onNodeWithText("Tell agents now").assertIsNotEnabled()
    }

    private data class Row(val id: String, val tab: String, val label: String, val agent: String, val session: String)

    /** A herdr server with claude-style panes: lists, `pane get`, screens, and recorded prompts. */
    private class Endpoint(name: String, initial: List<Row>) : Herdr.Commands {
        val server = Server(id = name, name = name, host = "127.0.0.1", port = if (name == "minipc") 1 else 2, user = "fixture", herdrPath = "/fixture/herdr")
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        val client = Herdr(ssh, server.herdrPath, Backend.TUI, null, this)
        override var connection: Any? = Any()
        val rows = initial.toMutableList()
        val status = mutableMapOf<String, String>()
        val screen = mutableMapOf<String, String>()
        val writes = mutableListOf<String>()

        private fun json(r: Row) = """{"pane_id":"${r.id}","workspace_id":"${r.id.substringBefore(':', "w1")}","tab_id":"${r.tab}","agent":"${r.agent}","agent_session":{"agent":"${r.agent}","kind":"id","source":"herdr:${r.agent}","value":"${r.session}"},"agent_status":"${status[r.id] ?: "idle"}","cwd":"/fixture","terminal_id":"term-${r.id}","scroll":{"offset_from_bottom":0}}"""
        private fun tabs() = rows.joinToString(",") { """{"tab_id":"${it.tab}","label":"${it.label}"}""" }
        fun panes(): List<Pane> = runBlocking { client.panes() }

        override suspend fun bulk(command: String): ExecResult =
            if ("'pane' 'list'" in command) ExecResult(0, """{"result":{"panes":[${rows.joinToString(",") { json(it) }}]}}
@@herdrdroid-tabs@@
{"result":{"tabs":[${tabs()}]}}""", "")
            else ExecResult(1, "", "not in this fixture")
        override suspend fun input(command: String, input: ByteArray, expectedConnection: Any): ExecResult =
            ExecResult(0, """{"ok":true,"busy":{${rows.joinToString(",") { "\"${it.id}\":0" }}}}""", "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            val id = rows.firstOrNull { "'${it.id}'" in command }?.id
            return when {
                "'pane' 'get'" in command && id != null ->
                    ExecResult(0, """{"result":{"pane":${json(rows.single { it.id == id })}}}""", "")
                "'agent' 'prompt'" in command -> { writes += command; ExecResult(0, "", "") }
                "'pane' 'read'" in command && id != null -> ExecResult(0, screen[id] ?: "❯ ", "")
                else -> ExecResult(1, "", "not in this fixture: $command")
            }
        }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state(owner: Any, name: String, value: Any?) {
        (field(owner, "$name\$delegate").get(owner) as MutableState<Any?>).value = value
    }
}
