package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.1.5 (Bill: the app could not send an OpenCode pane the cross-server notice 5 times). OpenCode
 * draws no prompt glyph, and inside a session its empty box shows no "Ask anything…" placeholder:
 * only three bare "┃" rows above its "Build · <model>" row. [inputLineOf] read that as "no input
 * line" (null), so the cross-server notice, the voice send and the relay refused every time. The
 * captures are both OpenCode panes on minipc2, idle with an empty box (one with OpenCode's sidebar).
 */
class OpenCodeSessionComposer215Test {
    private fun screen(name: String) =
        checkNotNull(javaClass.getResource("/screens/opencode/live-2026-11-22-session/$name")).readText()
    private val hosted = screen("hosted-empty.ansi")
    private val sidebar = screen("sidebar-empty.ansi")

    /** [s] with the middle row of the composer (the text row, two above the model row) replaced. */
    private fun withComposerRow(s: String, typed: String): String {
        val rows = s.split('\n').toMutableList()
        val mode = rows.indexOfLast { stripAnsi(it).contains("┃  Build · ") }
        val at = mode - 2
        val bar = rows[at].indexOf('┃')
        // On a styled row the text goes inside the box's own background run, in the default colour.
        val bg = "48;2;30;30;30m"
        val k = if (rows[at].contains('\u001B')) rows[at].indexOf(bg, bar) + bg.length else bar + 1
        rows[at] = rows[at].substring(0, k) + "  " + typed + rows[at].substring(k + 2 + typed.length)
        return rows.joinToString("\n")
    }

    @Test fun `the empty session box reads as an empty input line`() {
        for (s in listOf(hosted, sidebar)) {
            assertEquals("", inputLineOf(s, "opencode"))
            assertNull(composerDraftOf(s, "opencode"))
            assertNull(detectPrompt(forPhone(s), false, "opencode"))
            assertTrue(openCodeSessionComposerEmpty(s))
        }
    }

    @Test fun `the home screen box with its placeholder still reads as empty`() {
        val home = checkNotNull(javaClass.getResource("/screens/opencode/live-2026-11-20-ursinus/empty.ansi")).readText()
        assertEquals("", inputLineOf(home, "opencode"))
        // The same words in normal type could be typed: only the grey placeholder is empty.
        assertNotEquals("", inputLineOf(stripAnsi(home), "opencode"))
        assertNotEquals("", inputLineOf(home.replace("\u001B[38;2;128;128;128m\u001B[48;2;30;30;30mAsk", "\u001B[38;2;255;255;255m\u001B[48;2;30;30;30mAsk"), "opencode"))
    }

    @Test fun `typed text in the box is never an empty line`() {
        for (s in listOf(hosted, sidebar, stripAnsi(hosted), stripAnsi(sidebar))) {
            val typed = withComposerRow(s, "fix the build")
            assertTrue(stripAnsi(typed).contains("┃  fix the build"))
            assertNotEquals("", inputLineOf(typed, "opencode"))
            assertTrue(!openCodeSessionComposerEmpty(typed))
        }
    }

    @Test fun `a box grown by blank lines typed in it is never an empty line`() {
        // A draft of only line feeds makes the box taller: four or more bare rows.
        val rows = hosted.split('\n').toMutableList()
        val mode = rows.indexOfLast { stripAnsi(it).contains("┃  Build · ") }
        rows.add(mode - 1, rows[mode - 1])
        rows.removeAt(0)
        val taller = rows.joinToString("\n")
        assertNotEquals("", inputLineOf(taller, "opencode"))
    }

    @Test fun `only an OpenCode pane is read this way`() {
        assertNull(inputLineOf(hosted, "claude"))
        assertNull(inputLineOf(hosted, null))
    }
}
