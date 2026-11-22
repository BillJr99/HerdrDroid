package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/**
 * 2.1.4, Bill: "The grok pane was a usage tab probably opened by herdrdroid make sure that doesn't
 * get stuck in the future in 2.1.4". Grok 1.0.92's session/usage dialog (reconstructed from its
 * footer, see screens/grok/reconstructed-1.0.92/usage-dialog.expect), and closing a usage dialog
 * with Esc until a fresh read shows it closed.
 */
class GrokUsageDialog214Test {
    private val dialog = checkNotNull(javaClass.getResource("/screens/grok/reconstructed-1.0.92/usage-dialog.txt")).readText()
    private val idle = checkNotNull(javaClass.getResource("/screens/grok/live-2026-11-21/idle-compacted.txt")).readText()

    // ---- recognizing the dialog ----------------------------------------------------------------

    @Test fun theReconstructedDialogIsAKnownLayout() {
        val frame = checkNotNull(dismissibleUsageFrame("grok", dialog))
        assertTrue(frame.first().startsWith("┌") && frame.last().startsWith("└"))
        assertTrue(usageDialogHint("grok", dialog))
        assertNull("not a question card", detectPrompt(dialog, false, "grok"))
        // Grok's empty composer under the dialog reads as an empty input line: on its own it would let a send through.
        assertEquals("", grokInputText(dialog))
    }

    @Test fun theDialogDrawnOverChatTextIsFoundByItsColumn() {
        val shifted = dialog.lines().joinToString("\n") { row ->
            if (row.trimStart().firstOrNull() in setOf('┌', '│', '└') && !row.contains('❯')) "   chat text left of the box " + row.trimStart() else row
        }
        assertNotNull(dismissibleUsageFrame("grok", shifted))
    }

    @Test fun onlyTheKnownLayoutAuthorizesEsc() {
        assertNull(dismissibleUsageFrame("grok", idle))
        assertFalse(usageDialogHint("grok", idle))
        // A draft under the dialog: never an Esc (the draft is the user's), but still a dialog.
        val draft = dialog.replace("│ ❯" + " ".repeat(126) + "│", "│ ❯ half-typed draft" + " ".repeat(108) + "│")
        assertNotEquals(dialog, draft)
        assertNull(dismissibleUsageFrame("grok", draft))
        assertTrue(usageDialogHint("grok", draft))
        // No bottom border, or another footer: not this dialog.
        assertNull(dismissibleUsageFrame("grok", dialog.replace("└", " ")))
        assertNull(dismissibleUsageFrame("grok", dialog.replace("Esc close", "Esc cancel")))
        // The footer quoted in chat text, with no box around it.
        assertNull(dismissibleUsageFrame("grok", idle + "\n  Tab switch  |  ↑/↓ scroll  |  c copy session ID  |  Esc close\n"))
        // The same text for another agent never counts as Grok's dialog.
        assertNull(dismissibleUsageFrame("claude", dialog))
        assertNull(dismissibleUsageFrame("codex", dialog))
    }

    @Test fun antigravityUsageScreenIsKnownByItsFooterAndLimits() {
        val agy = """
  Gemini Pro                    ██████████░░░░░░  62%
  Resets in 4h 12m

  ↑/↓ Scroll · pgup/pgdown Page · ctrl+end Bottom · ctrl+home Top · esc Close
                                                                                                              Gemini 4 Argon · medium
"""
        assertNotNull(dismissibleUsageFrame("agy", agy))
        assertNull(dismissibleUsageFrame("agy", agy.replace("62%", "")))
        assertNull(dismissibleUsageFrame("agy", "$agy\n  > a draft"))
    }

    @Test fun whichCommandsOpenAUsageDialog() {
        for (agent in listOf("grok", "claude", "kiro", "agy", "antigravity")) assertTrue(agent, opensUsageDialog(agent, "/usage"))
        assertTrue(opensUsageDialog("grok", "  /usage  "))
        assertFalse(opensUsageDialog("grok", "/model"))
        assertFalse(opensUsageDialog("grok", "what is /usage for?"))
        // These print into the conversation and leave nothing open.
        assertFalse(opensUsageDialog("codex", "/status"))
        assertFalse(opensUsageDialog("copilot", "/usage"))
        assertFalse(opensUsageDialog("hermes", "/usage"))
        assertFalse(opensUsageDialog(null, "/usage"))
    }

    // ---- close and verify ----------------------------------------------------------------------

    private val shown = UsageOverlay("occupant", dialog)

    private class Script(vararg states: Any) {
        val queue = ArrayDeque(states.toList())
        var reads = 0
        var escapes = 0
        suspend fun read(): UsageState {
            reads++
            return when (val s = queue.removeFirstOrNull() ?: UsageState.Unknown) {
                is Throwable -> throw s
                else -> s as UsageState
            }
        }
    }

    private fun run(s: Script, current: () -> Boolean = { true }, send: suspend (UsageOverlay) -> Boolean = { s.escapes++; true }) = runBlocking {
        closeUsageAndVerify(current, { s.read() }, send, settle = {})
    }

    @Test fun oneEscAndAFreshReadThatShowsItClosed() {
        val s = Script(UsageState.Open(shown), UsageState.Closed)
        assertEquals(UsageCloseOutcome(true, 1, null), run(s))
        assertEquals(2, s.reads)
    }

    @Test fun aSecondEscWhenTheFirstDidNotCloseIt() {
        val s = Script(UsageState.Open(shown), UsageState.Open(shown), UsageState.Closed)
        assertEquals(UsageCloseOutcome(true, 2, null), run(s))
        assertEquals(2, s.escapes)
    }

    @Test fun boundedWhenItNeverCloses() {
        val s = Script(*Array(10) { UsageState.Open(shown) })
        val out = run(s)
        assertFalse(out.closed)
        assertEquals(3, s.escapes)
        assertEquals(3, out.escapes)
        assertEquals("it was still open after 3 Esc", out.why)
    }

    @Test fun nothingIsSentWhenItIsAlreadyClosed() {
        val s = Script(UsageState.Closed)
        assertEquals(UsageCloseOutcome(true, 0, null), run(s))
        assertEquals(0, s.escapes)
    }

    @Test fun aReadThatCantTellIsNeverTakenAsClosed() {
        val s = Script(UsageState.Unknown, UsageState.Unknown, UsageState.Closed)
        val out = run(s)
        assertFalse(out.closed)
        assertEquals(0, s.escapes)
        assertEquals("its screen couldn't be checked", out.why)
    }

    @Test fun aFailedReadOrSendIsRetriedNotSwallowed() {
        // The read fails once, then the Esc fails once; the next Esc goes and the read confirms.
        val s = Script(HerdrError("timed out", -2), UsageState.Open(shown), UsageState.Open(shown), UsageState.Closed)
        var calls = 0
        val out = run(s, send = { calls++; if (calls == 1) throw HerdrError("send failed", 1) else { s.escapes++; true } })
        assertEquals(UsageCloseOutcome(true, 1, null), out)
        assertEquals(2, calls)
    }

    @Test fun nothingIsReadOrSentOnceThePaneChanged() {
        val s = Script(UsageState.Open(shown))
        assertFalse(run(s, current = { false }).closed)
        assertEquals(0, s.reads)
        assertEquals(0, s.escapes)
        // Changed between the read and the Esc.
        var checks = 0
        val t = Script(UsageState.Open(shown))
        assertFalse(run(t, current = { ++checks < 2 }).closed)
        assertEquals(1, t.reads)
        assertEquals(0, t.escapes)
    }

    @Test fun cancellationIsPassedOn() {
        val s = Script(CancellationException("left"))
        try {
            run(s)
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals(0, s.escapes)
    }

    @Test fun aCancelledCallerStillFinishesTheCloseWhenRunNonCancellable() = runBlocking {
        val s = Script(UsageState.Open(shown), UsageState.Closed)
        var outcome: UsageCloseOutcome? = null
        val job = launch {
            withContext(NonCancellable) {
                outcome = closeUsageAndVerify({ true }, { s.read() }, { s.escapes++; true }, settle = { delay(50) })
            }
        }
        delay(10)
        job.cancel()
        job.join()
        assertEquals(UsageCloseOutcome(true, 1, null), outcome)
        assertEquals(1, s.escapes)
    }
}
