package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * 2.2.0: an OpenCode pane herdr keeps "blocked" after a failed turn (llmproxy had no free model).
 * The app shows it idle with an error card, never a question; Acknowledge clears the card while
 * herdr still says blocked; a message still goes in through the guarded send; Retry sends the
 * phone's own message once, and never the app's notice.
 */
@RunWith(AndroidJUnit4::class)
class OpenCodeErrorVm220Test {
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val server = Server(id = "fixture-oe", name = "FixtureOE", host = "oe.invalid", port = 22, user = "fixoe", herdrPath = "/fixture/herdr")
    private val error = checkNotNull(javaClass.getResource("/screens/opencode/live-2026-11-22-error/error.ansi")).readText()

    @After fun close() {
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun aStaleBlockIsShownIdleAndItsErrorIsACardNotAQuestion() = runBlocking {
        val fake = Fake(error)
        val p = fake.client.panes().single()
        assertEquals(Status.NEEDS_YOU, p.status)
        assertTrue(p.openCodeStaleBlock)
        open(fake, p)
        assertEquals(Status.IDLE, vm.shownStatus(p))
        assertFalse(vm.hasPendingQuestion(p))
        assertFalse(vm.blocksNormalSend(p, error, liveOpenCode = true))
        val notice = checkNotNull(vm.errorNoticeFor(p, error))
        assertEquals("llmproxy: no model available", notice.title)
        // Acknowledge works while herdr still says blocked, and types nothing.
        vm.acknowledgeError(p, notice)
        assertNull(vm.errorNoticeFor(p, error))
        assertTrue(fake.writes().isEmpty())
        assertEquals(Status.NEEDS_YOU, fake.client.panes().single().status)
        // A new error later shows again.
        assertNotNull(vm.errorNoticeFor(p, error.replace("1m 25s", "2m 03s")))
    }

    @Test fun aMessageStillGoesInThroughTheGuardedSend() = runBlocking {
        val fake = Fake(error)
        val p = fake.client.panes().single()
        open(fake, p)
        vm.sendMessage(p, "HD220_HELLO")
        pump(3)
        val typed = fake.writes()
        assertEquals(typed.toString(), 1, typed.count { "HD220_HELLO" in it })
        // herdr's own status still picks the route: a blocked pane is typed into, not prompted.
        assertTrue(typed.none { "'agent' 'prompt'" in it })
    }

    @Test fun retrySendsThePhonesOwnMessageOnceAndNeverTheNotice() = runBlocking {
        val fake = Fake(error)
        val p = fake.client.panes().single()
        open(fake, p)
        val notice = checkNotNull(vm.errorNoticeFor(p, error))
        // Nothing sent from the phone (the message above the error is the app's notice): no Retry.
        assertNull(vm.retryTextFor(p, notice))
        // A message the phone sent elsewhere, or a different one here: no Retry either.
        mark("w1:p9", notice.retryText!!)
        assertNull(vm.retryTextFor(p, notice))
        mark("w1:p1", "something else")
        assertNull(vm.retryTextFor(p, notice))
        // The same message the phone sent here, wrapped differently: Retry, once.
        val sent = notice.retryText!!.replace(". ", ".\n")
        mark("w1:p1", sent)
        assertEquals(sent, vm.retryTextFor(p, notice))
        vm.retryAfterError(p, notice)
        pump(3)
        assertEquals(1, fake.writes().count { "HerdrDroid notice" in it })
        assertNull(vm.retryTextFor(p, notice))
        vm.retryAfterError(p, notice)
        pump(2)
        assertEquals(1, fake.writes().count { "HerdrDroid notice" in it })
    }

    private fun mark(paneId: String, text: String) {
        val cls = Class.forName("com.billmongan.herdrdroid.AppViewModel\$SentMark")
        val ctor = cls.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }
        state("lastSentMark", ctor.newInstance(server.id, paneId, text))
    }

    private fun open(fake: Fake, p: com.billmongan.herdrdroid.herdr.Pane) {
        field("herdr").set(vm, fake.client)
        state("servers", listOf(server))
        state("current", server)
        state("link", Link.CONNECTED)
        state("panes", listOf(p))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pump(seconds: Int) {
        repeat(seconds * 4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)); Thread.sleep(15) }
    }

    private fun field(name: String) = vm.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value
    }

    /** One OpenCode pane that herdr calls blocked, on a fake herdr; the screen stays the same after a send. */
    private class Fake(@Volatile var screen: String) : Herdr.Commands {
        val ssh = HerdrSsh("fake.invalid", 22, "fake", Credentials(null, null), null, { null })
        val client = Herdr(ssh, "/fixture/herdr", Backend.TUI, null, this)
        override var connection: Any? = Any()
        private val paneJson = """{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","agent":"opencode","agent_status":"blocked","screen_detection_skipped":true,"cwd":"/fixture","terminal_id":"t1","scroll":{"offset_from_bottom":0}}"""
        val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun writes() = commands.filter { "'agent' 'prompt'" in it || "'send-text'" in it || "'send-keys'" in it || "'send'" in it }

        override suspend fun bulk(command: String): ExecResult = answer(command)
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = answer(command)

        private fun lines(command: String) = Regex("""'--lines' '(\d+)'""").find(command)?.groupValues?.get(1)?.toInt() ?: 200

        private fun answer(command: String): ExecResult {
            commands += command
            return when {
                "'pane' 'list'" in command -> ok("""{"result":{"panes":[$paneJson]}}
@@herdrdroid-tabs@@
{"result":{"tabs":[{"tab_id":"w1:t1","label":"opencode"}]}}""")
                "@@herdrdroid@@" in command -> ok("@@herdrdroid@@w1:p1\n" + screen.lines().takeLast(lines(command)).joinToString("\n"))
                "'pane' 'get' 'w1:p1'" in command -> ok("""{"result":{"pane":$paneJson}}""")
                "'--source' 'recent'" in command -> ok(screen.lines().takeLast(lines(command)).joinToString("\n"))
                "'--source' 'visible'" in command -> ok(screen)
                "'agent' 'prompt'" in command || "'send-text'" in command || "'send-keys'" in command || "'send'" in command -> ok("")
                else -> ExecResult(1, "", "not in the fake: $command")
            }
        }
        private fun ok(out: String) = ExecResult(0, out, "")
    }
}
