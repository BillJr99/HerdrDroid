package com.billmongan.herdrdroid.herdr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * 2.2.7: cross-server pane read requests. A frame raises a card only; Yes reads the target once,
 * keeps the capture on the phone and starts exactly one attachment send to the requesting pane.
 */
@RunWith(AndroidJUnit4::class)
class PaneReadTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val tmp = TemporaryFolder()

    private val code = "K7P3QX"
    private fun frame(code: String = this.code, server: String = "laptop", pane: String = "codex", extra: String = "") =
        "HDREAD1 $code\nHDSRV $server\nHDPANE $pane\n${extra}HDEND $code\n"

    private val codex = Pane("w2:p3", "w2:t3", "w2", "codex", Status.IDLE, "", "/x", tabLabel = "codex", scrollOffset = 0, terminalId = "t3")
    private val grok = Pane("w2:p4", "w2:t4", "w2", "grok", Status.IDLE, "", "/x", tabLabel = "grok", scrollOffset = 0, terminalId = "t4")

    private inner class Fake : PaneReadRequests.Port<String> {
        var enabled = true
        var current = true
        var connected = true
        var panes = listOf(codex, grok)
        var screen = "line 1\nline 2\n\u001B[1mline 3\u001B[0m\n"
        var undeliverable: String? = null
        val reads = mutableListOf<Pair<String, Int?>>()
        val deliveries = mutableListOf<Pair<PaneCapture, String>>()
        val logs = mutableListOf<String>()
        override fun enabled() = enabled
        override fun sourceCurrent(source: String) = current
        override fun servers() = listOf(
            PaneReadServer("s1", listOf("laptop2"), "laptop2", own = true, connected = true, panes = emptyList()),
            PaneReadServer("s2", listOf("laptop"), "laptop", own = false, connected = connected, panes = panes),
        )
        override suspend fun read(server: PaneReadServer, pane: Pane, lines: Int?): String { reads += pane.id to lines; return screen }
        override suspend fun deliver(source: String, capture: PaneCapture, message: String): String? {
            deliveries += capture to message
            return undeliverable
        }
        override fun log(message: String) { logs += message }
    }

    private var now = 1_790_000_000_000L
    private val port = Fake()
    private val ledger = CrossServerCodeLedger({ "" }, {})
    private val store by lazy { PaneReadStore(tmp.newFolder("reads")) }
    private val reads by lazy { PaneReadRequests(port, ledger, store, CoroutineScope(Dispatchers.Unconfined)) { now } }

    private fun note(text: String = frame()) = reads.noteFrames("src", "s1", "w1:p1", "Claude on laptop2", text)
    private fun only() = reads.cardsFor("s1", "w1:p1").single()

    // ---- the frame ---------------------------------------------------------------------------

    @Test fun aFrameParsesWithItsOptionalLinesAndReason() {
        val f = paneReadFrames("chat above\n" + frame(extra = "HDLINES 80\nHDWHY see why it stopped\n") + "below").single()
        assertEquals(PaneReadFrame(code, "laptop", "codex", 80, "see why it stopped"), f)
        assertEquals(PaneReadFrame(code, "laptop", "codex", null, ""), paneReadFrames(frame()).single())
        // Indented or boxed as agent UIs draw a code block.
        assertEquals(1, paneReadFrames(frame().lines().joinToString("\n") { "  │ $it │" }).size)
    }

    @Test fun theLineCountIsCappedAndBadFramesAreSkipped() {
        assertEquals(200, paneReadFrames(frame(extra = "HDLINES 5000\n")).single().lines)
        assertTrue(paneReadFrames(frame(extra = "HDLINES 0\n")).isEmpty())
        assertTrue(paneReadFrames(frame(extra = "HDLINES ten\n")).isEmpty())
        assertTrue(paneReadFrames(frame(code = "K0P3QX")).isEmpty())
        assertTrue(paneReadFrames(frame(extra = "HDWHY a\u0007b\n")).isEmpty())
        assertTrue(paneReadFrames(frame(extra = "something else\n")).isEmpty())
        assertTrue(paneReadFrames("HDREAD1 $code\nHDSRV laptop\nHDPANE codex\n").isEmpty())
        // The same code asking two different things yields nothing; a bad frame doesn't hide a good one.
        assertTrue(paneReadFrames(frame() + frame(pane = "grok")).isEmpty())
        assertEquals("grok", paneReadFrames(frame(code = "AAAAAA", extra = "junk\n") + frame(pane = "grok")).single().pane)
    }

    // ---- approval, single use and expiry -----------------------------------------------------

    @Test fun nothingIsReadOrSentWithoutApprovalAndYesReadsAndSendsExactlyOnce() {
        note()
        val card = only()
        assertEquals(PaneReadState.PENDING, card.state)
        assertEquals(card.id, reads.dialogFor("s1", "w1:p1")?.id)
        assertTrue(port.reads.isEmpty() && port.deliveries.isEmpty() && store.list().isEmpty())
        val approval = checkNotNull(reads.approvalOf(card))
        assertTrue(reads.approve(approval))
        assertEquals(listOf("w2:p3" to null), port.reads)
        assertEquals(PaneReadState.SAVED, only().state)
        assertEquals(1, port.deliveries.size)
        assertTrue(only().delivering)
        // A second tap, the same frame read again, or an app restart with the same ledger: nothing more.
        assertFalse(reads.approve(approval))
        note()
        PaneReadRequests(port, ledger, store, CoroutineScope(Dispatchers.Unconfined)) { now }.let {
            it.noteFrames("src", "s1", "w1:p1", "Claude on laptop2", frame())
            assertTrue(it.cards.isEmpty())
        }
        assertEquals(1, port.reads.size)
        assertEquals(1, port.deliveries.size)
    }

    @Test fun theMessageIsShortAndNeverHoldsTheCapturesText() {
        port.screen = "SECRET-LOOKING SCREEN TEXT\nsecond row"
        note()
        reads.approve(checkNotNull(reads.approvalOf(only())))
        val (capture, message) = port.deliveries.single()
        assertEquals("Pane read: laptop/codex, 2 lines (approved)", message)
        assertFalse(message.contains("SCREEN TEXT"))
        assertEquals("SECRET-LOOKING SCREEN TEXT\nsecond row", capture.body)
        // The relay log names codes, ids and counts only.
        assertTrue(port.logs.none { "SCREEN" in it })
        assertTrue(port.logs.any { "saved on the phone: s2/w2:p3, 2 lines" in it })
    }

    @Test fun noOnTheDialogCancelsAndBackLeavesItPending() {
        note()
        reads.defer(only())
        assertNull(reads.dialogFor("s1", "w1:p1"))
        assertEquals(PaneReadState.PENDING, only().state)
        reads.cancel(only())
        assertEquals(PaneReadState.CANCELLED, only().state)
        assertNull(reads.approvalOf(only()))
        assertTrue(port.reads.isEmpty())
    }

    @Test fun aRequestExpiresAfterTenMinutes() {
        note()
        val approval = checkNotNull(reads.approvalOf(only()))
        now += CrossServerCode.LIFETIME_MS
        assertFalse(reads.approve(approval))
        assertEquals(PaneReadState.EXPIRED, only().state)
        assertEquals(PaneReadRefusal.EXPIRED, only().refusal)
        assertTrue(port.reads.isEmpty())
    }

    @Test fun aCodeReusedForADifferentRequestIsRefusedOnItsCard() {
        note()
        reads.approve(checkNotNull(reads.approvalOf(only())))
        note(frame(pane = "grok"))
        val reused = reads.cardsFor("s1", "w1:p1").single { it.refusal == PaneReadRefusal.REUSED }
        assertEquals(PaneReadState.REFUSED, reused.state)
        assertNull(reads.approvalOf(reused))
        assertEquals(1, port.reads.size)
    }

    // ---- refusals ----------------------------------------------------------------------------

    @Test fun offRefusesOnTheCardAndRecordsNothing() {
        port.enabled = false
        note()
        assertEquals(PaneReadRefusal.OFF, only().refusal)
        assertNull(reads.approvalOf(only()))
        port.enabled = true
        note()
        assertEquals(PaneReadState.PENDING, only().state)
    }

    private fun approveRefused(): PaneReadRequests.Card<String> {
        note()
        assertTrue(reads.approve(checkNotNull(reads.approvalOf(only()))))
        assertTrue(port.reads.isEmpty())
        assertTrue(port.deliveries.isEmpty())
        return only()
    }

    @Test fun aServerThatIsNotConnectedLeavesItPendingForRetry() {
        port.connected = false
        val card = approveRefused()
        assertEquals(PaneReadState.PENDING, card.state)
        assertEquals(PaneReadRefusal.NOT_CONNECTED, card.refusal)
        port.connected = true
        assertTrue(reads.approve(checkNotNull(reads.approvalOf(only()))))
        assertEquals(PaneReadState.SAVED, only().state)
    }

    @Test fun anUnknownServerIsRefusedOnTheCard() {
        note(frame(server = "nowhere"))
        reads.approve(checkNotNull(reads.approvalOf(only())))
        assertEquals(PaneReadRefusal.UNKNOWN_SERVER, only().refusal)
        assertEquals(PaneReadState.PENDING, only().state)
        assertTrue(port.reads.isEmpty())
    }

    @Test fun anUnknownPaneIsRefusedOnTheCard() {
        port.panes = listOf(grok)
        assertEquals(PaneReadRefusal.UNKNOWN_PANE, approveRefused().refusal)
    }

    @Test fun anAmbiguousPaneAndTheOwnServerAreRefusedForGood() {
        port.panes = listOf(codex, codex.copy(id = "w2:p9"))
        assertEquals(PaneReadRefusal.AMBIGUOUS_PANE, approveRefused().refusal)
        assertEquals(PaneReadState.REFUSED, only().state)
        note(frame(code = "AAAAAA", server = "laptop2"))
        reads.approve(checkNotNull(reads.approvalOf(reads.cardsFor("s1", "w1:p1").last())))
        assertEquals(PaneReadRefusal.SAME_SERVER, reads.cardsFor("s1", "w1:p1").last().refusal)
        // The pane id picks one of two panes with the same label.
        note(frame(code = "BBBBBB", pane = "w2:p9"))
        reads.approve(checkNotNull(reads.approvalOf(reads.cardsFor("s1", "w1:p1").last())))
        assertEquals(listOf("w2:p9" to null), port.reads)
    }

    @Test fun aChangedSourcePaneIsRefused() {
        note()
        val approval = checkNotNull(reads.approvalOf(only()))
        port.current = false
        assertFalse(reads.approve(approval))
        assertTrue(port.reads.isEmpty())
    }

    @Test fun anUndeliverableAttachmentKeepsThePhoneCopyAndSaysSo() {
        port.undeliverable = "this chat's server is not connected"
        note()
        reads.approve(checkNotNull(reads.approvalOf(only())))
        assertEquals(PaneReadState.SAVED, only().state)
        assertEquals("this chat's server is not connected", only().undeliverable)
        assertEquals(1, store.list().size)
        assertEquals(1, port.deliveries.size)
    }

    @Test fun theDeliveryNoteReportsSentQueuedAndFailed() {
        assertEquals("Sent to Claude with the capture attached.", PaneCaptures.deliveryNote("Claude", delivered = true))
        assertEquals("Queued for Claude (it is working); it goes when the pane is free.",
            PaneCaptures.deliveryNote("Claude", pending = true, waiting = true, held = "it is working"))
        assertTrue(PaneCaptures.deliveryNote("Claude", pending = true, undelivered = true).contains("stays on this phone"))
        assertTrue(PaneCaptures.deliveryNote(unavailable = "no link").startsWith("Not sent to the agent: no link."))
        assertTrue(PaneCaptures.deliveryNote("Claude").contains("stays on this phone"))
    }

    // ---- the phone's store -------------------------------------------------------------------

    @Test fun theCaptureIsKeptOnThePhoneWithItsHeader() {
        note(frame(extra = "HDLINES 2\nHDWHY check it\n"))
        reads.approve(checkNotNull(reads.approvalOf(only())))
        val c = store.list().single()
        assertEquals(c.name, only().saved)
        assertEquals("line 2\nline 3", c.body)
        assertTrue(c.text.startsWith("HerdrDroid pane read $code\nserver: laptop\npane: codex (w2:p3)\ntime: "))
        assertTrue(c.text.contains("\nlines: 2\nrequested by: Claude on laptop2\nrequester: s1 w1:p1\nreason: check it\n----\nline 2"))
        assertEquals(listOf("w2:p3" to 2), port.reads)
    }

    @Test fun theStoreNeverOverwritesAndKeepsTheLastTwenty() {
        fun capture(i: Int) = PaneCapture(PaneReadStore.nameFor(now + i * 1000L, "AAAAAA"), "AAAAAA", "laptop", "codex", "w2:p3",
            "t", 1, "Claude on laptop2", "s1", "w1:p1", "", "row $i")
        assertTrue(store.save(capture(0)))
        assertFalse("never overwrites", store.save(capture(0).copy(body = "other")))
        assertEquals("row 0", store.list().single().body)
        for (i in 1..25) store.save(capture(i))
        val kept = store.list()
        assertEquals(PaneReadLimits.KEEP, kept.size)
        assertEquals("row 25", kept.first().body)
        assertEquals("row 6", kept.last().body)
        store.clear()
        assertTrue(store.list().isEmpty())
    }

    @Test fun aCaptureNameAlreadyOnThePhoneIsRefused() {
        note()
        store.save(PaneCapture(PaneReadStore.nameFor(now, code), code, "x", "x", "x", "t", 0, "r", "s1", "w1:p1", "", ""))
        reads.approve(checkNotNull(reads.approvalOf(only())))
        assertEquals(PaneReadRefusal.EXISTS, only().refusal)
        assertTrue(port.reads.isEmpty())
        assertNotNull(store.list().single())
    }
}
