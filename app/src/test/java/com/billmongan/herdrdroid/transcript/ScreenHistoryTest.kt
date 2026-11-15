package com.billmongan.herdrdroid.transcript

import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.3 (Bill: "make all the supported agents consistent"): a pane with no readable log
 * (Antigravity, a plain shell) keeps the screen just before each question, so its view is a history
 * of snapshots with each question between them, newer ones below, and the live screen last.
 */
class ScreenHistoryTest {
    private val run = Prompt("Run this command?", listOf(Choice("Yes, run command", "1", false), Choice("No, cancel", "2", false)))
    private val edit = Prompt("Allow access to this file?", listOf(Choice("Yes, allow access", "1", false), Choice("No, deny access", "2", false)))

    private val screen1 = """
> list the files
● I'll run ls.
Requesting permission for:
   ls -la

Run this command?
> 1. Yes, run command
  2. No, cancel
""".trimIndent()

    private val screen2 = """
● I'll run ls.
Requesting permission for:
   ls -la
● Ran ls: 12 files.
> now edit notes.txt
● Editing notes.txt.
Allow access to this file?
> 1. Yes, allow access
  2. No, deny access
""".trimIndent()

    private fun answered(log: List<QuestionItem>, at: Long) = QuestionLog.gone(log, working = true, at)

    @Test fun antigravityQuestionsSitBetweenTheirScreens() {
        var log = QuestionLog.seen(emptyList(), run, "Antigravity", null, 1_000, screen1)
        log = answered(log, 2_000)
        log = QuestionLog.seen(log, edit, "Antigravity", null, 3_000, screen2)
        log = answered(log, 4_000)
        // The first snapshot is the screen above the first card; the second only what is new since.
        assertEquals("> list the files\n● I'll run ls.\nRequesting permission for:\n   ls -la", log[0].screen)
        assertEquals("● Ran ls: 12 files.\n> now edit notes.txt\n● Editing notes.txt.", log[1].screen)
        // Oldest first: each question below its screen, and what came after it (the next screen) below it.
        val h = QuestionLog.screenHistory(log)
        assertEquals(listOf("snap", "q", "snap", "q"), h.map { if (it is ScreenSnapshot) "snap" else "q" })
        assertEquals(log[0].key, (h[1] as QuestionItem).key)
        assertTrue((h[2] as ScreenSnapshot).text.contains("Ran ls"))
    }

    @Test fun snapshotsSurviveAReopenAndAreCapped() {
        val long = (1..200).joinToString("\n") { "line $it" } + "\nRun this command?\n> 1. Yes, run command\n  2. No, cancel"
        val log = QuestionLog.seen(emptyList(), run, "Antigravity", null, 1_000, long)
        val back = QuestionLog.decode(QuestionLog.encode(log))!!
        assertEquals(log[0].screen, back[0].screen)
        val lines = back[0].screen!!.lines()
        assertEquals(QuestionLog.SNAPSHOT_LINES, lines.size)
        assertEquals("line 200", lines.last())   // the newest lines are the ones kept
    }

    @Test fun nothingNewMeansNoSnapshot() {
        var log = QuestionLog.seen(emptyList(), run, "Antigravity", null, 1_000, screen1)
        log = answered(log, 2_000)
        // The same screen again for another question: nothing new to keep.
        log = QuestionLog.seen(log, edit, "Antigravity", null, 3_000, screen1.replace("Run this command?", "Allow access to this file?"))
        assertNull(log[1].screen)
        assertEquals(listOf("snap", "q", "q"), QuestionLog.screenHistory(log).map { if (it is ScreenSnapshot) "snap" else "q" })
    }

    @Test fun aQuestionWithoutAScreenIsUnchanged() {
        // Agents with a readable log keep no screen; their questions go into the chat as before.
        val log = QuestionLog.seen(emptyList(), run, "Claude", "k1", 1_000)
        assertNull(log[0].screen)
        assertEquals(listOf<Any>(log[0]), QuestionLog.screenHistory(log))
    }
}
