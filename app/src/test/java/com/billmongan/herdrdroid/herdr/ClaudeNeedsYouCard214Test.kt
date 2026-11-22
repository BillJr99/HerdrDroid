package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

/**
 * 2.1.4, Bill: "In herdrdroid a claude pane missed rendering a question in chat for you it showed
 * the orange you know waiting on you icon but didn't actually render the question in chat for you".
 * Claude Code 2.1.329's permission dialogs: a Bash call held by a PreToolUse hook and an auto-mode
 * approval (real captures against a mock API, captured-2.1.329-hook and -auto-approval), the
 * composer with its mode row and helper list (real, captured-2.1.329-helper-list), and derived
 * screens from those (derived-2.1.329-hook: a helper's "· from the general-purpose agent" title,
 * the helper rows under a dialog), each .expect saying where its rows come from.
 */
class ClaudeNeedsYouCard214Test {
    private fun screen(name: String) = checkNotNull(javaClass.getResource("/screens/claude/derived-2.1.329-hook/$name.txt")) { name }.readText()
    private fun card(name: String, blocked: Boolean = true) = checkNotNull(detectPrompt(screen(name), blocked, "claude")) { name }
    private val hookOptions = listOf("Yes", "No")

    @Test fun theRealHookPromptIsACardWithItsCommandAndReason() {
        // Captured (captured-2.1.329-hook): Claude Code 2.1.329 holding a Bash call for a PreToolUse hook's "ask".
        for (blocked in listOf(false, true)) for (name in listOf("hook-main.txt", "hook-main.ansi")) {
            val p = checkNotNull(detectPrompt(real("captured-2.1.329-hook", name), blocked, "claude")) { "$name $blocked" }
            assertEquals(hookOptions, p.choices.map { it.label })
            assertEquals("1", p.selected)
            val parts = p.shownParts()
            assertEquals("Do you want to proceed?", parts.heading)
            val blocks = parts.blocks
            assertEquals(blocks.toString(), "gh api repos/O/R/actions/runs/1/artifacts -q '.artifacts[] | {name, size_in_bytes}'", parts.command)
            assertTrue(blocks.toString(), blocks.any { it.kind == QKind.TEXT && it.text == "List CI run artifacts" })
            assertTrue(blocks.toString(), blocks.any { it.kind == QKind.TEXT && it.text == "Hook PreToolUse:Bash requires confirmation for this command:" })
            assertTrue(blocks.toString(), blocks.any { it.kind == QKind.TEXT && it.text.startsWith("gh policy: shared gh policy unavailable [settings]") })
        }
        val p = checkNotNull(detectPrompt(real("captured-2.1.329-hook", "hook-main.txt"), false, "claude"))
        assertEquals(listOf("enter"), menuKeys(p, p.choices[0]))
        assertEquals(listOf("down", "enter"), menuKeys(p, p.choices[1]))
        assertTrue(p.choices.all { menuKeys(p, it)?.contains("tab") == false })
        for (name in listOf("hook-main-down.txt", "hook-main-down.ansi")) {
            val moved = checkNotNull(detectPrompt(real("captured-2.1.329-hook", name), false, "claude")) { name }
            assertEquals("2", moved.selected)
            assertEquals(listOf("up", "enter"), menuKeys(moved, moved.choices[0]))
            assertEquals(listOf("enter"), menuKeys(moved, moved.choices[1]))
            assertTrue(cardAnswerStillFits(p, moved))
        }
        assertNotNull(claudeNeedsYouReason(real("captured-2.1.329-hook", "hook-main.txt")))
        assertNull(detectPrompt(real("captured-2.1.329-hook", "hook-main-after.txt"), false, "claude"))
        assertNull(claudeNeedsYouReason(real("captured-2.1.329-hook", "hook-main-after.txt")))
    }

    @Test fun aHelpersHookPromptNamesTheHelper() {
        val p = card("hook-subagent")
        assertEquals(hookOptions, p.choices.map { it.label })
        val parts = p.shownParts()
        assertEquals("Do you want to proceed?", parts.heading)
        assertTrue(parts.blocks.toString(), parts.command?.startsWith("gh api repos/O/R/actions/runs/1/artifacts") == true)
        assertTrue(parts.blocks.toString(), parts.blocks.any { it.kind == QKind.TEXT && it.text == "Bash command, from the general-purpose agent" })
        assertTrue(parts.blocks.any { it.kind == QKind.TEXT && it.text == "List CI run artifacts" })
    }

    @Test fun claudesModeRowAndHelperListUnderTheDialogDoNotHideIt() {
        // These rows would read as the agent going on after the dialog.
        for (blocked in listOf(true, false)) {
            val p = card("hook-subagent-agent-list", blocked)
            assertEquals(hookOptions, p.choices.map { it.label })
            assertEquals("Do you want to proceed?", p.shownParts().heading)
        }
        // AskUserQuestion's real 2.1.329 dialog with the same rows under it.
        val ask = card("ask-agent-list", blocked = false)
        assertEquals(listOf("Cancel and stop the lane (Recommended)", "Cancel and redo it in a container", "Leave it", "Type something.", "Chat about this"),
            ask.choices.map { it.label })
        // The status check that promotes a Claude pane to needs-you reads past them too.
        assertNotNull(claudeNeedsYouReason(screen("ask-agent-list")))
    }

    @Test fun theRowsAreOnlyTakenOffUnderADialog() {
        val composer = checkNotNull(javaClass.getResource("/screens/claude/captured-2.1.329/composer-titled-list-typed.txt")).readText()
        assertEquals(composer, withoutClaudeChromeUnderDialog(composer))
        val listed = screen("hook-subagent-agent-list")
        val cut = withoutClaudeChromeUnderDialog(listed)
        assertTrue(cut.trimEnd().endsWith("Esc to cancel · Tab to amend"))
        assertFalse(cut.contains("general-purpose  Sample helper"))
        assertEquals(screen("hook-subagent").trimEnd(), cut.trimEnd())
    }

    @Test fun answeringUsesTheLiveHighlight() {
        val p = card("hook-subagent-agent-list")
        assertEquals(listOf("enter"), menuKeys(p, p.choices[0]))
        assertEquals(listOf("down", "enter"), menuKeys(p, p.choices[1]))
        // The highlight moved at the computer: the same answers now start from option 2.
        val moved = card("hook-subagent-agent-list-down")
        assertEquals("2", moved.selected)
        assertEquals(listOf("up", "enter"), menuKeys(moved, moved.choices[0]))
        assertEquals(listOf("enter"), menuKeys(moved, moved.choices[1]))
        // A moved highlight is still the same question, so a card answer still fits it.
        assertTrue(cardAnswerStillFits(p, moved))
    }

    @Test fun aChangedQuestionRefuses() {
        val shown = card("hook-subagent-agent-list")
        assertFalse(cardAnswerStillFits(shown, card("hook-subagent-other-command")))
        assertFalse(cardAnswerStillFits(shown, card("approval-helper-list")))
        assertFalse(cardAnswerStillFits(shown, null))
        assertFalse(cardAnswerStillFits(shown, unreadQuestionCard("Claude")))
    }

    @Test fun theOrangeStateAlwaysHasACard() {
        // Herdr says blocked, the read holds no dialog: the parser finds nothing...
        assertNull(detectPrompt(screen("blocked-unreadable"), true, "claude"))
        // ...and the card that takes its place says so, offers the terminal and sends nothing.
        val fallback = checkNotNull(withNeedsYouCard(Status.NEEDS_YOU, null, suppressed = false, agent = "Claude"))
        assertTrue(fallback.unreadQuestion)
        assertTrue(fallback.terminalReview)
        assertTrue(fallback.choices.isEmpty())
        assertTrue(fallback.question.startsWith("Claude is waiting for you"))
        assertNull(menuKeys(fallback, Choice("Yes", "1", false)))
        // A parsed card is kept as it is.
        val parsed = card("hook-subagent")
        assertSame(parsed, withNeedsYouCard(Status.NEEDS_YOU, parsed, suppressed = false, agent = "Claude"))
        // Not needing you: no card. Something else in its place (an answer being typed, a usage
        // dialog, an error banner): no second card.
        for (s in listOf(Status.WORKING, Status.DONE, Status.IDLE, Status.UNKNOWN)) assertNull(withNeedsYouCard(s, null, false, "Claude"))
        assertNull(withNeedsYouCard(Status.NEEDS_YOU, null, suppressed = true, agent = "Claude"))
        // Every derived dialog read with the pane needing you gives a card, parsed or not.
        for (name in listOf("hook-subagent", "hook-subagent-agent-list", "hook-subagent-agent-list-down", "hook-subagent-other-command",
                "approval-helper-list", "ask-agent-list", "blocked-unreadable")) {
            assertNotNull(name, withNeedsYouCard(Status.NEEDS_YOU, detectPrompt(screen(name), true, "claude"), false, "Claude"))
        }
    }

    private fun real(dir: String, name: String) = checkNotNull(javaClass.getResource("/screens/claude/$dir/$name")) { name }.readText()

    @Test fun theRealAutoModeApprovalIsACardFromItsLiveHighlight() {
        // Captured from Claude Code 2.1.329 (captured-2.1.329-auto-approval): unnumbered, No first.
        val dir = "captured-2.1.329-auto-approval"
        for (blocked in listOf(false, true)) for (name in listOf("approval.txt", "approval.ansi")) {
            val p = checkNotNull(detectPrompt(real(dir, name), blocked, "claude")) { "$name $blocked" }
            assertEquals(listOf("No", "Yes"), p.choices.map { it.label })
            assertEquals("1", p.selected)
            assertEquals("Do you want to proceed?", p.shownParts().heading)
        }
        val p = checkNotNull(detectPrompt(real(dir, "approval.txt"), false, "claude"))
        assertEquals(listOf("enter"), menuKeys(p, p.choices[0]))
        assertEquals(listOf("down", "enter"), menuKeys(p, p.choices[1]))
        // "Tab to amend" is a hint, never an option, and no answer sends Tab.
        assertTrue(p.choices.none { it.label.contains("amend", ignoreCase = true) })
        assertTrue(p.choices.all { menuKeys(p, it)?.contains("tab") == false })
        // After Down at the computer: Yes is highlighted, and the answers start from there.
        for (name in listOf("approval-down.txt", "approval-down.ansi")) {
            val moved = checkNotNull(detectPrompt(real(dir, name), false, "claude")) { name }
            assertEquals("2", moved.selected)
            assertEquals(listOf("up", "enter"), menuKeys(moved, moved.choices[0]))
            assertEquals(listOf("enter"), menuKeys(moved, moved.choices[1]))
            assertTrue(cardAnswerStillFits(p, moved))
        }
        // The dialog's own hint row makes the pane need you whatever herdr says; after Esc it doesn't.
        assertNotNull(claudeNeedsYouReason(real(dir, "approval.txt")))
        assertNotNull(claudeNeedsYouReason(real(dir, "approval.ansi")))
        assertNull(detectPrompt(real(dir, "after.txt"), false, "claude"))
        assertNull(claudeNeedsYouReason(real(dir, "after.txt")))
        assertFalse(cardAnswerStillFits(p, detectPrompt(real(dir, "after.txt"), true, "claude")))
    }

    @Test fun theRealHelperListRowsAreTheOnesTakenOff() {
        // Captured (captured-2.1.329-helper-list): the composer with its mode row and helper list.
        for (name in listOf("composer.txt", "composer.ansi")) {
            val composer = real("captured-2.1.329-helper-list", name)
            assertEquals(composer, withoutClaudeChromeUnderDialog(composer))
            assertNull(detectPrompt(composer, false, "claude"))
            assertNull(claudeNeedsYouReason(composer))
        }
        // The same rows under the real approval come off exactly, and the card stays.
        val dialog = real("captured-2.1.329-auto-approval", "approval.txt").trimEnd()
        val listed = screen("approval-helper-list")
        assertEquals(dialog, withoutClaudeChromeUnderDialog(listed).trimEnd())
        assertEquals(listOf("No", "Yes"), checkNotNull(detectPrompt(listed, false, "claude")).choices.map { it.label })
        assertNotNull(claudeNeedsYouReason(listed))
    }

    @Test fun whoAsksIsReadFromEveryTitleTheProgramDraws() {
        for (t in listOf("Bash command · from the general-purpose agent", "Bash command · from a subagent",
                "Run command · from the \"nightly\" workflow", "Bash command · from a workflow", "Bash command · from a remote cloud agent",
                "Bash command · from the demo plugin", "Bash command · from a plugin")) {
            assertNotNull(t, CLAUDE_REQUEST_SOURCE.find(t))
            assertEquals(t, QKind.TEXT, questionParts(listOf(QLine(t), QLine("Do you want to proceed?", gapBefore = true))).blocks.first { it.text.contains("from ") }.kind)
        }
        assertNull(CLAUDE_REQUEST_SOURCE.find("Bash command"))
        assertNull(CLAUDE_REQUEST_SOURCE.find("Copy the file from the backup"))
    }
}
