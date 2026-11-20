package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.1.1 (Bill: "Claude might have had a question in chat that was not a card but I couldn't see it in
 * terminal view"). Claude Code 2.1.329 moves its session to the background when Left is pressed on an
 * empty composer ("← for agents"); the screen then shows its sessions list, and a question the session
 * asks waits there with no dialog on screen. The screens are real captures from Claude Code 2.1.329
 * (fullscreen, 134x47) rendered against a mock API with neutral question text.
 */
class ClaudeBackgroundTest {
    private fun screen(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("screens/claude/captured-2.1.329/$name.txt")) { name }.readText()

    @Test fun theSessionsListWithAQuestionWaitingIsACard() {
        val p = detectPrompt(screen("background-awaiting-input"), blocked = false, agent = "claude")
        assertNotNull(p)
        p!!
        assertTrue(p.claudeBackground)
        assertTrue(p.choices.isEmpty())
        assertTrue(p.question, p.question.contains("waiting for your input"))
        // Only the stable lines: the hint and the count, never the row with its running timer.
        assertEquals(listOf(
            "Your session moved to the background · enter opens it · esc returns to it · ctrl+c twice exits and it keeps running",
            "1 awaiting input",
        ), p.region)
    }

    @Test fun aLaterReadWithAnotherTimerStillFitsTheCard() {
        val text = screen("background-awaiting-input")
        val shown = detectPrompt(text, blocked = false, agent = "claude")!!.forCard()
        val later = detectPrompt(text.replace("Done.", "Done again.").replace("  2s", " 14s"), blocked = false, agent = "claude")
        assertTrue(cardAnswerStillFits(shown, later))
    }

    @Test fun aChangedCountDoesNotFitTheCard() {
        val text = screen("background-awaiting-input")
        val shown = detectPrompt(text, blocked = false, agent = "claude")!!.forCard()
        val later = detectPrompt(text.replace("1 awaiting input", "2 awaiting input"), blocked = false, agent = "claude")
        assertFalse(cardAnswerStillFits(shown, later))
    }

    @Test fun theSessionsListWithNothingWaitingStillStopsSends() {
        val text = screen("background-awaiting-input").replace("1 awaiting input", "")
        val p = detectPrompt(text, blocked = false, agent = "claude")
        assertNotNull(p)
        assertTrue(p!!.claudeBackground)
        assertTrue(p.question, p.question.contains("Return to it before sending anything"))
        assertEquals(1, p.region?.size)
    }

    @Test fun aDialogBelowTheHintIsTheSessionBackOnScreen() {
        val text = screen("background-awaiting-input").lines().take(8).joinToString("\n") + "\n" + screen("single-question")
        val p = detectPrompt(text, blocked = false, agent = "claude")
        assertNotNull(p)
        assertFalse(p!!.claudeBackground)
        assertEquals("Cancel and stop the lane (Recommended)", p.choices.first().label)
    }

    @Test fun onlyClaudeReadsTheSessionsList() {
        val p = detectPrompt(screen("background-awaiting-input"), blocked = false, agent = "codex")
        assertTrue(p == null || !p.claudeBackground)
    }

    @Test fun claude2_1_329DialogsStillParse() {
        val first = detectPrompt(screen("multi-question-first"), blocked = false, agent = "claude")!!
        assertEquals("1", first.selected)
        assertEquals(listOf("Backup", "Restart", "Follow-up", "Checks", "Submit"), first.shownParts().tabs.map { it.title })
        assertTrue(first.shownParts().heading.orEmpty().startsWith("The nightly report job is blocked"))
        val moved = detectPrompt(screen("multi-question-second-moved"), blocked = false, agent = "claude")!!
        assertEquals("2", moved.selected)
        val single = detectPrompt(screen("single-question"), blocked = false, agent = "claude")!!
        assertEquals(5, single.choices.size)
        assertFalse(single.claudeBackground)
    }

    @Test fun theDraftCueIsRecognised() {
        val text = "❯ a note I am still typing\n" +
            "  Claude has a question for you. It shows once you send or clear what you're typing.\n" +
            "  ⏵⏵ auto mode on (shift+tab to cycle)"
        assertTrue(claudeQuestionBehindDraft(text))
        assertTrue(claudeQuestionBehindDraft("\u001B[2m" + text + "\u001B[0m"))
        assertFalse(claudeQuestionBehindDraft(screen("single-question")))
        assertNull(claudeBackgroundList(screen("single-question")))
    }
}
