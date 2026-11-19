package com.billmongan.herdrdroid.fleet

import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.2.8: Bill saw "AWS Claude" (the Claude pane on a watched server, not the open one) as idle
 * while its shell was running. Watched servers now get the same work sweep as the open one.
 */
class FleetBusyTest {
    private fun pane(id: String, agent: String?, status: Status) =
        Pane(id = id, tabId = "t$id", workspaceId = "w1", agent = agent, status = status, title = "", cwd = "/home/u/$id")

    private val raw = listOf(
        pane("p1", "claude", Status.IDLE),     // waiting on a long shell; herdr says idle
        pane("p2", "codex", Status.DONE),      // nothing under it
        pane("p3", null, Status.UNKNOWN),      // Vibe: herdr does not track it
        pane("p4", "grok", Status.NEEDS_YOU),  // a question stays a question
    )

    @Test fun busyAgentOnAWatchedServerShowsAsWorking() {
        val out = sweptPanes(raw, Result.success(mapOf("p1" to 1, "p3" to 2, "p4" to 1)))
        assertEquals(listOf(Status.WORKING, Status.DONE, Status.WORKING, Status.NEEDS_YOU), out.map { it.status })
    }

    @Test fun busyBadgeAppliesOnlyToBackgroundWorkAndClearsAfterCompletion() {
        val busy = sweptPanes(raw, Result.success(mapOf("p1" to 1, "p2" to 1, "p4" to 1)))
        assertEquals(listOf(true, true, false, false), busy.map { it.backgroundBusy })
        assertEquals(raw, sweptPanes(raw, Result.success(mapOf("p1" to 0))))
    }

    @Test fun aFailedSweepFallsBackToHerdrsStatus() {
        val out = sweptPanes(raw, Result.failure(IllegalStateException("no /proc here")))
        assertEquals(raw, out)
    }

    @Test fun noBusyNoChange() {
        assertEquals(raw, sweptPanes(raw, Result.success(emptyMap())))
    }
}
