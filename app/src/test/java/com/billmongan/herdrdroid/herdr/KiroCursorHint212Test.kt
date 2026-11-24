package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2.1.2 (Bill, 2026-11-21: "Kiro just has the 'ask a question or describe a task' question in its
 * box"): a live Kiro composer row with the cursor block first and its hint in the default colour.
 */
class KiroCursorHint212Test {
    private val e = "\u001B"
    private val hint = "ask a question or describe a task ↵"
    private fun capture() = checkNotNull(javaClass.getResource("/screens/kiro/live-2026-11-20-composer/empty.ansi")).readText()

    /** The 2026-11-20 Kiro capture with its composer row replaced by [row]. */
    private fun withRow(row: String) = capture().lines().joinToString("\n") { if (it.contains(hint)) row else it }

    /** The live row, read with `herdr pane read --ansi` on 2026-11-21. */
    private val liveRow = "› $e[0m$e[7m $e[0m$hint"

    @Test fun `the hint behind the cursor in the default colour is an empty box`() {
        val s = withRow(liveRow)
        assertNull(composerDraftOf(s, "kiro"))
        assertEquals("", inputLineOf(s, "kiro"))
        assertEquals("", inputLineOf("work_profile · auto\n$liveRow", "kiro"))
    }

    @Test fun `the same words with the cursor after them are a draft`() {
        val s = withRow("› $e[0m$hint$e[7m $e[0m")
        assertEquals(hint, composerDraftOf(s, "kiro")?.text)
        assertEquals(hint, inputLineOf(s, "kiro"))
        // The same words with no cursor drawn at all stay typing too.
        assertEquals(hint, inputLineOf(withRow("› $e[0m$hint"), "kiro"))
    }

    @Test fun `other text behind the cursor is a draft`() {
        val s = withRow("› $e[0m$e[7m $e[0mfix the build")
        assertEquals("fix the build", composerDraftOf(s, "kiro")?.text)
        assertEquals("fix the build", inputLineOf(s, "kiro"))
        // A longer line that starts with the hint is not the hint.
        assertEquals("$hint now", inputLineOf(withRow("› $e[0m$e[7m $e[0m$hint now"), "kiro"))
    }
}
