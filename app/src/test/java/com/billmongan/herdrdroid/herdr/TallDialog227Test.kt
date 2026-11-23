package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.7: a Claude permission dialog taller than the screen (derived-2.1.330-tall/scrolled-ask, its
 * top rule, title and first command rows scrolled off) is completed from the pane's recent history,
 * when the history ends with that very screen and the rows above it are the same dialog's frame.
 */
class TallDialog227Test {
    private fun screen(path: String) = checkNotNull(javaClass.getResource("/screens/claude/$path")) { path }.readText()
    private val scrolled = screen("derived-2.1.330-tall/scrolled-ask.txt")
    private val title = " Bash command · from the general-purpose agent"
    private val description = " Print canned probe text for prompt capture test"
    // The same terminal width as the scrolled screen: its own dashed rule, and a top rule as wide.
    private val dashed = scrolled.lines().first { it.startsWith("╌") }
    private val rule = "─".repeat(dashed.trimEnd().length)
    private val hidden = listOf(" │ cd /home/user/scratch && python3 - <<'EOF'", " │ # capture test for a tall permission prompt") +
        (1..45).map { " │ # filler row $it" }
    private val chat = listOf("● I'll run the probe now.", "")

    private fun history(above: List<String>) = (above + scrolled.trimEnd('\n').lines()).joinToString("\n")
    private val good = history(chat + rule + title + description + dashed + hidden)

    private fun complete(vararg reads: Pair<Int, String>): Pair<String, List<Int>> = runBlocking {
        val asked = mutableListOf<Int>()
        val out = completeTallDialog(scrolled, true, null) { n -> asked += n; reads.toMap()[n] }
        out to asked
    }

    @Test fun theScrolledFixtureHasItsTopOff() {
        val p = checkNotNull(detectPrompt(scrolled, true, "claude"))
        assertTrue(p.topOffScreen)
        assertTrue(p.shownParts().details.contains(CLAUDE_TOP_OFF_NOTE))
        assertEquals("Part of this command is above what the app can read; Show terminal to see it.", CLAUDE_TOP_OFF_NOTE)
    }

    @Test fun theHistoryGivesTheWholeCommandAndNoNote() {
        val (text, asked) = complete(200 to good)
        assertEquals(listOf(200), asked)
        assertEquals("marked as rebuilt", TALL_DIALOG_MARK, text.lines()[0])
        assertEquals("then the dialog's top rule, no chat", rule, text.lines()[1])
        assertTrue(text.lines().size > 80)
        val p = checkNotNull(detectPrompt(text, true, "claude"))
        assertFalse(p.topOffScreen)
        val parts = p.shownParts()
        assertFalse(parts.details.any { it == CLAUDE_TOP_OFF_NOTE || it == CLAUDE_TITLE_OFF_NOTE })
        assertTrue(parts.details.toString(), parts.details.any { it.contains("Bash command") })
        val command = checkNotNull(parts.command).lines()
        assertEquals("cd /home/user/scratch && python3 - <<'EOF'", command.first())
        assertEquals("# filler row 45", command[46])
        assertEquals("$ nproc", command[47])
        assertEquals("EOF", command.last())
        assertEquals(listOf("1", "2"), p.choices.map { it.keys })
        // The visible screen's card is its bottom, so an answer checked against the live screen still fits.
        val live = checkNotNull(detectPrompt(scrolled, true, "claude"))
        assertTrue(tallDialogFits(p, live))
        // The answer path checks the whole dialog, rebuilt again; the visible tail alone is not enough.
        assertFalse(cardAnswerStillFits(p, live))
        assertTrue(cardAnswerStillFits(p, checkNotNull(detectPrompt(complete(200 to good).first, true, "claude"))))
        // And the confirmation finds the dialog's top (its top rule, more than 60 rows above the options).
        val read = questionRead(text, p, "claude", null, true)
        assertTrue(read.topped)
        assertTrue(read.complete)
    }

    @Test fun aScreenWhoseTopRowContinuesAWrappedLineIsRebuiltToo() {
        val width = scrolled.lines().maxOf { it.trimEnd().length }
        val long = " │ " + "x".repeat(width - 3)
        val wrapped = history(chat + rule + title + description + dashed + hidden + long)
        val (text, _) = complete(200 to wrapped)
        assertEquals(TALL_DIALOG_MARK, text.lines()[0])
        val command = checkNotNull(checkNotNull(detectPrompt(text, true, "claude")).shownParts().command)
        assertTrue(command, command.contains("x".repeat(width - 3) + "$ nproc"))
    }

    @Test fun anOrdinaryReadStartingOnARuleIsNotTakenForARebuiltDialog() {
        val rows = listOf(rule) + (1..120).map { "chat row $it" }
        assertEquals(80, claudeDialogWindow(rows, "claude").size)
        assertEquals(80, claudeDialogWindow(listOf(TALL_DIALOG_MARK, rule, " │ not a title") + rows, "claude").size)
        assertEquals(124, claudeDialogWindow(listOf(TALL_DIALOG_MARK, rule, " Bash command") + rows, "claude").size)
    }

    @Test fun aLiveScreenWithAnotherCommandDoesNotFit() {
        val whole = checkNotNull(detectPrompt(complete(200 to good).first, true, "claude"))
        val other = checkNotNull(detectPrompt(scrolled.replace("$ nproc", "$ uname -a"), true, "claude"))
        assertFalse(tallDialogFits(whole, other))
        assertFalse(cardAnswerStillFits(whole, other))
    }

    @Test fun aHistoryThatDoesNotEndWithThisScreenIsRejected() {
        val changed = good.replaceFirst("$ nproc", "$ hostname")
        val (text, _) = complete(200 to changed, 400 to changed)
        assertEquals(scrolled, text)
        assertTrue(checkNotNull(detectPrompt(text, true, "claude")).topOffScreen)
    }

    @Test fun chatAboveTheScreenNeverEntersTheCommand() {
        val mixed = history(chat + rule + title + description + dashed + hidden.take(3) + " Some chat line" + hidden.drop(3))
        assertEquals(scrolled, complete(200 to mixed, 400 to mixed).first)
    }

    @Test fun aStaleCopyOfTheScreensTopIsRejected() {
        val top = scrolled.lines().take(3)
        val stale = history(chat + rule + title + description + dashed + hidden + top)
        assertEquals(scrolled, complete(200 to stale, 400 to stale).first)
    }

    @Test fun withoutTheTopWithinTheCapTheNoteStays() {
        fun deep(n: Int) = history((1..n).map { " │ # deep row $it" })
        val (text, asked) = complete(200 to deep(200), 400 to deep(400))
        assertEquals(listOf(200, 400), asked)
        assertEquals(scrolled, text)
        val p = checkNotNull(detectPrompt(text, true, "claude"))
        assertTrue(p.shownParts().details.contains(CLAUDE_TOP_OFF_NOTE))
    }

    @Test fun aShortHistoryStopsAfterOneReadAndADialogThatFitsIsNotReadAgain() {
        val short = history(listOf(" │ # one more row"))
        assertEquals(listOf(200), complete(200 to short, 400 to short).second)
        val fits = screen("captured-2.1.330-tall/tall-ask.txt")
        runBlocking {
            var reads = 0
            assertEquals(fits, completeTallDialog(fits, true, null) { reads++; null })
            assertEquals(0, reads)
        }
        assertNotEquals(fits, scrolled)
    }
}
