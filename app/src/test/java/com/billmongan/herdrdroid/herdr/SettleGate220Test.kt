package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.2.0: the settle gate decides from the reads alone, with no timer. */
class SettleGate220Test {
    @Test fun twoIdenticalReadsInARowSettle() {
        val g = SettleGate()
        assertFalse(g.offer("v1", "half drawn", urgent = false).show)
        assertFalse(g.offer("v1", "drawn", urgent = false).show)
        val v = g.offer("v1", "drawn", urgent = false)
        assertTrue(v.show)
        assertEquals(SettleGate.Why.IDENTICAL, v.why)
        assertEquals(3, v.reads)
        // Settled: every later read shows, changed or not, with no reason given.
        val later = g.offer("v1", "a new reply", urgent = false)
        assertTrue(later.show)
        assertNull(later.why)
    }

    @Test fun anUrgentReadShowsAtOnce() {
        val g = SettleGate()
        val v = g.offer("v1", "a question", urgent = true)
        assertTrue(v.show)
        assertEquals(SettleGate.Why.URGENT, v.why)
    }

    @Test fun readsThatNeverMatchAreCappedAndTheNewestShows() {
        val g = SettleGate(maxReads = 3)
        assertFalse(g.offer("v1", "a", urgent = false).show)
        assertFalse(g.offer("v1", "b", urgent = false).show)
        val v = g.offer("v1", "c", urgent = false)
        assertTrue(v.show)
        assertEquals(SettleGate.Why.CAPPED, v.why)
    }

    @Test fun aNewVisitStartsOver() {
        val g = SettleGate()
        g.offer("v1", "x", urgent = false); g.offer("v1", "x", urgent = false)
        assertTrue(g.settledFor("v1"))
        assertFalse(g.offer("v2", "x", urgent = false).show)
        assertFalse(g.settledFor("v1"))
        // The read before the switch does not count toward the new visit.
        assertTrue(g.offer("v2", "x", urgent = false).show)
    }

    @Test fun urgencyCoversCardsAlertsAndLiveStreams() {
        assertFalse(urgentRead(needsYou = false, working = false, question = false, error = false, dialog = false))
        assertTrue(urgentRead(needsYou = true, working = false, question = false, error = false, dialog = false))
        assertTrue(urgentRead(needsYou = false, working = true, question = false, error = false, dialog = false))
        assertTrue(urgentRead(needsYou = false, working = false, question = true, error = false, dialog = false))
        assertTrue(urgentRead(needsYou = false, working = false, question = false, error = true, dialog = false))
        assertTrue(urgentRead(needsYou = false, working = false, question = false, error = false, dialog = true))
    }

    @Test fun theScreenViewWaitsForAnAgentsChatLog() {
        assertTrue(screenViewAllowed(hasReader = false, transcriptAnswered = false, gaveUp = false))
        assertFalse(screenViewAllowed(hasReader = true, transcriptAnswered = false, gaveUp = false))
        assertTrue(screenViewAllowed(hasReader = true, transcriptAnswered = true, gaveUp = false))
        assertTrue(screenViewAllowed(hasReader = true, transcriptAnswered = false, gaveUp = true))
    }
}
