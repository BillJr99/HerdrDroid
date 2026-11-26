package com.billmongan.herdrdroid.questions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 2.2.8: keeps the Questions view live while it is on screen. Each round takes the panes needing
 * you ([sources], from the lists the app already keeps: the open server's and the watcher's), reads
 * the ones due ([read], over each server's one shared connection, with the card's two-read
 * confirmation), and publishes [entries]. A round runs when [poke]d (a pane's state changed: the
 * screen pokes on every change of the lists, which the status push and the watcher keep current)
 * and every [POLL_MS] as the fallback. Nothing runs while the view is hidden ([hide]).
 */
class QuestionsFeed(
    private val scope: CoroutineScope,
    private val sources: () -> List<QuestionSource>,
    private val read: suspend (QuestionSource) -> QuestionRead?,
    /** An answer to this pane is being sent now (the per-pane card's own in-flight flag). */
    private val inFlight: (QuestionSource) -> Boolean,
    /** The most a single pane's read may take before it counts as failed (a slow server never holds the others). */
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val clock = clock

    /** The list's state; a new one each time the view is hidden, so nothing shown before (or its targets) outlives a visit. */
    var aggregator = QuestionsAggregator(clock)
        private set

    var entries by mutableStateOf<List<QuestionEntry>>(emptyList())
        private set

    /** Entries the screen holds still, kept apart by why: a finger on it, and a typed answer open. */
    private val pressHolds = HashSet<String>()
    private val typingHolds = HashSet<String>()
    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val reads = Semaphore(MAX_PARALLEL_READS)

    /** Whether the view is on screen and the loop runs. */
    var visible = false
        private set

    fun show() {
        visible = true
        if (job?.isActive != true) job = scope.launch {
            // A previous visit can still be unwinding a cancelled read. This loop must
            // keep its own refresh rather than leave it with that cancelled visit.
            while (isActive && visible) {
                refreshRound(waitForRound = true)
                val wait = if (entries.any { it.state == EntryState.ANSWERED }) QuestionsAggregator.ANSWERED_SHOW_MS else POLL_MS
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
        poke()
    }

    /** Stops reading and forgets the list: its entries, their targets and every hold. */
    fun hide() {
        visible = false
        job?.cancel()
        job = null
        pressHolds.clear()
        typingHolds.clear()
        aggregator = QuestionsAggregator(clock)
        entries = emptyList()
    }

    /** Something changed (a pane's state, an answer sent) or Refresh was tapped: a round now, through the loop's one conflated wake. */
    fun poke() { wake.trySend(Unit) }

    /** Shows why an answer from the view wasn't sent on its entry; on the feed's own thread. */
    fun note(key: String, text: String) {
        scope.launch { aggregator.note(key, text); publish() }
    }

    /** A finger is on the entry (released in a finally when the press ends or is cancelled). */
    fun holdPress(key: String) { pressHolds += key; aggregator.hold(key) }

    fun releasePress(key: String) { if (pressHolds.remove(key)) poke() }

    /** Its typed-answer box is open (released when the box leaves the screen, however it goes). */
    fun holdTyping(key: String) { typingHolds += key; aggregator.hold(key) }

    fun releaseTyping(key: String) { if (typingHolds.remove(key)) poke() }

    internal fun isHeldByUi(key: String) = key in pressHolds || key in typingHolds

    /** One round at a time: a round asked for while one runs makes that one run once more, never a second beside it. */
    private val round = Mutex()
    @Volatile private var again = false

    /**
     * A round now, or (when one is running) once more right after it. Repeated asks while a round
     * runs add at most one more round, and its reads are only of panes due by then.
     */
    suspend fun refreshNow() = refreshRound(waitForRound = false)

    private suspend fun refreshRound(waitForRound: Boolean) {
        if (waitForRound) round.lock()
        else if (!round.tryLock()) { again = true; return }
        try {
            do { again = false; oneRound() } while (again)
        } finally {
            round.unlock()
        }
    }

    /** The list of panes needing you, then reads of the ones due. */
    private suspend fun oneRound() {
        syncHolds()
        aggregator.update(sources())
        publish()
        val due = aggregator.due(REREAD_MS)
        if (due.isEmpty()) return
        val agg = aggregator
        coroutineScope {
            for (s in due) {
                val token = agg.beginRead(s.key)
                launch {
                    val r = reads.withPermit {
                        try { withTimeoutOrNull(readTimeoutMs) { read(s) } } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
                    }
                    // Each read shows as soon as it is in, so one slow server never holds the others back.
                    if (agg !== aggregator) return@launch
                    syncHolds()
                    // The list as it is now: a pane that stopped needing you during the read is settled, not re-filled.
                    agg.update(sources())
                    agg.applyRead(s, r, token)
                    publish()
                }
            }
        }
    }

    private fun syncHolds() {
        for (e in aggregator.all()) {
            if (isHeldByUi(e.key) || inFlight(e.source)) aggregator.hold(e.key) else aggregator.release(e.key)
        }
    }

    private fun publish() {
        val next = aggregator.entries()
        if (next != entries) entries = next
    }

    companion object {
        /** The fallback round while the view is on screen. */
        const val POLL_MS = 3_000L
        /** A question already shown is read again after this long (a new question in the same dialog). */
        const val REREAD_MS = 5_000L
        /** Reads at once, over the shared connections. */
        const val MAX_PARALLEL_READS = 3
        /** A pane's read (its confirmation reads included) that takes longer than this counts as failed. */
        const val READ_TIMEOUT_MS = 15_000L
    }
}
