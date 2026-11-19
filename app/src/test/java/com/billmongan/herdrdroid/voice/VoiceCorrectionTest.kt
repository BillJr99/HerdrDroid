package com.billmongan.herdrdroid.voice

import com.billmongan.herdrdroid.herdr.HeldOutcome
import com.billmongan.herdrdroid.herdr.HeldSendRetry
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.PaneDispatch
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ssh.ConnectionChanged
import com.billmongan.herdrdroid.ssh.requireCurrentConnection
import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.TranscriptRead
import com.billmongan.herdrdroid.transcript.UserItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceCorrectionTest {
    private val pane = Pane("p1", "t1", "work", "claude", Status.WORKING, "Demo", "/sample", sessionId = "session-a")

    @Test fun speechRequiresUniquePaneAndMatchingLiveSessionOnBothSidesOfTheRead() = runBlocking {
        val reply = JSONObject().put("k", "r").put("t", "text").put("text", "Synthetic reply")
        val good = TranscriptRead(true, null, """{"file":"/sample/session-a.jsonl"}""", true, false, null, listOf(reply))
        suspend fun read(before: List<Pane>, after: List<Pane>, log: TranscriptRead = good): VoiceReplySnapshot? {
            var lists = 0
            return attributedReply(pane.id, "claude", "/sample", {
                if (lists++ == 0) before else after
            }, { log })
        }
        val done = pane.copy(status = Status.DONE)
        val duplicate = pane.copy(id = "p2", cwd = "/sample/", sessionId = "session-b")
        assertNull(read(listOf(pane, duplicate), listOf(pane)))
        assertNull(read(listOf(pane), listOf(done, duplicate)))
        assertNull(read(listOf(pane), listOf(done.copy(sessionId = "session-b"))))
        assertNull(read(listOf(pane.copy(sessionId = null)), listOf(done)))
        assertNull(read(listOf(pane), listOf(done), good.copy(cursor = """{"file":"/sample/session-b.jsonl"}""")))
        assertNull(read(listOf(pane), listOf(done), good.copy(cursor = """{"file":"/sample/session-a.jsonl","pin":"session-a"}""")))
        val attributed = read(listOf(pane), listOf(done))!!
        assertEquals(listOf(TextItem("r", "Synthetic reply")), attributed.items)
        assertEquals(Status.WORKING, attributed.status)
    }

    @Test fun firstWorkingBaselineAnnouncesItsCompletedTailOnceWithoutReplayingHistory() {
        val monitor = NewVoiceReplies()
        val history = listOf(UserItem("old-q", "Old"), TextItem("old-a", "Old answer"), UserItem("q", "New"))
        val partial = VoiceReplySnapshot("a", Status.WORKING, history + TextItem("a", "Running"))
        assertNull(monitor.observe(partial))
        val done = partial.copy(status = Status.DONE, items = history + TextItem("a", "Finished"))
        assertNull(monitor.observe(done))
        monitor.interrupt()
        assertNull(monitor.observe(done))
        assertEquals("Finished", monitor.observe(done))
        monitor.interrupt()
        assertNull(monitor.observe(done))
    }

    @Test fun connectionReplacementAndRestoredChatInvalidateReadback() {
        val first = Any()
        var connection = first
        val visit = SelectedChatVisit()
        visit.navigate(null, pane.id)
        fun target() = connection to visit.revision
        val drafts = VoiceMessageDraft<Pair<Any, Long>> { 0L }
        fun readBack() {
            drafts.begin(target(), "Demo")
            drafts.onReadBack(drafts.dictate(target(), "Synthetic message")!!)
        }
        readBack()
        connection = Any()
        assertNull(drafts.consume(target()))
        assertThrows(ConnectionChanged::class.java) { requireCurrentConnection(first, connection) }
        assertSame(connection, requireCurrentConnection(connection, connection))
        readBack()
        visit.navigate(pane.id, null)
        visit.navigate(null, pane.id)
        assertNull(drafts.consume(target()))
        readBack()
        visit.reopen()
        assertNull(drafts.consume(target()))
    }

    @Test fun touchInFlightExcludesVoiceAndHeldRetryUsesTheSamePaneLane() = runBlocking {
        val gate = PaneDispatch()
        val key = "demo-server" to pane.id
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val touch = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(key) { entered.complete(Unit); finish.await(); sent += "touch" }
        }
        entered.await()
        assertNull(gate.tryAcquire(key))
        gate.tryAcquire("demo-server" to "p2")!!.invoke()
        val held = launch(start = CoroutineStart.UNDISPATCHED) {
            var clock = 0L
            val result = HeldSendRetry(now = { clock }, sleep = { clock += it }).run(
                0L, "waiting", cancelled = { null },
                check = { assertNull(gate.tryAcquire(key)); null },
                send = { assertNull(gate.tryAcquire(key)); sent += "held" },
                acquire = { gate.acquire(key) },
            )
            assertTrue(result is HeldOutcome.Delivered)
        }
        assertTrue(sent.isEmpty())
        finish.complete(Unit)
        touch.join()
        held.join()
        assertEquals(listOf("touch", "held"), sent)
        gate.tryAcquire(key)!!.invoke()
    }
}
