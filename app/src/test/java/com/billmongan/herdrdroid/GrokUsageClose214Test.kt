package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
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
import java.security.MessageDigest
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.1.4: a usage dialog HerdrDroid opens in Grok is closed and checked closed; it opens only on an
 * idle agent with an empty input box; and nothing the app sends is typed into it while it is open.
 */
@RunWith(AndroidJUnit4::class)
class GrokUsageClose214Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val server = Server(id = "fixture-g", name = "FixtureG", host = "g.invalid", port = 22, user = "fixg", herdrPath = "/fixture/herdr")
    private val dialog = checkNotNull(javaClass.getResource("/screens/grok/reconstructed-1.0.92/usage-dialog.txt")).readText()
    private val idle = checkNotNull(javaClass.getResource("/screens/grok/live-2026-11-21/idle-compacted.txt")).readText()
    private val draft = idle.replace("│ ❯" + " ".repeat(126) + "│", "│ ❯ half-typed draft" + " ".repeat(108) + "│")

    @After fun close() {
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun theFixtureDraftReallyHasADraft() {
        assertNotEquals(idle, draft)
        assertEquals("half-typed draft", com.billmongan.herdrdroid.herdr.grokInputText(draft))
    }

    // ---- sends and answers refuse while the dialog is open ------------------------------------

    @Test fun theDialogBlocksANormalSendWhateverHerdrSays() {
        val fake = Fake(dialog)
        for (status in listOf(Status.IDLE, Status.DONE, Status.WORKING))
            assertTrue("$status", vm.blocksNormalSend(fake.pane.copy(status = status), dialog))
        assertFalse(vm.blocksNormalSend(fake.pane, idle))
    }

    @Test fun aChatSendIsHeldNotTypedIntoTheDialog() {
        val fake = Fake(dialog)
        open(fake)
        vm.sendMessage(fake.pane, "HD214_HELLO")
        pump(2)
        assertTrue(fake.typed.toString(), fake.typed.isEmpty())
        assertTrue(fake.escapes == 0)
        val row = vm.pendingSends.single()
        assertTrue(row.held.orEmpty(), row.held.orEmpty().contains("usage screen or side-question panel is open"))
    }

    @Test fun anAlertReplyIsRefusedWhileTheDialogIsOpen() = alertReplyRefused("idle")

    @Test fun anAlertReplyIsRefusedWhileTheDialogIsOpenAndHerdrSaysBlocked() = alertReplyRefused("blocked")

    private fun alertReplyRefused(status: String) {
        val fake = Fake(dialog, status = status)
        open(fake)
        pump(1)
        assertTrue(com.billmongan.herdrdroid.notify.AppBus.emit(
            com.billmongan.herdrdroid.notify.NotifyAction(server.id, fake.pane.id, "HD214_REPLY", true, "", agent = "grok", isReply = true)))
        pump(3)
        assertTrue(fake.typed.toString(), fake.typed.none { "HD214_REPLY" in it })
        assertEquals(0, fake.escapes)
    }

    // ---- the dialog opens only on an idle pane with an empty box -------------------------------

    @Test fun usageIsNotOpenedOverADraft() {
        val fake = Fake(draft)
        open(fake)
        vm.sendMessage(fake.pane, "/usage")
        pump(2)
        assertTrue(fake.typed.toString(), fake.typed.isEmpty())
        val row = vm.pendingSends.single()
        assertTrue(row.undelivered && !row.waiting)
        assertTrue(vm.sendNoticeFor(fake.pane.id).orEmpty(), vm.sendNoticeFor(fake.pane.id).orEmpty().contains("opens its usage screen"))
        assertTrue(vm.openedUsage.isEmpty())
    }

    @Test fun usageIsNotOpenedWhileTheAgentWorks() {
        val fake = Fake(idle, status = "working")
        open(fake)
        vm.sendMessage(fake.pane, "/usage")
        pump(2)
        assertTrue(fake.typed.toString(), fake.typed.isEmpty())
        assertTrue(vm.pendingSends.single().held.orEmpty().contains("isn't idle"))
    }

    @Test fun usageIsNotOpenedWhileAnotherSendToThatPaneIsUnderWay() {
        val fake = Fake(idle)
        open(fake)
        state("pendingSends", listOf(AppViewModel.PendingSend(7, fake.pane.id, "HD214_EARLIER", System.currentTimeMillis(), 0,
            held = "someone was typing in it a moment ago", waiting = true, serverId = server.id)))
        vm.sendMessage(fake.pane, "/usage")
        pump(1)
        assertTrue(fake.typed.toString(), fake.typed.none { "/usage" in it })
        assertTrue(vm.pendingSends.first { it.text == "/usage" }.held.orEmpty().contains("earlier message"))
    }

    // ---- closed and checked closed -------------------------------------------------------------

    @Test fun usageOpenedByTheAppIsClosedAndCheckedWhenNobodyIsLookingAtIt() {
        val fake = Fake(idle)
        fake.onPrompt = { if (it.contains("/usage")) fake.screen = dialog }
        fake.onEsc = { fake.screen = idle }
        open(fake)
        vm.sendMessage(fake.pane, "/usage")
        pump(6)
        assertEquals(listOf("/usage"), fake.typed)
        assertEquals(1, fake.escapes)
        assertEquals(idle, fake.screen)
        assertTrue(vm.openedUsage.isEmpty())
        assertNull(vm.sendNoticeFor(fake.pane.id))
    }

    @Test fun aSecondEscWhenGrokIgnoresTheFirst() {
        val fake = Fake(idle)
        var escs = 0
        fake.onPrompt = { if (it.contains("/usage")) fake.screen = dialog }
        fake.onEsc = { if (++escs == 2) fake.screen = idle }
        open(fake)
        vm.sendMessage(fake.pane, "/usage")
        pump(8)
        assertEquals(2, fake.escapes)
        assertEquals(listOf("/usage"), fake.typed)
        assertTrue(vm.openedUsage.isEmpty())
    }

    @Test fun anUnconfirmedCloseIsShownAndTheNextSendWaitsWithoutTypingIntoIt() {
        val fake = Fake(idle)
        fake.onPrompt = { if (it.contains("/usage")) fake.screen = dialog }
        open(fake)
        vm.sendMessage(fake.pane, "/usage")
        pump(10)
        // Three Esc at the most, nothing else typed.
        assertEquals(3, fake.escapes)
        assertEquals(listOf("/usage"), fake.typed)
        assertEquals(1, vm.openedUsage.single().failures)
        assertTrue(vm.sendNoticeFor(fake.pane.id).orEmpty(), vm.sendNoticeFor(fake.pane.id).orEmpty().contains("couldn't confirm"))
        vm.sendMessage(fake.pane, "HD214_AFTER")
        pump(3)
        assertEquals(listOf("/usage"), fake.typed)
        assertTrue(vm.pendingSends.first { it.text == "HD214_AFTER" }.waiting)
        // Once it closes (Esc at the computer), the next check clears it and the held send goes.
        fake.screen = idle
        pump(8)
        assertTrue(vm.openedUsage.isEmpty())
        assertEquals(listOf("/usage", "HD214_AFTER"), fake.typed)
    }

    @Test fun aDialogLeftOpenBeforeTheAppWasKilledIsClosedOnTheNextCheck() {
        val fake = Fake(dialog)
        fake.onEsc = { fake.screen = idle }
        open(fake)
        // What the app saved just before it typed /usage last time.
        AppViewModel::class.java.getDeclaredMethod("saveOpenedUsage", List::class.java).apply { isAccessible = true }
            .invoke(vm, listOf(AppViewModel.OpenedUsage(server.id, fake.pane.id, "grok", 1L)))
        AppViewModel::class.java.getDeclaredMethod("retryLeftOpenUsage").apply { isAccessible = true }.invoke(vm)
        pump(4)
        assertEquals(1, fake.escapes)
        assertTrue(fake.typed.isEmpty())
        assertTrue(vm.openedUsage.isEmpty())
    }

    // ---- harness -------------------------------------------------------------------------------

    private fun open(fake: Fake) {
        field("herdr").set(vm, fake.client)
        state("servers", listOf(server))
        state("current", server)
        state("link", Link.CONNECTED)
        state("panes", listOf(fake.pane))
    }

    private fun pump(seconds: Int) {
        repeat(seconds * 4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)); Thread.sleep(15) }
    }

    private fun field(name: String) = vm.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value
    }

    /** One Grok pane on a fake herdr. The guarded usage snapshot is answered as the server script would. */
    private class Fake(@Volatile var screen: String, status: String = "idle") : Herdr.Commands {
        val ssh = HerdrSsh("fake.invalid", 22, "fake", Credentials(null, null), null, { null })
        val client = Herdr(ssh, "/fixture/herdr", Backend.TUI, null, this)
        override var connection: Any? = Any()
        val pane = Pane("w1:p1", "w1:t1", "w1", "grok",
            when (status) { "blocked" -> Status.NEEDS_YOU; "working" -> Status.WORKING; else -> Status.IDLE }, "", "/fixture", scrollOffset = 0, terminalId = "t1")
        private val paneJson = """{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","agent":"grok","agent_status":"$status","cwd":"/fixture","terminal_id":"t1","scroll":{"offset_from_bottom":0}}"""
        val typed = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var escapes = 0
        var onPrompt: (String) -> Unit = {}
        var onEsc: () -> Unit = {}

        override suspend fun bulk(command: String): ExecResult = answer(command)
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = answer(command)

        private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        @Synchronized private fun answer(command: String): ExecResult {
            if ("boot_id" in command) {
                val m = Regex("""'w1:p1' 'grok'(?: '([^']*)' '([0-9a-f]{64})')?\s*$""").find(command) ?: return ok("")
                if (m.groupValues[2].isEmpty()) return ok(org.json.JSONObject().put("occupant", "occ").put("visible", screen).toString())
                if (m.groupValues[1] != "occ" || m.groupValues[2] != sha(screen)) return ok("")
                escapes++
                onEsc()
                return ok("sent")
            }
            return when {
                "'pane' 'list'" in command -> ok("""{"result":{"panes":[$paneJson]}}
@@herdrdroid-tabs@@
{"result":{"tabs":[{"tab_id":"w1:t1","label":"grok"}]}}""")
                "'pane' 'get' 'w1:p1'" in command -> ok("""{"result":{"pane":$paneJson}}""")
                "'--source' 'recent'" in command -> {
                    val n = Regex("""'--lines' '(\d+)'""").find(command)?.groupValues?.get(1)?.toInt() ?: 200
                    ok(screen.lines().takeLast(n).joinToString("\n"))
                }
                "'--source' 'visible'" in command -> ok(screen)
                "'agent' 'prompt'" in command || "'send-text'" in command -> {
                    val text = Regex("""'(?:prompt|send-text)' 'w1:p1' '(.*)'$""").find(command)?.groupValues?.get(1) ?: command
                    typed += text
                    onPrompt(text)
                    ok("")
                }
                "'send-keys'" in command -> { typed += "keys: $command"; ok("") }
                else -> ExecResult(1, "", "not in the fake: $command")
            }
        }
        private fun ok(out: String) = ExecResult(0, out, "")
    }
}
