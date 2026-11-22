package com.billmongan.herdrdroid

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
 * servers when the cross server feature is enabled"). The phone has laptop open, with Claude in p1,
 * and watches laptop2, with Hermes and a reviewer. Every notice goes through the guarded send paths.
 */
@RunWith(AndroidJUnit4::class)
class CrossServerNoticeDeliveryTest {
    @get:Rule val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: AppViewModel
    private val laptop = Endpoint("laptop", listOf(Row("p1", "w1:t1", "claude", "claude", "s-claude")))
    private val laptop2 = Endpoint("laptop2", listOf(
        Row("w2:p9", "w2:t6", "hermes", "claude", "s-hermes"),
        Row("w2:p5", "w2:t2", "reviewer", "claude", "s-review"),
    ))
    private lateinit var fleet: Fleet
    private var now = 10_000_000_000L

    // Made inside the compose rule, on the UI thread, as ComposeUiTest makes its view models: made
    // in the test's constructor, before the rule's environment existed, the suite's later Compose
    // tests timed out with AppNotIdleException. The loop's wait never sleeps for real here.
    @Before fun open() {
        rule.runOnUiThread {
            vm = AppViewModel(app)
            fleet = Fleet(vm.viewModelScope, { Credentials(null, null) }, { null }, {})
            vm.noticeLoopAllowed = false
            vm.noticeTicker = { awaitCancellation() }
            vm.noticeClock = { now }
            vm.onForeground()
            field(vm, "herdr").set(vm, laptop.client)
            state(vm, "servers", listOf(laptop.server, laptop2.server))
            state(vm, "current", laptop.server)
            state(vm, "panes", laptop.panes())
            vm.screen = Screen.Tabs
            watch()
            field(fleet, "clients").set(fleet, mutableMapOf(laptop2.server.id to (laptop2.ssh to laptop2.client)))
            field(vm, "fleet").set(vm, fleet)
        }
    }

    private fun watch() = state(fleet, "states", mapOf(laptop2.server.id to FleetState(laptop2.server, laptop2.panes(), null, 0)))

    @After fun close() {
        rule.runOnUiThread { vm.viewModelScope.cancel() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tick() = runBlocking { vm.noticeTick() }

    @Test fun enablingTellsEveryAgentOnceWithTheAgentsOnOtherServers() {
        vm.changeAgentMessaging(true)
        tick()
        assertEquals(1, laptop.writes.size)
        assertTrue(laptop.writes[0], laptop.writes[0].contains("Agents on other servers: Claude · hermes on laptop2, Claude · reviewer on laptop2."))
        assertEquals(laptop2.writes.toString(), 2, laptop2.writes.size)
        laptop2.writes.forEach {
            assertTrue(it, it.contains("Agents on other servers: Claude on laptop."))
            assertFalse(it, it.contains("hermes on laptop2"))
            assertFalse(it, it.contains(".invalid") || it.contains(":22"))
        }
        tick()
        assertEquals(1, laptop.writes.size)
        assertEquals(2, laptop2.writes.size)
    }

    /** Grok's RC review 4: an unnamed server is "server N" in the notice, with no account, host or port. */
    @Test fun anUnnamedServerIsNamedOnlyByItsPlaceInTheNotice() {
        val unnamed = laptop2.server.copy(name = "")
        assertEquals("fixture@laptop2.invalid", unnamed.label)
        state(vm, "servers", listOf(laptop.server, unnamed))
        state(fleet, "states", mapOf(unnamed.id to FleetState(unnamed, laptop2.panes(), null, 0)))
        vm.changeAgentMessaging(true)
        tick()
        assertEquals(1, laptop.writes.size)
        val told = laptop.writes[0]
        assertTrue(told, told.contains("Agents on other servers: Claude · hermes on server 2, Claude · reviewer on server 2."))
        assertFalse(told, told.contains("fixture") || told.contains(".invalid") || told.contains(":22") || told.contains("@"))
        // The named server is still named.
        assertEquals(2, laptop2.writes.size)
        laptop2.writes.forEach { assertTrue(it, it.contains("Agents on other servers: Claude on laptop.")) }
    }

    @Test fun enablingRunsTheFirstTickAtOnce() {
        vm.noticeLoopAllowed = true
        vm.changeAgentMessaging(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, laptop.writes.size)
        assertEquals(2, laptop2.writes.size)
    }

    @Test fun switchOffSendsNothing() {
        vm.changeCrossServerNotice(false)
        vm.changeAgentMessaging(true)
        tick()
        vm.tellAgentsNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(laptop.writes.isEmpty() && laptop2.writes.isEmpty())
        // Cross-server messaging off: nothing either, whatever the switch says.
        vm.changeCrossServerNotice(true)
        vm.changeAgentMessaging(false)
        tick()
        assertTrue(laptop.writes.isEmpty() && laptop2.writes.isEmpty())
    }

    @Test fun aReachableSetChangeIsToldButAStatusFlipIsNot() {
        vm.changeAgentMessaging(true)
        tick()
        now += 2 * 3_600_000
        // Status flips: working and back, for every agent on laptop2.
        laptop2.status["w2:p9"] = "working"; watch(); tick()
        laptop2.status["w2:p9"] = "idle"; watch(); tick()
        assertEquals(1, laptop.writes.size)
        assertEquals(2, laptop2.writes.size)
        // A new agent on laptop2: laptop's Claude hears of it; laptop2's agents' list is unchanged.
        laptop2.rows += Row("w2:p7", "w2:t7", "codex", "claude", "s-new")
        watch(); tick()
        assertEquals(2, laptop.writes.size)
        assertTrue(laptop.writes[1].contains("Claude · codex on laptop2"))
        assertEquals(listOf("w2:p9", "w2:p5", "w2:p7"), laptop2.writes.map { it.substringAfter("'prompt' '").substringBefore("'") })
    }

    @Test fun aChangeWithinTheHourWaitsAndTellAgentsNowBypassesOnlyThat() {
        vm.changeAgentMessaging(true)
        tick()
        laptop2.rows += Row("w2:p7", "w2:t7", "codex", "claude", "s-new")
        watch()
        now += 10 * 60_000
        tick()
        assertEquals(1, laptop.writes.size)
        vm.tellAgentsNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, laptop.writes.size)
        // Already told this session about this set: the tap sends nothing more.
        vm.tellAgentsNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, laptop.writes.size)
        assertEquals(3, laptop2.writes.size)
    }

    @Test fun heldWhileWorkingOrWithADialogThenSent() {
        laptop2.status["w2:p9"] = "working"
        laptop2.status["w2:p5"] = "blocked"
        watch()
        vm.changeAgentMessaging(true)
        repeat(8) { tick() }
        assertTrue(laptop2.writes.isEmpty())
        laptop2.status["w2:p9"] = "idle"
        laptop2.status["w2:p5"] = "idle"
        watch(); tick()
        assertEquals(2, laptop2.writes.size)
    }

    @Test fun heldForTheOpenChatsDraft() {
        vm.screen = Screen.Agent("p1")
        vm.noteVoiceComposerDraft(laptop.server.id, "p1", true)
        vm.changeAgentMessaging(true)
        tick()
        assertTrue(laptop.writes.isEmpty())
        assertEquals(2, laptop2.writes.size)
        vm.noteVoiceComposerDraft(laptop.server.id, "p1", false)
        tick()
        assertEquals(1, laptop.writes.size)
    }

    @Test fun aDraftAtTheComputerIsRetriedThenGivenUpWithAPaneNotice() {
        laptop2.screen["w2:p9"] = "❯ half typed"
        vm.changeAgentMessaging(true)
        for (i in 1..vm.notifier.maxTries) { tick(); now += 121_000 }
        assertEquals(listOf("w2:p5"), laptop2.writes.map { it.substringAfter("'prompt' '").substringBefore("'") })
        val notice = vm.sendNotices.single { it.serverId == "laptop2" && it.paneId == "w2:p9" }
        assertTrue(notice.text.contains("could not tell this agent"))
        now += 5 * 3_600_000
        tick()
        assertEquals(1, laptop2.writes.size)
    }

    @Test fun agentsOverTheQuotaLimitWaitAndUnknownUsageIsSent() {
        fun reading(p: Double) = QuotaReading(now, listOf(QuotaItem("week", p, null)))
        state(vm, "serverQuotas", mapOf("laptop2" to mapOf("claude" to reading(95.0))))
        vm.changeAgentMessaging(true)
        tick()
        assertTrue(laptop2.writes.isEmpty())
        // laptop's Claude has no reading: it is not skipped.
        assertEquals(1, laptop.writes.size)
        state(vm, "serverQuotas", mapOf("laptop2" to mapOf("claude" to reading(94.0))))
        tick()
        assertEquals(2, laptop2.writes.size)
    }

    @Test fun theThresholdIsASettingThatPersists() {
        assertEquals(95, vm.crossServerNoticeQuota)
        fun reading(p: Double) = QuotaReading(now, listOf(QuotaItem("week", p, null)))
        state(vm, "serverQuotas", mapOf("laptop2" to mapOf("claude" to reading(85.0))))
        vm.changeCrossServerNoticeQuota(80)
        vm.changeAgentMessaging(true)
        tick()
        assertTrue(laptop2.writes.isEmpty())
        vm.changeCrossServerNoticeQuota(100)
        state(vm, "serverQuotas", mapOf("laptop2" to mapOf("claude" to reading(100.0))))
        tick()
        assertEquals(2, laptop2.writes.size)
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
        assertEquals(3, laptop.writes.size + laptop2.writes.size)
        now += 5 * 3_600_000
        val restarted = AppViewModel(app)
        restarted.noticeLoopAllowed = false
        restarted.noticeTicker = { awaitCancellation() }
        restarted.noticeClock = { now }
        restarted.onForeground()
        field(restarted, "herdr").set(restarted, laptop.client)
        state(restarted, "servers", listOf(laptop.server, laptop2.server))
        state(restarted, "current", laptop.server)
        state(restarted, "panes", laptop.panes())
        field(restarted, "fleet").set(restarted, fleet)
        runBlocking { restarted.noticeTick() }
        assertEquals(3, laptop.writes.size + laptop2.writes.size)
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
        assertEquals(3, laptop.writes.size + laptop2.writes.size)
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
        val server = Server(id = name, name = name, host = "$name.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
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
