package com.billmongan.herdrdroid.board

import org.json.JSONObject

internal fun boardStateJson(state: BoardState): JSONObject = JSONObject()
    .put("phone", jsonArray(state.phone.values, ::cardJson))
    .put("servers", JSONObject().apply { state.servers.forEach { (id, board) -> put(id, serverJson(board)) } })

private fun serverJson(board: ServerBoard): JSONObject = JSONObject()
    .put("name", board.name ?: JSONObject.NULL)
    .put("as_of", board.asOf ?: JSONObject.NULL)
    .put("entries", jsonArray(board.entries.values, ::cardJson))
    .put("baseline", jsonArray(board.baseline.values, ::cardJson))
    .put("creates", jsonArray(board.creates) { it })
    .put("pending_notes", JSONObject().apply {
        board.pendingNotes.forEach { (id, notes) -> put(id, jsonArray(notes, ::noteJson)) }
    })
    .put("conflicted", JSONObject().apply {
        board.conflicted.forEach { (id, fields) -> put(id, jsonArray(fields) { it }) }
    })
    .put("issue", board.issue ?: JSONObject.NULL)
    .put("request", board.request?.let(::requestJson) ?: JSONObject.NULL)
    .put("request_sent_at", board.requestSentAt ?: JSONObject.NULL)
    .put("no_answer", board.noAnswer)
    .put("last_export_at", board.lastExportAt ?: JSONObject.NULL)
    .put("previous_export_at", board.previousExportAt ?: JSONObject.NULL)
    .put("attempted", board.attempted)
    .put("previously_attempted", board.previouslyAttempted)

private fun JSONObject.cards(key: String): Map<String, BoardCard> =
    getJSONArray(key).objects().map(::cardFromJson).associateBy { it.id }

private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)

private fun serverFromJson(json: JSONObject): ServerBoard {
    val notes = json.getJSONObject("pending_notes")
    val conflicted = json.getJSONObject("conflicted")
    return ServerBoard(
        name = json.nullableString("name"),
        asOf = json.nullableString("as_of"),
        entries = json.cards("entries"),
        baseline = json.cards("baseline"),
        creates = json.getJSONArray("creates").strings().toSet(),
        pendingNotes = notes.keys().asSequence().associateWith { notes.getJSONArray(it).objects().map(::noteFromJson) },
        conflicted = conflicted.keys().asSequence().associateWith { conflicted.getJSONArray(it).strings().toSet() },
        issue = json.nullableString("issue"),
        request = json.optJSONObject("request")?.let(::requestFromJson),
        requestSentAt = json.nullableLong("request_sent_at"),
        noAnswer = json.getBoolean("no_answer"),
        lastExportAt = json.nullableLong("last_export_at"),
        previousExportAt = json.nullableLong("previous_export_at"),
        attempted = json.getBoolean("attempted"),
        previouslyAttempted = json.optBoolean("previously_attempted"),
    )
}

internal fun boardStateFromJson(json: JSONObject): BoardState {
    val servers = json.getJSONObject("servers")
    return BoardState(
        phone = json.cards("phone"),
        servers = servers.keys().asSequence().associateWith { serverFromJson(servers.getJSONObject(it)) },
    )
}
