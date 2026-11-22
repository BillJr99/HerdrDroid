package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.0 (Bill: "let me click on the pill buttons for multi question menus to go back to prior
 * questions or forth to other questions to revise before submitting"). The screens are the real
 * Claude Code 2.1.329 captures of Leftovers211Test (Right once and twice without answering, Left
 * back, an answered question, Left back to it, the Submit tab), read as the card read gets them:
 * styles stripped, the current-tab mark kept.
 */
class QuestionPills220Test {
    private fun resource(path: String) = checkNotNull(javaClass.getResource(path)) { path }.readText()
    private fun live(name: String) = stripAnsi(resource("/screens/claude/captured-2.1.329/styled/$name.ansi"))
    private val esc = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    /** The same screen without styles: nothing marks the current tab. */
    private fun plain(name: String) = esc.replace(resource("/screens/claude/captured-2.1.329/styled/$name.ansi"), "")
    private fun card(text: String) = checkNotNull(detectPrompt(text, blocked = true, agent = "claude")) { "no card" }
    private val titles = listOf("Backup", "Restart", "Follow-up", "Submit")

    @Test fun claudeKeysAreTheFewestArrowPresses() {
        assertEquals(listOf("right"), QuestionPills.claudeKeys(0, 1))
        assertEquals(listOf("right", "right", "right"), QuestionPills.claudeKeys(0, 3))
        assertEquals(listOf("left", "left"), QuestionPills.claudeKeys(2, 0))
        assertEquals(emptyList<String>(), QuestionPills.claudeKeys(1, 1))
    }

    @Test fun onlyClaudesAnswerableMultiQuestionCardIsTappable() {
        assertTrue(QuestionPills.tappable("claude", card(live("mq-first"))))
        assertEquals(titles, card(live("mq-first")).shownParts().tabs.map { it.title })
        // One question: no pills.
        val single = card(resource("/screens/claude/captured-2.1.329/single-question.txt"))
        assertFalse(QuestionPills.tappable("claude", single))
        // The same bar under another agent's name is never driven with Claude's keys.
        assertFalse(QuestionPills.tappable("codex", card(live("mq-first"))))
        // Codex's overlay ("Question 1/2", "←/→ to navigate questions") names only the question it
        // shows, so its card has no pills; Hermes's clarify batch ("Tab/Shift+Tab switch question")
        // is a Terminal-only card with none either.
        val codex = checkNotNull(detectPrompt(resource("/screens/codex/derived-1.4.9/0206-request-user-input-multi-question.txt"), true, "codex"))
        assertTrue(codex.shownParts().tabs.isEmpty())
        assertFalse(QuestionPills.tappable("codex", codex))
        val batch = hermesClarify.replace("ask 1 question", "ask 2 questions").replace("0/1", "0/2")
        val hermes = checkNotNull(detectPrompt(batch, false, "hermes"))
        assertTrue(hermes.terminalReview)
        assertFalse(QuestionPills.tappable("hermes", hermes))
    }

    @Test fun theStyledBarIsMarkedAndAPlainOneIsNot() {
        val bar = checkNotNull(QuestionPills.bar(live("mq-moved-right2")))
        assertTrue(bar.marked)
        assertEquals(2, bar.current)
        assertEquals(titles, bar.titles)
        assertFalse(checkNotNull(QuestionPills.bar(plain("mq-moved-right2"))).marked)
    }

    private fun plan(shownScreen: String, to: Int, liveScreen: String = shownScreen) =
        card(shownScreen).let { shown ->
            QuestionPills.plan("claude", shown, to, shown.shownParts().tabs[to].title, liveScreen, detectPrompt(liveScreen, true, "claude"))
        }

    @Test fun forwardBackAndToTheReviewStep() {
        assertEquals(QuestionPills.Plan.Send(listOf("right", "right"), 0, 2), plan(live("mq-first"), 2))
        assertEquals(QuestionPills.Plan.Send(listOf("left", "left"), 2, 0), plan(live("mq-moved-right2"), 0))
        // Restart (unanswered) back to Backup, which is answered: revising it.
        assertEquals(QuestionPills.Plan.Send(listOf("left"), 1, 0), plan(live("mq-answered1"), 0))
        // The Submit pill only moves to the review step.
        assertEquals(QuestionPills.Plan.Send(listOf("right", "right"), 1, 3), plan(live("mq-answered1"), 3))
        // From the review step back to a question.
        assertEquals(QuestionPills.Plan.Send(listOf("left", "left"), 3, 1), plan(live("mq-submit-tab"), 1))
        assertEquals(QuestionPills.Plan.Already, plan(live("mq-moved-right1"), 1))
    }

    @Test fun noPlanEverSendsEnterEscSpaceOrADigit() {
        val screens = listOf("mq-first", "mq-moved-right1", "mq-moved-right2", "mq-moved-back1", "mq-answered1", "mq-answered-back", "mq-submit-tab")
        for (s in screens) for (to in titles.indices) {
            val p = plan(live(s), to)
            if (p is QuestionPills.Plan.Send) assertTrue("$s -> $to: ${p.keys}", p.keys.all { it == "left" || it == "right" })
        }
    }

    @Test fun refusesWithoutTheMarkOrWhenTheDialogChanged() {
        // A plain read cannot prove which tab is current.
        val unmarked = plan(plain("mq-first"), 2)
        assertTrue(unmarked.toString(), unmarked is QuestionPills.Plan.Refuse)
        // The card was read on the first question; the live dialog has since moved on.
        assertTrue(plan(live("mq-first"), 2, live("mq-moved-right1")) is QuestionPills.Plan.Refuse)
        // A different dialog (the two-question capture) on the same pane.
        val other = resource("/screens/claude/captured-2.1.329-two-questions/q1.txt")
        assertTrue(plan(live("mq-first"), 1, other) is QuestionPills.Plan.Refuse)
        // The question is gone.
        val shown = card(live("mq-first"))
        assertTrue(QuestionPills.plan("claude", shown, 1, "Restart", null, null) is QuestionPills.Plan.Refuse)
        // A pill that is not this card's.
        assertTrue(QuestionPills.plan("claude", shown, 1, "Follow-up", live("mq-first"), shown) is QuestionPills.Plan.Refuse)
        // Claude holds the question behind a draft typed in its input box.
        val draft = live("mq-first") + "\n  Claude has a question for you. It shows once you send or clear what you're typing.\n"
        assertTrue(plan(live("mq-first"), 1, draft) is QuestionPills.Plan.Refuse)
    }

    @Test fun refusesWhileTheHighlightIsOnTheTypedAnswerRow() {
        val shown = card(live("mq-first"))
        val typed = shown.choices.indexOfFirst { isTypedAnswerChoice(it.label) && !it.label.startsWith("Chat") }
        assertTrue(typed >= 0)
        val onTyped = shown.copy(selected = shown.choices[typed].keys)
        assertTrue(QuestionPills.focusOnTypedRow(onTyped))
        assertFalse(QuestionPills.focusOnTypedRow(shown))
        assertTrue(QuestionPills.plan("claude", shown, 1, "Restart", live("mq-first"), onTyped) is QuestionPills.Plan.Refuse)
    }

    @Test fun landedChecksTheMarkTheTitlesAndTheQuestion() {
        val origin = card(live("mq-first")).shownParts().heading
        fun landed(to: Int, screen: String, known: String? = null) =
            QuestionPills.landed(titles, to, screen, detectPrompt(screen, true, "claude"), origin, known)
        assertTrue(landed(1, live("mq-moved-right1")))
        // The wrong tab.
        assertFalse(landed(1, live("mq-moved-right2")))
        // Still on the first question (not redrawn yet).
        assertFalse(landed(1, live("mq-first")))
        // A plain read proves nothing.
        assertFalse(landed(2, plain("mq-moved-right2")))
        // A known question text that does not match.
        assertFalse(landed(1, live("mq-moved-right1"), known = "Some other question?"))
        val restart = card(live("mq-moved-right1")).shownParts().heading
        assertTrue(landed(1, live("mq-moved-back1"), known = restart))
        // The review step, with its Submit answers button.
        assertTrue(landed(3, live("mq-submit-tab")))
        // A different dialog.
        assertFalse(landed(1, resource("/screens/claude/captured-2.1.329-two-questions/q2.txt")))
    }

    /** A fake pane: [screens] in read order (the last repeats); [sent] records every key batch. */
    private class Fake(vararg val screens: String) {
        var reads = 0
        val sent = mutableListOf<List<String>>()
        var current = true
        suspend fun read(): String? = screens[minOf(reads++, screens.lastIndex)]
    }

    private fun move(fake: Fake, shownScreen: String, to: Int, headings: MutableMap<Int, String> = mutableMapOf()) = runBlocking {
        val shown = card(shownScreen)
        QuestionPills.move("claude", shown, to, shown.shownParts().tabs[to].title, headings,
            read = { fake.read() }, detect = { detectPrompt(it, true, "claude") },
            stillCurrent = { fake.current }, send = { fake.sent += it })
    }

    @Test fun aTapMovesAndConfirmsBeforeRendering() {
        val fake = Fake(live("mq-first"), live("mq-first"), live("mq-moved-right2"))
        val headings = mutableMapOf<Int, String>()
        assertEquals(QuestionPills.Outcome.Moved(2, listOf("right", "right")), move(fake, live("mq-first"), 2, headings))
        assertEquals(listOf(listOf("right", "right")), fake.sent)
        // Both questions' texts are remembered for later taps on this dialog.
        assertEquals(setOf(0, 2), headings.keys)
    }

    @Test fun landingOnTheWrongTabStopsWithoutMoreKeys() {
        val fake = Fake(live("mq-first"), live("mq-moved-right2"))
        assertEquals(QuestionPills.Outcome.Missed(1, listOf("right")), move(fake, live("mq-first"), 1))
        assertEquals(1, fake.sent.size)
    }

    @Test fun aDialogThatChangedAfterTheKeysIsReportedNotChased() {
        val fake = Fake(live("mq-first"), resource("/screens/claude/captured-2.1.329-two-questions/q2.txt"))
        assertTrue(move(fake, live("mq-first"), 1) is QuestionPills.Outcome.Missed)
        assertEquals(1, fake.sent.size)
    }

    @Test fun aStaleCardSendsNothing() {
        // The card was the first question; the pane now shows another step.
        val moved = Fake(live("mq-moved-right2"))
        assertTrue(move(moved, live("mq-first"), 1) is QuestionPills.Outcome.Refused)
        assertTrue(moved.sent.isEmpty())
        // The pane, server or occupant changed between the read and the keys.
        val gone = Fake(live("mq-first")).also { it.current = false }
        assertTrue(move(gone, live("mq-first"), 1) is QuestionPills.Outcome.Refused)
        assertTrue(gone.sent.isEmpty())
        // Already there: nothing sent.
        val here = Fake(live("mq-moved-right1"))
        assertEquals(QuestionPills.Outcome.Already, move(here, live("mq-moved-right1"), 1))
        assertTrue(here.sent.isEmpty())
    }

    @Test fun returningToAQuestionIsCheckedAgainstWhatItShowed() {
        val restart = card(live("mq-moved-right1")).shownParts().heading!!
        // Seen before with other text: the landing is not trusted.
        val wrong = Fake(live("mq-moved-right2"), live("mq-moved-back1"))
        assertTrue(move(wrong, live("mq-moved-right2"), 1, mutableMapOf(1 to "Some other question?")) is QuestionPills.Outcome.Missed)
        val right = Fake(live("mq-moved-right2"), live("mq-moved-back1"))
        assertTrue(move(right, live("mq-moved-right2"), 1, mutableMapOf(1 to restart)) is QuestionPills.Outcome.Moved)
    }

    @Test fun anAnsweredQuestionStillShowsItsAnswerWhenRevisited() {
        // Left back to the answered first question (captured): its tab is ticked and its answer marked.
        val back = live("mq-answered-back")
        val tabs = card(back).shownParts().tabs
        assertTrue(tabs[0].done && tabs[0].current)
        assertTrue(back.lines().any { "Yes, back it up" in it && it.trimEnd().endsWith("✔") })
    }
}
