package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflowTest {
    private fun pad(s: String, to: Int) = s + " ".repeat((to - s.length).coerceAtLeast(1))

    @Test
    fun antigravityChatUsesPlainRepliesAndMutedActivityWithoutLosingContent() {
        val screen = """
            > Explain the notation

            ○ Checking examples
            ● The symbol ● in this sentence is intentional and the reply wraps
              onto the next row.

              • Keep real list bullets
            ┌───┬───┐
            │ A │ B │
            └───┴───┘
            ────────────────────────────────
            >
            ────────────────────────────────
            Shift+Tab to change mode
        """.trimIndent()
        val segs = Reflow.parse(screen, antigravity = true)
        assertEquals(Seg.You("Explain the notation"), segs.first())
        val paras = segs.filterIsInstance<Seg.Para>()
        assertEquals(Seg.Para(0, null, "Checking examples", detail = true), paras[0])
        assertEquals(Seg.Para(0, null, "The symbol ● in this sentence is intentional and the reply wraps onto the next row."), paras[1])
        assertEquals("•", paras[2].mark)
        assertEquals(listOf("┌───┬───┐", "│ A │ B │", "└───┴───┘"), segs.filterIsInstance<Seg.Code>().single().lines)
        assertEquals(listOf("Shift+Tab to change mode"), segs.filterIsInstance<Seg.Footer>().single().lines)
        assertEquals(listOf("○", "●", "•"), Reflow.parse(screen).filterIsInstance<Seg.Para>().map { it.mark })
    }

    @Test
    fun antigravityKeepsShortTablesInChatWithAndWithoutComposer() {
        val table = listOf("┌───┬───┐", "│ A │ B │", "└───┴───┘")
        val reply = listOf("● Here's the result:") + table
        val rule = "────────────────────────────────"
        val withComposer = reply + listOf(rule, "> draft text", rule, "Shift+Tab to change mode")
        for (screen in listOf(reply, withComposer)) {
            val segs = Reflow.parse(screen.joinToString("\n"), antigravity = true)
            assertEquals(table, segs.filterIsInstance<Seg.Code>().single().lines)
            assertEquals("Here's the result:", segs.filterIsInstance<Seg.Para>().single().text)
            assertEquals(if (screen === reply) emptyList() else listOf(Seg.Footer(listOf("draft text", "Shift+Tab to change mode"))),
                segs.filterIsInstance<Seg.Footer>())
        }
    }

    @Test
    fun antigravityDoesNotMistakeQuotedOutputForComposer() {
        val rule = "────────────────────────────────"
        val screen = listOf("● Example input:", rule, "> keep this text", rule).joinToString("\n")
        val segs = Reflow.parse(screen, antigravity = true)
        assertEquals(listOf(Seg.You("keep this text")), segs.filterIsInstance<Seg.You>())
        assertEquals(emptyList<Seg.Footer>(), segs.filterIsInstance<Seg.Footer>())
    }

    @Test
    fun grokScrollbarClockAndWrappedParagraph() {
        val w = 118
        val screen = listOf(
            "     ◆ Thought for 18.2s" + " ".repeat(100) + "█",
            "",
            pad("     This is a capability check only. I will look at the native read-only command surface and will not read", w) + "  8:59 AM   █",
            pad("     credentials, sign in, or infer allowance from token counts.", w) + "            █",
            "",
            pad("     • grok usage <SESSION_ID> [TURN] prints the persisted token and cost totals for one local session. That is", w) + "█",
            pad("     session usage, not subscription allowance.", w) + "█",
            pad("     • No shell command reports a rate limit.", w) + "█",
            "",
            "  ╭" + "─".repeat(60) + "╮",
            "  │ ❯" + " ".repeat(58) + "│",
            "  ╰" + "─".repeat(44) + " Grok 4.7 (high) ─╯",
            "",
            "  Shift+Tab:mode  │  Ctrl+.:shortcuts",
        ).joinToString("\n")
        val segs = Reflow.parse(screen)
        val paras = segs.filterIsInstance<Seg.Para>()
        assertEquals("◆", paras[0].mark)
        assertEquals("Thought for 18.2s", paras[0].text)
        assertEquals(
            "This is a capability check only. I will look at the native read-only command surface and will not read " +
                "credentials, sign in, or infer allowance from token counts.",
            paras[1].text,
        )
        assertEquals(0, paras[1].indent)
        assertEquals("•", paras[2].mark)
        assertTrue(paras[2].text.endsWith("That is session usage, not subscription allowance."))
        assertEquals("No shell command reports a rate limit.", paras[3].text)
        val footer = segs.last() as Seg.Footer
        assertEquals(listOf("Grok 4.7 (high)", "Shift+Tab:mode    Ctrl+.:shortcuts"), footer.lines)
        assertTrue(segs.none { it is Seg.Code })
    }

    @Test
    fun grokShortReplyKeepsTableAndSeparatesNativeComposer() {
        val table = listOf("┌───┬───┐", "│ A │ B │", "└───┴───┘")
        val reply = listOf("◆ Thought for 2.1s", "", "Here is the result:") + table
        val composer = listOf("╭────────────────────────────╮", "│ ❯ draft                   │",
            "╰────────── Grok 4.7 (high) ─╯", "Shift+Tab:mode  │  Ctrl+.:shortcuts")
        for (footer in listOf(emptyList(), composer)) {
            val segs = Reflow.parse((reply + footer).joinToString("\n"), grok = true)
            assertEquals(table, segs.filterIsInstance<Seg.Code>().single().lines)
            val paras = segs.filterIsInstance<Seg.Para>()
            assertTrue(paras.first().detail)
            assertEquals("Here is the result:", paras.last().text)
            assertEquals(if (footer.isEmpty()) emptyList() else listOf(Seg.Footer(listOf(
                "draft", "Grok 4.7 (high)", "Shift+Tab:mode    Ctrl+.:shortcuts"))),
                segs.filterIsInstance<Seg.Footer>())
        }
    }

    @Test
    fun claudeBulletsDetailsUserAndFooter() {
        val screen = listOf(
            "❯ Could we line up the arrow keys at the bottom with the other keys like control alt so that the arrows are on",
            "  the same row and next to each other.",
            "",
            "● I'll put the arrows on the Ctrl and Alt row and restyle the console so it reads more like a chat. Here is what",
            "  changes first.",
            "",
            "● Bash(herdr pane read w2:p8)",
            "  ⎿  Read 40 lines",
            "",
            "─".repeat(100) + " Demo-box2 ─",
            "❯ ",
            "─".repeat(110),
            "  ⏵⏵ auto mode on (shift+tab to cycle) · esc to interrupt",
        ).joinToString("\n")
        val segs = Reflow.parse(screen)
        val you = segs.first() as Seg.You
        assertEquals(
            "Could we line up the arrow keys at the bottom with the other keys like control alt so that the arrows are on " +
                "the same row and next to each other.",
            you.text,
        )
        val paras = segs.filterIsInstance<Seg.Para>()
        assertEquals("●", paras[0].mark)
        assertTrue(paras[0].text.endsWith("Here is what changes first."))
        assertEquals("Bash(herdr pane read w2:p8)", paras[1].text)
        assertTrue(paras[2].detail)
        assertEquals("Read 40 lines", paras[2].text)
        assertEquals(listOf("⏵⏵ auto mode on (shift+tab to cycle) · esc to interrupt"), (segs.last() as Seg.Footer).lines)
    }

    @Test
    fun codexAlignedColumnsStayAsCodeAndPromptIsFooter() {
        val screen = listOf(
            "  Model:               GPT-6.1-Sol (reasoning low)",
            "  Directory:           ~/agents/codex",
            "  Weekly limit:        [████████████████████] 100% left (resets 6:04 AM on 18 Nov)",
            "",
            "› Ask Codex to do anything",
            "",
            "  GPT-6.1-Sol default · ~/agents/codex",
            "  ← for agents · ? for shortcuts",
        ).joinToString("\n")
        val segs = Reflow.parse(screen)
        val code = segs.first() as Seg.Code
        assertEquals(3, code.lines.size)
        assertTrue(code.lines[0].startsWith("Model:"))
        val footer = segs.last() as Seg.Footer
        assertEquals("Ask Codex to do anything", footer.lines.first())
        assertTrue(segs.none { it is Seg.You })
    }

    @Test
    fun openCodeSidePanelIsDroppedAndHyphenatedWrapJoins() {
        val screen = listOf(
            "     - Dockerfile, pinned oven/bun:1.4.4-debian (digest-pinned), installs @oh-my-pi/pi-",
            "       coding-agent@18.18.1 into /opt/omp, runs as non-root bun.",
            "",
            "     Both verification runs passed with exit code 0 (all checks PASS). No model/API call        ⬖ Getting started                ✕",
            "     was made, no keys or protected paths were touched, and no containers remain.",
            "                                                                                                  OpenCode includes free models so",
            "",
            "     DONE_ISOLATION_DEMO",
            "",
            "  ┃",
            "  ┃  Build · Big Pickle OpenCode",
            "  ╹" + "▀".repeat(80),
            "  ~/agents/opencode                                           54.1K (27%)  ctrl+p commands",
        ).joinToString("\n")
        val segs = Reflow.parse(screen)
        val paras = segs.filterIsInstance<Seg.Para>()
        assertEquals("-", paras[0].mark)
        assertEquals(
            "Dockerfile, pinned oven/bun:1.4.4-debian (digest-pinned), installs @oh-my-pi/pi-coding-agent@18.18.1 into " +
                "/opt/omp, runs as non-root bun.",
            paras[0].text,
        )
        assertEquals(
            "Both verification runs passed with exit code 0 (all checks PASS). No model/API call was made, no keys or " +
                "protected paths were touched, and no containers remain.",
            paras[1].text,
        )
        assertEquals("DONE_ISOLATION_DEMO", paras[2].text)
        assertTrue(segs.none { it is Seg.Para && it.text.contains("OpenCode includes") })
        val footer = segs.last() as Seg.Footer
        assertEquals("Build · Big Pickle OpenCode", footer.lines.first())
    }

    @Test
    fun shortLinesAreNotJoined() {
        val segs = Reflow.parse("Line one\nLine two\nLine three")
        assertEquals(3, segs.count { it is Seg.Para })
    }
}
