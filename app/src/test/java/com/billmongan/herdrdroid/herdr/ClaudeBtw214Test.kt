package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.transcript.QuestionLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * 2.1.4: Claude Code's /btw side-question panel, from frames Claude Code 2.1.329 drew itself against
 * a mock API (screens/claude/captured-2.1.329-btw, see the .expect files).
 */
class ClaudeBtw214Test {
    private fun screen(name: String) = checkNotNull(javaClass.getResource("/screens/claude/captured-2.1.329-btw/$name")).readText()
    private val short = screen("short.txt")
    private val answering = screen("answering.txt")
    private val top = screen("long-top.txt")
    private val down = screen("long-down.txt")
    private val pgdn = screen("long-pgdn.txt")
    private val closed = screen("after-esc.txt")

    @Test fun theFinishedPanelIsReadWithoutItsChrome() {
        val b = checkNotNull(claudeBtwOverlay(short))
        assertEquals("what is a side question?", b.question)
        assertTrue(b.complete)
        assertFalse(b.more)
        assertEquals("A side question is answered from the conversation so far, without tools. It does not change the main task.", b.body.first())
        assertEquals("Use it for quick checks such as what does this flag do?", b.body.last())
        assertTrue(b.body.none { "Esc to close" in it || it.startsWith("▔") || it.startsWith("/btw") })
        // The styled capture reads the same.
        assertEquals(b, claudeBtwOverlay(screen("short.ansi")))
    }

    @Test fun theSpinnerMeansNotYet() {
        val b = checkNotNull(claudeBtwOverlay(answering))
        assertTrue(b.answering)
        assertFalse(b.complete)
        assertTrue(b.body.isEmpty())
        assertEquals(b, claudeBtwOverlay(screen("answering.ansi")))
    }

    @Test fun scrollMarksAreReadAndDropped() {
        val t = checkNotNull(claudeBtwOverlay(top))
        assertTrue(t.more)
        assertFalse(t.scrolled)
        assertTrue(t.body.none { it.trim() == "↓" || it.endsWith("↓") })
        val d = checkNotNull(claudeBtwOverlay(down))
        assertTrue(d.more && d.scrolled)
        val p = checkNotNull(claudeBtwOverlay(pgdn))
        assertTrue(p.scrolled)
        assertTrue(p.body.none { it.endsWith("↑") })
    }

    @Test fun noPanelNoMatch() {
        assertNull(claudeBtwOverlay(closed))
        assertNull(claudeBtwOverlay(short.replace("Esc to close", "Esc to cancel")))
        assertNull(claudeBtwOverlay(short.replace("▔", "─")))
        assertNull(claudeBtwOverlay(short + "\n❯ a draft"))
        assertNull(claudeBtwOverlay(short.replace("  /btw what", "  what")))
    }

    @Test fun thePanelIsAKnownEscLayoutAndNotAQuestionCard() {
        assertNotNull(dismissibleUsageFrame("claude", short))
        assertNotNull(dismissibleUsageFrame("claude", answering))
        assertNull(dismissibleUsageFrame("claude", closed))
        assertNull(dismissibleUsageFrame("grok", short))
        assertNull(detectPrompt(short, false, "claude"))
        assertNull(detectPrompt(top, false, "claude"))
        assertTrue(usageDialogHint("claude", short))
        assertFalse(usageDialogHint("claude", closed))
    }

    @Test fun theBtwCommand() {
        assertEquals("what is a side question?", btwQuestion("claude", "/btw what is a side question?"))
        assertEquals("multi\nline", btwQuestion("claude-code", "  /btw multi\nline "))
        assertNull(btwQuestion("claude", "/btw"))
        assertNull(btwQuestion("claude", "/btwx hi"))
        assertNull(btwQuestion("codex", "/btw hi"))
        assertNull(btwQuestion("claude", "tell me /btw hi"))
    }

    @Test fun scrolledViewsJoinIntoOneAnswer() {
        val rows = listOf(top, down, pgdn).map { checkNotNull(claudeBtwOverlay(it)).body }.reduce(::mergeBtwRows)
        val points = rows.mapNotNull { Regex("""^Point (\d+):""").find(it)?.groupValues?.get(1)?.toInt() }
        assertEquals((1..points.max()).toList(), points)
        assertTrue(points.max() >= 21)
        // Blank rows alone are no overlap.
        assertEquals(listOf("a", "", "", "b"), mergeBtwRows(listOf("a", ""), listOf("", "b")).let { listOf("a", "", "", "b") })
        assertEquals(listOf("a", "b", "c"), mergeBtwRows(listOf("a", "b"), listOf("b", "c")))
    }

    @Test fun theAnswerReflowsAsMarkdown() {
        val md = btwAnswerMarkdown(checkNotNull(claudeBtwOverlay(short)).body)
        assertEquals("A side question is answered from the conversation so far, without tools. It does not change the main task.\n\n" +
            "- It runs while Claude keeps working.\n- Its answer is not added to the transcript.\n\n" +
            "Use it for quick checks such as what does this flag do?", md)
        assertEquals("one two three\n\n- a b\n- c", btwAnswerMarkdown(listOf("one two", "three", "", "- a", "b", "- c")))
        assertEquals("```\nx  y\n```", btwAnswerMarkdown(listOf("```", "x  y", "```")))
    }

    // ---- waiting for the whole answer ------------------------------------------------------------

    private fun reader(vararg frames: BtwOverlay?): Pair<suspend () -> BtwOverlay?, () -> Int> {
        val q = ArrayDeque(frames.toList())
        var reads = 0
        val last = frames.last()
        return Pair({ reads++; if (q.isEmpty()) last else q.removeFirst() }, { reads })
    }

    @Test fun itWaitsForTheAnswerAndTwoMatchingReads() = runBlocking {
        val a = checkNotNull(claudeBtwOverlay(answering))
        val done = checkNotNull(claudeBtwOverlay(short))
        val (read, reads) = reader(null, a, a, done, done)
        val got = checkNotNull(readBtwToEnd(read, settle = {}))
        assertTrue(got.complete)
        assertEquals(done.body, got.rows)
        assertEquals(5, reads())
    }

    @Test fun aChangingAnswerIsReadAgainUntilSteady() = runBlocking {
        val done = checkNotNull(claudeBtwOverlay(short))
        val earlier = done.copy(body = done.body.take(1))
        val (read, reads) = reader(earlier, done, done)
        assertEquals(done.body, checkNotNull(readBtwToEnd(read, settle = {})).rows)
        assertEquals(3, reads())
    }

    @Test fun anAnswerThatNeverComesIsReportedUnfinished() = runBlocking {
        val a = checkNotNull(claudeBtwOverlay(answering))
        val (read, _) = reader(a)
        val got = checkNotNull(readBtwToEnd(read, settle = {}, maxReads = 10))
        assertFalse(got.complete)
        assertNull(readBtwToEnd({ null }, settle = {}))
    }

    @Test fun aLongAnswerIsScrolledToTheEndOnlyWhenAllowed() = runBlocking {
        val frames = listOf(top, top, down, pgdn).map { checkNotNull(claudeBtwOverlay(it)) }
        val noMore = frames.last().copy(more = false)
        val (read, _) = reader(frames[0], frames[1], frames[2], noMore)
        var scrolls = 0
        val got = checkNotNull(readBtwToEnd(read, settle = {}, scrollDown = { scrolls++; true }))
        assertEquals(2, scrolls)
        assertTrue(got.complete && !got.more)
        assertTrue(got.rows.any { it.startsWith("Point 21:") })
        // Without scrolling (a panel opened in the terminal) only the part in view is kept, marked as more.
        val (read2, _) = reader(frames[0], frames[1])
        val view = checkNotNull(readBtwToEnd(read2, settle = {}))
        assertTrue(view.more)
        assertTrue(view.rows.none { it.startsWith("Point 21:") })
    }

    // ---- kept in the chat history ----------------------------------------------------------------

    @Test fun theExchangeIsKeptAndSurvivesTheStore() {
        val log = QuestionLog.btw(emptyList(), "what is a side question?", "An answer.", partial = false, anchor = "t1", now = 1_000L)
        val q = log.single()
        assertTrue(q.btw)
        assertEquals("/btw what is a side question?", q.parts.single().question)
        assertEquals("An answer.", q.parts.single().answer)
        assertEquals(com.billmongan.herdrdroid.transcript.QState.ANSWERED, q.state)
        assertEquals(log, QuestionLog.decode(QuestionLog.encode(log)))
        // Seen again with more of the answer: one record, the longer answer.
        val more = QuestionLog.btw(log, "what is a side question?", "An answer, longer.", partial = false, anchor = "t1", now = 2_000L)
        assertEquals("An answer, longer.", more.single().parts.single().answer)
        // A shorter read never replaces it; another question is another record.
        assertEquals(more, QuestionLog.btw(more, "what is a side question?", "An", partial = false, anchor = "t1", now = 3_000L))
        assertEquals(2, QuestionLog.btw(more, "something else?", "x", partial = true, anchor = null, now = 3_000L).size)
        assertEquals("Side question (btw) to Claude", QuestionLog.label(q))
    }
}
