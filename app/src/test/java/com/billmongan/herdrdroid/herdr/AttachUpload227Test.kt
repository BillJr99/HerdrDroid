package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 2.2.7 (Codex's review, Bill's answer): Attach a file gets what Send a secret got: the final name
 * only once every byte arrived, and the upload bound to its connection and counted as a send.
 */
class AttachUpload227Test {
    private val text = "fixture attachment text, not a secret\n".repeat(20).toByteArray()

    private class Seam : Herdr.Commands {
        override val connection: Any? = Any()
        lateinit var ssh: HerdrSsh
        val counted = mutableListOf<Int>()
        val pinnedTo = mutableListOf<Any>()
        override suspend fun bulk(command: String) = ExecResult(0, "", "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?) = ExecResult(0, "", "")
        override suspend fun input(command: String, input: ByteArray, expectedConnection: Any): ExecResult {
            counted += ssh.sendsRunning
            pinnedTo += expectedConnection
            val path = Regex("mv -n [^ ]+ \"\\\$HOME\"/'([^']+)'").find(command)!!.groupValues[1]
            return ExecResult(0, "$UPLOAD_MARKER\n/home/u/$path\n${input.size}\n${sha256Hex(input)}\n", "")
        }
    }

    @Test fun theUploadIsCountedAsASendAndBoundToItsConnection() = runBlocking {
        val seam = Seam()
        val ssh = HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null })
        seam.ssh = ssh
        val r = Herdr(ssh, "herdr", Backend.TUI, null, seam).uploadVerified("notes.txt", text)
        assertTrue(r.path.endsWith("-notes.txt"))
        assertEquals("counted while it ran", listOf(1), seam.counted)
        assertSame(seam.connection, seam.pinnedTo.single())
        assertEquals("released after", 0, ssh.sendsRunning)
    }

    @Test fun theCommandChecksTheSizeBeforeTheFinalName() {
        val c = inboxUploadCommand(".herdrdroid/inbox/x.txt", 42)
        assertTrue(c.indexOf("-eq 42 ]") in 0 until c.indexOf("mv -n"))
        assertTrue(c.contains("Only part of the file arrived, so it was not saved."))
    }

    /** The real command in a real shell: an upload cut short never gets the final name. */
    @Test fun aCutShortUploadLeavesOnlyThePartFile() {
        val sh = listOf("/bin/sh", "/usr/bin/sh").map { File(it) }.firstOrNull { it.canExecute() }
        assumeTrue("needs a POSIX shell", sh != null)
        val home = java.nio.file.Files.createTempDirectory("hd227-attach").toFile()
        try {
            fun run(name: String, stdin: ByteArray): Pair<Int, String> {
                val p = ProcessBuilder(sh!!.path, "-c", inboxUploadCommand(".herdrdroid/inbox/$name", text.size))
                p.environment()["HOME"] = home.path
                val proc = p.start()
                proc.outputStream.use { it.write(stdin) }
                proc.inputStream.readBytes()
                val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
                return proc.waitFor() to err
            }
            val inbox = File(home, ".herdrdroid/inbox")
            val (exit, err) = run("cut.txt", text.copyOf(text.size / 3))
            assertEquals(5, exit)
            assertTrue(err, err.contains("Only part of the file arrived, so it was not saved."))
            assertFalse("no file at the final name", File(inbox, "cut.txt").exists())
            assertTrue("the partial copy stays under .part", File(inbox, "cut.txt.part").exists())
            assertEquals(0, run("whole.txt", text).first)
            assertArrayEquals(text, File(inbox, "whole.txt").readBytes())
            assertFalse(File(inbox, "whole.txt.part").exists())
        } finally {
            home.deleteRecursively()
        }
    }
}
