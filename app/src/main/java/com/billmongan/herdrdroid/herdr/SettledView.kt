package com.billmongan.herdrdroid.herdr

/**
 * When a pane just opened shows its freshly read screen (2.2.0, Bill: "hold on refreshing the pane
 * view until that settles ... don't do it by time but do it by ... when you actually have the right
 * data"). Switching panes used to draw the first screen read at once; a read taken while the agent
 * was redrawing, or the terminal-parsed view of an agent whose chat log had not loaded yet, showed
 * for a moment and then jumped to the right view.
 *
 * The gate decides from the reads alone, with no timer. A visit's reads are held until one of:
 * - two reads in a row return identical text ([Why.IDENTICAL]), the pattern the usage-close check uses;
 * - a read that must show at once ([Why.URGENT]): a question, an approval, an error, a dialog, the
 *   pane needing you, or the agent working (a live stream is settled enough to show);
 * - [maxReads] reads that never matched ([Why.CAPPED]): the newest read is shown, and the caller logs it.
 *
 * Once a visit has settled, every later read of it shows as before: the gate only covers the switch.
 * A new [visit] key (another pane, server or visit) starts over.
 */
internal class SettleGate(private val maxReads: Int = MAX_READS) {
    enum class Why { IDENTICAL, URGENT, CAPPED }

    /** [show] when the read is to be drawn; [why] says what settled it, on the read that did. */
    data class Verdict(val show: Boolean, val why: Why?, val reads: Int)

    private var visit: Any? = null
    private var last: String? = null
    private var reads = 0

    /** True once the current visit has settled. */
    var settled = false
        private set

    /** Starts a visit over, held until its reads settle. */
    fun reset(visit: Any?) {
        this.visit = visit
        last = null
        reads = 0
        settled = false
    }

    /** Whether [visit] is the visit the gate follows and has settled. */
    fun settledFor(visit: Any?) = settled && this.visit == visit

    fun offer(visit: Any?, text: String, urgent: Boolean): Verdict {
        if (visit != this.visit) reset(visit)
        if (settled) return Verdict(true, null, reads)
        reads++
        val why = when {
            urgent -> Why.URGENT
            last != null && last == text -> Why.IDENTICAL
            reads >= maxReads -> Why.CAPPED
            else -> null
        }
        last = text
        if (why != null) settled = true
        return Verdict(why != null, why, reads)
    }

    companion object {
        /** At the open pane's usual read cadence, a bounded few reads; then the newest one shows. */
        const val MAX_READS = 5
    }
}

/**
 * A read that shows at once, whatever the gate holds (2.2.0): the needs-you alert, a question or
 * approval read from it, an agent's error, a question dialog or a draft card on screen, or a working agent (its
 * stream is the view; holding it would hide live output).
 */
internal fun urgentRead(needsYou: Boolean, working: Boolean, question: Boolean, error: Boolean, dialog: Boolean): Boolean =
    needsYou || working || question || error || dialog

/**
 * Whether the screen view (the terminal parsed into a chat) may stand in for an agent's chat log
 * (2.2.0). An agent with a log reader shows its log; while that log has not answered yet for this
 * visit, the screen view would show for a moment and then be replaced, so it waits. It shows when
 * the agent has no reader, the reader said there is no log, or [gaveUp] (the log never answered
 * within a bounded number of settled screen reads).
 */
internal fun screenViewAllowed(hasReader: Boolean, transcriptAnswered: Boolean, gaveUp: Boolean): Boolean =
    !hasReader || transcriptAnswered || gaveUp

/** How many settled screen reads a visit waits for its chat log before showing the screen view instead. */
internal const val TRANSCRIPT_WAIT_READS = 6
