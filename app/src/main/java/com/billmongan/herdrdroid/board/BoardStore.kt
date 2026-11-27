package com.billmongan.herdrdroid.board

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class BoardConflict(
    val cardId: String,
    val field: String,
    val phoneValue: String,
    val serverValue: String,
)

data class BoardState(
    val phone: Map<String, BoardCard> = emptyMap(),
    val servers: Map<String, ServerBoard> = emptyMap(),
)

data class ServerBoard(
    val name: String? = null,
    val asOf: String? = null,
    internal val entries: Map<String, BoardCard> = emptyMap(),
    internal val baseline: Map<String, BoardCard> = emptyMap(),
    internal val creates: Set<String> = emptySet(),
    internal val pendingNotes: Map<String, List<BoardNote>> = emptyMap(),
    internal val conflicted: Map<String, Set<String>> = emptyMap(),
    val issue: String? = null,
    val request: BoardRequest? = null,
    val requestSentAt: Long? = null,
    val noAnswer: Boolean = false,
    val lastExportAt: Long? = null,
    internal val previousExportAt: Long? = null,
    internal val attempted: Boolean = false,
    internal val previouslyAttempted: Boolean = false,
) {
    val cards: Map<String, BoardCard> get() = entries.mapValues { (id, card) ->
        card.copy(notes = card.notes + pendingNotes[id].orEmpty())
    }

    val conflicts: List<BoardConflict> get() = conflicted.flatMap { (id, fields) ->
        fields.map { field ->
            BoardConflict(
                id, field,
                if (field == DELETED_FIELD) entries[id]?.title.orEmpty() else label(entries[id]?.fields()?.get(field)),
                if (field == DELETED_FIELD) "Deleted on server" else label(baseline[id]?.fields()?.get(field)),
            )
        }
    }
}

private const val DELETED_FIELD = "deleted"
private fun label(value: Any?): String = when (value) {
    null -> "(empty)"
    is List<*> -> if (value.isEmpty()) "(empty)" else value.joinToString("\n") {
        if (it is BoardNote) "${it.at} ${it.by}: ${it.text}" else it.toString()
    }
    else -> value.toString()
}

class BoardStore internal constructor(
    initial: BoardState,
    private val persist: (BoardState) -> Boolean,
) {
    private constructor(context: Context, storage: String) : this(
        initial = context.getSharedPreferences(storage, Context.MODE_PRIVATE).getString("state", null)
            ?.let { runCatching { boardStateFromJson(JSONObject(it)) }.getOrNull() } ?: BoardState(),
        persist = { state ->
            context.getSharedPreferences(storage, Context.MODE_PRIVATE).edit()
                .putString("state", boardStateJson(state).toString()).commit()
        },
    )

    constructor(context: Context) : this(context.applicationContext, "board_v1")

    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<BoardState> = mutable.asStateFlow()

    @Synchronized
    private fun mutate(block: (BoardState) -> BoardState): Boolean {
        val old = state.value
        val new = block(old)
        if (new == old) return false
        if (!persist(new)) return false
        mutable.value = new
        return true
    }

    private fun server(id: String, change: (ServerBoard) -> ServerBoard): Boolean = mutate { state ->
        val old = state.servers[id] ?: ServerBoard()
        val new = change(old)
        if (new == old) state else state.copy(servers = state.servers + (id to new))
    }

    fun saveCard(serverId: String?, card: BoardCard): Boolean {
        if (serverId == null) return mutate { it.copy(phone = it.phone + (card.id to card)) }
        return server(serverId) { board ->
            val new = card.id !in board.entries
            val pending = board.pendingNotes[card.id].orEmpty()
            val kept = if (pending.isNotEmpty() && card.notes.takeLast(pending.size) == pending) {
                card.copy(notes = card.notes.dropLast(pending.size))
            } else card
            board.copy(
                entries = board.entries + (card.id to kept),
                creates = if (new) board.creates + card.id else board.creates,
                pendingNotes = if (pending.isNotEmpty() && kept == card) board.pendingNotes - card.id else board.pendingNotes,
            ).afterEdit()
        }
    }

    fun movePhoneCard(id: String, serverId: String): Boolean = mutate { state ->
        val card = state.phone[id] ?: return@mutate state
        val board = state.servers[serverId] ?: ServerBoard()
        if (id in board.entries) return@mutate state
        state.copy(
            phone = state.phone - id,
            servers = state.servers + (serverId to board.copy(
                entries = board.entries + (id to card), creates = board.creates + id,
            ).afterEdit()),
        )
    }

    fun addNote(serverId: String?, id: String, note: BoardNote): Boolean {
        if (serverId == null) return mutate { state ->
            val card = state.phone[id] ?: return@mutate state
            state.copy(phone = state.phone + (id to card.copy(notes = card.notes + note)))
        }
        return server(serverId) { board ->
            val card = board.entries[id] ?: return@server board
            if (id in board.creates && !(board.attempted && board.request?.changes.orEmpty().any {
                it is BoardChange.Create && it.clientId == id
            })) board.copy(entries = board.entries + (id to card.copy(notes = card.notes + note))).afterEdit()
            else board.copy(pendingNotes = board.pendingNotes + (id to (board.pendingNotes[id].orEmpty() + note))).afterEdit()
        }
    }

    fun resolve(serverId: String, cardId: String, field: String, keepPhone: Boolean): Boolean = server(serverId) { board ->
        val fields = board.conflicted[cardId].orEmpty()
        if (field !in fields) return@server board
        val remaining = fields - field
        val cleared = board.copy(conflicted = if (remaining.isEmpty()) board.conflicted - cardId else board.conflicted + (cardId to remaining))
        val phone = cleared.entries[cardId] ?: return@server cleared
        if (field == DELETED_FIELD) {
            if (keepPhone) cleared.copy(creates = cleared.creates + cardId).afterEdit()
            else cleared.copy(entries = cleared.entries - cardId, pendingNotes = cleared.pendingNotes - cardId).afterEdit()
        } else if (keepPhone) cleared.afterEdit() else {
            val remote = cleared.baseline[cardId] ?: return@server cleared
            cleared.copy(
                entries = cleared.entries + (cardId to phone.withFields(mapOf(field to remote.fields()[field]))),
                pendingNotes = if (field == "notes") cleared.pendingNotes - cardId else cleared.pendingNotes,
            ).afterEdit()
        }
    }

    fun queueExport(serverId: String, now: Long): Boolean = server(serverId) { board ->
        if (board.request != null && (board.request.kind == BoardRequestKind.EXPORT || board.attempted || board.noAnswer) ||
            !board.exportDue(now)) board
        else board.copy(request = BoardRequest(BoardRequestKind.EXPORT, board.asOf), issue = null)
    }

    fun queueApply(serverId: String): Boolean = server(serverId) { board ->
        if (board.request != null || board.issue != null) return@server board
        board.withPendingApply()
    }

    fun canSend(serverId: String, now: Long): Boolean {
        val board = state.value.servers[serverId] ?: return false
        return board.request != null && board.requestSentAt == null && !board.noAnswer &&
            (board.request.kind != BoardRequestKind.EXPORT || board.exportDue(now))
    }

    fun markSent(serverId: String, requestId: String, now: Long): Boolean = server(serverId) { board ->
        val request = board.request ?: return@server board
        if (request.id != requestId || board.requestSentAt != null || board.noAnswer) return@server board
        val isExport = request.kind == BoardRequestKind.EXPORT
        if (isExport && !board.exportDue(now)) return@server board
        board.copy(
            requestSentAt = now, attempted = true, previouslyAttempted = board.attempted,
            lastExportAt = if (isExport) now else board.lastExportAt,
            previousExportAt = if (isExport) board.lastExportAt else null,
        )
    }

    fun markQueued(serverId: String, requestId: String): Boolean = server(serverId) { board ->
        if (board.request?.id != requestId || board.requestSentAt == null) return@server board
        // Only invoked when transport proves that this particular attempt wrote nothing.
        val isExport = board.request.kind == BoardRequestKind.EXPORT
        board.copy(
            requestSentAt = null,
            lastExportAt = if (isExport) board.previousExportAt else board.lastExportAt,
            previousExportAt = null, attempted = board.previouslyAttempted,
            previouslyAttempted = false,
        ).let { queued ->
            if (!queued.attempted && !isExport) queued.withPendingApply() else queued
        }
    }

    fun markNoAnswer(serverId: String, requestId: String, reason: String = "No answer from board agent"): Boolean =
        server(serverId) { board ->
            if (board.request?.id != requestId) board else board.copy(noAnswer = true, issue = reason)
        }

    fun retry(serverId: String): Boolean = server(serverId) { board ->
        if (board.request != null && board.noAnswer) {
            // A response may have been lost after an apply. Reuse the ID and exact payload.
            board.copy(requestSentAt = null, noAnswer = false, issue = null)
        } else if (board.request == null && board.issue != null) {
            board.copy(issue = null).withPendingApply()
        } else board
    }

    fun receive(serverId: String, reply: BoardReply): Boolean = server(serverId) { board ->
        val request = board.request ?: return@server board
        if (!board.attempted || !BoardProtocol.validReply(reply, request)) return@server board
        board.accept(request, reply)
    }
}

private fun ServerBoard.exportDue(now: Long): Boolean =
    lastExportAt?.let { now >= it && now - it >= BoardProtocol.EXPORT_INTERVAL_MS } ?: true

private fun ServerBoard.afterEdit(): ServerBoard {
    if (request != null && (attempted || request.kind == BoardRequestKind.EXPORT || noAnswer)) return this
    return copy(issue = null).withPendingApply()
}

private fun ServerBoard.withPendingApply(): ServerBoard {
    val changes = buildList {
        entries.forEach { (id, phone) ->
            if (id in conflicted) return@forEach
            if (id in creates) {
                add(BoardChange.Create(id, phone.copy(notes = phone.notes + pendingNotes[id].orEmpty())))
                return@forEach
            }
            val remote = baseline[id]?.fields() ?: return@forEach
            val changed = phone.fields().filter { (field, value) -> value != remote[field] }
            if (changed.isNotEmpty()) add(BoardChange.Update(id, remote.filterKeys { it in changed }, changed))
            pendingNotes[id].orEmpty().forEach { add(BoardChange.Note(id, it.text)) }
        }
    }
    if (changes == request?.changes) return this
    return copy(request = if (changes.isEmpty()) null else BoardRequest(BoardRequestKind.APPLY, asOf, changes))
}

private fun ServerBoard.accept(request: BoardRequest, reply: BoardReply): ServerBoard {
    val local = entries.toMutableMap()
    val known = baseline.toMutableMap()
    val pending = pendingNotes.toMutableMap()
    val newIds = creates.toMutableSet()
    val conflicts = conflicted.toMutableMap()
    val deleted = reply.deleted.toMutableSet()
    if (request.kind == BoardRequestKind.EXPORT && request.since == null) {
        deleted += known.keys - reply.cards.map { it.id }.toSet()
    }
    val incoming = reply.cards.associateBy { it.id }.toMutableMap()
    val issues = mutableListOf<String>()
    request.changes.zip(reply.results).forEach { (change, result) ->
        if (!result.ok) {
            if (result.error != "conflict") issues += result.reason.orEmpty().ifBlank { result.error.orEmpty() }
            if (result.error == "unknown_id" && change !is BoardChange.Create) deleted += result.id
            result.current?.let { if (it.id !in incoming) incoming[it.id] = it }
            return@forEach
        }
        when (change) {
            is BoardChange.Create -> {
                val id = change.clientId
                val notesSent = pending[id].orEmpty()
                val inFlight = local[id]
                newIds.remove(id)
                local.remove(id)
                pending.remove(id)
                if (inFlight != null) local[result.id] = inFlight.copy(id = result.id, notes = inFlight.notes + notesSent)
                known[result.id] = change.card.copy(id = result.id)
            }
            is BoardChange.Update -> known[change.id]?.let { known[change.id] = it.withFields(change.set) }
            is BoardChange.Note -> {
                val notes = pending[change.id].orEmpty()
                val match = notes.indexOfFirst { it.text == change.text }
                if (match >= 0) pending[change.id] = notes.filterIndexed { index, _ -> index != match }
            }
        }
    }
    deleted.forEach { id ->
        val old = known.remove(id)
        val phone = local[id]
        if (id !in newIds && phone != null) {
            if (id in conflicts || phone != old || pending[id].orEmpty().isNotEmpty()) conflicts[id] = setOf(DELETED_FIELD)
            else {
                local.remove(id)
                pending.remove(id)
            }
        }
    }
    incoming.values.forEach { remote ->
        val id = remote.id
        if (id in deleted || id in newIds) return@forEach
        val phone = local[id]
        val old = known.put(id, remote)
        if (phone == null || old == null && id !in conflicts) {
            local[id] = remote
            return@forEach
        }
        val existing = conflicts[id].orEmpty()
        val conflictFields = mutableSetOf<String>()
        val oldFields = old?.fields().orEmpty()
        val remoteFields = remote.fields()
        val merged = phone.fields().mapValues { (field, phoneValue) ->
            val serverValue = remoteFields[field]
            val fieldChangedOnPhone = phoneValue != oldFields[field]
            val fieldChangedOnServer = serverValue != oldFields[field]
            if (field in existing || phoneValue != serverValue && (DELETED_FIELD in existing ||
                    fieldChangedOnPhone && fieldChangedOnServer)) {
                conflictFields += field
                phoneValue
            } else if (fieldChangedOnPhone) phoneValue else serverValue
        }
        local[id] = phone.withFields(merged)
        if (conflictFields.isEmpty()) conflicts.remove(id) else conflicts[id] = conflictFields
    }
    return copy(
        name = reply.board,
        asOf = if (request.kind == BoardRequestKind.EXPORT) reply.asOf else asOf,
        entries = local, baseline = known, creates = newIds, pendingNotes = pending.filterValues { it.isNotEmpty() },
        conflicted = conflicts, issue = issues.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        request = null, requestSentAt = null, noAnswer = false, attempted = false, previouslyAttempted = false, previousExportAt = null,
    )
}
