package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 2.2.8 (Codex's re-review 2): a message queued behind a question is typed only into the occupant it
 * was queued for, checked on the computer in the write's own command. The real command, in a real
 * shell, against a stand-in herdr: the Python check and the shell check.
 */
class OccupantCheck228Test {
    private fun json(terminal: String, offset: Int = 0) =
        """{"result":{"pane":{"pane_id":"p1","tab_id":"t1","workspace_id":"w1","agent":"claude","agent_status":"idle","cwd":"/w",""" +
            """"terminal_id":"$terminal","agent_session":{"kind":"id","agent":"claude","value":"s1"},"scroll":{"offset_from_bottom":$offset}}}}"""

    private fun digest(j: String) = cardOccupantDigest(JSONObject(j).getJSONObject("result").getJSONObject("pane"))

    private class Shell(val dir: File) : Herdr.Commands {
        override val connection: Any? = Any()
        override suspend fun bulk(command: String) = ExecResult(0, "", "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            val p = ProcessBuilder("/bin/sh", "-c", command).apply { environment()["HOME"] = File(dir, "home").apply { mkdirs() }.path }.start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
            return ExecResult(p.waitFor(), out, err)
        }
    }

    private fun run(shellOnly: Boolean) {
        val dir = java.nio.file.Files.createTempDirectory("hd228-occ").toFile()
        try {
            val get = File(dir, "get.json")
            val sent = File(dir, "sent.log")
            val herdr = File(dir, "herdr")
            herdr.writeText("#!/bin/sh\ncase \"\$1 \$2\" in\n  'pane get') /bin/cat '${get.path}' ;;\n" +
                "  'agent prompt'|'pane send-text'|'pane send-keys') echo \"\$*\" >> '${sent.path}' ;;\n  *) exit 9 ;;\nesac\n")
            herdr.setExecutable(true)
            val seam = Shell(dir)
            val h = Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), herdr.path, Backend.TUI, null, seam)
            val pane = Pane("p1", "t1", "w1", "claude", Status.IDLE, "", "/w", terminalId = "term-1")
            get.writeText(json("term-1"))
            val bound = digest(json("term-1"))
            runBlocking { h.sendMessage(pane, "hello", allowTerminalFallback = false, expectedConnection = seam.connection, occupant = bound, shellOnly = shellOnly) }
            assertEquals("shellOnly=$shellOnly: typed once", 1, sent.readLines().count { it.contains("hello") })
            // Scrolled back: still the same occupant, so the message goes.
            get.writeText(json("term-1", offset = 4))
            runBlocking { h.sendMessage(pane, "again", allowTerminalFallback = false, expectedConnection = seam.connection, occupant = bound, shellOnly = shellOnly) }
            assertEquals(1, sent.readLines().count { it.contains("again") })
            // Another occupant on the computer: refused there, nothing typed.
            get.writeText(json("term-2"))
            val before = sent.readText()
            assertThrows(OccupantChanged::class.java) {
                runBlocking { h.sendMessage(pane, "never", allowTerminalFallback = false, expectedConnection = seam.connection, occupant = bound, shellOnly = shellOnly) }
            }
            assertEquals("shellOnly=$shellOnly: nothing typed", before, sent.readText())
            // The send-text route (a pane waiting on you) is guarded the same way.
            assertThrows(OccupantChanged::class.java) {
                runBlocking { h.sendMessage(pane.copy(status = Status.NEEDS_YOU), "never", expectedConnection = seam.connection, occupant = bound, shellOnly = shellOnly) }
            }
            assertEquals(before, sent.readText())
            // An unreadable pane: the check failed, nothing typed.
            get.writeText("not json")
            assertThrows(CardCheckFailed::class.java) {
                runBlocking { h.sendMessage(pane, "never", allowTerminalFallback = false, expectedConnection = seam.connection, occupant = bound, shellOnly = shellOnly) }
            }
            assertEquals(before, sent.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theShellCheckRefusesAnotherOccupant() {
        assumeTrue(File("/bin/sh").canExecute())
        run(shellOnly = true)
    }

    @Test fun thePythonCheckRefusesAnotherOccupant() {
        assumeTrue(File("/bin/sh").canExecute() && listOf("/usr/bin/python3", "/bin/python3").any { File(it).canExecute() })
        run(shellOnly = false)
    }

    @Test fun theCommandCarriesNoMessageTextInTheCheck() {
        val h = Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), "herdr", Backend.TUI, null)
        val c = h.occupantCheckCommand("p1", "a".repeat(64))
        assertTrue(c.contains("'p1' '${"a".repeat(64)}' '-'"))
    }
}
