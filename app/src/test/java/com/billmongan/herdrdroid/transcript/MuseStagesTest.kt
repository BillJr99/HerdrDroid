package com.billmongan.herdrdroid.transcript

import com.billmongan.herdrdroid.herdr.detectPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 1.4.8 (Bill: "sometimes there appear to be more than one question and I answer the first one and it
 * says the second one was answered outside of the terminal"): one Muse card is one question through
 * redraws, and a real next stage is a new one.
 */
class MuseStagesTest {
    private val rule = "─".repeat(120)

    private fun card(command: String, stage: String?, withHeading: Boolean = true) = buildString {
        appendLine("◈ Calling tools (1m 2s · esc to interrupt)")
        appendLine()
        if (withHeading) { appendLine(rule); appendLine("Would you like to run the following command?"); appendLine() }
        appendLine("  $ $command")
        stage?.let { appendLine("  Stage $it") }
        appendLine()
        appendLine("⟩ 1. Allow this stage once (y)")
        appendLine("  2. Abort the entire command (esc)")
        appendLine("Change approval defaults in /permissions")
        appendLine(rule)
        appendLine("  muse-wonder-full-1.0 · max · ~/agents/muse · Launch overrides")
    }

    /** The judge's "Reviewing approval request" screen, between two draws of the same card. */
    private val reviewing = "◈ Reviewing approval request (2s)\n$rule\n❯\n$rule\n  muse-wonder-full-1.0 · max · ~/agents/muse"

    private fun prompt(screen: String) = detectPrompt(screen, false, "muse").also { assertNotNull(it) }!!

    @Test fun aRedrawWithoutItsHeadingIsTheSameCard() {
        val a = prompt(card("git status", "1/1"))
        val b = prompt(card("git status", "1/1", withHeading = false))
        assertEquals(QuestionLog.signature(a), QuestionLog.signature(b))
        var log = QuestionLog.seen(emptyList(), a, "muse", null, 1_000)
        log = QuestionLog.seen(log, b, "muse", null, 2_000)
        assertEquals(1, log.size)
        assertEquals(QState.OPEN, log.single().state)
    }

    @Test fun anAnsweredCardRedrawnAfterTheJudgeIsNotASecondQuestion() {
        val a = prompt(card("git status", "1/1"))
        assertEquals(null, detectPrompt(reviewing, false, "muse")?.takeIf { it.choices.isNotEmpty() })
        var log = QuestionLog.seen(emptyList(), a, "muse", null, 1_000)
        log = QuestionLog.answered(log, a, "Allow this stage once (y)")
        // The card goes while the judge reviews, then is drawn again, then goes for good.
        log = QuestionLog.gone(log, working = true, now = 3_000)
        log = QuestionLog.seen(log, a, "muse", null, 5_000)
        log = QuestionLog.gone(log, working = true, now = 7_000)
        assertEquals(1, log.size)
        assertEquals(QState.ANSWERED, log.single().state)
    }

    @Test fun theNextStageIsANewQuestion() {
        val one = prompt(card("git add -A && git commit -m wip", "1/2"))
        val two = prompt(card("git add -A && git commit -m wip", "2/2"))
        assertNotEquals(QuestionLog.signature(one), QuestionLog.signature(two))
        var log = QuestionLog.seen(emptyList(), one, "muse", null, 1_000)
        log = QuestionLog.answered(log, one, "Allow this stage once (y)")
        log = QuestionLog.gone(log, working = true, now = 2_000)
        log = QuestionLog.seen(log, two, "muse", null, 3_000)
        assertEquals(listOf(QState.ANSWERED, QState.OPEN), log.map { it.state })
    }

    @Test fun anotherCommandIsANewQuestion() {
        val a = prompt(card("git status", null))
        val b = prompt(card("git diff --stat", null))
        assertNotEquals(QuestionLog.signature(a), QuestionLog.signature(b))
    }
}
