package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeProbeTransitions229Test {
    private fun screen(name: String): String = checkNotNull(
        javaClass.getResource("/screens/claude/captured-2.1.332-probe-transitions/$name.txt")
    ).readText()

    private fun card(name: String) = detectPrompt(screen(name), false, "claude", "2.1.332")

    @Test fun nativeHelperAboveApprovalRetainsCommandAndChoices() {
        val approval = checkNotNull(card("helper-approval"))
        assertEquals("printf 'fixture-helper\\n'", approval.shownParts().command)
        assertEquals(listOf("Yes", "No"), approval.choices.map { it.label })
        assertNull(card("helper-cancelled"))
    }

    @Test fun nativeHelperRowsBelowAComposerDoNotCreateACard() {
        assertNull(card("helper-active"))
        assertNull(card("helper-done"))
    }

    @Test fun nativeModelDismissalsWithdrawTheOldActions() {
        assertNull(card("fast-cancelled"))
        val restored = card("model-chooser-restored")
        assertNotNull(restored)
        assertEquals("Select model", restored?.question)
        assertTrue(restored?.terminalReview == true && restored.choices.isEmpty())
        val parsed = checkNotNull(restored)
        assertTrue(parsed.region.orEmpty().any { it.trimStart().startsWith("❯ 2.") })
        assertTrue(parsed.shownParts().blocks.any { it.text.startsWith("2.  Opus") })
        assertTrue(parsed.shownParts().blocks.none { it.text.startsWith("❯") })
        assertNull(card("model-cancelled"))
    }
}
