package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.store.VaultLocked
import com.billmongan.herdrdroid.transcript.CachedChat
import com.billmongan.herdrdroid.transcript.ChatStore
import com.billmongan.herdrdroid.transcript.QuestionLog
import com.billmongan.herdrdroid.transcript.TextItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 1.3.1: the usage check types into a pane only when it is quiet (Bill: "Could these occur only
 * when the pane appears to be idle (at least idle of user input or scrolling)?"), and a chat file
 * that can't be opened because the fingerprint window passed is kept, not deleted.
 */
class QuietGateTest {
    @get:Rule val tmp = TemporaryFolder()

    private val screen = "Some output\n\n> \n  ? for shortcuts"

    @Test fun aStillPaneBecomesQuietAfterTheQuietPeriod() {
        val g = QuietGate(quietMs = 45_000, minGapMs = 300_000)
        assertEquals("not watched yet", g.verdict("p", Status.IDLE, 0))
        g.observe("p", screen, screen, 0)
        assertEquals("screen changed 30s ago", g.verdict("p", Status.IDLE, 30_000))
        g.observe("p", screen, screen, 30_000)
        assertNull(g.verdict("p", Status.IDLE, 60_000))
    }

    @Test fun aChangeOrASendStartsTheWaitAgain() {
        val g = QuietGate()
        g.observe("p", screen, screen, 0)
        g.observe("p", screen + "\nnew line", screen + "\nnew line", 50_000)
        assertEquals("screen changed 10s ago", g.verdict("p", Status.IDLE, 60_000))
        assertNull(g.verdict("p", Status.IDLE, 96_000))
        g.sent("p", 100_000)
        assertEquals("sent to 20s ago", g.verdict("p", Status.IDLE, 120_000))
        assertNull(g.verdict("p", Status.IDLE, 146_000))
        // A redraw with only trailing spaces is not a change.
        g.observe("p", (screen + "\nnew line").lines().joinToString("\n") { "$it   " }, screen + "\nnew line", 150_000)
        assertNull(g.verdict("p", Status.IDLE, 150_000))
    }

    @Test fun neverWhileWorkingOrWaitingOrScrolledBack() {
        val g = QuietGate()
        g.observe("p", screen, screen, 0)
        assertEquals("working", g.verdict("p", Status.WORKING, 100_000))
        assertEquals("waiting on a question", g.verdict("p", Status.NEEDS_YOU, 100_000))
        // Someone scrolled the pane back: the visible screen is not the bottom of the output.
        val older = "line 1\nline 2\nline 3\nline 4"
        g.observe("p", older, screen, 100_000)
        assertEquals("scrolled back", g.verdict("p", Status.IDLE, 200_000))
        g.observe("p", screen, screen, 200_000)
        assertEquals("screen changed 0s ago", g.verdict("p", Status.IDLE, 200_000))
    }

    @Test fun atMostOnceEveryFewMinutes() {
        val g = QuietGate(quietMs = 45_000, minGapMs = 300_000)
        g.observe("p", screen, screen, 0)
        assertNull(g.verdict("p", Status.IDLE, 60_000))
        g.checked("p", 60_000)
        assertEquals("checked 100s ago", g.verdict("p", Status.IDLE, 160_000))
        assertNull(g.verdict("p", Status.IDLE, 360_000))
        // Other panes are their own.
        g.observe("q", screen, screen, 0)
        assertNull(g.verdict("q", Status.DONE, 160_000))
    }

    @Test fun herdrsOwnScrollOffsetDecides() {
        val g = QuietGate()
        // herdr says the pane is scrolled back 12 rows, even though the text compares equal.
        g.observe("p", screen, screen, 0, scrollOffset = 12)
        assertEquals("scrolled back", g.verdict("p", Status.IDLE, 100_000))
        // Back at the bottom (offset 0), and the text was the same all along: quiet.
        g.observe("p", screen, screen, 100_000, scrollOffset = 0)
        assertNull(g.verdict("p", Status.IDLE, 100_000))
    }

    @Test fun bottomComparison() {
        assertTrue(QuietGate.sameBottom(screen, "earlier\n" + screen))
        // A blank visible screen over output with lines is not the bottom (1.3.2); both blank is.
        assertFalse(QuietGate.sameBottom("", screen))
        assertTrue(QuietGate.sameBottom("", ""))
    }

    @Test fun aLockedChatOrQuestionFileIsKept() {
        val seal: (ByteArray) -> ByteArray = { it.reversedArray() }
        var locked = false
        val open: (ByteArray) -> ByteArray = { if (locked) throw VaultLocked(RuntimeException("not authenticated")) else it.reversedArray() }
        val store = ChatStore(tmp.root, seal, open)
        val chat = CachedChat(listOf(TextItem("t1", "hello")), "{}", false, null)
        store.save("s", "p", chat)
        val q = QuestionLog.seen(emptyList(), Prompt("Go on?", listOf(Choice("Yes", "1", false))), "Codex", null, 1_000)
        store.saveQuestions("s", "p", q)
        locked = true
        assertNull(store.load("s", "p"))
        assertNull(store.loadQuestions("s", "p"))
        assertEquals(2, tmp.root.listFiles()!!.size)
        locked = false
        assertEquals(chat.items, store.load("s", "p")!!.items)
        assertEquals(q, store.loadQuestions("s", "p"))
    }
}
