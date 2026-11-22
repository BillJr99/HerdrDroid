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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** 2.1.4: Claude's /btw panel: read into the chat, closed and checked when the app opened it, left alone when you did. */
@RunWith(AndroidJUnit4::class)
class ClaudeBtwVm214Test {
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val server = Server(id = "fixture-c", name = "FixtureC", host = "c.invalid", port = 22, user = "fixc", herdrPath = "/fixture/herdr")
    private fun screen(name: String) = checkNotNull(javaClass.getResource("/screens/claude/captured-2.1.329-btw/$name")).readText()
    private val short = screen("short.txt")
    private val idle = screen("after-esc.txt")
    private val top = screen("long-top.txt")
    private val down = screen("long-down.txt")
    private val pgdn = screen("long-pgdn.txt")

    @After fun close() {
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun btws() = vm.questionsFor("w1:p1").filter { it.btw }

    @Test fun aBtwTheAppSentIsReadIntoTheChatThenClosedAndChecked() {
        val fake = Fake(idle)
        fake.onPrompt = { if (it.startsWith("/btw")) fake.screen = short }
        fake.onEsc = { fake.screen = idle }
        open(fake)
        vm.sendMessage(fake.pane, "/btw what is a side question?")
        pump(6)
        assertEquals(listOf("/btw what is a side question?"), fake.typed)
        assertEquals(1, fake.escapes)
        val q = btws().single()
        assertEquals("/btw what is a side question?", q.parts.single().question)
        assertTrue(q.parts.single().answer.orEmpty().startsWith("A side question is answered from the conversation so far"))
        assertTrue(q.parts.single().answer.orEmpty().contains("\n- It runs while Claude keeps working."))
        assertNull(q.action)
        assertTrue(vm.openedUsage.isEmpty())
        // Not sent anywhere else and not left as a draft or pending message.
        assertTrue(vm.pendingSends.none { it.undelivered || it.waiting })
    }

    @Test fun aLongAnswerIsScrolledToItsEndBeforeTheClose() {
        val fake = Fake(idle)
        var view = 0
        // The last view with its "↓" mark taken off: nothing more below (the hint row keeps its "↑/↓").
        val last = pgdn.lines().joinToString("\n") { if (it.trimEnd().endsWith("↓") && !it.contains("to scroll")) it.trimEnd().dropLast(1) else it }
        val frames = listOf(top, down, last)
        fake.onPrompt = { if (it.startsWith("/btw")) fake.screen = frames[0] }
        fake.onDown = { view = minOf(view + 1, 2); fake.screen = frames[view] }
        fake.onEsc = { fake.screen = idle }
        open(fake)
        vm.sendMessage(fake.pane, "/btw what is a side question?")
        pump(12)
        assertEquals(2, fake.downs)
        assertEquals(1, fake.escapes)
        val answer = btws().single().parts.single().answer.orEmpty()
        assertTrue(answer, answer.contains("Point 1:") && answer.contains("Point 21:"))
    }

    @Test fun aFailedReadStillClosesThePanel() {
        val fake = Fake(idle)
        fake.onPrompt = { if (it.startsWith("/btw")) { fake.screen = short; fake.failSnapshots = 200 } }
        fake.onEsc = { fake.screen = idle }
        open(fake)
        vm.sendMessage(fake.pane, "/btw what is a side question?")
        // The reads fail for a while; then the snapshot answers again and the close goes through.
        pump(6)
        // The close couldn't be checked: nothing was pressed, the record stays and the chat says so.
        assertEquals(0, fake.escapes)
        assertEquals(1, vm.openedUsage.single().failures)
        assertTrue(vm.sendNoticeFor(fake.pane.id).orEmpty(), vm.sendNoticeFor(fake.pane.id).orEmpty().contains("side-question panel"))
        // The next check closes it once the screen can be read again.
        fake.failSnapshots = 0
        AppViewModel::class.java.getDeclaredMethod("retryLeftOpenUsage").apply { isAccessible = true }.invoke(vm)
        pump(4)
        assertEquals(1, fake.escapes)
        assertTrue(vm.openedUsage.isEmpty())
        assertEquals(idle, fake.screen)
        assertEquals(listOf("/btw what is a side question?"), fake.typed)
    }

    @Test fun aPanelOpenedInTheTerminalIsKeptButNeverClosedUntilCloseIsTapped() {
        val fake = Fake(short)
        fake.onEsc = { fake.screen = idle }
        open(fake)
        val overlay = com.billmongan.herdrdroid.herdr.UsageOverlay("occ", short)
        val usage = AppViewModel.OpenUsage(server.id, fake.pane.id, 0L, fake.client, "claude", overlay)
        val note = AppViewModel::class.java.getDeclaredMethod("noteSeenBtw", String::class.java, String::class.java, AppViewModel.OpenUsage::class.java)
            .apply { isAccessible = true }
        note.invoke(vm, server.id, fake.pane.id, usage)
        assertTrue("one read is not steady yet", btws().isEmpty())
        note.invoke(vm, server.id, fake.pane.id, usage)
        assertEquals(1, btws().size)
        pump(5)
        assertEquals("never pressed by itself", 0, fake.escapes)
        assertTrue(vm.openedUsage.isEmpty())
    }

    @Test fun sendsAreRefusedWhileThePanelIsOpen() {
        val fake = Fake(short)
        open(fake)
        assertTrue(vm.blocksNormalSend(fake.pane, short))
        assertFalse(vm.blocksNormalSend(fake.pane, idle))
        vm.sendMessage(fake.pane, "HD214_HELLO")
        pump(2)
        assertTrue(fake.typed.toString(), fake.typed.isEmpty())
        assertTrue(vm.pendingSends.single().held.orEmpty().contains("side-question panel"))
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

    /** One Claude pane on a fake herdr. The guarded usage snapshot is answered as the server script would. */
    private class Fake(@Volatile var screen: String, status: String = "idle") : Herdr.Commands {
        val ssh = HerdrSsh("fake.invalid", 22, "fake", Credentials(null, null), null, { null })
        val client = Herdr(ssh, "/fixture/herdr", Backend.TUI, null, this)
        override var connection: Any? = Any()
        val pane = Pane("w1:p1", "w1:t1", "w1", "claude",
            when (status) { "blocked" -> Status.NEEDS_YOU; "working" -> Status.WORKING; else -> Status.IDLE }, "", "/fixture", scrollOffset = 0, terminalId = "t1")
        private val paneJson = """{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","agent":"claude","agent_status":"$status","cwd":"/fixture","terminal_id":"t1","scroll":{"offset_from_bottom":0}}"""
        val typed = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var escapes = 0
        @Volatile var downs = 0
        @Volatile var failSnapshots = 0
        var onPrompt: (String) -> Unit = {}
        var onEsc: () -> Unit = {}
        var onDown: () -> Unit = {}

        override suspend fun bulk(command: String): ExecResult = answer(command)
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = answer(command)

        private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        @Synchronized private fun answer(command: String): ExecResult {
            if ("boot_id" in command) {
                if (failSnapshots > 0) { failSnapshots--; return ExecResult(1, "", "timed out") }
                val m = Regex("""'w1:p1' 'claude'(?: '([^']*)' '([0-9a-f]{64})'( down)?)?\s*$""").find(command) ?: return ok("")
                if (m.groupValues[2].isEmpty()) return ok(org.json.JSONObject().put("occupant", "occ").put("visible", screen).toString())
                if (m.groupValues[1] != "occ" || m.groupValues[2] != sha(screen)) return ok("")
                if (m.groupValues[3].isNotEmpty()) { downs++; onDown(); return ok("sent") }
                escapes++
                onEsc()
                return ok("sent")
            }
            return when {
                "'pane' 'list'" in command -> ok("""{"result":{"panes":[$paneJson]}}
@@herdrdroid-tabs@@
{"result":{"tabs":[{"tab_id":"w1:t1","label":"claude"}]}}""")
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
