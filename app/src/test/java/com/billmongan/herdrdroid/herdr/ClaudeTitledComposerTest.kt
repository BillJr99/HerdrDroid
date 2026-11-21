package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2.1.1 (placeholder audit, 2026-11-20): Claude Code 2.1.329 puts the session's title in the rule
 * above its input box ("──── … ──── Sample Title ─") and, while helpers run, a list of the session
 * and its background agents under the status row ("  ● main", "  ◯ general-purpose  …  7s"). The
 * draft finder knew neither, so a typed draft showed no "draft in pane" card.
 *
 * The screens are real captures of Claude Code 2.1.329 (134x48, tmux capture with styles) in a
 * throwaway container against a mock API, with the draft typed into that container's composer.
 * The placeholder screen is the empty capture with its composer row drawn as the live 2.1.329 pane
 * draws a prompt suggestion (faint, after "❯ "), with neutral text.
 */
class ClaudeTitledComposerTest {
    private fun screen(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("screens/claude/captured-2.1.329/$name")) { name }.readText()

    @Test fun anEmptyBoxUnderATitledRuleIsNoDraft() {
        val text = screen("composer-titled-list-empty.ansi")
        assertNull(composerDraftOf(text, "claude"))
        assertEquals("", inputLineOf(text, "claude"))
    }

    @Test fun aPlaceholderInTheTitledBoxIsNoDraft() {
        val text = screen("composer-titled-list-placeholder.ansi")
        assertNull(composerDraftOf(text, "claude"))
        assertEquals("", inputLineOf(text, "claude"))
    }

    @Test fun aTypedDraftInTheTitledBoxIsReadExactlyAndBounded() {
        val text = screen("composer-titled-list-typed.ansi")
        assertEquals(ComposerDraft("check the release notes for 2.1.2", bounded = true, menu = false), composerDraftOf(text, "claude"))
        assertEquals("check the release notes for 2.1.2", inputLineOf(text, "claude"))
        // A plain read of the same screen finds it too.
        assertEquals(ComposerDraft("check the release notes for 2.1.2", bounded = true, menu = false),
            composerDraftOf(screen("composer-titled-list-typed.txt"), "claude"))
    }

    @Test fun aTwoRowDraftKeepsBothRows() {
        val text = screen("composer-titled-list-two-rows.ansi")
        assertEquals(ComposerDraft("check the release notes for 2.1.2\nthen summarize them", bounded = true, menu = false),
            composerDraftOf(text, "claude"))
    }

    @Test fun aLongerHelperListStillLeavesTheBoxFound() {
        val text = screen("composer-titled-list-typed.ansi")
        val rows = text.trimEnd('\n').lines()
        val last = rows.last { it.contains("general-purpose") }
        val longer = (rows + List(8) { last }).joinToString("\n")
        assertEquals("check the release notes for 2.1.2", composerDraftOf(longer, "claude")?.text)
        assertEquals("check the release notes for 2.1.2", inputLineOf(longer, "claude"))
    }

    @Test fun aTitledRuleAloneOpensTheBoxAndAListWithoutABoxIsLeftAlone() {
        val rule = "─".repeat(60)
        val titled = "─".repeat(45) + " Sample Title ─"
        // Title in the opening rule, no list: found.
        assertEquals(ComposerDraft("fix it", true), composerDraftOf("● Done.\n\n$titled\n❯ fix it\n$rule\n  ? for shortcuts", "claude"))
        // List rows with no box rule above their footer are left alone: the lowest prompt row is
        // still too far from the bottom to be judged.
        val noBox = "● Done.\n❯ fix it\nsome output\n  ⏵⏵ auto mode on\n\n  ● main\n  ◯ general-purpose  a task  7s\n" +
            "  ◯ general-purpose  b task  7s\n  ◯ general-purpose  c task  7s\n  ◯ general-purpose  d task  7s"
        assertNull(composerDraftOf(noBox, "claude"))
    }
}
