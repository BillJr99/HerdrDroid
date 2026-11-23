package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.notify.alertPlan
import com.billmongan.herdrdroid.notify.alertText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.2.2 (Bill: "sometimes it shows the text from the chat as the question rather than the actual
 * question itself and then it updates ... my messages actually show up as answers in the question
 * card responses"): a question card shows only once its dialog is proven and read the same twice;
 * until then a neutral card. Partial reads are built from the captured dialogs in the screen
 * library: cut off at several heights, the footer not drawn yet, and chat rows (the user's own
 * numbered message, short lines, rules, tables, "Do you want to" prose) above a dialog whose top
 * edge is not drawn yet. The chat rows all carry the word "Quokka", which no capture has, so any
 * card text with it came from the chat.
 */
class QuestionConfirm222Test {
    private fun screen(path: String) = requireNotNull(javaClass.getResource("/screens/$path")) { path }.readText()

    /** Full captures of question dialogs: path, agent, herdr's status when captured. */
    private val dialogs = listOf(
        Triple("claude/captured-2.1.329/single-question.txt", "claude", false),
        Triple("claude/captured-2.1.329/multi-question-first.txt", "claude", false),
        Triple("claude/captured-2.1.329-two-questions/q1.txt", "claude", false),
        Triple("claude/captured-2026-11-13/bash-permission.txt", "claude", true),
        Triple("codex/captured-2026-11-13/run-command.txt", "codex", false),
        Triple("grok/captured-2026-11-13/ssh-permission.txt", "grok", true),
        Triple("grok/live-2026-11-21b/no-box.txt", "grok", true),
        Triple("kiro/captured-2026-11-13/shell-approval.txt", "kiro", false),
        Triple("vibe/captured-2026-11-13/bash-permission.txt", "vibe", false),
    )

    private val chat = listOf(
        "  Quokka reply line from earlier in the chat.",
        "",
        "❯ 1. Quokka check the logs",
        "  2. Quokka restart the job",
        "",
        "  Quokka ok",
        "  Should Quokka go ahead?",
        "",
    )

    /** Chat rows that look like a dialog's top: a rule, a table, "Do you want to" prose. */
    private val edgyChat = listOf(
        "  Quokka notes from the last reply:",
        "  ┌──────────┬──────────┐",
        "  │ Quokka   │ value    │",
        "  └──────────┴──────────┘",
        "  ---",
        "  Do you want to Quokka the next step as well?",
        "",
    )

    private fun read(text: String, agent: String, blocked: Boolean): QuestionRead? =
        detectPrompt(text, blocked, agent)?.let { questionRead(text, it, agent, null, blocked) }

    /** The card shown after [n] identical reads of [text], or null while it is not shown. */
    private fun shownAfter(n: Int, text: String, agent: String, blocked: Boolean): Prompt? {
        val p = detectPrompt(text, blocked, agent) ?: return null
        val gate = QuestionConfirm()
        var v = Verdict(CardShows.NONE)
        repeat(n) { v = gate.decide("k", questionRead(text, p, agent, null, blocked)) }
        return p.takeIf { v.shows == CardShows.CARD }
    }

    private fun confirmedTwice(text: String, agent: String, blocked: Boolean): Prompt? {
        val p = detectPrompt(text, blocked, agent) ?: return null
        val gate = QuestionConfirm()
        val first = gate.offer("k", questionRead(text, p, agent, null, blocked))
        assertFalse("one read never confirms a question card", first && needsConfirmation(p))
        return p.takeIf { gate.offer("k", questionRead(text, p, agent, null, blocked)) }
    }

    private fun mentionsChat(p: Prompt): Boolean =
        (listOf(p.question) + p.choices.map { it.label } + p.shownParts().blocks.map { it.text } + p.region.orEmpty()).any { it.contains("Quokka") }

    private fun decision(p: Prompt, shows: CardShows) = CardDecision(p, Verdict(shows))

    @Test fun everyFullDialogIsConfirmedByTwoMatchingReadsAndMatchesTheCapture() {
        for ((path, agent, blocked) in dialogs) {
            val text = screen(path)
            val full = requireNotNull(detectPrompt(text, blocked, agent)) { path }
            assertTrue(path, full.choices.isNotEmpty())
            assertTrue("$path: the dialog is complete", questionRead(text, full, agent, null, blocked).complete)
            assertEquals(path, full, confirmedTwice(text, agent, blocked))
            // Shown once confirmed, exactly as parsed; before that, the neutral card.
            assertSame(path, full, shownCard(full, decision(full, CardShows.CARD), "Agent"))
            assertTrue(path, shownCard(full, null, "Agent")!!.readingQuestion)
            assertTrue(path, shownCard(full, decision(full, CardShows.READING), "Agent")!!.readingQuestion)
            assertTrue(path, shownCard(full, decision(full, CardShows.UNREAD), "Agent")!!.unreadQuestion)
        }
    }

    /**
     * Every card with options in the library is either shown by reads that keep giving it or, when
     * its top can't be proven (fragments derived from documentation start at the dialog's first row),
     * says after [UNREAD_AFTER] reads that the app couldn't read it: never stuck on "Reading it…",
     * never refused. Every real capture of a dialog is shown.
     */
    @Test fun everyLibraryCardIsShownOrSaysItCouldNotBeRead() {
        val root = javaClass.classLoader?.getResource("screens")?.let { File(it.toURI()) } ?: error("no screens")
        val broken = mutableListOf<String>()
        val unread = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".txt") }.sortedBy { it.path }.forEach { f ->
            val e = File(f.path.removeSuffix(".txt") + ".expect").takeIf { it.isFile }?.readLines()
                ?.mapNotNull { l -> l.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }?.toMap()
                ?: return@forEach
            if (e["card"] != "options") return@forEach
            val agent = f.parentFile.parentFile.name
            val blocked = e["blocked"] == "true"
            val text = f.readText()
            val p = detectPrompt(text, blocked, agent) ?: return@forEach
            val r = questionRead(text, p, agent, null, blocked)
            val rel = f.relativeTo(root).path
            if (r.refused) broken += "refused $rel"
            val gate = QuestionConfirm()
            val verdicts = (1..UNREAD_AFTER).map { gate.decide("k", r).shows }
            when {
                CardShows.CARD in verdicts.take(FALLBACK_READS) -> {}
                verdicts.last() == CardShows.UNREAD -> unread += rel
                else -> broken += "stuck $rel"
            }
            if (!f.parentFile.name.startsWith("derived") && rel in unread) broken += "real capture not shown $rel"
        }
        println("2.2.2 cards whose dialog top can't be proven (say they couldn't be read):\n" + unread.joinToString("\n"))
        assertTrue(broken.joinToString("\n"), broken.isEmpty())
    }

    @Test fun aDialogCutOffAtAnyHeightIsNeverConfirmedByTwoReads() {
        for ((path, agent, blocked) in dialogs) {
            val rows = screen(path).lines().dropLastWhile { it.isBlank() }
            val full = requireNotNull(detectPrompt(rows.joinToString("\n"), blocked, agent))
            val lastChoice = rows.indexOfLast { it.contains(full.choices.last().label.take(10)) }
            // The dialog's first footer row under its options (a hint row, or the box's bottom edge).
            val footer = (lastChoice + 1..rows.lastIndex).first { isDialogFooterRow(rows[it]) }
            // Every height from just above the footer up through the options and the question.
            for (cut in footer downTo maxOf(1, footer - 14)) {
                val text = rows.take(cut).joinToString("\n")
                val p = detectPrompt(text, true, agent) ?: continue
                if (!needsConfirmation(p)) continue
                assertTrue(shownCard(p, null, "Agent")!!.readingQuestion)
                assertFalse("$path cut at $cut: \"${p.question}\" ${p.choices.map { it.label }}", questionRead(text, p, agent, null, true).complete)
                assertNull(confirmedTwice(text, agent, true))
            }
        }
    }

    @Test fun chatRowsAboveADialogWhoseTopIsNotDrawnNeverShowAsTheQuestionOrAChoice() {
        for ((path, agent, blocked) in dialogs) {
            val rows = screen(path).lines().dropLastWhile { it.isBlank() }
            val full = requireNotNull(detectPrompt(rows.joinToString("\n"), blocked, agent))
            val first = rows.indexOfFirst { r -> full.choices.first().label.take(10).let { r.contains(it) } }
            assertTrue(path, first > 0)
            // The dialog drawn from its options down; above them still the chat (its edge, header and question not yet).
            for (above in listOf(chat, edgyChat, chat + edgyChat)) for (keep in 0..2) {
                val text = (above + rows.subList(first - keep, rows.size)).joinToString("\n")
                for (b in listOf(true, false)) {
                    val p = detectPrompt(text, b, agent) ?: continue
                    // However many identical reads: chat text is never shown.
                    val shown = shownAfter(10, text, agent, b)
                    if (shown != null) assertFalse("$path keep $keep: chat text in a shown card: \"${shown.question}\" ${shown.choices.map { it.label }}", mentionsChat(shown))
                    else if (needsConfirmation(p)) assertTrue(shownCard(p, null, "Agent")!!.readingQuestion)
                }
            }
            // The full dialog under the same chat: its own question.
            val under = (chat + edgyChat + rows).joinToString("\n")
            shownAfter(2, under, agent, blocked)?.let { assertFalse(path, mentionsChat(it)) }
        }
    }

    @Test fun chatRowsThatLookLikeAnEdgeAreNotADialogsTop() {
        // A table in the chat, a markdown rule, and "Do you want to" prose over a numbered list with a hint under it.
        for (top in listOf("  ┌──────────┬──────────┐", "  ---", "  Do you want to Quokka the next step as well?")) {
            val text = listOf("  Quokka reply.", top, "  Quokka first line", "", "❯ 1. Quokka first", "  2. Quokka second", "", " Esc to cancel · Tab to amend").joinToString("\n")
            val r = read(text, "claude", true) ?: continue
            assertFalse(top, r.topped && mentionsChat(r.prompt) && shownAfter(10, text, "claude", true) != null)
        }
    }

    /** Before 2.2.2: the user's own message, echoed with "❯" while the dialog is not drawn yet, became the card. */
    @Test fun theUsersEchoedMessageWasReadAsTheCardAndIsNowNeverShown() {
        val unnumbered = listOf(
            "  Quokka reply from earlier.", "",
            "❯ Please look at these Quokka items", "  Quokka item one", "  Quokka item two", "", "", "",
        ).joinToString("\n")
        val numbered = listOf(
            "  Should Quokka go ahead?", "",
            "❯ 1. Quokka first thing", "  2. Quokka second thing", "", "", "",
        ).joinToString("\n")
        for (text in listOf(unnumbered, numbered)) {
            val today = requireNotNull(detectPrompt(text, blocked = true, agent = "claude"))
            println("2.2.2 repro, card read today: \"${today.question}\" ${today.choices.map { c -> c.label }}")
            assertTrue("the old card took the chat", mentionsChat(today))
            val r = questionRead(text, today, "claude", null, true)
            assertTrue(r.refused)
            assertFalse(r.topped)
            // However many identical reads, never the card; in the end the card says it couldn't be read.
            val gate = QuestionConfirm()
            val verdicts = (1..12).map { gate.decide("k", r).shows }
            assertFalse(CardShows.CARD in verdicts)
            assertEquals(CardShows.UNREAD, verdicts.last())
            val shown = shownCard(today, CardDecision(today, Verdict(verdicts.last())), "Claude")!!
            assertFalse(mentionsChat(shown))
            assertTrue(shown.choices.isEmpty())
        }
    }

    @Test fun twoConsecutiveIdenticalReadsConfirmAndTheDialogStaysUpThroughItsOwnChanges() {
        val text = screen("claude/captured-2.1.329/single-question.txt")
        val p = requireNotNull(detectPrompt(text, false, "claude"))
        val read = questionRead(text, p, "claude", null, false)
        val gate = QuestionConfirm()
        assertFalse(gate.offer("a", read))
        assertTrue(gate.offer("a", read))
        assertTrue("stays confirmed while the reads keep giving it", gate.offer("a", read))
        // The highlight moving is not a new question: shown at once.
        val moved = text.replace("❯ 1. Cancel and stop", "  1. Cancel and stop").replace("  2. Cancel and redo", "❯ 2. Cancel and redo")
        val p2 = requireNotNull(detectPrompt(moved, false, "claude"))
        assertEquals("2", p2.selected)
        assertTrue(gate.offer("a", questionRead(moved, p2, "claude", null, false)))
        // No card starts over; so does another question.
        assertFalse(gate.offer("a", null))
        assertFalse(gate.offer("a", read))
        assertTrue(gate.offer("a", read))
        val bash = screen("claude/captured-2026-11-13/bash-permission.txt")
        val other = questionRead(bash, requireNotNull(detectPrompt(bash, true, "claude")), "claude", null, true)
        assertFalse(gate.offer("a", other))
        assertTrue(gate.offer("a", other))
        // Each pane is its own.
        assertFalse(gate.offer("b", read))
    }

    @Test fun aMultiQuestionDialogStaysUpThroughItsSteps() {
        val q1 = screen("claude/captured-2.1.329-two-questions/q1.txt")
        val q2 = screen("claude/captured-2.1.329-two-questions/q2.txt")
        val p1 = requireNotNull(detectPrompt(q1, false, "claude"))
        val p2 = requireNotNull(detectPrompt(q2, false, "claude"))
        assertEquals(p1.shownParts().tabs.map { it.title }, p2.shownParts().tabs.map { it.title })
        val gate = QuestionConfirm()
        assertFalse(gate.offer("a", questionRead(q1, p1, "claude", null, false)))
        assertTrue(gate.offer("a", questionRead(q1, p1, "claude", null, false)))
        // The next step (a pill tap, or an answer): the same dialog, shown at once, its pills too.
        val r2 = questionRead(q2, p2, "claude", null, false)
        assertTrue(r2.complete)
        assertTrue(gate.offer("a", r2))
        assertTrue(QuestionPills.tappable("claude", p2))
    }

    @Test fun ticksInAMultiSelectKeepTheCardUp() {
        val text = screen("claude/captured-2.1.329/single-question.txt")
        val p = requireNotNull(detectPrompt(text, false, "claude"))
        val gate = QuestionConfirm()
        val r = questionRead(text, p, "claude", null, false)
        gate.offer("a", r); assertTrue(gate.offer("a", r))
        // A tick changes an option's label from "[ ] x" to "[x] x": the identity ignores ticks.
        val ticked = p.copy(choices = p.choices.mapIndexed { i, c -> if (i == 0) c.copy(label = "[x] " + c.label) else c })
        val unticked = p.copy(choices = p.choices.mapIndexed { i, c -> if (i == 0) c.copy(label = "[ ] " + c.label) else c })
        val g2 = QuestionConfirm()
        g2.offer("a", QuestionRead(unticked, topped = true, complete = true))
        assertTrue(g2.offer("a", QuestionRead(unticked, topped = true, complete = true)))
        assertTrue(g2.offer("a", QuestionRead(ticked, topped = true, complete = true)))
    }

    @Test fun completeReadsThatKeepDifferingShowTheNewestAfterABound() {
        val base = requireNotNull(detectPrompt(screen("claude/captured-2.1.329/single-question.txt"), false, "claude"))
        val gate = QuestionConfirm()
        var last = Verdict(CardShows.NONE)
        for (n in 1..GIVE_UP_READS) {
            // A counter in the question text, changing every read.
            last = gate.decide("a", QuestionRead(base.copy(question = base.question + " ($n s)"), topped = true, complete = true))
            if (n < GIVE_UP_READS) assertEquals("read $n", CardShows.READING, last.shows)
        }
        assertEquals(CardShows.CARD, last.shows)
        assertTrue(last.gaveUp)
        // An unproven read never shows, however long: it ends as the card that couldn't be read.
        val g2 = QuestionConfirm()
        val verdicts = (1..UNREAD_AFTER).map { g2.decide("a", QuestionRead(base.copy(question = "q $it"), topped = false, complete = false)).shows }
        assertFalse(CardShows.CARD in verdicts)
        assertEquals(CardShows.UNREAD, verdicts.last())
    }

    @Test fun aDialogWithNoFooterButAProvenTopTakesFourReads() {
        val text = screen("claude/captured-2.1.329-two-questions/review.txt")
        val p = requireNotNull(detectPrompt(text, false, "claude"))
        val r = questionRead(text, p, "claude", null, false)
        assertTrue(r.topped)
        assertFalse(r.complete)
        val gate = QuestionConfirm()
        val verdicts = (1..FALLBACK_READS).map { gate.decide("a", r).shows }
        assertEquals(listOf(CardShows.READING, CardShows.READING, CardShows.READING, CardShows.CARD), verdicts)
    }

    @Test fun cardsThatNeedNoConfirmingShowAtOnce() {
        val unread = unreadQuestionCard("Claude")
        assertSame(unread, shownCard(unread, null, "Claude"))
        assertNull(shownCard(null, null, "Claude"))
        assertTrue(QuestionConfirm().offer("k", QuestionRead(unread, topped = false, complete = false)))
    }

    @Test fun theScreenAndKeysCardNeedsAProvenDialogToo() {
        val chatOnly = "  Quokka reply.\n\n  Should Quokka go ahead?\n"
        val p = detectPrompt(chatOnly, true, "claude") ?: return
        assertTrue(p.choices.isEmpty())
        assertTrue(needsConfirmation(p))
        val r = questionRead(chatOnly, p, "claude", null, true)
        assertFalse(r.topped)
        val gate = QuestionConfirm()
        assertFalse((1..10).any { gate.decide("k", r).shows == CardShows.CARD })
    }

    @Test fun theAlertSaysOnlyThatAQuestionIsAskedUntilConfirmed() {
        val text = screen("claude/captured-2.1.329/single-question.txt")
        val p = requireNotNull(detectPrompt(text, true, "claude"))
        val before = requireNotNull(alertCard(p, confirmed = false, agent = "Claude"))
        assertEquals("Claude is asking a question. Open HerdrDroid to see it.", alertText(before))
        assertTrue(before.choices.isEmpty())
        assertTrue(alertPlan(before, before.terminalReview, reply = false).buttons.isEmpty())
        assertFalse(alertText(before).contains("redirect"))
        // Once confirmed, the alert is the question itself, with its options.
        val after = alertCard(p, confirmed = true, agent = "Claude")
        assertSame(p, after)
        assertTrue(alertText(after!!).contains("How should I redirect it?"))
        assertTrue(alertPlan(after).buttons.isNotEmpty())
    }

    @Test fun theAlertConfirmsFromItsOwnReadsAndSkipsABadOne() = runBlocking {
        val full = screen("claude/captured-2.1.329/single-question.txt")
        val rows = full.lines()
        val partial = rows.take(rows.indexOfFirst { it.startsWith("Enter to select") }).joinToString("\n")
        val first = requireNotNull(detectPrompt(partial, true, "claude"))
        // A blank read and a read with no card in between are skipped, not the end.
        val reads = ArrayDeque(listOf("", "Quokka redraw\n", full, full))
        val got = requireNotNull(confirmByReads(partial, first, "claude", null) { reads.removeFirstOrNull() })
        assertEquals(detectPrompt(full, true, "claude"), got.first)
        // The pane stopped waiting: no more reads, nothing confirmed.
        assertNull(confirmByReads(partial, first, "claude", null) { null })
        // The user's echoed message, read as a menu, is never confirmed: the alert stays neutral.
        val echo = "  Quokka reply.\n\n❯ Please look at these Quokka items\n  Quokka item one\n  Quokka item two\n\n"
        val echoed = requireNotNull(detectPrompt(echo, true, "claude"))
        val never = ArrayDeque(List(ALERT_CONFIRM_READS) { echo })
        assertNull(confirmByReads(echo, echoed, "claude", null) { never.removeFirstOrNull() })
    }

    @Test fun thePlaceholderCanNeverBeAnswered() {
        val text = screen("claude/captured-2.1.329/single-question.txt")
        val fresh = requireNotNull(detectPrompt(text, true, "claude"))
        for (placeholder in listOf(readingQuestionCard("Claude"), unreadQuestionCard("Claude"))) {
            assertTrue(placeholder.choices.isEmpty())
            assertTrue(placeholder.terminalReview)
            assertFalse(cardAnswerStillFits(placeholder, fresh))
            assertFalse(cardAnswerStillFits(fresh, placeholder))
            fresh.choices.forEach { assertNull(menuKeys(placeholder, it)) }
            assertFalse(QuestionPills.tappable("claude", placeholder))
            assertTrue(QuestionPills.plan("claude", placeholder, 0, "x", text, fresh) is QuestionPills.Plan.Refuse)
        }
        assertTrue(cardAnswerStillFits(fresh, detectPrompt(text, true, "claude")))
    }

    @Test fun multiQuestionPillsSurviveConfirmation() {
        val text = screen("claude/captured-2.1.329-two-questions/q1.txt")
        val p = requireNotNull(confirmedTwice(text, "claude", false))
        val shown = requireNotNull(shownCard(p, decision(p, CardShows.CARD), "Claude"))
        assertTrue(shown.shownParts().tabs.size >= 2)
        assertTrue(QuestionPills.tappable("claude", shown))
    }
}
