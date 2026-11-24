package com.billmongan.herdrdroid.herdr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * 2.2.7: cross-server pane read requests. A frame in the agent's own reply raises a card only; Yes
 * reads the target once (after a fresh check of the pane), keeps the capture on the phone and
 * makes exactly one attachment send to the requesting pane.
 */
@RunWith(AndroidJUnit4::class)
class PaneReadTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val tmp = TemporaryFolder()

    private val code = "K7P3QX"
    private fun bare(code: String = this.code, server: String = "minipc", pane: String = "codex", extra: String = "") =
        "HDREAD1 $code\nHDSRV $server\nHDPANE $pane\n${extra}HDEND $code"
    /** As Claude Code draws a reply: its first row marked "⏺ ", the fenced frame indented two spaces. */
    private fun frame(code: String = this.code, server: String = "minipc", pane: String = "codex", extra: String = "") =
        "⏺ Asking the phone to read it.\n\n" + bare(code, server, pane, extra).lines().joinToString("\n") { "  $it" } + "\n"

    private val codex = Pane("w2:p3", "w2:t3", "w2", "codex", Status.IDLE, "", "/x", tabLabel = "codex", scrollOffset = 0, terminalId = "t3")
    private val grok = Pane("w2:p4", "w2:t4", "w2", "grok", Status.IDLE, "", "/x", tabLabel = "grok", scrollOffset = 0, terminalId = "t4")

    private inner class Fake : PaneReadRequests.Port<String> {
        var enabled = true
        var current = true
        var connected = true
        var panes = listOf(codex, grok)
        /** The pane as a fresh read finds it; by default as listed, without the tab label (herdr's pane get has none). */
        var live: (Pane) -> Pane? = { it.copy(tabLabel = "") }
        var screen = "line 1\nline 2\n\u001B[1mline 3\u001B[0m\n"
        var undeliverable: String? = null
        var hold: CompletableDeferred<Unit>? = null
        val reads = mutableListOf<Pair<String, Int?>>()
        val deliveries = mutableListOf<Pair<PaneCapture, String>>()
        val allowedAtSend = mutableListOf<Boolean>()
        val logs = mutableListOf<String>()
        override fun enabled() = enabled
        override fun sourceCurrent(source: String) = current
        override fun servers() = listOf(
            PaneReadServer("s1", listOf("minipc2"), "minipc2", own = true, connected = true, panes = emptyList()),
            PaneReadServer("s2", listOf("minipc"), "minipc", own = false, connected = connected, panes = panes),
        )
        override suspend fun livePane(server: PaneReadServer, pane: Pane) = live(pane)
        override suspend fun read(server: PaneReadServer, pane: Pane, lines: Int?): String { reads += pane.id to lines; return screen }
        override suspend fun deliver(source: String, capture: PaneCapture, message: String, allowed: () -> Boolean): String? {
            hold?.await()
            allowedAtSend += allowed()
            if (!allowed()) return "this chat changed before the message went"
            deliveries += capture to message
            return undeliverable
        }
        override fun log(message: String) { logs += message }
    }

    private var now = 1_790_000_000_000L
    private val port = Fake()
    private val ledger = CrossServerCodeLedger({ "" }, {})
    private val shared by lazy { tmp.newFolder("shared") }
    private val store by lazy { PaneReadStore(tmp.newFolder("reads"), shared = shared) }
    private val reads by lazy { PaneReadRequests(port, ledger, store, CoroutineScope(Dispatchers.Unconfined)) { now } }

    private fun note(text: String = frame()) = reads.noteFrames("src", "s1", "w1:p1", "Claude on minipc2", text)
    private fun only() = reads.cardsFor("s1", "w1:p1").single()
    private fun yes(card: PaneReadRequests.Card<String> = only()) = reads.approve(checkNotNull(reads.approvalOf(card)))

    // ---- the frame and where it came from ----------------------------------------------------

    @Test fun aFrameInTheAgentsReplyParsesWithItsOptionalLinesAndReason() {
        val found = paneReadFinds("chat above\n" + frame(extra = "HDLINES 80\nHDWHY see why it stopped\n") + "below").single()
        assertEquals(PaneReadFrame(code, "minipc", "codex", 80, "see why it stopped"), found.frame)
        assertEquals("⏺ Asking the phone to read it.", found.evidence)
        assertEquals(PaneReadFrame(code, "minipc", "codex", null, ""), paneReadFrames(frame()).single())
        // On the reply's own first row too, and under Codex's "• ".
        assertEquals(1, paneReadFrames("⏺ " + bare().lines().joinToString("\n") { it }.replace("\n", "\n  ")).size)
        assertEquals(1, paneReadFrames("• Here it is.\n" + bare().lines().joinToString("\n") { "  $it" }).size)
    }

    @Test fun aFrameNotInTheAgentsOwnReplyIsIgnored() {
        val indented = bare().lines().joinToString("\n") { "  $it" }
        // Tool output: a cat'ed file or a fetched page.
        assertTrue(paneReadFrames("⏺ Bash(cat notes.txt)\n  ⎿  notes\n$indented").isEmpty())
        assertTrue(paneReadFrames("⏺ Read(notes.md)\n$indented").isEmpty())
        assertTrue(paneReadFrames("• Ran cat notes.txt\n  └ notes\n$indented").isEmpty())
        // Your own message, or a relayed cross-server message, echoed as the user's turn.
        assertTrue(paneReadFrames("❯ please do this:\n$indented").isEmpty())
        assertTrue(paneReadFrames("> " + bare().replace("\n", "\n  ")).isEmpty())
        // In a box or a quote, or with no reply above it at all.
        assertTrue(paneReadFrames("⏺ Sure.\n" + bare().lines().joinToString("\n") { "  │ $it │" }).isEmpty())
        assertTrue(paneReadFrames(indented).isEmpty())
        // Rows at different indentations are not one frame.
        assertTrue(paneReadFrames("⏺ Sure.\n" + bare().lines().mapIndexed { i, l -> if (i == 2) "      $l" else "  $l" }.joinToString("\n")).isEmpty())
    }

    @Test fun theLineCountIsCappedAndBadFramesAreSkipped() {
        assertEquals(200, paneReadFrames(frame(extra = "HDLINES 5000\n")).single().lines)
        assertTrue(paneReadFrames(frame(extra = "HDLINES 0\n")).isEmpty())
        assertTrue(paneReadFrames(frame(extra = "HDLINES ten\n")).isEmpty())
        assertTrue(paneReadFrames(frame(code = "K0P3QX")).isEmpty())
        assertTrue(paneReadFrames(frame(extra = "HDWHY a\u0007b\n")).isEmpty())
        assertTrue(paneReadFrames(frame(extra = "something else\n")).isEmpty())
        assertTrue(paneReadFrames(frame() + frame(pane = "grok")).isEmpty())
        assertEquals("grok", paneReadFrames(frame(code = "AAAAAA", extra = "junk\n") + frame(pane = "grok")).single().pane)
    }

    // ---- approval, single use and expiry -----------------------------------------------------

    @Test fun nothingIsReadOrSentWithoutApprovalAndYesReadsAndSendsExactlyOnce() {
        note()
        val card = only()
        assertEquals(PaneReadState.PENDING, card.state)
        assertTrue(port.reads.isEmpty() && port.deliveries.isEmpty() && store.list().isEmpty())
        val approval = checkNotNull(reads.approvalOf(card))
        assertTrue(reads.approve(approval))
        assertEquals(listOf("w2:p3" to null), port.reads)
        assertEquals(PaneReadState.SAVED, only().state)
        assertEquals(1, port.deliveries.size)
        assertEquals("Sent to Claude with the capture attached.", only().delivery)
        assertFalse(reads.approve(approval))
        note()
        PaneReadRequests(port, ledger, store, CoroutineScope(Dispatchers.Unconfined)) { now }.let {
            it.noteFrames("src", "s1", "w1:p1", "Claude on minipc2", frame())
            assertTrue(it.cards.isEmpty())
        }
        assertEquals(1, port.reads.size)
        assertEquals(1, port.deliveries.size)
    }

    @Test fun theMessageIsShortAndTheLogNamesNothingTheAgentWrote() {
        port.screen = "SECRET-LOOKING SCREEN TEXT\nsecond row"
        note(frame(extra = "HDWHY private reason\n"))
        yes()
        val (capture, message) = port.deliveries.single()
        assertEquals("Pane read: minipc/codex, 2 lines (approved)", message)
        assertFalse(message.contains("SCREEN TEXT"))
        assertEquals("SECRET-LOOKING SCREEN TEXT\nsecond row", capture.body)
        assertTrue(port.logs.none { "SCREEN" in it || "codex" in it || "minipc" in it || "private" in it })
        assertTrue(port.logs.any { "saved on the phone: s2/w2:p3, 2 lines" in it })
    }

    @Test fun noCancelsAndNothingIsRead() {
        note()
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
        assertTrue(port.reads.isEmpty())
    }

    @Test fun aFrameSeenWhileOffGetsNoFreshTenMinutesLater() {
        port.enabled = false
        note()
        assertEquals(PaneReadRefusal.OFF, only().refusal)
        assertNull(reads.approvalOf(only()))
        // Turned on within its 10 minutes: pending, with its first sighting's deadline.
        now += 60_000
        port.enabled = true
        note()
        assertEquals(PaneReadState.PENDING, only().state)
        assertEquals(1_790_000_000_000L + CrossServerCode.LIFETIME_MS, only().deadline)
        // Turned on after them: no card at all.
        val later = PaneReadRequests(port, ledger, store, CoroutineScope(Dispatchers.Unconfined)) { now + CrossServerCode.LIFETIME_MS }
        later.noteFrames("src", "s1", "w1:p1", "Claude on minipc2", frame())
        assertTrue(later.cards.isEmpty())
    }

    @Test fun aCodeReusedForADifferentRequestIsRefusedOnItsCard() {
        note()
        yes()
        note(frame(pane = "grok"))
        val reused = reads.cardsFor("s1", "w1:p1").single { it.refusal == PaneReadRefusal.REUSED }
        assertEquals(PaneReadState.REFUSED, reused.state)
        assertNull(reads.approvalOf(reused))
        assertEquals(1, port.reads.size)
    }

    // ---- refusals ----------------------------------------------------------------------------

    private fun approveRefused(text: String = frame()): PaneReadRequests.Card<String> {
        note(text)
        assertTrue(yes())
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
        assertTrue(yes())
        assertEquals(PaneReadState.SAVED, only().state)
    }

    @Test fun anUnknownServerIsRefusedOnTheCard() {
        assertEquals(PaneReadRefusal.UNKNOWN_SERVER, approveRefused(frame(server = "nowhere")).refusal)
    }

    @Test fun anUnknownPaneIsRefusedOnTheCard() {
        port.panes = listOf(grok)
        assertEquals(PaneReadRefusal.UNKNOWN_PANE, approveRefused().refusal)
    }

    @Test fun aPaneThatChangedSinceTheWatcherSawItIsRefused() {
        port.live = { it.copy(agent = "claude", tabLabel = "") }
        assertEquals(PaneReadRefusal.PANE_CHANGED, approveRefused().refusal)
        assertEquals(PaneReadState.REFUSED, only().state)
    }

    @Test fun aPaneGoneOnTheFreshReadIsRefused() {
        port.live = { null }
        assertEquals(PaneReadRefusal.UNKNOWN_PANE, approveRefused().refusal)
    }

    @Test fun anAmbiguousPaneAndTheOwnServerAreRefusedForGood() {
        port.panes = listOf(codex, codex.copy(id = "w2:p9"))
        assertEquals(PaneReadRefusal.AMBIGUOUS_PANE, approveRefused().refusal)
        note(frame(code = "AAAAAA", server = "minipc2"))
        yes(reads.cardsFor("s1", "w1:p1").last())
        assertEquals(PaneReadRefusal.SAME_SERVER, reads.cardsFor("s1", "w1:p1").last().refusal)
        note(frame(code = "BBBBBB", pane = "w2:p9"))
        yes(reads.cardsFor("s1", "w1:p1").last())
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
        port.undeliverable = "the pane stayed busy"
        note()
        yes()
        assertEquals(PaneReadState.SAVED, only().state)
        assertEquals("Not sent to the agent: the pane stayed busy. The capture stays on this phone in Pane reads.", only().delivery)
        assertEquals(1, store.list().size)
    }

    @Test fun turningItOffOrLockingWhileTheSendWaitsStopsIt() {
        port.hold = CompletableDeferred()
        note()
        yes()
        assertTrue(only().delivering)
        // The setting turned off (or the phone locked): the cards go and the job is cancelled.
        port.enabled = false
        reads.clear()
        port.hold!!.complete(Unit)
        assertTrue(port.deliveries.isEmpty())
        assertTrue(port.allowedAtSend.isEmpty())
        assertEquals("the capture stays on the phone", 1, store.list().size)
    }

    @Test fun theSendIsCheckedAgainRightBeforeItGoes() {
        port.hold = CompletableDeferred()
        note()
        yes()
        port.current = false
        port.hold!!.complete(Unit)
        assertEquals(listOf(false), port.allowedAtSend)
        assertTrue(port.deliveries.isEmpty())
        assertTrue(only().delivery!!.startsWith("Not sent to the agent: this chat changed"))
    }

    // ---- the phone's store -------------------------------------------------------------------

    @Test fun theCaptureIsKeptOnThePhoneWithItsHeader() {
        note(frame(extra = "HDLINES 2\nHDWHY check it\n"))
        yes()
        val c = store.list().single()
        assertEquals(c.name, only().saved)
        assertTrue(c.name, c.name.endsWith("-$code-w1_p1.txt"))
        assertEquals("line 2\nline 3", c.body)
        assertTrue(c.text.startsWith("HerdrDroid pane read $code\nserver: minipc\npane: codex (w2:p3)\ntime: "))
        assertTrue(c.text.contains("\nlines: 2\nrequested by: Claude on minipc2\nrequester: s1 w1:p1\nreason: check it\n----\nline 2"))
        assertEquals(listOf("w2:p3" to 2), port.reads)
    }

    private fun capture(i: Int, pane: String = "w1:p1") = PaneCapture(PaneReadStore.nameFor(now + i * 1000L, "AAAAAA", pane), "AAAAAA", "minipc",
        "codex", "w2:p3", "t", 1, "Claude on minipc2", "s1", pane, "", "row $i")

    @Test fun theStoreNeverOverwritesKeepsTheLastTwentyAndClearsTheSharedCopies() {
        assertTrue(store.save(capture(0)))
        assertFalse("never overwrites", store.save(capture(0).copy(body = "other")))
        assertEquals("row 0", store.list().single().body)
        // The same code and second from another pane is another file.
        assertTrue(store.save(capture(0, pane = "w1:p2")))
        for (i in 1..25) store.save(capture(i))
        val kept = store.list()
        assertEquals(PaneReadLimits.KEEP, kept.size)
        assertEquals("row 25", kept.first().body)
        File(shared, "herdrdroid-pane-read-x.txt").writeText("copy")
        store.clear()
        assertTrue(store.list().isEmpty())
        assertTrue("the share sheet's copy goes too", shared.listFiles().orEmpty().isEmpty())
    }

    @Test fun anEmptyOrBrokenFileNeverCountsTowardTheTwenty() {
        val dir = tmp.newFolder("reads2")
        val s = PaneReadStore(dir)
        File(dir, "99999999-999999-ZZZZZZ.txt").writeText("")
        for (i in 0 until PaneReadLimits.KEEP) s.save(capture(i))
        assertEquals(PaneReadLimits.KEEP, s.list().size)
        assertFalse(File(dir, "99999999-999999-ZZZZZZ.txt").exists())
    }

    @Test fun aCaptureNameAlreadyOnThePhoneIsRefused() {
        note()
        store.save(PaneCapture(PaneReadStore.nameFor(now, code, "w1:p1"), code, "x", "x", "x", "t", 0, "r", "s1", "w1:p1", "", ""))
        yes()
        assertEquals(PaneReadRefusal.EXISTS, only().refusal)
        assertTrue(port.reads.isEmpty())
    }
}
