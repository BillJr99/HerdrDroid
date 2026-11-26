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
import com.billmongan.herdrdroid.herdr.SendNowFixtures
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.herdr.inputLineOf
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** 2.2.7: Send now on a queued message: Ctrl+B first where Claude shows its hint, else Force Send or stay queued. */
@RunWith(AndroidJUnit4::class)
class SendNowVm227Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()

    private companion object {
        fun res(path: String) = checkNotNull(SendNowVm227Test::class.java.getResource(path)).readText().replace("\r", "")
        val working = res("/screens/claude/derived-2.1.330-shells/working-3-shells.ansi")
        val withHint = SendNowFixtures.withHint(working)
        val codexBusy = res("/screens/codex/live-2026-11-26-busy/empty.ansi")
        val codexDraft = res("/screens/codex/live-2026-11-26-busy/draft.ansi")
    }

    private fun fixture(agent: String, frame: String, test: Fixture.() -> Unit) {
        val f = Fixture(agent, frame)
        try { f.test() } finally {
            f.vm.clearForTest()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class Fixture(val agent: String, var frame: String) : Herdr.Commands {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        private val server = Server(id = "fixture", name = "Fixture", host = "127.0.0.1", port = 1, user = "fixture", herdrPath = "/fixture/herdr")
        private val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        override var connection: Any? = Any()
        /** Every write, in order: "keys:ctrl+b", "prompt:<text>", "text:<text>", "keys:enter". */
        val writes = mutableListOf<String>()
        /** The screen after Ctrl+B (null: unchanged). */
        var afterCtrlB: String? = null
        var failSends = false
        var dropAtCtrlB = false
        var statusNow = Status.WORKING
        var terminal = "t1"
        /**
         * 2.2.8 (Codex's re-review 2): the terminal the computer reports for `pane get` and for the
         * occupant check, when it differs from the app's list (null: the same as [terminal]).
         */
        var remoteTerminal: String? = null
        /** The terminal only the occupant check on the computer sees (null: as `pane get`). */
        var checkTerminal: String? = null
        /** 2.2.8: the exit of an agent prompt write (it is still recorded): a write that fails after it began. */
        var promptExit = 0
        /** Occupant checks the computer ran, and how many refused. */
        var occupantChecks = 0
        var occupantRefused = 0
        /** 2.2.8: runs as a screen read is answered (once), for a change while the send reads the pane. */
        var duringRead: (() -> Unit)? = null
        var duringCodexCheck: (() -> Unit)? = null

        init {
            field("herdr").set(vm, Herdr(ssh, server.herdrPath, Backend.TUI, null, this))
            state("current", server)
            state("link", Link.CONNECTED)
            status(Status.WORKING)
            field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
            field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
            vm.screen = Screen.Agent("p1")
            vm.backgroundCheckMs = 10
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun status(s: Status) {
            statusNow = s
            state("panes", listOf(Pane("p1", "tab", "w1", agent, s, "", "/fixture", terminalId = terminal, scrollOffset = 0)))
        }

        /** The chat's latest read of the pane, as the screen Send now first decides from. */
        fun shown() {
            state("paneText", frame)
            state("paneTextPane", "p1")
        }

        fun queue(text: String): AppViewModel.QueuedPrompt {
            vm.enqueue("p1", text)
            return vm.queued.single { it.text == text }
        }

        fun drain(ms: Long = 50) = repeat(20) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
            Thread.sleep(2)
        }

        fun drainUntil(done: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                if (done()) return
                Thread.sleep(2)
            } while (System.nanoTime() < deadline)
            fail("did not finish; writes=$writes queued=${vm.queued}")
        }

        override suspend fun bulk(command: String): ExecResult = ok("""{"result":{"panes":[$pane]}}
            |@@herdrdroid-tabs@@
            |{"result":{"tabs":[]}}
        """.trimMargin())

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            // 2.2.8: the occupant check before a bound write, run as the computer would, then each write it guards.
            Regex("""(?s)'p1' '([0-9a-f]{64})' '-'.*\) && (.*)$""").find(command)?.takeIf { "herdrdroid-check" in command }?.let { m ->
                occupantChecks++
                val now = com.billmongan.herdrdroid.herdr.cardOccupantDigest(org.json.JSONObject(paneOn(checkTerminal ?: remoteTerminal ?: terminal)))
                if (now != m.groupValues[1]) { occupantRefused++; return ExecResult(com.billmongan.herdrdroid.herdr.OCCUPANT_CHANGED_EXIT, com.billmongan.herdrdroid.herdr.OCCUPANT_REFUSED_MARK + "\n", "") }
                var out = ""
                var last = ok("")
                for (part in m.groupValues[2].split(" && ")) {
                    if (part.startsWith("echo ")) { out += part.removePrefix("echo ") + "\n"; continue }
                    last = direct(part); if (last.exit != 0) break
                }
                return last.copy(out = out + last.out)
            }
            if ("'pane' 'run' 'p1'" in command) {
                duringCodexCheck?.invoke()
                val exact = com.billmongan.herdrdroid.herdr.codexSteeringGuard(frame)
                val who = com.billmongan.herdrdroid.herdr.cardOccupantDigest(
                    org.json.JSONObject(paneOn(checkTerminal ?: remoteTerminal ?: terminal)),
                )
                if ("'$who' '$exact'" !in command) return ExecResult(
                    com.billmongan.herdrdroid.herdr.CARD_CHANGED_EXIT,
                    com.billmongan.herdrdroid.herdr.CARD_REFUSED_MARK + "\n", "",
                )
                writes += "submit:" + command.substringAfter("'pane' 'run' 'p1' ").removeSurrounding("'")
                return ok(com.billmongan.herdrdroid.herdr.CARD_PASSED_MARK + "\n")
            }
            return direct(command)
        }

        private fun direct(command: String): ExecResult = when {
            command.endsWith("'pane' 'get' 'p1'") -> ok("""{"result":{"pane":${paneOn(remoteTerminal ?: terminal)}}}""")
            command.contains("'pane' 'read' 'p1'") -> { duringRead?.also { duringRead = null }?.invoke(); ok(frame) }
            command.contains("'agent' 'prompt' 'p1' ") -> {
                if (failSends) ExecResult(1, "", "fixture refused") else {
                    writes += "prompt:" + command.substringAfter("'p1' ").removeSurrounding("'")
                    if (promptExit != 0) ExecResult(promptExit, "", "fixture write lost") else ok("")
                }
            }
            command.contains("'pane' 'send-text' 'p1' ") -> {
                if (failSends) ExecResult(1, "", "fixture refused") else {
                    writes += "text:" + command.substringAfter("'p1' ").removeSurrounding("'")
                    ok("")
                }
            }
            command.contains("'pane' 'send-keys' 'p1' ") -> {
                val keys = command.substringAfter("'p1' ").replace("'", "")
                writes += "keys:$keys"
                if (keys == "ctrl+b" && dropAtCtrlB) throw com.billmongan.herdrdroid.ssh.LinkDown(java.io.IOException("reset at ctrl+b"))
                if (keys == "ctrl+b") afterCtrlB?.let { frame = it }
                ok("")
            }
            else -> ok("")
        }

        private val pane get() = paneOn(terminal)
        private fun paneOn(t: String) = """{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"$agent","agent_status":"${when (statusNow) {
            Status.IDLE -> "idle"; Status.DONE -> "done"; Status.WORKING -> "working"; else -> "blocked"
        }}","cwd":"/fixture","terminal_id":"$t","scroll":{"offset_from_bottom":0}}"""
        private fun ok(out: String) = ExecResult(0, out, "")
        private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }
    }

    @Test fun codexRealReadKeepsVerifiedStylingForTheBusySendMenu() = fixture("codex", codexBusy) {
        kotlinx.coroutines.runBlocking { vm.readPane("p1") }
        assertEquals("p1", vm.paneTextPane)
        assertTrue(com.billmongan.herdrdroid.herdr.codexSteeringReady(vm.paneText))
        vm.sendQueuedNow(queue("Switch to the small example."))
        assertNotNull(vm.sendNowConfirm)
        assertTrue(writes.isEmpty())
    }

    @Test fun codexTickBetweenAppReadAndHostCheckStillSendsOnce() = fixture("codex", codexBusy) {
        shown()
        vm.sendQueuedNow(queue("Switch to the small example."))
        duringCodexCheck = { frame = frame.replace("0s", "1s") }
        vm.confirmSendNow()
        drainUntil { vm.queued.isEmpty() }
        assertEquals(listOf("submit:Switch to the small example."), writes)
    }

    @Test fun codexBusySendConfirmsAndUsesOneNativeSubmit() = fixture("codex", codexBusy) {
        shown()
        vm.sendQueuedNow(queue("Switch to the small example."))
        assertTrue(writes.isEmpty())
        assertNotNull(vm.sendNowConfirm)
        vm.confirmSendNow()
        drainUntil { vm.queued.isEmpty() }
        assertEquals(listOf("submit:Switch to the small example."), writes)
    }

    @Test fun codexDraftAppearingAtTheHostCheckLeavesTheQueueUntouched() = fixture("codex", codexBusy) {
        shown()
        vm.sendQueuedNow(queue("Switch to the small example."))
        assertNotNull(vm.sendNowConfirm)
        duringCodexCheck = { frame = codexDraft }
        vm.confirmSendNow()
        drainUntil { vm.queued.single().note != null }
        assertTrue(writes.isEmpty())
        assertFalse(vm.queued.single().mayHaveSent)
        assertTrue(vm.queued.single().note.orEmpty().contains("Still queued"))
    }

    /**
     * 2.2.8 (Codex's review 1): a message sent while a question card is open waits, bound to the pane's
     * occupant. Once the question is answered it goes, once, to that occupant.
     */
    @Test fun aMessageQueuedBehindACardGoesOnceAfterTheCard() = fixture("claude", working.replace("esc to interrupt", "")) {
        status(Status.NEEDS_YOU)
        shown()
        vm.enqueue("p1", "after the question", behindQuestion = true, displayedPane = vm.pane("p1"), displayedServer = vm.current?.id)
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4)); Thread.sleep(5) }
        assertTrue("held while the question is open", writes.isEmpty())
        status(Status.IDLE)
        drainUntil { writes.isNotEmpty() }
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4)); Thread.sleep(5) }
        assertEquals("delivered once", listOf("prompt:after the question"), writes)
        assertTrue(vm.queued.isEmpty())
    }

    @Test fun staleComposerTapDoesNotQueueForAReplacementOccupant() = fixture("claude", working) {
        val displayed = vm.pane("p1")
        val serverId = vm.current?.id
        terminal = "replacement"
        status(Status.NEEDS_YOU)
        assertFalse(vm.enqueue("p1", "for the first session", behindQuestion = true,
            displayedPane = displayed, displayedServer = serverId))
        assertFalse(vm.enqueue("p1", "unattributed", behindQuestion = true))
        assertTrue(vm.queued.isEmpty())
        assertTrue(writes.isEmpty())
    }

    /** The same, with another session in the pane by then: nothing goes to it, and the text is back in the box. */
    @Test fun aMessageQueuedBehindACardNeverGoesToAReplacement() = fixture("claude", working.replace("esc to interrupt", "")) {
        status(Status.NEEDS_YOU)
        shown()
        vm.enqueue("p1", "for the first session only", behindQuestion = true, displayedPane = vm.pane("p1"), displayedServer = vm.current?.id)
        assertNotNull(vm.queued.single().occupant)
        terminal = "replacement"
        status(Status.IDLE)
        var noted = false
        repeat(12) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); Thread.sleep(5)
            if (vm.notice == QUEUED_OCCUPANT_CHANGED) noted = true
        }
        assertTrue("nothing sent to the replacement", writes.isEmpty())
        assertTrue(vm.queued.isEmpty())
        assertEquals("for the first session only", vm.composerDraft("p1"))
        assertTrue("the note says so", noted)
        // Send now on a row bound to the old occupant sends nothing either.
        terminal = "t1"
        status(Status.NEEDS_YOU)
        vm.setComposerDraft("p1", "")
        vm.enqueue("p1", "second", behindQuestion = true, displayedPane = vm.pane("p1"), displayedServer = vm.current?.id)
        terminal = "other"
        status(Status.IDLE)
        vm.sendQueuedNow(vm.queued.single())
        drain()
        assertTrue(writes.isEmpty())
        assertEquals("second", vm.composerDraft("p1"))
        // A message queued with no question open is not bound (as before 2.2.8).
        vm.enqueue("p1", "unbound")
        assertNull(vm.queued.single { it.text == "unbound" }.occupant)
    }

    /**
     * 2.2.8 (Codex's re-review): the occupant is checked again at the write itself. Another session takes
     * the pane while the send reads its screen: nothing is typed, and the text is back in the box.
     */
    @Test fun aBoundMessageIsCheckedAgainRightBeforeTheWrite() = fixture("claude", working.replace("esc to interrupt", "")) {
        status(Status.IDLE)
        shown()
        vm.enqueue("p1", "only for the first session", behindQuestion = true, displayedPane = vm.pane("p1"), displayedServer = vm.current?.id)
        duringRead = { terminal = "replacement"; status(Status.IDLE) }
        vm.sendQueuedImmediately(vm.queued.single())
        var noted = false
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); Thread.sleep(2)
            if (vm.notice == QUEUED_OCCUPANT_CHANGED) noted = true
        }
        assertTrue("nothing typed to the replacement", writes.isEmpty())
        assertTrue(vm.queued.isEmpty())
        assertTrue(vm.pendingSends.isEmpty())
        assertEquals("only for the first session", vm.composerDraft("p1"))
        assertTrue(noted)
    }

    /**
     * 2.2.8 (Codex's re-review 2): the computer's occupant changes while the app's pane list stays as it
     * was. The send reads the occupant on its own connection: nothing is typed, the text is back in the box.
     */
    @Test fun aRemoteOccupantChangeTheListHasNotSeenStopsABoundSend() = fixture("claude", working.replace("esc to interrupt", "")) {
        status(Status.IDLE)
        shown()
        vm.enqueue("p1", "only for the first session", behindQuestion = true, displayedPane = vm.pane("p1"), displayedServer = vm.current?.id)
        remoteTerminal = "replacement"
        vm.sendQueuedImmediately(vm.queued.single())
        var noted = false
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); Thread.sleep(2)
            if (vm.notice == QUEUED_OCCUPANT_CHANGED) noted = true
        }
        assertEquals("the app's list never saw it", "t1", vm.pane("p1")?.terminalId)
        assertTrue("nothing typed to the replacement", writes.isEmpty())
        assertTrue(vm.queued.isEmpty())
        assertTrue(vm.pendingSends.isEmpty())
        assertEquals("only for the first session", vm.composerDraft("p1"))
        assertTrue(noted)
    }

    /** The same change, seen only by the check on the computer in the write's own command: refused there. */
    @Test fun theComputerRefusesABoundWriteToAnotherOccupant() = fixture("claude", working.replace("esc to interrupt", "")) {
        status(Status.IDLE)
        shown()
        vm.enqueue("p1", "only for the first session", behindQuestion = true, displayedPane = vm.pane("p1"), displayedServer = vm.current?.id)
        checkTerminal = "replacement"
        vm.sendQueuedImmediately(vm.queued.single())
        var noted = false
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); Thread.sleep(2)
            if (vm.notice == QUEUED_OCCUPANT_CHANGED) noted = true
        }
        assertEquals(1, occupantRefused)
        assertTrue("nothing typed to the replacement", writes.isEmpty())
        assertTrue(vm.queued.isEmpty())
        assertTrue(vm.pendingSends.isEmpty())
        assertEquals("only for the first session", vm.composerDraft("p1"))
        assertTrue(noted)
    }

    /** With the occupant unchanged, the bound write goes once, behind the check on the computer. */
    @Test fun aBoundWriteGoesBehindTheComputersCheck() = fixture("claude", working.replace("esc to interrupt", "")) {
        status(Status.IDLE)
        shown()
        vm.enqueue("p1", "for this session", behindQuestion = true, displayedPane = vm.pane("p1"), displayedServer = vm.current?.id)
        vm.sendQueuedImmediately(vm.queued.single())
        drainUntil { writes.isNotEmpty() }
        assertEquals(listOf("prompt:for this session"), writes)
        assertEquals(1, occupantChecks)
        assertEquals(0, occupantRefused)
    }

    /**
     * 2.2.8 (Codex's re-review 3): the write itself fails with 5 after the check passed. That is not a
     * refusal: the message may have gone in, so it is kept as maybe sent and never put back as unsent.
     */
    @Test fun aWriteThatFailsAfterTheCheckIsMaybeSentNotRestored() = fixture("claude", working.replace("esc to interrupt", "")) {
        status(Status.IDLE)
        shown()
        promptExit = com.billmongan.herdrdroid.herdr.OCCUPANT_CHANGED_EXIT
        // Send now: the queued row stays, marked as maybe sent.
        vm.enqueue("p1", "maybe it went", behindQuestion = true, displayedPane = vm.pane("p1"), displayedServer = vm.current?.id)
        vm.sendQueuedNow(vm.queued.single())
        drainUntil { vm.queued.singleOrNull()?.mayHaveSent == true }
        assertEquals(listOf("prompt:maybe it went"), writes)
        assertTrue(vm.queued.single().note.orEmpty().startsWith("It may have reached the agent"))
        assertEquals("not put back as unsent", "", vm.composerDraft("p1"))
        assertTrue(vm.notice != QUEUED_OCCUPANT_CHANGED)
        // The idle loop's route (Send immediately): the pending row shows it as maybe sent.
        vm.cancelQueued(vm.queued.single())
        writes.clear()
        vm.enqueue("p1", "maybe again", behindQuestion = true, displayedPane = vm.pane("p1"), displayedServer = vm.current?.id)
        vm.sendQueuedImmediately(vm.queued.single())
        drainUntil { vm.pendingSends.any { it.held == MAY_HAVE_SENT } }
        assertEquals(listOf("prompt:maybe again"), writes)
        assertTrue(vm.pendingSends.single().undelivered)
        assertEquals("", vm.composerDraft("p1"))
        assertTrue(vm.queued.isEmpty())
    }

    /** The check's own refusal (its line, then 5) is still told apart: nothing typed, back in the box. */
    @Test fun theChecksRefusalIsStillToldApart() = fixture("claude", working.replace("esc to interrupt", "")) {
        status(Status.IDLE)
        shown()
        checkTerminal = "replacement"
        vm.enqueue("p1", "not for the replacement", behindQuestion = true, displayedPane = vm.pane("p1"), displayedServer = vm.current?.id)
        vm.sendQueuedNow(vm.queued.single())
        drainUntil { vm.composerDraft("p1") == "not for the replacement" }
        assertTrue(writes.isEmpty())
        assertTrue(vm.queued.isEmpty())
    }

    @Test fun theFixtureScreensAreCleanWorkingClaudeScreens() {
        for (s in listOf(working, withHint)) {
            assertNull(detectPrompt(s, false, "claude"))
            assertEquals("", inputLineOf(s, "claude"))
        }
    }

    @Test fun claudeWithTheBackgroundHintGetsCtrlBThenTheMessageOnce() = fixture("claude", withHint) {
        afterCtrlB = working
        shown()
        val q = queue("look at the logs")
        vm.sendQueuedNow(q)
        drainUntil { vm.queued.isEmpty() }
        drain()
        assertEquals(listOf("keys:ctrl+b", "prompt:look at the logs"), writes)
        assertNull(vm.sendNowConfirm)
        // Nothing more goes later: the idle loop never sends it again.
        status(Status.IDLE)
        drain(400)
        assertEquals(1, writes.count { it.contains("look at the logs") })
    }

    @Test fun ctrlBThatDoesNotTakeSendsNothingAndKeepsItQueued() = fixture("claude", withHint) {
        afterCtrlB = null // the hint stays: the task did not move
        shown()
        val q = queue("after the build")
        vm.sendQueuedNow(q)
        drainUntil { vm.queued.single().note?.startsWith("Not sent") == true }
        assertEquals(listOf("keys:ctrl+b"), writes)
        assertFalse(vm.queued.single().sending)
    }

    @Test fun claudeWithoutTheHintAsksForceSendFirst() = fixture("claude", working) {
        shown()
        val q = queue("next step")
        vm.sendQueuedNow(q)
        drain()
        assertEquals(q.id, vm.sendNowConfirm?.id)
        assertTrue("nothing typed before Send anyway", writes.isEmpty())
        vm.cancelSendNow()
        drain()
        assertNull(vm.sendNowConfirm)
        assertEquals(1, vm.queued.size)
        assertTrue(writes.isEmpty())

        vm.sendQueuedNow(vm.queued.single())
        drain()
        vm.confirmSendNow()
        drainUntil { vm.queued.isEmpty() }
        assertEquals(listOf("prompt:next step"), writes)
    }

    @Test fun anAgentThatTakesNoInputWhileWorkingKeepsItQueuedAndSaysWhy() = fixture("codex", "• Working (12s • esc to interrupt)\n\n› ") {
        shown()
        val q = queue("when you can")
        vm.sendQueuedNow(q)
        drain()
        assertTrue(writes.isEmpty())
        assertNull(vm.sendNowConfirm)
        val left = vm.queued.single()
        assertTrue(left.note.orEmpty(), left.note.orEmpty().contains("stays queued"))
        assertFalse(left.sending)
    }

    @Test fun aDeliveredSendLeavesTheQueueAndAFailedOneStaysMarked() = fixture("claude", working.replace("esc to interrupt", "")) {
        status(Status.IDLE)
        shown()
        failSends = true
        val q = queue("first try")
        vm.sendQueuedNow(q)
        drainUntil { vm.queued.single().note?.startsWith("Not sent") == true }
        val kept = vm.queued.single()
        assertFalse(kept.sending)
        assertFalse("nothing reached the computer", kept.mayHaveSent)
        assertTrue(writes.isEmpty())
        assertTrue("no second row for the same message", vm.removableSendsFor("p1").isEmpty())

        failSends = false
        vm.sendQueuedNow(kept)
        drainUntil { vm.queued.isEmpty() }
        assertEquals(listOf("prompt:first try"), writes)
    }

    /** A draft typed at the computer, in plain text (the fixture's own "❯" row is grey, which reads as a placeholder). */
    private fun drafted(screen: String) = com.billmongan.herdrdroid.herdr.stripAnsi(screen).lines()
        .joinToString("\n") { if (it.trimStart().startsWith("❯")) "❯ half typed" else it }

    @Test fun aDraftOrAQuestionStopsSendNow() = fixture("claude", drafted(withHint)) {
        assertEquals("half typed", inputLineOf(frame, "claude"))
        afterCtrlB = drafted(working)
        shown()
        val q = queue("not over a draft")
        vm.sendQueuedNow(q)
        drain()
        drainUntil { vm.queued.single().note?.startsWith("Not sent") == true }
        assertTrue("no Ctrl+B and no text over a draft", writes.isEmpty())
    }

    /** Pre-review M1: Remove during the check after Ctrl+B means nothing is typed. */
    @Test fun removedDuringTheCtrlBCheckIsNeverSent() = fixture("claude", withHint) {
        afterCtrlB = working
        vm.backgroundCheckMs = 2_000
        shown()
        val q = queue("removed while checking")
        vm.sendQueuedNow(q)
        drainUntil { writes.contains("keys:ctrl+b") }
        vm.cancelQueued(q)
        drain(400)
        assertEquals(listOf("keys:ctrl+b"), writes)
        assertTrue(vm.queued.isEmpty())
    }

    /** Pre-review M2: a link drop at Ctrl+B is never replayed; the row stays queued, not sending. */
    @Test fun aLinkDropAtCtrlBIsNotReplayed() = fixture("claude", withHint) {
        dropAtCtrlB = true
        shown()
        val q = queue("after the drop")
        vm.sendQueuedNow(q)
        drainUntil { vm.queued.single().note?.startsWith("Not sent") == true }
        drain(400)
        val left = vm.queued.single()
        assertFalse(left.inFlight())
        assertTrue("never typed", writes.none { it.startsWith("prompt:") || it.startsWith("text:") })
    }

    /** Pre-review L1: a row whose attempt was dropped without a word is free again after a while. */
    @Test fun aStuckSendingRowIsFreeAgain() {
        val stuck = AppViewModel.QueuedPrompt(1L, "s", "p1", "x", sending = true, sendingSince = 1_000L)
        assertTrue(stuck.inFlight(now = 1_000L + 60_000L))
        assertFalse(stuck.inFlight(now = 1_000L + AppViewModel.QueuedPrompt.SENDING_STALE_MS))
    }

    /** Pre-review L3: a row that may have gone in asks before it is sent again. */
    @Test fun aRowThatMayHaveGoneInAsksFirst() = fixture("claude", working.replace("esc to interrupt", "")) {
        status(Status.IDLE)
        shown()
        val q = queue("maybe sent")
        state("queued", vm.queued.map { it.copy(mayHaveSent = true) })
        vm.sendQueuedNow(vm.queued.single())
        drain()
        assertEquals(q.id, vm.resendConfirm?.id)
        assertTrue(writes.isEmpty())
        vm.cancelResend()
        vm.sendQueuedImmediately(vm.queued.single())
        drain()
        assertTrue(writes.isEmpty())
        vm.confirmResend()
        drainUntil { vm.queued.isEmpty() }
        assertEquals(listOf("prompt:maybe sent"), writes)
    }
}
