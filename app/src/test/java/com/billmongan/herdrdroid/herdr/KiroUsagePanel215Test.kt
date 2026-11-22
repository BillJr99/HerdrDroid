package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.1.5 (Bill, 2026-11-22: "Kiro doesn't have a draft in text ... update herdrdroid 2.1.5 if a fix
 * is needed for kiro to ignore prefilled text"). A live Kiro pane with its /usage panel left open,
 * drawn without the "This session: N credits" row, and the same pane's empty composer after a
 * restart. Earlier messages in both captures are replaced with neutral text of the same length.
 */
class KiroUsagePanel215Test {
    private fun capture(name: String) =
        checkNotNull(javaClass.getResource("/screens/kiro/live-2026-11-22-usage/$name")).readText()

    private val panel get() = capture("usage-open.ansi")
    private val restarted get() = capture("after-restart.ansi")

    /** What [Herdr.readUsageState] makes of a live read, without the SSH guard around it. */
    private fun stateOf(visible: String): UsageState = when {
        dismissibleUsageFrame("kiro", visible) != null -> UsageState.Open(UsageOverlay("occupant", visible))
        usageDialogHint("kiro", visible) || visible.isBlank() -> UsageState.Unknown
        else -> UsageState.Closed
    }

    @Test fun `the panel without its session row is Kiro's usage panel`() {
        // herdr reads the live pane both ways: plain for the close guard, styled for sends.
        for (s in listOf(panel, stripAnsi(panel))) {
            val frame = checkNotNull(dismissibleUsageFrame("kiro", s))
            assertEquals("/usage", frame.first())
            assertTrue(frame.last().startsWith("esc to close"))
            assertTrue(usageDialogHint("kiro", s))
            assertTrue(usageScreenShown("kiro", s))
            assertTrue(stateOf(s) is UsageState.Open)
        }
        // The layout seen on 2026-11-13, with the session row, is still known.
        val withSession = panel.replace(" \u001B[0m\u001B[1mEstimated Usage", " This session: 0.07 credits\n \u001B[0m\u001B[1mEstimated Usage")
        assertNotNull(dismissibleUsageFrame("kiro", withSession))
        // Its reading is the plan's credits.
        val items = parseQuota(stripAnsi(panel))
        assertEquals(1, items.size)
        assertEquals(2.3, items[0].usedPercent, 0.001)
        // Anything else drawn under the panel is not the panel: no Esc for it.
        assertNull(dismissibleUsageFrame("kiro", "$panel\n› fix the build"))
        assertNull(dismissibleUsageFrame("kiro", panel.replace("Tab to switch to /context", "Tab to switch to /model")))
        assertNull(dismissibleUsageFrame("claude", panel))
    }

    @Test fun `the open panel is no draft and no question, and holds sends for the panel`() {
        for (s in listOf(panel, stripAnsi(panel))) {
            assertNull(detectPrompt(forPhone(s), false, "kiro"))
            // No input line shows under the panel.
            assertNull(inputLineOf(s, "kiro"))
            // The box parser alone reads the earlier message above the panel as box text; the
            // panel check is what keeps it from the draft card and from Send draft / Clear draft.
            assertNotNull(composerDraftOf(s, "kiro"))
            assertTrue(usageScreenShown("kiro", s))
        }
    }

    @Test fun `after a restart the box is empty, with no draft and no question`() {
        for (s in listOf(restarted, stripAnsi(restarted))) {
            assertEquals("", inputLineOf(s, "kiro"))
            assertNull(composerDraftOf(s, "kiro"))
            assertNull(detectPrompt(forPhone(s), false, "kiro"))
            assertNull(dismissibleUsageFrame("kiro", s))
            assertFalse(usageDialogHint("kiro", s))
            assertFalse(usageScreenShown("kiro", s))
            assertEquals(UsageState.Closed, stateOf(s))
        }
    }

    @Test fun `the app's close sends one Esc for the panel and sees it closed`() = runBlocking {
        val reads = ArrayDeque(listOf(stateOf(stripAnsi(panel)), stateOf(stripAnsi(restarted))))
        var escapes = 0
        val outcome = closeUsageAndVerify({ true }, { reads.removeFirst() }, { escapes++; true }, settle = {})
        assertEquals(UsageCloseOutcome(true, 1, null), outcome)
        assertEquals(1, escapes)
    }
}
