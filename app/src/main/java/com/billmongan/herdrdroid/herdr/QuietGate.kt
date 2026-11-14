package com.billmongan.herdrdroid.herdr

/**
 * When the app may type into a pane on its own (1.3.1, Bill: "herdrdroid occasionally scrolls a
 * pane which causes the actual herdr session to scroll. I'd like to do this less frequently to
 * avoid disruptions. Could these occur only when the pane appears to be idle (at least idle of
 * user input or scrolling)?").
 *
 * The only thing the app does by itself that moves a real pane is the usage check: it types the
 * agent's usage command (`/usage`, `/status`...), may press the arrows to scroll that screen, and
 * closes it. Every other read uses `herdr pane read`, which moves nothing. So the usage check runs
 * only on a quiet pane, which [verdict] decides from what the app has seen:
 *  - the agent isn't working (never while working, whatever the agent);
 *  - its visible screen has been the same for [quietMs] (no one typed, scrolled or printed there;
 *    a pane that is focused at the computer and just changed is not quiet);
 *  - the app sent it nothing (no message, key or answer) for [quietMs];
 *  - it isn't scrolled back: the bottom of its visible screen is the bottom of its output, so the
 *    check, which returns the view to the bottom, can't pull someone's scrolled-back view away;
 *  - it wasn't checked in the last [minGapMs].
 * The screen is sampled on each usage round (every 30 s while a check is due), so a pane is first
 * found quiet about a minute after it went still.
 */
class QuietGate(val quietMs: Long = 45_000, val minGapMs: Long = 5 * 60_000) {
    private data class Seen(val digest: Int, val since: Long, val scrolledBack: Boolean)
    private val seen = HashMap<String, Seen>()
    private val sent = HashMap<String, Long>()
    private val checked = HashMap<String, Long>()

    /**
     * The pane's visible screen and the bottom of its output, as just read, and herdr's own scroll
     * offset when it reports one (`scroll.offset_from_bottom` in `herdr pane list`: above 0 means
     * someone scrolled the pane back). Without it, a visible screen whose bottom isn't the output's
     * bottom counts as scrolled back.
     */
    @Synchronized fun observe(pane: String, visible: String, recent: String, now: Long, scrollOffset: Int? = null) {
        val d = norm(visible).hashCode()
        val back = if (scrollOffset != null) scrollOffset > 0 else !sameBottom(visible, recent)
        val old = seen[pane]
        seen[pane] = if (old != null && old.digest == d) old.copy(scrolledBack = back) else Seen(d, now, back)
    }

    /** The app sent the pane something (a message, keys, an answer). */
    @Synchronized fun sent(pane: String, now: Long) { sent[pane] = now }

    /** The app is about to type a usage check into the pane. */
    @Synchronized fun checked(pane: String, now: Long) { checked[pane] = now }

    /** Null when the pane may be checked now; otherwise why not, for the diagnostic log. */
    @Synchronized fun verdict(pane: String, status: Status, now: Long): String? {
        if (status == Status.WORKING) return "working"
        if (status == Status.NEEDS_YOU) return "waiting on a question"
        val s = seen[pane] ?: return "not watched yet"
        if (s.scrolledBack) return "scrolled back"
        val still = now - s.since
        if (still < quietMs) return "screen changed ${still / 1000}s ago"
        sent[pane]?.let { if (now - it < quietMs) return "sent to ${(now - it) / 1000}s ago" }
        checked[pane]?.let { if (now - it < minGapMs) return "checked ${(now - it) / 1000}s ago" }
        return null
    }

    @Synchronized fun forget(pane: String) { seen.remove(pane); sent.remove(pane); checked.remove(pane) }

    companion object {
        /** The screen without trailing spaces, so a redraw that changes nothing isn't a change. */
        fun norm(s: String): String = s.lines().joinToString("\n") { it.trimEnd() }.trimEnd()

        /** True when the last lines of the visible screen are the last lines of the pane's output. */
        fun sameBottom(visible: String, recent: String): Boolean {
            fun tail(s: String) = s.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.takeLast(3)
            val v = tail(visible)
            return v.isEmpty() || v == tail(recent)
        }
    }
}
