package com.billmongan.herdrdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 2.1.4: the cross-server notice's text and its sending rules, without a server. */
@RunWith(AndroidJUnit4::class)
class CrossServerNoticeTest {
    private val others = listOf("Hermes on minipc2", "OpenCode · opencode-ursinus on minipc2", "Claude on minipc2")

    /** Grok's RC review 4: a server with no name is "server N" to agents, never its account, host or port. */
    @Test fun agentFacingServerLabelsCarryNoAccountHostOrPort() {
        val named = com.billmongan.herdrdroid.store.Server(id = "a", name = "minipc2", host = "10.0.0.19", port = 2222, user = "bill", herdrPath = "herdr")
        val blank = com.billmongan.herdrdroid.store.Server(id = "b", name = "  ", host = "10.0.0.26", port = 2222, user = "bill", herdrPath = "herdr")
        val all = listOf(named, blank)
        assertEquals("minipc2", com.billmongan.herdrdroid.store.agentServerLabel(named, all))
        assertEquals("server 2", com.billmongan.herdrdroid.store.agentServerLabel(blank, all))
        assertEquals("another server", com.billmongan.herdrdroid.store.agentServerLabel(blank, listOf(named)))
        for (label in all.map { com.billmongan.herdrdroid.store.agentServerLabel(it, all) }) {
            assertFalse(label, label.contains("bill") || label.contains("10.0.0") || label.contains("2222") || label.contains("@"))
        }
        // The phone user's own label is unchanged.
        assertEquals("bill@10.0.0.26", blank.label)
    }

    @Test fun noticeHasTheNewFrameFormatTheAgentsAndTheConfirmationLine() {
        val text = checkNotNull(CrossServerNotice.text(others))
        assertTrue(text.startsWith("HerdrDroid notice, for information only."))
        assertTrue(text.contains("No reply is needed; if you must answer, reply only \"ok\"."))
        // 2.1.5: proposals only when the user asks in chat, with a code the agent picks and a named destination.
        assertTrue(text.contains("When the user asks you in chat to send a message to an agent on another server"))
        assertTrue(text.contains("HDMSG2 <code>") && text.contains("HDTO <destination>") && text.contains("HDEND <code>") && text.contains("HD> "))
        assertFalse(text.contains("HDMSG1"))
        assertTrue(text.contains("6 characters from ${CrossServerCode.ALPHABET}"))
        assertTrue(text.contains("a new code you pick for each frame"))
        assertTrue(text.contains("then wait"))
        assertTrue(text.contains("one name from the list below"))
        assertFalse(text.contains("one-time code"))
        assertTrue(text.contains("at most 1024 UTF-8 bytes"))
        assertTrue(text.contains("do not send it yourself"))
        assertTrue(text.contains("Agents on other servers: Claude on minipc2, Hermes on minipc2, OpenCode · opencode-ursinus on minipc2."))
        // 2.2.0: the user approves with Yes or No on the phone; nobody types the code back.
        assertTrue(text.endsWith("delivered only if the user taps Yes on the phone; you will not see that answer."))
        assertTrue(text.contains("with a Yes/No question"))
        assertFalse(text.contains("types the code") || text.contains("type the code"))
        assertFalse(text.contains("name it in your reply"))
        assertFalse(text.contains("\u2014"))
        // The notice's own template, echoed in a pane, is never a frame.
        assertTrue(crossServerFrames(text).isEmpty())
    }

    @Test fun noticeIsShortAndHoldsNoHostsPortsKeysOrPaths() {
        val text = checkNotNull(CrossServerNotice.text(others))
        assertTrue("${text.length} chars", text.length <= CrossServerNotice.MAX_CHARS)
        assertTrue(text.lines().size <= 8)
        assertFalse(Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""").containsMatchIn(text))
        assertFalse(Regex(""":\d{2,5}\b""").containsMatchIn(text))
        assertFalse(text.contains(".invalid") || text.contains("/home/") || text.contains("ssh") || text.contains("key"))
        assertFalse(Regex("""\bw\d+:p\d+\b""").containsMatchIn(text))
    }

    @Test fun noAgentsElsewhereMeansNoNotice() {
        assertNull(CrossServerNotice.text(emptyList()))
        assertNull(CrossServerNotice.text(listOf(" ")))
    }

    @Test fun quotaThresholdDefaultsTo95AndSkipsAtOrAbove() {
        assertEquals(95, CrossServerNotice.DEFAULT_QUOTA_THRESHOLD)
        fun at(p: Double) = QuotaReading(0, listOf(QuotaItem("5h", 10.0, null), QuotaItem("week", p, null)))
        assertFalse(CrossServerNotice.quotaSkips(at(94.0), 95))
        assertTrue(CrossServerNotice.quotaSkips(at(95.0), 95))
        assertTrue(CrossServerNotice.quotaSkips(at(85.0), 80))
        assertFalse(CrossServerNotice.quotaSkips(at(79.0), 80))
        assertFalse(CrossServerNotice.quotaSkips(at(100.0), 100))
        assertFalse(CrossServerNotice.quotaSkips(null, 95))
        assertFalse(CrossServerNotice.quotaSkips(QuotaReading(0, emptyList()), 50))
    }

    @Test fun thresholdStaysBetween50And100InStepsOf5() {
        assertEquals(50, CrossServerNotice.clampThreshold(10))
        assertEquals(100, CrossServerNotice.clampThreshold(130))
        assertEquals(80, CrossServerNotice.clampThreshold(81))
        assertEquals(85, CrossServerNotice.clampThreshold(83))
        assertEquals(95, CrossServerNotice.clampThreshold(95))
    }

    private var stored = "{}"
    private var now = 1_000_000_000L
    private fun notifier() = CrossServerNotifier(NoticeLedger({ stored }, { stored = it }), { now })
    private fun pane(session: String = "s1") = Pane("w2:p9", "t", "w2", "hermes", Status.IDLE, "", "/x", sessionId = session)
    private fun cand(text: String = "A", session: String = "s1", hold: Boolean = false) =
        CrossServerNotifier.Candidate("minipc2", pane(session), text, hold)

    @Test fun oncePerSessionAndReachableSet() {
        val n = notifier()
        assertEquals(CrossServerNotifier.Decision.SEND, n.decide(cand(), false))
        n.sent(cand())
        assertEquals(CrossServerNotifier.Decision.ALREADY_SENT, n.decide(cand(), false))
        assertEquals(CrossServerNotifier.Decision.ALREADY_SENT, n.decide(cand(), true))
        now += 2 * 3_600_000
        assertEquals(CrossServerNotifier.Decision.ALREADY_SENT, n.decide(cand(), false))
        // A new agent session, or a changed reachable set, is due again.
        assertEquals(CrossServerNotifier.Decision.SEND, n.decide(cand(session = "s2"), false))
        assertEquals(CrossServerNotifier.Decision.SEND, n.decide(cand(text = "B"), false))
    }

    @Test fun neverMoreThanOnceAnHourPerPaneUnlessForced() {
        val n = notifier()
        n.sent(cand())
        now += 59 * 60_000
        assertEquals(CrossServerNotifier.Decision.HOURLY, n.decide(cand(text = "B"), false))
        assertEquals(CrossServerNotifier.Decision.HOURLY, n.decide(cand(session = "s2"), false))
        // "Tell agents now" lifts only the hourly limit.
        assertEquals(CrossServerNotifier.Decision.SEND, n.decide(cand(text = "B"), true))
        assertEquals(CrossServerNotifier.Decision.HOLD, n.decide(cand(text = "B", hold = true), true))
        now += 2 * 60_000
        assertEquals(CrossServerNotifier.Decision.SEND, n.decide(cand(text = "B"), false))
    }

    @Test fun whatWasSentSurvivesARestart() {
        notifier().sent(cand())
        assertTrue(stored.contains("minipc2|w2:p9"))
        val restarted = notifier()
        assertEquals(CrossServerNotifier.Decision.ALREADY_SENT, restarted.decide(cand(), false))
        assertEquals(CrossServerNotifier.Decision.HOURLY, restarted.decide(cand(text = "B"), false))
        stored = "not json"
        assertEquals(CrossServerNotifier.Decision.SEND, notifier().decide(cand(), false))
    }

    @Test fun heldWhileWorkingWithoutUsingATry() {
        val n = notifier()
        repeat(20) { assertEquals(CrossServerNotifier.Decision.HOLD, n.decide(cand(hold = true), false)) }
        assertEquals(CrossServerNotifier.Decision.SEND, n.decide(cand(), false))
    }

    @Test fun refusedTriesWaitAndThenGiveUpWithANotice() {
        val n = notifier()
        for (i in 1 until n.maxTries) {
            assertEquals(CrossServerNotifier.Decision.SEND, n.decide(cand(), false))
            assertFalse(n.refused(cand(), false))
            assertEquals(CrossServerNotifier.Decision.RETRY_LATER, n.decide(cand(), false))
            now += 121_000
        }
        assertTrue(n.refused(cand(), false))
        now += 10 * 3_600_000
        assertEquals(CrossServerNotifier.Decision.GAVE_UP, n.decide(cand(), false))
        // A new session, or the user's tap, tries again.
        assertEquals(CrossServerNotifier.Decision.SEND, n.decide(cand(session = "s2"), false))
        assertEquals(CrossServerNotifier.Decision.SEND, n.decide(cand(), true))
    }
}
