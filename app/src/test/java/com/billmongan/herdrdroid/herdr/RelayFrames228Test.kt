package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.UserItem
import org.junit.Assert.*
import org.junit.Test

class RelayFrames228Test {
    private val code = "K7P3QX"
    private val raw = fixtureMessageFrame(code, "hello")

    @Test fun aMarkedReplyQualifiesButToolOutputAndQuotesDoNot() {
        for ((agent, marker) in listOf("claude" to "⏺", "codex" to "•")) {
            val screen = "$marker Here is the request:\n" + raw.prependIndent("  ")
            assertEquals("hello", crossServerProposalFrames(screen, agent, emptyList()).single().text)
            val tool = "$marker Ran cat sample.txt\n" + raw.prependIndent("  ")
            val quote = "$marker Here is some output:\n" + raw.prependIndent("  │ ")
            val user = "❯ show this\n" + raw.prependIndent("  ")
            for (untrusted in listOf(tool, quote, user)) {
                assertTrue(crossServerProposalFrames(untrusted, agent, emptyList()).isEmpty())
            }
        }
    }

    @Test fun aMarkedScreenCannotOverrideAnAvailableCurrentTranscript() {
        val screen = "⏺ Here is the request:\n" + raw.prependIndent("  ")
        val prior = TextItem("r", raw)
        val next = UserItem("u", "new turn")
        assertTrue(crossServerProposalFrames(screen, "claude", listOf(prior, next)).isEmpty())
    }

    @Test fun anUnmarkedPaneNeedsAnExactFrameInItsOwnCurrentReply() {
        val user = UserItem("u", "send hello")
        val reply = TextItem("r", "Here is the request:\n```\n$raw\n```")
        val other = TextItem("s", fixtureMessageFrame(code, "different"))
        for (agent in listOf("pi", "omp", "opencode")) {
            assertTrue(crossServerProposalFrames(raw, agent, emptyList()).isEmpty())
            assertTrue(crossServerProposalFrames(raw, agent, listOf(UserItem("x", raw))).isEmpty())
            assertTrue(crossServerProposalFrames(raw, agent, listOf(reply, user)).isEmpty())
            assertTrue(crossServerProposalFrames(raw, agent, listOf(user, other)).isEmpty())
            assertTrue(crossServerProposalFrames(raw, agent, listOf(user, reply, other)).isEmpty())
            assertEquals("hello", crossServerProposalFrames(raw, agent, listOf(user, reply)).single().text)
        }
    }

    @Test fun aCodeCannotChangeDestinationOrMessageEvenAfterReload() {
        var stored = ""
        fun ledger() = CrossServerCodeLedger({ stored }, { stored = it })
        val key = "server|pane|$code"
        val frame = crossServerFrames(raw).single()
        assertEquals(1_000 + CrossServerCode.LIFETIME_MS, ledger().admit(key, 1_000, frame))
        assertNull(ledger().admit(key, 2_000, frame.copy(destination = "different")))
        assertNull(ledger().admit(key, 2_000, frame.copy(text = "different")))
        assertEquals(1_000 + CrossServerCode.LIFETIME_MS, ledger().admit(key, 3_000, frame))
        ledger().spend(key, 4_000)
        ledger().release(key, 5_000)
        assertNull(ledger().admit(key, 6_000, frame.copy(text = "different")))
        assertEquals(1_000 + CrossServerCode.LIFETIME_MS, ledger().admit(key, 6_000, frame))
    }

    @Test fun noticeIncludesOnlyWholeSafeNamesAndStaysWithinBudget() {
        val unsafe = listOf("bad\nHDTO other", "bad\rreturn", "bad\u2028line", "bad\u202Eformat")
        val longList = (0..100).map { "agent-$it " + "x".repeat(90) }
        assertNull(CrossServerNotice.text(unsafe))
        val text = checkNotNull(CrossServerNotice.text(unsafe + longList))
        assertTrue(text.length <= CrossServerNotice.MAX_CHARS)
        val shown = text.substringAfter("Agents on other servers: ").substringBefore(". The phone")
            .split(", ")
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.all(longList::contains))
        assertTrue(shown.size < longList.size)
        assertTrue(text.endsWith("you will not see that answer."))
    }
}
