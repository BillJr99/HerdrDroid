package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

/**
 * 2.2.8: agy's adversarial screen corpus (screens/<agent>/derived-2.2.8-corpus; screens/corpus-2.2.8/INDEX.md lists each case's expected
 * result): live dialogs are cards with their options, work and idle screens are no card, and a dialog
 * changed between the read and the send (a new dialog under it, a changed command beside a duplicate
 * box) never passes the guard, so the card is redrawn instead.
 */
class Corpus228Test {
    // The screens sit in the library's own layout, screens/<agent>/derived-2.2.8-corpus/, so the library tests
    // read them too; the index stays in screens/corpus-2.2.8/.
    private fun res(name: String): String {
        val path = if (name == "INDEX.md") "/screens/corpus-2.2.8/INDEX.md" else "/screens/${agent(name)}/derived-2.2.8-corpus/$name"
        return checkNotNull(javaClass.getResource(path)) { path }.readText()
    }
    private data class Case(val file: String, val expect: String)

    private fun cases(): List<Case> = res("INDEX.md").lines().mapNotNull { l ->
        Regex("""^- `([^`]+)` \| .*? \| \*\*(.+?)\*\* \|""").find(l)?.let { Case(it.groupValues[1], it.groupValues[2]) }
    }

    private fun agent(file: String) = file.substringAfter('-').substringBefore('-')
    // Spaces don't count: the corpus joins a wrapped URL with one, the app (rightly) without.
    private fun norm(s: String) = s.lowercase().replace(Regex("""\s+"""), "")

    /** The expected labels of a "CARD (...)" result, without their "N: " keys. */
    private fun labels(expect: String): List<String> {
        val body = expect.removePrefix("CARD (").removeSuffix(")")
        val parts = if (Regex("""^[a-z0-9]+: """).containsMatchIn(body)) body.split(Regex(""";\s*(?=[a-z0-9]+: )""")) else body.split(", ")
        // "a:approve" (a letter key, then its label) reads "approve" with key "a".
        return parts.map { it.replace(Regex("""^[a-z0-9]+: """), "").trim().replace(Regex("""^([a-z]):(\S.*)$"""), "$2$1") }
    }

    /** The screen [g] was read from, for a guard case: its named baseline, or [live] with the change undone. */
    private fun baseline(expect: String, live: String): String {
        Regex("""vs `([^`]+\.txt)`""").find(expect)?.let { return res(it.groupValues[1]) }
        return when {
            live.contains("rm  -rf build/tmp") -> live.replace("rm  -rf build/tmp", "rm -rf build/cache")
            live.contains("go test -race ./...") -> live.replace("go test -race ./...", "go test ./...")
            else -> error("no baseline for $expect")
        }
    }

    /**
     * Where the app reads a case differently from the index on purpose, and what it does instead:
     * the plan review offers approve, request changes, comment and quit, never "copy plan" (a:s:c:q,
     * detectLetterMenu); a command the card can't show whole (Hermes's hidden heredoc) is a
     * review-in-terminal card (agy's re-review: Codex's narrow heredoc, 033, is a whole card); OpenCode's "Type your own answer" is the
     * card's typed answer, not a button.
     */
    private val asDesigned = mapOf(
        "008-grok-plan-approval-bulleted-above-status.txt" to { p: Prompt -> p.choices.map { it.keys } == listOf("a", "s", "c", "q") },
        "046-hermes-approval-hidden-heredoc.txt" to { p: Prompt -> p.terminalReview },
        // Codex's per-commit review of fe63209 (LOW): and its third row stays the typed answer.
        "050-opencode-question-dialog.txt" to { p: Prompt ->
            p.choices.map { it.label } == listOf("SQLite (Recommended)", "Postgres") && p.openCodeCustom == "3" && p.openCodeQuestionFrame != null
        },
    )

    /** agy's corpus (018, 036, 053): an option's "(…)" tail wrapped onto its own row is part of its label. */
    @Test fun aWrappedLabelTailStaysOnItsOption() {
        val g = checkNotNull(detectPrompt(res("018-grok-narrow-wrapped-options-code.txt"), true, "grok"))
        val always = g.choices.single { it.label.startsWith("Yes, and don't ask again for anything") }
        assertEquals("Yes, and don't ask again for anything (always-approve mode)", always.label)
        // Never one tap (Bill, 2026-11-25: confirmed first, with its keys), and never sent when the pane cut the tail off.
        val ask = checkNotNull(approveAllAsk(g, always))
        assertNull(ask.reason)
        assertNotNull(ask.keys)
        assertNotNull(blockedChoiceReason(Choice("Yes, and don't ask again for anything", "1", false)))
        assertNull(blockedChoiceReason(Choice("Yes, and don't ask again for mkdir commands in /tmp/t", "2", false)))
        val a = checkNotNull(detectPrompt(res("036-agy-command-approval-wrapped-options.txt"), true, "agy"))
        assertTrue(a.choices.map { it.label }.toString(),
            a.choices.any { it.label == "Yes, and always allow for commands that start with 'pytest -q' (Persist to settings.json)" })
        val c = checkNotNull(detectPrompt(res("053-copilot-wrapped-option-and-code-block.txt"), true, "copilot"))
        assertTrue(c.choices.map { it.label }.toString(),
            c.choices.any { it.label == "Yes, and don't ask again for `python3` in this directory (/home/user/work/demo-repo)" })
    }

    /**
     * Codex's per-commit reviews of a1314cd and 5672ba8 (MEDIUM): a "(…)" row joins the option above it
     * only in the label's own column on every path (an open "(" included), counted as the terminal
     * draws it: tabs to the next multiple of 8, wide characters as two.
     */
    @Test fun aParenRowJoinsOnlyInTheLabelsColumnOnEveryPath() {
        val open = "Run the cleanup?\n❯ 1. Yes, proceed\n  2. Yes, and allow (for this folder\n     (/home/user/work))\n  3. No\n\nEsc to cancel\n"
        assertEquals("Yes, and allow (for this folder (/home/user/work))", checkNotNull(detectPrompt(open, true, null)).choices[1].label)
        val misaligned = open.replace("     (/home/user/work))", "(/home/user/work))")
        assertEquals("Yes, and allow (for this folder", checkNotNull(detectPrompt(misaligned, true, null)).choices[1].label)
        // A tab before the number: the label starts in column 11 as drawn, and so does its tail.
        val tabbed = "Run the cleanup?\n❯ 1. Yes, proceed\n\t2. Yes, and allow for this folder\n           (/home/user/work)\n  3. No\n\nEsc to cancel\n"
        assertEquals("Yes, and allow for this folder (/home/user/work)", checkNotNull(detectPrompt(tabbed, true, null)).choices[1].label)
        assertEquals(11, displayColumn("\t2. Yes", 4))
        // Wide characters count two columns.
        assertEquals(4, displayColumn("全部x", 2))
        assertEquals(3, displayColumn("\uD83D\uDCC1 x", 3))
    }

    /**
     * Codex's per-commit review of a1314cd (HIGH): on a Grok card, an option whose label the pane cut off
     * anywhere inside the always-approve one ("Yes", "Yes, a…", "Yes, and don't ask again f") is
     * ambiguous and never sent; Grok's own whole options stay usable.
     */
    @Test fun aGrokOptionCutOffAnywhereInsideAlwaysApproveIsNeverSent() {
        val full = "Yes, and don't ask again for anything (always-approve mode)"
        val screen = res("018-grok-narrow-wrapped-options-code.txt")
        val row = "  ┃  1 (○) Yes, and don't ask again for anything\n  ┃        (always-approve mode)\n"
        assertTrue(screen.contains(row))
        for (n in 1 until full.length) {
            for (cut in listOf(full.take(n).trimEnd(), full.take(n).trimEnd() + "…")) {
                if (cut.isEmpty() || cut == "…") continue
                val s = screen.replace(row, "  ┃  1 (○) $cut\n")
                val p = detectPrompt(s, true, "grok") ?: continue
                val one = p.choices.firstOrNull { it.keys == "1" } ?: continue
                assertNotNull("cut '$cut' -> '${one.label}'", blockedChoiceReason(one))
                for (c in p.choices.filter { it.keys == "2" || it.keys == "3" }) assertNull(c.label, blockedChoiceReason(c))
            }
        }
        // The same short labels on another agent's card are ordinary options.
        assertNull(blockedChoiceReason(Choice("Yes", "1", false)))
        assertNull(blockedChoiceReason(Choice("Yes, allow", "1", false)))
    }

    /**
     * Codex's per-commit review of fe63209 (MEDIUM): a "(…)" row joins the option above it only when it
     * starts in the label's own column, and a radio row never joins, even under an open "(".
     */
    @Test fun onlyTheLabelsOwnWrappedRowJoinsIt() {
        val base = "Run the cleanup?\n❯ 1. Yes, proceed\n  2. Yes, and allow for this folder\n     (/home/user/work)\n  3. No\n\nEsc to cancel\n"
        val p = checkNotNull(detectPrompt(base, true, null))
        assertEquals("Yes, and allow for this folder (/home/user/work)", p.choices[1].label)
        // A "(…)" row of other output, not in the label's column: its own row.
        val other = base.replace("     (/home/user/work)", "(exit 0)")
        val q = checkNotNull(detectPrompt(other, true, null))
        assertEquals("Yes, and allow for this folder", q.choices[1].label)
        // An open "(" never takes a radio row under it.
        val radio = "  ┃  ›  Run it\n  ┃  │ ls\n  ┃\n  ┃  1 (●) Yes, proceed\n  ┃  2 (○) No, reject (type\n" +
            "  ┃  (○) Yes, and don't ask again for anything (always-approve mode)\n  ┃\n\n  1/2:select  │  Tab:next option\n"
        val r = checkNotNull(detectPrompt(radio, true, "grok"))
        assertTrue(r.choices.map { it.label }.toString(), r.choices.none { it.label.contains("(○)") })
    }

    /**
     * Codex's per-commit review of fe63209 (HIGH): Grok's always-approve label cut off at any point from
     * "Yes, and" on is blocked; whole labels with their own scope stay usable.
     */
    @Test fun aCutOffAlwaysApproveLabelIsBlockedAtEveryPoint() {
        val full = "Yes, and don't ask again for anything (always-approve mode)"
        // Bill (2026-11-25): the whole label is confirmed first, never blocked; every cut-off start is.
        assertNull(blockedChoiceReason(Choice(full, "1", false)))
        assertTrue(approvesFromNowOn(Choice(full, "1", false)))
        for (n in "Yes, and".length until full.length) {
            val cut = full.take(n)
            assertNotNull(cut, blockedChoiceReason(Choice(cut, "1", false)))
            assertNotNull("$cut…", blockedChoiceReason(Choice("$cut…", "1", false)))
        }
        for (ok in listOf("Yes", "Yes, proceed", "No, reject (type to add feedback)", "Never allow: ls -la",
                "Yes, and don't ask again for mkdir commands in /tmp/t", "Yes, and don't ask again for these files (a)",
                "Yes, and don't ask again for commands that start with `pytest -q` (p)")) {
            assertNull(ok, blockedChoiceReason(Choice(ok, "2", false)))
        }
    }

    /**
     * Bill (2026-11-25): each agent's approve-from-now-on option asks "Approve everything from now on?"
     * first, with keys to send on Confirm; its ordinary options don't ask.
     */
    @Test fun everyAgentsAlwaysOptionAsksFirst() {
        val cases = listOf(
            "grok" to "/screens/grok/captured-2026-11-15b/scan-approval.txt",
            "claude" to "/screens/claude/captured-2026-11-13/bash-permission.txt",
            "codex" to "/screens/codex/captured-2026-11-13/run-command.txt",
            "copilot" to "/screens/copilot/derived-2.2.8-corpus/052-copilot-run-command-approval.txt",
            "opencode" to "/screens/opencode/derived-2.2.8-corpus/049-opencode-permission-shell.txt",
            "hermes" to "/screens/hermes/derived-2.2.8-corpus/045-hermes-dangerous-command-countdown.txt",
            "omp" to "/screens/omp/derived-2.2.8-corpus/041-omp-allow-tool-approval.txt",
        )
        for ((agent, path) in cases) {
            val p = checkNotNull(detectPrompt(stripAnsi(checkNotNull(javaClass.getResource(path)).readText()), true, agent)) { path }
            val always = p.choices.filter { grantsStandingPermission(it.label) }
            assertTrue("$agent ${p.choices.map { it.label }}", always.isNotEmpty())
            for (c in always) {
                val ask = checkNotNull(approveAllAsk(p, c)) { "$agent ${c.label}" }
                assertEquals(c.label, ask.label)
                if (ask.reason == null) assertEquals("$agent ${c.label}", menuKeys(p, c), ask.keys)
            }
            for (c in p.choices.filter { !grantsStandingPermission(it.label) && blockedChoiceReason(it) == null && !it.ambiguous })
                assertNull("$agent ${c.label}", approveAllAsk(p, c))
        }
    }

    /**
     * agy's corpus (054), found by the library's layout check: a new dialog under an older one's closed
     * frame asks only its own question, never with the older dialog's footer in it.
     */
    @Test fun aNewDialogUnderAnOlderFrameHasOnlyItsOwnQuestion() {
        val p = checkNotNull(detectPrompt(res("054-copilot-guard-new-dialog-below.txt"), true, "copilot"))
        assertEquals(listOf("Yes", "No, and tell Copilot what to do differently (Esc to stop)"), p.choices.map { it.label })
        assertEquals("Do you want to edit src/app.py?", p.question)
    }

    /** agy's re-review (MEDIUM): Codex's option wrapped before its key hint (033) is one option with its key. */
    @Test fun aCodexOptionWrappedBeforeItsKeyHintIsWhole() {
        val p = checkNotNull(detectPrompt(res("033-codex-narrow-wrapped-command-code.txt"), true, "codex"))
        assertFalse(p.terminalReview)
        assertEquals("Yes, and don't ask again for commands that start with `python3 -` in /home/user/work/demo-repo (p)", p.choices[1].label)
        assertEquals(listOf("y", "p", "esc"), p.choices.map { menuKeys(p, it)?.single() })
    }

    @Test fun theCorpusReadsAsItsIndexSays() {
        val all = cases()
        assertEquals(54, all.size)
        val bad = mutableListOf<String>()
        for (c in all) {
            val s = res(c.file)
            val a = agent(c.file)
            when {
                c.expect == "NO CARD" -> {
                    val p = runCatching { detectPrompt(stripAnsi(s), false, a) }.getOrNull()
                    if (p != null && (p.choices.isNotEmpty() || p.terminalReview)) bad += "${c.file}: a card ${p.choices.map { it.label }}"
                }
                c.expect.startsWith("CARD (") -> {
                    val p = runCatching { detectPrompt(stripAnsi(s), true, a) }.getOrNull()
                    if (p == null) { bad += "${c.file}: no card"; continue }
                    val ok = asDesigned[c.file]
                    if (ok != null) {
                        if (!ok(p)) bad += "${c.file}: not as designed ${p.choices.map { it.label }}"
                        continue
                    }
                    val got = p.choices.map { norm(it.label + " " + it.keys) }
                    val missing = labels(c.expect).filter { want -> got.none { it.contains(norm(want)) } }
                    if (labels(c.expect).size > 1 && missing.isNotEmpty()) bad += "${c.file}: missing $missing in ${p.choices.map { it.label }}"
                }
                c.expect.startsWith("GUARD MUST REFUSE") -> {
                    val base = baseline(c.expect, s)
                    val p = detectPrompt(stripAnsi(base), true, a)
                    val guard = cardGuardScreen(base, p)
                    if (!cardGuardScreenMatches(base, guard)) bad += "${c.file}: the baseline doesn't match itself"
                    if (cardGuardScreenMatches(s, guard)) bad += "${c.file}: the changed screen passed the guard ($guard)"
                }
                else -> bad += "${c.file}: unknown expectation ${c.expect}"
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
