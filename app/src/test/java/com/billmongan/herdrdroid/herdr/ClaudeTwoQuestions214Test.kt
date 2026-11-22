package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.notify.NotifyAction
import com.billmongan.herdrdroid.notify.alertIdentity
import com.billmongan.herdrdroid.notify.alertOptionsSig
import com.billmongan.herdrdroid.notify.alertPlan
import com.billmongan.herdrdroid.notify.notificationAnswerStillFits
import com.billmongan.herdrdroid.transcript.QState
import com.billmongan.herdrdroid.transcript.QuestionLog
import com.billmongan.herdrdroid.transcript.ToolItem
import com.billmongan.herdrdroid.transcript.ToolState
import org.junit.Assert.*
import org.junit.Test

/**
 * 2.1.4, Bill: "a Claude pane had asked me a question that didn't get rendered in chat". The claude
 * pane's AskUserQuestion of 18:18 UTC (two questions, "Allow rule" and "Host key", with option
 * descriptions), drawn by Claude Code 2.1.329 against a mock API with neutral text
 * (screens/claude/captured-2.1.329-two-questions).
 */
class ClaudeTwoQuestions214Test {
    private fun screen(name: String) = checkNotNull(javaClass.getResource("/screens/claude/captured-2.1.329-two-questions/$name")).readText()
    private fun card(name: String, blocked: Boolean = false) = checkNotNull(detectPrompt(screen(name), blocked, "claude")) { name }

    @Test fun bothQuestionsAndTheReviewStepAreCardsWhateverHerdrSays() {
        for (blocked in listOf(false, true)) {
            val q1 = card("q1.txt", blocked)
            assertEquals(listOf("Exact path (Recommended)", "Wildcard as written", "Don't add it", "Type something.", "Chat about this"), q1.choices.map { it.label })
            assertTrue(q1.question, q1.question.contains("Which rule should I add?"))
            val q2 = card("q2.txt", blocked)
            assertEquals(listOf("You connect first (Recommended)", "Trust on first use", "Type something.", "Chat about this"), q2.choices.map { it.label })
            assertTrue(q2.question.contains("How should I handle the first connection?"))
            assertEquals(listOf("Submit answers", "Cancel"), card("review.txt", blocked).choices.map { it.label })
        }
        // The styled reads give the same cards.
        assertEquals(card("q1.txt").choices, card("q1.ansi").choices)
        assertEquals(card("q2.txt").choices, card("q2.ansi").choices)
        assertNull(detectPrompt(screen("after.txt"), false, "claude"))
    }

    @Test fun theCardNamesEveryStepAndTheOptionNotes() {
        val parts = card("q1.txt").shownParts()
        assertEquals(listOf("Allow rule", "Host key", "Submit"), parts.tabs.map { it.title })
        assertTrue(parts.tabs.first().current)
        assertTrue(showsQuestionDialog(screen("q1.txt")))
    }

    @Test fun theMergedTypedOptionAndTheAlert() {
        val q1 = card("q1.txt")
        val shown = shownChoices(q1.answerRows())
        assertEquals(listOf("Exact path (Recommended)", "Wildcard as written", "Don't add it", TYPED_ANSWER_LABEL),
            shown.map { shownChoiceLabel(q1.answerRows(), it) })
        val plan = alertPlan(q1)
        assertTrue(plan.buttons.none { it.choice?.label == "Chat about this" })
        assertEquals(listOf("Exact path", "Wildcard as written", "Esc"), plan.buttons.map { it.title.take(if (it.title.startsWith("Exact")) 10 else 99) })
        assertTrue(plan.inApp.toString(), plan.inApp.containsAll(listOf("Don't add it", "Type your own answer")))
    }

    @Test fun answeringOneQuestionAtATimeThenSubmitUsesTheLiveHighlight() {
        // Question 1, highlight on its first row: Enter picks "Exact path".
        val q1 = card("q1.txt")
        assertEquals(listOf("enter"), menuKeys(q1, q1.choices[0]))
        // The same question with the highlight moved down one: Up, then Enter.
        val moved = card("q1-down.txt")
        assertEquals(listOf("up", "enter"), menuKeys(moved, moved.choices[0]))
        // Question 2: "Trust on first use" is one down from the highlight.
        val q2 = card("q2.txt")
        assertEquals(listOf("down", "enter"), menuKeys(q2, q2.choices[1]))
        val q2moved = card("q2-down.txt")
        assertEquals(listOf("up", "enter"), menuKeys(q2moved, q2moved.choices[0]))
        // The review step: Submit answers is highlighted.
        val review = card("review.txt")
        assertEquals(listOf("enter"), menuKeys(review, review.choices.first { it.label == "Submit answers" }))
    }

    @Test fun aCardForOneQuestionNeverAnswersTheNext() {
        val q1 = card("q1.txt")
        val q2 = card("q2.txt")
        assertFalse(cardAnswerStillFits(q1, q2))
        assertFalse(cardAnswerStillFits(q2, card("review.txt")))
        assertTrue(cardAnswerStillFits(q1, card("q1.txt")))
        val c = q1.choices[0]
        val tap = NotifyAction("s", "w1:p1", c.keys, c.pressEnter, q1.question, label = c.label, agent = "claude",
            options = alertOptionsSig(q1.choices), identity = alertIdentity(q1))
        assertTrue(notificationAnswerStillFits(q1, tap))
        assertFalse(notificationAnswerStillFits(q2, tap))
    }

    @Test fun theLoggedCallShowsBothQuestionsInChat() {
        val input = """{"questions":[{"question":"The requested rule allows `requests_read_files.sh` in ANY project worktree (`worktrees/*`). Which rule should I add?","header":"Allow rule","multiSelect":false,"options":[{"label":"Exact path (Recommended)","description":"Allow only that file."},{"label":"Wildcard as written","description":"Add `Bash(bash /home/user/x/*/y.sh)` exactly as requested."},{"label":"Don't add it","description":"Leave the settings unchanged."}]},{"question":"This machine has never connected to hostb (host-b.example.invalid), so SSH will ask to trust a new host key. How should I handle the first connection?","header":"Host key","multiSelect":false,"options":[{"label":"You connect first (Recommended)","description":"You run `! ssh user@host-b.example.invalid hostname` once."},{"label":"Trust on first use","description":"I connect once with StrictHostKeyChecking=accept-new."}]}]}"""
        val running = ToolItem(key = "t1", id = "toolu_01", name = "AskUserQuestion", title = "AskUserQuestion", kind = "other", input = input,
            diff = null, patch = null, out = null, state = ToolState.RUNNING)
        val q = checkNotNull(QuestionLog.fromTool(running))
        assertEquals(QState.OPEN, q.state)
        assertEquals(listOf("Allow rule", "Host key"), q.parts.map { it.title })
        assertEquals(listOf("Exact path (Recommended)", "Wildcard as written", "Don't add it"), q.parts[0].options)
        assertEquals(listOf("You connect first (Recommended)", "Trust on first use"), q.parts[1].options)
        val shown = QuestionLog.withQuestions(listOf(running), emptyList())
        assertTrue(shown.single() is com.billmongan.herdrdroid.transcript.QuestionItem)
    }
}
