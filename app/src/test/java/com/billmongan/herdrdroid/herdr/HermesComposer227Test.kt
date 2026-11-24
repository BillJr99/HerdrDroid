package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.7 (Bill, 2026-11-23: "Hermes pane failed to render a question and also doesn't show the Blue
 * Dot and busy when the agent is actually working instead it shows under idle"). herdr called the
 * minipc2 Hermes pane done through a whole turn and through its approval, and Hermes's modern
 * layout puts its composer rules, session title and footer under the approval panel, where the
 * parsers took them for newer output. Screens: screens/hermes/live-2026-11-23-turn (the capture,
 * private text replaced) and screens/hermes/derived-2.2.7 (from Hermes's own program text).
 */
class HermesComposer227Test {
    private fun res(path: String) = checkNotNull(javaClass.getResource("/screens/hermes/$path")).readText().replace("\r", "").trimEnd()
    private val finished get() = res("live-2026-11-23-turn/visible.txt")
    private val working get() = res("live-2026-11-23-turn/working.txt")
    private val approval get() = res("derived-2.2.7/approval-modern.txt")
    private val hidden get() = res("derived-2.2.7/approval-modern-hidden.txt")
    private val confirm get() = res("derived-2.2.7/slash-confirm-modern.txt")
    private fun pane(status: Status) = Pane("w2:p9", "w2:t6", "w2", "hermes", status, "", "/fixture", terminalId = "t1")

    @Test fun theFinishedTurnIsNeitherBusyNorWaiting() {
        val c = requireNotNull(hermesComposer(finished))
        assertNull(c.icon)
        assertFalse(c.spinning)
        assertFalse(c.stopHint)
        assertFalse(c.busy)
        assertFalse(c.waiting)
        // The old approval's remnant in the transcript ("⚠ Approval: … → allowed once") is not a question.
        assertNull(detectPrompt(finished, blocked = false, agent = "hermes"))
    }

    @Test fun aRunningTurnIsBusyByItsSpinnerIconAndFooter() {
        for (screen in listOf(working, res("derived-2.2.7/working-modern.txt"))) {
            val c = requireNotNull(hermesComposer(screen))
            assertEquals("☤", c.icon)
            assertTrue(c.spinning)
            assertTrue(c.stopHint)
            assertTrue(hermesScreenWorking(screen))
            assertFalse(hermesScreenWaiting(screen))
            assertNull(detectPrompt(screen, blocked = false, agent = "hermes"))
        }
        // Any one of the three is enough: a narrower footer drops the key hints.
        val noHints = res("derived-2.2.7/working-modern-no-hints.txt")
        assertFalse(requireNotNull(hermesComposer(noHints)).stopHint)
        assertTrue(hermesScreenWorking(noHints))
        val iconOnly = working.lines().map { if (it.startsWith("── ⠧")) "─".repeat(it.length) else it }
            .map { it.replace("ctrl+c stop", "") }.joinToString("\n")
        assertTrue(hermesScreenWorking(iconOnly))
        // The idle composer with its example placeholder is not busy.
        val idle = working.replace("☤ ❯ type to redirect this turn · Tab queues it for after", "❯ Research this topic and write me a brief")
            .replace("── ⠧ Working… · 58s ─", "──────────────────────").replace("enter interrupt · tab queue · ctrl+c stop", "/ commands · @ files · ! shell")
        assertFalse(hermesScreenWorking(idle))
    }

    @Test fun aClippedComposerOrTheAnswerLabelIsNeverReadAsBusy() {
        // Without the composer's bottom rule and footer the chrome is not trusted; herdr's status stands.
        val clipped = working.lines().dropLast(2).joinToString("\n")
        assertNull(hermesComposer(clipped))
        assertFalse(hermesScreenWorking(clipped))
        // The transcript's "☤ Hermes" label between two separators is not a state icon.
        val noComposer = finished.lines().dropLast(4).joinToString("\n")
        assertFalse(hermesScreenWorking(noComposer))
        assertFalse(hermesScreenWaiting(noComposer))
        assertEquals(pane(Status.DONE), markHermesScreen(pane(Status.DONE), noComposer))
    }

    @Test fun theModernApprovalIsACardWithItsFourAnswersWhatHerdrSays() {
        assertTrue(hermesScreenWaiting(approval))
        assertFalse(hermesScreenWorking(approval))
        for (blocked in listOf(false, true)) {
            val card = requireNotNull(detectPrompt(approval, blocked = blocked, agent = "hermes"))
            assertTrue(card.question, card.question.contains("Dangerous Command"))
            assertFalse(card.terminalReview)
            assertEquals(listOf("Allow once", "Allow for this session", "Add to permanent allowlist", "Deny"), card.choices.map { it.label })
            assertEquals("1", card.selected)
            // ↑/↓ to select, Enter to confirm: Deny is three rows down from the highlight.
            assertEquals(listOf("down", "down", "down", "enter"), menuKeys(card, card.choices.last()))
            assertEquals(listOf("enter"), menuKeys(card, card.choices.first()))
        }
        // The same card as the panel alone (the 1.4.9 shape), so a fresh read in either form agrees.
        val panel = res("derived-1.4.9/1001-dangerous-command-approval.txt")
        assertEquals(detectPrompt(panel, true, "hermes")?.choices?.map { it.label },
            detectPrompt(approval, false, "hermes")?.choices?.map { it.label })
    }

    @Test fun anAnswerGoesOnlyWhileTheFreshScreenShowsTheSameApproval() {
        val shown = requireNotNull(detectPrompt(approval, blocked = false, agent = "hermes"))
        assertTrue(cardAnswerStillFits(shown, detectPrompt(approval, blocked = false, agent = "hermes")))
        // The highlight moved at the computer: still the same question.
        val moved = approval.replace("│ ❯ 1. Allow once", "│   1. Allow once").replace("│   2. Allow for this session", "│ ❯ 2. Allow for this session")
        assertTrue(cardAnswerStillFits(shown, detectPrompt(moved, blocked = false, agent = "hermes")))
        // Another command, the approval answered (the turn running on), or the turn finished: no key.
        val other = approval.replace("check.py --live", "check.py --wipe")
        assertFalse(cardAnswerStillFits(shown, detectPrompt(other, blocked = false, agent = "hermes")))
        assertFalse(cardAnswerStillFits(shown, detectPrompt(working, blocked = false, agent = "hermes")))
        assertFalse(cardAnswerStillFits(shown, detectPrompt(finished, blocked = false, agent = "hermes")))
    }

    @Test fun aTruncatedModernApprovalAsksForTheTerminal() {
        // The live 2026-11-23 case: a heredoc too long for the panel, so Hermes hides its middle.
        val card = requireNotNull(detectPrompt(hidden, blocked = false, agent = "hermes"))
        assertTrue(card.terminalReview)
        assertTrue(card.choices.isEmpty())
        assertEquals("Review the full command", card.question)
        assertEquals("python3 -B - <<'PY'\nimport pathlib,subprocess,sys\nroot=pathlib.Path.home()/'.demo'", card.shownParts().command)
        assertNull(menuKeys(card, Choice("Allow once", "1", false)))
        assertFalse(cardAnswerStillFits(card, card))
    }

    @Test fun theSlashConfirmPanelIsACardToo() {
        val card = requireNotNull(detectPrompt(confirm, blocked = false, agent = "hermes"))
        assertEquals(3, card.choices.size)
        assertTrue(card.choices.first().label.startsWith("Approve Once"))
    }

    @Test fun theListShowsHermesByItsOwnComposerAndForgetsItOnTheNextRead() {
        for (s in listOf(Status.DONE, Status.IDLE, Status.WORKING, Status.UNKNOWN)) assertEquals(1, screenAskable(listOf(pane(s))).size)
        assertTrue(screenAskable(listOf(pane(Status.NEEDS_YOU))).isEmpty())

        val asked = markScreenNeedsYou(listOf(pane(Status.DONE)), mapOf("w2:p9" to approval)).single()
        assertEquals(Status.NEEDS_YOU, asked.status)
        assertEquals(Status.DONE, asked.sendStatus)
        val hiddenAsk = markScreenNeedsYou(listOf(pane(Status.IDLE)), mapOf("w2:p9" to hidden)).single()
        assertEquals(Status.NEEDS_YOU, hiddenAsk.status)

        val busy = markScreenNeedsYou(listOf(pane(Status.DONE)), mapOf("w2:p9" to working)).single()
        assertEquals(Status.WORKING, busy.status)
        assertNull(busy.screenNeedsYouFrom)
        assertEquals(pane(Status.WORKING), markScreenNeedsYou(listOf(pane(Status.WORKING)), mapOf("w2:p9" to working)).single())

        // Each list read starts from herdr's own status: the turn ends, the pane is done again.
        assertEquals(pane(Status.DONE), markScreenNeedsYou(listOf(pane(Status.DONE)), mapOf("w2:p9" to finished)).single())
        assertEquals(pane(Status.IDLE), markScreenNeedsYou(listOf(pane(Status.IDLE)), emptyMap()).single())
        // A pane herdr already calls blocked is left as it is.
        assertEquals(pane(Status.NEEDS_YOU), markScreenNeedsYou(listOf(pane(Status.NEEDS_YOU)), mapOf("w2:p9" to working)).single())
    }

    @Test fun theScreenWaitWakesOnHermesPromptsOnly() {
        val wake = Regex(requireNotNull(screenWakeRegex("hermes")))
        assertTrue(wake.containsMatchIn(approval))
        assertTrue(wake.containsMatchIn(hidden))
        assertTrue(wake.containsMatchIn(confirm))
        assertFalse(wake.containsMatchIn(finished))
        assertFalse(wake.containsMatchIn(working))
        val watched = screenWatchPanes(listOf(pane(Status.DONE)), mapOf("w2:p9" to Status.DONE))
        assertNotNull(watched.singleOrNull())
    }
}
