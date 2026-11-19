package com.billmongan.herdrdroid.voice

import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.transcript.Transcript
import com.billmongan.herdrdroid.transcript.TranscriptRead
import org.json.JSONObject
import com.billmongan.herdrdroid.transcript.Item
import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.UserItem

internal enum class ConfirmationGrammar(val grammar: String) {
    ANSWER("""["confirm answer","cancel answer","repeat that","[unk]"]"""),
    MESSAGE("""["confirm message","cancel message","repeat that","[unk]"]"""),
}

/** An in-memory, single-use draft bound to an exact connection and pane visit. */
internal class VoiceMessageDraft<T>(private val now: () -> Long) {
    data class Draft<T>(val target: T, val label: String, val text: String, val expires: Long)
    private var draft: Draft<T>? = null
    private var readBack = false

    fun begin(target: T, label: String) { clear(); draft = Draft(target, label, "", now() + 45_000) }
    fun clear() { draft = null; readBack = false }
    fun isFresh(draft: Draft<T>) = now() < draft.expires
    fun isPending(target: T?) = draft?.let { it.target == target && isFresh(it) } == true
    fun activeTarget(valid: (T) -> Boolean): T? = draft?.takeIf { isFresh(it) && valid(it.target) }?.target

    fun dictate(target: T?, text: String): Draft<T>? {
        val selected = draft
        clear()
        if (selected == null || selected.target != target || !isFresh(selected) || selected.text.isNotEmpty()) return null
        return selected.copy(text = text, expires = now() + 45_000).also { draft = it }
    }

    fun onReadBack(draft: Draft<T>) {
        if (this.draft === draft && isFresh(draft)) readBack = true
    }

    fun consume(target: T?): Draft<T>? {
        val selected = draft.takeIf { readBack }
        clear()
        return selected?.takeIf { it.target == target && it.text.isNotBlank() && isFresh(it) }
    }
}

internal data class VoiceReplySnapshot(val session: String, val status: Status, val items: List<Item>)

/** Seeds existing history silently; only fresh, stable assistant text at the end of the conversation qualifies. */
internal class NewVoiceReplies {
    private var session: String? = null
    private val seen = mutableSetOf<String>()
    private var candidate: List<TextItem> = emptyList()
    fun interrupt() { candidate = emptyList() }
    fun clear() { session = null; seen.clear(); interrupt() }

    fun observe(snapshot: VoiceReplySnapshot): String? {
        val items = snapshot.items
        if (session != snapshot.session) {
            clear()
            session = snapshot.session
            val lastUser = items.indexOfLast { it is UserItem }
            val inProgress = if (snapshot.status == Status.WORKING && lastUser >= 0) {
                // Thinking and tool activity can follow the in-progress assistant text.
                items.subList(lastUser + 1, items.size).lastOrNull { it is TextItem }?.key
            } else null
            seen += items.map { it.key }.filterNot { it == inProgress }
            return null
        }
        val currentTurn = items.drop(items.indexOfLast { it is UserItem } + 1)
        // Ignore assistant blocks before a newer user turn even if they weren't read yet.
        seen += items.take(items.size - currentTurn.size).map { it.key }
        val next = currentTurn.filterIsInstance<TextItem>().filter { it.text.isNotBlank() && it.key !in seen }
        if (snapshot.status !in setOf(Status.DONE, Status.IDLE) || next.isEmpty()) {
            candidate = emptyList()
            return null
        }
        if (next != candidate) {
            candidate = next
            return null
        }
        seen += next.map { it.key }
        candidate = emptyList()
        return next.joinToString("\n") { it.text }
    }
}


/** Only a live pane identity corroborated by the transcript cursor can be spoken. */
internal suspend fun attributedReply(
    paneId: String,
    agent: String,
    cwd: String,
    panes: suspend () -> List<Pane>,
    transcript: suspend (Pane) -> TranscriptRead?,
): VoiceReplySnapshot? {
    fun select(list: List<Pane>): Pane? {
        if (cwd.isBlank()) return null
        val match = list.filter { it.agent == agent && it.cwd.trimEnd('/') == cwd.trimEnd('/') }.singleOrNull()
        return match?.takeIf { it.id == paneId && !it.sessionId.isNullOrBlank() }
    }
    val before = select(panes()) ?: return null
    val read = transcript(before)?.takeIf { it.ok && it.reset } ?: return null
    val cursor = runCatching { JSONObject(read.cursor) }.getOrNull() ?: return null
    val sessionId = before.sessionId ?: return null
    if (cursor.has("pin") || cursor.has("pin_sid")) return null
    val file = cursor.optString("file").substringAfterLast('/').substringAfterLast('\\')
    val cursorMatches = when {
        cursor.optString("sid").isNotBlank() -> cursor.optString("sid") == sessionId
        agent == "claude" -> file == "$sessionId.jsonl"
        agent == "codex" -> file.startsWith("rollout-") && file.endsWith("-$sessionId.jsonl")
        else -> false
    }
    if (!cursorMatches) return null
    val after = select(panes())?.takeIf { it.sessionId == sessionId } ?: return null
    val status = if (before.status == Status.WORKING) Status.WORKING else after.status
    return VoiceReplySnapshot(sessionId, status, Transcript.merge(emptyList(), read.items, reset = true))
}


internal class SelectedChatVisit {
    @Volatile var revision: Long = 0
        private set

    fun navigate(from: String?, to: String?) {
        if (from != to) reopen()
    }

    fun reopen() { revision++ }
}
