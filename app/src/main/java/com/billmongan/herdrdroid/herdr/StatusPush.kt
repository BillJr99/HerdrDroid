package com.billmongan.herdrdroid.herdr

/**
 * How often the open server's list and the other servers are read for the "needs you" badge
 * (2.1.4, Bill: "a little bit of a delay in lighting up the waiting for you yellow icon in the
 * agents left menu"). herdr itself says when a state changes ([StatusPush]); the polls are the
 * safety net, tighter only while the drawer is out.
 */
internal object NeedsYouCadence {
    /** The open pane's list poll with the drawer shut: as before 2.1.4. */
    const val LIST_GAP_MS = 4_000L
    /** The same poll while the drawer, with its badges, is out. */
    const val DRAWER_LIST_GAP_MS = 2_000L
    /** One herdr wait while the app is in front; short, so a wait the app no longer needs ends soon. */
    const val PUSH_WAIT_MS = 25_000L
    /** The background wait, as before 2.1.4. */
    const val BACKGROUND_WAIT_MS = 60_000L
    /** Never more than one list read woken by herdr per this gap, however often herdr wakes. */
    const val MIN_WAKE_GAP_MS = 2_000L
    /** A wait that couldn't run (an error, an old herdr) is tried again after this; the polls cover the gap. */
    const val WAIT_RETRY_MS = 10_000L
    /** A screen that matched a dialog footer but showed nothing that needs you isn't watched again for this long. */
    const val SCREEN_QUIET_MS = 60_000L
    /** The other servers while the drawer is out in front, and otherwise (as before 2.1.4). */
    const val FLEET_DRAWER_GAP_MS = 3_000L
    const val FLEET_WATCHING_GAP_MS = 5_000L
    const val FLEET_GAP_MS = 10_000L

    fun listGapMs(drawerOpen: Boolean): Long = if (drawerOpen) DRAWER_LIST_GAP_MS else LIST_GAP_MS

    fun fleetGapMs(watching: Boolean, drawerOpen: Boolean, foreground: Boolean): Long = when {
        drawerOpen && foreground -> FLEET_DRAWER_GAP_MS
        watching -> FLEET_WATCHING_GAP_MS
        else -> FLEET_GAP_MS
    }

    /**
     * The pause before the next wait, from when the last one started ([elapsedMs]): after a wake, the
     * rest of [MIN_WAKE_GAP_MS]; after a wait that ran out its time, none; after one that ended early
     * with nothing (it couldn't run), [WAIT_RETRY_MS].
     */
    fun pauseAfter(woke: Boolean, elapsedMs: Long, waitMs: Long): Long = when {
        woke -> (MIN_WAKE_GAP_MS - elapsedMs).coerceAtLeast(0L)
        elapsedMs >= waitMs / 2 -> 0L
        else -> WAIT_RETRY_MS
    }
}

/**
 * One herdr wait on the open server and the list read it triggers (2.1.4). The wait ends when any
 * agent's herdr state changes, or a Claude or OpenCode screen shows a dialog herdr doesn't report;
 * [round] then reads the list at once, which runs the screen promotion ([markScreenNeedsYou]) in
 * the same read. A screen wake whose read shows nothing that needs you leaves that pane's screen
 * unwatched for [NeedsYouCadence.SCREEN_QUIET_MS], or until herdr's state for it changes, so a
 * footer-like line in old output can't wake the wait over and over.
 */
internal class StatusPush(private val now: () -> Long) {
    data class Round(val wake: Herdr.Wake?, val elapsedMs: Long)

    private data class Quiet(val occupant: Occupant, val state: Status?, val until: Long)

    private var owner: Herdr? = null
    private var connection: Any? = null
    private val quiet = mutableMapOf<String, Quiet>()

    private fun adopt(h: Herdr) {
        if (owner === h && connection === h.connectionIdentity) return
        quiet.clear()
        owner = h
        connection = h.connectionIdentity
    }

    /** The panes whose screens the next wait watches. */
    fun screens(h: Herdr, list: List<Pane>): List<Pane> {
        adopt(h)
        val states = h.herdrStates
        val panes = list.associateBy(Pane::id)
        val t = now()
        quiet.entries.removeAll { (id, q) ->
            t >= q.until || states[id] != q.state || panes[id]?.let(Occupant::of) != q.occupant
        }
        return screenWatchPanes(list, states).filter { it.id !in quiet }
    }

    /**
     * Waits up to [waitMs] on [h] for [list]; on a wake, reads the list at once with [refresh], which
     * gives the panes as now shown, or null when it didn't read.
     */
    suspend fun round(h: Herdr, list: List<Pane>, waitMs: Long, refresh: suspend () -> List<Pane>?): Round {
        val start = now()
        val watched = screens(h, list)
        val pinned = connection
        val wake = h.waitForWake(list, waitMs, watched)
        if (wake != null) {
            val fresh = refresh()
            val watchedPane = watched.firstOrNull { it.id == wake.paneId }
            val currentPane = fresh?.firstOrNull { it.id == wake.paneId }
            if (wake.screen && owner === h && pinned === connection && pinned === h.connectionIdentity &&
                watchedPane != null && currentPane != null && sameOccupant(watchedPane, currentPane) &&
                currentPane.status != Status.NEEDS_YOU) {
                quiet[wake.paneId] = Quiet(Occupant.of(currentPane), h.herdrStates[wake.paneId],
                    now() + NeedsYouCadence.SCREEN_QUIET_MS)
                com.billmongan.herdrdroid.diag.DiagLog.log("watch", "${wake.paneId}: its screen matched a dialog footer, but nothing needs you; not watching its screen for a minute")
            }
        }
        return Round(wake, now() - start)
    }
}
