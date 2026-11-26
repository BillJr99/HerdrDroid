package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

/** 2.2.7 (Bill: "show the whole question"): Oh My Pi's boxed multi-question Ask dialog on the card. */
class OmpAsk227Test {
    private fun screen(name: String) = checkNotNull(javaClass.getResource("/screens/omp/live-2026-11-24-ask/$name")) { name }.readText()

    @Test fun anOverflowingQuestionNumberOrHeaderIsRefused() {
        val frame = stripAnsi(screen("ask-open-other.ansi"))
        assertNull(OmpAsk.parse(frame.replace(Regex("""Ask · \d+ of"""), "Ask · 999999999999 of")))
        assertNull(OmpAsk.parse(frame.replace("3. project-x builds", "999999999999. project-x builds")))
    }

    @Test fun aFramedAskDoesNotNeedItsSyntheticCancelActionOnScreen() {
        val frame = screen("ask-open-other.ansi")
        for (agent in listOf("omp", "pi")) {
            val prompt = checkNotNull(detectPrompt(frame, true, agent))
            assertNotNull(prompt.ompAsk)
            assertTrue(prompt.choices.any { it.label == OmpAsk.CANCEL_LABEL })
            val read = questionRead(frame, prompt, agent, null, blocked = true)
            assertTrue(read.complete)
            assertTrue(read.topped)
        }
    }

    @Test fun aWrappedSelectedAnswerKeepsAllOfItsText() {
        val frame = stripAnsi(screen("ask-open-other.ansi")).lines().flatMap { row ->
            if ("◉ Leave as CHECK-FIRST" in row) {
                listOf(row.replace("Leave as CHECK-FIRST", "Leave as"), "│     CHECK-FIRST │")
            } else listOf(row)
        }.joinToString("\n")
        val prompt = checkNotNull(detectPrompt(frame, true, "omp"))
        assertEquals("Leave as CHECK-FIRST", prompt.ompAsk?.answers?.get(2))
    }

    @Test fun theOpenQuestionShowsWholeWithTheLeadAndEveryOtherQuestion() {
        for (agent in listOf("omp", "pi")) {
            val p = checkNotNull(detectPrompt(screen("ask-open-other.ansi"), blocked = true, agent = agent)) { agent }
            assertEquals("3. project-x builds (2.1 GiB): 7 local commits are on no remote", p.question)
            assertEquals(listOf(
                "Leave as CHECK-FIRST",
                "List the 7 commits and check whether the work landed on a pushed branch under other hashes (read-only)",
                "The commits are throwaway, so treat them as SAFE-TO-MOVE",
                "Other: type your own",
                OmpAsk.SUBMIT_LABEL, OmpAsk.CANCEL_LABEL,
            ), p.choices.map { it.label })
            assertEquals("the highlight is on Other", "4", p.selected)
            assertTrue(isTypedAnswerChoice(p.choices[3].label))
            val texts = p.shownParts().blocks.map { it.text }
            assertEquals(p.question, p.shownParts().heading)
            assertTrue(texts.toString(), texts.contains("The review is read-only so far: 120 items (3.5 GiB) are SAFE-TO-MOVE, 12 (1.2 GiB) are " +
                "CHECK-FIRST and 8 (0.9 GiB) are KEEP. Report: outputs/review-report.md. Moves would go into a holding folder only; nothing would be deleted."))
            assertTrue(texts.toString(), texts.contains("✓ 1. What should I do with the SAFE-TO-MOVE items?: Leave it to the rev…"))
            assertTrue(texts.contains("✓ 5. outputs/sample-cache-v1 (1.3 GiB, modified today): KEEP, it is still i…"))
            val tabs = p.shownParts().tabs
            assertEquals(6, tabs.size)
            assertEquals(listOf(true, true, false, true, true, false), tabs.map { it.done })
            assertEquals(2, tabs.indexOfFirst { it.current })
            assertTrue(tabs.last().submit)
        }
    }

    @Test fun theKeysMoveTheHighlightThenEnterAndEscCancels() {
        val p = checkNotNull(detectPrompt(screen("ask-open-other.ansi"), true, "omp"))
        assertEquals(listOf("up", "up", "up", "enter"), menuKeys(p, p.choices[0]))
        assertEquals(listOf("enter"), menuKeys(p, p.choices[3]))
        assertEquals(listOf("esc"), menuKeys(p, p.choices.single { it.label == OmpAsk.CANCEL_LABEL }))
        val first = checkNotNull(detectPrompt(screen("ask-open-first.ansi"), true, "omp"))
        assertEquals("1", first.selected)
        assertEquals("Submit only once every question is answered", null, first.choices.firstOrNull { it.keys == OmpAsk.SUBMIT })
        assertEquals(listOf("down", "down", "down"), moveKeys(first, first.choices[3]))
        assertTrue("the same dialog, its highlight moved", cardAnswerStillFits(first.forCard(),
            checkNotNull(detectPrompt(screen("ask-open-first.ansi").replace("❯ ◉ Leave", "  ◉ Leave").replace("  ○ Other", "❯ ○ Other"), true, "omp")).forCard()))
    }

    @Test fun everyQuestionAnsweredOffersSubmit() {
        // Codex's review: with a typed Other among the answers, Submit stays in the terminal.
        val typed = checkNotNull(detectPrompt(screen("ask-submit.ansi"), true, "omp"))
        assertEquals(OmpAsk.TERMINAL_SUBMIT, typed.question)
        assertEquals(listOf(OmpAsk.CANCEL_LABEL), typed.choices.map { it.label })
        val chosen = screen("ask-submit.ansi").replace("   Leave as CHECK-F…   List the 7 commit…   The commits are…   ✓ Other: look at the…",
            "   ✓ Leave as CHECK-F…   List the 7 commit…   The commits are…   Other: look at the…")
        val p = checkNotNull(detectPrompt(chosen, true, "omp"))
        assertEquals("Submit all answers?", p.question)
        assertEquals(listOf(OmpAsk.SUBMIT_LABEL, OmpAsk.CANCEL_LABEL), p.choices.map { it.label })
        assertTrue(p.ompAsk!!.verifiable)
        assertFalse(typed.ompAsk!!.verifiable)
        assertTrue(p.ompAsk!!.onSubmit && p.ompAsk!!.allAnswered)
        assertTrue(typed.shownParts().blocks.any { it.text == "✓ 3. project-x builds (2.1 GiB): 7 local commits are on no remote: Other: look at the…" })
        assertTrue(p.shownParts().tabs.last().current)
    }

    @Test fun aDialogNotAllOnScreenIsLeftToTheOtherReaders() {
        val cut = screen("ask-open-other.ansi").lines().filterNot { it.trimStart().startsWith("╰─────") }.joinToString("\n")
        assertNull(OmpAsk.parse(stripAnsi(cut)))
        assertNull("another agent's screen", OmpAsk.prompt("❯ 1. Yes\n  2. No"))
    }

    /** Codex's re-review: Submit is bound to the answers the card showed; a changed answer is another dialog state. */
    @Test fun theStateHoldsEveryAnswer() {
        val p = checkNotNull(detectPrompt(screen("ask-submit.ansi"), true, "omp")).ompAsk!!
        assertEquals(listOf("Leave it to the rev…", "Keep them SAFE-TO-M…", "Other: look at the…",
            "Leave as CHECK-FIRST", "KEEP, it is still i…"), p.answers)
        val changed = checkNotNull(detectPrompt(screen("ask-submit.ansi").replace("✓ Other: look at the…", "✓ Other: delete it al…"), true, "omp")).ompAsk!!
        assertEquals(p.questions, changed.questions)
        assertFalse(OmpAsk.sameAnswers(p, changed))
        fun st(answer: String, at: Int) = OmpAsk.State(listOf("1. q"), 1, 1, false, listOf(answer), listOf(at))
        assertTrue("a shortened answer is the same answer", OmpAsk.sameAnswers(st("Leave as CHECK-FIRST", 0), st("Leave as CHECK-F…", 0)))
        assertFalse(OmpAsk.sameAnswers(st("Leave as CHECK-FIRST", 0), st("List the 7 commit…", 1)))
        // Grok's review: two answers that share the shortened start, at different places, never match.
        assertFalse(OmpAsk.sameAnswers(st("Look at the logs first", 1), st("Look at the…", 3)))
        assertFalse("a typed Other can change past the cut: never checked as the same", OmpAsk.sameAnswers(st("Other: look at the logs first", 3), st("Other: look at the…", 3)))
        assertFalse("a missing answer is never the same", OmpAsk.sameAnswers(
            OmpAsk.State(listOf("1. q"), 1, 1, false, listOf(null), listOf(0)), OmpAsk.State(listOf("1. q"), 1, 1, false, listOf(null), listOf(0))))
        assertFalse("a missing place is never a wildcard", OmpAsk.sameAnswers(
            OmpAsk.State(listOf("1. q"), 1, 1, false, listOf("A"), listOf(null)), OmpAsk.State(listOf("1. q"), 1, 1, false, listOf("A"), listOf(2))))
        assertFalse("two shortened forms must be equal", OmpAsk.sameAnswers(st("Look at the…", 3), st("Look at it…", 3)))
        assertEquals("the place of each answer is kept", listOf(3, 0, 3, 0, 0), p.answerAt)
        val open = checkNotNull(detectPrompt(screen("ask-open-other.ansi"), true, "omp")).ompAsk!!
        assertEquals("the open question's selection counts", "Leave as CHECK-FIRST", open.answers[2])
        assertNotNull("pi while working reads the Ask dialog too", activePiQuestion(screen("ask-open-other.ansi"), null))
    }

    /** Grok's review: an old Ask box above newer output is history, not a live question (pi while working too). */
    @Test fun anAskBoxAboveNewerOutputIsNotLive() {
        val live = screen("ask-open-other.ansi")
        val bottom = live.lines().indexOfLast { stripAnsi(it).trimStart().startsWith("╰────") }
        val old = (live.lines().take(bottom + 1) + listOf("", " The answers are in; moving the safe items now.", " Done: 120 items moved.") +
            live.lines().drop(bottom + 1)).joinToString("\n")
        assertNull(OmpAsk.prompt(stripAnsi(old)))
        assertNull(activePiQuestion(old, null))
        assertNotNull("the live one still reads", OmpAsk.prompt(stripAnsi(live)))
    }

    /** Grok's review: only Oh My Pi's and pi's exact status rows may sit under a live box. */
    @Test fun onlyTheAgentsOwnStatusRowsMaySitUnderTheBox() {
        val live = stripAnsi(screen("ask-open-other.ansi"))
        assertNotNull(OmpAsk.prompt(live))
        val bottom = live.lines().indexOfLast { it.trimStart().startsWith("╰────") }
        fun under(vararg rows: String) = (live.lines().take(bottom + 1) + rows.toList()).joinToString("\n")
        assertNull("a lone path", OmpAsk.prompt(under("~/work/demo")))
        assertNull("text with a gauge in it", OmpAsk.prompt(under(" note ▶── done")))
        assertNotNull("pi's folder row over its token row", OmpAsk.prompt(under("~/work/demo", "0.0%/128k (auto)   (local) sample/model")))
        assertNotNull("Oh My Pi's rows", OmpAsk.prompt(under("  ⎋ Asking review decisions", " π > ⬢ sample/model > 📁 ~/work ▶──────45%──╎──┃──128K─", "╰─")))
    }

    /** Grok's review: pi's real token row (with its ↑/↓/R/CH stats first) under a live box. */
    @Test fun pisRealTokenRowsCountAsStatusRows() {
        val live = stripAnsi(screen("ask-open-other.ansi"))
        val bottom = live.lines().indexOfLast { it.trimStart().startsWith("╰────") }
        fun under(vararg rows: String) = (live.lines().take(bottom + 1) + rows.toList()).joinToString("\n")
        val real = stripAnsi(checkNotNull(javaClass.getResource("/screens/pi/live-2026-11-22-menus/empty.ansi")).readText()).lines()
            .first { it.contains("%/128k") }
        assertNotNull(OmpAsk.prompt(under("~/agents/demo", real)))
        assertNotNull(OmpAsk.prompt(under("~/agents/demo", "↑1k ↓2k 3.0%/128k (auto)                    (local) sample/model")))
        assertNotNull(OmpAsk.prompt(under("0.0%/128k (auto)")))
        assertNull(OmpAsk.prompt(under("~/agents/demo", "↑1k ↓2k and then some text")))
    }

    /** Grok's review: a shortened answer that is only "…" proves nothing. */
    @Test fun aBareEllipsisAnswerIsNotVerifiable() {
        fun st(answer: String, at: Int) = OmpAsk.State(listOf("1. q"), 1, 1, false, listOf(answer), listOf(at))
        assertFalse(OmpAsk.sameAnswers(st("…", 0), st("Leave as CHECK-FIRST", 0)))
        assertFalse(st("…", 0).verifiable)
    }
}
