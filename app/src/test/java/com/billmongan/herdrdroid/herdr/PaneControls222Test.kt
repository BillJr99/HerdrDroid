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
 * 2.2.2 (Bill, 2026-11-22: "make sure that I can switch sessions for any agent that supports it and also
 * make sure that I can set thinking mode and general mode meaning Auto versus plan versus accept edits
 * for all agents that support it"). Screens are from live panes on 2026-11-22 (live-2026-11-22-menus,
 * conversation text taken out); "typed" and "hint" variants are derived from them.
 */
class PaneControls222Test {
    private fun res(path: String) = requireNotNull(javaClass.getResource("/screens/$path")).readText()
    private fun menus(a: String) = "$a/live-2026-11-22-menus"

    // ---- Input boxes: pi, Oh My Pi, Copilot -------------------------------------------------------------

    @Test fun piOmpAndCopilotEmptyBoxesLetAMenuWriteGo() {
        for (a in listOf("pi", "omp", "copilot")) {
            assertEquals(a, "", inputLineOf(res("${menus(a)}/empty.ansi"), a))
            assertEquals(a, "", inputLineOf(stripAnsi(res("${menus(a)}/empty.ansi")), a))
            assertNull(a, PaneControls.writeBlock(a, res("${menus(a)}/empty.ansi")))
        }
    }

    @Test fun textInTheirBoxesIsTyping() {
        for (a in listOf("pi", "omp", "copilot")) {
            assertEquals(a, "hello there", inputLineOf(res("${menus(a)}/typed.ansi"), a))
            assertEquals(a, PaneControls.TYPED, PaneControls.writeBlock(a, res("${menus(a)}/typed.ansi")))
        }
    }

    @Test fun ompsOwnHintIsEmptyButTheSameWordsInNormalTypeAreTyping() {
        assertEquals("", inputLineOf(res("${menus("omp")}/hint.ansi"), "omp"))
        assertEquals("", inputLineOf(stripAnsi(res("${menus("omp")}/hint.ansi")), "omp"))
        assertEquals("shift+tab to change thinking effort", inputLineOf(res("${menus("omp")}/hint-typed.ansi"), "omp"))
    }

    @Test fun anUnknownLayoutIsNotEmpty() {
        // No rules, no frame, no gutter: nothing is judged empty.
        for (a in listOf("pi", "omp", "copilot")) assertTrue(a, PaneControls.writeBlock(a, "some output\nmore output\n") != null)
        // pi with a list under its editor (autocomplete), or a top rule carrying a status: not known.
        val pi = stripAnsi(res("${menus("pi")}/empty.ansi"))
        assertNull(AgentComposers.pi(pi + "\n/new  start\n/resume  pick\n/model  choose\n/help  help\n/quit  quit"))
        assertNull(AgentComposers.pi(pi.replaceFirst("────────", "── Working ")))
        // Copilot's box with something other than its frame inside.
        assertNull(AgentComposers.copilot(stripAnsi(res("${menus("copilot")}/empty.ansi")).replace(Regex("""(?m)^┃\s*$"""), "x")))
        // Oh My Pi's gutter with no status row above it.
        assertNull(AgentComposers.omp("output\n╰─ \n"))
    }

    // ---- Modes ---------------------------------------------------------------------------------------------

    @Test fun codexSetsAPermissionProfileOutrightAndNeverFullAccess() {
        val spec = PaneControls.modes("codex")!!
        assertNull(spec.read(res("${menus("codex")}/screen.ansi")))
        assertTrue(PaneControls.canSwitch(spec, null))
        assertEquals(listOf(":read-only", ":workspace"), PaneControls.reachable(spec, null).map { it.name })
        assertEquals("/permissions :read-only", spec.commandFor(null, ":read-only"))
        assertNull(spec.commandFor(":workspace", ":danger-full-access"))
        val before = "› Ask Codex to do anything"
        assertTrue(spec.confirmed!!(before, "$before\n• Permissions updated to Read Only\n› Ask Codex", ":read-only"))
        assertFalse(spec.confirmed!!(before, before, ":read-only"))
        assertFalse(spec.confirmed!!(before, "$before\n• Permissions updated to Full Access", ":workspace"))
    }

    @Test fun grokTogglesAutoReviewByItsCommand() {
        val spec = PaneControls.modes("grok")!!
        assertEquals("auto", spec.read(res("${menus("grok")}/screen.ansi")))
        assertEquals("default", spec.read("  ╰──── Grok 4.7 (high) ─╯"))
        assertEquals("bypass", spec.read("  ╰──── Grok 4.7 (high) · always-approve ─╯"))
        assertNull(spec.read("  ╰──── Grok 4.7 (high) · something-new ─╯"))
        assertEquals("/auto-review", spec.commandFor("auto", "default"))
        assertEquals("/auto-review", spec.commandFor("default", "auto"))
        assertEquals(listOf("default", "auto"), PaneControls.reachable(spec, "auto").map { it.name })
        // Always-approve is never offered, and the menu doesn't switch away from it.
        assertFalse(PaneControls.canSwitch(spec, "bypass"))
        assertFalse(PaneControls.reachable(spec, "default").any { it.approvesAll })
    }

    @Test fun copilotPlanByShiftTabBackByAutopilotOffAndNeverPastAutopilot() {
        val spec = PaneControls.modes("copilot")!!
        val screen = res("${menus("copilot")}/empty.ansi")
        assertEquals("interactive", spec.read(screen))
        assertEquals(listOf("interactive", "plan"), PaneControls.reachable(spec, "interactive").map { it.name })
        assertNull(spec.commandFor("interactive", "plan"))
        assertEquals("/autopilot off", spec.commandFor("plan", "interactive"))
        assertEquals("/autopilot off", spec.commandFor("autopilot", "interactive"))
        // From Autopilot, Plan would need two presses: not offered.
        assertEquals(listOf("interactive", "autopilot"), PaneControls.reachable(spec, "autopilot").map { it.name })
        // One press that doesn't land on Plan stops there instead of pressing on.
        val sw = ModeSwitcher(spec, "interactive", "plan")
        assertEquals(Step.Press, sw.next("interactive"))
        assertEquals("autopilot", (sw.next("autopilot") as Step.Stop).mode)
        assertEquals(Step.Done("plan"), ModeSwitcher(spec, "interactive", "plan").let { it.next("interactive"); it.next("plan") })
    }

    @Test fun copilotApprovalsNeverAllowAll() {
        val spec = PaneControls.approvals("copilot")!!
        assertEquals("Approvals", spec.title)
        assertEquals("manual", spec.read(res("${menus("copilot")}/empty.ansi")))
        assertEquals("assisted", spec.read(" ← open sidebar · Plan · Assisted Permissions · / commands"))
        assertEquals("bypass", spec.read(" ← open sidebar · Interactive · Allow All · / commands"))
        assertEquals("/permissions assisted", spec.commandFor("manual", "assisted"))
        assertEquals("/permissions default", spec.commandFor("assisted", "manual"))
        assertNull(spec.commandFor("manual", "bypass"))
        assertFalse(PaneControls.canSwitch(spec, "bypass"))
        assertEquals(listOf("Mode", "Approvals"), PaneControls.modeMenus("copilot").map { it.title })
    }

    @Test fun antigravityReadsItsModeRow() {
        val spec = PaneControls.modes("agy")!!
        assertEquals("default", spec.read(res("agy/live-2026-11-20-composer/empty.ansi")))
        assertEquals("plan", spec.read(">\n────\nPlan mode: research & plan only (shift+tab to cycle)        Gemini 4 Neon · high"))
        assertEquals("accept edits", spec.read("Accept-edits mode: file edits auto-approved (shift+tab to cycle)"))
        assertEquals("auto", spec.read("Auto mode: edits & safe commands auto-approved (shift+tab to cycle)"))
        // While it works the row shows "esc to cancel": the mode can't be read.
        assertNull(spec.read(res("${menus("agy")}/screen.ansi")))
        assertTrue(spec.options.none { it.approvesAll })
    }

    @Test fun ompTogglesPlan() {
        val spec = PaneControls.modes("omp")!!
        assertEquals("default", spec.read(res("${menus("omp")}/empty.ansi")))
        assertEquals("plan", spec.read(" π > 🗺 Plan > ⬢ model · ◒ high > 📁 ~/x ▶────\n╰─ "))
        assertNull(spec.read(" π > Goal > ⬢ model > 📁 ~/x ▶────\n╰─ "))
        assertEquals("/plan", spec.commandFor("default", "plan"))
        assertEquals("/plan", spec.commandFor("plan", "default"))
    }

    @Test fun agentsWithoutAVerifiedSwitchHaveNoModeMenu() {
        for (a in listOf("pi", "muse", "hermes")) assertTrue(a, PaneControls.modeMenus(a).isEmpty())
    }

    // ---- Thinking ------------------------------------------------------------------------------------------

    @Test fun thinkingCommandsAndReadBack() {
        val codex = PaneControls.thinking("codex")!!
        assertEquals("/model --reasoning=high", codex.command("high"))
        assertEquals("default", codex.shown(res("${menus("codex")}/screen.ansi")))
        assertEquals("high", codex.shown("› Ask Codex\n\n  GPT-6.2-Sol high · ~/agents/codex\n  ? for shortcuts"))
        val hermes = PaneControls.thinking("hermes")!!
        assertEquals("/reasoning low", hermes.command("low"))
        assertTrue(PaneControls.thinkingConfirmed(hermes, "low", "", "✓ Reasoning effort set to 'low' (session only)"))
        assertFalse(PaneControls.thinkingConfirmed(hermes, "high", "", "✓ Reasoning effort set to 'low' (session only)"))
        val pi = PaneControls.thinking("pi")!!
        assertEquals("/thinking xhigh", pi.command("xhigh"))
        assertNull(pi.shown(res("${menus("pi")}/empty.ansi")))
        assertEquals("high", pi.shown("~/x\n↑1k ↓2k 3.0%/128k (auto)        (prov) model-x • high"))
        assertEquals("off", pi.shown("~/x\n↑1k 3.0%/128k        model-x • thinking off"))
        val omp = PaneControls.thinking("omp")!!
        assertEquals("/effort medium", omp.command("medium"))
        assertNull(omp.shown(res("${menus("omp")}/empty.ansi")))
        assertEquals("medium", omp.shown(" π > ⬢ model-x · ◑ med > 📁 ~/x ▶────\n╰─ "))
        // Antigravity 1.5.14 ends its row with the level.
        assertEquals("xhigh", PaneControls.thinking("agy")!!.shown(res("${menus("agy")}/screen.ansi")))
        assertEquals("high", PaneControls.thinking("muse")!!.shown(res("${menus("muse")}/screen.ansi")))
    }

    // ---- Sessions ------------------------------------------------------------------------------------------

    private fun s(ref: String) = PastSession(ref, null, 0, null, 0)

    @Test fun sessionsForEveryAgentThatHasThem() {
        assertEquals("/resume 01a1f936-1597-74de-bcd4-1336fb97ae54",
            SessionControls.forAgent("omp")!!.resume(s("/h/.omp/agent/sessions/-agents-omp/2026-11-20T13-47-13-687Z_01a1f936-1597-74de-bcd4-1336fb97ae54.jsonl")))
        assertEquals("/resume 4c979fcb-8bb6-4ef7-8623-a8909326873e",
            SessionControls.forAgent("copilot")!!.resume(s("/h/.copilot/session-state/4c979fcb-8bb6-4ef7-8623-a8909326873e/events.jsonl")))
        assertEquals("/resume local-0a90b0f4", SessionControls.forAgent("hermes")!!.resume(PastSession("local-0a90b0f4", "/h/.hermes/state.db", 0, null, 0)))
        val pi = SessionControls.forAgent("pi")!!
        assertEquals("/new", pi.newCommand)
        assertNull(pi.resume(s("/h/.pi/agent/sessions/--x--/2026_01a1.jsonl")))
        assertEquals("/resume", pi.picker)
        val agy = SessionControls.forAgent("agy")!!
        assertEquals("/new", agy.newCommand)
        assertEquals("/resume", agy.picker)
        for (a in listOf("pi", "omp", "copilot", "agy", "hermes")) assertNotNull(a, SessionControls.forAgent(a))
        assertNull(SessionControls.forAgent("hermes")!!.resume(PastSession("x; rm", null, 0, null, 0)))
    }
}
