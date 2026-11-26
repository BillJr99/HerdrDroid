package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.notify.alertPlan
import com.billmongan.herdrdroid.voice.voiceCardKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeModelCard228Test {
    private val frame = checkNotNull(javaClass.getResource(
        "/screens/claude/captured-2.1.332-model-picker/screen.ansi"
    )).readText()

    private fun card(screen: String = frame) = checkNotNull(detectPrompt(screen, false, "claude", "2.1.332"))

    @Test fun capturedPickerShowsModelsAndSessionOnlyActions() {
        val prompt = card()
        assertTrue(prompt.claudeModelControl)
        assertTrue(prompt.guardedShortcutApproval)
        assertFalse(prompt.terminalReview)
        assertEquals("3", prompt.selected)
        assertEquals(listOf(
            "Use Default (recommended) this session", "Use Opus 5.5 this session", "Use Fable 5.2 this session",
            "Use Sonnet 5.5 this session", "Use Haiku 5.5 this session", "Lower effort", "Raise effort", "Save Fable 5.2 as default", "Cancel",
        ), prompt.choices.map { it.label })
        assertEquals(listOf("up", "s"), menuKeys(prompt, prompt.choices[1]))
        assertEquals(listOf("s"), menuKeys(prompt, prompt.choices[2]))
        assertEquals(listOf("down", "down", "s"), menuKeys(prompt, prompt.choices[4]))
        assertEquals(listOf("left"), menuKeys(prompt, prompt.choices[5]))
        assertEquals(listOf("right"), menuKeys(prompt, prompt.choices[6]))
        assertEquals(listOf("enter"), menuKeys(prompt, prompt.choices[7]))
        assertEquals(listOf("esc"), menuKeys(prompt, prompt.choices[8]))
        assertTrue(alertPlan(prompt).buttons.isEmpty())
        assertNull(voiceCardKeys(prompt, prompt.choices[1]))
    }

    @Test fun theCapturedEffortAndMoreModelsControlsUseOnlyTheirDisplayedKeys() {
        val high = card(frame.replace("◐ Medium effort (default)", "● High effort"))
        assertEquals(listOf("left"), menuKeys(high, high.choices.first { it.label == "Lower effort" }))
        val more = frame.replace("❯ 3.", "  3.").replace("▸   More models", "❯ ▸   More models")
            .replace("s to use this session only · Enter to save · Esc to cancel", "Enter to expand · Esc to cancel")
        val expanded = card(more)
        assertEquals(listOf("Expand more models", "Cancel"), expanded.choices.map { it.label })
        assertEquals(listOf("enter"), menuKeys(expanded, expanded.choices[0]))
        assertFalse(cardAnswerStillFits(card(), expanded))
        assertTrue(card(more.replace("❯ ▸", "  ▸")).choices.isEmpty())
    }

    @Test fun changedOptionsEffortOrFocusInvalidateTheShownPicker() {
        val shown = card()
        for (fresh in listOf(
            frame.replace("Haiku 5.5", "Haiku 5.6"),
            frame.replace("Medium effort", "High effort"),
            frame.replace("❯ 3.", "  3.").replace("  4.", "❯ 4."),
        )) {
            assertFalse(cardAnswerStillFits(shown, card(fresh)))
            val digest = cardGuardScreen(frame, shown, "claude")
            assertFalse(cardGuardScreenMatches(fresh, digest, "claude"))
        }
    }

    @Test fun anIncompleteOrStaleScreenCannotAuthorizePickerKeys() {
        assertNull(claudeModelCard(frame + "\n❯ A new message"))
        assertNull(claudeModelCard(frame.replace("Select model", "Here is an example")))
        for (partial in listOf(frame.substringAfter('\n'), frame.replace("❯ ", "  "), frame.replace("   4.", "   8."),
                frame.replace("Use /fast", "A different control: /fast"))) {
            val prompt = card(partial)
            assertTrue(prompt.terminalReview)
            assertTrue(prompt.choices.isEmpty())
        }
    }
}
