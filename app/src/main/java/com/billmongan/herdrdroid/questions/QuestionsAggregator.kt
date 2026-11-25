package com.billmongan.herdrdroid.questions

import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Prompt

/*
 * 2.2.8 (Bill, 2026-11-24: "in the left menu that lists the agents in chat mode, another menu item
 * at the bottom called Questions ... it shows me all the open question cards in a compact view from
 * all the agents it can see, with the appropriate buttons, so I can resolve all the permissions and
 * see what's being asked and what the choices are"; and "have the Questions view auto-refresh so as
 * new questions come along they appear, and as questions get answered they disappear").
 *
 * The list's state, with no Android or SSH in it, so it can be tested on its own. Every pane the app
 * shows as needing you is a [QuestionSource]; each becomes one [QuestionEntry]. Its question comes
 * from a confirmed read of the pane's screen ([applyRead]), the same two-read confirmation the open
 * pane's card uses. The list stays still under the finger: an entry that is [hold]ing (a button
 * pressed, an answer in flight, a typed answer being written) is never re-read, reordered or
 * removed; an entry whose pane no longer needs you shows "Answered" for [ANSWERED_SHOW_MS] first.
 */

/** A pane the app shows as needing you, on the open server ([current]) or one the app watches. */
data class QuestionSource(
    val serverId: String,
    val serverLabel: String,
    val current: Boolean,
    val pane: Pane,
) {
    val key: String get() = "$serverId|${pane.id}"
}

enum class EntryState {
    /** Not read yet, or being read: the question shows once two reads agree. */
    READING,
    /** A confirmed question with its answers. */
    READY,
    /** The pane needs you, but its screen showed no question the app could read. */
    UNREAD,
    /** The pane no longer needs you, or its dialog closed: shown briefly, then removed. */
    ANSWERED,
}

/** One open question. [target] is what an answer is sent through (the open server's card target), or null. */
data class QuestionEntry(
    val source: QuestionSource,
    val state: EntryState,
    /** When this question was first seen waiting (milliseconds). */
    val since: Long,
    val prompt: Prompt? = null,
    val target: Any? = null,
    val readAt: Long = 0L,
    val answeredAt: Long = 0L,
    /** Why the last answer from the view wasn't sent, or what changed in the pane; shown on the entry. */
    val note: String? = null,
) {
    val key: String get() = source.key
}

/** One read of a pane for the list: the confirmed card (null when the screen shows none) and the target bound before the read. */
data class QuestionRead(val prompt: Prompt?, val target: Any?)

class QuestionsAggregator(private val clock: () -> Long = System::currentTimeMillis) {
    private val byKey = LinkedHashMap<String, QuestionEntry>()
    private val held = HashSet<String>()
    /** Per pane: the last read handed out ([beginRead]) and the newest one applied, so a slower older read never overwrites a newer one. */
    private val issued = HashMap<String, Long>()
    private val applied = HashMap<String, Long>()

    /** A token for a read of [key] about to start; reads are applied only newest-first ([applyRead]). */
    fun beginRead(key: String): Long = ((issued[key] ?: 0L) + 1).also { issued[key] = it }

    /** Entries grouped by server (the open one first, then by name), oldest waiting first in each. */
    fun entries(): List<QuestionEntry> = byKey.values.sortedWith(
        compareBy<QuestionEntry>({ !it.source.current }, { it.source.serverLabel.lowercase() }, { it.source.serverId },
            { it.since }, { it.source.pane.id }),
    )

    /** How many questions are still open (an entry showing "Answered" doesn't count). */
    fun openCount(): Int = byKey.values.count { it.state != EntryState.ANSWERED }

    fun entry(key: String): QuestionEntry? = byKey[key]

    fun isHeld(key: String): Boolean = key in held

    /** Holds an entry still: no re-read, reorder or removal until [release]. */
    fun hold(key: String) { held += key }

    fun release(key: String) { held -= key }

    /** Shows [text] on the entry (why an answer wasn't sent); a new question in it clears it. */
    fun note(key: String, text: String) { byKey[key]?.let { byKey[key] = it.copy(note = text) } }

    /**
     * The panes needing you now. A new one is added as READING; one whose pane changed occupant
     * (another agent or session in the same pane) starts again; one no longer listed shows
     * "Answered", then goes after [ANSWERED_SHOW_MS]. Held entries are left exactly as they are.
     */
    fun update(sources: List<QuestionSource>) {
        val now = clock()
        val listed = sources.associateBy { it.key }
        for ((key, src) in listed) {
            if (key in held) continue
            val old = byKey[key]
            byKey[key] = when {
                old == null -> QuestionEntry(src, EntryState.READING, since = now)
                !sameOccupant(old.source.pane, src.pane) -> QuestionEntry(src, EntryState.READING, since = now, note = OCCUPANT_NOTE)
                // Its dialog closed while herdr still says it needs you: "Answered" shows its full
                // time; once it has gone, a pane still needing you is read again as a new entry.
                old.state == EntryState.ANSWERED -> old
                else -> old.copy(source = src)
            }
        }
        val gone = byKey.keys.filter { it !in listed && it !in held }
        for (key in gone) settle(key, now)
        prune(now)
    }

    /**
     * A confirmed read of [source]'s pane, started with [token] from [beginRead]. Ignored when a
     * newer read of the pane was already taken, when the entry is held, gone, or now belongs to
     * another occupant (a read that started before the change). A different question than before
     * starts its own wait time. No question on a pane that showed one means its dialog closed.
     */
    fun applyRead(source: QuestionSource, read: QuestionRead?, token: Long = beginRead(source.key)) {
        val key = source.key
        // An older read finishing after a newer one was applied (or dropped) is superseded.
        if (token <= (applied[key] ?: 0L)) return
        applied[key] = token
        if (key in held) return
        val old = byKey[key] ?: return
        if (old.state == EntryState.ANSWERED || !sameOccupant(old.source.pane, source.pane)) return
        val now = clock()
        byKey[key] = when {
            // The read itself failed: keep what is shown, try again next time.
            read == null -> old.copy(readAt = now)
            read.prompt == null -> if (old.state == EntryState.READY) old.copy(state = EntryState.ANSWERED, answeredAt = now, target = null, readAt = now)
                else old.copy(state = EntryState.UNREAD, readAt = now, target = null)
            old.state == EntryState.READY && !sameQuestion(old.prompt, read.prompt) ->
                QuestionEntry(old.source, EntryState.READY, since = now, prompt = read.prompt, target = read.target, readAt = now)
            else -> old.copy(state = EntryState.READY, prompt = read.prompt, target = read.target, readAt = now)
        }
    }

    /** The entries due a read: not held, not answered, and not read in the last [rereadMs]. */
    fun due(rereadMs: Long): List<QuestionSource> {
        val now = clock()
        return entries().filter { it.key !in held && it.state != EntryState.ANSWERED &&
            (it.state == EntryState.READING || now - it.readAt >= rereadMs) }.map { it.source }
    }

    /** Removes "Answered" entries shown long enough; true when any went. */
    fun prune(now: Long = clock()): Boolean {
        val old = byKey.values.filter { it.state == EntryState.ANSWERED && it.key !in held && now - it.answeredAt >= ANSWERED_SHOW_MS }
        old.forEach { byKey.remove(it.key) }
        return old.isNotEmpty()
    }

    private fun settle(key: String, now: Long) {
        val old = byKey[key] ?: return
        if (old.state == EntryState.ANSWERED) return
        // Never read as a question (still reading, or unreadable): nothing to show as answered.
        if (old.state != EntryState.READY) { byKey.remove(key); return }
        byKey[key] = old.copy(state = EntryState.ANSWERED, answeredAt = now, target = null)
    }

    companion object {
        /** How long an answered entry stays, saying so, before it goes. */
        const val ANSWERED_SHOW_MS = 1_500L
        /** What an entry says once another agent or session took its pane. */
        const val OCCUPANT_NOTE = "Another agent or session is in this pane now; this is what it shows."

        /** The same agent session in the same terminal: what the card's own occupant check compares. */
        fun sameOccupant(a: Pane, b: Pane): Boolean = a.id == b.id && a.agent == b.agent && a.sessionId == b.sessionId &&
            a.terminalId == b.terminalId && a.tabId == b.tabId && a.workspaceId == b.workspaceId && a.cwd == b.cwd

        /** The same question: its words and its answers (the highlight may move). */
        fun sameQuestion(a: Prompt?, b: Prompt?): Boolean = a != null && b != null &&
            a.question.filterNot(Char::isWhitespace) == b.question.filterNot(Char::isWhitespace) &&
            a.choices.map { it.label to it.keys } == b.choices.map { it.label to it.keys }
    }
}
