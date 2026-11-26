package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import com.billmongan.herdrdroid.herdr.fixtureMessageFrame
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.1.1, from the 2.1.0 acceptance: a message held for FixtureB's pane w1:p1 ("Codex · 1") showed as a
 * dim bubble in FixtureA's pane w1:p1, with the same id and title. Pane ids are not global, so every
 * per-pane piece of chat state belongs to (server, pane).
 */
@RunWith(AndroidJUnit4::class)
class ServerScopedPaneStateTest {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val a = Server(id = "fixture-a", name = "FixtureA", host = "127.0.0.1", port = 1, user = "fixa", herdrPath = "/fixture/herdr")
    private val b = Server(id = "fixture-b", name = "FixtureB", host = "127.0.0.1", port = 2, user = "fixb", herdrPath = "/fixture/herdr")
    private val codex = Pane("w1:p1", "w1:t1", "w1", "codex", Status.IDLE, "Codex · 1", "/fixture", scrollOffset = 0, terminalId = "t1")

    @After fun close() {
        vm.clearForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun on(server: Server) {
        state(vm, "current", server)
        state(vm, "panes", listOf(codex))
    }

    @Test fun heldQueuedAndUnconfirmedSendsStayWithTheirServer() {
        val now = System.currentTimeMillis()
        state(vm, "pendingSends", listOf(
            AppViewModel.PendingSend(1, codex.id, "HD210_APP_MSG_HELD", now, 0, held = "a draft is typed", waiting = true, serverId = b.id),
            AppViewModel.PendingSend(2, codex.id, "not seen yet", now, 0, undelivered = true, serverId = b.id),
            AppViewModel.PendingSend(3, codex.id, "on its way", now, 0, serverId = b.id),
        ))
        on(a)
        assertTrue(vm.removableSendsFor(codex.id).isEmpty())
        assertTrue(vm.pendingFor(codex.id).isEmpty())
        on(b)
        assertEquals(listOf("HD210_APP_MSG_HELD", "not seen yet"), vm.removableSendsFor(codex.id).map { it.text })
        assertEquals(listOf("on its way"), vm.pendingFor(codex.id).map { it.first })
    }

    @Test fun aSendQueuedForTheLinkAndTheLastSentShowOnlyOnItsServer() {
        on(b)
        vm.sendMessage(codex, "HD210_FOR_B")
        idle()
        assertEquals(b.id, vm.pendingSends.single().serverId)
        assertEquals(codex.id to "HD210_FOR_B", vm.lastSent)
        assertEquals(listOf("HD210_FOR_B"), vm.removableSendsFor(codex.id).map { it.text })
        on(a)
        assertNull(vm.lastSent)
        assertTrue(vm.removableSendsFor(codex.id).isEmpty())
        assertTrue(vm.pendingFor(codex.id).isEmpty())
    }

    @Test fun aComposerPrefillIsTakenOnlyOnItsServer() {
        on(b)
        vm.forward("From the other agent", null, codex)
        on(a)
        assertNull(vm.takePrefill(codex.id))
        on(b)
        assertTrue(vm.takePrefill(codex.id).orEmpty().contains("From the other agent"))
        assertNull(vm.takePrefill(codex.id))
    }

    @Test fun theDraftCardBelongsToItsServer() {
        on(b)
        val client = Herdr(HerdrSsh(b.host, b.port, b.user, Credentials(null, null), null, { null }), b.herdrPath, Backend.TUI)
        val target = AppViewModel.CardTarget(client, Any(), b.id, 0, codex)
        state(vm, "paneDraftShown", AppViewModel.PaneDraftShown(target, "typed on B", actionable = true, clearable = true))
        assertEquals("typed on B", vm.paneDraft?.text)
        on(a)
        assertNull(vm.paneDraft)
    }

    @Test fun inputLineTypingOnOneServerDoesNotHoldTheOther() {
        val note = AppViewModel::class.java.getDeclaredMethod("noteInputLine", String::class.java, String::class.java).apply { isAccessible = true }
        val hold = AppViewModel::class.java.getDeclaredMethod("holdReason", String::class.java, String::class.java).apply { isAccessible = true }
        fun screen(line: String) = "• Ran the tests.\n\n› $line\n\n  GPT-6.1-Sol default · ~/fixture\n  ← for agents · ? for shortcuts"
        on(b)
        note.invoke(vm, codex.id, screen("ty"))
        note.invoke(vm, codex.id, screen("typing"))
        on(a)
        // Same pane id, empty input line, nobody typing at FixtureA: no hold.
        val empty = "• Ran the tests.\n\n› \n\n  GPT-6.1-Sol default · ~/fixture\n  ← for agents · ? for shortcuts"
        assertNull(hold.invoke(vm, codex.id, empty))
        on(b)
        assertEquals("someone was typing in it a moment ago", hold.invoke(vm, codex.id, empty))
    }

    /**
     * 2.1.5: the 2.1.1 "Listen for agent proposal" menu is gone. A frame on the first completed
     * read, once the connection opens, still becomes a card, so nothing waits on a menu.
     */
    @Test fun aFrameOnTheFirstCompletedReadBecomesACard() = runBlocking {
        val source = Endpoint(a)
        vm.onForeground()
        field(vm, "herdr").set(vm, source.client)
        state(vm, "servers", listOf(a))
        on(a)
        state(vm, "panes", listOf(source.pane))
        vm.screen = Screen.Agent(source.pane.id)
        vm.changeAgentMessaging(true)
        source.connection = null
        source.recent = "⏺ " + fixtureMessageFrame("K7P3QX", "hello")
        vm.readPane(source.pane.id)
        assertTrue(vm.crossServerCardsFor(source.pane.id).isEmpty())
        source.connection = Any()
        vm.readPane(source.pane.id)
        assertEquals("K7P3QX", vm.crossServerCardsFor(source.pane.id).single().code)
    }

    private fun idle() {
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
    }

    private class Endpoint(server: Server) : Herdr.Commands {
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        val client = Herdr(ssh, server.herdrPath, Backend.TUI, null, this)
        override var connection: Any? = Any()
        var recent = "Normal source output"
        val pane = Pane("w1:p1", "w1:t1", "w1", "claude", Status.IDLE, "", "/fixture", scrollOffset = 0, terminalId = "t1")
        override suspend fun bulk(command: String): ExecResult = error("Unexpected bulk command: $command")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = when {
            command.contains("'pane' 'get' 'w1:p1'") -> ok("""{"result":{"pane":{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","agent":"claude","agent_status":"idle","cwd":"/fixture","terminal_id":"t1","scroll":{"offset_from_bottom":0}}}}""")
            "'--source' 'recent'" in command -> ok(recent)
            "'--source' 'visible'" in command -> ok("❯ ")
            else -> error("Unexpected fixture command: $command")
        }
        private fun ok(out: String) = ExecResult(0, out, "")
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun state(owner: Any, name: String, value: Any?) {
        (field(owner, "$name\$delegate").get(owner) as MutableState<Any?>).value = value
    }
}
