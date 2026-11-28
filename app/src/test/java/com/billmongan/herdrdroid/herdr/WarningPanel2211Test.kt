package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningPanel2211Test {
    private val pane = Pane("pane", "tab", "workspace", "codex", Status.IDLE, "Agent", "/project",
        scrollOffset = 0, terminalId = "terminal", sessionId = "session")
    private val initial = """
        ›
          GPT default · /project
          ← for agents · ? for shortcuts                           ⚠ 1 warning · f2 to view
    """.trimIndent()

    // Shape from OpenAI Codex rust-v0.172.0's warnings_page renderer snapshot.
    private fun panel(number: Int, count: Int, details: String, padding: String = "\n\n") =
        "Earlier transcript\n\n  Warnings · $number of $count · Warning\n\n  $details" +
            padding + "  k keep & next · esc dismiss & close · ⌃o copy · ←/→ warning"

    private class Transport(vararg val frames: WarningPaneFrame) {
        var next = 0
        var refused = false
        val keys = mutableListOf<String>()

        suspend fun fetch(pane: Pane, allowed: () -> Boolean = { true }): PaneWarningResult =
            readCodexWarningPages(pane, allowed, { frames.getOrNull(next++) }, { _, key ->
                if (!refused) keys.add(key)
                !refused
            }, {})
    }

    private fun frame(text: String = initial, current: Pane = pane, occupant: String = "same") =
        WarningPaneFrame(current, occupant, text)

    @Test fun opensReadsKeepsAndVerifiesRestoredScreen() = runBlocking {
        val details = "Could not save a diagnostic.\n  More detail."
        val transport = Transport(frame(), frame(), frame(panel(1, 1, details)), frame())
        assertEquals(PaneWarningResult.Ready(listOf("Could not save a diagnostic.\nMore detail.")), transport.fetch(pane))
        assertEquals(listOf("f2", "k"), transport.keys)
    }

    @Test fun multiPageViewerKeepsEveryWarningAndClosesAtTheEnd() = runBlocking {
        val transport = Transport(frame(initial.replace("1 warning", "2 warnings")),
            frame(panel(1, 2, "First diagnostic")), frame(panel(2, 2, "Second diagnostic")),
            frame(initial.replace("1 warning", "2 warnings")))
        assertEquals(PaneWarningResult.Ready(listOf("First diagnostic", "Second diagnostic")), transport.fetch(pane))
        assertEquals(listOf("f2", "k", "k"), transport.keys)
    }

    @Test fun busyDraftAndOpenQuestionNeverSendKeys() = runBlocking {
        val question = initial.replace("›", "Press enter to confirm or esc to cancel\n›")
        for (unsafe in listOf(frame(current = pane.copy(status = Status.WORKING)),
                frame(initial.replace("›", "› unfinished draft")), frame(question))) {
            val transport = Transport(unsafe)
            assertTrue(transport.fetch(pane) is PaneWarningResult.Unavailable)
            assertTrue(transport.keys.isEmpty())
        }
    }

    @Test fun replacementOrUnprovenPanelNeverReceivesCloseKey() = runBlocking {
        val view = panel(1, 1, "Diagnostic")
        val changed = listOf(frame(view, occupant = "different"),
            frame(view, current = pane.copy(terminalId = "replacement")),
            frame(view.replace("k keep & next", "enter select")))
        for (unsafe in changed) {
            val transport = Transport(frame(), unsafe)
            assertTrue(transport.fetch(pane) is PaneWarningResult.Unavailable)
            assertEquals(listOf("f2"), transport.keys)
        }
    }

    @Test fun invalidVisitOrHostGuardRefusalNeverSendsAKey() = runBlocking {
        val transport = Transport(frame())
        assertTrue(transport.fetch(pane) { false } is PaneWarningResult.Unavailable)
        transport.refused = true
        assertTrue(transport.fetch(pane) is PaneWarningResult.Unavailable)
        assertTrue(transport.keys.isEmpty())
    }

    @Test fun clippedBodyClosesButDoesNotReturnPartialWarningAsComplete() = runBlocking {
        val clipped = panel(1, 1, "Truncated diagnostic", padding = "\n")
        val transport = Transport(frame(), frame(clipped), frame())
        assertTrue(transport.fetch(pane) is PaneWarningResult.Unavailable)
        assertEquals(listOf("f2", "k"), transport.keys)
        assertNull(codexWarningPage(clipped.replace("1 of 1", "2 of 1")))
    }
}
