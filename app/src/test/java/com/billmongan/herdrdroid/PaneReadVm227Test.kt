package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.fleet.Fleet
import com.billmongan.herdrdroid.fleet.FleetState
import com.billmongan.herdrdroid.herdr.*
import com.billmongan.herdrdroid.ssh.*
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
 * 2.2.7: a pane read approved on the phone is read once from the other server, kept on the phone,
 * and sent to the requesting agent as one message with the capture attached (the message box's
 * attachment path), through the guarded send: held while a draft is typed there, never pasted.
 */
@RunWith(AndroidJUnit4::class)
class PaneReadVm227Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val source = Endpoint("Source", "p1", "claude")
    private val target = Endpoint("Destination", "p3", "codex")
    private val code = "K7P3QX"
    private val request = "HDREAD1 $code\nHDSRV Destination\nHDPANE codex\nHDWHY check codex\nHDEND $code\n"
    private lateinit var fleet: Fleet

    init {
        vm.changeCrossServerNotice(false)
        vm.onForeground()
        field(vm, "herdr").set(vm, source.client)
        state(vm, "servers", listOf(source.server, target.server))
        state(vm, "current", source.server)
        state(vm, "panes", listOf(source.pane))
        vm.screen = Screen.Agent("p1")
        fleet = Fleet(vm.viewModelScope, { Credentials(null, null) }, { null }, {})
        state(fleet, "states", mapOf(target.server.id to FleetState(target.server, listOf(target.pane), null, 0)))
        field(fleet, "clients").set(fleet, mutableMapOf(target.server.id to (target.ssh to target.client)))
        field(vm, "fleet").set(vm, fleet)
        vm.paneReadStore.clear()
        target.visible = "codex screen row one\ncodex screen row two\n"
    }

    @After fun close() {
        vm.paneReadStore.clear()
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun card(): PaneReadRequests.Card<AppViewModel.CardTarget> {
        source.recent = "Sure, asking the phone.\n$request\n❯ "
        vm.readPane("p1")
        return vm.paneReads.cardsFor(source.server.id, "p1").single()
    }

    private fun settle(until: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(5)
        }
    }

    @Test fun approvalReadsOnceKeepsItOnThePhoneAndSendsOneAttachment() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = card()
        assertTrue("nothing before approval", target.reads.isEmpty() && source.prompts.isEmpty() && source.uploads.isEmpty())
        assertTrue(vm.paneReads.approve(checkNotNull(vm.paneReads.approvalOf(card))))
        settle { source.prompts.isNotEmpty() && vm.paneReadDelivery(vm.paneReads.cardsFor(source.server.id, "p1").single())?.startsWith("Sent") == true }
        val now = vm.paneReads.cardsFor(source.server.id, "p1").single()
        assertEquals(PaneReadState.SAVED, now.state)
        assertEquals(1, target.reads.size)
        // The capture is on the phone and went as an attachment; the message names it and holds none of its text.
        val kept = vm.paneReadStore.list().single()
        assertTrue(kept.body.contains("codex screen row two"))
        assertEquals(1, source.uploads.size)
        assertTrue(String(source.uploads.single(), Charsets.UTF_8).contains("codex screen row two"))
        val prompt = source.prompts.single()
        assertTrue(prompt, prompt.contains("Pane read: Destination/codex, 2 lines (approved) /home/fixture/.herdrdroid/inbox/"))
        assertFalse(prompt.contains("codex screen row"))
        assertEquals("Sent to Claude with the capture attached.", vm.paneReadDelivery(now))
        // Single use: no second read or send.
        assertFalse(vm.paneReads.approve(checkNotNull(PaneReadRequests.Approval(card.id, code))))
        vm.readPane("p1")
        settle { false }
        assertEquals(1, target.reads.size)
        assertEquals(1, source.prompts.size)
    }

    @Test fun aDraftInTheRequestingPaneQueuesTheAttachmentAndTypesNothing() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = card()
        source.recent = "Sure, asking the phone.\n$request\n❯ half typed by Bill"
        assertTrue(vm.paneReads.approve(checkNotNull(vm.paneReads.approvalOf(card))))
        settle { vm.paneReadDelivery(vm.paneReads.cardsFor(source.server.id, "p1").single())?.startsWith("Queued") == true }
        val note = vm.paneReadDelivery(vm.paneReads.cardsFor(source.server.id, "p1").single())
        assertTrue(note, note!!.startsWith("Queued for Claude"))
        assertTrue("never typed over the draft", source.prompts.isEmpty())
        assertEquals(1, vm.paneReadStore.list().size)
    }

    @Test fun withTheSettingOffNothingIsReadAndTheCardSaysWhy() = runBlocking {
        val card = card()
        assertEquals(PaneReadRefusal.OFF, card.refusal)
        assertNull(vm.paneReads.approvalOf(card))
        assertTrue(target.reads.isEmpty() && source.prompts.isEmpty())
    }

    private class Endpoint(name: String, val id: String, val agent: String) : Herdr.Commands {
        val server = Server(id = name, name = name, host = "unused.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        val client = Herdr(ssh, server.herdrPath, Backend.TUI, null, this)
        override var connection: Any? = Any()
        var recent = "❯ "
        var visible = "❯ "
        val reads = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        val uploads = mutableListOf<ByteArray>()
        val pane get() = Pane(id, "tab-$id", "w1", agent, Status.IDLE, "", "/fixture", tabLabel = agent, scrollOffset = 0, terminalId = "t-$id")
        override suspend fun bulk(command: String): ExecResult = immediate(command, 30, null)
        override suspend fun input(command: String, input: ByteArray, expectedConnection: Any): ExecResult {
            if (".herdrdroid/inbox" in command) {
                uploads += input
                return ok("/home/fixture/.herdrdroid/inbox/20261123-120000-capture.txt")
            }
            return ok("""{"ok":true,"busy":{"$id":0}}""")
        }
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = when {
            command.endsWith("'pane' 'get' '$id'") -> ok("""{"result":{"pane":{"pane_id":"$id","workspace_id":"w1","tab_id":"tab-$id","agent":"$agent","agent_status":"idle","cwd":"/fixture","terminal_id":"t-$id","scroll":{"offset_from_bottom":0}}}}""")
            "'--source' 'recent'" in command -> ok(recent)
            "'--source' 'visible'" in command -> { reads += command; ok(visible) }
            "'agent' 'prompt' '$id'" in command -> { prompts += command; ok("") }
            else -> ok("")
        }
        private fun ok(out: String) = ExecResult(0, out, "")
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state(owner: Any, name: String, value: Any?) {
        (field(owner, "$name\$delegate").get(owner) as MutableState<Any?>).value = value
    }
}
