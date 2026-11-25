package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ui.CardFormat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 2.2.8 (Bill: "grok has a question but keeps saying it changed and won't send", then "I don't like the
 * 'question changed, won't send' in general; just render the question and then give the buttons so that
 * can't happen"): the guard compares the dialog's own rows, so running times, spinners and counters
 * beside it never stop an answer, while a changed command, option or highlight still does.
 * Grok's fixtures are neutral rewrites in the layout of Grok 4.7's approval box.
 */
class CardRegion228Test {
    private fun grok(name: String) = checkNotNull(javaClass.getResource("/screens/grok/synthetic-2.2.8-approval/$name")) { name }.readText()
    private val clipped = "approval-clipped.ansi"
    private val whole = "approval-whole.ansi"

    private fun card(s: String, agent: String = "grok") = checkNotNull(detectPrompt(stripAnsi(s), true, agent)) { "no card" }

    /** Grok's status row, header counter and token counter as they tick. */
    private fun tick(s: String, n: Int) = s.replace("1m53s", "1m${53 + n}s").replace("7m33s", "7m${33 + n}s")
        .replace("⇣196k", "⇣${196 + n}k").replace("196k / 256k", "${196 + n}k / 256k").replace("◆ List the build cache…", if (n % 2 == 0) "◆ List the build cache…" else "◇ List the build cache…")

    @Test fun groksApprovalCardHasCleanOptionsAndNoOneTapApproveEverything() {
        for (name in listOf(clipped, whole)) {
            val p = card(grok(name))
            assertEquals(name, listOf("Yes, proceed", "No, reject (type to add feedback)",
                "Yes, and don't ask again for anything (always-approve mode)"), p.choices.map { it.label })
            assertEquals("2", p.selected)
            assertEquals(listOf("enter"), menuKeys(p, p.choices[0]))
            assertEquals(listOf("down", "enter"), menuKeys(p, p.choices[1]))
            // Shown, but never sent: approve-everything needs Bill.
            assertNotNull(blockedChoiceReason(p.choices[2]))
            assertNull(blockedChoiceReason(p.choices[0]))
            assertNull(blockedChoiceReason(p.choices[1]))
            assertNull(menuKeys(p, p.choices[2]))
            // No alert button for it either.
            val plan = com.billmongan.herdrdroid.notify.alertPlan(p)
            assertTrue(plan.buttons.none { it.choice?.keys == GROK_ALWAYS_APPROVE })
            assertFalse(p.question.contains("Ctrl+F"))
        }
        // The feedback hint reads as a quieter hint, not as part of the answer.
        assertEquals("No, reject" to " (type to add feedback)", CardFormat.labelParts("No, reject (type to add feedback)"))
        // The older layout's numbered always-approve row is blocked the same way.
        assertNotNull(blockedChoiceReason(Choice("Yes, and don't ask again for anything (always-approve mode)", "1", false)))
    }

    @Test fun aClippedCommandIsShownWholeBeforeItCanBeAnswered() {
        val cut = card(grok(clipped))
        assertTrue(cut.clipped)
        assertTrue(com.billmongan.herdrdroid.notify.alertPlan(cut).buttons.isEmpty())
        val all = card(grok(whole))
        assertFalse(all.clipped)
        assertTrue(all.question, all.question.contains("wc -l build/cache/index.txt"))
        assertTrue(all.question, all.question.startsWith("List the build cache: ls -la build/cache"))
        // The wrapped row joins the row above it.
        assertTrue(all.question, all.question.contains("du -sh build/cache ; echo sized_rc:\$?"))
    }

    @Test fun grokRunningRowsAreNotAChangeButTheDialogIs() {
        for (name in listOf(clipped, whole)) {
            val s = grok(name)
            val p = card(s)
            val guard = cardGuardScreen(s, p)
            assertTrue(guard, guard.startsWith("r:"))
            for (n in 1..3) {
                assertNotEquals(s, tick(s, n))
                assertTrue("$name tick $n", cardGuardScreenMatches(tick(s, n), guard))
            }
            assertFalse("command", cardGuardScreenMatches(s.replace("ls -la build/cache", "ls -la build/cachf"), guard))
            assertFalse("option", cardGuardScreenMatches(s.replace("Yes, proceed", "Yes, proceed now"), guard))
            assertFalse("highlight", cardGuardScreenMatches(s.replace("(●) Yes", "(○) Yes").replace("(○) No", "(●) No"), guard))
            assertFalse("a row under the box", cardGuardScreenMatches(s + "\nmore", guard))
            assertFalse("gone", cardGuardScreenMatches(s.lines().take(20).joinToString("\n"), guard))
        }
    }

    /** [s] with spaces after the end of the row holding [needle]. */
    private fun trailing(s: String, needle: String): String = s.split("\n").joinToString("\n") { if (stripAnsi(it).contains(needle)) "$it   " else it }

    /**
     * Grok's review 4 (HIGH): the region is the card's own option rows and the question block the parser
     * read, never rows found by a few letters. An older copy of the menu above, a wrapped option, or a
     * row near the options repeating the question's opening never moves the command out of the digest.
     */
    @Test fun theRegionIsTheParsersOwnRowsNotLookAlikes() {
        // A row just above the options repeating the title's opening: the command above it still counts.
        val s = grok(whole).replace("# the stamp file marks the last clean build", "# List the build cache, then clean it")
        val p = card(s)
        val guard = cardGuardScreen(s, p)
        assertTrue(guard, guard.startsWith("r:"))
        assertFalse("command above a look-alike row", cardGuardScreenMatches(s.replace("ls -la build/cache", "ls -la build/cachf"), guard))
        assertFalse("inner spaces", cardGuardScreenMatches(s.replace("ls -la build/cache", "ls  -la build/cache"), guard))
        assertTrue("trailing spaces", cardGuardScreenMatches(trailing(s, "Yes, proceed"), guard))
        // An older copy of the menu above the live one, and a wrapped option with a short first row.
        val older = "Old question?\n❯ 1. Ok\n  2. No,\n  and stop\n\n"
        val live = "Run the cleanup?\n  echo one\n❯ 1. Ok\n  2. No,\n  and stop\n\nEsc to cancel\n"
        val screen = older + live
        val q = checkNotNull(detectPrompt(screen, true, null))
        assertEquals(listOf("Ok", "No, and stop"), q.choices.map { it.label })
        val g = cardGuardScreen(screen, q)
        assertFalse("the live command", cardGuardScreenMatches(screen.replace("echo one", "echo two"), g))
        assertFalse("the live wrapped option", cardGuardScreenMatches(screen.replace("  and stop\n\nEsc", "  and go\n\nEsc"), g))
        assertTrue("the same screen", cardGuardScreenMatches(screen, g))
        // The question block the parser read is the region's top: its first row counts.
        assertFalse("the question", cardGuardScreenMatches(screen.replace("Run the cleanup?", "Run the cleanup now?"), g))
    }

    /**
     * 2.2.8 (Bill: "grok is stuck on a question I can't answer"): Grok's live thinking panel sits in the
     * same "┃" gutter as its approval box, with its running row and the message box under it. No card,
     * whatever herdr reports, and none for an older approval box scrolled above the work either.
     */
    @Test fun groksThinkingPanelIsNoQuestion() {
        for (name in listOf("thinking-panel.ansi", "thinking-under-stale-approval.ansi")) {
            val s = grok(name)
            for (blocked in listOf(false, true)) assertNull("$name blocked=$blocked", detectPrompt(stripAnsi(s), blocked, "grok"))
            assertTrue(name, grokWorkingBelowAnyQuestion(stripAnsi(s)))
        }
        // A waiting approval draws its running row above the box: still a card.
        for (name in listOf(clipped, whole, "approval-scope-clipped.ansi", "approval-scope-tall.ansi")) {
            assertFalse(name, grokWorkingBelowAnyQuestion(stripAnsi(grok(name))))
            assertNotNull(name, detectPrompt(stripAnsi(grok(name)), false, "grok"))
        }
    }

    // ---- Grok 4.7's newer box: a scope row, a fourth option, and a command taller than the screen.

    private val scoped = "approval-scope-clipped.ansi"
    private val tall = "approval-scope-tall.ansi"
    private fun tick2(s: String, n: Int) = s.replace("3m10s", "3m${10 + n}s").replace("8m38s", "8m${38 + n}s")
        .replace("⇣112k", "⇣${112 + n}k").replace("112k / 256k", "${112 + n}k / 256k")

    @Test fun grokNewerBoxHasNeverAllowAsAButtonAndNoScopeRowInTheQuestion() {
        for (name in listOf(scoped, tall)) {
            val p = card(grok(name))
            assertEquals(name, listOf("Yes, proceed", "No, reject (type to add feedback)", "Never allow: ls -la",
                "Yes, and don't ask again for anything (always-approve mode)"), p.choices.map { it.label })
            assertNull(blockedChoiceReason(p.choices[2]))
            assertNotNull(blockedChoiceReason(p.choices[3]))
            assertEquals(listOf("down", "down", "enter"), menuKeys(p, p.choices[2]))
            assertFalse(name, p.question.contains("widen scope"))
            val guard = cardGuardScreen(grok(name), p)
            assertTrue(name, guard.startsWith("r:"))
            assertFalse("$name never allow", cardGuardScreenMatches(grok(name).replace("Never allow: ls -la", "Never allow: ls -l"), guard))
        }
        assertTrue(card(grok(scoped)).clipped)
        val s = grok(scoped)
        val guard = cardGuardScreen(s, card(s))
        for (n in 1..3) assertTrue("ticking status row", cardGuardScreenMatches(tick2(s, n), guard))
    }

    /** After Ctrl+F the command can be taller than the screen, its title scrolled off: still a card that can be answered. */
    @Test fun grokExpandedCommandTallerThanTheScreenIsStillAnswerable() {
        val s = grok(tall)
        val p = card(s)
        assertFalse(p.clipped)
        assertFalse(p.terminalReview)
        assertTrue(p.question, p.question.contains("echo step-29"))
        assertTrue(p.question, p.question.contains("12 more rows"))
        val guard = cardGuardScreen(s, p)
        assertTrue(guard, guard.startsWith("r:"))
        assertTrue(cardGuardScreenMatches(s, guard))
        assertFalse(cardGuardScreenMatches(s.replace("echo step-29", "echo step-2X"), guard))
    }

    // ---- Codex's own update prompt.

    private fun codexUpdate() = checkNotNull(javaClass.getResource("/screens/codex/synthetic-2.2.8-update/update-prompt.ansi")).readText()

    @Test fun codexUpdatePromptIsACardWithTheExactCommand() {
        val s = codexUpdate()
        for (blocked in listOf(false, true)) {
            val p = checkNotNull(detectPrompt(stripAnsi(s), blocked, "codex")) { "blocked=$blocked" }
            assertFalse(p.terminalReview)
            assertEquals(listOf("1", "2", "3"), p.choices.map { it.keys })
            assertEquals("Update now (runs `sh -c 's=\$(curl -fsSL https://example.com/demo-cli/install.sh) && DEMO_NON_INTERACTIVE=1 eval \"\$s\"'`)", p.choices[0].label)
            assertEquals(listOf("Skip", "Skip until next version"), p.choices.drop(1).map { it.label })
            assertEquals("1", p.selected)
            assertEquals(listOf("enter"), menuKeys(p, p.choices[0]))
            assertEquals(listOf("down", "enter"), menuKeys(p, p.choices[1]))
            assertTrue(p.question, p.question.startsWith("Update available · 1.2.0 to 1.3.0"))
            val guard = cardGuardScreen(s, p)
            assertTrue(guard, guard.startsWith("r:"))
            assertFalse(cardGuardScreenMatches(s.replace("install.sh", "other.sh"), guard))
            assertFalse(cardGuardScreenMatches(s.replace("› 1. Update", "  1. Update").replace("  2. Skip\r", "› 2. Skip\r"), guard))
        }
        // Without its footer, or with something under it, it is not this prompt.
        assertNull(codexUpdatePrompt(stripAnsi(s).replace("enter continue · esc skip", "")))
        assertNull(codexUpdatePrompt(stripAnsi(s) + "\n› a new message"))
    }

    @Test fun bothChecksHandleGroksNewerBoxAndCodexsUpdatePrompt() {
        val hasPython = listOf("/usr/bin/python3", "/bin/python3").any { File(it).canExecute() }
        for (python in listOfNotNull(false, true.takeIf { hasPython })) {
            val s = grok(scoped)
            assertEquals("python=$python tick", 0, runCheck(python, s, tick2(s, 2)))
            assertEquals("python=$python never", CARD_CHANGED_EXIT, runCheck(python, s, s.replace("Never allow: ls -la", "Never allow: ls -l")))
            val t = grok(tall)
            assertEquals("python=$python tall", 0, runCheck(python, t, t))
            assertEquals("python=$python tall changed", CARD_CHANGED_EXIT, runCheck(python, t, t.replace("echo step-29", "echo step-2X")))
            val c = codexUpdate()
            assertEquals("python=$python codex", 0, runCheck(python, c, c, "codex"))
            assertEquals("python=$python codex changed", CARD_CHANGED_EXIT, runCheck(python, c, c.replace("install.sh", "other.sh"), "codex"))
        }
    }

    // ---- every agent: rows outside the dialog never count; the dialog's own rows always do.

    private fun fixtures(agent: String): List<Pair<String, String>> {
        val root = File(checkNotNull(javaClass.getResource("/screens/$agent")).toURI())
        return root.walkTopDown().filter { it.isFile && (it.name.endsWith(".txt") || it.name.endsWith(".ansi")) }
            .sortedBy { it.path }.map { it.path to it.readText() }.toList()
    }

    @Test fun everyAgentsTickingRowsOutsideTheDialogAreNotAChange() {
        for (agent in listOf("claude", "codex", "agy", "hermes", "omp", "grok", "copilot", "kiro", "muse", "pi", "vibe", "opencode")) {
            var proven = 0
            for ((path, s) in fixtures(agent)) {
                val p = runCatching { detectPrompt(stripAnsi(s), true, agent) }.getOrNull() ?: continue
                if (p.choices.isEmpty() || p.terminalReview) continue
                val r = cardRegion(s, p) ?: continue
                proven++
                val guard = cardGuardScreen(s, p)
                assertTrue(path, guard.startsWith("r:"))
                val rows = cardRows(s)
                val start = rows.size - r.below - r.count
                val end = rows.size - r.below - 1
                fun with(i: Int, row: String) = rows.toMutableList().also { it[i] = row }.joinToString("\n") + if (s.endsWith("\n")) "\n" else ""
                // A spinner and running times above the dialog.
                for (t in listOf("⠋ Working… 12s ⇣1.2k [stop]", "⠙ Working… 13s ⇣1.3k [stop]")) {
                    if (start > 0) assertTrue("$path above", cardGuardScreenMatches(with(0, t), guard))
                }
                // Codex's review 4B: under it, a counter ticking is no change, but a new row (a new dialog) is.
                if (r.below > 0) {
                    val tickGuard = cardGuardScreen(with(rows.size - 1, "⠋ Working… 12s ⇣1.2k [stop]"), p)
                    assertTrue("$path below", tickGuard.startsWith("r:"))
                    assertTrue("$path below tick", cardGuardScreenMatches(with(rows.size - 1, "⠙ Working… 13s ⇣1.3k [stop]"), tickGuard))
                    assertFalse("$path below new dialog", cardGuardScreenMatches(with(rows.size - 1, "❯ 1. Delete everything"), guard))
                }
                // The region is the parser's own rows, exactly (Codex's review 4B).
                assertEquals(path, p.dialogRows!!.rows, rows.subList(start, end + 1).map { cardRegionRow(stripAnsi(it)) })
                // Whitespace inside a row is not a change.
                // Spaces after a row's end are not a change (Grok's review 4: spaces inside it are).
                assertTrue("$path trailing spaces", cardGuardScreenMatches(with(end, rows[end] + "   "), guard))
                // The last option and the question's first row are.
                assertFalse("$path option", cardGuardScreenMatches(with(end, rows[end] + " x"), guard))
                assertFalse("$path question", cardGuardScreenMatches(with(start, rows[start] + " x"), guard))
            }
            println("card regions proven for $agent: $proven")
            // Oh My Pi's options are unnumbered: its screen is compared whole, with its own proven-box rules
            // (ompsRunningRowsStillTickFreelyInsideTheRegionRule, OmpApproval228Test).
            if (agent in setOf("claude", "codex", "hermes", "grok")) assertTrue("$agent has proven dialogs", proven > 0)
        }
    }

    /** Oh My Pi's unnumbered approval falls back to the whole screen, where its running rows still tick freely. */
    @Test fun ompsRunningRowsStillTickFreelyInsideTheRegionRule() {
        for (name in listOf("approval-three.ansi", "approval-reason-two.ansi")) {
            val s = checkNotNull(javaClass.getResource("/screens/omp/synthetic-2.2.8-approval/$name")).readText()
            val p = card(s, "omp")
            val guard = cardGuardScreen(s, p)
            val ticked = s.replace("⠙ 4m", "⠹ 5m").replace("⠴ 7m", "⠦ 8m").replace("elapsed 3m46s", "elapsed 3m52s").replace("elapsed 51.0s", "elapsed 57.5s")
            assertTrue(name, cardGuardScreenMatches(ticked, guard))
            assertFalse(name, cardGuardScreenMatches(s.replace("Command: ", "Command: echo hi; "), guard))
        }
    }

    // ---- the checks on the computer: Python and shell, byte for byte the same.

    private val getJson = """{"result":{"pane":{"pane_id":"p1","tab_id":"t1","workspace_id":"w1","agent":"grok","agent_status":"blocked","cwd":"/w","terminal_id":"term-1","scroll":{"offset_from_bottom":0}}}}"""

    private fun runCheck(python: Boolean, guardScreen: String, live: String, agent: String = "grok", prompt: Prompt? = null): Int {
        val sh = listOf("/bin/sh", "/usr/bin/sh").map { File(it) }.firstOrNull { it.canExecute() }
        assumeTrue(sh != null)
        val dir = java.nio.file.Files.createTempDirectory("hd228-region").toFile()
        try {
            File(dir, "get.json").writeText(getJson)
            File(dir, "screen.txt").writeBytes(live.toByteArray())
            val herdr = File(dir, "herdr")
            herdr.writeText("#!/bin/sh\ncase \"\$2\" in get) /bin/cat '${dir.path}/get.json' ;; read) /bin/cat '${dir.path}/screen.txt' ;; *) : ;; esac\n")
            herdr.setExecutable(true)
            val g = CardGuard(cardOccupantDigest(JSONObject(getJson).getJSONObject("result").getJSONObject("pane")), cardGuardScreen(guardScreen, prompt ?: card(guardScreen, agent)))
            assertTrue(g.screen.startsWith("r:"))
            val h = Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), herdr.path, Backend.TUI, null)
            val c = h.cardSendCommand("p1", CardInput.Keys(listOf("enter")), g, shellOnly = !python).let {
                if (python) it.replace(Regex("""; st=.*fi \)""", RegexOption.DOT_MATCHES_ALL), " )") else it
            }
            val p = ProcessBuilder(sh!!.path, "-c", c).apply { environment()["HOME"] = File(dir, "home").apply { mkdirs() }.path }.start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8); p.errorStream.readBytes()
            val exit = p.waitFor()
            // A region refusal says so (the refused line before exit 3), and only a pass reaches the keys.
            val lines = out.lines().map { it.trim() }
            if (exit == CARD_CHANGED_EXIT) assertTrue(out, CARD_REFUSED_MARK in lines && CARD_PASSED_MARK !in lines)
            if (exit == 0) assertTrue(out, CARD_PASSED_MARK in lines && CARD_REFUSED_MARK !in lines)
            return exit
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun bothChecksLetTheAnswerGoAsGroksTimersRunAndStopAChangedDialog() {
        val hasPython = listOf("/usr/bin/python3", "/bin/python3").any { File(it).canExecute() }
        for (python in listOfNotNull(false, true.takeIf { hasPython })) {
            for (name in listOf(clipped, whole)) {
                val s = grok(name)
                assertEquals("$name python=$python same", 0, runCheck(python, s, s))
                for (n in 1..2) assertEquals("$name python=$python tick", 0, runCheck(python, s, tick(s, n)))
                assertEquals("$name python=$python trailing spaces", 0, runCheck(python, s, trailing(s, "Yes, proceed")))
                // Grok's review 4: spaces inside a command or an option are part of it.
                assertEquals("$name python=$python inner spaces", CARD_CHANGED_EXIT, runCheck(python, s, s.replace("ls -la build/cache", "ls  -la build/cache")))
                assertEquals("$name python=$python option spaces", CARD_CHANGED_EXIT, runCheck(python, s, s.replace("Yes, proceed", "Yes,   proceed")))
                assertEquals("$name python=$python command", CARD_CHANGED_EXIT, runCheck(python, s, s.replace("ls -la build/cache", "ls -la build/cachf")))
                assertEquals("$name python=$python option", CARD_CHANGED_EXIT, runCheck(python, s, s.replace("Yes, proceed", "Yes, proceed now")))
                assertEquals("$name python=$python highlight", CARD_CHANGED_EXIT,
                    runCheck(python, s, s.replace("(●) Yes", "(○) Yes").replace("(○) No", "(●) No")))
                assertEquals("$name python=$python gone", CARD_CHANGED_EXIT, runCheck(python, s, s.lines().take(20).joinToString("\n")))
                assertEquals("$name python=$python moved", CARD_CHANGED_EXIT, runCheck(python, s, s + "\nA new row under it"))
            }
        }
    }
    // ---- agy's GROKTHINK review (on 8edb9ec) and Codex's review 4B (the whole branch).

    private fun plain(name: String) = stripAnsi(grok(name))
    private val narrowStatus = Regex("""⠧ Thinking… 44s\s+22m58s ⇣121k \[stop]""")
    private val thoughtRow = "its own files, and the option rows are numbered in the order the menu shows them."

    /** agy HIGH: a plan review or folder trust prompt under Grok's running row is still a question. */
    @Test fun aPlanApprovalOrFolderTrustUnderAGrokRunningRowIsStillACard() {
        val plan = checkNotNull(javaClass.getResource("/screens/grok/derived-1.4.9/0306-plan-approval.txt")).readText()
            .replace(Regex("""(?m)^  \d\. """), "  - ")
        val screen = "   ⠧ Planning… 12s   3m02s ⇣4.1k [stop]\n\n  ┃  ◆ Thinking…\n  ┃  1. read the app first\n\n$plan"
        assertFalse(grokWorkingBelowAnyQuestion(screen))
        val p = checkNotNull(detectPrompt(screen, true, "grok")) { "plan approval" }
        assertTrue(p.choices.map { it.keys }.toString(), p.choices.map { it.keys }.containsAll(listOf("a", "s", "c", "q")))
        val trust = "   ⠧ Starting… 1s ⇣0 [stop]\n\n" +
            checkNotNull(javaClass.getResource("/screens/grok/derived-1.4.9/0307-trust-this-folder-startup.txt")).readText()
        assertFalse(grokWorkingBelowAnyQuestion(trust))
        val t = checkNotNull(detectPrompt(trust, true, "grok")) { "folder trust" }
        assertEquals(listOf("y", "n"), t.choices.map { it.keys })
    }

    /**
     * Codex's per-commit review of 7ce9dd0 (MEDIUM): only a whole plan review or folder trust prompt at
     * the screen's bottom counts. Prose that mentions their keys, or a stale prompt with Grok's running
     * output under it, is Grok at work: no card.
     */
    @Test fun aMentionOfPlanOrTrustKeysIsNotAQuestion() {
        val running = "   ⠧ Planning… 12s   3m02s ⇣4.1k [stop]\n\n"
        val quoted = listOf(
            "  Press a:approve or s:request changes when the plan looks right.",
            "  a:approve and s:request changes are the keys; q:quit plan leaves it.",
            "  The prompt says Enter or y to trust, then n or Esc to quit.",
            "  Enter or y to trust · n or Esc to quit",
        )
        for (q in quoted) {
            val s = running + q + "\n"
            assertTrue(q, grokWorkingBelowAnyQuestion(s))
            assertNull(q, detectPrompt(s, true, "grok"))
        }
        val plan = checkNotNull(javaClass.getResource("/screens/grok/derived-1.4.9/0306-plan-approval.txt")).readText()
        val trust = checkNotNull(javaClass.getResource("/screens/grok/derived-1.4.9/0307-trust-this-folder-startup.txt")).readText()
        for ((what, stale) in listOf("plan" to plan, "trust" to trust)) {
            val s = stale.trimEnd() + "\n\n   ⠧ Working… 3s   0s ⇣1.1k [stop]\n"
            assertTrue(what, grokWorkingBelowAnyQuestion(s))
            assertNull(what, detectPrompt(s, true, "grok"))
            assertEquals(what, -1, grokPlanOrTrustBottom(s.lines()))
            assertTrue(what, grokPlanOrTrustBottom(stale.lines()) >= 0)
        }
    }

    /** agy MEDIUM: a draft in the message box is not a question row, numbered or saying "Opt in". */
    @Test fun aDraftInGroksMessageBoxIsNotAQuestionRow() {
        for (draft in listOf("1. check foo", "Opt in to the cleanup")) {
            val s = plain("thinking-panel.ansi").replaceFirst(Regex("""│ ❯ {${draft.length + 1}}"""), "│ ❯ $draft ")
            assertTrue(draft, s.contains("│ ❯ $draft"))
            assertTrue(draft, grokWorkingBelowAnyQuestion(s))
            for (blocked in listOf(false, true)) assertNull("$draft blocked=$blocked", detectPrompt(s, blocked, "grok"))
        }
    }

    /**
     * Codex's per-commit review of 573e426 (MEDIUM): the card parser and the running filter take the same
     * whole plan footer; a partial one is no card, with or without a running row above it.
     */
    @Test fun aPartialPlanFooterIsNoCard() {
        val plan = checkNotNull(javaClass.getResource("/screens/grok/derived-1.4.9/0306-plan-approval.txt")).readText()
        val whole = plan.lines().last { it.isNotBlank() }
        assertTrue(GROK_PLAN_FOOTER_ROW.matches(whole))
        for (part in listOf("  a:approve  │  s:request changes", "  a:approve  │  c:comment  │  q:quit plan", "  a:approve  │  s:request changes  │  c:comment")) {
            val s = plan.replace(whole, part)
            for (running in listOf("", "   ⠧ Planning… 12s   3m02s ⇣4.1k [stop]\n\n")) {
                assertNull("$part running=${running.isNotEmpty()}", detectPrompt(running + s, true, "grok"))
                assertNull(part, detectLetterMenu(running + s, true))
            }
        }
        assertNotNull(detectLetterMenu(plan, true))
    }

    /**
     * Codex's per-commit review of 573e426 (MEDIUM): a live folder trust prompt with one more row of its
     * own UI under its hint (a footer) is still a card under a running row; running output there is not.
     */
    @Test fun aTrustPromptWithAFooterUnderItsHintIsStillACard() {
        val trust = checkNotNull(javaClass.getResource("/screens/grok/derived-1.4.9/0307-trust-this-folder-startup.txt")).readText().trimEnd()
        val running = "   ⠧ Starting… 1s ⇣0 [stop]\n\n"
        for (foot in listOf("  ? for shortcuts", "  Grok 4.7 · ~/work/demo")) {
            val s = "$running$trust\n\n$foot\n"
            assertFalse(foot, grokWorkingBelowAnyQuestion(s))
            assertEquals(foot, listOf("y", "n"), checkNotNull(detectPrompt(s, true, "grok")) { foot }.choices.map { it.keys })
        }
        for (after in listOf("  ⠋ Working… 2s [stop]", "  ◈ Explored 3 files", "  extra row one\n  extra row two")) {
            val s = "$running$trust\n\n$after\n"
            assertTrue(after, grokWorkingBelowAnyQuestion(s))
        }
    }

    /**
     * Codex's per-commit review of 7d6950b (MEDIUM): a message box drawn without its bottom edge ends at
     * the first row outside its frame, so a live folder trust prompt under it is still a card.
     */
    @Test fun aMessageBoxWithoutItsBottomEdgeHidesNothingUnderIt() {
        val trust = checkNotNull(javaClass.getResource("/screens/grok/derived-1.4.9/0307-trust-this-folder-startup.txt")).readText()
        val rows = plain("thinking-panel.ansi").trimEnd().lines()
        val bottom = rows.indexOfLast { it.trimStart().startsWith("╰") }
        assertTrue(bottom > 0)
        // The box's bottom edge and everything under it gone, then the trust prompt.
        val s = rows.take(bottom).joinToString("\n") + "\n\n" + trust
        assertFalse(grokWorkingBelowAnyQuestion(s))
        assertEquals(listOf("y", "n"), checkNotNull(detectPrompt(s, true, "grok")).choices.map { it.keys })
        // A live approval box's own rows under it (radio rows, the select footer) count too.
        val box = "  ┃  ›  List the build cache\n  ┃  │ ls -la build/cache\n  ┃\n  ┃  1 (●) Yes, proceed\n" +
            "  ┃  2 (○) No, reject (type to add feedback)\n\n  1/2:select  │  Tab:next option  │  Esc:scrollback\n"
        assertFalse(grokWorkingBelowAnyQuestion(rows.take(bottom).joinToString("\n") + "\n\n" + box))
        // Codex's per-commit review of 63560fe (LOW): right after the box's last row, end to end: the
        // approval is a card with its own options and question.
        val tight = rows.take(bottom).joinToString("\n") + "\n" + box
        assertFalse(grokWorkingBelowAnyQuestion(tight))
        val card = checkNotNull(detectPrompt(tight, true, "grok")) { "approval under an open message box" }
        assertEquals(listOf("Yes, proceed", "No, reject (type to add feedback)"), card.choices.map { it.label })
        assertTrue(card.question, card.question.contains("ls -la build/cache"))
        // With its bottom edge, a draft inside it is still no question.
        val draft = rows.toMutableList().also { it.add(bottom, "  │   2. Opt in to the cleanup   │") }.joinToString("\n")
        assertTrue(grokWorkingBelowAnyQuestion(draft))
    }

    /**
     * agy MEDIUM: a numbered point or "Opt in" in the thinking panel's own text is Grok's thought, with
     * a narrow running row (fewer than 3 spaces, no "⇣" yet) or with none drawn under the panel.
     */
    @Test fun numberedPointsInGroksThoughtAreNotQuestionRows() {
        val base = plain("thinking-panel.ansi").replace(thoughtRow, "1. Opt in to the cleanup, then list the option rows.")
        assertTrue(base.contains("┃  1. Opt in"))
        val narrow = narrowStatus.replace(base, "⠧ Thinking… 44s 22m58s [stop]")
        assertNotEquals(base, narrow)
        val none = narrowStatus.replace(base, "")
        for ((what, s) in listOf("status row" to base, "narrow status row" to narrow, "no status row" to none)) {
            assertTrue(what, grokWorkingBelowAnyQuestion(s))
            for (blocked in listOf(false, true)) assertNull("$what blocked=$blocked", detectPrompt(s, blocked, "grok"))
        }
        // The privacy banner's own rows still count as a question (its buttons, its radio rows).
        assertFalse(grokWorkingBelowAnyQuestion("$none\n  [Opt out]  [Opt in]"))
        assertFalse(grokWorkingBelowAnyQuestion("$none\n  ○ Opt in"))
    }

    /**
     * Codex's per-commit review of 7d6950b (MEDIUM): the thinking panel ends where a dialog starts in the
     * same "┃" gutter: a highlighted option row, a box title, or a numbered run with a select footer under it.
     */
    @Test fun aLiveMenuInTheThinkingPanelsGutterIsStillAQuestion() {
        val base = narrowStatus.replace(plain("thinking-panel.ansi"), "")
        assertTrue(base.contains(thoughtRow))
        val marked = base.replace(thoughtRow, "$thoughtRow\n  ┃  ❯ 1. Yes, proceed\n  ┃    2. No, reject")
        assertFalse(grokWorkingBelowAnyQuestion(marked))
        val titled = base.replace(thoughtRow, "$thoughtRow\n  ┃  ›  List the build cache\n  ┃  │ ls -la build/cache\n  ┃  1. Yes, proceed\n  ┃  2. No, reject")
        assertFalse(grokWorkingBelowAnyQuestion(titled))
        val footed = base.replace(thoughtRow, "$thoughtRow\n  ┃  1. Yes, proceed\n  ┃  2. No, reject") +
            "\n  1/2:select  │  Tab:next option  │  Esc:scrollback\n"
        assertFalse(grokWorkingBelowAnyQuestion(footed))
        // Codex's per-commit review of 894e44b (LOW): each is a card end to end, with its own options and
        // question, never the thought above it.
        // As Grok draws a waiting menu: its footer under it, no message box.
        val top = base.substring(0, base.indexOf(thoughtRow) + thoughtRow.length)
        val foot = "\n  ┃\n\n  1/2:select  │  Tab:next option  │  Esc:scrollback\n"
        val asked = "$top\n  ┃\n  ┃  Run the cleanup?\n  ┃  ❯ 1. Yes, proceed\n  ┃    2. No, reject$foot"
        val boxed = "$top\n  ┃  ›  List the build cache\n  ┃  │ ls -la build/cache\n  ┃  ❯ 1. Yes, proceed\n  ┃    2. No, reject$foot"
        assertFalse(grokWorkingBelowAnyQuestion(asked))
        assertFalse(grokWorkingBelowAnyQuestion(boxed))
        for ((what, s) in listOf("asked" to asked, "titled" to boxed)) {
            val p = checkNotNull(detectPrompt(s, true, "grok")) { what }
            assertEquals(what, listOf("Yes, proceed", "No, reject"), p.choices.map { it.label })
            assertFalse("$what: ${p.question}", p.question.contains("scripts lock"))
        }
        assertTrue(checkNotNull(detectPrompt(asked, true, "grok")).question.contains("Run the cleanup?"))
        assertTrue(checkNotNull(detectPrompt(boxed, true, "grok")).question.contains("List the build cache"))
        // Codex's per-commit review of 894e44b (MEDIUM): one quoted highlighted row in the thought is
        // thought, and a footer detached from the panel by other output doesn't make its list a menu.
        val quoted = base.replace(thoughtRow, "$thoughtRow\n  ┃  ❯ 1. Yes, proceed is what the box will show.\n  ┃  Then I run it.")
        assertTrue(grokWorkingBelowAnyQuestion(quoted))
        val detached = base.replace(thoughtRow, "$thoughtRow\n  ┃  1. Check the lock.\n  ┃  2. Run the cleanup.") +
            "\n     ◈ Ran 1 command\n\n  1/2:select  │  Tab:next option\n\n   ⠧ Thinking… 50s [stop]\n"
        assertTrue(grokWorkingBelowAnyQuestion(detached))
        // Numbered points in the thought, with no footer or highlight, are still thought.
        val points = base.replace(thoughtRow, "$thoughtRow\n  ┃  1. Check the lock.\n  ┃  2. Run the cleanup.")
        assertTrue(grokWorkingBelowAnyQuestion(points))
    }

    /** agy LOW: the thinking panel's title with three dots, as a narrow or plain terminal draws it. */
    @Test fun groksThinkingPanelWithThreeDotsIsNoQuestion() {
        val s = narrowStatus.replace(plain("thinking-under-stale-approval.ansi"), "").replace("◆ Thinking…", "◆ Thinking...")
        assertTrue(s.contains("◆ Thinking..."))
        assertTrue(grokWorkingBelowAnyQuestion(s))
        assertNull(detectPrompt(s, true, "grok"))
    }

    private fun withRow(s: String, i: Int, row: String): String {
        val rows = cardRows(s).toMutableList()
        rows[i] = row
        return rows.joinToString("\n") + if (s.endsWith("\n")) "\n" else ""
    }

    /** The last row under the dialog that holds text, in [s]. */
    private fun lastUnder(s: String, p: Prompt): Int {
        val r = checkNotNull(cardRegion(s, p))
        val rows = cardRows(s)
        return (rows.size - r.below until rows.size).last { stripAnsi(rows[it]).isNotBlank() }
    }

    /**
     * Codex 4B HIGH: a new dialog drawn under the old one while the old rows stay put takes the keys, so
     * the rows under the dialog count, by their shape: a counter or spinner ticking there is no change.
     */
    @Test fun aNewDialogUnderTheOldOneIsAChange() {
        for (name in listOf(whole, scoped)) {
            val s = grok(name)
            val p = card(s)
            val guard = cardGuardScreen(s, p)
            assertTrue(guard, guard.startsWith("r:"))
            val i = lastUnder(s, p)
            assertFalse(name, cardGuardScreenMatches(withRow(s, i, "  ❯ 1. Delete the build cache"), guard))
            assertFalse(name, cardGuardScreenMatches(withRow(s, i, stripAnsi(cardRows(s)[i]) + " │ y:approve all"), guard))
            // Codex's per-commit review of 8c0125d (HIGH): every other row under the dialog counts exactly:
            // a shortcut's number, or a highlight shown only in colour.
            val row = cardRows(s)[i]
            assertFalse(name, cardGuardScreenMatches(withRow(s, i, row.replaceFirst(Regex("""m\d\u001b"""), "m9\u001b")), guard))
            assertFalse(name, cardGuardScreenMatches(withRow(s, i, row.replaceFirst("Tab:next option", "\u001b[7mTab:next option\u001b[27m")), guard))
            // A running status row under it ticks freely (the card as read before it appeared).
            val last = cardRows(s).size - 1
            val a = withRow(s, last, "  ⠋ Working… 12s · ⇣1.2k [stop]")
            val b = withRow(s, last, "  ⠙ Working… 13s · ⇣1.9k [stop]")
            val ga = cardGuardScreen(a, p)
            assertTrue(name, ga.startsWith("r:"))
            assertTrue(name, cardGuardScreenMatches(b, ga))
        }
    }

    @Test fun bothChecksRefuseANewDialogUnderTheOldOne() {
        val hasPython = listOf("/usr/bin/python3", "/bin/python3").any { File(it).canExecute() }
        for (python in listOfNotNull(false, true.takeIf { hasPython })) {
            for (name in listOf(whole, scoped)) {
                val s = grok(name)
                val i = lastUnder(s, card(s))
                assertEquals("$name python=$python new dialog", CARD_CHANGED_EXIT, runCheck(python, s, withRow(s, i, "  ❯ 1. Delete the build cache")))
                val last = cardRows(s).size - 1
                val a = withRow(s, last, "  ⠋ Working… 12s · ⇣1.2k [stop]")
                val b = withRow(s, last, "  ⠙ Working… 13s · ⇣1.9k [stop]")
                assertEquals("$name python=$python tick under", 0, runCheck(python, a, b, prompt = card(s)))
                // Codex's per-commit review of 8c0125d: a non-status row under the dialog counts exactly.
                val row = cardRows(s)[i]
                assertEquals("$name python=$python shortcut", CARD_CHANGED_EXIT, runCheck(python, s, withRow(s, i, row.replaceFirst(Regex("""m\d\u001b"""), "m9\u001b"))))
                assertEquals("$name python=$python colour", CARD_CHANGED_EXIT,
                    runCheck(python, s, withRow(s, i, row.replaceFirst("Tab:next option", "\u001b[7mTab:next option\u001b[27m"))))
            }
            val c = codexUpdate()
            val foot = cardRows(c).indexOfLast { stripAnsi(it).contains("enter continue") }
            assertTrue(foot > 0)
            assertEquals("python=$python codex row under", CARD_CHANGED_EXIT,
                runCheck(python, c, withRow(c, foot, stripAnsi(cardRows(c)[foot]) + " · y yes to all"), "codex"))
        }
    }

    /**
     * Codex's per-commit review of dce009c (HIGH): a status row is each agent's own running row, never any
     * row that starts with a glyph; every other row under the dialog counts exactly, in the app and both checks.
     */
    @Test fun onlyEachAgentsOwnRunningRowIsAStatusRow() {
        val status = listOf(
            "⠧ Thinking… 44s   22m58s ⇣121k [stop]", "◆ Check SSH to devbox in batch mode… 59s   2m47s ⇣61.9k [↓][stop]",
            "⠸ Run cleanup check… 3s  [stop]", "✻ Working… (14s · esc to interrupt)", "◇ Calling tools (17m 14s · esc to interrupt)",
            "• Working (12s • esc to interrupt)", "── ⠹ Working… · 1m 2s ──────── Check the calendar setup ─",
        )
        for (r in status) assertTrue(r, CARD_STATUS_ROW.containsMatchIn(r))
        val notStatus = listOf(
            "✻ 1. Yes, delete it (5s)", "⠋ 2. Allow for 5m", "· Step 2 of 5 · 3m", "◆ Ran command · Run the unit tests · ✓ · 41s · ctrl+o",
            "✻ Worked for 0s · done 10:53 PM", "• 1. Allow once (12s)", "⠋ Delete in 5s? (y/n)", "◆ Thought for 3.4s",
        )
        for (r in notStatus) {
            assertFalse(r, CARD_STATUS_ROW.containsMatchIn(r))
            assertEquals(r, r, cardUnderRow(r))
        }
        val s = grok(whole)
        val p = card(s)
        val last = cardRows(s).size - 1
        val a = withRow(s, last, "  ✻ 1. Yes, delete it (5s)")
        val b = withRow(s, last, "  ✻ 2. Yes, delete it (5s)")
        val ga = cardGuardScreen(a, p)
        assertFalse(cardGuardScreenMatches(b, ga))
        val hasPython = listOf("/usr/bin/python3", "/bin/python3").any { File(it).canExecute() }
        for (python in listOfNotNull(false, true.takeIf { hasPython })) {
            assertEquals("python=$python", CARD_CHANGED_EXIT, runCheck(python, a, b, prompt = p))
            assertEquals("python=$python same", 0, runCheck(python, a, a, prompt = p))
        }
    }

    /** Running status rows that grow a digit or a unit: before and after (Codex's per-commit review of 8c0125d). */
    private val rollovers = listOf(
        "  ⠋ Working… 9s · ⇣9k [stop]" to "  ⠙ Working… 10s · ⇣10.2k [stop]",
        "  ⠋ Working… 59s [stop]" to "  ⠙ Working… 1m 0s [stop]",
        "  ✻ Running… (59m59s · ↓ 999k tokens · esc to interrupt)" to "  ✶ Running… (1h 0m 0s · ↓ 998k tokens · esc to interrupt)",
        "  ⠧ Thinking… 0.9s   9m59s ⇣9.9k [↓][stop]" to "  ⠏ Thinking… 12.5s   10m0s ⇣10k [↓][stop]",
        "── ⠹ Working… · 59s ──────── Check the build ─" to "── ⠼ Working… · 1m 0s ──────── Check the build ─",
    )

    /**
     * Codex's per-commit review of 8c0125d (MEDIUM): a running status row's whole time and counter are
     * read as tokens, so "9s" to "10s" or "59s" to "1m 0s" is no change, in the app and in both checks;
     * its words still are.
     */
    @Test fun aStatusRowsTimeRollingOverIsNoChange() {
        val s = grok(whole)
        val p = card(s)
        val last = cardRows(s).size - 1
        for ((a, b) in rollovers) {
            val ga = cardGuardScreen(withRow(s, last, a), p)
            assertTrue(a, ga.startsWith("r:"))
            assertTrue("$a -> $b", cardGuardScreenMatches(withRow(s, last, b), ga))
            assertFalse("$a words", cardGuardScreenMatches(withRow(s, last, "$b now deleting"), ga))
        }
        assertEquals(cardUnderRow("⠋ Working… 59s [stop]"), cardUnderRow("⠙ Working… 1m 0s [stop]"))
        // Not a status row (no spinner first): exact.
        assertNotEquals(cardUnderRow("Working… 59s"), cardUnderRow("Working… 1m 0s"))
        val hasPython = listOf("/usr/bin/python3", "/bin/python3").any { File(it).canExecute() }
        for (python in listOfNotNull(false, true.takeIf { hasPython })) {
            for ((a, b) in rollovers) {
                assertEquals("python=$python $a -> $b", 0, runCheck(python, withRow(s, last, a), withRow(s, last, b), prompt = p))
                assertEquals("python=$python $a words", CARD_CHANGED_EXIT,
                    runCheck(python, withRow(s, last, a), withRow(s, last, "$b now deleting"), prompt = p))
            }
        }
    }

    /**
     * Codex 4B MEDIUM: the guard takes the parser's own rows. When they are on the screen twice (an older
     * copy of the same dialog), which one the parser read isn't proven: the whole screen is compared.
     */
    @Test fun theGuardTakesTheParsersOwnRowsOrTheWholeScreen() {
        val live = "Run the cleanup?\n  echo one\n❯ 1. Ok\n  2. No,\n  and stop\n\nEsc to cancel\n"
        val q = checkNotNull(detectPrompt(live, true, null))
        val d = checkNotNull(q.dialogRows)
        assertEquals(listOf("Run the cleanup?", "echo one", "❯ 1. Ok", "2. No,", "and stop"), d.rows)
        assertEquals(listOf(2, 3), d.options)
        val r = checkNotNull(cardRegion(live, q))
        assertEquals(5, r.count)
        // The parser's rows twice on the screen (an older copy of the same dialog): not proven, so the whole screen.
        val twice = live.replace("\nEsc to cancel\n", "\n") + "\n" + live
        assertNull(cardRegion(twice, q))
        assertFalse(cardGuardScreen(twice, q).startsWith("r:"))
        assertTrue(cardGuardScreenMatches(twice, cardGuardScreen(twice, q)))
        // Rows the parser read that are no longer on the screen: whole screen.
        assertNull(cardRegion(live.replace("echo one", "echo two"), q))
        // A card whose option keys don't match the rows: whole screen.
        assertNull(cardRegion(live, q.copy(choices = q.choices.reversed()).withSpanOf(q)))
        // Codex's update prompt: its wrapped first option and every option row, as the parser read them.
        val c = stripAnsi(codexUpdate())
        val cp = checkNotNull(detectPrompt(c, true, "codex"))
        assertEquals(cp.choices.size, checkNotNull(cp.dialogRows).options.size)
        assertNotNull(cardRegion(codexUpdate(), cp))
    }
}
