package com.billmongan.herdrdroid.questions

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 2.2.8: the Questions view's live feed (its entries are Compose state, so Robolectric). */
@RunWith(AndroidJUnit4::class)
class QuestionsFeedTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    private var now = 1_000_000L

    private fun pane(id: String) = Pane(id, "tab-$id", "w1", "claude", Status.NEEDS_YOU, "", "/work", tabLabel = id, terminalId = "t-$id")
    private fun src(server: String, id: String) = QuestionSource(server, server, server == "open", pane(id))
    private fun prompt(q: String, vararg options: String) = Prompt(q, options.mapIndexed { i, o -> Choice(o, "${i + 1}", false) })

    @Test fun theFeedHoldsAnEntryWhileItsAnswerIsInFlight() = runBlocking {
        val p1 = src("open", "p1")
        var listed = listOf(p1)
        var inFlight = false
        var reads = 0
        var question = "Run it?"
        val feed = QuestionsFeed(this, { listed }, { reads++; QuestionRead(prompt(question, "Yes", "No"), "target") }, { inFlight }) { now }
        feed.refreshNow()
        assertEquals(1, reads)
        assertEquals(EntryState.READY, feed.entries.single().state)
        assertEquals("target", feed.entries.single().target)
        // The tap is in flight; a refresh lands with the pane gone and a different screen.
        inFlight = true
        listed = emptyList()
        question = "Other?"
        now += 10_000
        feed.refreshNow()
        assertEquals(1, reads)
        assertEquals(EntryState.READY, feed.entries.single().state)
        assertEquals("Run it?", feed.entries.single().prompt?.question)
        // The answer went: the entry says so, then goes.
        inFlight = false
        feed.refreshNow()
        assertEquals(EntryState.ANSWERED, feed.entries.single().state)
        now += QuestionsAggregator.ANSWERED_SHOW_MS
        feed.refreshNow()
        assertTrue(feed.entries.isEmpty())
    }

    @Test fun theFeedHoldsWhatTheScreenHoldsAndReadsANewPaneAtOnce() = runBlocking {
        val p1 = src("open", "p1")
        val p2 = src("other", "p2")
        var listed = listOf(p1)
        val read = mutableListOf<String>()
        val feed = QuestionsFeed(this, { listed }, { read += it.key; QuestionRead(prompt("Q ${it.key}", "Yes"), null) }, { false }) { now }
        feed.refreshNow()
        feed.holdUi(p1.key)
        listed = listOf(p1, p2)
        now += 10_000
        feed.refreshNow()
        assertEquals(listOf("open|p1", "other|p2"), read)
        assertEquals(listOf("open|p1", "other|p2"), feed.entries.map { it.key })
        feed.releaseUi(p1.key)
        feed.refreshNow()
        assertEquals(listOf("open|p1", "other|p2", "open|p1"), read)
        assertFalse(feed.visible)
    }

    @Test fun repeatedRefreshesWhileARoundRunsAddOneRoundAndNoExtraReads() = runBlocking {
        val p1 = src("open", "p1")
        val p2 = src("open", "p2")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var reads = 0
        var inRead = 0
        var maxInRead = 0
        var sourcesAsked = 0
        val feed = QuestionsFeed(this, { sourcesAsked++; listOf(p1, p2) }, {
            reads++; inRead++; maxInRead = maxOf(maxInRead, inRead)
            gate.await()
            inRead--
            QuestionRead(prompt("Q ${it.key}", "Yes"), null)
        }, { false }) { now }
        val first = launch { feed.refreshNow() }
        while (reads < 2) kotlinx.coroutines.yield()
        // Ten taps while the first round's reads are still out: none starts a round beside it.
        val taps = (1..10).map { launch { feed.refreshNow() } }
        taps.forEach { it.join() }
        assertEquals("only the first round's reads", 2, reads)
        gate.complete(Unit)
        first.join()
        assertEquals("the panes just read are not read again", 2, reads)
        assertEquals(2, maxInRead)
        // The first round (before and after its reads) and one more round with nothing due: never one per tap.
        assertEquals(3, sourcesAsked)
        assertEquals(listOf(EntryState.READY, EntryState.READY), feed.entries.map { it.state })
    }
}
