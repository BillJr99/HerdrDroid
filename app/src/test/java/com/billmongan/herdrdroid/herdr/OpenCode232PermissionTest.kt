package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.1.2 (Bill, 2026-11-21: "herdrdroid did not render the question card for OpenCode"). OpenCode
 * v2.3.2 draws "ctrl+f fullscreen" between its Reject button and "⇆ select", so the button row no
 * longer ended in "Reject" and the card fell back to the terminal-only review. herdr skips screen
 * detection for OpenCode and reports the pane done or idle, so the screen alone has to carry it.
 */
class OpenCode232PermissionTest {
    private val frame = javaClass.getResource("/screens/opencode/live-2026-11-21/permission-shell-2.3.2.txt")!!.readText()

    /** OpenCode marks the selected button with a background color only (the first one when the dialog opens). */
    private fun focused(text: String, focus: String) = listOf("Allow once", "Always allow", "Reject").fold(text) { t, label ->
        t.replace("   $label", "   \u001B[${if (label == focus) 44 else 49}m$label\u001B[0m")
    }

    private fun card(text: String, agent: String = "opencode-work"): Prompt {
        val p = detectPrompt(text, blocked = false, agent = agent)
        assertNotNull("no card for:\n$text", p)
        return p!!
    }

    @Test fun theShellPermissionIsAnActionableCardWhileHerdrSaysDone() {
        for (agent in listOf("opencode", "opencode-work")) {
            val p = card(frame, agent)
            assertFalse(p.terminalReview)
            assertTrue(p.openCodePermission)
            assertEquals(listOf("Allow once", "Always allow", "Reject"), p.choices.map { it.label })
            assertEquals("Permission required", p.question)
            assertTrue(p.escRejects)
            assertTrue(p.horizontal)
            assertTrue(p.shownParts().command.orEmpty().contains("echo hi > ~/scratch/x"))
        }
    }

    @Test fun theOpeningFocusIsReadFromTheButtonColors() {
        assertEquals("1", card(focused(frame, "Allow once")).selected)
        assertEquals("3", card(focused(frame, "Reject")).selected)
        // Plain text has no focus: the answer path then reads a styled viewport before any key.
        assertNull(card(frame).selected)
    }

    @Test fun theOneFourNineLayoutStillParses() {
        val old = javaClass.getResource("/screens/opencode/derived-1.4.9/0701-permission-prompt-all-tools.txt")!!.readText()
        val p = card(old)
        assertFalse(p.terminalReview)
        assertEquals(listOf("Allow once", "Always allow", "Reject"), p.choices.map { it.label })
    }

    @Test fun theExpandedDialogsMinimizeHintParses() {
        val p = card(frame.replace("ctrl+f fullscreen", "ctrl+f   minimize"))
        assertFalse(p.terminalReview)
        assertEquals(listOf("Allow once", "Always allow", "Reject"), p.choices.map { it.label })
    }

    @Test fun aRebindOrNoFullscreenHintStillParses() {
        assertEquals(listOf("Allow once", "Always allow", "Reject"), card(frame.replace("ctrl+f fullscreen", "alt+z fullscreen")).choices.map { it.label })
        assertEquals(listOf("Allow once", "Always allow", "Reject"), card(frame.replace("ctrl+f fullscreen", "                 ")).choices.map { it.label })
    }

    @Test fun aStatusRowUnderTheDialogIsNotItsFooter() {
        val p = card(frame.trimEnd() + "\n  ~/fixture                         1.2K (1%)  ctrl+p commands    ~/fixture\n")
        assertFalse(p.terminalReview)
        assertEquals(listOf("Allow once", "Always allow", "Reject"), p.choices.map { it.label })
    }

    @Test fun narrowPanesStackTheHintsUnderTheButtons() {
        val narrow = frame.lines().flatMap { row ->
            if ("Allow once" !in row) listOf(row)
            else listOf("  ┃   Allow once   Always allow   Reject", "  ┃", "  ┃  ctrl+f fullscreen  ⇆ select  enter confirm")
        }.joinToString("\n")
        val p = card(narrow)
        assertFalse(p.terminalReview)
        assertEquals(listOf("Allow once", "Always allow", "Reject"), p.choices.map { it.label })
    }

    @Test fun aWrappedCommandRowStartingWithARedirectIsNotAnInputRow() {
        val wrapped = frame.replace("  ┃  \$ echo hi > ~/scratch/x",
            "  ┃  \$ ( setsid nohup /home/user/bin/fixture-job.py run --out /home/user/scratch/fixture-out\n" +
                "  ┃  >/dev/null 2>&1 & )")
        val p = card(wrapped)
        assertFalse(p.terminalReview)
        assertEquals(listOf("Allow once", "Always allow", "Reject"), p.choices.map { it.label })
        assertTrue(p.approvalIdentity.orEmpty().contains(">/dev/null 2>&1 & )"))
    }

    @Test fun aClippedCommandIsStillTerminalOnly() {
        val p = card(frame.replace("echo hi > ~/scratch/x", "echo hi > ~/scra…"))
        assertTrue(p.terminalReview)
        assertTrue(p.choices.isEmpty())
    }

    @Test fun aPromptRowBelowTheDialogStillMeansTheAgentWentOn() {
        // Outside the command block an input row still means the dialog above is stale.
        val stale = frame.replace("  ┃  \$ echo hi > ~/scratch/x\n  ┃\n", "  ┃  \$ echo hi > ~/scratch/x\n  ┃\n  ┃ > next message\n")
        assertTrue(detectPrompt(stale, blocked = false, agent = "opencode")?.openCodePermission != true)
    }

    @Test fun theEmptyComposerIsStillNoCard() {
        val empty = javaClass.getResource("/screens/opencode/live-2026-11-20-second/empty.ansi")!!.readText()
        assertNull(detectPrompt(empty, blocked = false, agent = "opencode-work"))
    }
}
