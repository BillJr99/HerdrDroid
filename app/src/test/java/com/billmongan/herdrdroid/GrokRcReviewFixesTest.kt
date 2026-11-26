package com.billmongan.herdrdroid

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.notify.AppBus
import com.billmongan.herdrdroid.notify.Notify
import com.billmongan.herdrdroid.notify.NotifyAction
import com.billmongan.herdrdroid.notify.notificationOffersReply
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.1.1, Grok's NO-GO review of the release candidate (44fb407). Claude's sessions list (Claude Code
 * 2.1.329, captured) has its only marker on line 6 of 47, so a 40-line read misses it, and its
 * composer ("describe a task for a new session") starts a new session from whatever is typed.
 */
@RunWith(AndroidJUnit4::class)
class GrokRcReviewFixesTest {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val a = Server(id = "fixture-a", name = "FixtureA", host = "127.0.0.1", port = 1, user = "fixa", herdrPath = "/fixture/herdr")
    private val b = Server(id = "fixture-b", name = "FixtureB", host = "127.0.0.1", port = 2, user = "fixb", herdrPath = "/fixture/herdr")
    private val sessionsList = checkNotNull(javaClass.getResource("/screens/claude/captured-2.1.329/background-awaiting-input.txt")).readText()
    private val emptyClaude = "● Done.\n\n" + "─".repeat(60) + "\n❯ \n" + "─".repeat(60) + "\n  ? for shortcuts"

    @After fun close() {
        vm.clearForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- H2: the checks before typing see the sessions list -------------------------------------

    @Test fun theSessionsListBlocksANormalSendWhateverHerdrSays() {
        val claude = Fake(sessionsList).pane
        for (status in listOf(Status.IDLE, Status.DONE, Status.WORKING, Status.NEEDS_YOU))
            assertTrue("$status", vm.blocksNormalSend(claude.copy(status = status), sessionsList))
        // What the old 40-line read saw: the marker is gone, so the screen alone did not hold.
        val tail40 = sessionsList.lines().takeLast(40).joinToString("\n")
        assertNull(detectPrompt(tail40, false, "claude"))
        assertNotNull(detectPrompt(sessionsList.lines().takeLast(80).joinToString("\n"), false, "claude")?.takeIf { it.claudeBackground })
    }

    @Test fun aChatSendToTheSessionsListIsHeldNotTyped() {
        val fake = Fake(sessionsList)
        open(b, fake)
        vm.sendMessage(fake.pane, "HD211_NOT_A_NEW_SESSION")
        idle()
        assertTrue(fake.writes().toString(), fake.writes().isEmpty())
        assertNotNull(vm.pendingSends.single().held)
    }

    @Test fun aHeldRetryDoesNotTypeIntoTheSessionsList() {
        val fake = Fake(sessionsList)
        open(b, fake)
        state(vm, "pendingSends", listOf(AppViewModel.PendingSend(7, fake.pane.id, "HD211_HELD", System.currentTimeMillis(), 0,
            held = "someone was typing in it a moment ago", waiting = true, serverId = b.id)))
        AppViewModel::class.java.getDeclaredMethod("startHeldRetry", String::class.java).apply { isAccessible = true }.invoke(vm, fake.pane.id)
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3)); Thread.sleep(5) }
        assertTrue(fake.reads > 0)
        assertTrue(fake.writes().toString(), fake.writes().isEmpty())
        assertTrue(vm.pendingSends.single().waiting)
    }

    @Test fun aVoiceMessageIsNotTypedIntoTheSessionsList() = runBlocking {
        val fake = Fake(sessionsList)
        open(b, fake)
        vm.screen = Screen.Agent(fake.pane.id)
        state(vm, "locked", false)
        val target = checkNotNull(vm.voiceTarget())
        val said = vm.sendVoiceMessage(target, "HD211_VOICE", { true })
        assertNotEquals("Message sent.", said)
        assertTrue(fake.writes().toString(), fake.writes().isEmpty())
        // The same path still sends to an ordinary empty Claude box.
        fake.screen = emptyClaude
        assertEquals("Message sent.", vm.sendVoiceMessage(target, "HD211_VOICE", { true }))
    }

    // ---- H1: no typed reply from an alert into the sessions list ------------------------------

    @Test fun theSessionsListAlertHasEscButNoReply() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notify.channels(app)
        val card = checkNotNull(detectPrompt(sessionsList, true, "claude"))
        assertTrue(card.claudeBackground && card.choices.isEmpty())
        assertFalse(notificationOffersReply(card))
        assertTrue(notificationOffersReply(Prompt("What should the file be called?", emptyList())))
        assertTrue(notificationOffersReply(null))
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        Notify.needsYou(app, b.id, "FixtureB", "w1:p1", "Claude", card.question, card.choices, "claude", reply = notificationOffersReply(card))
        assertEquals(listOf("Esc"), nm.allNotifications.single().actions.map { it.title.toString() })
    }

    @Test fun anAlertReplyIsRefusedWhenTheFreshReadIsTheSessionsList() {
        val fake = Fake(sessionsList, status = "blocked")
        open(b, fake)
        idle()
        assertTrue(AppBus.emit(NotifyAction(b.id, fake.pane.id, "HD211_REPLY", true, "", isReply = true)))
        idle()
        assertTrue(fake.reads > 0)
        assertTrue(fake.writes().toString(), fake.writes().isEmpty())
    }

    private fun replyTo(fake: Fake): NotifyAction {
        val p = checkNotNull(detectPrompt(fake.screen, true, "claude"))
        return NotifyAction(b.id, fake.pane.id, "HD211_REPLY", true, p.question, agent = "claude", isReply = true,
            options = com.billmongan.herdrdroid.notify.alertOptionsSig(p.choices),
            identity = com.billmongan.herdrdroid.notify.alertIdentity(p))
    }

    @Test fun anAlertReplyStillGoesToAnOrdinaryClaudeQuestion() {
        val fake = Fake("● Which file should I edit?\n\n" + "─".repeat(60) + "\n❯ \n" + "─".repeat(60) + "\n  ? for shortcuts", status = "blocked")
        open(b, fake)
        idle()
        assertTrue(AppBus.emit(replyTo(fake)))
        idle()
        assertTrue(fake.writes().toString(), fake.writes().any { "HD211_REPLY" in it })
    }

    // ---- Grok re-review H1 (strict notification read) ----------------------------------------

    @Test fun anAlertReplyIsRefusedWhenTheStrictReadTimesOutWhileScrolledBack() {
        // The 80-line read times out (exit -2), the viewport is scrolled back, and card recovery's
        // 40-line read would miss the sessions-list hint on line 6 of 47.
        val fake = Fake(sessionsList, status = "blocked", scroll = 3)
        fake.timeoutLines = 80
        open(b, fake)
        idle()
        assertTrue(AppBus.emit(NotifyAction(b.id, fake.pane.id, "HD211_REPLY", true, "", agent = "claude", isReply = true)))
        // Past card recovery's pause before its 40-line read.
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); Thread.sleep(5) }
        assertTrue(fake.commands.none { "'--source' 'recent'" in it })
        assertTrue(fake.writes().toString(), fake.writes().isEmpty())
    }

    @Test fun aFailedAlertReplyToClaudeIsNotOfferedAgain() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notify.channels(app)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val fake = Fake("● Which file should I edit?\n\n" + "─".repeat(60) + "\n❯ \n" + "─".repeat(60) + "\n  ? for shortcuts", status = "blocked")
        fake.failSends = true
        open(b, fake)
        idle()
        assertTrue(AppBus.emit(replyTo(fake)))
        idle()
        assertTrue(fake.writes().toString(), fake.writes().isNotEmpty())
        val failed = nm.allNotifications.filter { n -> n.extras.getCharSequence("android.text")?.contains("may have been sent") == true }
        assertTrue(nm.allNotifications.map { it.extras.getCharSequence("android.text") }.toString(), failed.isNotEmpty())
        assertTrue(failed.all { n -> (n.actions ?: emptyArray()).none { it.title.toString() == "Reply" } })
    }

    @Test fun modelPickerCannotWriteIntoAnApproval() {
        val fake = Fake("Allow the change? [y/n]", status = "blocked")
        open(b, fake)
        var pickerStarted = false
        vm.runCommand(fake.pane.id, "/model") { pickerStarted = true }
        idle()
        assertFalse(pickerStarted)
        assertTrue(fake.writes().isEmpty())
    }

    // ---- M1: a tap belongs to the server it was made on --------------------------------------

    @Test fun aSendTappedWithNoServerOpenIsDroppedNotRunOnTheNextOne() {
        val fake = Fake(emptyClaude)
        field(vm, "herdr").set(vm, fake.client)
        state(vm, "current", null)
        state(vm, "panes", listOf(fake.pane))
        vm.sendMessage(fake.pane, "HD211_STRAY")
        // The next server connects before the tap's coroutine runs (Grok's ordering).
        state(vm, "current", b)
        idle()
        assertTrue(fake.writes().toString(), fake.writes().isEmpty())
        assertTrue(vm.pendingSends.isEmpty())
        assertNotNull(vm.error)
    }

    @Test fun aQueuedSendFlushedOnAnotherServerIsDroppedAndReported() = runBlocking {
        val fake = Fake(emptyClaude)
        open(a, fake)
        state(vm, "link", Link.RECONNECTING)
        vm.sendMessage(fake.pane, "HD211_FOR_A")
        idle()
        assertTrue(vm.pendingSends.single().awaitingLink)
        state(vm, "current", b)
        state(vm, "link", Link.CONNECTED)
        callSuspend("flushQueue")
        idle()
        assertTrue(fake.writes().toString(), fake.writes().isEmpty())
        val row = vm.pendingSends.single()
        assertFalse(row.awaitingLink)
        assertTrue(row.undelivered)
        assertEquals(a.id, row.serverId)
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun open(server: Server, fake: Fake) {
        field(vm, "herdr").set(vm, fake.client)
        state(vm, "servers", listOf(a, b))
        state(vm, "current", server)
        state(vm, "link", Link.CONNECTED)
        state(vm, "panes", listOf(fake.pane))
    }

    private fun idle() {
        repeat(6) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(3) }
    }

    private suspend fun callSuspend(name: String) {
        val m = AppViewModel::class.java.getDeclaredMethod(name, Continuation::class.java).apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Any?> { c -> m.invoke(vm, c) }
    }

    /** One Claude pane on a fake herdr: reads give the last N lines of [screen]; writes are recorded. */
    private class Fake(@Volatile var screen: String, status: String = "idle", scroll: Int = 0) : Herdr.Commands {
        val ssh = HerdrSsh("127.0.0.1", 3, "fake", Credentials(null, null), null, { null })
        val client = Herdr(ssh, "/fixture/herdr", Backend.TUI, null, this)
        override var connection: Any? = Any()
        val pane = Pane("w1:p1", "w1:t1", "w1", "claude",
            if (status == "blocked") Status.NEEDS_YOU else Status.IDLE, "", "/fixture", scrollOffset = scroll, terminalId = "t1")
        private val paneJson = """{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","agent":"claude","agent_status":"$status","cwd":"/fixture","terminal_id":"t1","scroll":{"offset_from_bottom":$scroll}}"""
        val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var reads = 0
        /** A recent read of exactly this many lines times out (exit -2). */
        @Volatile var timeoutLines: Int? = null
        /** Every typed send fails. */
        @Volatile var failSends = false
        fun writes() = commands.filter { "'agent' 'prompt'" in it || "'send-text'" in it || "'send-keys'" in it }

        override suspend fun bulk(command: String): ExecResult = answer(command)
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = answer(command)

        private fun answer(command: String): ExecResult {
            commands += command
            runAlertFixtureGuard(command, "w1:p1", paneJson, screen, ::answer)?.let { return it }
            return when {
                "'pane' 'list'" in command -> ok("""{"result":{"panes":[$paneJson]}}
@@herdrdroid-tabs@@
{"result":{"tabs":[{"tab_id":"w1:t1","label":"claude"}]}}""")
                "'pane' 'get' 'w1:p1'" in command -> ok("""{"result":{"pane":$paneJson}}""")
                "'--source' 'recent'" in command -> {
                    reads++
                    val n = Regex("""'--lines' '(\d+)'""").find(command)?.groupValues?.get(1)?.toInt() ?: 200
                    if (n == timeoutLines) return ExecResult(-2, "", "timed out")
                    ok(screen.lines().takeLast(n).joinToString("\n"))
                }
                "'--source' 'visible'" in command -> { reads++; ok(screen) }
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
