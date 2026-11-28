package com.billmongan.herdrdroid.board

import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class BoardPull2211Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()

    private val first = "source-a"
    private val second = "source-b"
    private val time = "2026-01-02T12:00:00Z"
    private val newer = "2026-01-02T12:01:00Z"
    private val existing = BoardCard("work-1", "Fold a Paper Boat", next = "Find paper")
    private val store = BoardStore(BoardState()) { true }

    private fun board(source: String = first) = store.state.value.servers.getValue(source)
    private fun request(source: String = first) = board(source).request!!
    private fun export(card: BoardCard, source: String = first) {
        assertTrue(store.queueExport(source, 1))
        val request = request(source)
        assertTrue(store.markSent(source, request.id, 1))
        assertTrue(store.receive(source, BoardReply(request.id, "Board", time, listOf(card), emptyList(), emptyList())))
    }

    @Test
    fun pullAppliesTargetedCardsAndEditsBeforeAnImmediateIncrementalExport() {
        export(existing)
        val local = BoardCard(UUID.randomUUID().toString(), "Make a kite")
        val private = BoardCard(UUID.randomUUID().toString(), "Read a book")
        assertTrue(store.saveCard(null, private))
        assertTrue(store.saveCard(null, local, first))
        assertTrue(store.saveCard(first, existing.copy(next = "Find scrap paper")))
        assertTrue(store.startSync(first, 2))
        val apply = request()
        assertEquals(BoardRequestKind.APPLY, apply.kind)
        assertEquals(BoardSyncProgress.QUEUED, board().syncProgress)
        assertEquals(2, apply.changes.size)
        val change = apply.changes.filterIsInstance<BoardChange.Create>().single()
        assertEquals(local.id, change.clientId)
        assertTrue(store.markSent(first, apply.id, 2))
        assertEquals(BoardSyncProgress.APPLYING, board().syncProgress)
        val adopted = local.copy(id = "server-assigned")
        val updated = existing.copy(next = "Find scrap paper")
        val results = apply.changes.map { BoardResult(true, if (it is BoardChange.Create) adopted.id else updated.id) }
        assertTrue(store.receive(first, BoardReply(apply.id, "Board", newer,
            listOf(updated, adopted), emptyList(), results)))
        assertEquals(setOf(private.id), store.state.value.phone.keys)
        assertEquals(adopted, board().cards[adopted.id])
        val export = request()
        assertEquals(BoardRequestKind.EXPORT, export.kind)
        assertEquals(time, export.since)
        assertTrue(store.canSend(first, 3))
        assertTrue(store.markSent(first, export.id, 3))
        assertTrue(store.receive(first, BoardReply(export.id, "Board", newer, emptyList(), emptyList(), emptyList())))
        assertEquals(BoardSyncProgress.SYNCED, board().syncProgress)
        assertFalse(store.queueExport(first, 4))
    }

    @Test
    fun uncertainCreateKeepsImmutableIdentityAndRoundAfterRestart() {
        val local = BoardCard(UUID.randomUUID().toString(), "Make a kite")
        assertTrue(store.saveCard(null, local, first))
        assertTrue(store.startSync(first, 1))
        val apply = request()
        assertTrue(store.markSent(first, apply.id, 1))
        assertFalse(store.saveCard(null, local, second))
        assertTrue(store.markNoAnswer(first, apply.id))
        val restored = BoardStore(boardStateFromJson(JSONObject(boardStateJson(store.state.value).toString()))) { true }
        assertEquals(BoardSyncProgress.NO_ANSWER, restored.state.value.servers.getValue(first).syncProgress)
        assertTrue(restored.retry(first))
        assertEquals(apply, restored.state.value.servers.getValue(first).request)
        assertTrue(BoardProtocol.message(apply).contains("client_id as an idempotency key"))
    }

    @Test
    fun normalizedDuplicateRequiresADecisionBeforeAnyCreation() {
        export(existing.copy(next = null))
        val local = BoardCard(UUID.randomUUID().toString(), "  Ｆold  a paper boat ", notes =
            listOf(BoardNote(time, "Phone", "Use scrap paper")))
        assertTrue(store.saveCard(null, local, first))
        assertTrue(store.startSync(first, 2))
        assertEquals(listOf(BoardDuplicate(local.id, listOf(existing.id))), board().duplicates)
        val export = request()
        assertEquals(BoardRequestKind.EXPORT, export.kind)
        assertTrue(store.markSent(first, export.id, 2))
        assertTrue(store.receive(first, BoardReply(export.id, "Board", newer, emptyList(), emptyList(), emptyList())))
        assertEquals(BoardSyncProgress.CONFLICTS, board().syncProgress)
        assertTrue(store.resolveDuplicate(first, local.id, existing.id))
        assertFalse(local.id in store.state.value.phone)
        val update = request().changes.single() as BoardChange.Update
        assertEquals(existing.id, update.id)
        assertEquals(local.notes, update.set["notes"])
    }

    @Test
    fun mergeUnionsNotesChronologicallyAndDeletionPreservesEditedCard() {
        val early = BoardNote("2026-01-02T11:00:00Z", "Author", "Earlier")
        val late = BoardNote("2026-01-02T13:00:00Z", "Author", "Later")
        val shared = BoardNote(time, "Author", "Shared")
        export(existing.copy(notes = listOf(shared)))
        assertTrue(store.saveCard(first, existing.copy(notes = listOf(late, shared))))
        assertTrue(store.queueExport(first, BoardProtocol.EXPORT_INTERVAL_MS + 1))
        val export = request()
        assertTrue(store.markSent(first, export.id, BoardProtocol.EXPORT_INTERVAL_MS + 1))
        assertTrue(store.receive(first, BoardReply(export.id, "Board", newer,
            listOf(existing.copy(notes = listOf(shared, early, shared))), emptyList(), emptyList())))
        assertEquals(listOf(early, shared, late), board().cards.getValue(existing.id).notes)
        assertTrue(board().conflicts.isEmpty())
        assertTrue(store.saveCard(first, board().cards.getValue(existing.id).copy(next = "Find scrap paper")))
        assertTrue(store.queueExport(first, 2 * BoardProtocol.EXPORT_INTERVAL_MS + 1))
        val deletion = request()
        assertTrue(store.markSent(first, deletion.id, 2 * BoardProtocol.EXPORT_INTERVAL_MS + 1))
        assertTrue(store.receive(first, BoardReply(deletion.id, "Board", newer, emptyList(),
            listOf(existing.id), emptyList())))
        assertEquals("deleted", board().conflicts.single().field)
        assertEquals("Find scrap paper", board().cards.getValue(existing.id).next)
    }

    @Test
    fun twoBoardsKeepSeparateTargetsRequestsAndQueueState() {
        val localA = BoardCard(UUID.randomUUID().toString(), "One")
        val localB = BoardCard(UUID.randomUUID().toString(), "Two")
        assertTrue(store.saveCard(null, localA, first))
        assertTrue(store.saveCard(null, localB, second))
        assertTrue(store.startSync(first, 1))
        assertTrue(store.startSync(second, 1))
        assertFalse(store.startSync(first, 1))
        assertEquals(localA.id, (request(first).changes.single() as BoardChange.Create).clientId)
        assertEquals(localB.id, (request(second).changes.single() as BoardChange.Create).clientId)
        assertNotEquals(request(first).id, request(second).id)
        assertTrue(store.markSent(second, request(second).id, 1))
        assertEquals(BoardSyncProgress.QUEUED, board(first).syncProgress)
        assertEquals(BoardSyncProgress.APPLYING, board(second).syncProgress)
    }
    @Test
    fun createAcknowledgementUsesServerNotesAndKeepsPhoneNotesAddedInFlight() {
        val firstNote = BoardNote(time, "Phone", "Start the drawing")
        val lateNote = BoardNote(newer, "Phone", "Pick up pencils")
        val local = BoardCard(UUID.randomUUID().toString(), "Draw a boat", notes = listOf(firstNote))
        assertTrue(store.saveCard(null, local, first))
        assertTrue(store.startSync(first, 1))
        val create = request()
        assertTrue(store.markSent(first, create.id, 1))
        assertTrue(store.addNote(null, local.id, lateNote))

        val saved = local.copy(id = "server-assigned", notes = listOf(firstNote.copy(by = "Editor")))
        assertTrue(store.receive(first, BoardReply(create.id, "Board", newer,
            listOf(saved), emptyList(), listOf(BoardResult(true, saved.id)))))
        assertEquals(saved.notes + lateNote, board().cards.getValue(saved.id).notes)
        assertFalse(local.id in store.state.value.phone)

        val export = request()
        assertEquals(BoardRequestKind.EXPORT, export.kind)
        assertTrue(store.markSent(first, export.id, 2))
        assertTrue(store.receive(first, BoardReply(export.id, "Board", newer,
            listOf(saved), emptyList(), emptyList())))
        assertTrue(store.queueApply(first, 3))
        assertEquals(listOf(BoardChange.Note(saved.id, lateNote.text)), request().changes)
    }

    @Test
    fun createAcknowledgementAdoptsDedupedServerNotesAsTheBase() {
        val phoneNote = BoardNote(time, "Phone", "Start the drawing")
        val drawing = BoardNote(newer, "Editor", "Start the drawing")
        val paper = BoardNote(time, "Editor", "Lay out the paper")
        val lateNote = BoardNote("2026-01-02T12:02:00Z", "Phone", "Pick up pencils")
        val local = BoardCard(UUID.randomUUID().toString(), "Draw a boat", notes = listOf(phoneNote, phoneNote))
        assertTrue(store.saveCard(null, local, first))
        assertTrue(store.startSync(first, 1))
        val create = request()
        assertTrue(store.markSent(first, create.id, 1))
        assertTrue(store.addNote(null, local.id, lateNote))

        val saved = local.copy(id = "server-assigned", notes = listOf(drawing, paper, drawing))
        assertTrue(store.receive(first, BoardReply(create.id, "Board", newer,
            listOf(saved), emptyList(), listOf(BoardResult(true, saved.id)))))
        val base = listOf(drawing, paper)
        assertEquals(base, board().baseline.getValue(saved.id).notes)
        assertEquals(base, board().entries.getValue(saved.id).notes)
        assertEquals(listOf(paper, drawing, lateNote), board().cards.getValue(saved.id).notes)
        assertFalse(local.id in store.state.value.phone)

        val export = request()
        assertEquals(BoardRequestKind.EXPORT, export.kind)
        assertTrue(store.markSent(first, export.id, 2))
        assertTrue(store.receive(first, BoardReply(export.id, "Board", newer,
            listOf(saved.copy(notes = base)), emptyList(), emptyList())))
        assertEquals(base, board().baseline.getValue(saved.id).notes)
        assertEquals(listOf(paper, drawing, lateNote), board().cards.getValue(saved.id).notes)
        assertTrue(store.queueApply(first, 3))
        assertEquals(listOf(BoardChange.Note(saved.id, lateNote.text)), request().changes)
    }

    @Test
    fun repeatedIdenticalServerNotesCollapseWithoutDroppingANewNote() {
        val note = BoardNote(time, "Phone", "Start the drawing")
        val lateNote = BoardNote(newer, "Phone", "Pick up pencils")
        val local = BoardCard(UUID.randomUUID().toString(), "Draw a boat", notes = listOf(note, note))
        assertTrue(store.saveCard(null, local, first))
        assertTrue(store.startSync(first, 1))
        val create = request()
        assertTrue(store.markSent(first, create.id, 1))
        assertTrue(store.addNote(null, local.id, lateNote))
        val saved = local.copy(id = "server-assigned", notes = listOf(note, note))
        assertTrue(store.receive(first, BoardReply(create.id, "Board", newer,
            listOf(saved), emptyList(), listOf(BoardResult(true, saved.id)))))
        assertEquals(listOf(note), board().baseline.getValue(saved.id).notes)
        assertEquals(listOf(note), board().entries.getValue(saved.id).notes)
        assertEquals(listOf(note, lateNote), board().cards.getValue(saved.id).notes)
    }

    @Test
    fun createSeparatelyQueuesThePhoneCardInsteadOfShowingSynced() {
        export(existing.copy(next = null))
        val local = BoardCard(UUID.randomUUID().toString(), "Fold a paper boat")
        assertTrue(store.saveCard(null, local, first))
        assertTrue(store.startSync(first, 2))
        assertEquals(listOf(BoardDuplicate(local.id, listOf(existing.id))), board().duplicates)
        val export = request()
        assertEquals(BoardRequestKind.EXPORT, export.kind)
        assertTrue(store.markSent(first, export.id, 2))
        assertTrue(store.receive(first, BoardReply(export.id, "Board", newer, emptyList(), emptyList(), emptyList())))
        assertEquals(BoardSyncProgress.CONFLICTS, board().syncProgress)
        assertTrue(store.resolveDuplicate(first, local.id, null))
        assertEquals(BoardSyncProgress.QUEUED, board().syncProgress)
        val apply = request()
        assertEquals(BoardRequestKind.APPLY, apply.kind)
        assertNull(board().requestSentAt)
        assertEquals(local.id, (apply.changes.single() as BoardChange.Create).clientId)
        assertEquals(first, store.state.value.phoneTargets[local.id])
        assertTrue(local.id in board().allowDuplicate)
    }

    @Test
    fun createSeparatelyKeepsAnUnsentCreateAlreadyStaged() {
        export(existing.copy(next = null))
        val kite = BoardCard("card-a", "Make a kite")
        val twin = BoardCard("card-b", "Fold a paper boat")
        assertTrue(store.saveCard(null, kite, first))
        assertTrue(store.saveCard(null, twin, first))
        assertTrue(store.startSync(first, 2))
        assertEquals(kite.id, (request().changes.single() as BoardChange.Create).clientId)
        assertTrue(store.resolveDuplicate(first, twin.id, null))
        assertEquals(BoardSyncProgress.QUEUED, board().syncProgress)
        assertEquals(setOf(kite.id, twin.id), request().changes.filterIsInstance<BoardChange.Create>().map { it.clientId }.toSet())
        assertNull(board().requestSentAt)
    }

    @Test
    fun pullComparesCardsStagedInTheSameRound() {
        val ids = List(3) { UUID.randomUUID().toString() }.sorted()
        listOf("Fold a paper boat", "  fold   a paper boat  ", "Fold a paper boat").zip(ids).forEach { (title, id) ->
            assertTrue(store.saveCard(null, BoardCard(id, title), first))
        }
        val kite = BoardCard(UUID.randomUUID().toString(), "Make a kite")
        assertTrue(store.saveCard(null, kite, first))
        assertTrue(store.startSync(first, 1))
        assertEquals(
            listOf(BoardDuplicate(ids[1], listOf(ids[0])), BoardDuplicate(ids[2], listOf(ids[0]))),
            board().duplicates,
        )
        assertEquals(BoardSyncProgress.QUEUED, board().syncProgress)
        assertEquals(setOf(ids[0], kite.id), request().changes.filterIsInstance<BoardChange.Create>().map { it.clientId }.toSet())
        assertTrue(ids[0] in board().entries)
        assertFalse(ids[1] in board().entries)
        assertFalse(ids[2] in board().entries)
    }

    @Test
    fun pendingNotesAreOrderedAndDeduplicatedWithoutTurningIntoAFieldUpdate() {
        val original = BoardNote(time, "Author", "Original")
        val earlier = BoardNote("2026-01-02T11:00:00Z", "Author", "Earlier")
        val latest = BoardNote(newer, "Author", "Latest")
        export(existing.copy(notes = listOf(original)))
        assertTrue(store.addNote(first, existing.id, latest))
        assertTrue(store.addNote(first, existing.id, earlier))
        assertFalse(store.addNote(first, existing.id, earlier))
        assertFalse(store.addNote(first, existing.id, original))
        val shown = board().cards.getValue(existing.id)
        assertEquals(listOf(earlier, original, latest), shown.notes)
        assertTrue(store.saveCard(first, shown.copy(next = "Find scrap paper")))
        val changes = request().changes
        assertEquals(listOf("Earlier", "Latest"), changes.filterIsInstance<BoardChange.Note>().map { it.text })
        assertEquals(mapOf("next" to "Find scrap paper"), changes.filterIsInstance<BoardChange.Update>().single().set)
    }
}
