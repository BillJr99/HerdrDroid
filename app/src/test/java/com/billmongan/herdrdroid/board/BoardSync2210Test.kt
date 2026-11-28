package com.billmongan.herdrdroid.board

import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import org.junit.Rule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BoardSync2210Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()

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
    fun createConflictRequiresManualRetry() {
        val pending = card.copy(id = "16958fa2-a9fc-4343-8a0e-9d4270d04fe5")
        assertTrue(store.saveCard(source, pending))
        assertConflictRequiresManualRetry(pending)
    }

    @Test
    fun noteConflictRequiresManualRetry() {
        serverCards(card)
        assertTrue(store.addNote(source, card.id, BoardNote(firstTime, "Phone", "Add a crease")))
        assertConflictRequiresManualRetry(card)
    }

    @Test
    fun updateConflictWithoutConflictingFieldsRequiresManualRetry() {
        serverCards(card)
        assertTrue(store.saveCard(source, card.copy(next = "Use scrap paper")))
        assertConflictRequiresManualRetry(card)
    }

    private fun assertConflictRequiresManualRetry(current: BoardCard) {
        val pending = request()
        val reason = "The agent could not apply this change"
        assertTrue(store.markSent(source, pending.id, 1))
        assertTrue(store.receive(source, BoardReply(pending.id, "Example board", secondTime,
            listOf(current), emptyList(), listOf(BoardResult(false, current.id, "conflict", current, reason)))))
        assertTrue(snapshot().conflicts.isEmpty())
        assertEquals(reason, snapshot().issue)
        assertNull(snapshot().request)
        assertFalse(store.queueApply(source))
        assertTrue(store.retry(source))
        assertEquals(pending.changes, request().changes)
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
    @Test
    fun requestedRefreshWaitsForActiveApplyAndRefusalSurvivesRefreshAndEdit() {
        serverCards(card)
        assertTrue(store.saveCard(source, card.copy(next = "Use scrap paper")))
        val apply = request()
        assertTrue(store.markSent(source, apply.id, 299_999))
        assertTrue(store.queueExport(source, 300_000))
        assertEquals(apply.id, request().id)
        assertTrue(store.receive(source, BoardReply(apply.id, "Example board", firstTime, listOf(card),
            emptyList(), listOf(BoardResult(false, card.id, "refused", reason = "The change is restricted")))))
        assertTrue(store.queueApply(source, 300_001))
        val export = request()
        assertEquals(BoardRequestKind.EXPORT, export.kind)
        assertTrue(store.markSent(source, export.id, 300_001))
        assertTrue(store.receive(source, BoardReply(export.id, "Example board", secondTime, emptyList(),
            emptyList(), emptyList())))
        assertTrue(store.saveCard(source, snapshot().cards.getValue(card.id).copy(owner = "reader")))
        assertNull(snapshot().request)
        assertFalse(store.queueApply(source, 300_002))
        assertEquals("The change is restricted", snapshot().issue)
    }

    @Test
    fun keepingLocallyEditedDeletedCardUsesNewCreateIdentity() {
        serverCards(card)
        assertTrue(store.saveCard(source, card.copy(next = "Use scrap paper")))
        val request = startExport(300_000)
        assertTrue(store.receive(source, BoardReply(request.id, "Example board", secondTime, emptyList(),
            listOf(card.id), emptyList())))
        assertEquals("deleted", snapshot().conflicts.single().field)
        assertTrue(store.resolve(source, card.id, "deleted", keepPhone = true))
        val create = request().changes.single() as BoardChange.Create
        assertEquals(create.clientId, java.util.UUID.fromString(create.clientId).toString())
        assertEquals("Use scrap paper", create.card.next)
        assertFalse(card.id in snapshot().cards)
    }

    @Test
    fun noteAddedDuringCreateAppendsToTheAssignedCard() {
        val firstNote = BoardNote(firstTime, "Phone", "Start with scrap paper")
        val lateNote = BoardNote(secondTime, "Phone", "Save the template")
        val pendingCard = card.copy(id = "16958fa2-a9fc-4343-8a0e-9d4270d04fe5", notes = listOf(firstNote))
        assertTrue(store.saveCard(source, pendingCard))
        val create = request()
        assertTrue(store.markSent(source, create.id, 1))
        assertTrue(store.addNote(source, pendingCard.id, lateNote))
        val saved = card.copy(notes = listOf(firstNote.copy(by = "reader")))
        assertTrue(store.receive(source, BoardReply(create.id, "Example board", secondTime,
            listOf(saved), emptyList(), listOf(BoardResult(true, saved.id)))))

        assertTrue(snapshot().conflicts.isEmpty())
        assertEquals(saved.notes + lateNote, snapshot().cards.getValue(saved.id).notes)
        assertTrue(store.queueApply(source))
        val append = request()
        assertEquals(listOf(BoardChange.Note(saved.id, lateNote.text)), append.changes)
        assertTrue(store.markSent(source, append.id, 2))
        val withAppend = saved.copy(notes = saved.notes + lateNote.copy(by = "reader"))
        assertTrue(store.receive(source, BoardReply(append.id, "Example board", secondTime,
            listOf(withAppend), emptyList(), listOf(BoardResult(true, saved.id)))))
        assertEquals(withAppend.notes, snapshot().cards.getValue(saved.id).notes)
        assertFalse(store.queueApply(source))
    }

    @Test
    fun successfulCreateCannotReplaceADifferentExistingCard() {
        serverCards(card)
        val newCard = card.copy(id = "16958fa2-a9fc-4343-8a0e-9d4270d04fe5", title = "Build a kite")
        assertTrue(store.saveCard(source, newCard))
        val request = request()
        assertTrue(store.markSent(source, request.id, 1))
        assertFalse(store.receive(source, BoardReply(request.id, "Example board", firstTime,
            listOf(newCard.copy(id = card.id)), emptyList(), listOf(BoardResult(true, card.id)))))
        assertEquals(card, snapshot().cards[card.id])
        assertEquals(newCard, snapshot().cards[newCard.id])
        assertEquals(request, request())
    }
    @Test
    fun changingUnsentRejectedPayloadClearsItsNoAnswerState() {
        serverCards(card)
        assertTrue(store.saveCard(source, card.copy(next = "x".repeat(25_000))))
        val oversized = request()
        assertTrue(store.markNoAnswer(source, oversized.id, "The queued changes are too large for one board request."))
        assertFalse(store.canSend(source, 1))
        assertTrue(store.saveCard(source, card.copy(next = "Use scrap paper")))
        assertFalse(snapshot().noAnswer)
        assertNull(snapshot().issue)
        assertNotEquals(oversized.id, request().id)
        assertEquals(mapOf("next" to "Use scrap paper"), (request().changes.single() as BoardChange.Update).set)
        assertTrue(store.canSend(source, 1))
    }
}
