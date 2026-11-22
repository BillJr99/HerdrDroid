package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.herdr.PaneControls.ModeSwitcher
import com.billmongan.herdrdroid.herdr.PaneControls.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.0 (Bill: "make sure all panes can change the mode like I can in Claude and also let all panes
 * change the thinking mode (omit any menus where not supported)"): which agents get the menus, how a
 * switch walks the cycle and confirms it from the footer, and how the thinking level is read back.
 */
class PaneControls220Test {
    private fun res(path: String) = requireNotNull(javaClass.getResource("/screens/$path")).readText()

    /** Feeds the switcher the modes a pane would show after each press; returns the steps taken. */
    private fun run(spec: PaneControls.ModeSpec, start: String, target: String, reads: List<String?>): List<Step> {
        val sw = ModeSwitcher(spec, start, target)
        val out = mutableListOf<Step>()
        var read: String? = start
        val it = reads.iterator()
        while (true) {
            val step = sw.next(read)
            out += step
            if (step !is Step.Press) return out
            read = if (it.hasNext()) it.next() else return out
        }
    }

    @Test fun menusOnlyWhereTheAgentHasAVerifiedWay() {
        for (a in listOf("claude", "vibe", "kiro", "opencode")) assertNotNull(a, PaneControls.modes(a))
        for (a in listOf("codex", "grok", "copilot", "agy", "gemini", "pi", "omp", "hermes", "muse")) assertNull(a, PaneControls.modes(a))
        for (a in listOf("claude", "grok", "muse", "agy", "kiro")) assertNotNull(a, PaneControls.thinking(a))
        for (a in listOf("codex", "opencode", "copilot", "pi", "omp", "hermes", "vibe", "gemini")) assertNull(a, PaneControls.thinking(a))
    }

    @Test fun claudeReachesPlanByPressingAndReading() {
        val spec = PaneControls.modes("claude")!!
        val steps = run(spec, "default", "plan", listOf("accept edits", "plan"))
        assertEquals(listOf(Step.Press, Step.Press, Step.Done("plan")), steps)
    }

    @Test fun aModeNotInTheCycleComesBackToTheStart() {
        // Claude without auto in its cycle: default, accept edits, plan, default.
        val spec = PaneControls.modes("claude")!!
        val steps = run(spec, "default", "auto", listOf("accept edits", "plan", "default"))
        val last = steps.last() as Step.Stop
        assertEquals("default", last.mode)
        assertTrue(last.why, last.why.contains("isn't in this session's cycle"))
    }

    @Test fun claudeMayPassOverBypassOnTheWayToAuto() {
        val spec = PaneControls.modes("claude")!!
        val steps = run(spec, "plan", "auto", listOf("bypass", "auto"))
        assertEquals(Step.Done("auto"), steps.last())
    }

    /** Feeds the switcher a read after each press; true in a pair means "the footer didn't change after the press". */
    private fun drive(spec: PaneControls.ModeSpec, start: String, target: String, reads: List<Pair<String, Boolean>>): List<Step> {
        val sw = ModeSwitcher(spec, start, target)
        val out = mutableListOf(sw.next(start))
        for ((read, same) in reads) {
            if (out.last() !is Step.Press) break
            out += if (same) sw.unchanged(read) else sw.next(read)
        }
        return out
    }

    @Test fun aVibeLandingOnAutoApproveKeepsPressingBackToTheStart() {
        // From Grok's review: with Smart approve off, a press can land on Auto approve, and a press there
        // may not change the footer at first. The switch never stops there: it presses on to the start.
        val spec = PaneControls.modes("vibe")!!
        val steps = drive(spec, "accept edits", "auto", listOf("bypass" to false, "bypass" to true, "bypass" to true, "default" to false, "accept edits" to false))
        val last = steps.last() as Step.Stop
        assertEquals("accept edits", last.mode)
        assertTrue(last.why, last.why.contains("approves every tool"))
        assertEquals(5, steps.count { it == Step.Press })
    }

    @Test fun aSwitchThatCantLeaveAutoApproveStopsLoudly() {
        val spec = PaneControls.modes("vibe")!!
        val sw = ModeSwitcher(spec, "default", "accept edits")
        assertEquals(Step.Press, sw.next("default"))
        var step = sw.next("bypass")
        var guard = 0
        while (step == Step.Press && guard++ < 100) step = sw.unchanged("bypass")
        val stop = step as Step.Stop
        assertEquals("bypass", stop.mode)
        assertTrue(stop.why, stop.why.contains("didn't come back"))
        assertTrue(sw.presses <= sw.limit)
        assertTrue(PaneControls.unconfirmedApproveAll(spec, stop.mode, "accept edits"))
    }

    @Test fun claudeNeverStopsOnBypassItDidntPick() {
        val spec = PaneControls.modes("claude")!!
        // On the way from plan to auto the footer sticks on bypass: it presses on to plan.
        val steps = drive(spec, "plan", "auto", listOf("bypass" to false, "bypass" to true, "default" to false, "accept edits" to false, "plan" to false))
        assertEquals("plan", (steps.last() as Step.Stop).mode)
        // Bypass picked and confirmed is a target like any other.
        assertFalse(PaneControls.unconfirmedApproveAll(spec, "bypass", "bypass"))
        assertEquals(Step.Done("bypass"), drive(spec, "plan", "bypass", listOf("bypass" to false)).last())
        // An ordinary mode that doesn't change stops where it is.
        assertTrue((drive(spec, "default", "plan", listOf("default" to true)).last() as Step.Stop).why.contains("didn't change"))
    }

    @Test fun vibeOffersOnlyAskAndAcceptEdits() {
        val spec = PaneControls.modes("vibe")!!
        assertEquals(listOf("default", "accept edits"), PaneControls.reachable(spec, "default").map { it.name })
        // Smart approve shows only as the mode already on; nothing else is reachable from it without Auto approve.
        assertEquals(listOf("auto"), PaneControls.reachable(spec, "auto").map { it.name })
        assertFalse(PaneControls.reachable(spec, "accept edits").any { it.name == "auto" || it.approvesAll })
        assertFalse(PaneControls.canSwitch(spec, "bypass"))
    }

    @Test fun kiroWithTrustAllToolsOnlyShowsIt() {
        val spec = PaneControls.modes("kiro")!!
        assertFalse(PaneControls.canSwitch(spec, "bypass"))
        assertTrue(PaneControls.canSwitch(spec, "default"))
        assertEquals(listOf("default", "plan"), PaneControls.reachable(spec, "default").map { it.name })
    }

    @Test fun claudeKeepsBypassButOnlyBehindItsFlag() {
        val spec = PaneControls.modes("claude")!!
        assertTrue(PaneControls.reachable(spec, "default").single { it.approvesAll }.name == "bypass")
        assertTrue(PaneControls.canSwitch(spec, "bypass"))
    }

    @Test fun aMenuWriteWaitsForADraftOrAQuestion() {
        val draft = res("claude/captured-2.1.329/composer-titled-list-typed.ansi")
        assertEquals(PaneControls.TYPED, PaneControls.writeBlock("claude", draft))
        val question = res("claude/captured-2.1.329/single-question.txt")
        assertEquals("a question or dialog is open in it", PaneControls.writeBlock("claude", question))
        assertNull(PaneControls.writeBlock("claude", res("claude/captured-2.1.329/composer-titled-list-empty.ansi")))
        // The empty input boxes captured live let a menu write go for every agent with a menu. pi, Oh My
        // Pi and Copilot draw no input line the app can find, so nothing would be typed: they have none.
        val all = listOf("claude", "vibe", "kiro", "opencode", "grok", "muse", "agy", "codex", "pi", "omp", "hermes", "copilot")
        val refused = all.filter { a -> PaneControls.writeBlock(a, res("$a/live-2026-11-20-composer/empty.ansi")) != null }
        assertEquals(listOf("pi", "omp", "copilot"), refused)
        for (a in all) if (PaneControls.modes(a) != null || PaneControls.thinking(a) != null || SessionControls.forAgent(a) != null)
            assertTrue(a, a !in refused)
    }

    @Test fun anUnchangedOrUnreadFooterStopsTheSwitch() {
        val spec = PaneControls.modes("claude")!!
        assertTrue(run(spec, "default", "plan", listOf(null)).last() is Step.Stop)
        assertEquals(Step.Stop(null, "the footer couldn't be read; check the pane"), ModeSwitcher(spec, "x", "plan").next(null))
    }

    @Test fun pressesOnAKnownCycleAvoidApproveAll() {
        val c = listOf("default", "accept edits", "auto", "bypass")
        assertEquals(2, PaneControls.pressesOnCycle(c, "default", "auto", setOf("bypass")))
        assertNull(PaneControls.pressesOnCycle(c, "auto", "default", setOf("bypass")))
        assertNull(PaneControls.pressesOnCycle(null, "a", "b"))
    }

    @Test fun openCodeShowsItsAgentInTheInputBox() {
        assertEquals("build", agentMode("opencode", stripAnsi(res("opencode/live-2026-11-22-session/hosted-empty.ansi"))))
        assertEquals("plan", agentMode("opencode", "  ┃\n  ┃  Plan · Hosted Coding Model  Hosted AI\n  ╹▀▀▀▀\n  ~/x    ctrl+p commands"))
        // A message in the chat that starts with the word is not the input box.
        assertNull(agentMode("opencode", "  ┃  Build the parser first\n"))
    }

    @Test fun thinkingIsReadBackFromEachAgentsScreen() {
        assertEquals("high", PaneControls.thinking("grok")!!.shown(res("grok/captured-2026-11-13/privacy-banner.txt")))
        assertEquals("max", PaneControls.thinking("muse")!!.shown(res("muse/captured-2026-11-15/command-approval.txt")))
        assertEquals("medium", PaneControls.thinking("agy")!!.shown(res("agy/errors-1.4.2/api.txt")))
    }

    @Test fun claudeAndKiroConfirmByTheirReply() {
        val claude = PaneControls.thinking("claude")!!
        val before = "❯ hello\n⎿  Set effort level to high (this session only): deeper reasoning"
        val after = before + "\n❯ /effort high\n  ⎿  Set effort level to high (this session only): deeper reasoning"
        assertFalse(PaneControls.thinkingConfirmed(claude, "high", before, before))
        assertTrue(PaneControls.thinkingConfirmed(claude, "high", before, after))
        assertFalse(PaneControls.thinkingConfirmed(claude, "max", before, after))
        val kiro = PaneControls.thinking("kiro")!!
        assertTrue(PaneControls.thinkingConfirmed(kiro, "low", "", "> /effort low\nEffort set to low"))
        assertEquals("/effort xhigh", kiro.command("xhigh"))
    }
}
