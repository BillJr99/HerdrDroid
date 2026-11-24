package com.billmongan.herdrdroid

import org.junit.Rule
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
import com.billmongan.herdrdroid.herdr.WritePermit
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.2.0, from Grok's review of the pane menus: a Mode, Thinking or Sessions action goes into the pane
 * only through the guarded send path (a fresh read, an empty input line with no draft, no question),
 * never forced, and a mode switch never stops on Vibe's Auto approve.
 */
@RunWith(AndroidJUnit4::class)
class MenuWriteGuard220Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val server = Server(id = "fixture-m", name = "FixtureM", host = "127.0.0.1", port = 1, user = "fixm", herdrPath = "/fixture/herdr")

    @After fun close() {
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun res(path: String) = checkNotNull(javaClass.getResource(path)).readText()
    private fun pane(agent: String) = Pane("w1:p1", "w1:t1", "w1", agent, Status.IDLE, "", "/fixture", terminalId = "t1")

    @Test fun aDraftInTheBoxStopsAThinkingCommand() {
        val fake = Fake("claude", res("/screens/claude/captured-2.1.329/composer-titled-list-typed.ansi"))
        open(fake, "claude")
        vm.setThinking("w1:p1", "high")
        settle(4)
        assertEquals(emptyList<String>(), fake.writes())
        assertTrue(vm.error.orEmpty(), vm.error.orEmpty().contains("text is typed in its input box"))
    }

    @Test fun aQuestionOnScreenStopsASessionCommand() {
        val fake = Fake("claude", res("/screens/claude/captured-2.1.329/single-question.txt"))
        open(fake, "claude")
        vm.newSession("w1:p1")
        settle(4)
        assertEquals(emptyList<String>(), fake.writes())
        assertTrue(vm.error.orEmpty(), vm.error.orEmpty().contains("not sent"))
    }

    @Test fun anEmptyBoxTakesTheCommandAsAnUnforcedPrompt() {
        val fake = Fake("claude", res("/screens/claude/captured-2.1.329/composer-titled-list-empty.ansi"))
        open(fake, "claude")
        vm.newSession("w1:p1")
        settle(4)
        val w = fake.writes()
        assertEquals(w.toString(), 1, w.size)
        assertTrue(w.single(), "'agent' 'prompt' 'w1:p1' '/clear'" in w.single())
        assertTrue(w.none { "--force" in it || "send-text" in it })
    }

    @Test fun herdrCoreSendsAMenuCommandUnforcedAndKeysAsHcKeys() = runBlocking {
        val fake = Fake("claude", "", core = true)
        val idle = pane("claude")
        fake.client.sendPermitted(idle, "/effort high", fake.connection!!, WritePermit())
        fake.client.sendPermittedKeys("w1:p1", listOf("shift+tab"), fake.connection!!, WritePermit())
        val w = fake.writes()
        assertEquals(w.toString(), 2, w.size)
        assertTrue(w[0], "'send' 'w1:p1' '/effort high'" in w[0] && "--force" !in w[0])
        assertTrue(w[1], "'keys' 'w1:p1' 'shift+tab'" in w[1])
    }

    @Test fun aVibeSwitchThatLandsOnAutoApprovePressesBackToTheStart() {
        val base = res("/screens/vibe/live-2026-11-20-composer/empty.ansi")
        fun footer(mode: String) = base.replace("smart approve", mode)
        // Accept edits, then a press lands on Auto approve (Smart approve off), the next press doesn't
        // redraw it, then ask, then accept edits again.
        val fake = Fake("vibe", footer("ask"))
        fake.afterKeys += listOf(footer("accept edits"))
        open(fake, "vibe")
        vm.switchMode("w1:p1", "accept edits")
        settle(30)
        assertEquals(1, fake.keyPresses())
        assertTrue(vm.notice.orEmpty(), vm.notice.orEmpty().contains("Accept edits"))
        // Now from Accept edits toward a target that isn't offered but is asked for: it lands on Auto approve.
        fake.afterKeys += listOf(footer("auto approve"), footer("auto approve"), footer("ask"), footer("accept edits"))
        vm.error = null
        vm.switchMode("w1:p1", "auto")
        settle(60)
        assertEquals(5, fake.keyPresses())
        assertTrue(vm.error.orEmpty(), vm.error.orEmpty().contains("approves every tool"))
        assertTrue(vm.error.orEmpty(), !vm.error.orEmpty().contains("couldn't switch it back"))
    }

    @Test fun aVibeSwitchStuckOnAutoApproveSaysSoLoudly() {
        val base = res("/screens/vibe/live-2026-11-20-composer/empty.ansi")
        fun footer(mode: String) = base.replace("smart approve", mode)
        val fake = Fake("vibe", footer("accept edits"))
        fake.afterKeys += List(40) { footer("auto approve") }
        open(fake, "vibe")
        vm.switchMode("w1:p1", "auto")
        settle(200)
        assertTrue(vm.error.orEmpty(), vm.error.orEmpty().contains("couldn't switch it back"))
        assertTrue(fake.keyPresses() <= 12)
    }

    private fun open(fake: Fake, agent: String) {
        field(vm, "herdr").set(vm, fake.client)
        state(vm, "servers", listOf(server))
        state(vm, "current", server)
        state(vm, "link", Link.CONNECTED)
        state(vm, "panes", listOf(pane(agent)))
        idle()
    }

    /** Past [n] pauses of 250-300 ms, on Robolectric's paused main-looper clock. */
    private fun settle(n: Int) {
        repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300)); idle() }
    }

    private fun idle() {
        repeat(8) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
    }

    /** One idle agent pane on a fake herdr; every read gives [screen]; each key press moves to the next of [afterKeys]. */
    private class Fake(agent: String, @Volatile var screen: String, core: Boolean = false) : Herdr.Commands {
        val ssh = HerdrSsh("127.0.0.1", 2, "fake", Credentials(null, null), null, { null })
        val client = Herdr(ssh, "/fixture/herdr", if (core) Backend.CORE else Backend.TUI, if (core) "/fixture/hc" else null, this)
        override var connection: Any? = Any()
        private val paneJson = """{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","agent":"$agent","agent_status":"idle","cwd":"/fixture","terminal_id":"t1","scroll":{"offset_from_bottom":0}}"""
        val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
        val afterKeys = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun writes() = commands.filter { "'agent' 'prompt'" in it || "'send-text'" in it || "'send-keys'" in it || "'send'" in it || "'keys'" in it }
        fun keyPresses() = commands.count { "'send-keys'" in it }

        override suspend fun bulk(command: String): ExecResult = answer(command)
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = answer(command)

        private fun lines(command: String) = Regex("""'--lines' '(\d+)'""").find(command)?.groupValues?.get(1)?.toInt() ?: 200

        private fun answer(command: String): ExecResult {
            commands += command
            return when {
                "'pane' 'list'" in command -> ok("""{"result":{"panes":[$paneJson]}}
@@herdrdroid-tabs@@
{"result":{"tabs":[{"tab_id":"w1:t1","label":"agent"}]}}""")
                "'pane' 'get' 'w1:p1'" in command -> ok("""{"result":{"pane":$paneJson}}""")
                "'--source' 'recent'" in command -> ok(screen.lines().takeLast(lines(command)).joinToString("\n"))
                "'--source' 'visible'" in command -> ok(screen)
                "'agent' 'prompt'" in command || "'send-text'" in command || "'send'" in command || "'keys'" in command -> ok("")
                "'send-keys'" in command -> {
                    synchronized(afterKeys) { if (afterKeys.isNotEmpty()) screen = afterKeys.removeAt(0) }
                    ok("")
                }
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
