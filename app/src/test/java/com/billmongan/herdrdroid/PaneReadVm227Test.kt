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
    private val request = "⏺ Sure, asking the phone.\n\n" + listOf("HDREAD1 $code", "HDSRV Destination", "HDPANE codex", "HDWHY check codex", "HDEND $code").joinToString("\n") { "  $it" } + "\n"
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
        vm.clearForTest()
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

    private fun now() = vm.paneReads.cardsFor(source.server.id, "p1").single()

    private fun approve(card: PaneReadRequests.Card<AppViewModel.CardTarget>) =
        assertTrue(vm.paneReads.approve(checkNotNull(vm.paneReads.approvalOf(card))))

    /** Moves the main looper's clock on, as the send's 2.5-second retries wait on it. */
    private fun advance(seconds: Long) {
        repeat(seconds.toInt() * 2) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            Thread.sleep(5)
        }
    }

    @Test fun approvalReadsOnceKeepsItOnThePhoneAndSendsOneAttachment() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = card()
        assertTrue("nothing before approval", target.reads.isEmpty() && source.prompts.isEmpty() && source.uploads.isEmpty())
        approve(card)
        settle { now().delivery != null }
        assertEquals(PaneReadState.SAVED, now().state)
        assertEquals(1, target.reads.size)
        val kept = vm.paneReadStore.list().single()
        assertTrue(kept.body.contains("codex screen row two"))
        assertEquals(1, source.uploads.size)
        assertTrue(String(source.uploads.single(), Charsets.UTF_8).contains("codex screen row two"))
        val prompt = source.prompts.single()
        assertTrue(prompt, prompt.contains("Pane read: Destination/codex, 2 lines (approved) /home/fixture/.herdrdroid/inbox/"))
        assertFalse("the capture is never typed", prompt.contains("codex screen row"))
        assertEquals("Sent to Claude with the capture attached.", now().delivery)
        assertTrue("the target was checked fresh before the read", target.gets > 0)
        assertFalse(vm.paneReads.approve(PaneReadRequests.Approval(card.id, code)))
        vm.readPane("p1")
        settle { false }
        assertEquals(1, target.reads.size)
        assertEquals(1, source.prompts.size)
    }

    @Test fun aDraftInTheRequestingPaneHoldsTheAttachmentThenItGoesOnce() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = card()
        source.recent = "Sure, asking the phone.\n$request\n❯ half typed by Bill"
        approve(card)
        settle { now().delivering }
        advance(1)
        assertTrue("never typed over the draft", source.prompts.isEmpty())
        assertEquals(1, vm.paneReadStore.list().size)
        source.recent = "Sure, asking the phone.\n$request\n❯ "
        repeat(10) { if (now().delivery == null) advance(3) }
        assertEquals("Sent to Claude with the capture attached.", now().delivery)
        assertEquals(1, source.prompts.size)
    }

    @Test fun aPaneThatStaysBusyGetsNothingAndKeepsThePhoneCopy() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = card()
        source.recent = "Sure, asking the phone.\n$request\n❯ half typed by Bill"
        approve(card)
        repeat(20) { if (now().delivery == null) advance(3) }
        assertTrue(now().delivery!!, now().delivery!!.startsWith("Not sent to the agent: the pane stayed busy"))
        assertTrue(source.prompts.isEmpty())
        assertEquals(1, vm.paneReadStore.list().size)
    }

    @Test fun turningCrossServerOffWhileTheSendWaitsStopsIt() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = card()
        source.recent = "Sure, asking the phone.\n$request\n❯ half typed by Bill"
        approve(card)
        settle { now().delivering }
        vm.changeAgentMessaging(false)
        source.recent = "Sure, asking the phone.\n$request\n❯ "
        advance(30)
        assertTrue(source.prompts.isEmpty())
        assertEquals(1, vm.paneReadStore.list().size)
    }

    @Test fun withTheSettingOffNothingIsReadAndTheCardSaysWhy() = runBlocking {
        val card = card()
        assertEquals(PaneReadRefusal.OFF, card.refusal)
        assertNull(vm.paneReads.approvalOf(card))
        assertTrue(target.reads.isEmpty() && source.prompts.isEmpty())
    }

    private class Endpoint(name: String, val id: String, val agent: String) : Herdr.Commands {
        val server = Server(id = name, name = name, host = "127.0.0.1", port = 1, user = "fixture", herdrPath = "/fixture/herdr")
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        val client = Herdr(ssh, server.herdrPath, Backend.TUI, null, this)
        override var connection: Any? = Any()
        var recent = "❯ "
        var visible = "❯ "
        val reads = mutableListOf<String>()
        @Volatile var gets = 0
        val prompts = mutableListOf<String>()
        val uploads = mutableListOf<ByteArray>()
        val pane get() = Pane(id, "tab-$id", "w1", agent, Status.IDLE, "", "/fixture", tabLabel = agent, scrollOffset = 0, terminalId = "t-$id")
        override suspend fun bulk(command: String): ExecResult = immediate(command, 30, null)
        override suspend fun input(command: String, input: ByteArray, expectedConnection: Any): ExecResult {
            if (".herdrdroid/inbox" in command) {
                uploads += input
                // 2.2.7: the verified upload's reply: a marker, then the path, size and SHA-256.
                return ok("${com.billmongan.herdrdroid.herdr.UPLOAD_MARKER}\n/home/fixture/.herdrdroid/inbox/20261123-120000-capture.txt\n${input.size}\n${com.billmongan.herdrdroid.herdr.sha256Hex(input)}")
            }
            return ok("""{"ok":true,"busy":{"$id":0}}""")
        }
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = when {
            command.endsWith("'pane' 'get' '$id'") -> { gets++; ok("""{"result":{"pane":{"pane_id":"$id","workspace_id":"w1","tab_id":"tab-$id","agent":"$agent","agent_status":"idle","cwd":"/fixture","terminal_id":"t-$id","scroll":{"offset_from_bottom":0}}}}""") }
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
