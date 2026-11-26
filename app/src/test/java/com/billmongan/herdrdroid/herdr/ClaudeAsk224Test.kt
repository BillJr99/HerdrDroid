package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.notify.alertBody
import com.billmongan.herdrdroid.notify.alertPlan
import com.billmongan.herdrdroid.notify.alertText
import com.billmongan.herdrdroid.transcript.QuestionLog
import com.billmongan.herdrdroid.ui.CardBlock
import com.billmongan.herdrdroid.ui.CardFormat
import com.billmongan.herdrdroid.voice.voiceCardKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.4 (Bill: "herdrDroid failed to render that yes no question and Claude just now please fix in
 * 2.2.4"). Claude Code 2.1.330's permission prompt for a helper agent's tall heredoc command that a
 * PreToolUse hook sent to the user, captured live (captured-2.1.330-tall, plain and ansi): the title
 * and description, 28 command rows in the dashed frame's "│" gutter (with "$ id", quote rows and bare
 * digits among them), the hook's reason in the gutter under the second dashed rule, "settings.json to
 * update hooks", the question, Yes and No, the hint row and the closing rule. Derived from it
 * (derived-2.1.330-tall): the same dialog taller than a 47-row screen, its top rule, title and first
 * command rows scrolled off. Each goes through every path: the card, its confirmation, the needs-you
 * status and alert, voice, the question history and the answer keys, blocked or not.
 */
class ClaudeAsk224Test {
    private fun screen(path: String) = checkNotNull(javaClass.getResource("/screens/claude/$path")) { path }.readText()

    private data class Case(val path: String, val title: String?, val topOff: Boolean, val rows: Int, val first: String, val swap: String)

    private val cases = listOf(
        Case("captured-2.1.330-tall/tall-ask.txt", "Bash command, from the general-purpose agent", false, 28, "cd /home/user/scratch && python3 - <<'EOF'", "printf ok;  "),
        Case("captured-2.1.330-tall/tall-ask.ansi", "Bash command, from the general-purpose agent", false, 28, "cd /home/user/scratch && python3 - <<'EOF'", "printf ok;  "),
        Case("derived-2.1.330-tall/scrolled-ask.txt", null, true, 36, "$ nproc", "$ uname"),
    )
    private val description = "Print canned probe text for prompt capture test"
    private val explanation = listOf(
        "Hook PreToolUse:Bash requires confirmation for this command:",
        "gh policy: core list 1: a secret location (keys, tokens, ~/.config, keystores, secret/private) [settings]",
        "settings.json to update hooks",
    )

    /** The command as the screen shows it: each gutter row above the last dashed rule, without the gutter. */
    private fun commandOf(c: Case): List<String> {
        val rows = stripAnsi(screen(c.path)).lines().map { it.trimEnd() }
        val end = rows.indexOfLast { it.startsWith("╌") }
        val start = rows.take(end).indexOfLast { it.startsWith("╌") } + 1
        return rows.subList(start, end).map { it.removePrefix(" │ ").removePrefix(" │") }
    }

    private fun parsed(c: Case, blocked: Boolean) = checkNotNull(detectPrompt(screen(c.path), blocked, "claude")) { "${c.path} blocked=$blocked: no card" }

    private fun squash(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    @Test fun theFixturesAreTheDialogWeMeant() {
        for (c in cases) {
            val cmd = commandOf(c)
            assertEquals(c.path, c.rows, cmd.size)
            assertEquals(c.path, c.first, cmd.first())
            assertEquals(c.path, "EOF", cmd.last())
            assertTrue(c.path, cmd.any { it == "$ id" } && cmd.any { it == "'''" } && cmd.any { it == "1073741824" })
        }
        assertEquals(47, screen("derived-2.1.330-tall/scrolled-ask.txt").trimEnd('\n').lines().size)
        assertEquals(47, screen("captured-2.1.330-tall/tall-ask.txt").trimEnd('\n').lines().size)
    }

    @Test fun theCardIsTheTitleDescriptionWholeCommandAndHookReason() {
        for (c in cases) for (blocked in listOf(true, false)) {
            val p = parsed(c, blocked)
            val where = "${c.path} blocked=$blocked"
            assertEquals(where, listOf("Yes", "No"), p.choices.map { it.label })
            assertEquals(where, listOf("1", "2"), p.choices.map { it.keys })
            assertEquals(where, "1", p.selected)
            assertEquals(where, c.topOff, p.topOffScreen)
            val parts = p.shownParts()
            assertEquals(where, "Do you want to proceed?", parts.heading)
            // One command block: every row, in order, one line each, indentation kept.
            assertEquals("$where ${parts.blocks}", commandOf(c).joinToString("\n"), parts.command)
            assertEquals(where, 1, parts.blocks.count { it.kind == QKind.COMMAND })
            val details = parts.details.map(::squash)
            val context = squash(details.joinToString(" "))
            if (c.title != null) {
                assertTrue("$where title in $details", c.title in details)
                assertTrue("$where description in $details", description in details)
            }
            for (e in explanation) assertTrue("$where \"$e\" in $details", context.contains(squash(e)))
            assertEquals("$where note in $details", c.topOff, details.any { it == CLAUDE_TOP_OFF_NOTE })
            // Nothing from the chat above the dialog, and no frame, footer or rule rows.
            val all = parts.blocks.joinToString("\n") { it.text } + "\n" + p.question
            for (bad in listOf("grep -q", "herdr pane", "ctrl+b", "Esc to cancel", "Session", "╌", "──", "│", "❯"))
                assertFalse("$where: \"$bad\" in ${parts.blocks}", all.contains(bad))
            // The card: the command as one code block (long, so it folds with Show all), the reason as text.
            val blocks = CardFormat.format(parts, screen(c.path))
            val code = blocks.filterIsInstance<CardBlock.Code>()
            assertEquals("$where $blocks", 1, code.size)
            assertEquals(where, commandOf(c).map { it.trim() }, code.single().lines.map { it.trim() })
            assertFalse(where, code.single().wrap)
            val prose = blocks.filter { it !is CardBlock.Code }.joinToString("\n") { CardFormat.plain(it) }.let(::squash)
            for (e in explanation) assertTrue("$where \"$e\" in $prose", prose.contains(squash(e)))
            if (c.title != null) assertTrue("$where $prose", prose.contains(description))
            if (c.topOff) assertTrue("$where $prose", prose.contains(CLAUDE_TOP_OFF_NOTE))
        }
    }

    @Test fun theDialogIsProvenFromItsTopOrTheScreensTopAndConfirmedByTwoReads() {
        for (c in cases) for (blocked in listOf(true, false)) {
            val text = screen(c.path)
            val p = parsed(c, blocked)
            val r = questionRead(text, p, "claude", null, blocked)
            assertTrue("${c.path} topped", r.topped)
            assertTrue("${c.path} complete", r.complete)
            assertFalse(r.refused)
            val gate = QuestionConfirm()
            val first = if (c.topOff) CardShows.CARD else CardShows.READING
            assertEquals(first, gate.decide("k", r).shows)
            assertEquals("${c.path}: two identical reads show the card", CardShows.CARD, gate.decide("k", r).shows)
            assertEquals(p, shownCard(p, CardDecision(p, Verdict(CardShows.CARD)), "Claude"))
        }
    }

    @Test fun aHalfDrawnDialogNeverConfirms() {
        for (c in cases) {
            val rows = screen(c.path).lines().dropLastWhile { it.isBlank() }
            val footer = rows.indexOfLast { stripAnsi(it).trim().startsWith("Esc to cancel") }
            assertTrue(c.path, footer > 0)
            for (cut in footer downTo footer - 14) {
                val text = rows.take(cut).joinToString("\n")
                val p = detectPrompt(text, true, "claude") ?: continue
                if (!needsConfirmation(p)) continue
                val r = questionRead(text, p, "claude", null, true)
                assertFalse("${c.path} cut at $cut: ${p.question} ${p.choices.map { it.label }}", r.complete)
                val gate = QuestionConfirm()
                assertFalse("${c.path} cut at $cut", (1..2).any { gate.decide("k", r).shows == CardShows.CARD })
            }
        }
    }

    @Test fun theChatAboveTheDialogNeverBecomesTheQuestionOrAnOption() {
        // The live chat quotes "1. Yes" and "Do you want to proceed?" in a grep pattern.
        for (c in cases.filter { it.title != null }) {
            val rows = stripAnsi(screen(c.path)).lines().dropLastWhile { it.isBlank() }
            val title = rows.indexOfFirst { it.trim().startsWith("Bash command") }
            val options = rows.indexOfFirst { it.contains("1. Yes") && it.trimStart().startsWith("❯") && it.startsWith(" ") }
            for (from in listOf(options, options - 1, options - 2, options - 4)) {
                val text = (rows.take(title - 1) + rows.drop(from)).joinToString("\n")
                for (b in listOf(true, false)) {
                    val p = detectPrompt(text, b, "claude") ?: continue
                    val gate = QuestionConfirm()
                    val r = questionRead(text, p, "claude", null, b)
                    if ((1..10).any { gate.decide("k", r).shows == CardShows.CARD }) {
                        val all = listOf(p.question) + p.choices.map { it.label } + p.shownParts().blocks.map { it.text }
                        assertFalse("${c.path} from $from: chat in a shown card $all", all.any { it.contains("grep") || it.contains("herdr pane") })
                    }
                }
            }
        }
    }

    @Test fun rowsOutsideTheFrameAreNeverReadAsTheCommand() {
        // Chat prose in place of command rows, or above a dashed rule with no top rule: not the frame.
        val rows = screen("derived-2.1.330-tall/scrolled-ask.txt").lines()
        val options = rows.indexOfFirst { it.contains("1. Yes") }
        assertNotNull(claudeFramedDialog(rows, options))
        val chat = rows.toMutableList().also { it[3] = "  ● Quokka went on with the next step." }
        assertNull(claudeFramedDialog(chat, options))
        val p = detectPrompt(chat.joinToString("\n"), true, "claude")
        if (p != null) {
            assertFalse(p.topOffScreen)
            val r = questionRead(chat.joinToString("\n"), p, "claude", null, true)
            val gate = QuestionConfirm()
            if ((1..10).any { gate.decide("k", r).shows == CardShows.CARD })
                assertFalse(p.shownParts().blocks.any { it.text.contains("Quokka") })
        }
        // Prose that only mentions the question, with no frame: no framed dialog.
        val prose = listOf("  I'll ask: Do you want to proceed?", " Do you want to proceed?", " ❯ 1. Yes", "   2. No")
        assertNull(claudeFramedDialog(prose, 2))
    }

    @Test fun thePaneNeedsYouAndTheAlertIsTheQuestionWithYesAndNo() {
        for (c in cases) {
            val text = screen(c.path)
            assertNotNull("${c.path} needs you", claudeNeedsYouReason(text))
            val p = parsed(c, true)
            val alert = checkNotNull(alertCard(p, confirmed = true, agent = "Claude"))
            val said = alertText(alert)
            assertTrue("${c.path}: $said", said.contains("Do you want to proceed?"))
            assertTrue("${c.path}: $said", said.contains(c.first))
            val body = alertBody(alert)
            assertTrue("${c.path}: $body", body.contains(c.first))
            assertFalse("${c.path}: $body", body.contains("grep -q") || body.contains("herdr pane"))
            // A tall command: its first lines and how many more, then the hook's reason.
            assertTrue("${c.path}: $body", body.contains("more lines in the app") && body.contains("Hook PreToolUse:Bash requires confirmation"))
            val buttons = alertPlan(alert).buttons
            val labels = if (c.topOff) emptyList() else listOf("Yes", "No")
            assertEquals("${c.path}: $buttons", labels, buttons.filter { it.choice != null }.map { it.title })
            assertEquals(if (c.topOff) emptyList() else listOf("1", "2"), buttons.mapNotNull { it.choice?.keys })
        }
    }

    @Test fun yesSendsOptionOnesKeyAndNoOptionTwosFromTheCardTheAlertAndVoice() {
        for (c in cases) for (blocked in listOf(true, false)) {
            val p = parsed(c, blocked)
            val (yes, no) = p.choices
            if (c.topOff) {
                assertTrue(p.forCard().terminalReview)
                for (c in listOf(yes, no)) {
                    assertNull(menuKeys(p, c))
                    assertNull(voiceCardKeys(p, c))
                }
                continue
            }
            assertEquals(c.path, listOf("enter"), menuKeys(p, yes))
            assertEquals(c.path, listOf("down", "enter"), menuKeys(p, no))
            assertEquals(c.path, listOf("enter"), voiceCardKeys(p, yes))
            assertEquals(c.path, listOf("down", "enter"), voiceCardKeys(p, no))
            // A highlight moved to No at the computer: the keys start from there, never a guess.
            if (c.path.endsWith(".txt")) {
                val moved = screen(c.path).replace("❯ 1. Yes", "  1. Yes").replace("   2. No", " ❯ 2. No")
                val m = checkNotNull(detectPrompt(moved, blocked, "claude")) { "${c.path} moved" }
                assertEquals(c.path, "2", m.selected)
                assertEquals(listOf("up", "enter"), menuKeys(m, m.choices[0]))
                assertEquals(listOf("enter"), menuKeys(m, m.choices[1]))
                assertTrue(cardAnswerStillFits(p, m))
            }
            // A different command is a different question: an answer to this card never goes to it.
            val other = detectPrompt(screen(c.path).replace(c.first.take(c.swap.length), c.swap), blocked, "claude")
            assertNotNull(c.path, other)
            assertFalse(c.path, cardAnswerStillFits(p, other))
        }
    }

    @Test fun theQuestionHistoryHoldsTheQuestionCommandAndOptions() {
        for (c in cases) {
            val p = parsed(c, true)
            val item = QuestionLog.seen(emptyList(), p, "claude", null, 1L, screen(c.path)).last()
            val q = item.parts.single()
            assertEquals(c.path, listOf("Yes", "No"), q.options)
            assertEquals(c.path, "Do you want to proceed?", q.question)
            assertEquals(c.path, commandOf(c).joinToString("\n"), q.command)
            val details = q.details.map(::squash)
            if (c.title != null) assertTrue("${c.path} $details", c.title in details)
            for (e in explanation) assertTrue("${c.path} $details", squash(details.joinToString(" ")).contains(squash(e)))
        }
    }
}
