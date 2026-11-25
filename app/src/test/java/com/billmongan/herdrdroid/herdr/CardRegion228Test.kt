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
                // A spinner and running times above the dialog, or a counter under it.
                for (t in listOf("⠋ Working… 12s ⇣1.2k [stop]", "⠙ Working… 13s ⇣1.3k [stop]")) {
                    if (start > 0) assertTrue("$path above", cardGuardScreenMatches(with(0, t), guard))
                    if (r.below > 0) assertTrue("$path below", cardGuardScreenMatches(with(rows.size - 1, t), guard))
                }
                // Whitespace inside a row is not a change.
                assertTrue("$path spaces", cardGuardScreenMatches(with(end, rows[end] + "   "), guard))
                // The last option and the question's first row are.
                assertFalse("$path option", cardGuardScreenMatches(with(end, rows[end] + " x"), guard))
                assertFalse("$path question", cardGuardScreenMatches(with(start, rows[start] + " x"), guard))
            }
            println("card regions proven for $agent: $proven")
            if (agent in setOf("claude", "codex", "hermes", "omp", "grok")) assertTrue("$agent has proven dialogs", proven > 0)
        }
    }

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

    private fun runCheck(python: Boolean, guardScreen: String, live: String, agent: String = "grok"): Int {
        val sh = listOf("/bin/sh", "/usr/bin/sh").map { File(it) }.firstOrNull { it.canExecute() }
        assumeTrue(sh != null)
        val dir = java.nio.file.Files.createTempDirectory("hd228-region").toFile()
        try {
            File(dir, "get.json").writeText(getJson)
            File(dir, "screen.txt").writeBytes(live.toByteArray())
            val herdr = File(dir, "herdr")
            herdr.writeText("#!/bin/sh\ncase \"\$2\" in get) /bin/cat '${dir.path}/get.json' ;; read) /bin/cat '${dir.path}/screen.txt' ;; *) : ;; esac\n")
            herdr.setExecutable(true)
            val g = CardGuard(cardOccupantDigest(JSONObject(getJson).getJSONObject("result").getJSONObject("pane")), cardGuardScreen(guardScreen, card(guardScreen, agent)))
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
                assertEquals("$name python=$python spaces", 0, runCheck(python, s, s.replace("Yes, proceed", "Yes,   proceed")))
                assertEquals("$name python=$python command", CARD_CHANGED_EXIT, runCheck(python, s, s.replace("ls -la build/cache", "ls -la build/cachf")))
                assertEquals("$name python=$python option", CARD_CHANGED_EXIT, runCheck(python, s, s.replace("Yes, proceed", "Yes, proceed now")))
                assertEquals("$name python=$python highlight", CARD_CHANGED_EXIT,
                    runCheck(python, s, s.replace("(●) Yes", "(○) Yes").replace("(○) No", "(●) No")))
                assertEquals("$name python=$python gone", CARD_CHANGED_EXIT, runCheck(python, s, s.lines().take(20).joinToString("\n")))
                assertEquals("$name python=$python moved", CARD_CHANGED_EXIT, runCheck(python, s, s + "\nA new row under it"))
            }
        }
    }
}
