package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.1.4 (Bill, 2026-11-21: "there's a little bit of a delay in lighting up the waiting for you
 * yellow icon in the agents left menu"). While the app is in front, a herdr wait on the open server
 * ends on any state change or on a Claude or OpenCode dialog on screen, and the list (with its
 * screen promotion) is read at once; the polls stay as the safety net, tighter with the drawer out.
 */
class NeedsYouLatency214Test {
    private val claudeQuestion = res("/screens/claude/captured-2.1.329/single-question.txt")
    private val openCodePermission = res("/screens/opencode/live-2026-11-21/permission-shell-2.3.2.txt")

    // ---- cadence and backoff --------------------------------------------------------------------

    @Test fun theListPollTightensOnlyWhileTheDrawerIsOut() {
        assertEquals(2_000L, NeedsYouCadence.listGapMs(drawerOpen = true))
        assertEquals(4_000L, NeedsYouCadence.listGapMs(drawerOpen = false))
    }

    @Test fun theOtherServersAreReadFasterOnlyWithTheDrawerOutInFront() {
        assertEquals(3_000L, NeedsYouCadence.fleetGapMs(watching = true, drawerOpen = true, foreground = true))
        assertEquals(3_000L, NeedsYouCadence.fleetGapMs(watching = false, drawerOpen = true, foreground = true))
        // Behind, the drawer left out changes nothing: as before 2.1.4.
        assertEquals(5_000L, NeedsYouCadence.fleetGapMs(watching = true, drawerOpen = true, foreground = false))
        assertEquals(10_000L, NeedsYouCadence.fleetGapMs(watching = false, drawerOpen = false, foreground = true))
    }

    @Test fun aWakeIsSpacedATimeoutRearmsAndAFailureBacksOff() {
        val wait = NeedsYouCadence.PUSH_WAIT_MS
        assertEquals(1_700L, NeedsYouCadence.pauseAfter(woke = true, elapsedMs = 300, waitMs = wait))
        assertEquals(0L, NeedsYouCadence.pauseAfter(woke = true, elapsedMs = 9_000, waitMs = wait))
        assertEquals(0L, NeedsYouCadence.pauseAfter(woke = false, elapsedMs = wait, waitMs = wait))
        assertEquals(10_000L, NeedsYouCadence.pauseAfter(woke = false, elapsedMs = 50, waitMs = wait))
    }

    // ---- the wait itself --------------------------------------------------------------------------

    @Test fun waitsUseHerdrsOwnStatusSoAPromotedOrBusyPaneDoesNotWakeAtOnce() = runBlocking {
        val f = Fake()
        f.status["c1"] = "working"
        f.status["x1"] = "idle"
        f.screen["c1"] = claudeQuestion
        val shown = f.client.panes()
        val claude = shown.single { it.id == "c1" }
        assertEquals(Status.NEEDS_YOU, claude.status)
        assertEquals(Status.WORKING, f.client.herdrStates["c1"])
        // Before 2.1.4 the wait asked for "working" on a pane herdr already called working.
        assertFalse("working" in waitUntilStates(claude, f.client.herdrStates["c1"]))
        assertTrue("working" in waitUntilStates(claude))
        // A pane shown working for its background shell waits on herdr's "idle" being left.
        val busy = shown.single { it.id == "x1" }.copy(status = Status.WORKING, backgroundBusy = true)
        assertFalse("idle" in waitUntilStates(busy, f.client.herdrStates["x1"]))
        val script = checkNotNull(f.client.waitScript(listOf(claude, busy), 25_000))
        assertTrue(script, "'c1' --until idle --until blocked --until done --timeout 25000" in script)
        assertTrue(script, "'x1' --until working --until blocked --until done --timeout 25000" in script)
    }

    @Test fun onlyClaudeAndOpenCodeScreensAreWatchedAndTheWaitsAreStoppedWithTheirChildren() = runBlocking {
        val f = Fake()
        f.status["c1"] = "working"; f.status["o1"] = "done"; f.status["x1"] = "idle"
        f.agent["o1"] = "opencode"
        val shown = f.client.panes()
        val screens = screenWatchPanes(shown, f.client.herdrStates)
        assertEquals(listOf("c1", "o1"), screens.map { it.id }.sorted())
        val script = checkNotNull(f.client.waitScript(shown, 25_000, screens))
        assertEquals(3, Regex("agent wait").findAll(script).count())
        assertEquals(2, Regex("'wait-output'").findAll(script).count())
        assertTrue(script, "'--source' 'visible' '--timeout' '25000' 'c1'" in script)
        assertTrue(script, "'--source' 'visible' '--timeout' '25000' 'o1'" in script)
        assertFalse(script, "'--source' 'visible' '--timeout' '25000' 'x1'" in script)
        // A failed screen wait sleeps out the round; the herdr processes go when the round ends.
        assertTrue(script, "|| sleep 30" in script)
        assertTrue(script, "pkill -P" in script)
    }

    @Test fun aPaneAlreadyShownAsNeedingYouOrHerdrBlockedIsNotWatched() = runBlocking {
        val f = Fake()
        f.status["c1"] = "blocked"; f.status["c2"] = "idle"
        f.agent["c2"] = "claude"
        f.screen["c2"] = claudeQuestion
        val shown = f.client.panes()
        assertTrue(screenWatchPanes(shown, f.client.herdrStates).isEmpty())
    }

    @Test fun theScreenFootersMatchTheCapturedDialogsAndNotAComposer() {
        // Java reads these inline flags and classes the way herdr's Rust regex does.
        val claude = Regex(screenWakeRegex("claude")!!)
        val openCode = Regex(screenWakeRegex("opencode")!!)
        assertTrue(claude.containsMatchIn(claudeQuestion))
        assertTrue(openCode.containsMatchIn(openCodePermission))
        assertFalse(claude.containsMatchIn("❯ \n  ? for shortcuts"))
        assertFalse(openCode.containsMatchIn("┃  Build  claude-opus  \n  ~/dir  12K (6%)  ctrl+p commands"))
        assertNull(screenWakeRegex("codex"))
    }

    // ---- a change reads the list at once, with its promotion -------------------------------------

    @Test fun aStateChangeReadsTheListAtOnceAndPromotesTheClaudeQuestion() = runBlocking {
        val clock = Clock()
        val f = Fake(clock)
        f.status["c1"] = "working"
        f.screen["c1"] = "❯ \n"
        val before = f.client.panes()
        assertEquals(Status.WORKING, before.single { it.id == "c1" }.status)
        // The question appears; herdr calls the pane idle.
        f.screen["c1"] = claudeQuestion
        f.status["c1"] = "idle"
        f.waitOut = "@@chg c1\n"
        f.commands.clear()
        val push = StatusPush { clock.t }
        var refreshed = 0
        var shown: List<Pane> = before
        val r = push.round(f.client, before, NeedsYouCadence.PUSH_WAIT_MS) { refreshed++; f.client.panes().also { shown = it } }
        assertEquals(Herdr.Wake("c1", screen = false), r.wake)
        assertEquals(1, refreshed)
        assertEquals(Status.NEEDS_YOU, shown.single { it.id == "c1" }.status)
        // The wait, then the list and the promotion's screen read, nothing else.
        assertEquals(listOf("wait", "list", "tails"), f.commands.map { f.kind(it) })
    }

    @Test fun aScreenWakeLightsAClaudeQuestionHerdrStillCallsWorking() = runBlocking {
        val clock = Clock()
        val f = Fake(clock)
        f.status["c1"] = "working"
        f.screen["c1"] = "❯ \n"
        val before = f.client.panes()
        f.screen["c1"] = claudeQuestion
        f.waitOut = "@@scr c1\n"
        val push = StatusPush { clock.t }
        var shown: List<Pane> = before
        val r = push.round(f.client, before, NeedsYouCadence.PUSH_WAIT_MS) { f.client.panes().also { shown = it } }
        assertEquals(Herdr.Wake("c1", screen = true), r.wake)
        assertEquals(Status.NEEDS_YOU, shown.single { it.id == "c1" }.status)
        // Already shown as needing you, so its screen isn't watched again.
        assertTrue(push.screens(f.client, shown).isEmpty())
    }

    @Test fun aScreenWakeThatShowsNothingQuietsThatScreenUntilHerdrChangesOrAMinutePasses() = runBlocking {
        val clock = Clock()
        val f = Fake(clock)
        f.status["c1"] = "working"
        f.screen["c1"] = "Enter to select something in old output\n❯ \n"
        val before = f.client.panes()
        f.waitOut = "@@scr c1\n"
        val push = StatusPush { clock.t }
        var shown = before
        push.round(f.client, before, NeedsYouCadence.PUSH_WAIT_MS) { f.client.panes().also { shown = it } }
        assertEquals(Status.WORKING, shown.single { it.id == "c1" }.status)
        assertTrue(push.screens(f.client, shown).isEmpty())
        clock.t += NeedsYouCadence.SCREEN_QUIET_MS
        assertEquals(listOf("c1"), push.screens(f.client, shown).map { it.id })
        // Quieted again, then herdr's state for it changes: watched again at once.
        push.round(f.client, shown, NeedsYouCadence.PUSH_WAIT_MS) { f.client.panes().also { shown = it } }
        assertTrue(push.screens(f.client, shown).isEmpty())
        f.status["c1"] = "idle"
        shown = f.client.panes()
        assertEquals(listOf("c1"), push.screens(f.client, shown).map { it.id })
    }

    @Test fun aQuietScreenDoesNotFollowThePaneIdToAnotherServerOrOccupant() = runBlocking {
        val first = Fake()
        first.status["c1"] = "working"
        first.waitOut = "@@scr c1\n"
        val shown = first.client.panes()
        val push = StatusPush { 1000L }
        push.round(first.client, shown, NeedsYouCadence.PUSH_WAIT_MS) { shown }
        assertTrue(push.screens(first.client, shown).isEmpty())

        val replacement = shown.map { it.copy(terminalId = "replacement") }
        assertEquals(listOf("c1"), push.screens(first.client, replacement).map { it.id })

        push.round(first.client, shown, NeedsYouCadence.PUSH_WAIT_MS) { shown }
        assertTrue(push.screens(first.client, shown).isEmpty())
        val other = Fake()
        other.status["c1"] = "working"
        assertEquals(listOf("c1"), push.screens(other.client, other.client.panes()).map { it.id })
    }

    @Test fun aScreenWakeCannotQuietAClosedPaneId() = runBlocking {
        val f = Fake()
        f.status["c1"] = "working"
        f.waitOut = "@@scr c1\n"
        val shown = f.client.panes()
        val push = StatusPush { 1000L }
        push.round(f.client, shown, NeedsYouCadence.PUSH_WAIT_MS) { emptyList() }
        assertEquals(listOf("c1"), push.screens(f.client, shown).map { it.id })
    }

    @Test fun herdrCoreAndNoAgentsRunNoWait() = runBlocking {
        val core = Fake(core = true)
        assertNull(core.client.waitForWake(listOf(pane("c1", "claude")), 25_000))
        val tui = Fake()
        assertNull(tui.client.waitForWake(listOf(pane("s1", null)), 25_000))
        assertTrue(core.commands.isEmpty() && tui.commands.isEmpty())
    }

    // ---- the command budget -------------------------------------------------------------------------

    /** The loop the app runs while in front, on a fake clock, for [spanMs]: the waits and list reads it made. */
    private suspend fun loop(f: Fake, clock: Clock, spanMs: Long): Pair<Int, Int> {
        val push = StatusPush { clock.t }
        var list = f.client.panes()
        f.commands.clear()
        val end = clock.t + spanMs
        var waits = 0
        var refreshes = 0
        while (clock.t < end) {
            waits++
            val r = push.round(f.client, list, NeedsYouCadence.PUSH_WAIT_MS) { refreshes++; clock.t += 150; f.client.panes().also { list = it } }
            clock.t += NeedsYouCadence.pauseAfter(r.wake != null, r.elapsedMs, NeedsYouCadence.PUSH_WAIT_MS)
        }
        return waits to refreshes
    }

    @Test fun aHerdrThatWakesConstantlyCostsAtMostOneReadEveryTwoSeconds() = runBlocking {
        val clock = Clock()
        val f = Fake(clock)
        f.status["c1"] = "working"; f.status["x1"] = "idle"
        f.waitOut = "@@chg x1\n"
        f.waitCostMs = 50
        val (waits, refreshes) = loop(f, clock, 60_000)
        assertTrue("$waits waits", waits <= 31)
        assertTrue("$refreshes refreshes", refreshes <= 30)
        // Each refresh is the list and at most one screen read per askable pane: no more than 2 bulk commands.
        val bulk = f.commands.count { f.kind(it) != "wait" }
        assertTrue("$bulk reads", bulk <= 2 * refreshes)
    }

    @Test fun aQuietServerCostsOneWaitPer25SecondsAndNoReads() = runBlocking {
        val clock = Clock()
        val f = Fake(clock)
        f.status["c1"] = "working"
        f.waitOut = ""
        f.waitCostMs = NeedsYouCadence.PUSH_WAIT_MS
        val (waits, refreshes) = loop(f, clock, 60_000)
        assertEquals(3, waits)
        assertEquals(0, refreshes)
        assertTrue(f.commands.all { f.kind(it) == "wait" })
    }

    @Test fun aWaitThatFailsAtOnceIsTriedAgainOnlyEveryTenSeconds() = runBlocking {
        val clock = Clock()
        val f = Fake(clock)
        f.status["c1"] = "working"
        f.waitOut = ""
        f.waitExit = 2
        f.waitCostMs = 20
        val (waits, refreshes) = loop(f, clock, 60_000)
        assertTrue("$waits waits", waits <= 6)
        assertEquals(0, refreshes)
    }

    // ---- the fake ---------------------------------------------------------------------------------

    class Clock { var t = 1_000_000L }

    private class Fake(private val clock: Clock = Clock(), core: Boolean = false) : Herdr.Commands {
        val ssh = HerdrSsh("127.0.0.1", 1, "fake", Credentials(null, null), null, { null })
        val client = Herdr(ssh, "/fixture/herdr", if (core) Backend.CORE else Backend.TUI, if (core) "/fixture/hc" else null, this)
        override var connection: Any? = Any()
        val status = linkedMapOf<String, String>()
        val agent = mutableMapOf<String, String>()
        val screen = mutableMapOf<String, String>()
        var waitOut = ""
        var waitExit = 0
        var waitCostMs = 0L
        val commands = mutableListOf<String>()

        fun kind(command: String) = when {
            "agent wait" in command -> "wait"
            "'pane' 'list'" in command -> "list"
            "@@herdrdroid@@" in command -> "tails"
            else -> "other"
        }

        override suspend fun bulk(command: String): ExecResult = answer(command)
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = answer(command)

        private fun agentOf(id: String) = agent[id] ?: if (id.startsWith("c")) "claude" else "codex"

        private fun answer(command: String): ExecResult {
            commands += command
            return when (kind(command)) {
                "wait" -> { clock.t += waitCostMs; ExecResult(if (waitOut.isEmpty() && waitExit == 0) 1 else waitExit, waitOut, "") }
                "list" -> ExecResult(0, """{"result":{"panes":[${status.entries.joinToString(",") { (id, st) ->
                    """{"pane_id":"$id","workspace_id":"w1","tab_id":"t-$id","agent":"${agentOf(id)}","agent_status":"$st","cwd":"/fixture","terminal_id":"term-$id","scroll":{"offset_from_bottom":0}}"""
                }}]}}
@@herdrdroid-tabs@@
{"result":{"tabs":[]}}""", "")
                "tails" -> ExecResult(0, Regex("""@@herdrdroid@@([^']+)""").findAll(command).joinToString("\n") { m ->
                    "@@herdrdroid@@${m.groupValues[1]}\n" + (screen[m.groupValues[1]] ?: "❯ \n")
                }, "")
                else -> ExecResult(1, "", "not in the fake: $command")
            }
        }
    }

    private fun pane(id: String, agent: String?) = Pane(id, "t", "w", agent, Status.WORKING, "", "/fixture")

    private fun res(path: String) = checkNotNull(javaClass.getResource(path)) { path }.readText()
}
