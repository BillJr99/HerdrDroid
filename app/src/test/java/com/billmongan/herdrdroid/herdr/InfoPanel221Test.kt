package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.1 (Bill, 2026-11-22: "also grok had a status pane open so make sure that 2.2.1 herdrdroid
 * clears that too", "(for all panes)", "try to be robust to harness version changes").
 *
 * Fixtures: Grok's screen right after its status panel was closed (screens/grok/live-2026-11-22-closed,
 * chat replaced with neutral text), Grok 1.0.92's /status ("Session info") box built from its bundled
 * strings over that screen (screens/grok/synthetic-2.2.1), the existing Kiro and Grok usage captures,
 * and synthetic variants that change wording, rows and box style.
 */
class InfoPanel221Test {
    private fun res(path: String) = checkNotNull(javaClass.getResource(path)) { path }.readText()

    private val grokClosed = res("/screens/grok/live-2026-11-22-closed/closed.txt")
    private val grokStatus = res("/screens/grok/synthetic-2.2.1/session-info.txt")
    private val grokUsage = res("/screens/grok/reconstructed-1.0.92/usage-dialog.txt")
    private val kiroUsage = res("/screens/kiro/live-2026-11-22-usage/usage-open.ansi")
    private val kiroAfter = res("/screens/kiro/live-2026-11-22-usage/after-restart.ansi")

    private fun known(agent: String, screen: String): InfoPanel {
        val p = checkNotNull(infoPanelOn(agent, screen)) { "no panel for $agent" }
        assertTrue("$agent: ${p.kind} not known: ${p.why}", p.known)
        return p
    }

    private fun notKnown(agent: String, screen: String) {
        val p = infoPanelOn(agent, screen)
        assertTrue("$agent: must not be a known panel: $p", p == null || !p.known)
        assertNull(dismissibleUsageFrame(agent, screen))
    }

    // ---- Grok's status panel (the one left open on Bill's pane) --------------------------------

    @Test fun grokSessionInfoIsAKnownPanelAndHoldsSends() {
        assertNull("the exact 2.1.4 matcher never saw it", exactUsageFrame("grok", grokStatus))
        val p = known("grok", grokStatus)
        assertEquals(PanelKind.STATUS, p.kind)
        assertEquals("esc", p.closeKey)
        assertNotNull(dismissibleUsageFrame("grok", grokStatus))
        assertTrue(usageScreenShown("grok", grokStatus))
        assertNull("not a question card", detectPrompt(grokStatus, false, "grok"))
        assertTrue(panelStateOf("grok", "occ", grokStatus) is PanelState.Open)
    }

    @Test fun grokClosedScreenWithItsDashboardChipIsNotAPanel() {
        assertTrue(grokClosed.contains("[Dashboard]"))
        assertNull(infoPanelOn("grok", grokClosed))
        assertFalse(usageScreenShown("grok", grokClosed))
        assertNull(dismissibleUsageFrame("grok", grokClosed))
        assertEquals(PanelState.Closed, panelStateOf("grok", "occ", grokClosed))
    }

    @Test fun grokStatusVariantsStillRead() {
        val footer = "↑/↓ scroll  |  c copy session ID  |  y copy all  |  Esc close"
        fun variant(f: (String) -> String) = f(grokStatus)
        val variants = mapOf(
            "reworded close" to variant { it.replace("Esc close", "Esc to close") },
            "lowercase close" to variant { it.replace("Esc close", "esc close") },
            "dot separators" to variant { it.replace(footer, "↑/↓ scroll · c copy session ID · y copy all · Esc close" + " ".repeat(footer.length - 56)) },
            "fewer footer items" to variant { it.replace(footer, "c copy  |  Esc close" + " ".repeat(footer.length - 20)) },
            "title renamed" to variant { it.replace("Session info", "Status      ") },
            "missing rows" to variant { s -> s.lines().filterNot { it.contains("Auth ") || it.contains("Turns ") }.joinToString("\n") },
            "extra rows" to variant { it.replace("│ click or drag to copy", "│ Version      9.9.9        \n  │ click or drag to copy".let { r -> r }) },
            "heavy box" to variant { it.replace('┌', '┏').replace('┐', '┓').replace('└', '┗').replace('┘', '┛').replace("  │ ", "  ┃ ").replace(" │\n", " ┃\n").replace('─', '━') },
            "rounded box" to variant { it.replace('┌', '╭').replace('┐', '╮').replace('└', '╰').replace('┘', '╯') },
            "box right of chat" to variant { s -> s.lines().joinToString("\n") { row ->
                val t = row.trimStart()
                if (t.firstOrNull() in setOf('┌', '│', '└') && !row.contains('❯')) "   chat text left of the box " + t else row } },
        )
        for ((name, s) in variants) {
            assertTrue("$name changed nothing", s != grokStatus)
            val p = checkNotNull(infoPanelOn("grok", s)) { name }
            assertTrue("$name: ${p.why}", p.known)
        }
    }

    @Test fun grokUsageDialogWithAChangedFooterIsStillKnown() {
        val changed = grokUsage.replace("Tab switch  |  ↑/↓ scroll  |  c copy session ID  |  Esc close",
            "Tab tabs  |  ↑/↓ scroll  |  Esc close                           ")
        assertNull(exactUsageFrame("grok", changed))
        assertEquals(PanelKind.USAGE, known("grok", changed).kind)
        // The original still reads through the exact matcher.
        assertNotNull(exactUsageFrame("grok", grokUsage))
        assertTrue(known("grok", grokUsage).known)
    }

    @Test fun grokDraftUnderThePanelHoldsButIsNeverClosed() {
        val draft = grokStatus.replace("│ ❯" + " ".repeat(126) + "│", "│ ❯ half-typed draft" + " ".repeat(108) + "│")
        assertTrue(draft != grokStatus)
        val p = checkNotNull(infoPanelOn("grok", draft))
        assertFalse(p.known)
        assertTrue(p.why!!.contains("typed"))
        assertNull(dismissibleUsageFrame("grok", draft))
        assertTrue(usageScreenShown("grok", draft))
        assertTrue(panelStateOf("grok", "occ", draft) is PanelState.Stop)
    }

    @Test fun grokDashboardOnlyHolds() {
        val dash = grokStatus.replace("Session info", "Agent Dashboard").replace("Esc close", "Esc back ")
        val p = checkNotNull(infoPanelOn("grok", dash))
        assertEquals(PanelKind.DASHBOARD, p.kind)
        assertFalse(p.known)
        assertTrue(usageScreenShown("grok", dash))
    }

    // ---- Kiro ---------------------------------------------------------------------------------

    @Test fun kiroUsageAndItsVariants() {
        assertEquals(PanelKind.USAGE, known("kiro", kiroUsage).kind)
        val plain = stripAnsi(kiroUsage)
        // Reworded and missing rows: not the exact layout any more, still the panel.
        val reworded = plain.replace("Estimated Usage", "Estimated usage this month").replace(Regex("""Credits \(.*\)"""), "Plan credits")
        assertNull(exactUsageFrame("kiro", reworded))
        assertEquals(PanelKind.USAGE, known("kiro", reworded).kind)
        val context = plain.replace("/usage", "/context").replace("Tab to switch to /context", "Tab to switch to /usage")
        assertEquals(PanelKind.CONTEXT, known("kiro", context).kind)
        // The composer after a restart is not a panel.
        assertNull(infoPanelOn("kiro", kiroAfter))
        assertEquals(PanelState.Closed, panelStateOf("kiro", "occ", kiroAfter))
    }

    // ---- Claude Code, and every agent ---------------------------------------------------------

    private val claudeStatus = """
  neutral sample text of an earlier reply kept for its shape only.

────────────────────────────────────────────────────────────────────────────────
 Settings:  Status   Config   Usage   Stats

 Version: 9.9.9
 Session ID: 00000000-0000-0000-0000-000000000000
 cwd: /home/user/work/sample
 Model: Sample model

 Esc to cancel
""".trimEnd('\n') + "\n"

    @Test fun claudeSettingsStatusTabIsKnown() {
        assertEquals(PanelKind.SETTINGS, known("claude", claudeStatus).kind)
        assertEquals(PanelKind.SETTINGS, known("claude", claudeStatus.replace("Esc to cancel", "esc to close")).kind)
        assertEquals(PanelKind.SETTINGS, known("claude", claudeStatus.replace(" Model: Sample model\n", "")).kind)
        // "esc again to quit": a second Esc could quit the agent, so only a hold.
        val quit = claudeStatus.replace("Esc to cancel", "esc to close · esc again to quit")
        assertFalse(checkNotNull(infoPanelOn("claude", quit)).known)
    }

    /** One neutral boxed status panel over an empty composer, for any agent. */
    private fun boxed(title: String, footer: String, composer: String = "│ ❯                                                                    │") = buildString {
        appendLine("  neutral sample text of an earlier reply kept for its shape only.")
        appendLine()
        appendLine("  ┌──────────────────────────────────────────────────────────────────────┐")
        appendLine("  │ ${title.padEnd(68)} │")
        appendLine("  │ ${"".padEnd(68)} │")
        appendLine("  │ ${"Model      sample".padEnd(68)} │")
        appendLine("  │ ${"Context    12k / 200k".padEnd(68)} │")
        appendLine("  │ ${footer.padEnd(68)} │")
        appendLine("  └──────────────────────────────────────────────────────────────────────┘")
        appendLine()
        appendLine("  ╭──────────────────────────────────────────────────────────────────────╮")
        appendLine("  $composer")
        appendLine("  ╰──────────────────────────────────────────────────────────────────────╯")
    }

    @Test fun everyAgentReadsAStatusPanelAndOnlyVerifiedOnesMayClose() {
        val agents = listOf("claude", "codex", "opencode", "omp", "pi", "hermes", "grok", "copilot", "agy", "kiro", "vibe", "muse")
        for (agent in agents) {
            for (footer in listOf("Esc close", "esc to close", "↑/↓ scroll · Esc to go back", "esc:close")) {
                val p = checkNotNull(infoPanelOn(agent, boxed("Status", footer))) { "$agent $footer" }
                assertEquals("$agent $footer", PanelKind.STATUS, p.kind)
                assertEquals("$agent $footer: ${p.why}", PanelKind.STATUS in PANEL_ESC_VERIFIED[canonicalAgent(agent)].orEmpty(), p.known)
                assertTrue(usageScreenShown(agent, boxed("Status", footer)))
            }
        }
        for (agent in listOf("hermes", "copilot", "muse")) assertFalse(agent, infoPanelOn(agent, boxed("Status", "Esc close"))!!.known)
        for ((title, kind) in listOf("/usage" to PanelKind.USAGE, "Context window" to PanelKind.CONTEXT, "Help" to PanelKind.HELP,
                "Keyboard Shortcuts" to PanelKind.SHORTCUTS, "Cost" to PanelKind.COST)) {
            assertEquals(title, kind, infoPanelOn("grok", boxed(title, "Esc close"))?.kind)
        }
    }

    @Test fun pickersFormsAndSearchFieldsOnlyHold() {
        for (footer in listOf("Enter to confirm · Esc to exit", "enter select  esc cancel", "Type to search · Esc to cancel")) {
            val p = infoPanelOn("claude", boxed("Select model", footer))
            assertTrue("$footer: $p", p == null || !p.known)
            assertNull(dismissibleUsageFrame("claude", boxed("Select model", footer)))
        }
        val numbered = boxed("Status", "Esc close").replace("Model      sample     ", "1. Model sample        ")
        assertFalse(checkNotNull(infoPanelOn("grok", numbered)).known)
        // A panel with no title the app knows only holds.
        val untitled = infoPanelOn("grok", boxed("Sample", "Esc close"))
        assertFalse(checkNotNull(untitled).known)
    }

    // ---- what is never a panel ----------------------------------------------------------------

    @Test fun questionsAndApprovalsAreNeverPanels() {
        for (path in listOf(
            "/screens/claude/captured-2.1.329-auto-approval/approval.txt",
            "/screens/claude/captured-2.1.329/single-question.txt",
            "/screens/claude/captured-2026-11-13/bash-permission.txt",
            "/screens/codex/captured-2026-11-13/run-command.txt",
            "/screens/codex/captured-2026-11-14/edit-approval.txt",
            "/screens/copilot/captured-2026-11-13/run-command.txt",
            "/screens/grok/captured-2026-11-15/python-approval.txt",
            "/screens/grok/derived-1.4.9/0306-plan-approval.txt",
            "/screens/opencode/live-2026-11-21/permission-shell-2.3.2.txt",
        )) {
            val agent = path.split('/')[2]
            notKnown(agent, res(path))
        }
        val ask = boxed("Status", "Esc close").replace("Context    12k / 200k ", "Do you want to proceed?")
        notKnown("grok", ask)
    }

    @Test fun theComposerErrorsAndStatusWordsInChatAreNotPanels() {
        // Composers.
        for (path in listOf("/screens/grok/live-2026-11-20-composer/empty.ansi", "/screens/claude/live-2026-11-20-composer",
                "/screens/codex/live-2026-11-20-composer")) {
            val text = runCatching { res(path) }.getOrNull() ?: continue
            assertNull(path, infoPanelOn(path.split('/')[2], text))
        }
        // An error that mentions Esc in a sentence.
        val error = """
  neutral sample text of an earlier reply.
  Image was too large. Double press esc to go back and try again with a smaller image.

──────────────────────────────────────────────
❯
──────────────────────────────────────────────
  ? for shortcuts
"""
        assertNull(infoPanelOn("claude", error))
        // Status words in normal output, and a stray hint quoted in chat with the chat going on under it.
        val chat = """
  Status: all sample checks pass
  Usage
  Esc to close
  neutral sample text that keeps going under the quoted hint.
  more neutral sample text.
  and more neutral sample text.
  and still more neutral sample text.

──────────────────────────────────────────────
❯
──────────────────────────────────────────────
"""
        for (agent in listOf("claude", "codex", "grok", "kiro")) {
            assertNull(agent, infoPanelOn(agent, chat))
            assertFalse(agent, usageScreenShown(agent, chat))
        }
        // Codex prints /status into the transcript: a box with no close hint, the composer under it.
        val codexStatus = """
╭──────────────────────────────────────────────╮
│  >_ Sample CLI (v0.0.0)                      │
│                                              │
│  Model:      sample                          │
│  Status:     ready                           │
╰──────────────────────────────────────────────╯

›
  ? for shortcuts
"""
        assertNull(infoPanelOn("codex", codexStatus))
        // A working agent's own hint.
        assertNull(infoPanelOn("claude", "  ✻ Sampling… (esc to interrupt)\n\n❯ \n"))
    }

    // ---- the close flow -----------------------------------------------------------------------

    private fun flow(states: List<PanelState>, sendOk: Boolean = true): Pair<PanelCloseOutcome, Int> = runBlocking {
        var i = 0
        var sent = 0
        val out = closePanelAndVerify(
            stillCurrent = { true },
            readState = { states[i.coerceAtMost(states.size - 1)].also { i++ } },
            sendEscape = { sent++; sendOk },
            settle = {},
        )
        out to sent
    }

    private val open = PanelState.Open(UsageOverlay("occ", "panel"))

    @Test fun oneEscThenConfirmedClosed() {
        val (out, sent) = flow(listOf(open, PanelState.Closed))
        assertTrue(out.closed)
        assertEquals(1, out.escapes)
        assertEquals(1, sent)
    }

    @Test fun aChangedFrameStopsBeforeAnyKey() {
        val (out, _) = flow(listOf(open, PanelState.Closed), sendOk = false)
        assertFalse(out.closed)
        assertEquals(0, out.escapes)
        assertTrue(out.why!!.contains("changed"))
    }

    @Test fun aQuestionAppearingStops() {
        val (out, sent) = flow(listOf(open, PanelState.Stop("a question is open"), open))
        assertFalse(out.closed)
        assertEquals(1, sent)
        assertEquals("a question is open", out.why)
    }

    @Test fun atMostTwoEscThenTheCard() {
        val (out, sent) = flow(listOf(open, open, open, open))
        assertFalse(out.closed)
        assertEquals(2, sent)
        assertTrue(out.why!!.contains("still open"))
    }

    @Test fun unreadableTwiceStops() {
        val (out, sent) = flow(listOf(PanelState.Unknown, PanelState.Unknown, open))
        assertFalse(out.closed)
        assertEquals(0, sent)
    }

    @Test fun theLeavingPaneStopsTheFlow() = runBlocking {
        var current = true
        val out = closePanelAndVerify(
            stillCurrent = { current },
            readState = { open },
            sendEscape = { current = false; true },
            settle = {},
        )
        assertFalse(out.closed)
        assertEquals(1, out.escapes)
    }

    @Test fun liveReadsClassifyForTheFlow() {
        assertTrue(panelStateOf("grok", "occ", grokStatus) is PanelState.Open)
        assertEquals(PanelState.Closed, panelStateOf("grok", "occ", grokClosed))
        assertEquals(PanelState.Unknown, panelStateOf("grok", "occ", ""))
        val approval = res("/screens/claude/captured-2.1.329-auto-approval/approval.txt")
        assertTrue(panelStateOf("claude", "occ", approval) is PanelState.Stop)
    }
}
