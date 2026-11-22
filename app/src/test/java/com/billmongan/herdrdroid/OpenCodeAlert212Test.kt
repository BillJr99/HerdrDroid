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
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.herdr.markScreenNeedsYou
import com.billmongan.herdrdroid.herdr.openCodeNeedsYouReason
import com.billmongan.herdrdroid.herdr.screenAskable
import com.billmongan.herdrdroid.herdr.waitUntilStates
import com.billmongan.herdrdroid.notify.AppBus
import com.billmongan.herdrdroid.notify.Notify
import com.billmongan.herdrdroid.notify.NotifyAction
import com.billmongan.herdrdroid.notify.notificationOffersReply
import com.billmongan.herdrdroid.notify.openCodePermissionAlertKeys
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.1.2 (Bill, 2026-11-21: "herdrdroid did not render the question card for OpenCode"). herdr skips
 * screen detection for OpenCode panes and reports one with a permission dialog as done or idle, so
 * no alert came and the drawer and list showed no badge while its chat was closed. The pane list's
 * screen read (the one that already promotes Claude dialogs) now marks it as needing you for display
 * and alerts only: herdr's own status keeps picking the send route, and an alert's answer goes in
 * only after the strict re-read shows the same permission with a visible highlight.
 */
@RunWith(AndroidJUnit4::class)
class OpenCodeAlert212Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val server = Server(id = "fixture-e", name = "FixtureE", host = "e.invalid", port = 22, user = "fixe", herdrPath = "/fixture/herdr")
    private val frame = res("/screens/opencode/live-2026-11-21/permission-shell-2.3.2.txt")
    private val composer = res("/screens/opencode/live-2026-11-20-composer/empty.ansi")

    @After fun close() {
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- status promotion ---------------------------------------------------------------------

    @Test fun aPermissionOnScreenIsNeedsYouWhileHerdrSaysDoneOrIdle() {
        for (agent in listOf("opencode", "opencode-ursinus")) for (herdrSays in listOf(Status.DONE, Status.IDLE)) {
            val p = pane(agent, herdrSays)
            assertEquals(listOf(p), screenAskable(listOf(p)))
            val marked = markScreenNeedsYou(listOf(p), mapOf(p.id to tail(frame))).single()
            assertEquals("$agent $herdrSays", Status.NEEDS_YOU, marked.status)
            assertEquals(herdrSays, marked.screenNeedsYouFrom)
            assertEquals(herdrSays, marked.sendStatus)
        }
        assertEquals("a permission dialog is open", openCodeNeedsYouReason(tail(frame)))
    }

    @Test fun noDialogMeansNoPromotion() {
        val p = pane("opencode", Status.DONE)
        for (screen in listOf(composer, "", "  ● Done. The file is written.\n\n  ┃  Ask anything\n")) {
            val marked = markScreenNeedsYou(listOf(p), mapOf(p.id to tail(screen))).single()
            assertEquals(p, marked)
        }
        assertNull(openCodeNeedsYouReason(tail(composer)))
        // A pane with no screen read is left as herdr said.
        assertEquals(p, markScreenNeedsYou(listOf(p), emptyMap()).single())
    }

    @Test fun onlyDoneAndIdleOpenCodePanesAreRead() {
        val working = pane("opencode", Status.WORKING)
        val blocked = pane("opencode", Status.NEEDS_YOU)
        val codex = pane("codex", Status.DONE)
        assertTrue(screenAskable(listOf(working, blocked, codex)).isEmpty())
        val marked = markScreenNeedsYou(listOf(working, blocked, codex), mapOf(working.id to tail(frame), blocked.id to tail(frame), codex.id to tail(frame)))
        assertEquals(listOf(working, blocked, codex), marked)
        assertNull(marked[1].screenNeedsYouFrom)
    }

    @Test fun claudePanesArePromotedAsBefore() {
        val claude = pane("claude", Status.IDLE)
        val dialog = "  Which file?\n  ❯ 1. a.txt\n    2. b.txt\n\n  Enter to select · ↑/↓ to navigate · Esc to cancel\n"
        val marked = markScreenNeedsYou(listOf(claude), mapOf(claude.id to dialog)).single()
        assertEquals(Status.NEEDS_YOU, marked.status)
        assertNull(marked.screenNeedsYouFrom)
        assertEquals(Status.NEEDS_YOU, marked.sendStatus)
    }

    @Test fun thePaneListMarksItInTheSameRoundTripAsClaude() = runBlocking {
        val fake = Fake(frame, status = "done", withClaude = true)
        val panes = fake.client.panes()
        val oc = panes.single { it.id == "w1:p1" }
        assertEquals(Status.NEEDS_YOU, oc.status)
        assertEquals(Status.DONE, oc.screenNeedsYouFrom)
        // One screen read for both askable panes, at the existing depth.
        val reads = fake.commands.filter { "@@herdrdroid@@" in it }
        assertEquals(1, reads.size)
        assertTrue(reads.single(), "'w1:p1'" in reads.single() && "'w1:p2'" in reads.single() && "'--lines' '80'" in reads.single())
        // Waiting on herdr uses its own status, so a done pane shown as needing you doesn't wake the wait at once.
        assertFalse("done" in waitUntilStates(oc))
        assertTrue("blocked" in waitUntilStates(oc))
    }

    @Test fun aWorkingOpenCodePaneCostsNoExtraRead() = runBlocking {
        val fake = Fake(frame, status = "working")
        assertEquals(Status.WORKING, fake.client.panes().single().status)
        assertTrue(fake.commands.none { "@@herdrdroid@@" in it })
    }

    // ---- send route ---------------------------------------------------------------------------

    @Test fun theSendRouteStaysHerdrsAgentPrompt() = runBlocking {
        val fake = Fake(frame, status = "done")
        val shown = fake.client.panes().single()
        assertEquals(Status.NEEDS_YOU, shown.status)
        fake.client.sendMessage(shown, "HD212E_TEXT")
        assertEquals("herdr agent prompt", fake.client.lastSendRoute)
        val writes = fake.writes()
        assertEquals(1, writes.size)
        assertTrue(writes.single(), "'agent' 'prompt' 'w1:p1' 'HD212E_TEXT'" in writes.single())
        assertTrue(writes.none { "send-text" in it || "--force" in it })
        // A pane herdr itself reports blocked keeps its own (typed) route.
        val herdrBlocked = shown.copy(screenNeedsYouFrom = null)
        fake.client.sendMessage(herdrBlocked, "HD212E_BLOCKED")
        assertEquals("herdr pane send-text + enter", fake.client.lastSendRoute)
    }

    @Test fun herdrCoreKeepsItsUnforcedSend() = runBlocking {
        val fake = Fake(frame, status = "done", core = true)
        val shown = pane("opencode", Status.DONE).let { it.copy(status = Status.NEEDS_YOU, screenNeedsYouFrom = it.status) }
        fake.client.sendMessage(shown, "HD212E_CORE")
        assertEquals("hc send", fake.client.lastSendRoute)
        assertTrue(fake.writes().toString(), fake.writes().single().let { "'send'" in it && "--force" !in it })
    }

    // ---- the alert ----------------------------------------------------------------------------

    @Test fun theAlertOffersEveryButtonWithNoReply() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notify.channels(app)
        val card = checkNotNull(detectPrompt(frame, blocked = true, agent = "opencode"))
        assertTrue(card.openCodePermission)
        assertFalse(card.terminalReview)
        assertFalse(notificationOffersReply(card))
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        Notify.needsYou(app, server.id, "FixtureE", "w1:p1", "OpenCode", card.question, card.choices, "opencode",
            terminalReview = card.terminalReview, reply = notificationOffersReply(card), prompt = card)
        val titles = nm.allNotifications.single().actions.map { it.title.toString() }
        // 2.1.2 (Bill: "The alert should offer whatever options the question does"): Always allow
        // too; Reject is OpenCode's Esc, so it is the alert's Esc.
        assertEquals(listOf("Allow once", "Always allow", "Reject (Esc)"), titles)
        assertFalse(titles.contains("Reply"))
    }

    @Test fun alertKeysComeOnlyFromAVisibleHighlight() {
        fun card(text: String) = checkNotNull(detectPrompt(text, blocked = true, agent = "opencode"))
        fun tap(keys: String, label: String, isKey: Boolean = false) =
            NotifyAction(server.id, "w1:p1", keys, false, "Permission required", label = label, agent = "opencode", isKey = isKey)
        assertEquals(listOf("enter"), openCodePermissionAlertKeys(card(focused(frame, "Allow once")), tap("1", "Allow once")))
        assertEquals(listOf("left", "left", "enter"), openCodePermissionAlertKeys(card(focused(frame, "Reject")), tap("1", "Allow once")))
        // Plain text shows no highlight: Allow once sends nothing.
        assertNull(openCodePermissionAlertKeys(card(frame), tap("1", "Allow once")))
        // Reject and Esc are Esc wherever the highlight is.
        assertEquals(listOf("esc"), openCodePermissionAlertKeys(card(frame), tap("3", "Reject")))
        assertEquals(listOf("esc"), openCodePermissionAlertKeys(card(frame), tap("esc", "", isKey = true)))
        // Always allow (2.1.2) from the highlight too; never a typed reply, never another question's button.
        assertEquals(listOf("right", "enter"), openCodePermissionAlertKeys(card(focused(frame, "Allow once")), tap("2", "Always allow")))
        assertNull(openCodePermissionAlertKeys(card(frame), tap("2", "Always allow")))
        assertNull(openCodePermissionAlertKeys(card(frame), NotifyAction(server.id, "w1:p1", "yes", true, "Permission required", agent = "opencode", isReply = true)))
        assertNull(openCodePermissionAlertKeys(card(focused(frame, "Allow once")),
            NotifyAction(server.id, "w1:p1", "1", false, "Another question", label = "Allow once", agent = "opencode")))
    }

    @Test fun allowOnceFromTheAlertPressesEnterAfterTheStrictRead() {
        val fake = Fake(focused(frame, "Allow once"), status = "done")
        open(fake)
        emit(NotifyAction(server.id, "w1:p1", "1", false, "Permission required", label = "Allow once", agent = "opencode"))
        assertTrue(fake.commands.any { "'--source' 'recent'" in it && "'--lines' '80'" in it && "'--format' 'ansi'" in it })
        assertEquals(listOf("'send-keys' 'w1:p1' 'enter'"), fake.writes().map { it.substringAfter("'pane' ") })
    }

    @Test fun rejectFromTheAlertPressesEsc() {
        val fake = Fake(frame, status = "done")
        open(fake)
        emit(NotifyAction(server.id, "w1:p1", "3", false, "Permission required", label = "Reject", agent = "opencode"))
        assertEquals(listOf("'send-keys' 'w1:p1' 'esc'"), fake.writes().map { it.substringAfter("'pane' ") })
    }

    @Test fun nothingIsSentWithoutAHighlightOrAGoodStrictRead() {
        // Plain text: no highlight, so Allow once is refused (no typed "1").
        Fake(frame, status = "done").let { fake ->
            open(fake)
            emit(NotifyAction(server.id, "w1:p1", "1", false, "Permission required", label = "Allow once", agent = "opencode"))
            assertTrue(fake.writes().toString(), fake.writes().isEmpty())
        }
        // The strict read times out: refused, with no card-recovery fallback read.
        Fake(focused(frame, "Allow once"), status = "done").let { fake ->
            fake.timeoutLines = 80
            open(fake)
            emit(NotifyAction(server.id, "w1:p1", "1", false, "Permission required", label = "Allow once", agent = "opencode"))
            assertTrue(fake.writes().toString(), fake.writes().isEmpty())
            assertTrue(fake.commands.none { "'--lines' '40'" in it })
        }
        // A blank read: refused.
        Fake("\n\n   \n", status = "done").let { fake ->
            open(fake)
            emit(NotifyAction(server.id, "w1:p1", "3", false, "Permission required", label = "Reject", agent = "opencode"))
            assertTrue(fake.writes().toString(), fake.writes().isEmpty())
        }
        // The dialog went away: refused.
        Fake(composer, status = "idle").let { fake ->
            open(fake)
            emit(NotifyAction(server.id, "w1:p1", "esc", false, "Permission required", agent = "opencode", isKey = true))
            assertTrue(fake.writes().toString(), fake.writes().isEmpty())
        }
    }

    @Test fun aTypedAlertReplyIsNeverSentToThePermission() {
        val fake = Fake(focused(frame, "Allow once"), status = "done")
        open(fake)
        emit(NotifyAction(server.id, "w1:p1", "HD212E_REPLY", true, "", agent = "opencode", isReply = true))
        assertTrue(fake.writes().toString(), fake.writes().isEmpty())
    }

    // ---- 2.1.2, Grok's RC review HIGH: the alert answers only its own question -----------------

    /** A button of the alert made from [shown], as the posted alert carries it. */
    private fun button(shown: com.billmongan.herdrdroid.herdr.Prompt, label: String? = null, isKey: Boolean = false): NotifyAction {
        val c = label?.let { l -> shown.choices.single { it.label == l } }
        return NotifyAction(server.id, "w1:p1", c?.keys ?: "esc", false, shown.question, label = c?.label.orEmpty(), agent = "opencode",
            isKey = isKey, options = com.billmongan.herdrdroid.notify.alertOptionsSig(shown.choices),
            identity = com.billmongan.herdrdroid.notify.alertIdentity(shown))
    }

    @Test fun aNewCommandWithTheSameHeadingGetsNoKeysFromTheOldAlert() {
        val shown = checkNotNull(detectPrompt(frame, blocked = true, agent = "opencode"))
        val other = frame.replace("echo hi > ~/scratch/x", "rm -rf ~/scratch/fixture-other")
        for (label in listOf("Allow once", "Always allow", "Reject", null)) {
            val fake = Fake(focused(other, "Allow once"), status = "done")
            open(fake)
            emit(button(shown, label, isKey = label == null))
            assertTrue("$label: ${fake.writes()}", fake.writes().isEmpty())
        }
        // The same command still answers.
        val fake = Fake(focused(frame, "Allow once"), status = "done")
        open(fake)
        emit(button(shown, "Always allow"))
        assertEquals(listOf("'send-keys' 'w1:p1' 'right' 'enter'"), fake.writes().map { it.substringAfter("'pane' ") })
    }

    private val question = res("/screens/opencode/live-2026-11-21/question-2.3.2.txt")

    @Test fun anOpenCodeQuestionAlertSendsTheOptionsDigitAfterTheStrictRead() {
        val shown = checkNotNull(detectPrompt(question, blocked = true, agent = "opencode"))
        val fake = Fake(question, status = "done")
        open(fake)
        emit(button(shown, "Postgres"))
        assertTrue(fake.commands.any { "'--source' 'recent'" in it && "'--lines' '80'" in it && "'--format' 'ansi'" in it })
        assertEquals(listOf("'send-keys' 'w1:p1' '2'"), fake.writes().map { it.substringAfter("'pane' ") })
    }

    @Test fun anOpenCodeQuestionAlertRefusesAnotherQuestionOrFrame() {
        val shown = checkNotNull(detectPrompt(question, blocked = true, agent = "opencode"))
        for (changed in listOf(
            question.replace("A server database", "A hosted database"),
            question.replace("Which database should the demo use?", "Which cache should the demo use?"),
            question.replace("  ┃  ↑↓ select  enter submit  esc dismiss", "  ┃  ⇆ tab  ↑↓ select  enter confirm  esc dismiss"),
            composer,
        )) {
            val fake = Fake(changed, status = "done")
            open(fake)
            emit(button(shown, "Postgres"))
            emit(button(shown, isKey = true))
            assertTrue(fake.writes().toString(), fake.writes().isEmpty())
        }
        // Esc on the same question dismisses it.
        val fake = Fake(question, status = "done")
        open(fake)
        emit(button(shown, isKey = true))
        assertEquals(listOf("'send-keys' 'w1:p1' 'esc'"), fake.writes().map { it.substringAfter("'pane' ") })
    }

    @Test fun anOpenCodeQuestionReplyOpensItsOwnAnswerRowThenTypes() {
        val shown = checkNotNull(detectPrompt(question, blocked = true, agent = "opencode"))
        val reply = button(shown).copy(keys = "MariaDB", isKey = false, isReply = true, pressEnter = true)
        val editing = question.replace("  ┃  ↑↓ select  enter submit  esc dismiss", "  ┃  enter submit  esc close")
        // The row didn't open: no text after the digit.
        Fake(question, status = "done").let { fake ->
            open(fake)
            emit(reply)
            settle()
            assertEquals(listOf("'send-keys' 'w1:p1' '3'"), fake.writes().map { it.substringAfter("'pane' ") })
        }
        // Another question on screen: nothing at all.
        Fake(question.replace("Which database", "Which cache"), status = "done").let { fake ->
            open(fake)
            emit(reply)
            assertTrue(fake.writes().toString(), fake.writes().isEmpty())
        }
        // The row opens: the text goes in after the digit (last: an answered question isn't answered twice for 15 s).
        Fake(question, status = "done").let { fake ->
            fake.afterKeys = editing
            open(fake)
            emit(reply)
            // The text waits for the redraw after the digit (300 ms on the paused main clock).
            settle()
            assertEquals(listOf("'send-keys' 'w1:p1' '3'", "'send-text' 'w1:p1' 'MariaDB'", "'send-keys' 'w1:p1' 'enter'"),
                fake.writes().map { it.substringAfter("'pane' ") })
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun res(path: String) = checkNotNull(javaClass.getResource(path)).readText()
    private fun tail(text: String) = text.lines().takeLast(80).joinToString("\n")
    private fun pane(agent: String, status: Status) = Pane("w1:p1", "w1:t1", "w1", agent, status, "", "/fixture", terminalId = "t1")

    /** OpenCode marks the selected button with a background color only (as in OpenCode232PermissionTest). */
    private fun focused(text: String, focus: String) = listOf("Allow once", "Always allow", "Reject").fold(text) { t, label ->
        t.replace("   $label", "   \u001B[${if (label == focus) 44 else 49}m$label\u001B[0m")
    }

    private fun open(fake: Fake) {
        field(vm, "herdr").set(vm, fake.client)
        state(vm, "servers", listOf(server))
        state(vm, "current", server)
        state(vm, "link", Link.CONNECTED)
        state(vm, "panes", listOf(pane("opencode", Status.NEEDS_YOU).copy(screenNeedsYouFrom = Status.DONE)))
        idle()
    }

    private fun emit(a: NotifyAction) {
        assertTrue(AppBus.emit(a))
        idle()
    }

    /** Past the answer's 300 ms pause, on Robolectric's paused main-looper clock. */
    private fun settle() {
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)); idle() }
    }

    private fun idle() {
        repeat(8) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
    }

    /** One OpenCode pane (and optionally a Claude pane) on a fake herdr; reads give the last N lines of [screen]. */
    private class Fake(@Volatile var screen: String, status: String, withClaude: Boolean = false, core: Boolean = false) : Herdr.Commands {
        val ssh = HerdrSsh("fake.invalid", 22, "fake", Credentials(null, null), null, { null })
        val client = Herdr(ssh, "/fixture/herdr", if (core) Backend.CORE else Backend.TUI, if (core) "/fixture/hc" else null, this)
        override var connection: Any? = Any()
        private val paneJson = """{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","agent":"opencode","agent_status":"$status","screen_detection_skipped":true,"cwd":"/fixture","terminal_id":"t1","scroll":{"offset_from_bottom":0}}"""
        private val claudeJson = """{"pane_id":"w1:p2","workspace_id":"w1","tab_id":"w1:t2","agent":"claude","agent_status":"idle","cwd":"/fixture","terminal_id":"t2","scroll":{"offset_from_bottom":0}}"""
        private val listJson = if (withClaude) "$paneJson,$claudeJson" else paneJson
        val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var timeoutLines: Int? = null
        /** The screen after the first key goes in (2.1.2: OpenCode's own answer row opening). */
        @Volatile var afterKeys: String? = null
        fun writes() = commands.filter { "'agent' 'prompt'" in it || "'send-text'" in it || "'send-keys'" in it || "'send'" in it }

        override suspend fun bulk(command: String): ExecResult = answer(command)
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = answer(command)

        private fun lines(command: String) = Regex("""'--lines' '(\d+)'""").find(command)?.groupValues?.get(1)?.toInt() ?: 200

        private fun answer(command: String): ExecResult {
            commands += command
            return when {
                "'pane' 'list'" in command -> ok("""{"result":{"panes":[$listJson]}}
@@herdrdroid-tabs@@
{"result":{"tabs":[{"tab_id":"w1:t1","label":"opencode"}]}}""")
                "@@herdrdroid@@" in command -> ok(Regex("""@@herdrdroid@@([^']+)""").findAll(command).joinToString("\n") { m ->
                    "@@herdrdroid@@${m.groupValues[1]}\n" + if (m.groupValues[1] == "w1:p1") screen.lines().takeLast(lines(command)).joinToString("\n") else "❯ \n"
                })
                "'pane' 'get' 'w1:p1'" in command -> ok("""{"result":{"pane":$paneJson}}""")
                "'--source' 'recent'" in command -> {
                    if (lines(command) == timeoutLines) return ExecResult(-2, "", "timed out")
                    ok(screen.lines().takeLast(lines(command)).joinToString("\n"))
                }
                "'--source' 'visible'" in command -> ok(screen)
                "'agent' 'prompt'" in command || "'send-text'" in command || "'send-keys'" in command || "'send'" in command -> {
                    if ("'send-keys'" in command) afterKeys?.let { screen = it; afterKeys = null }
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
