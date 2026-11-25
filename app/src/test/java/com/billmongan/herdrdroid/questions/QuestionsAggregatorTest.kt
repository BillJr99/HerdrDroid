package com.billmongan.herdrdroid.questions

import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.2.8: the Questions view's list: grouping, order, and staying still while it updates live. */
class QuestionsAggregatorTest {
    private var now = 1_000_000L
    private val agg = QuestionsAggregator { now }

    private fun pane(id: String, agent: String = "claude", terminal: String = "t-$id", session: String? = null) =
        Pane(id, "tab-$id", "w1", agent, Status.NEEDS_YOU, "", "/work", tabLabel = id, terminalId = terminal, sessionId = session)
    private fun src(server: String, id: String, current: Boolean = server == "open", label: String = server.replaceFirstChar { it.uppercase() },
        p: Pane = pane(id)) = QuestionSource(server, label, current, p)
    private fun prompt(q: String, vararg options: String) = Prompt(q, options.mapIndexed { i, o -> Choice(o, "${i + 1}", false) })

    @Test fun noPanesNeedingYouIsAnEmptyList() {
        agg.update(emptyList())
        assertTrue(agg.entries().isEmpty())
        assertEquals(0, agg.openCount())
    }

    @Test fun groupsByServerOpenFirstThenOldestWaitingFirst() {
        agg.update(listOf(src("open", "b")))
        now += 10_000
        agg.update(listOf(src("open", "b"), src("zeta", "z1"), src("alpha", "a1"), src("open", "a")))
        now += 10_000
        agg.update(listOf(src("open", "b"), src("zeta", "z1"), src("alpha", "a1"), src("open", "a"), src("alpha", "a0")))
        assertEquals(listOf("open|b", "open|a", "alpha|a1", "alpha|a0", "zeta|z1"), agg.entries().map { it.key })
        assertEquals(5, agg.openCount())
        assertTrue(agg.entries().all { it.state == EntryState.READING })
    }

    @Test fun anEntryAppearsOnANewBlockedStateAndShowsItsConfirmedQuestion() {
        agg.update(emptyList())
        val s = src("open", "p1")
        agg.update(listOf(s))
        assertEquals(EntryState.READING, agg.entry(s.key)?.state)
        assertEquals(listOf(s), agg.due(5_000))
        val target = Any()
        agg.applyRead(s, QuestionRead(prompt("Run it?", "Yes", "No"), target))
        val e = agg.entry(s.key)!!
        assertEquals(EntryState.READY, e.state)
        assertEquals("Run it?", e.prompt?.question)
        assertEquals(target, e.target)
        assertTrue("just read", agg.due(5_000).isEmpty())
        now += 5_000
        assertEquals(listOf(s), agg.due(5_000))
    }

    @Test fun anEntryDisappearsWhenItsPaneStopsNeedingYouAfterShowingAnswered() {
        val s = src("open", "p1")
        agg.update(listOf(s))
        agg.applyRead(s, QuestionRead(prompt("Run it?", "Yes", "No"), Any()))
        now += 1_000
        agg.update(emptyList())
        val e = agg.entry(s.key)!!
        assertEquals(EntryState.ANSWERED, e.state)
        assertNull("no answers on an answered entry", e.target)
        assertEquals(0, agg.openCount())
        now += QuestionsAggregator.ANSWERED_SHOW_MS - 1
        agg.update(emptyList())
        assertEquals(EntryState.ANSWERED, agg.entry(s.key)?.state)
        now += 1
        agg.update(emptyList())
        assertNull(agg.entry(s.key))
    }

    @Test fun aDialogThatClosesBetweenTheListAndTheReadIsAnsweredNotRefilled() {
        val s = src("open", "p1")
        agg.update(listOf(s))
        agg.applyRead(s, QuestionRead(prompt("Run it?", "Yes", "No"), Any()))
        // herdr still says blocked, but the screen shows no question now: its dialog closed.
        now += 5_000
        agg.update(listOf(s))
        agg.applyRead(s, QuestionRead(null, null))
        assertEquals(EntryState.ANSWERED, agg.entry(s.key)?.state)
        assertNull(agg.entry(s.key)?.target)
        // A read that started before still can't bring its buttons back.
        agg.applyRead(s, QuestionRead(prompt("Run it?", "Yes", "No"), Any()))
        assertEquals(EntryState.ANSWERED, agg.entry(s.key)?.state)
    }

    @Test fun aPaneThatNeverShowedAQuestionSaysItCouldntBeRead() {
        val s = src("open", "p1")
        agg.update(listOf(s))
        agg.applyRead(s, QuestionRead(null, null))
        assertEquals(EntryState.UNREAD, agg.entry(s.key)?.state)
        // Never a question: when the pane stops needing you it simply goes.
        agg.update(emptyList())
        assertNull(agg.entry(s.key))
    }

    @Test fun aFailedReadKeepsWhatIsShown() {
        val s = src("open", "p1")
        agg.update(listOf(s))
        agg.applyRead(s, QuestionRead(prompt("Run it?", "Yes", "No"), Any()))
        agg.applyRead(s, null)
        assertEquals(EntryState.READY, agg.entry(s.key)?.state)
    }

    @Test fun anOccupantChangeStartsAgainAndAStaleReadIsIgnored() {
        val old = src("open", "p1")
        agg.update(listOf(old))
        agg.applyRead(old, QuestionRead(prompt("Run it?", "Yes", "No"), Any()))
        now += 30_000
        // Another agent session in the same pane.
        val replaced = src("open", "p1", p = pane("p1", terminal = "t-other"))
        agg.update(listOf(replaced))
        val e = agg.entry(replaced.key)!!
        assertEquals(EntryState.READING, e.state)
        assertNull(e.prompt)
        assertNull(e.target)
        assertEquals(now, e.since)
        assertEquals("the entry says the pane changed hands", QuestionsAggregator.OCCUPANT_NOTE, e.note)
        // The read made for the old occupant lands late: ignored.
        agg.applyRead(old, QuestionRead(prompt("Run it?", "Yes", "No"), Any()))
        assertEquals(EntryState.READING, agg.entry(replaced.key)?.state)
        agg.applyRead(replaced, QuestionRead(prompt("Delete it?", "Yes", "No"), Any()))
        assertEquals("Delete it?", agg.entry(replaced.key)?.prompt?.question)
    }

    @Test fun aNewQuestionInTheSamePaneStartsItsOwnWait() {
        val s = src("open", "p1")
        agg.update(listOf(s))
        agg.applyRead(s, QuestionRead(prompt("First?", "Yes", "No"), Any()))
        val first = agg.entry(s.key)!!.since
        now += 20_000
        agg.applyRead(s, QuestionRead(prompt("First?", "Yes", "No").copy(selected = "2"), Any()))
        assertEquals("the highlight moved, same question", first, agg.entry(s.key)!!.since)
        now += 20_000
        agg.applyRead(s, QuestionRead(prompt("Second?", "Allow", "Deny"), Any()))
        assertEquals(now, agg.entry(s.key)!!.since)
        assertEquals("Second?", agg.entry(s.key)!!.prompt?.question)
    }

    @Test fun aHeldEntryIsNotRereadReorderedOrRemoved() {
        val a = src("open", "a")
        agg.update(listOf(a))
        val target = Any()
        agg.applyRead(a, QuestionRead(prompt("Run it?", "Yes", "No"), target))
        agg.hold(a.key)
        now += 60_000
        // While the tap is in flight the pane stops needing you and another question appears.
        agg.update(emptyList())
        agg.applyRead(a, QuestionRead(prompt("Something else?", "A", "B"), Any()))
        val held = agg.entry(a.key)!!
        assertEquals(EntryState.READY, held.state)
        assertEquals("Run it?", held.prompt?.question)
        assertEquals(target, held.target)
        assertTrue("never due while held", agg.due(0).isEmpty())
        agg.release(a.key)
        agg.update(emptyList())
        assertEquals(EntryState.ANSWERED, agg.entry(a.key)?.state)
    }

    @Test fun aNoteShowsOnItsEntryUntilANewQuestion() {
        val s = src("open", "p1")
        agg.update(listOf(s))
        agg.applyRead(s, QuestionRead(prompt("Run it?", "Yes", "No"), Any()))
        agg.note(s.key, "Not sent. Another agent or session is in this pane now.")
        assertEquals("Not sent. Another agent or session is in this pane now.", agg.entry(s.key)?.note)
        agg.applyRead(s, QuestionRead(prompt("Run it?", "Yes", "No"), Any()))
        assertEquals("the same question keeps it", "Not sent. Another agent or session is in this pane now.", agg.entry(s.key)?.note)
        agg.applyRead(s, QuestionRead(prompt("Other?", "A"), Any()))
        assertNull(agg.entry(s.key)?.note)
    }

    @Test fun anOlderReadFinishingAfterANewerOneDoesNotOverwriteIt() {
        val s = src("open", "p1")
        agg.update(listOf(s))
        val older = agg.beginRead(s.key)
        val newer = agg.beginRead(s.key)
        agg.applyRead(s, QuestionRead(prompt("Second?", "Allow", "Deny"), "new"), newer)
        agg.applyRead(s, QuestionRead(prompt("First?", "Yes", "No"), "old"), older)
        assertEquals("Second?", agg.entry(s.key)?.prompt?.question)
        assertEquals("new", agg.entry(s.key)?.target)
        // Nor an older "no question" read: the newer question stays.
        val stale = agg.beginRead(s.key)
        agg.applyRead(s, QuestionRead(prompt("Third?", "A"), "third"), agg.beginRead(s.key))
        agg.applyRead(s, QuestionRead(null, null), stale)
        assertEquals(EntryState.READY, agg.entry(s.key)?.state)
        assertEquals("Third?", agg.entry(s.key)?.prompt?.question)
    }

    @Test fun anUnreadablePaneSaysWhyAndIsReadOnlyAfterTheWait() {
        val s = src("open", "p1")
        agg.update(listOf(s))
        agg.applyRead(s, QuestionRead(null, null, unreadable = "The pane is scrolled back."))
        val e = agg.entry(s.key)!!
        assertEquals(EntryState.UNREAD, e.state)
        assertEquals("The pane is scrolled back.", e.note)
        assertTrue("not read again at once", agg.due(5_000).isEmpty())
        now += 5_000
        assertEquals(listOf(s), agg.due(5_000))
    }

    @Test fun aFailedFirstReadSaysSoAndWaitsBeforeTheNext() {
        val s = src("open", "p1")
        agg.update(listOf(s))
        agg.applyRead(s, null)
        assertEquals(EntryState.UNREAD, agg.entry(s.key)?.state)
        assertEquals(QuestionsAggregator.READ_FAILED_NOTE, agg.entry(s.key)?.note)
        assertTrue(agg.due(5_000).isEmpty())
        // Read fine later: the question shows, the note goes.
        agg.applyRead(s, QuestionRead(prompt("Run it?", "Yes"), Any()))
        assertEquals(EntryState.READY, agg.entry(s.key)?.state)
        assertNull(agg.entry(s.key)?.note)
    }

    @Test fun aDialogThatNeverReadTheSameTwiceIsNotTakenAsAnswered() {
        val s = src("open", "p1")
        agg.update(listOf(s))
        agg.applyRead(s, QuestionRead(prompt("Run it?", "Yes", "No"), Any()))
        agg.applyRead(s, QuestionRead(null, null, timedOut = true))
        assertEquals(EntryState.READY, agg.entry(s.key)?.state)
        assertEquals("Run it?", agg.entry(s.key)?.prompt?.question)
        // Never shown: it says the question kept changing, with Open pane, and is read again only after the wait.
        val p2 = src("open", "p2")
        agg.update(listOf(s, p2))
        agg.applyRead(p2, QuestionRead(null, null, timedOut = true))
        assertEquals(EntryState.UNREAD, agg.entry(p2.key)?.state)
        assertEquals(QuestionsAggregator.KEPT_CHANGING_NOTE, agg.entry(p2.key)?.note)
        assertFalse(p2 in agg.due(5_000))
        now += 5_000
        assertTrue(p2 in agg.due(5_000))
        // Then it settles: the question shows, the note goes.
        agg.applyRead(p2, QuestionRead(prompt("Now?", "Yes"), Any()))
        assertEquals(EntryState.READY, agg.entry(p2.key)?.state)
        assertNull(agg.entry(p2.key)?.note)
    }

    @Test fun aCandidatePaneIsListedOnlyOnceItShowsAQuestion() {
        val c = QuestionSource("open", "Open", true, pane("omp1", agent = "omp"), candidate = true)
        agg.update(listOf(c))
        assertTrue(agg.entries().isEmpty())
        assertEquals(0, agg.openCount())
        assertEquals("but it is read", listOf(c), agg.due(5_000))
        agg.applyRead(c, QuestionRead(null, null))
        assertTrue("no question: never shown", agg.entries().isEmpty())
        now += 5_000
        agg.applyRead(c, QuestionRead(prompt("Allow tool: bash?", "Approve", "Deny"), Any()))
        assertEquals(listOf(c.key), agg.entries().map { it.key })
        assertEquals(1, agg.openCount())
        agg.update(emptyList())
        assertEquals(EntryState.ANSWERED, agg.entries().single().state)
    }
}
