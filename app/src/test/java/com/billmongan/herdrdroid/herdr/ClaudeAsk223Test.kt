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
 * 2.2.3 (Bill: "Claude tab herdrdroid failed to parse that yes/no question and the most recent
 * question please fix in 2.2.3 now"). Claude Code 2.1.330's permission prompt, captured live
 * (captured-2.1.330-ask, plain and ansi): a title with "· from the general-purpose agent", a
 * description, the command in a dashed frame with a "│" gutter, explanation rows (a permissions
 * rule), "Do you want to proceed?", Yes and No, the hint row "Esc to cancel · Tab to amend · ctrl+x
 * ctrl+k twice to stop background agents", and under it the closing rule of the input area with the
 * session's title in it. Derived from the same layout (derived-2.1.330-ask): a PreToolUse hook's
 * "ask" with a reason that wraps, and the older layout with no "· from" suffix and no dashed frame.
 * Each goes through every path: the card, its confirmation, the needs-you status and alert, voice,
 * the question history and the answer keys.
 */
class ClaudeAsk223Test {
    private fun screen(path: String) = checkNotNull(javaClass.getResource("/screens/claude/$path")) { path }.readText()

    private data class Case(val path: String, val title: String, val description: String, val command: String, val explanation: List<String>)

    private val cases = listOf(
        Case("captured-2.1.330-ask/rule-ask.txt", "Bash command, from the general-purpose agent", "Dry-run rsync with delete on a scratch folder",
            "rsync -n --delete /home/user/scratch/sample-dir.abcdef/ /home/user/scratch/sample-dir.abcdef/; echo \"exit=$?\"",
            listOf("Rule Bash(rsync *--delete*) from user settings overrides auto mode.", "/permissions to let auto mode decide")),
        Case("captured-2.1.330-ask/rule-ask.ansi", "Bash command, from the general-purpose agent", "Dry-run rsync with delete on a scratch folder",
            "rsync -n --delete /home/user/scratch/sample-dir.abcdef/ /home/user/scratch/sample-dir.abcdef/; echo \"exit=$?\"",
            listOf("Rule Bash(rsync *--delete*) from user settings overrides auto mode.", "/permissions to let auto mode decide")),
        Case("derived-2.1.330-ask/hook-ask.txt", "Bash command, from the general-purpose agent", "Show the signed-in GitHub account state",
            "gh auth status --hostname github.com",
            listOf("Hook PreToolUse:Bash requires confirmation for this command:",
                "gh policy: core list 1: a secret location (keys, tokens, ~/.config, keystores, vault/private) is named in the command (see: the shared policy, rule 4: paths)")),
        Case("derived-2.1.330-ask/legacy-ask.txt", "Bash command", "Dry-run rsync with delete on a scratch folder",
            "rsync -n --delete /home/user/scratch/sample-dir.abcdef/ /home/user/scratch/sample-dir.abcdef/; echo \"exit=$?\"",
            listOf("Rule Bash(rsync *--delete*) from user settings overrides auto mode.", "/permissions to let auto mode decide")),
    )

    private fun parsed(c: Case, blocked: Boolean) = checkNotNull(detectPrompt(screen(c.path), blocked, "claude")) { "${c.path} blocked=$blocked: no card" }

    private fun squash(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    @Test fun theCardIsTheDialogWithItsTitleDescriptionCommandAndExplanation() {
        for (c in cases) for (blocked in listOf(true, false)) {
            val p = parsed(c, blocked)
            val where = "${c.path} blocked=$blocked"
            assertEquals(where, listOf("Yes", "No"), p.choices.map { it.label })
            assertEquals(where, listOf("1", "2"), p.choices.map { it.keys })
            assertEquals(where, "1", p.selected)
            val parts = p.shownParts()
            assertEquals(where, "Do you want to proceed?", parts.heading)
            assertEquals("$where ${parts.blocks}", c.command, parts.command)
            val details = parts.details.map(::squash)
            assertTrue("$where title in $details", c.title in details)
            assertTrue("$where description in $details", c.description in details)
            for (e in c.explanation) assertTrue("$where \"$e\" in $details", squash(e) in details)
            // Nothing from the chat above the dialog, and no frame, footer or rule rows.
            val all = parts.blocks.joinToString("\n") { it.text } + "\n" + p.question
            for (bad in listOf("Claude tab", "grep -q", "kanban", "Esc to cancel", "Session", "╌", "──", "│", "❯"))
                assertFalse("$where: \"$bad\" in ${parts.blocks}", all.contains(bad))
            // The card shows the command as a code block and the explanation as text.
            val blocks = CardFormat.format(parts, screen(c.path))
            assertTrue("$where $blocks", blocks.any { it is CardBlock.Code && it.lines.joinToString(" ") { l -> l.trim() } == c.command })
            val prose = blocks.filter { it !is CardBlock.Code }.joinToString("\n") { CardFormat.plain(it) }.let(::squash)
            for (e in c.explanation) assertTrue("$where \"$e\" in $prose", prose.contains(squash(e)))
            assertTrue("$where $prose", prose.contains(c.description))
        }
    }

    @Test fun theDialogIsProvenFromItsTopOptionsAndFooterAndConfirmedByTwoReads() {
        for (c in cases) for (blocked in listOf(true, false)) {
            val text = screen(c.path)
            val p = parsed(c, blocked)
            val r = questionRead(text, p, "claude", null, blocked)
            assertTrue("${c.path} topped", r.topped)
            assertTrue("${c.path} complete", r.complete)
            assertFalse(r.refused)
            val gate = QuestionConfirm()
            assertEquals(CardShows.READING, gate.decide("k", r).shows)
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
        // The live chat has a row quoting "1. Yes" and "Do you want to proceed?" and the user's echoed message.
        for (c in cases) {
            val rows = screen(c.path).lines().dropLastWhile { it.isBlank() }
            val title = rows.indexOfFirst { stripAnsi(it).trim().startsWith("Bash command") }
            // Only the dialog's options and footer drawn so far under the chat: never a card with chat text.
            val options = rows.indexOfFirst { stripAnsi(it).contains("1. Yes") && it.trimStart().startsWith("❯") && it.startsWith(" ") }
            for (from in listOf(options, options - 1, options - 2)) {
                val text = (rows.take(title - 1) + rows.drop(from)).joinToString("\n")
                for (b in listOf(true, false)) {
                    val p = detectPrompt(text, b, "claude") ?: continue
                    val gate = QuestionConfirm()
                    val r = questionRead(text, p, "claude", null, b)
                    if ((1..10).any { gate.decide("k", r).shows == CardShows.CARD }) {
                        val all = listOf(p.question) + p.choices.map { it.label } + p.shownParts().blocks.map { it.text }
                        assertFalse("${c.path} from $from: chat in a shown card $all", all.any { it.contains("grep") || it.contains("kanban") || it.contains("Claude tab") })
                    }
                }
            }
        }
    }

    @Test fun thePaneNeedsYouAndTheAlertIsTheQuestionWithYesAndNo() {
        for (c in cases) {
            val text = screen(c.path)
            assertNotNull("${c.path} needs you", claudeNeedsYouReason(text))
            val p = parsed(c, true)
            val alert = checkNotNull(alertCard(p, confirmed = true, agent = "Claude"))
            val said = alertText(alert)
            assertTrue("${c.path}: $said", said.contains("Do you want to proceed?"))
            assertTrue("${c.path}: $said", said.contains(c.command))
            val body = alertBody(alert)
            assertTrue("${c.path}: $body", body.contains(c.command))
            assertFalse("${c.path}: $body", body.contains("Claude tab") || body.contains("grep -q"))
            val buttons = alertPlan(alert).buttons
            assertEquals("${c.path}: $buttons", listOf("Yes", "No"), buttons.filter { it.choice != null }.map { it.title })
            assertEquals(listOf("1", "2"), buttons.mapNotNull { it.choice?.keys })
        }
    }

    @Test fun yesSendsOptionOnesKeyAndNoOptionTwosFromTheCardTheAlertAndVoice() {
        for (c in cases) for (blocked in listOf(true, false)) {
            val p = parsed(c, blocked)
            val (yes, no) = p.choices
            assertEquals(c.path, listOf("enter"), menuKeys(p, yes))
            assertEquals(c.path, listOf("down", "enter"), menuKeys(p, no))
            assertEquals(c.path, listOf("enter"), voiceCardKeys(p, yes))
            assertEquals(c.path, listOf("down", "enter"), voiceCardKeys(p, no))
            // A highlight moved to No at the computer: the keys start from there, never a guess.
            val moved = screen(c.path).replace("❯ 1. Yes", "  1. Yes").replace(Regex("""(?m)^(\s*(?:\u001B\[[0-9;]*m)*) {2}((?:\u001B\[[0-9;]*m)*2\. )"""), "$1❯ $2")
            val m = detectPrompt(moved, blocked, "claude")
            if (c.path.endsWith(".txt")) {
                checkNotNull(m) { "${c.path} moved" }
                assertEquals(c.path, "2", m.selected)
                assertEquals(listOf("up", "enter"), menuKeys(m, m.choices[0]))
                assertEquals(listOf("enter"), menuKeys(m, m.choices[1]))
                assertTrue(cardAnswerStillFits(p, m))
            }
            // A different command is a different question: an answer to this card never goes to it.
            val other = detectPrompt(screen(c.path).replace(c.command.take(12), "printf ok;  "), blocked, "claude")
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
            assertEquals(c.path, c.command, q.command)
            val details = q.details.map(::squash)
            assertTrue("${c.path} $details", c.title in details)
            for (e in c.explanation) assertTrue("${c.path} $details", squash(e) in details)
        }
    }

    @Test fun theFooterAndItsChipsAreTheDialogsOwnRow() {
        for (row in listOf(" Esc to cancel · Tab to amend · ctrl+x ctrl+k twice to stop background agents", " Esc to cancel · Tab to amend",
                " Esc to cancel", " Esc to cancel · ctrl+e to explain · Tab to amend · shift+tab to toggle a future mode")) {
            assertTrue(row, isDialogFooterRow(row))
            assertNotNull(row, claudeNeedsYouReason("──────────────────────────────\n Do you want to proceed?\n ❯ 1. Yes\n   2. No\n\n$row\n"))
        }
        // Chat prose that mentions Esc is not a footer.
        assertNull(claudeNeedsYouReason("  Press Esc to cancel the run if it hangs.\n"))
    }
}
