package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

/** 2.2.7 (Bill: "show the whole question"): Oh My Pi's boxed multi-question Ask dialog on the card. */
class OmpAsk227Test {
    private fun screen(name: String) = checkNotNull(javaClass.getResource("/screens/omp/live-2026-11-24-ask/$name")) { name }.readText()

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
        val p = checkNotNull(detectPrompt(screen("ask-submit.ansi"), true, "omp"))
        assertEquals("Submit all answers?", p.question)
        assertEquals(listOf(OmpAsk.SUBMIT_LABEL, OmpAsk.CANCEL_LABEL), p.choices.map { it.label })
        assertTrue(p.ompAsk!!.onSubmit && p.ompAsk!!.allAnswered)
        assertTrue(p.shownParts().blocks.any { it.text == "✓ 3. project-x builds (2.1 GiB): 7 local commits are on no remote: Other: look at the…" })
        assertTrue(p.shownParts().tabs.last().current)
    }

    @Test fun aDialogNotAllOnScreenIsLeftToTheOtherReaders() {
        val cut = screen("ask-open-other.ansi").lines().filterNot { it.trimStart().startsWith("╰─────") }.joinToString("\n")
        assertNull(OmpAsk.parse(stripAnsi(cut)))
        assertNull("another agent's screen", OmpAsk.prompt("❯ 1. Yes\n  2. No"))
    }
}
