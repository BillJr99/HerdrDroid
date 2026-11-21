package com.billmongan.herdrdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 2.1.4: the cross-server notice's text and its sending rules, without a server. */
@RunWith(AndroidJUnit4::class)
class CrossServerNoticeTest {
    private val others = listOf("Hermes on laptop2", "OpenCode · opencode-college on laptop2", "Claude on laptop2")

    @Test fun noticeHasTheInstructionsTheAgentsAndTheApprovalLine() {
        val text = checkNotNull(CrossServerNotice.text(others))
        assertTrue(text.startsWith("HerdrDroid notice, for information only."))
        assertTrue(text.contains("No reply is needed; if you must answer, reply only \"ok\"."))
        assertTrue(text.contains("HDMSG1 <code>") && text.contains("HDEND <code>") && text.contains("HD> "))
        assertTrue(text.contains("at most 1024 UTF-8 bytes"))
        assertTrue(text.contains("Do not send it yourself"))
        assertTrue(text.contains("Agents on other servers: Claude on laptop2, Hermes on laptop2, OpenCode · opencode-college on laptop2."))
        assertTrue(text.endsWith("The phone user approves every delivery."))
        // Never asks for a reply or an action beyond "ok": the proposal text's "name it in your reply" is not here.
        assertFalse(text.contains("name it in your reply"))
        assertFalse(text.contains("—"))
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
        CrossServerNotifier.Candidate("laptop2", pane(session), text, hold)

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
        assertTrue(stored.contains("laptop2|w2:p9"))
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
