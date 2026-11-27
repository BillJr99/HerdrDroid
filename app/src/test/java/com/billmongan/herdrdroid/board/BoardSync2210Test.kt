package com.billmongan.herdrdroid.board

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BoardSync2210Test {
    private val source = "source"
    private val firstTime = "2026-01-02T12:00:00Z"
    private val secondTime = "2026-01-02T12:05:00Z"
    private val card = BoardCard("work-1", "Fold a paper boat", next = "Find paper")
    private val store = BoardStore(BoardState()) { true }

    private fun snapshot() = requireNotNull(store.state.value.servers[source])
    private fun request() = requireNotNull(snapshot().request)

    private fun startExport(now: Long = 0): BoardRequest {
        assertTrue(store.queueExport(source, now))
        return request().also { assertTrue(store.markSent(source, it.id, now)) }
    }

    private fun wireReply(
        request: BoardRequest,
        cards: List<BoardCard> = emptyList(),
        deleted: List<String> = emptyList(),
        results: JSONArray = JSONArray(),
        time: String = firstTime,
    ) = JSONObject().put("herdrdroid_board", 1).put("request", request.id)
        .put("board", "Example board").put("as_of", time).put("cards", jsonArray(cards, ::cardJson))
        .put("deleted", JSONArray(deleted)).put("results", results)

    private fun fenced(json: JSONObject) = "```json\n$json\n```"

    private fun serverCards(vararg cards: BoardCard) {
        val request = startExport()
        val reply = requireNotNull(BoardProtocol.parseReply(fenced(wireReply(request, cards.toList())), request))
        assertTrue(store.receive(source, reply))
    }

    @Test
    fun exportCarriesSinceAndAppliesIncrementAndDeletion() {
        val second = card.copy(id = "work-2")
        serverCards(card, second)
        assertFalse(store.queueExport(source, 299_999))
        val request = startExport(300_000)
        assertEquals(firstTime, request.since)
        assertFalse(requestJson(request).has("changes"))
        val updated = card.copy(next = "Use scrap paper")
        val wire = wireReply(request, listOf(updated), listOf(second.id), time = secondTime)
            .put("future_field", true)
        val reply = requireNotNull(BoardProtocol.parseReply("Board snapshot\n${fenced(wire)}", request))
        assertTrue(store.receive(source, reply))
        assertEquals(mapOf(card.id to updated), snapshot().cards)
        assertEquals(secondTime, snapshot().asOf)
    }

    @Test
    fun missingMalformedMismatchedAndUnknownRepliesDoNotReplaceCache() {
        serverCards(card)
        val request = startExport(300_000)
        val wire = wireReply(request)
        listOf(
            "No report",
            "```json\n{\"request\":\"${request.id}\", bad: 1}\n```",
            "```json\n$wire\n",
            fenced(wireReply(request.copy(id = "other-request"))),
            fenced(wire.put("herdrdroid_board", 2)),
        ).forEach { assertNull(BoardProtocol.parseReply(it, request)) }
        assertTrue(store.markNoAnswer(source, request.id))
        assertEquals(mapOf(card.id to card), snapshot().cards)
        assertTrue(snapshot().noAnswer)
        assertTrue(store.retry(source))
        assertEquals(request, request())
        assertFalse(store.canSend(source, 599_999))
        assertTrue(store.canSend(source, 600_000))
    }

    @Test
    fun busyNoWriteKeepsQueuedAndBatchesFollowingEdit() {
        serverCards(card)
        assertTrue(store.saveCard(source, card.copy(next = "Use scrap paper")))
        val first = request()
        assertTrue(store.markSent(source, first.id, 1))
        assertTrue(store.saveCard(source, card.copy(next = "Use scrap paper", owner = "reader")))
        assertTrue(store.markQueued(source, first.id))
        val queued = request()
        assertNull(snapshot().requestSentAt)
        assertEquals(1, queued.changes.size)
        val change = queued.changes.single() as BoardChange.Update
        assertEquals(mapOf("next" to card.next, "owner" to null), change.seen)
        assertEquals(mapOf("next" to "Use scrap paper", "owner" to "reader"), change.set)
    }

    @Test
    fun nonoverlappingUpdatesMergeAndSuccessKeepsLateEdits() {
        serverCards(card)
        assertTrue(store.saveCard(source, card.copy(next = "Use scrap paper")))
        val export = startExport(300_000)
        val server = card.copy(owner = "reader")
        assertTrue(store.receive(source, BoardReply(export.id, "Example board", secondTime, listOf(server), emptyList(), emptyList())))
        assertEquals("reader", snapshot().cards.getValue(card.id).owner)
        assertEquals("Use scrap paper", snapshot().cards.getValue(card.id).next)
        assertTrue(store.queueApply(source))
        val apply = request()
        assertTrue(store.markSent(source, apply.id, 300_001))
        assertTrue(store.saveCard(source, snapshot().cards.getValue(card.id).copy(next = "Use cardstock")))
        val applied = server.copy(next = "Use scrap paper")
        val results = JSONArray().put(JSONObject().put("ok", true).put("id", card.id))
        val reply = BoardProtocol.parseReply(fenced(wireReply(apply, listOf(applied), results = results)), apply)!!
        assertTrue(store.receive(source, reply))
        assertTrue(snapshot().conflicts.isEmpty())
        assertTrue(store.queueApply(source))
        val replay = request().changes.single() as BoardChange.Update
        assertEquals(mapOf("next" to "Use scrap paper"), replay.seen)
        assertEquals(mapOf("next" to "Use cardstock"), replay.set)
    }

    @Test
    fun applyConflictFreezesCardUntilUserChooses() {
        serverCards(card)
        assertTrue(store.saveCard(source, card.copy(next = "Use scrap paper", owner = "reader")))
        val apply = request()
        assertTrue(store.markSent(source, apply.id, 1))
        val remote = card.copy(next = "Use printer paper")
        val results = JSONArray().put(JSONObject().put("ok", false).put("id", card.id)
            .put("error", "conflict").put("reason", "The next step changed").put("current", cardJson(remote)))
        val reply = BoardProtocol.parseReply(fenced(wireReply(apply, listOf(remote), results = results)), apply)!!
        assertTrue(store.receive(source, reply))
        assertEquals(BoardConflict(card.id, "next", "Use scrap paper", "Use printer paper"), snapshot().conflicts.single())
        assertFalse(store.queueApply(source))
        assertTrue(store.resolve(source, card.id, "next", keepPhone = true))
        val update = request().changes.single() as BoardChange.Update
        assertEquals("Use printer paper", update.seen["next"])
        assertEquals("Use scrap paper", update.set["next"])
        assertEquals("reader", update.set["owner"])
    }

    @Test
    fun refusalAndDuplicateNoteSurviveReloadWithoutAutomaticRetry() {
        serverCards(card)
        val note = BoardNote(firstTime, "Phone", "Add a crease")
        assertTrue(store.addNote(source, card.id, note))
        assertTrue(store.addNote(source, card.id, note))
        val apply = request()
        assertEquals(2, apply.changes.size)
        assertTrue(store.markSent(source, apply.id, 1))
        val results = JSONArray().put(JSONObject().put("ok", true).put("id", card.id))
            .put(JSONObject().put("ok", false).put("id", card.id).put("error", "refused").put("reason", "Cannot add a second note"))
        val message = wireReply(apply, listOf(card.copy(notes = listOf(note.copy(by = "reader")))), results = results)
        val reply = BoardProtocol.parseReply(fenced(message), apply)!!
        assertTrue(store.receive(source, reply))
        assertEquals("Cannot add a second note", snapshot().issue)
        val restored = BoardStore(boardStateFromJson(JSONObject(boardStateJson(store.state.value).toString()))) { true }
        assertFalse(restored.queueApply(source))
        assertTrue(restored.retry(source))
        val replay = restored.state.value.servers.getValue(source).request!!
        assertEquals(listOf(BoardChange.Note(card.id, note.text)), replay.changes)
    }

    @Test
    fun phoneOnlyCardNeedsExplicitMoveAndNewServerIdKeepsLaterEdits() {
        val local = card.copy(id = "16958fa2-a9fc-4343-8a0e-9d4270d04fe5")
        assertTrue(store.saveCard(null, local))
        assertTrue(store.state.value.servers.isEmpty())
        assertTrue(store.movePhoneCard(local.id, source))
        assertTrue(store.state.value.phone.isEmpty())
        val apply = request()
        assertEquals(BoardChange.Create(local.id, local), apply.changes.single())
        assertTrue(store.markSent(source, apply.id, 0))
        assertTrue(store.saveCard(source, local.copy(next = "Choose cardstock")))
        val remote = local.copy(id = "new-1")
        assertTrue(store.receive(source, BoardReply(apply.id, "Example board", firstTime,
            listOf(remote), emptyList(), listOf(BoardResult(true, remote.id)))))
        assertEquals("Choose cardstock", snapshot().cards.getValue(remote.id).next)
        assertTrue(store.queueApply(source))
        assertEquals(remote.id, (request().changes.single() as BoardChange.Update).id)
    }

    @Test
    fun persistedInFlightRequestRequiresManualRetryAfterTimeout() {
        serverCards(card)
        assertTrue(store.saveCard(source, card.copy(next = "Use scrap paper")))
        val apply = request()
        assertTrue(store.markSent(source, apply.id, 1))
        val restored = BoardStore(boardStateFromJson(JSONObject(boardStateJson(store.state.value).toString()))) { true }
        assertFalse(restored.canSend(source, BoardProtocol.NO_ANSWER_MS + 1))
        assertTrue(restored.markNoAnswer(source, apply.id))
        assertFalse(restored.canSend(source, BoardProtocol.NO_ANSWER_MS + 1))
        assertTrue(restored.retry(source))
        assertEquals(apply, restored.state.value.servers.getValue(source).request)
        assertTrue(restored.canSend(source, BoardProtocol.NO_ANSWER_MS + 1))
    }
}
