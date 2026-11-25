package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.BACKGROUND_REFUSED_EXIT
import com.billmongan.herdrdroid.herdr.BACKGROUND_REFUSED_MARK
import com.billmongan.herdrdroid.herdr.BackgroundStep
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Backgrounder
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.backgroundCheckPasses
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * 2.2.8 (Muse's review): the view model's Ctrl+B paths, through a fake computer that runs the occupant
 * check and the screen check ([com.billmongan.herdrdroid.herdr.BACKGROUND_CHECK], as
 * [backgroundCheckPasses]) on its own screen at the moment of the key. No key goes when the screen,
 * the occupant, the server or the link changes at any step, and never a blind Ctrl+B.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundVm228Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()

    private companion object {
        fun res(path: String) = checkNotNull(BackgroundVm228Test::class.java.getResource(path)).readText().replace("\r", "")
        val fg = res("/screens/claude/live-2026-11-25-probe/claude-fg1.ansi")
        val moved = res("/screens/claude/live-2026-11-25-probe/claude-after-ctrlb.ansi")
        val dialog = fg.trimEnd() + "\n Do you want to proceed?\n ❯ 1. Yes\n   2. No\n\n Esc to cancel · Tab to amend"
        val grokRunning = res("/screens/grok/live-2026-11-25-probe/grok-running.ansi")
        val grokMoved = res("/screens/grok/live-2026-11-25-probe/grok-after-ctrlb.ansi")
    }

    private fun fixture(agent: String, frame: String, test: Fixture.() -> Unit) {
        val f = Fixture(agent, frame)
        try { f.test() } finally {
            f.vm.viewModelScope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class Fixture(val agent: String, var frame: String) : Herdr.Commands {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        private val server = Server(id = "fixture", name = "Fixture", host = "127.0.0.1", port = 1, user = "fixture", herdrPath = "/fixture/herdr")
        private val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        override var connection: Any? = Any()
        /** Every key the computer typed. */
        val keys = mutableListOf<String>()
        /** The screen after Ctrl+B (null: unchanged). */
        var afterCtrlB: String? = null
        /** Runs when the computer's own check reads the screen, before it decides (once). */
        var atCheck: (() -> Unit)? = null
        var terminal = "t1"
        /** The terminal the computer reports (null: [terminal]). */
        var remoteTerminal: String? = null
        var checks = 0
        var reads = 0
        var pushReads = 0
        val prompts = mutableListOf<String>()
        var afterRead: (() -> Unit)? = null
        var afterKey: (() -> Unit)? = null
        var beforeNthRead: ((Int) -> Unit)? = null
        var statusNow = Status.WORKING
        /** Runs once, after the first `pane get` the computer answers (the app binding the occupant). */
        var afterFirstGet: (() -> Unit)? = null

        init {
            field("herdr").set(vm, Herdr(ssh, server.herdrPath, Backend.TUI, null, this))
            state("current", server)
            state("link", Link.CONNECTED)
            status(Status.WORKING)
            field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
            field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
            vm.screen = Screen.Agent("p1")
            vm.pushLimits = Backgrounder.Limits(maxPresses = 3, maxReads = 10, pollMs = 10)
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun status(s: Status) {
            statusNow = s
            state("panes", listOf(com.billmongan.herdrdroid.herdr.Pane("p1", "tab", "w1", agent, s, "", "/fixture", terminalId = terminal)))
        }

        fun replaceServer() = state("current", server.copy(id = "other-fixture"))

        fun startPending() {
            val row = pending("finish the sample")
            vm.sendPendingImmediately(row.text, row.at)
        }

        fun startBackground(push: Boolean) {
            if (push) startPending() else vm.backgroundForeground("p1")
        }

        fun draftCard(text: String) =
            AppViewModel.PaneDraftShown(checkNotNull(vm.cardTarget("p1")), text, actionable = true, clearable = true)

        fun drain(ms: Long = 30, rounds: Int = 60) = repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
            Thread.sleep(2)
        }

        override suspend fun bulk(command: String): ExecResult = ok("""{"result":{"panes":[${paneOn(terminal)}]}}
            |@@herdrdroid-tabs@@
            |{"result":{"tabs":[]}}
        """.trimMargin())

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            check(expectedConnection == null || expectedConnection === connection) { "write or read used a replaced connection" }
            if (BACKGROUND_REFUSED_MARK in command) {
                // The occupant check, then the screen check, then the key: as the computer would run them.
                checks++
                val occupant = Regex("""'p1' '([0-9a-f]{64})' '-'""").find(command)!!.groupValues[1]
                val now = com.billmongan.herdrdroid.herdr.cardOccupantDigest(org.json.JSONObject(paneOn(remoteTerminal ?: terminal)))
                if (now != occupant) return ExecResult(com.billmongan.herdrdroid.herdr.OCCUPANT_CHANGED_EXIT, com.billmongan.herdrdroid.herdr.OCCUPANT_REFUSED_MARK + "\n", "")
                atCheck?.also { atCheck = null }?.invoke()
                val step = BackgroundStep.entries.single { Regex("""'p1' '${it.check}' && echo""").containsMatchIn(command) }
                if (!backgroundCheckPasses(step, frame)) return ExecResult(BACKGROUND_REFUSED_EXIT, "$BACKGROUND_REFUSED_MARK\n", "")
                keys += "ctrl+b"
                afterCtrlB?.let { frame = it }
                afterKey?.invoke()
                return ok(com.billmongan.herdrdroid.herdr.OCCUPANT_PASSED_MARK + "\n")
            }
            return when {
                command.endsWith("'pane' 'get' 'p1'") -> ok("""{"result":{"pane":${paneOn(remoteTerminal ?: terminal)}}}""").also { afterFirstGet?.also { afterFirstGet = null }?.invoke() }
                command.contains("'pane' 'read' 'p1'") -> {
                    reads++
                    if (vm.pushingSends.isNotEmpty()) pushReads++
                    beforeNthRead?.invoke(reads)
                    ok(frame).also { afterRead?.also { afterRead = null }?.invoke() }
                }
                command.contains("'agent' 'prompt' 'p1' ") -> { prompts += command; ok("") }
                command.contains("'pane' 'send-text' 'p1' ") -> { prompts += command; ok("") }
                command.contains("'pane' 'send-keys' 'p1' ") -> { keys += "unchecked:" + command.substringAfter("'p1' ").replace("'", ""); ok("") }
                else -> ok("")
            }
        }

        private val statusWord get() = when (statusNow) {
            Status.IDLE -> "idle"
            Status.NEEDS_YOU -> "blocked"
            else -> "working"
        }
        private fun paneOn(t: String) = """{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"$agent","agent_status":"$statusWord","cwd":"/fixture","terminal_id":"$t","scroll":{"offset_from_bottom":0}}"""
        private fun ok(out: String) = ExecResult(0, out, "")
        private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }

        /** A message in the agent's own queue, as the chat shows it greyed. */
        fun pending(text: String): AppViewModel.PendingSend {
            val p = AppViewModel.PendingSend(77L, "p1", text, System.currentTimeMillis(), 0, serverId = "fixture")
            state("pendingSends", listOf(p))
            return p
        }
    }

    @Test fun backgroundOnTheRunningPageGoesOnceThroughTheCheckedKey() = fixture("claude", fg) {
        afterCtrlB = moved
        vm.backgroundForeground("p1")
        drain()
        assertEquals(listOf("ctrl+b"), keys)
    }

    @Test fun backgroundForAnAgentWhoseMoveCantBeSeenTypesNothing() {
        for (agent in listOf("opencode", "muse", "codex")) fixture(agent, fg) {
            vm.backgroundForeground("p1")
            drain(rounds = 10)
            assertTrue("$agent: no key", keys.isEmpty())
            assertEquals(0, checks)
        }
    }

    @Test fun aDialogThatOpensBeforeTheComputersCheckGetsNoKey() = fixture("claude", fg) {
        atCheck = { frame = dialog }
        vm.backgroundForeground("p1")
        drain()
        assertTrue(keys.isEmpty())
        assertTrue(checks >= 1)
    }

    @Test fun anOccupantChangeBeforeTheKeyGetsNoKey() = fixture("claude", fg) {
        // The computer's pane changes after the app bound the occupant, before the check.
        afterFirstGet = { remoteTerminal = "replacement" }
        vm.backgroundForeground("p1")
        drain()
        assertTrue(keys.isEmpty())
        assertTrue(checks >= 1)
    }

    @Test fun sendImmediatelyPressesUntilTheMessageIsTaken() = fixture("claude", fg) {
        val p = pending("pick this up")
        afterCtrlB = moved
        vm.sendPendingImmediately(p.text, p.at)
        drain(rounds = 10)
        assertEquals(listOf("ctrl+b"), keys)
        // Claude took it: the chat shows it, the run ends with no more keys.
        state("pendingSends", emptyList<AppViewModel.PendingSend>())
        drain()
        assertEquals(listOf("ctrl+b"), keys)
        assertTrue(vm.pushingSends.isEmpty())
    }

    @Test fun sendImmediatelyStopsAtADialogAndWhenTheLinkDrops() = fixture("claude", dialog) {
        val p = pending("wait for the answer")
        vm.sendPendingImmediately(p.text, p.at)
        drain()
        assertTrue("never into a dialog", keys.isEmpty())
        frame = fg
        state("link", Link.OFFLINE)
        vm.sendPendingImmediately(p.text, p.at)
        drain()
        assertTrue("never without the link", keys.isEmpty())
    }

    @Test fun grokIsMovedThroughItsOwnCheck() = fixture("grok", grokRunning) {
        val p = pending("go on")
        afterCtrlB = grokMoved
        vm.sendPendingImmediately(p.text, p.at)
        drain()
        assertEquals(listOf("ctrl+b"), keys)
    }

    @Test fun afterTheAppReadBothActionsStopOnLocalTargetReplacement() {
        val changes: List<Fixture.() -> Unit> = listOf(
            { terminal = "replacement"; status(Status.WORKING) },
            { connection = Any() },
            { replaceServer() },
            { status(Status.NEEDS_YOU) },
        )
        for (push in listOf(false, true)) for (change in changes) fixture("claude", fg) {
            afterRead = { change() }
            startBackground(push)
            drain()
            assertTrue("push=$push: the read seam ran", afterRead == null)
            assertEquals("push=$push", emptyList<String>(), keys)
            assertEquals("push=$push", 0, checks)
        }
    }

    @Test fun atTheRemoteCheckBothActionsRefuseAChangedScreenOrOccupant() {
        for (push in listOf(false, true)) for (replacement in listOf(dialog, moved, null)) fixture("claude", fg) {
            if (replacement == null) afterRead = { remoteTerminal = "replacement" }
            else atCheck = { frame = replacement }
            startBackground(push)
            drain()
            assertTrue("push=$push: the check ran", checks > 0)
            assertEquals("push=$push", emptyList<String>(), keys)
        }
    }

    @Test fun betweenPressesBothActionsStopOnAQuestionOrTargetChange() {
        val changes: List<Fixture.() -> Unit> = listOf(
            { frame = dialog },
            { connection = Any() },
            { replaceServer() },
            { terminal = "replacement"; status(Status.WORKING) },
        )
        for (push in listOf(false, true)) for (change in changes) fixture("claude", fg) {
            afterKey = { change() }
            startBackground(push)
            drain()
            assertEquals("push=$push", listOf("ctrl+b"), keys)
        }
    }

    @Test fun unsupportedPanesDoNotUseThePendingMessageShortcut() {
        for (agent in listOf("opencode", "muse", "codex")) fixture(agent, fg) {
            startPending()
            drain(rounds = 10)
            assertEquals(agent, emptyList<String>(), keys)
            assertEquals(agent, 0, checks)
        }
    }

    @Test fun pendingMessageStopsAtSixPresses() = fixture("claude", fg) {
        vm.pushLimits = Backgrounder.Limits(pollMs = 10)
        beforeNthRead = { n -> frame = if (n % 2 == 1) fg else fg.replace("(ctrl+b to run in background)", "") }
        startPending()
        drain()
        assertEquals(List(6) { "ctrl+b" }, keys)
        assertTrue(vm.pushingSends.isEmpty())
    }

    @Test fun pendingMessageStopsAfterSixtyReadsWithNoHint() = fixture("claude", fg.replace("(ctrl+b to run in background)", "")) {
        vm.pushLimits = Backgrounder.Limits(pollMs = 1)
        startPending()
        drain()
        assertEquals(60, pushReads)
        assertEquals(emptyList<String>(), keys)
        assertTrue(vm.pushingSends.isEmpty())
    }

    @Test fun queuedSendStopsIfAQuestionOpensAfterTheBackgroundKey() = fixture("claude", fg) {
        vm.backgroundCheckMs = 10
        state("paneText", fg)
        state("paneTextPane", "p1")
        vm.enqueue("p1", "finish the sample")
        afterCtrlB = dialog
        vm.sendQueuedNow(vm.queued.single())
        drain()
        assertEquals(listOf("ctrl+b"), keys)
        assertEquals(emptyList<String>(), prompts)
        assertEquals("finish the sample", vm.queued.single().text)
    }

    private fun ompDraft() = res("/screens/omp/live-2026-11-25-probe/omp-draft.ansi")

    @Test fun clearRequiresTheShownDraftToMatchTheVerifiedInput() = fixture("omp", ompDraft()) {
        status(Status.IDLE)
        vm.clearPaneDraft(draftCard("some older draft"))
        drain()
        assertTrue(reads > 0)
        assertEquals(emptyList<String>(), keys)
    }

    @Test fun clearDoesNotTouchAReplacementDraftAfterTheViewportRead() = fixture("omp", ompDraft()) {
        status(Status.IDLE)
        afterRead = { frame = frame.replace("alpha beta gamma", "another draft") }
        vm.clearPaneDraft(draftCard("alpha beta gamma"))
        drain()
        assertEquals(emptyList<String>(), keys)
    }
}
