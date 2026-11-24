package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 2.2.8 (Bill: "there's a question for which I cannot send the answer" and "OMP popped up another question
 * it won't let me answer and got stuck"): Oh My Pi's tool approval box.
 */
class OmpApproval228Test {
    private fun screen(name: String) = checkNotNull(javaClass.getResource("/screens/omp/synthetic-2.2.8-approval/$name")) { name }.readText()
    private val three = "approval-three.ansi"
    private val two = "approval-reason-two.ansi"

    private fun withoutChrome(s: String): String = s.lines().filterNot { l ->
        val p = stripAnsi(l).trim()
        p.startsWith("⎋") || p.contains("▶─") || p == "╰─"
    }.joinToString("\n")

    @Test fun theCardShowsOnlyTheOptionsWhateverHerdrReports() {
        for ((name, labels) in listOf(three to listOf("Approve", "Approve bash for this session", "Deny"), two to listOf("Approve", "Deny"))) {
            for (s in listOf(screen(name), withoutChrome(screen(name)))) {
                // herdr may call Oh My Pi working while its tool waits: the box itself says it waits.
                for (blocked in listOf(false, true)) {
                    val p = checkNotNull(detectPrompt(s, blocked, "omp")) { "$name blocked=$blocked" }
                    assertEquals(name, labels + OMP_STEER_LABEL, p.choices.map { it.label })
                    assertTrue(name, p.question.startsWith("Command: ") || p.shownParts().blocks.any { it.text.contains("Command: ") })
                    assertEquals("1", p.selected)
                    assertFalse(p.terminalReview)
                    assertTrue(isTypedAnswerChoice(OMP_STEER_LABEL))
                    assertTrue("$name proven", questionRead(s, p.forCard(), "omp", null, blocked).topped)
                }
            }
        }
        val p = checkNotNull(detectPrompt(screen(three), false, "omp"))
        assertEquals(listOf("down", "down", "enter"), menuKeys(p, p.choices[2]))
    }

    /** The root cause: the spinner and running times redrawn every second changed the screen digest. */
    @Test fun theSpinnerAndRunningTimesAreNotAChange() {
        for (name in listOf(three, two)) {
            val s = screen(name)
            val ticked = s.replace("⠙ 4m", "⠹ 5m").replace("⠴ 7m", "⠦ 8m").replace("elapsed 3m46s", "elapsed 3m52s").replace("elapsed 51.0s", "elapsed 57.5s")
            assertNotEquals("the fixture ticks", s, ticked)
            assertEquals(name, cardScreenDigest(s), cardScreenDigest(ticked))
            assertNotEquals("the command still counts", cardScreenDigest(s), cardScreenDigest(s.replace("Command: ", "Command: echo hi; ")))
            assertNotEquals("an option still counts", cardScreenDigest(s), cardScreenDigest(s.replace("  Deny", "  Defer")))
        }
    }

    /** A row added to the box right under its Command row, as a command over several rows draws one. */
    private fun withBoxRow(s: String, row: String): String = s.lines().flatMap { l ->
        if (stripAnsi(l).contains("Command: ")) listOf(l, "\u001b[0m\u001b[38;2;42;48;56m│\u001b[0m $row\u001b[0m                    \u001b[38;2;42;48;56m│\u001b[0m") else listOf(l)
    }.joinToString("\n")

    private val statusLike = "⠋ 9m > ⬢ m > 📁 ~/a ▶────"
    private val statusLikeChanged = "⠋ 9m > ⬢ m > 📁 ~/b ▶────"

    /**
     * 2.2.8 (Codex's review 1): the running rows are left out only where Oh My Pi draws them. A row inside
     * the approval box counts as it is, whatever it looks like; so does a status-shaped row anywhere but
     * right under the box, and with no approval box on the screen no row is left out.
     */
    @Test fun onlyTheRowsAroundTheBoxAreLeftOut() {
        for (name in listOf(three, two)) {
            val s = screen(name)
            val inBox = withBoxRow(s, statusLike)
            assertNotEquals("$name: the fixture adds a row", s, inBox)
            assertNotEquals("$name: a status-shaped command row counts", cardScreenDigest(inBox), cardScreenDigest(withBoxRow(s, statusLikeChanged)))
            assertNotEquals("$name: an elapsed edge in the box counts",
                cardScreenDigest(withBoxRow(s, "╰─── elapsed 3s ───╯")), cardScreenDigest(withBoxRow(s, "╰─── elapsed 4s ───╯")))
            // The real status row (right under the box) still ticks freely beside the added row.
            assertEquals(name, cardScreenDigest(inBox), cardScreenDigest(inBox.replace("⠙ 4m", "⠹ 5m").replace("⠴ 7m", "⠦ 8m")))
            // A status-shaped row above the box is part of the transcript, not the footer.
            val above = "$statusLike\n$s"
            assertNotEquals("$name: above the box", cardScreenDigest(above), cardScreenDigest("$statusLikeChanged\n$s"))
            // No approval box (its top edge gone): nothing is left out, the footer included.
            val noBox = s.replace("Allow tool:", "Allowed tool:")
            assertNotEquals("$name: no box", cardScreenDigest(noBox), cardScreenDigest(noBox.replace("⠙ 4m", "⠹ 5m").replace("⠴ 7m", "⠦ 8m")))
            assertNotEquals("$name: no box, elapsed", cardScreenDigest(noBox),
                cardScreenDigest(noBox.replace("elapsed 3m46s", "elapsed 3m52s").replace("elapsed 51.0s", "elapsed 57.5s")))
        }
        // Without the box the rows are exactly Hermes's rule: a bare status row is itself.
        assertEquals(listOf(statusLike), cardScreenRows(listOf(statusLike)))
    }

    private fun tick(s: String) = s.replace("⠙ 4m", "⠹ 5m").replace("⠴ 7m", "⠦ 8m")
        .replace("elapsed 3m46s", "elapsed 3m52s").replace("elapsed 51.0s", "elapsed 57.5s")

    /** A row put in as it is (no box gutter) right under the box's Command row, or right above its top. */
    private fun withRawRow(s: String, row: String, aboveTop: Boolean = false): String = s.lines().flatMap { l ->
        val p = stripAnsi(l)
        when {
            aboveTop && p.contains("Allow tool:") -> listOf(row, l)
            !aboveTop && p.contains("Command: ") -> listOf(l, row)
            else -> listOf(l)
        }
    }.joinToString("\n")

    private val oldToolBox = "╭─── Older step ───╮\n│ ls │\n╰─── elapsed 9s ───╯\n\n"

    /**
     * Codex's re-review: screens where the approval box isn't proven, or rows outside it look like its
     * running rows. Each pair differs only in a row that is not the proven box's own running row, so the
     * digests must differ.
     */
    private fun ambiguous(s: String): List<Pair<String, Pair<String, String>>> = listOf(
        // Two approval boxes: neither is proven, nothing is left out.
        "two boxes" to ("$s\n$s" to tick("$s\n$s")),
        // A second bottom-shaped edge under the box.
        "edge after" to ("$s\n╰──────╯" to tick("$s\n╰──────╯")),
        // A bottom-shaped edge inside the box, with no gutter.
        "edge inside" to (withRawRow(s, "╰──────╯") to tick(withRawRow(s, "╰──────╯"))),
        // An older tool box's elapsed edge, far above the box: it counts as it is.
        "older elapsed" to ("$oldToolBox$s" to "${oldToolBox.replace("9s", "10s")}$s"),
        // An elapsed-shaped edge right above the box with no tool box over it: it counts, and so does the real one.
        "lone elapsed" to (withRawRow(s, "╰─── elapsed 3s ───╯", aboveTop = true) to withRawRow(s, "╰─── elapsed 4s ───╯", aboveTop = true)),
    )

    @Test fun anUnprovenBoxOrLookAlikeRowsLeaveNothingOut() {
        for (name in listOf(three, two)) {
            val s = screen(name)
            assertEquals("$name: the proven box still ticks freely", cardScreenDigest(s), cardScreenDigest(tick(s)))
            assertEquals("$name: and an older tool box above it is no problem", cardScreenDigest("$oldToolBox$s"), cardScreenDigest(tick("$oldToolBox$s")))
            for ((case, pair) in ambiguous(s)) {
                assertNotEquals("$name: $case: the fixture changes", pair.first, pair.second)
                assertNotEquals("$name: $case", cardScreenDigest(pair.first), cardScreenDigest(pair.second))
            }
        }
    }

    private val getJson = """{"result":{"pane":{"pane_id":"p1","tab_id":"t1","workspace_id":"w1","agent":"omp","agent_status":"working","cwd":"/w","terminal_id":"term-1","scroll":{"offset_from_bottom":0}}}}"""

    private fun runCheck(python: Boolean, guardScreen: String, live: String): Int {
        val sh = listOf("/bin/sh", "/usr/bin/sh").map { File(it) }.firstOrNull { it.canExecute() }
        assumeTrue(sh != null)
        val dir = java.nio.file.Files.createTempDirectory("hd228-appr").toFile()
        try {
            File(dir, "get.json").writeText(getJson)
            File(dir, "screen.txt").writeBytes(live.toByteArray())
            val herdr = File(dir, "herdr")
            herdr.writeText("#!/bin/sh\ncase \"\$2\" in get) /bin/cat '${dir.path}/get.json' ;; read) /bin/cat '${dir.path}/screen.txt' ;; *) : ;; esac\n")
            herdr.setExecutable(true)
            val g = CardGuard(cardOccupantDigest(JSONObject(getJson).getJSONObject("result").getJSONObject("pane")), cardScreenDigest(guardScreen))
            val h = Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), herdr.path, Backend.TUI, null)
            val c = h.cardSendCommand("p1", CardInput.Keys(listOf("enter")), g, shellOnly = !python).let {
                if (python) it.replace(Regex("""; st=.*fi \)""", RegexOption.DOT_MATCHES_ALL), " )") else it
            }
            val p = ProcessBuilder(sh!!.path, "-c", c).apply { environment()["HOME"] = File(dir, "home").apply { mkdirs() }.path }.start()
            p.inputStream.readBytes(); p.errorStream.readBytes()
            return p.waitFor()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun bothChecksLetTheAnswerGoAsTheTimersRunAndStopAChangedBox() {
        val hasPython = listOf("/usr/bin/python3", "/bin/python3").any { File(it).canExecute() }
        for (python in listOfNotNull(false, true.takeIf { hasPython })) {
            for (name in listOf(three, two)) {
                val s = screen(name)
                val ticked = s.replace("⠙ 4m", "⠹ 5m").replace("⠴ 7m", "⠦ 8m").replace("elapsed 3m46s", "elapsed 3m52s").replace("elapsed 51.0s", "elapsed 57.5s")
                assertEquals("$name python=$python tick", 0, runCheck(python, s, ticked))
                assertEquals("$name python=$python command", CARD_CHANGED_EXIT, runCheck(python, s, s.replace("Command: ", "Command: echo hi; ")))
                // Codex's re-review: an unproven box or look-alike rows leave nothing out on the computer either.
                for ((case, pair) in ambiguous(s)) assertEquals("$name python=$python $case", CARD_CHANGED_EXIT, runCheck(python, pair.first, pair.second))
                assertEquals("$name python=$python older tool box", 0, runCheck(python, "$oldToolBox$s", tick("$oldToolBox$s")))
                // Codex's review 1: the checks on the computer keep every row of the box too.
                val inBox = withBoxRow(s, statusLike)
                assertEquals("$name python=$python box row", CARD_CHANGED_EXIT, runCheck(python, inBox, withBoxRow(s, statusLikeChanged)))
                assertEquals("$name python=$python box elapsed", CARD_CHANGED_EXIT,
                    runCheck(python, withBoxRow(s, "╰─── elapsed 3s ───╯"), withBoxRow(s, "╰─── elapsed 4s ───╯")))
                assertEquals("$name python=$python box row, ticking", 0, runCheck(python, inBox, inBox.replace("⠙ 4m", "⠹ 5m").replace("⠴ 7m", "⠦ 8m")))
                assertEquals("$name python=$python above", CARD_CHANGED_EXIT, runCheck(python, "$statusLike\n$s", "$statusLikeChanged\n$s"))
                val noBox = s.replace("Allow tool:", "Allowed tool:")
                assertEquals("$name python=$python no box", CARD_CHANGED_EXIT, runCheck(python, noBox, noBox.replace("⠙ 4m", "⠹ 5m").replace("⠴ 7m", "⠦ 8m")))
            }
        }
    }
}
