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
 * card responses"): a question card shows only once two reads in a row give it from a complete
 * dialog; until then a neutral card. Partial reads are built from the captured dialogs in the screen
 * library: cut off at several heights, the footer not drawn yet, and chat rows (the user's own
 * numbered message, short lines) above a dialog whose top edge is not drawn yet. The chat rows all
 * carry the word "Quokka", which no capture has, so any card text with it came from the chat.
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

    private fun confirmedTwice(text: String, agent: String, blocked: Boolean): Prompt? {
        val p = detectPrompt(text, blocked, agent) ?: return null
        val gate = QuestionConfirm()
        val first = gate.offer("k", questionRead(text, p, agent, null, blocked))
        val second = gate.offer("k", questionRead(text, p, agent, null, blocked))
        assertFalse("one read never confirms a question card", first && needsConfirmation(p))
        return p.takeIf { second }
    }

    private fun mentionsChat(p: Prompt): Boolean =
        (listOf(p.question) + p.choices.map { it.label } + p.shownParts().blocks.map { it.text } + p.region.orEmpty()).any { it.contains("Quokka") }

    @Test fun everyFullDialogIsConfirmedByTwoMatchingReadsAndMatchesTheCapture() {
        for ((path, agent, blocked) in dialogs) {
            val text = screen(path)
            val full = requireNotNull(detectPrompt(text, blocked, agent)) { path }
            assertTrue(path, full.choices.isNotEmpty())
            assertTrue("$path: the dialog is complete", questionRead(text, full, agent, null, blocked).complete)
            assertEquals(path, full, confirmedTwice(text, agent, blocked))
            // Shown once confirmed, exactly as parsed; before that, the neutral card.
            assertSame(path, full, shownCard(full, full, "Agent"))
            assertTrue(path, shownCard(full, null, "Agent")!!.readingQuestion)
        }
    }

    /**
     * Every card with options in the library is confirmed by reads that keep giving it (no
     * regression), none is refused, and every real capture of a dialog with a footer is complete.
     * The ones that are not complete (fragments derived from documentation, Claude's review step,
     * which draws no footer, a reconstructed Grok prompt with no frame) take [FALLBACK_READS] reads.
     */
    @Test fun everyLibraryCardWithOptionsIsConfirmedByMatchingReads() {
        val root = javaClass.classLoader?.getResource("screens")?.let { File(it.toURI()) } ?: error("no screens")
        val broken = mutableListOf<String>()
        val incomplete = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".txt") }.sortedBy { it.path }.forEach { f ->
            val e = File(f.path.removeSuffix(".txt") + ".expect").takeIf { it.isFile }?.readLines()
                ?.mapNotNull { l -> l.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }?.toMap()
                ?: return@forEach
            if (e["card"] != "options") return@forEach
            val agent = f.parentFile.parentFile.name
            val blocked = e["blocked"] == "true"
            val text = f.readText()
            val p = detectPrompt(text, blocked, agent) ?: return@forEach
            val read = questionRead(text, p, agent, null, blocked)
            val rel = f.relativeTo(root).path
            if (read.refused) broken += "refused $rel"
            if (!read.complete) incomplete += rel
            val gate = QuestionConfirm()
            if ((1..FALLBACK_READS).none { gate.offer("k", read) }) broken += "never confirmed $rel"
        }
        println("2.2.2 cards confirmed by $FALLBACK_READS reads (structure not shown complete):\n" + incomplete.joinToString("\n"))
        assertTrue(broken.joinToString("\n"), broken.isEmpty())
        for ((path, _, _) in dialogs) assertFalse(path, path in incomplete)
    }

    @Test fun aDialogCutOffAtAnyHeightIsNeverConfirmed() {
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
                // The screen-and-keys card shows the rows as they are, once two reads agree; a card with options waits for its footer.
                if (p.choices.isEmpty()) continue
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
            for (keep in 0..2) {
                val text = (chat + rows.subList(first - keep, rows.size)).joinToString("\n")
                for (b in listOf(true, false)) {
                    val p = detectPrompt(text, b, agent) ?: continue
                    val shown = confirmedTwice(text, agent, b)
                    if (shown != null) assertFalse("$path keep $keep: chat text in a confirmed card: \"${shown.question}\" ${shown.choices.map { it.label }}", mentionsChat(shown))
                    else if (needsConfirmation(p)) assertTrue(shownCard(p, null, "Agent")!!.readingQuestion)
                }
            }
            // The full dialog under the same chat: its own question, confirmed.
            val under = (chat + rows).joinToString("\n")
            val p = detectPrompt(under, blocked, agent)
            val shown = confirmedTwice(under, agent, blocked)
            if (p != null && shown != null) assertFalse(path, mentionsChat(shown))
        }
    }

    /** Before 2.2.2: the user's own message, echoed with "❯" while the dialog is not drawn yet, became the card's options. */
    @Test fun theUsersEchoedMessageWasReadAsTheOptionsAndIsNowNeverShown() {
        val unnumbered = listOf(
            "  Quokka reply from earlier.", "",
            "❯ Please look at these Quokka items", "  Quokka item one", "  Quokka item two", "", "", "",
        ).joinToString("\n")
        val numbered = listOf(
            "  Should Quokka go ahead?", "",
            "❯ 1. Quokka first thing", "  2. Quokka second thing", "", "", "",
        ).joinToString("\n")
        for (text in listOf(unnumbered, numbered)) {
            val today = detectPrompt(text, blocked = true, agent = "claude")
            println("2.2.2 repro, card read today: " + today?.let { "\"${it.question}\" ${it.choices.map { c -> c.label }}" })
            if (today != null && today.choices.isNotEmpty()) assertTrue("the old card took the chat", mentionsChat(today))
            if (today != null) {
                assertFalse(questionRead(text, today, "claude", null, true).complete)
                assertNull(confirmedTwice(text, "claude", true))
                val shown = shownCard(today, null, "Claude")!!
                assertTrue(shown.readingQuestion)
                assertFalse(mentionsChat(shown))
                assertTrue(shown.choices.isEmpty())
            }
        }
        // The unnumbered case does reproduce: the message rows were the options.
        val today = requireNotNull(detectPrompt(unnumbered, blocked = true, agent = "claude"))
        assertTrue(today.choices.any { it.label.contains("Quokka") })
        // The echoed message as a menu's first option, with nothing of a dialog under it, is never confirmed.
        val read = questionRead(unnumbered, today, "claude", null, true)
        assertTrue(read.refused)
        val gate = QuestionConfirm()
        repeat(10) { assertFalse(gate.offer("k", read)) }
    }

    @Test fun twoConsecutiveIdenticalReadsConfirm() {
        val text = screen("claude/captured-2.1.329/single-question.txt")
        val p = requireNotNull(detectPrompt(text, false, "claude"))
        val read = questionRead(text, p, "claude", null, false)
        val gate = QuestionConfirm()
        assertFalse(gate.offer("a", read))
        assertTrue(gate.offer("a", read))
        assertTrue("stays confirmed while the reads keep giving it", gate.offer("a", read))
        // The highlight moving is not a new question.
        val moved = text.replace("❯ 1. Cancel and stop", "  1. Cancel and stop").replace("  2. Cancel and redo", "❯ 2. Cancel and redo")
        val p2 = requireNotNull(detectPrompt(moved, false, "claude"))
        assertEquals("2", p2.selected)
        assertTrue(gate.offer("a", questionRead(moved, p2, "claude", null, false)))
        // An incomplete read starts over; so does no card.
        val cut = text.lines().takeWhile { !it.startsWith("Enter to select") }.joinToString("\n")
        detectPrompt(cut, true, "claude")?.let { assertFalse(gate.offer("a", questionRead(cut, it, "claude", null, true))) }
        assertFalse(gate.offer("a", read))
        assertTrue(gate.offer("a", read))
        assertTrue(gate.offer("a", null).not())
        assertFalse(gate.offer("a", read))
        // Two different reads in a row are not a confirmation; each pane is its own.
        val other = questionRead(screen("claude/captured-2026-11-13/bash-permission.txt"),
            requireNotNull(detectPrompt(screen("claude/captured-2026-11-13/bash-permission.txt"), true, "claude")), "claude", null, true)
        assertFalse(gate.offer("b", other))
        assertFalse(gate.offer("b", read))
        assertTrue(gate.offer("b", read))
    }

    @Test fun cardsThatNeedNoConfirmingShowAtOnce() {
        val unread = unreadQuestionCard("Claude")
        assertSame(unread, shownCard(unread, null, "Claude"))
        assertNull(shownCard(null, null, "Claude"))
        assertTrue(QuestionConfirm().offer("k", QuestionRead(unread, null)))
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

    @Test fun theAlertConfirmsFromItsOwnReads() = runBlocking {
        val full = screen("claude/captured-2.1.329/single-question.txt")
        val rows = full.lines()
        val partial = rows.take(rows.indexOfFirst { it.startsWith("Enter to select") }).joinToString("\n")
        val first = requireNotNull(detectPrompt(partial, true, "claude"))
        val reads = ArrayDeque(listOf(full, full))
        val got = requireNotNull(confirmByReads(partial, first, "claude", null) { reads.removeFirstOrNull() })
        assertEquals(detectPrompt(full, true, "claude"), got.first)
        // A dialog not finished drawing is not confirmed by two reads: the alert stays neutral.
        val again = ArrayDeque(listOf(partial))
        assertNull(confirmByReads(partial, first, "claude", null, maxReads = 1) { again.removeFirstOrNull() })
        // The user's echoed message, read as a menu, is never confirmed: the alert stays neutral.
        val echo = "  Quokka reply.\n\n❯ Please look at these Quokka items\n  Quokka item one\n  Quokka item two\n\n"
        val echoed = requireNotNull(detectPrompt(echo, true, "claude"))
        val never = ArrayDeque(List(ALERT_CONFIRM_READS) { echo })
        assertNull(confirmByReads(echo, echoed, "claude", null) { never.removeFirstOrNull() })
        // The pane stopped waiting: no more reads, nothing confirmed.
        assertNull(confirmByReads(partial, first, "claude", null) { null })
    }

    @Test fun thePlaceholderCanNeverBeAnswered() {
        val text = screen("claude/captured-2.1.329/single-question.txt")
        val fresh = requireNotNull(detectPrompt(text, true, "claude"))
        val placeholder = readingQuestionCard("Claude")
        assertTrue(placeholder.choices.isEmpty())
        assertTrue(placeholder.terminalReview)
        assertFalse(cardAnswerStillFits(placeholder, fresh))
        assertFalse(cardAnswerStillFits(fresh, placeholder))
        assertFalse(cardAnswerStillFits(placeholder, placeholder))
        fresh.choices.forEach { assertNull(menuKeys(placeholder, it)) }
        assertTrue(cardAnswerStillFits(fresh, detectPrompt(text, true, "claude")))
        assertFalse(QuestionPills.tappable("claude", placeholder))
        assertTrue(QuestionPills.plan("claude", placeholder, 0, "x", text, fresh) is QuestionPills.Plan.Refuse)
    }

    @Test fun multiQuestionPillsSurviveConfirmation() {
        val text = screen("claude/captured-2.1.329-two-questions/q1.txt")
        val p = requireNotNull(confirmedTwice(text, "claude", false))
        val shown = requireNotNull(shownCard(p, p, "Claude"))
        assertTrue(shown.shownParts().tabs.size >= 2)
        assertTrue(QuestionPills.tappable("claude", shown))
    }
}
