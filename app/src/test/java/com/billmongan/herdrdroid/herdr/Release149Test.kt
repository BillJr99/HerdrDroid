package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.9: Grok's command approval as Bill saw it (Bill: "Grok is stuck on another fake question box"). */
class Release149Test {
    private val screen = javaClass.getResource("/screens/grok/captured-2026-11-15b/scan-approval.txt")!!.readText()

    @Test fun grokScanApprovalParsesWithItsKeys() {
        for (blocked in listOf(true, false)) for (agent in listOf("grok", null)) {
            val p = detectPrompt(screen, blocked, agent)
            assertNotNull("blocked=$blocked agent=$agent", p)
            assertEquals(listOf("Yes, and don't ask again for anything (always-approve mode)", "Yes, proceed", "No, reject (type to add feedback)", "Never allow: python3 -"),
                p!!.choices.map { it.label })
            assertEquals("2", p.selected)
            assertEquals(listOf(listOf("up", "enter"), listOf("enter"), listOf("down", "enter"), listOf("down", "down", "enter")), p.choices.map { menuKeys(p, it) })
            assertEquals("Scan recordings and compare published player", p.parts?.heading ?: p.shownParts().heading ?: p.question)
        }
    }

    @Test fun cautionOnlyOnTheStandingGrant() {
        val p = detectPrompt(screen, true, "grok")!!
        assertEquals(listOf(true, false, false, false), p.choices.map { grantsStandingPermission(it.label) })
    }

    @Test fun notAnErrorAndShowsTheCommand() {
        assertNull(detectAgentError(screen))
        val p = detectPrompt(screen, true, "grok")!!
        assertTrue(p.shownParts().command.orEmpty().startsWith("python3 - << 'PY'"))
        assertFalse(p.shownParts().blocks.any { it.text.contains("narrow scope") })
    }

    /** The same card after it was answered: Grok went back to work and printed under it. */
    private val afterAnswer = screen.trimEnd().lines().dropLast(1).joinToString("\n") + "\n" + (1..12).joinToString("\n") { "     ● Ran step $it of the scan: compared recording $it with the published player" } +
        "\n\n   ◆ Comparing recordings… 0.4s                    15m01s ⇣84.1k [↓][stop]\n  ╭" + "─".repeat(60) + "╮\n  │ ❯" + " ".repeat(57) + "│\n  ╰" + "─".repeat(44) + " Grok 4.7 (high) ─╯\n  Shift+Tab:mode  │  Ctrl+.:shortcuts"

    @Test fun anAnsweredCardLeftInTheScrollbackIsNotAQuestion() {
        for (blocked in listOf(false, true)) {
            val p = detectPrompt(afterAnswer, blocked, "grok")
            assertTrue("blocked=$blocked: ${p?.choices?.map { it.label }}", p == null || p.choices.isEmpty())
        }
    }

    @Test fun everyCapturedCardStillHasRoomForItsFooter() {
        // The cards in the screen library keep their options (ScreenLibraryTest checks each); this one too.
        assertEquals(4, detectPrompt(screen, false, "grok")!!.choices.size)
    }
}
