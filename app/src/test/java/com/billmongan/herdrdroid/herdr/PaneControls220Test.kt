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

    @Test fun vibeNeverStopsOnAutoApprove() {
        // Smart approve turned off: accept edits goes straight to auto approve, which is passed back to ask.
        val spec = PaneControls.modes("vibe")!!
        val steps = run(spec, "accept edits", "auto", listOf("bypass", "default", "accept edits"))
        val last = steps.last() as Step.Stop
        assertEquals("accept edits", last.mode)
        assertTrue(last.why, last.why.contains("approves every tool"))
    }

    @Test fun vibeOffersOnlyModesReachedWithoutPassingAutoApprove() {
        val spec = PaneControls.modes("vibe")!!
        // From smart approve, ask and accept edits lie past auto approve.
        assertEquals(listOf("auto"), PaneControls.reachable(spec, "auto").map { it.name })
        assertEquals(listOf("default", "accept edits", "auto"), PaneControls.reachable(spec, "default").map { it.name })
        assertFalse(PaneControls.reachable(spec, "default").any { it.approvesAll })
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
