package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.1.1 (Bill: "continue to improve chat view for antigravity especially when rendering its meta
 * output (which lets its glyphs through to chat view currently)"). The fixture is the agy pane's
 * own screen, read with `herdr pane read --source recent --lines 200` on 2026-11-20 (paths and
 * IDs sanitized). Before this change its tool-call headers, "… N more actions …" tallies, the
 * notice box, the warning, the titled rule and the composer with its status bar all came through
 * as chat prose with their glyphs.
 */
class AgyChatTest {
    private fun resource(path: String) = checkNotNull(javaClass.getResource(path)).readText()
    private val live by lazy { resource("/screens/agy/live-2026-11-20/idle-after-work.txt") }

    /** The pieces as text, one per line, so a whole screen can be compared with a saved one. */
    private fun dump(segs: List<Seg>): String = segs.joinToString("\n") { s ->
        when (s) {
            Seg.Gap -> ""
            Seg.Rule -> "---"
            is Seg.You -> "you: ${s.text}"
            is Seg.Para -> "para[${s.indent}${s.mark?.let { ",$it" } ?: ""}${if (s.detail) ",detail" else ""}]: ${s.text}"
            is Seg.Code -> "code:\n" + s.lines.joinToString("\n") { "  | $it" }
            is Seg.Activity -> "activity: ${s.summary}\n" + s.steps.joinToString("\n") { st ->
                "  - ${st.kind} ${st.title}${st.detail?.let { " ($it)" } ?: ""}${if (st.failed > 0) " [failed ${st.failed}]" else ""}${if (st.running) " [running]" else ""}"
            }
            is Seg.Footer -> "footer: " + s.lines.joinToString(" | ")
        }
    }

    private val leaks = Regex("""^[●○◦…┃⚠⎿⠀-⣿◐◓◑◒]|[-]|[\uDB80-\uDBFF]""")

    @Test fun liveScreenMatchesItsSavedChat() {
        val got = dump(Reflow.parse(live, antigravity = true))
        val want = javaClass.getResource("/screens/agy/live-2026-11-20/idle-after-work.chat")?.readText()
        if (want == null) {
            File("build/agy-chat-dump.txt").writeText(got)
            error("no saved chat; wrote build/agy-chat-dump.txt")
        }
        assertEquals(want.trimEnd(), got.trimEnd())
    }

    @Test fun liveScreenProseHasNoMetaGlyphs() {
        val segs = Reflow.parse(live, antigravity = true)
        val texts = segs.filterIsInstance<Seg.Para>().map { it.text } + segs.filterIsInstance<Seg.You>().map { it.text }
        texts.forEach { assertFalse(it, leaks.containsMatchIn(it)) }
        // Every tool and thinking header became a step, none stayed prose.
        assertTrue(texts.none { it.startsWith("Ran ") || it.startsWith("Explored ") || it.startsWith("Thought for ") || "more actions" in it })
        // Four runs of work, each its own chip: four tool headers and 134 folded actions in the first.
        val chips = segs.filterIsInstance<Seg.Activity>()
        assertEquals(4, chips.size)
        assertEquals("Thought for 15m 39s · 138 actions · 11 failed", chips[0].summary)
        assertEquals(AgyMeta.Kind.MORE, chips[0].steps[3].kind)
        assertEquals("(11 reads, 11 edits, 112 commands: 9 failed)", "(" + chips[0].steps[3].detail + ")")
        // The composer and its status bar are the footer, not chat: only the model name is left.
        assertEquals(Seg.Footer(listOf("Orchestrator (Neon)")), segs.last())
        // The notice box keeps its words and loses its edge and key hint.
        assertTrue(segs.contains(Seg.Para(0, null, "Auto is now the default agent mode", detail = true)))
        assertTrue(segs.none { it is Seg.Para && "esc to dismiss" in it.text })
        // The titled rule says what happened.
        assertEquals(2, segs.count { it == Seg.Para(0, null, "Conversation compacted", detail = true) })
        // The warning reads as one.
        assertTrue(segs.any { it is Seg.Para && it.text.startsWith("Warning: Individual quota reached.") })
    }

    @Test fun liveScreenKeepsTheRepliesWordForWord() {
        val segs = Reflow.parse(live, antigravity = true)
        val paras = segs.filterIsInstance<Seg.Para>().map { it.text }
        assertTrue(paras.contains("Acknowledged: stopping the kanban-32748 herdrdroid-2.1.0 lane now, leaving scratch untouched for Claude, and staying idle."))
        assertTrue(paras.contains("ack-agy"))
        assertTrue(paras.any { it.startsWith("Check 1 (Exact delivery on Instance Alpha w2:p1): HD210_AGY_FIXTURE_ALPHA_P1_20261120 delivered") })
        assertTrue(segs.filterIsInstance<Seg.You>().any { it.text.startsWith("From claude (tab: claude): Herdr routing test approved by Bill.") })
    }

    @Test fun ansiReadGivesTheSameChat() {
        val ansi = resource("/screens/agy/live-2026-11-20/idle-after-work.ansi")
        assertEquals(dump(Reflow.parse(live, antigravity = true)), dump(Reflow.parse(forPhone(ansi), antigravity = true)))
    }

    @Test fun otherAgentsDoNotGetChips() {
        assertTrue(Reflow.parse(live).none { it is Seg.Activity })
        assertTrue(Reflow.parse(live, grok = true).none { it is Seg.Activity })
    }

    @Test fun toolHeadersAndTalliesBecomeSteps() {
        assertEquals(AgyMeta.Step(AgyMeta.Kind.SHELL, "Ran", "git status"), AgyMeta.step("● Ran (git status)"))
        assertEquals(AgyMeta.Step(AgyMeta.Kind.SHELL, "Ran", "grep -R x /tmp", failed = 1),
            AgyMeta.step("● Ran (grep -R x /tmp) failed (ctrl+o to expand)"))
        assertEquals(AgyMeta.Step(AgyMeta.Kind.SHELL, "Ran 2 commands, listed tasks", "tail -n 30 log", count = 2),
            AgyMeta.step("● Ran 2 commands, listed tasks (tail -n 30 log)"))
        assertEquals(AgyMeta.Step(AgyMeta.Kind.READ, "Explored 19 files", "A.kt, B.kt, +17", count = 19),
            AgyMeta.step("● Explored 19 files (A.kt, B.kt, +17)"))
        assertEquals(AgyMeta.Step(AgyMeta.Kind.TASK, "Killed 2 tasks", "task-1, task-2", count = 2),
            AgyMeta.step("● Killed 2 tasks (task-1, task-2) (ctrl+o to expand)"))
        assertEquals(AgyMeta.Step(AgyMeta.Kind.THINK, "Thought for 6.8s", "The instruction is to stop..."),
            AgyMeta.step("○ Thought for 6.8s (The instruction is to stop...)"))
        assertEquals(AgyMeta.Step(AgyMeta.Kind.MORE, "20 more actions", "2 reads, 1 edit, 17 commands: 4 failed", count = 20, failed = 4),
            AgyMeta.step("… 20 more actions (2 reads, 1 edit, 17 commands: 4 failed) …"))
    }

    @Test fun repliesThatLookLikeHeadersStayProse() {
        for (t in listOf("● Updated the plan.", "● Ran ls: 12 files.", "● I'll run ls.", "● The symbol ● is intentional.",
            "○ Checking examples", "… and then it stopped")) {
            assertEquals(t, null, AgyMeta.step(t))
        }
        val segs = Reflow.parse("● Updated the plan.\n\n● Ran ls: 12 files.", antigravity = true)
        assertEquals(listOf("Updated the plan.", "Ran ls: 12 files."), segs.filterIsInstance<Seg.Para>().map { it.text })
    }

    @Test fun spinnerIsARunningStep() {
        val rule = "─".repeat(60)
        val screen = listOf("● Explored 2 files (a.kt, b.kt)", "⠋ Thinking… (12s · esc to cancel)", rule, ">", rule,
            "? for shortcuts" + " ".repeat(60) + "Gemini 4 Argon").joinToString("\n")
        val segs = Reflow.parse(screen, antigravity = true)
        val chip = segs.filterIsInstance<Seg.Activity>().single()
        assertTrue(chip.running)
        assertEquals("Thinking… (12s · esc to cancel)", chip.steps.last().title)
        assertEquals("1 action", chip.summary)
        assertEquals(Seg.Footer(listOf("Gemini 4 Argon")), segs.last())
    }

    @Test fun consecutiveStepsAcrossBlankLinesShareOneChip() {
        val screen = "○ Thought for 3s (Looking)\n\n● Ran (ls)\n\nDone: three files."
        val segs = Reflow.parse(screen, antigravity = true)
        assertEquals(listOf("activity", "gap", "para"), segs.map {
            when (it) { is Seg.Activity -> "activity"; Seg.Gap -> "gap"; is Seg.Para -> "para"; else -> it.toString() }
        })
        assertEquals("Thought for 3s · 1 action", (segs[0] as Seg.Activity).summary)
    }

    @Test fun privateUseGlyphsLeaveProseButNotCode() {
        val folder = "" // a Nerd Font folder icon
        val plane15 = String(Character.toChars(0xF0001))
        val screen = listOf(
            "● $folder Opened the folder $plane15 and listed it.",
            "",
            "┌──────┬──────┐",
            "│ $folder a │ b    │",
            "└──────┴──────┘",
        ).joinToString("\n")
        val segs = Reflow.parse(screen, antigravity = true)
        assertEquals("Opened the folder and listed it.", segs.filterIsInstance<Seg.Para>().single().text)
        assertEquals(listOf("┌──────┬──────┐", "│ $folder a │ b    │", "└──────┴──────┘"), segs.filterIsInstance<Seg.Code>().single().lines)
        // Other agents' prose is left as it was.
        assertTrue(Reflow.parse(screen).filterIsInstance<Seg.Para>().single().text.contains(folder))
    }

    @Test fun olderStatusBarStillEndsTheChat() {
        val rule = "─".repeat(40)
        val segs = Reflow.parse(listOf("● Done.", rule, ">", rule, "Shift+Tab to change mode").joinToString("\n"), antigravity = true)
        assertEquals(Seg.Footer(listOf("Shift+Tab to change mode")), segs.last())
        assertEquals(listOf("Done."), segs.filterIsInstance<Seg.Para>().map { it.text })
    }
}
