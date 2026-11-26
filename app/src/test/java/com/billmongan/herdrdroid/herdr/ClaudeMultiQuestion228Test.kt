package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ui.CardFormat
import org.junit.Assert.*
import org.junit.Test

/**
 * 2.2.8 (Bill: "the AWS Cloud session failed to render a question ... a one-of-four question with
 * multiple choice responses ... make that more robust"): Claude Code's multi-question dialog, on
 * neutral screens with the live dialog's layout and styling.
 */
class ClaudeMultiQuestion228Test {
    private fun screen(name: String) = checkNotNull(javaClass.getResource("/screens/claude/synthetic-2.2.8-multiq/$name")) { name }.readText()
    private val all = listOf("four-tabs-first.ansi", "four-tabs-second.ansi", "four-tabs-type.ansi", "three-tabs-three-options.ansi",
        "two-tabs-unanswered.ansi", "four-tabs-long.ansi")

    @Test fun everyVariantShowsTheWholeQuestionItsOptionsAndTabs() {
        for (name in all) {
            val s = screen(name)
            val p = checkNotNull(detectPrompt(s, blocked = true, agent = "claude")) { name }.forCard()
            assertEquals(name, "Which step should run first in the sample build?", p.shownParts().heading)
            val text = p.shownParts().blocks.joinToString(" ") { it.text }
            assertTrue("$name: $text", text.contains("newer notes say the tests run first and the lint step runs last."))
            if (name == "four-tabs-long.ansi") assertTrue(text.contains("the notes that describe it."))
            val labels = p.choices.map { it.label }
            assertEquals(name, "Tests first (Recommended)", labels.first())
            assertTrue(name, labels.containsAll(listOf("Lint first on request", "Type something.", "Chat about this")))
            assertEquals(name, if (name.contains("three-options") || name.contains("unanswered") || name.contains("long")) 5 else 4, labels.size)
            assertTrue(isTypedAnswerChoice("Type something."))
            val tabs = p.shownParts().tabs
            assertTrue(name, tabs.last().submit)
            assertEquals(name, tabs.dropLast(1).map { it.title }, when {
                name.startsWith("four") -> listOf("Build order", "Cache", "Test step", "Reports")
                name.startsWith("three") -> listOf("Build order", "Test step", "Reports")
                else -> listOf("Test step", "Reports")
            })
            assertEquals(name, tabs.dropLast(1).count { it.done }, when { name.startsWith("four") -> 2; name.startsWith("three") -> 1; else -> 0 })
            assertEquals("$name: the current tab", 1, tabs.count { it.current })
            // Every option's description shows under it on the card.
            val notes = CardFormat.optionNotes(s, p.choices)
            assertTrue("$name: $notes", notes["1"].orEmpty().contains("never a question of which one applies"))
            assertTrue(name, notes["2"].orEmpty().contains("needs a rule for which run wins"))
            if (name == "four-tabs-long.ansi") assertTrue(notes["1"].orEmpty().contains("third row for the layout check"))
            // The dialog is proven: its top and its footer, so the card shows (on two reads).
            val r = questionRead(s, p, "claude", null, true)
            assertTrue("$name topped", r.topped)
            assertTrue("$name complete", r.complete)
        }
    }

    @Test fun theHighlightIsReadOnEveryRow() {
        assertEquals("1", detectPrompt(screen("four-tabs-first.ansi"), true, "claude")!!.selected)
        assertEquals("2", detectPrompt(screen("four-tabs-second.ansi"), true, "claude")!!.selected)
        assertEquals("3", detectPrompt(screen("four-tabs-type.ansi"), true, "claude")!!.selected)
        val p = detectPrompt(screen("four-tabs-first.ansi"), true, "claude")!!
        assertEquals(listOf("down", "enter"), menuKeys(p, p.choices[1]))
        assertEquals(listOf("down", "down"), moveKeys(p, p.choices.single { it.label == "Type something." }))
    }

    /**
     * The regression shape: a recent read that has the options but not the dialog's top (its tab bar,
     * question box and rule went by in the scrollback) can't prove the dialog, so it never shows by itself.
     */
    @Test fun aRecentReadWithoutTheDialogsTopIsUnproven() {
        val lines = stripAnsi(screen("four-tabs-first.ansi")).replace("\r", "").lines()
        val cut = (listOf("❯ go ahead and ask the remaining review questions") + lines.dropWhile { !it.startsWith("❯ 1.") }).joinToString("\n")
        val p = detectPrompt(cut, true, "claude")?.forCard()
        assertTrue("no card, or one that can't be proven", p == null || p.choices.isEmpty() || !questionRead(cut, p, "claude", null, true).topped)
    }

    @Test fun optionNotesReadStyledAndCarriageReturnRows() {
        val s = screen("four-tabs-first.ansi")
        val p = checkNotNull(detectPrompt(s, true, "claude")).forCard()
        assertEquals(CardFormat.optionNotes(stripAnsi(s).replace("\r", ""), p.choices), CardFormat.optionNotes(s, p.choices))
        assertTrue(CardFormat.optionNotes(s, p.choices).isNotEmpty())
    }
}
