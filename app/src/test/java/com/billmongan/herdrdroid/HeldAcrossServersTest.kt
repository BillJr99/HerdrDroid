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
import com.billmongan.herdrdroid.herdr.footerModel
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.1.2, from the 2.1.1 emulator acceptance: a draft at FixtureB's b1 (w1:p1) held a message sent
 * from its chat; opening FixtureA's a1 (also w1:p1, the same title) dropped the message, showed
 * "Not sent to Codex: the computer changed..." on a1's chat, and left a plain bubble on b1 as if it
 * had gone. b1's drawer entry also showed b3's model line.
 */
@RunWith(AndroidJUnit4::class)
class HeldAcrossServersTest {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val a = Server(id = "fixture-a", name = "FixtureA", host = "127.0.0.1", port = 1, user = "fixa", herdrPath = "/fixture/herdr")
    private val b = Server(id = "fixture-b", name = "FixtureB", host = "127.0.0.1", port = 2, user = "fixb", herdrPath = "/fixture/herdr")
    private val drafted = "● Done.\n\n" + "─".repeat(60) + "\n❯ a draft at the computer\n" + "─".repeat(60) + "\n  ? for shortcuts"
    private val empty = "● Done.\n\n" + "─".repeat(60) + "\n❯ \n" + "─".repeat(60) + "\n  ? for shortcuts"

    @After fun close() {
        vm.clearForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- Finding 1: a held send survives opening another server -------------------------------

    @Test fun aHeldSendStaysHeldOnItsServerAndGoesWhenItsDraftIsCleared() {
        val onA = Fake(empty)
        val onB = Fake(drafted)
        open(b, onB)
        hold(7, "HD212_HELD_ON_B")
        startHeldRetry(onB.pane.id)
        wait(2)
        assertTrue(onB.writes().toString(), onB.writes().isEmpty())

        // The drawer opens FixtureA's w1:p1: same id, same title, an empty input line.
        open(a, onA)
        wait(3)
        assertTrue(onA.writes().toString(), onA.writes().isEmpty())
        val row = vm.pendingSends.single()
        assertTrue("still held, not dropped", row.waiting)
        assertEquals(b.id, row.serverId)
        // Nothing about it on FixtureA, and nothing shown there as sent.
        assertNull(vm.error)
        assertNull(vm.sendNoticeFor(onA.pane.id))
        assertTrue(vm.removableSendsFor(onA.pane.id).isEmpty())
        assertNull(vm.lastSent)

        // Back on FixtureB, still held while the draft is there: shown as held, never as sent.
        open(b, onB)
        resumeHeldSends(b.id)
        wait(2)
        assertTrue(onB.writes().toString(), onB.writes().isEmpty())
        assertEquals(listOf("HD212_HELD_ON_B"), vm.removableSendsFor(onB.pane.id).map { it.text })

        // The computer clears b1's draft: the held send goes, once, to b1 only.
        onB.screen = empty
        wait(3)
        assertEquals(onB.writes().toString(), 1, onB.writes().count { "HD212_HELD_ON_B" in it })
        assertTrue(onA.writes().toString(), onA.writes().isEmpty())
        assertFalse(vm.pendingSends.single().waiting)
    }

    // ---- Finding 2: a send's problem shows only in its own pane, on its own server -------------

    @Test fun aFailedHeldSendIsReportedOnlyInItsOwnServersPane() {
        val onB = Fake(empty).apply { failSends = true }
        open(b, onB)
        hold(8, "HD212_FAILS_ON_B")
        startHeldRetry(onB.pane.id)
        wait(3)
        assertTrue(onB.writes().toString(), onB.writes().isNotEmpty())
        assertTrue(vm.pendingSends.single().undelivered)
        assertNull("no global banner", vm.error)
        assertNotNull(vm.sendNoticeFor(onB.pane.id))

        open(a, Fake(empty))
        assertNull(vm.sendNoticeFor(onB.pane.id))

        open(b, onB)
        assertTrue(vm.sendNoticeFor(onB.pane.id).orEmpty().startsWith("Not sent to"))
        vm.dismissSendNotice(onB.pane.id)
        assertNull(vm.sendNoticeFor(onB.pane.id))
    }

    // ---- Finding 3: each card's preview and model come from its own pane's screen --------------

    /** b1 and b2 are Codex stand-ins with no model in the footer; b3's placeholder screen shows GPT-6.2-Sol. */
    private val b1 = "› \n\n  ? for shortcuts · proj1"
    private val b2 = "› \n\n  ? for shortcuts · proj2"
    private val b3 = "› Select argon model\n\n  GPT-6.2-Sol default · ~/agents/codex · Select argon model"

    @Test fun previewsAreSplitPerPaneWhenAReadEndsWithoutANewline() = runBlocking {
        // What `herdr pane read --format ansi` gives: the last row ends with a reset and no newline.
        val out = listOf("w1:p1" to b1, "w1:p2" to b2, "w1:p3" to b3)
            .joinToString("") { (id, screen) -> "@@herdrdroid@@$id\n$screen\u001B[0m" }
        val previews = Previews(out).client.previews(listOf("w1:p1", "w1:p2", "w1:p3"))
        assertEquals(setOf("w1:p1", "w1:p2", "w1:p3"), previews.keys)
        assertNull(footerModel("codex", previews.getValue("w1:p1").tail))
        assertNull(footerModel("codex", previews.getValue("w1:p2").tail))
        assertEquals("GPT-6.2-Sol · default", footerModel("codex", previews.getValue("w1:p3").tail))
        assertFalse(previews.getValue("w1:p1").tail.contains("GPT-6.2-Sol"))
        assertTrue(previews.getValue("w1:p2").tail.contains("proj2"))
    }

    @Test fun previewsStillSplitWhenEachReadEndsWithANewline() = runBlocking {
        val out = listOf("w1:p1" to b1, "w1:p3" to b3).joinToString("") { (id, screen) -> "@@herdrdroid@@$id\n$screen\n" }
        val previews = Previews(out).client.previews(listOf("w1:p1", "w1:p3"))
        assertNull(footerModel("codex", previews.getValue("w1:p1").tail))
        assertEquals("GPT-6.2-Sol · default", footerModel("codex", previews.getValue("w1:p3").tail))
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun hold(id: Long, text: String) = state(vm, "pendingSends", listOf(AppViewModel.PendingSend(id, "w1:p1", text,
        System.currentTimeMillis(), 0, held = "a draft is typed in its input box at the computer", waiting = true, serverId = b.id)))

    private fun startHeldRetry(paneId: String) =
        AppViewModel::class.java.getDeclaredMethod("startHeldRetry", String::class.java).apply { isAccessible = true }.invoke(vm, paneId)

    private fun resumeHeldSends(serverId: String) =
        AppViewModel::class.java.getDeclaredMethod("resumeHeldSends", String::class.java).apply { isAccessible = true }.invoke(vm, serverId)

    private fun open(server: Server, fake: Fake) {
        field(vm, "herdr").set(vm, fake.client)
        state(vm, "servers", listOf(a, b))
        state(vm, "current", server)
        state(vm, "link", Link.CONNECTED)
        state(vm, "panes", listOf(fake.pane))
    }

    /** Lets the held retry run for about [steps] of its 2-second checks. */
    private fun wait(steps: Int) = repeat(steps) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3)); Thread.sleep(5) }

    private class Previews(private val out: String) : Herdr.Commands {
        val client = Herdr(HerdrSsh("127.0.0.1", 3, "fake", Credentials(null, null), null, { null }), "/fixture/herdr", Backend.TUI, null, this)
        override val connection: Any? = Any()
        override suspend fun bulk(command: String): ExecResult = ExecResult(0, out, "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = error("not expected: $command")
    }

    /** One Claude pane w1:p1 on a fake herdr: reads give [screen]; writes are recorded. */
    private class Fake(@Volatile var screen: String) : Herdr.Commands {
        val ssh = HerdrSsh("127.0.0.1", 3, "fake", Credentials(null, null), null, { null })
        val client = Herdr(ssh, "/fixture/herdr", Backend.TUI, null, this)
        override var connection: Any? = Any()
        val pane = Pane("w1:p1", "w1:t1", "w1", "claude", Status.IDLE, "Codex · 1", "/fixture", scrollOffset = 0, terminalId = "t1")
        private val paneJson = """{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","agent":"claude","agent_status":"idle","cwd":"/fixture","terminal_id":"t1","scroll":{"offset_from_bottom":0}}"""
        val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var failSends = false
        fun writes() = commands.filter { "'agent' 'prompt'" in it || "'pane' 'run'" in it || "'send-keys'" in it }

        override suspend fun bulk(command: String): ExecResult = answer(command)
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = answer(command)

        private fun answer(command: String): ExecResult {
            commands += command
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
                "'pane' 'run'" in command ->
                    if (failSends) ExecResult(1, "", """{"error":{"code":"pane_not_found","message":"pane w1:p1 not found"}}""") else ok("")
                "'agent' 'prompt'" in command || "'send-text'" in command || "'send-keys'" in command ->
                    if (failSends) ExecResult(1, "", "send failed") else ok("")
                else -> ExecResult(1, "", "not in the fake: $command")
            }
        }
        private fun ok(out: String) = ExecResult(0, out, "")
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun state(owner: Any, name: String, value: Any?) {
        (field(owner, "$name\$delegate").get(owner) as MutableState<Any?>).value = value
    }
}
