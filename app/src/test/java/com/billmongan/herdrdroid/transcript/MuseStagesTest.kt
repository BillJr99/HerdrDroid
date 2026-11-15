package com.billmongan.herdrdroid.transcript

import com.billmongan.herdrdroid.herdr.detectPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        assertTrue(QuestionLog.sameCard(QuestionLog.signature(a), QuestionLog.signature(b)))
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

    /** A heredoc as Muse draws it: "$ python3 - <<'PY'", then the script on "│" lines. */
    private fun script(body: List<String>, stage: String, withTop: Boolean = true) = buildString {
        if (withTop) { appendLine(rule); appendLine("Would you like to run the following command?"); appendLine(); appendLine("  $ python3 - <<'PY'") }
        body.forEach { appendLine("  │ $it") }
        appendLine("  │ PY")
        appendLine("  Stage $stage")
        appendLine("  Current argv: [\"python3\"]")
        appendLine()
        appendLine("⟩ 1. Allow this stage once (y)")
        appendLine("  2. Abort the entire command (esc)")
        appendLine("Change approval defaults in /permissions")
        appendLine(rule)
    }

    @Test fun twoScriptsAreTwoQuestions() {
        // From Grok's review: the lines under "$" are part of the command, so two scripts differ.
        val a = prompt(script(listOf("print(1)"), "1/1"))
        val b = prompt(script(listOf("print(2)"), "1/1"))
        assertNotEquals(QuestionLog.signature(a), QuestionLog.signature(b))
        assertEquals(false, QuestionLog.sameCard(QuestionLog.signature(a), QuestionLog.signature(b)))
    }

    @Test fun aReadThatMissedTheCommandIsStillTheSameCard() {
        // A read too short for a tall card has no "$" line: the same stage and options are the same card.
        val body = (1..10).map { "x$it = $it" }
        val full = QuestionLog.signature(prompt(script(body, "1/2")))
        val cut = QuestionLog.signature(prompt(script(body, "1/2", withTop = false)))
        assertTrue(QuestionLog.sameCard(full, cut))
        var log = QuestionLog.seen(emptyList(), prompt(script(body, "1/2")), "muse", null, 1_000)
        log = QuestionLog.seen(log, prompt(script(body, "1/2", withTop = false)), "muse", null, 2_000)
        assertEquals(1, log.size)
        // The next stage, read either way, is another card.
        val next = QuestionLog.signature(prompt(script(body, "2/2", withTop = false)))
        assertEquals(false, QuestionLog.sameCard(full, next))
        assertEquals(false, QuestionLog.sameCard(cut, next))
    }

    @Test fun aShortReadOfOnlyTheLastLinesIsStillTheSameCard() {
        // From Grok's review: a read that starts at the script's last line joins "Stage 1/1" onto it.
        val body = (1..10).map { "x$it = $it" } + "print(label, len(rows))"
        val full = QuestionLog.signature(prompt(script(body, "1/1")))
        val tail = script(body, "1/1").lines().dropWhile { !it.contains("print(label") }.joinToString("\n")
        assertTrue(QuestionLog.sameCard(full, QuestionLog.signature(prompt(tail))))
    }

    @Test fun aStageInsideAQuestionStays() {
        // From Grok's review: only Muse's trailing lines are cut, not a "Stage" inside a question.
        fun q(text: String) = com.billmongan.herdrdroid.herdr.Prompt(text, listOf(com.billmongan.herdrdroid.herdr.Choice("Yes", "1", false), com.billmongan.herdrdroid.herdr.Choice("No", "2", false)), "1")
        assertNotEquals(QuestionLog.signature(q("Deploy Stage 1/2 now?")), QuestionLog.signature(q("Deploy Stage 1/2 later?")))
    }

    @Test fun aShortReadOfAnotherScriptIsAnotherCard() {
        // From Grok's review: a short read of a different script at the same stage is not the same card.
        val body = (1..10).map { "x$it = $it" }
        val full = QuestionLog.signature(prompt(script(body, "1/1")))
        val other = QuestionLog.signature(prompt(script((1..10).map { "y$it = ${it * 7}" }, "1/1", withTop = false)))
        assertEquals(false, QuestionLog.sameCard(full, other))
    }

    @Test fun aWrappedLineDoesNotEndTheScript() {
        // A long line wraps with no "│" (the path list in Bill's card); the lines after it are still the script.
        fun card(last: String) = buildString {
            appendLine(rule); appendLine("Would you like to run the following command?"); appendLine()
            appendLine("  $ python3 - <<'PY'")
            appendLine("  │ for label,p in")
            appendLine("    [(\"a\",\"/home/user/a.cast\"),(\"b\",\"/home/user/b.cast\")]:")
            appendLine("  │     $last")
            appendLine("  │ PY")
            appendLine("  Stage 1/1")
            appendLine()
            appendLine("⟩ 1. Allow this stage once (y)")
            appendLine("  2. Abort the entire command (esc)")
            appendLine("Change approval defaults in /permissions")
        }
        val a = QuestionLog.signature(prompt(card("print(label)")))
        val b = QuestionLog.signature(prompt(card("print(p)")))
        assertEquals(false, QuestionLog.sameCard(a, b))
    }

    @Test fun anotherCommandIsANewQuestion() {
        val a = prompt(card("git status", null))
        val b = prompt(card("git diff --stat", null))
        assertNotEquals(QuestionLog.signature(a), QuestionLog.signature(b))
    }
}
