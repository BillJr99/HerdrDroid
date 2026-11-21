package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.1.1 leftovers (Bill: "do the pending work in 2.1.1 all of it so that nothing is deferred to
 * 2.1.2").
 *
 * - Claude's question bar marks the current tab only by colour. The styled screens are real
 *   captures of Claude Code 2.1.329 (fullscreen, 134x48, tmux capture-pane -e) rendered against a
 *   mock API in a throwaway container on 2026-11-20, with neutral question text: the first
 *   question, Right once and twice without answering, Left back, the first answered, Left back to
 *   the answered one, and the Submit tab.
 * - The pane list's blocked check reads far enough to pass blank rows under a fullscreen dialog,
 *   and a sessions list with a session awaiting input needs you.
 * - Antigravity's usage panel ("└ Models & Quota") is one quiet line in Chat. That fixture is
 *   CONSTRUCTED, not captured (see its .expect file).
 */
class Leftovers211Test {
    private fun resource(path: String) = checkNotNull(javaClass.getResource(path)) { path }.readText()
    private fun styled(name: String) = resource("/screens/claude/captured-2.1.329/styled/$name.ansi")
    private val esc = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    /** The same screen read without styles, as herdr-core or an older herdr gives it. */
    private fun plain(name: String) = esc.replace(styled(name), "")

    private fun tabs(text: String): List<QTab> =
        checkNotNull(detectPrompt(text, blocked = false, agent = "claude")) { "no card" }.shownParts().tabs

    private fun currentOf(text: String) = tabs(text).single { it.current }.title

    @Test fun theStyledBarNamesTheCurrentTab() {
        assertEquals("Backup", currentOf(styled("mq-first")))
        assertEquals("Restart", currentOf(styled("mq-moved-right1")))
        assertEquals("Follow-up", currentOf(styled("mq-moved-right2")))
        assertEquals("Restart", currentOf(styled("mq-moved-back1")))
        assertEquals("Restart", currentOf(styled("mq-answered1")))
        // Back on an answered question: the old rule said the first unanswered one (Restart).
        assertEquals("Backup", currentOf(styled("mq-answered-back")))
        assertEquals("Submit", currentOf(styled("mq-submit-tab")))
    }

    @Test fun theStepFollowsTheStyledTab() {
        val p = detectPrompt(styled("mq-moved-right2"), blocked = false, agent = "claude")!!
        assertEquals("Question 3 of 3", p.shownParts().step)
        assertTrue(p.shownParts().heading.orEmpty().startsWith("Should I send a short summary"))
        val review = detectPrompt(styled("mq-submit-tab"), blocked = false, agent = "claude")!!
        assertEquals("Review and submit (3 questions)", review.shownParts().step)
        val back = detectPrompt(styled("mq-answered-back"), blocked = false, agent = "claude")!!
        assertEquals("Question 1 of 3", back.shownParts().step)
    }

    @Test fun aPlainReadFallsBackToTheFirstUnansweredTab() {
        // Without styles nothing marks the tab, so the 2.1.0 rule stands.
        assertEquals("Backup", currentOf(plain("mq-moved-right2")))
        assertEquals("Restart", currentOf(plain("mq-answered-back")))
        assertEquals("Backup", currentOf(resource("/screens/claude/captured-2.1.329/multi-question-second-moved.txt")))
    }

    @Test fun theMarkIsInvisibleAndOnlyOnTheBar() {
        val text = stripAnsi(styled("mq-moved-right1"))
        val marked = text.lines().filter { TAB_CURRENT_MARK in it }
        assertEquals(1, marked.size)
        assertTrue(marked.single().contains("$TAB_CURRENT_MARK☐ Restart"))
        // Without the mark, the text is the plain read exactly.
        assertEquals(plain("mq-moved-right1"), text.replace(TAB_CURRENT_MARK.toString(), ""))
        // forPhone (the Terminal view and notifications) keeps it too, and it parses the same.
        assertEquals("Restart", currentOf(forPhone(styled("mq-moved-right1"))))
    }

    @Test fun theTabTitlesCarryNoMark() {
        tabs(styled("mq-moved-right1")).forEach { assertFalse(it.title, TAB_CURRENT_MARK in it.title) }
        assertEquals(listOf("Backup", "Restart", "Follow-up", "Submit"), tabs(styled("mq-moved-right1")).map { it.title })
    }

    @Test fun aBarWhollyOnABackgroundSinglesOutNothing() {
        val row = "\u001B[48;5;236m←  ☐ Backup  ☐ Restart  ✔ Submit  →\u001B[49m"
        assertNull(markCurrentTab(row))
        val two = "←  \u001B[48;5;153m ☐ Backup \u001B[49m\u001B[48;5;153m ☐ Restart \u001B[49m ✔ Submit  →"
        assertNull(markCurrentTab(two))
        assertEquals("Backup", parseTabBar(stripAnsi(two))!!.first.single { it.current }.title)
    }

    @Test fun aMovedTabIsAnotherCard() {
        val first = detectPrompt(styled("mq-first"), blocked = false, agent = "claude")!!.forCard()
        val moved = detectPrompt(styled("mq-moved-right1"), blocked = false, agent = "claude")
        assertFalse(cardAnswerStillFits(first, moved))
        val again = detectPrompt(styled("mq-moved-back1"), blocked = false, agent = "claude")
        assertTrue(cardAnswerStillFits(moved!!.forCard(), again))
    }

    @Test fun rowsWithoutABarComeOutAsBefore() {
        val s = "\u001B[1mhello\u001B[0m ← and → are arrows\n\u001B[2mfaint\u001B[0m"
        assertEquals("hello ← and → are arrows\nfaint", stripAnsi(s))
        assertEquals("no escapes", stripAnsi("no escapes"))
    }

    // Item 2: the pane list's blocked check.

    @Test fun aDialogAboveManyBlankRowsStillNeedsYou() {
        val dialog = resource("/screens/claude/captured-2.1.329/multi-question-short-chat.txt").trimEnd()
        val screen = dialog + "\n".repeat(20)
        // What a 12-row read saw: only blank rows.
        assertNull(claudeNeedsYouReason(screen.lines().takeLast(12).joinToString("\n")))
        assertTrue(CLAUDE_STATUS_READ_LINES >= 48)
        assertEquals("a question dialog is open", claudeNeedsYouReason(screen.lines().takeLast(CLAUDE_STATUS_READ_LINES).joinToString("\n")))
    }

    @Test fun theSessionsListWithASessionAwaitingInputNeedsYou() {
        val list = resource("/screens/claude/captured-2.1.329/background-awaiting-input.txt")
        assertEquals(1, claudeSessionsAwaiting(list))
        assertEquals("Claude's sessions list shows a session awaiting input", claudeNeedsYouReason(list))
        assertEquals(2, claudeSessionsAwaiting(list.replace("1 awaiting input", "2 awaiting input")))
        val none = list.replace("1 awaiting input", "")
        assertEquals(0, claudeSessionsAwaiting(none))
        assertNull(claudeNeedsYouReason(none))
    }

    @Test fun anOrdinaryClaudeScreenDoesNotNeedYou() {
        val idle = "❯ Please ask me the questions.\n\n● Done.\n\n" + "─".repeat(60) + "\n❯ \n" + "─".repeat(60) +
            "\n  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents\n"
        assertNull(claudeNeedsYouReason(idle))
        assertEquals(0, claudeSessionsAwaiting(idle))
    }

    // Item 3: agy's usage panel.

    private val usage by lazy { resource("/screens/agy/constructed-2.1.1/usage-open.txt") }

    @Test fun agyUsagePanelIsOneQuietLine() {
        val segs = Reflow.parse(usage, antigravity = true)
        val quiet = segs.filterIsInstance<Seg.Para>().filter { it.detail && it.text.startsWith("Models & Quota") }
        assertEquals(1, quiet.size)
        assertEquals("Models & Quota is open on the computer (Esc there closes it): " +
            "Gemini Week 98% left · Gemini 5h 99% left · Claude/GPT Week 100% left", quiet.single().text)
        // None of the panel's rows is prose, a table or a message, and the account never shows.
        val texts = segs.flatMap { s ->
            when (s) {
                is Seg.Para -> listOf(s.text)
                is Seg.You -> listOf(s.text)
                is Seg.Code -> s.lines
                is Seg.Footer -> s.lines
                else -> emptyList()
            }
        }
        assertTrue(texts.none { "Account" in it || "someone@" in it || "GEMINI MODELS" in it || "█" in it || "esc to close" in it })
        assertTrue(segs.none { it is Seg.Code || it is Seg.You })
        // The reply stays; the panel's line comes last, and the empty composer (whose status bar
        // the panel hides) leaves no footer and no "you" bubble.
        assertTrue(segs.any { it is Seg.Para && it.text == "ack-agy" })
        assertEquals(quiet.single(), segs.last())
        assertTrue(segs.none { it is Seg.Footer })
    }

    @Test fun agyUsagePanelLeftAboveTheComposerIsQuietToo() {
        val lines = usage.lines()
        val head = lines.indexOfFirst { it.startsWith("└ Models & Quota") }
        val panel = lines.subList(head, lines.size).joinToString("\n").trimEnd()
        val rule = "─".repeat(137)
        val screen = panel + "\n\n  Done.\n\n" + rule + "\n>\n" + rule + "\n? for shortcuts" + " ".repeat(90) + "Orchestrator (Neon)\n"
        val segs = Reflow.parse(screen, antigravity = true)
        val quiet = segs.filterIsInstance<Seg.Para>().filter { it.detail && it.text.startsWith("Models & Quota") }
        assertEquals(1, quiet.size)
        assertTrue(quiet.single().text.startsWith("Models & Quota: Gemini Week"))
        assertTrue(segs.any { it is Seg.Para && it.text == "Done." })
        assertTrue(segs.none { it is Seg.Code })
        assertEquals(Seg.Footer(listOf("Orchestrator (Neon)")), segs.last())
    }

    @Test fun agyUsagePanelIsNoCard() {
        assertNull(detectPrompt(usage, blocked = false, agent = "agy"))
        assertNull(detectPrompt(usage, blocked = true, agent = "agy")?.takeIf { it.choices.isNotEmpty() })
    }

    @Test fun otherAgentsKeepThePanelText() {
        val segs = Reflow.parse(usage)
        assertTrue(segs.filterIsInstance<Seg.Para>().none { it.text.startsWith("Models & Quota is open") })
        assertNotNull(segs.filterIsInstance<Seg.Para>().firstOrNull { "GEMINI MODELS" in it.text })
    }
}
