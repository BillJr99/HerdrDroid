package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.0 (Bill, 2026-11-22: "open code has some kind of problem in its terminal view that is
 * showing up as a question that cannot be rendered or answered in chat"). OpenCode's turn failed
 * because llmproxy had no free model for its tier. OpenCode drew the error in its transcript,
 * above an empty input box, and herdr kept the pane "blocked". The app took "blocked" for a
 * question, found none on the screen, and showed the "couldn't read the question" card. The
 * captures (live-2026-11-22-error, private text replaced by neutral text of the same length) are
 * that pane, styled and plain.
 */
class OpenCodeError220Test {
    private fun res(path: String) = checkNotNull(javaClass.getResource(path)).readText()
    private val styled = res("/screens/opencode/live-2026-11-22-error/error.ansi")
    private val plain = res("/screens/opencode/live-2026-11-22-error/error.txt")
    private val screens = listOf(styled, plain)
    private fun pane(status: Status, agent: String = "opencode") = Pane("w1:p1", "w1:t1", "w1", agent, status, "", "/fixture", terminalId = "t1")

    @Test fun `the false question came from herdr's blocked status with no question on screen`() {
        for (s in screens) {
            // Nothing on the screen asks: no OpenCode question, permission or other card.
            assertNull(detectPrompt(s, blocked = true, agent = "opencode"))
            assertNull(openCodeNeedsYouReason(s))
            // Before 2.2.0 the error went unrecognized, so herdr's "blocked" made the unread-question card.
            val old = withNeedsYouCard(Status.NEEDS_YOU, null, suppressed = false, agent = "OpenCode")
            assertTrue(checkNotNull(old).unreadQuestion)
            // Now the error is read, and it holds the card's place: no question card at all.
            val notice = agentErrorNotice(s, "opencode", "OpenCode")
            assertNotNull(notice)
            assertNull(withNeedsYouCard(Status.NEEDS_YOU, null, suppressed = notice != null, agent = "OpenCode"))
        }
    }

    @Test fun `the error card names llmproxy's missing model and carries the whole error text`() {
        for (s in screens) {
            val n = checkNotNull(agentErrorNotice(s, "opencode", "OpenCode"))
            assertEquals("llmproxy: no model available", n.title)
            assertEquals(LLMPROXY_NO_MODEL, n.summary)
            assertTrue(n.modelUnavailable)
            assertTrue(n.switchModel)
            assertTrue(n.message!!, n.message!!.startsWith("Error: No 'llmproxy__flagship/free' models are currently available."))
            assertTrue(n.message!!.endsWith("so the same model may be paid here and free elsewhere."))
            // OpenCode's sidebar beside the error is not part of it.
            assertFalse(n.message!!.contains("Getting started"))
            assertFalse(n.message!!.contains("OpenCode includes"))
            // The user's message above the failed turn, read whole (here the app's own notice).
            assertTrue(n.retryText!!.startsWith("HerdrDroid notice, for information only."))
            assertTrue(n.retryText!!.endsWith("will not see that reply."))
        }
        assertEquals(agentErrorNotice(styled, "opencode", "OpenCode"), agentErrorNotice(plain, "opencode", "OpenCode"))
    }

    @Test fun `a blocked OpenCode pane with its box and no dialog is shown idle, keeping herdr's status for sends`() {
        val home = res("/screens/opencode/live-2026-11-20-ursinus/empty.ansi")
        for (s in screens + home) {
            assertTrue(openCodeStaleBlocked(s))
            val marked = markScreenNeedsYou(listOf(pane(Status.NEEDS_YOU)), mapOf("w1:p1" to s)).single()
            assertTrue(marked.openCodeStaleBlock)
            assertEquals(Status.NEEDS_YOU, marked.status)
            assertEquals(Status.NEEDS_YOU, marked.sendStatus)
        }
        // The home screen after /new has no error: no card, only the idle display.
        assertNull(agentErrorNotice(home, "opencode", "OpenCode"))
    }

    @Test fun `a real OpenCode question or permission still waits for you`() {
        for (path in listOf("/screens/opencode/live-2026-11-21/permission-shell-2.3.2.txt", "/screens/opencode/live-2026-11-21/question-2.3.2.txt")) {
            val s = res(path)
            assertFalse(path, openCodeStaleBlocked(s))
            val marked = markScreenNeedsYou(listOf(pane(Status.NEEDS_YOU)), mapOf("w1:p1" to s)).single()
            assertFalse(marked.openCodeStaleBlock)
            assertNotNull(path, detectPrompt(s, blocked = true, agent = "opencode"))
        }
        // No tail read, no change.
        assertFalse(markScreenNeedsYou(listOf(pane(Status.NEEDS_YOU)), emptyMap()).single().openCodeStaleBlock)
    }

    @Test fun `the frame template in the app's own notice is never read as a proposal`() {
        for (s in screens) {
            assertTrue(stripAnsi(s).contains("HDMSG2 <code>"))
            assertTrue(crossServerFrames(stripAnsi(s)).isEmpty())
        }
    }

    @Test fun `an Error paragraph that is not the failed turn is no error card`() {
        // The turn footer taken away: the paragraph is no longer the end of a turn.
        val noFooter = plain.lines().filterNot { it.contains("· 1m 25s") }.joinToString("\n")
        assertNull(openCodeTurnError(noFooter))
        // The error's first row scrolled off the top: not read from its middle.
        val rows = plain.lines()
        val cut = rows.drop(rows.indexOfFirst { it.contains("Error: No") } + 1).joinToString("\n")
        assertNull(openCodeTurnError(cut))
        // An ordinary reply above the footer is no error.
        val reply = plain.replace("Error: No", "Done:  No")
        assertNull(agentErrorNotice(reply, "opencode", "OpenCode"))
        // Other agents never get OpenCode's reading.
        assertNull(agentErrorNotice(plain, "claude", "Claude"))
    }

    @Test fun `llmproxy's wording on any agent reads as a model availability error`() {
        assertEquals(NO_MODEL, detectAgentError("  Error: No 'flagship' models are currently available.\n❯ \n"))
        assertEquals(LLMPROXY_NO_MODEL, detectAgentError("  Error: llmproxy: No model currently qualifies for this tier.\n"))
        assertEquals(LLMPROXY_NO_MODEL, detectAgentError("  No model currently qualifies (llmproxy)\n"))
        val n = checkNotNull(agentErrorNotice("  Error: llmproxy: No model currently qualifies.\n❯ \n", "omp", "Oh My Pi"))
        assertEquals("llmproxy: no model available", n.title)
        assertTrue(n.modelUnavailable)
        // Away from OpenCode's turn layout the app can't tell which message failed: no Retry, no picker.
        assertNull(n.retryText)
        assertFalse(n.switchModel)
        // Your own line about it is not an error.
        assertNull(detectAgentError("❯ why does it say No 'flagship' models are currently available?\n"))
    }

    @Test fun `a different error has a different identity, so acknowledging one does not hide the next`() {
        val a = checkNotNull(agentErrorNotice(plain, "opencode", "OpenCode"))
        val b = checkNotNull(agentErrorNotice(plain.replace("· 1m 25s", "· 2m 03s"), "opencode", "OpenCode"))
        val c = checkNotNull(agentErrorNotice(plain.replace("No 'flagship'", "No 'standard'"), "opencode", "OpenCode"))
        assertNotEquals(a.identity, b.identity)
        assertNotEquals(a.identity, c.identity)
        assertEquals(a.identity, checkNotNull(agentErrorNotice(styled, "opencode", "OpenCode")).identity)
    }
}
