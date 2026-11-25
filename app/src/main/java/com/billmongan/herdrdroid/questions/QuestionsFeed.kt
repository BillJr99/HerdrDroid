package com.billmongan.herdrdroid.questions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    clock: () -> Long = System::currentTimeMillis,
) {
    val aggregator = QuestionsAggregator(clock)

    var entries by mutableStateOf<List<QuestionEntry>>(emptyList())
        private set

    /** Entries the screen holds still (a button pressed, a typed answer open). */
    private val uiHolds = HashSet<String>()
    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val reads = Semaphore(MAX_PARALLEL_READS)

    /** Whether the view is on screen and the loop runs. */
    var visible = false
        private set

    fun show() {
        visible = true
        if (job?.isActive != true) job = scope.launch {
            while (isActive && visible) {
                refreshNow()
                val wait = if (entries.any { it.state == EntryState.ANSWERED }) QuestionsAggregator.ANSWERED_SHOW_MS else POLL_MS
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
        poke()
    }

    fun hide() {
        visible = false
        job?.cancel()
        job = null
    }

    /** Something changed (a pane's state, an answer sent) or Refresh was tapped: a round now, through the loop's one conflated wake. */
    fun poke() { wake.trySend(Unit) }

    fun holdUi(key: String) { uiHolds += key; aggregator.hold(key) }

    fun releaseUi(key: String) {
        uiHolds -= key
        poke()
    }

    /** One round at a time: a round asked for while one runs makes that one run once more, never a second beside it. */
    private val round = Mutex()
    @Volatile private var again = false

    /**
     * A round now, or (when one is running) once more right after it. Repeated asks while a round
     * runs add at most one more round, and its reads are only of panes due by then.
     */
    suspend fun refreshNow() {
        if (!round.tryLock()) { again = true; return }
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
        val results = coroutineScope {
            due.map { s ->
                val token = aggregator.beginRead(s.key)
                async {
                    Triple(s, token, reads.withPermit {
                        try { read(s) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
                    })
                }
            }.awaitAll()
        }
        syncHolds()
        // The list as it is now: a pane that stopped needing you during the reads is settled, not re-filled.
        aggregator.update(sources())
        results.forEach { (s, token, r) -> aggregator.applyRead(s, r, token) }
        publish()
    }

    private fun syncHolds() {
        for (e in aggregator.entries()) {
            if (e.key in uiHolds || inFlight(e.source)) aggregator.hold(e.key) else aggregator.release(e.key)
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
    }
}
