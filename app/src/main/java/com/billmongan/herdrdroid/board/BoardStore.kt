package com.billmongan.herdrdroid.board

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.Locale
import java.text.Normalizer
import java.time.Instant

data class BoardConflict(
    val cardId: String,
    val field: String,
    val phoneValue: String,
    val serverValue: String,
)

data class BoardDuplicate(val phoneId: String, val remoteIds: List<String>)

enum class BoardSyncProgress { QUEUED, APPLYING, EXPORTING, SYNCED, NO_ANSWER, CONFLICTS, REFUSED }

data class BoardState(
    val phone: Map<String, BoardCard> = emptyMap(),
    val servers: Map<String, ServerBoard> = emptyMap(),
    val phoneTargets: Map<String, String> = emptyMap(),
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
    internal val exportWanted: Boolean = false,
    internal val refusal: String? = null,
    internal val manualRound: Boolean = false,
    internal val manualRequestId: String? = null,
    internal val manualFinished: Boolean = false,
    internal val duplicateCandidates: Map<String, List<String>> = emptyMap(),
    internal val allowDuplicate: Set<String> = emptySet(),
) {
    val duplicates: List<BoardDuplicate> get() = duplicateCandidates.map { (phone, remote) -> BoardDuplicate(phone, remote) }

    val syncProgress: BoardSyncProgress? get() = when {
        noAnswer -> BoardSyncProgress.NO_ANSWER
        request != null && requestSentAt == null -> BoardSyncProgress.QUEUED
        request?.kind == BoardRequestKind.APPLY -> BoardSyncProgress.APPLYING
        request?.kind == BoardRequestKind.EXPORT -> BoardSyncProgress.EXPORTING
        conflicted.isNotEmpty() || duplicateCandidates.isNotEmpty() -> BoardSyncProgress.CONFLICTS
        refusal != null -> BoardSyncProgress.REFUSED
        manualFinished -> BoardSyncProgress.SYNCED
        else -> null
    }

    val cards: Map<String, BoardCard> get() = entries.mapValues { (id, card) ->
        card.copy(notes = unionNotes(card.notes, pendingNotes[id].orEmpty()))
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

private fun loadBoard(context: Context, storage: String): BoardState =
    context.getSharedPreferences(storage, Context.MODE_PRIVATE).getString("state", null)
        ?.let { runCatching { boardStateFromJson(JSONObject(it)) }.getOrNull() } ?: BoardState()

class BoardStore internal constructor(
    initial: BoardState,
    private val persist: (BoardState) -> Boolean,
) {
    private constructor(context: Context, storage: String) : this(
        initial = loadBoard(context, storage),
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
        if (serverId == null) return saveCard(null, card, state.value.phoneTargets[card.id])
        return server(serverId) { board ->
            val new = card.id !in board.entries
            val pending = board.pendingNotes[card.id].orEmpty()
            val pendingOnly = pending.toSet() - board.entries[card.id]?.notes.orEmpty().toSet()
            val preservesPending = card.notes.containsAll(pendingOnly)
            val kept = card.copy(notes = unionNotes(
                if (preservesPending) card.notes.filterNot { it in pendingOnly } else card.notes, emptyList(),
            ))
            board.copy(
                entries = board.entries + (card.id to kept),
                creates = if (new) board.creates + card.id else board.creates,
                pendingNotes = if (preservesPending) board.pendingNotes else board.pendingNotes - card.id,
            ).afterEdit()
        }
    }

    fun saveCard(serverId: String?, card: BoardCard, targetServerId: String?): Boolean {
        if (serverId != null) return saveCard(serverId, card)
        return mutate { state ->
            val prior = state.phoneTargets[card.id]
            val old = prior?.let(state.servers::get)
            val attempted = old?.let { board -> board.attempted && board.request?.changes.orEmpty()
                .any { it is BoardChange.Create && it.clientId == card.id } } == true
            if (attempted && prior != targetServerId) return@mutate state
            var servers = state.servers
            if (prior != null && old != null) {
                val edited = if (prior == targetServerId && card.id in old.creates) {
                    old.copy(entries = old.entries + (card.id to card)).afterEdit()
                } else {
                    old.copy(
                        entries = if (card.id in old.creates) old.entries - card.id else old.entries,
                        creates = old.creates - card.id,
                        duplicateCandidates = old.duplicateCandidates - card.id,
                        allowDuplicate = old.allowDuplicate - card.id,
                    ).afterEdit()
                }
                servers = servers + (prior to edited)
            }
            state.copy(
                phone = state.phone + (card.id to card),
                phoneTargets = if (targetServerId == null) state.phoneTargets - card.id
                    else state.phoneTargets + (card.id to targetServerId),
                servers = servers,
            )
        }
    }

    fun startSync(serverId: String, @Suppress("UNUSED_PARAMETER") now: Long): Boolean = mutate { state ->
        val board = state.servers[serverId] ?: ServerBoard()
        if (board.manualRound || board.noAnswer) return@mutate state
        val requested = board.copy(manualRound = true, manualFinished = false, exportWanted = false)
        val started = if (board.request != null && board.attempted) requested
            else state.beginManualSync(serverId, requested)
        state.copy(servers = state.servers + (serverId to started))
    }

    fun resolveDuplicate(serverId: String, phoneId: String, remoteId: String?): Boolean =
        mutate { state ->
            val board = state.servers[serverId] ?: return@mutate state
            val card = state.phone[phoneId] ?: return@mutate state
            if (state.phoneTargets[phoneId] != serverId || phoneId !in board.duplicateCandidates)
                return@mutate state
            val cleared = board.copy(duplicateCandidates = board.duplicateCandidates - phoneId)
            if (remoteId == null) {
                val staged = cleared.copy(
                    entries = cleared.entries + (phoneId to card),
                    creates = cleared.creates + phoneId,
                    allowDuplicate = cleared.allowDuplicate + phoneId,
                    manualFinished = false,
                )
                val queued = when {
                    staged.refusal != null || (staged.request != null && staged.attempted) -> staged
                    else -> staged.withPendingApply()
                }
                return@mutate state.copy(servers = state.servers + (serverId to queued))
            }
            if (remoteId !in board.duplicateCandidates.getValue(phoneId)) return@mutate state
            val remote = board.entries[remoteId] ?: return@mutate state
            val linked = remote.copy(notes = unionNotes(remote.notes, card.notes))
            val fieldChoices = (card.fields() - setOf("title", "notes", "updated_at", "native_status"))
                .filter { (field, value) -> value != remote.fields()[field] }
            state.copy(
                phone = state.phone - phoneId,
                phoneTargets = state.phoneTargets - phoneId,
                servers = state.servers + (serverId to cleared.copy(
                    entries = board.entries + (remoteId to linked.withFields(fieldChoices)),
                    conflicted = if (fieldChoices.isEmpty()) board.conflicted else board.conflicted +
                        (remoteId to (board.conflicted[remoteId].orEmpty() + fieldChoices.keys)),
                ).afterEdit()),
            )
        }

    fun movePhoneCard(id: String, serverId: String): Boolean = mutate { state ->
        val card = state.phone[id] ?: return@mutate state
        val board = state.servers[serverId] ?: ServerBoard()
        if (id in board.entries) return@mutate state
        state.copy(
            phone = state.phone - id,
            phoneTargets = state.phoneTargets - id,
            servers = state.servers + (serverId to board.copy(
                entries = board.entries + (id to card), creates = board.creates + id,
            ).afterEdit()),
        )
    }

    fun addNote(serverId: String?, id: String, note: BoardNote): Boolean {
        if (serverId == null) {
            val card = state.value.phone[id] ?: return false
            return saveCard(null, card.copy(notes = unionNotes(card.notes, listOf(note))))
        }
        return server(serverId) { board ->
            val card = board.entries[id] ?: return@server board
            if (note in card.notes || note in board.pendingNotes[id].orEmpty()) return@server board
            if (id in board.creates && !(board.attempted && board.request?.changes.orEmpty().any {
                it is BoardChange.Create && it.clientId == id
            })) board.copy(entries = board.entries + (id to card.copy(notes = unionNotes(card.notes, listOf(note))))).afterEdit()
            else board.copy(pendingNotes = board.pendingNotes + (id to unionNotes(board.pendingNotes[id].orEmpty(), listOf(note)))).afterEdit()
        }
    }

    fun resolve(serverId: String, cardId: String, field: String, keepPhone: Boolean): Boolean = server(serverId) { board ->
        val fields = board.conflicted[cardId].orEmpty()
        if (field !in fields) return@server board
        val remaining = fields - field
        val cleared = board.copy(conflicted = if (remaining.isEmpty()) board.conflicted - cardId else board.conflicted + (cardId to remaining))
        val phone = cleared.entries[cardId] ?: return@server cleared
        if (field == DELETED_FIELD) {
            if (keepPhone) {
                val clientId = UUID.randomUUID().toString()
                cleared.copy(
                    entries = cleared.entries - cardId + (clientId to phone.copy(id = clientId)),
                    creates = cleared.creates + clientId,
                    pendingNotes = cleared.pendingNotes - cardId +
                        (clientId to cleared.pendingNotes[cardId].orEmpty()),
                ).afterEdit()
            } else cleared.copy(entries = cleared.entries - cardId, pendingNotes = cleared.pendingNotes - cardId).afterEdit()
        } else if (keepPhone) cleared.afterEdit() else {
            val remote = cleared.baseline[cardId] ?: return@server cleared
            cleared.copy(
                entries = cleared.entries + (cardId to phone.withFields(mapOf(field to remote.fields()[field]))),
                pendingNotes = if (field == "notes") cleared.pendingNotes - cardId else cleared.pendingNotes,
            ).afterEdit()
        }
    }

    fun queueExport(serverId: String, now: Long): Boolean = server(serverId) { board ->
        val request = board.request
        when {
            board.manualRound || !board.exportDue(now) || request?.kind == BoardRequestKind.EXPORT -> board
            request != null && (board.attempted || board.noAnswer) -> board.copy(exportWanted = true)
            else -> board.withExport()
        }
    }

    fun queueApply(serverId: String, now: Long = System.currentTimeMillis()): Boolean =
        server(serverId) { board ->
            if (board.request != null) return@server board
            if (board.exportWanted && board.exportDue(now)) return@server board.withExport()
            if (board.issue != null) return@server board
            board.withPendingApply()
        }

    fun canSend(serverId: String, now: Long): Boolean {
        val board = state.value.servers[serverId] ?: return false
        return board.request != null && board.requestSentAt == null && !board.noAnswer &&
            (board.request.kind != BoardRequestKind.EXPORT || board.manualRequestId == board.request.id || board.exportDue(now))
    }

    fun markSent(serverId: String, requestId: String, now: Long): Boolean = server(serverId) { board ->
        val request = board.request ?: return@server board
        if (request.id != requestId || board.requestSentAt != null || board.noAnswer) return@server board
        val isExport = request.kind == BoardRequestKind.EXPORT
        if (isExport && board.manualRequestId != request.id && !board.exportDue(now)) return@server board
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
            board.copy(requestSentAt = null, noAnswer = false, issue = board.refusal)
        } else if (board.request == null && board.issue != null) {
            board.copy(issue = null, refusal = null).withPendingApply()
        } else board
    }

    fun receive(serverId: String, reply: BoardReply): Boolean = mutate { state ->
        val board = state.servers[serverId] ?: return@mutate state
        val request = board.request ?: return@mutate state
        if (!board.attempted || !BoardProtocol.validReply(reply, request)) return@mutate state
        val created = request.changes.zip(reply.results).mapNotNull { (change, result) ->
            if (change !is BoardChange.Create || !result.ok) return@mapNotNull null
            if (result.id in board.entries && result.id != change.clientId) return@mutate state
            change.clientId to result.id
        }
        val createdIds = created.map { it.second }
        if (createdIds.toSet().size != createdIds.size) return@mutate state
        val sentPhone = created.map { it.first }.filter { state.phoneTargets[it] == serverId }.toSet()
        val remaining = state.copy(phone = state.phone - sentPhone, phoneTargets = state.phoneTargets - sentPhone)
        val accepted = board.accept(request, reply).copy(allowDuplicate = board.allowDuplicate - sentPhone)
        val updated = when {
            !board.manualRound -> accepted
            board.manualRequestId != request.id -> remaining.beginManualSync(serverId, accepted)
            request.kind == BoardRequestKind.APPLY -> accepted.withManualExport()
            else -> accepted.copy(manualRound = false, manualFinished = true, manualRequestId = null)
        }
        remaining.copy(servers = state.servers + (serverId to updated))
    }
}

private fun BoardState.beginManualSync(serverId: String, board: ServerBoard): ServerBoard {
    val candidates = mutableMapOf<String, List<String>>()
    val phoneCards = phoneTargets.filterValues { it == serverId }.keys.mapNotNull(phone::get).sortedBy { it.id }
    val stagedNow = mutableListOf<String>()
    val staged = phoneCards.fold(board.copy(request = null, manualRequestId = null)) { current, card ->
        if (card.id in current.entries) return@fold current
        val title = normalizedTitle(card.title)
        val sameTitle = current.baseline.values.filter { normalizedTitle(it.title) == title }.map { it.id } +
            stagedNow.filter { normalizedTitle(current.entries.getValue(it).title) == title }
        if (sameTitle.isNotEmpty() && card.id !in current.allowDuplicate) {
            candidates[card.id] = sameTitle
            current
        } else {
            stagedNow += card.id
            current.copy(entries = current.entries + (card.id to card), creates = current.creates + card.id)
        }
    }.copy(duplicateCandidates = candidates)
    val apply = if (staged.refusal == null) staged.withPendingApply() else staged
    return if (apply.request != null) apply.copy(manualRequestId = apply.request.id)
        else apply.withManualExport()
}

private val titleSpace = Regex("\\s+", RegexOption.IGNORE_CASE)
private fun normalizedTitle(title: String): String =
    titleSpace.replace(Normalizer.normalize(title, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT), " ")

private fun ServerBoard.withManualExport(): ServerBoard =
    withExport().let { it.copy(manualRequestId = it.request?.id) }

private fun dedupedNotes(notes: List<BoardNote>): List<BoardNote> {
    val seen = HashSet<Triple<String, String, String>>()
    return notes.filter { seen.add(Triple(it.at, it.by, it.text)) }
}

private fun unionNotes(first: List<BoardNote>, second: List<BoardNote>): List<BoardNote> =
    (first + second).distinct().sortedWith(compareBy(
        { runCatching { Instant.parse(it.at) }.getOrNull() }, { it.at }, { it.by }, { it.text },
    ))

private fun ServerBoard.exportDue(now: Long): Boolean =
    lastExportAt?.let { now >= it && now - it >= BoardProtocol.EXPORT_INTERVAL_MS } ?: true

private fun ServerBoard.withExport(): ServerBoard = copy(
    request = BoardRequest(BoardRequestKind.EXPORT, asOf), issue = refusal, exportWanted = false,
)

private fun ServerBoard.afterEdit(): ServerBoard {
    if (refusal != null) return this
    if (request != null && (attempted || request.kind == BoardRequestKind.EXPORT)) return this
    val updated = withPendingApply()
    // A local size refusal happened before any write. Let a changed payload leave that state.
    return if (updated.request == request && noAnswer) updated else updated.copy(issue = null, noAnswer = false)
}

private fun ServerBoard.withPendingApply(): ServerBoard {
    val changes = buildList {
        entries.forEach { (id, phone) ->
            if (id in conflicted) return@forEach
            if (id in creates) {
                add(BoardChange.Create(id, phone))
                return@forEach
            }
            val remote = baseline[id]?.fields() ?: return@forEach
            val changed = phone.fields().filter { (field, value) -> value != remote[field] }
            if (changed.isNotEmpty()) add(BoardChange.Update(id, remote.filterKeys { it in changed }, changed))
            unionNotes(pendingNotes[id].orEmpty(), emptyList()).filterNot { it in phone.notes }
                .forEach { add(BoardChange.Note(id, it.text)) }
        }
    }
    if (changes == request?.changes) return this
    val pending = if (changes.isEmpty()) null else BoardRequest(BoardRequestKind.APPLY, asOf, changes)
    if (manualRound && request?.id == manualRequestId) {
        return if (pending == null) withManualExport() else copy(request = pending, manualRequestId = pending.id)
    }
    return copy(request = pending)
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
    val resultPairs = request.changes.zip(reply.results)
    resultPairs.forEach { (change, result) ->
        if (!result.ok) {
            if (result.error == "refused" || result.error == "unknown_id" && change is BoardChange.Create) {
                issues += result.reason.orEmpty().ifBlank { result.error.orEmpty() }
            }
            if (result.error == "unknown_id" && change !is BoardChange.Create) deleted += result.id
            result.current?.let { if (it.id !in incoming) incoming[it.id] = it }
            return@forEach
        }
        when (change) {
            is BoardChange.Create -> {
                val id = change.clientId
                val inFlight = local[id]
                val submittedNotes = change.card.notes.toSet()
                val base = incoming.getValue(result.id).let { it.copy(notes = dedupedNotes(it.notes)) }
                incoming[result.id] = base
                val serverKeys = base.notes.map { Triple(it.at, it.by, it.text) }.toSet()
                val notesToAppend = unionNotes(pending.remove(id).orEmpty(),
                    inFlight?.notes.orEmpty().filterNot { it in submittedNotes })
                    .filterNot { Triple(it.at, it.by, it.text) in serverKeys }
                newIds.remove(id)
                local.remove(id)
                if (notesToAppend.isNotEmpty()) pending[result.id] = notesToAppend
                // The acknowledgement is the base: one copy of each server note, in server order.
                if (inFlight != null) local[result.id] = inFlight.copy(id = result.id, notes = base.notes)
                known[result.id] = base
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
        val merged = (phone.fields() - "notes").mapValues { (field, phoneValue) ->
            val serverValue = remoteFields[field]
            val fieldChangedOnPhone = phoneValue != oldFields[field]
            val fieldChangedOnServer = serverValue != oldFields[field]
            if (field in existing || phoneValue != serverValue && (DELETED_FIELD in existing ||
                    fieldChangedOnPhone && fieldChangedOnServer)) {
                conflictFields += field
                phoneValue
            } else if (fieldChangedOnPhone) phoneValue else serverValue
        }
        local[id] = phone.withFields(merged).copy(notes = if (remote.notes == old?.notes)
            phone.notes else unionNotes(phone.notes, remote.notes))
        if (conflictFields.isEmpty()) conflicts.remove(id) else conflicts[id] = conflictFields
    }
    // A conflict with no matching choice needs a manual retry, just like a refusal.
    resultPairs.forEach { (change, result) ->
        if (!result.ok && result.error == "conflict") {
            val fields = conflicts[result.id].orEmpty()
            val hasChoice = when (change) {
                is BoardChange.Create -> false
                is BoardChange.Note -> "notes" in fields || DELETED_FIELD in fields
                is BoardChange.Update -> fields.any { it == DELETED_FIELD || it in change.set }
            }
            if (!hasChoice) issues += result.reason.orEmpty().ifBlank { "The agent reported a conflict." }
        }
    }
    val refused = issues.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: refusal
    return copy(
        name = reply.board,
        asOf = if (request.kind == BoardRequestKind.EXPORT) reply.asOf else asOf,
        entries = local, baseline = known, creates = newIds, pendingNotes = pending.filterValues { it.isNotEmpty() },
        conflicted = conflicts, issue = refused, refusal = refused,
        request = null, requestSentAt = null, noAnswer = false, attempted = false, previouslyAttempted = false, previousExportAt = null,
    )
}
