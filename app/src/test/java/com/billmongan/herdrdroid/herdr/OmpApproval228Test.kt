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
            }
        }
    }
}
