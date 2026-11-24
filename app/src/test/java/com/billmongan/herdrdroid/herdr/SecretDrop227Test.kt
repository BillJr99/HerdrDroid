package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.diag.DiagLog
import com.billmongan.herdrdroid.ssh.ExecResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private fun plain(id: String) = Pane(id, "tab-$id", "w1", "claude", Status.IDLE, "", "/fixture", terminalId = "t-$id")

/** 2.2.7 Send a secret: the remote command, its receipt, the log, and where the path goes. */
class SecretDrop227Test {
    private val secretText = "ya29.not-a-real-token-ZZQQ"

    /** A computer that keeps what it is sent and answers like the command would. */
    private class Computer(
        var fileMode: String = "600", var dirMode: String = "700",
        var corruptFirst: Boolean = false, var exit: Int = 0, var err: String = "",
    ) : SecretDrop.Run {
        val writes = mutableListOf<Pair<String, ByteArray>>()
        val others = mutableListOf<String>()
        override suspend fun run(command: String, stdin: ByteArray?): ExecResult {
            if (stdin == null) { others += command; return ExecResult(0, "", "") }
            writes += command to stdin.copyOf()
            if (exit != 0) return ExecResult(exit, "", err)
            val name = Regex("chmod 600 [^ ]*secrets'/'([^']+)'").find(command)!!.groupValues[1]
            val bytes = if (corruptFirst && writes.size == 1) stdin.copyOf(stdin.size - 1) else stdin
            return ExecResult(0, "Welcome banner\n${SecretDrop.MARKER}\n/home/u/.herdrdroid/secrets/$name\n${bytes.size}\n${sha256Hex(bytes)}\n$fileMode\n$dirMode\n", "")
        }
    }

    @Test fun theCommandIsPrivateAndCarriesNoSecret() {
        val c = SecretDrop.command(SecretDrop.fileName("google oauth", text = true, stamp = "20261123-120000", rand = "abcd1234"), 26)
        assertTrue(c.startsWith("umask 077; "))
        assertTrue(c.contains("chmod 700 \"\$HOME\"/'.herdrdroid/secrets'"))
        assertTrue(c.contains("chmod 600 \"\$HOME\"/'.herdrdroid/secrets'/'20261123-120000-abcd1234-google_oauth.txt'"))
        assertTrue("never over an existing file", c.contains("set -C") && c.contains("mv -n"))
        assertTrue("reads the secret from stdin", c.contains("cat > "))
        assertTrue("refuses a linked folder", c.contains("[ -L \"\$HOME\"/'.herdrdroid/secrets' ]"))
        assertTrue("reads back both modes", c.contains("stat -c %a") && c.contains("stat -f %Lp"))
        assertFalse("not the attach inbox", c.contains("inbox"))
        assertThrows(IllegalArgumentException::class.java) { SecretDrop.command("../x", 1) }
        assertThrows(IllegalArgumentException::class.java) { SecretDrop.command(".hidden", 1) }
    }

    @Test fun namesComeFromTheLabelNeverTheSecret() {
        assertEquals("20261123-120000-abcd1234.txt", SecretDrop.fileName(null, true, "20261123-120000", "abcd1234"))
        assertEquals("20261123-120000-abcd1234-key.json", SecretDrop.fileName("key.json", true, "20261123-120000", "abcd1234"))
        assertEquals("20261123-120000-abcd1234-client_secret.json", SecretDrop.fileName("client secret.json", false, "20261123-120000", "abcd1234"))
        assertEquals("20261123-120000-abcd1234-etc_passwd", SecretDrop.fileName("../../etc/passwd", false, "20261123-120000", "abcd1234"))
        assertNull(SecretDrop.label("   ...  "))
        val a = SecretDrop.fileName(null, true)
        val b = SecretDrop.fileName(null, true)
        assertNotEquals("each save has its own name", a, b)
    }

    @Test fun aCheckedPrivateSaveReturnsItsPathAndLogsOnlyTheCount() = runBlocking {
        DiagLog.clear()
        val pc = Computer()
        val bytes = secretText.toByteArray()
        val r = SecretDrop.upload("oauth", true, bytes, pc)
        assertTrue(r.path.startsWith("/home/u/.herdrdroid/secrets/") && r.path.endsWith("-oauth.txt"))
        assertEquals(bytes.size.toLong(), r.size)
        assertEquals(1, pc.writes.size)
        assertArrayEquals("the secret goes as stdin", secretText.toByteArray(), pc.writes[0].second)
        assertFalse("never in the command", pc.writes[0].first.contains(secretText))
        val log = DiagLog.text()
        assertTrue(log.contains("secret bytes=${bytes.size} try=1 verify=ok"))
        assertFalse(log.contains(secretText))
        assertFalse("no path in the log", log.contains(".herdrdroid/secrets"))
    }

    @Test fun wrongPermissionsAreRefusedWithThePath() = runBlocking<Unit> {
        val pc = Computer(fileMode = "644")
        val e = assertThrows(SecretNotPrivate::class.java) { runBlocking { SecretDrop.upload(null, true, secretText.toByteArray(), pc) } }
        assertTrue(e.message!!.contains("/home/u/.herdrdroid/secrets/"))
        assertTrue(e.message!!.contains("644"))
        val pc2 = Computer(dirMode = "755")
        assertThrows(SecretNotPrivate::class.java) { runBlocking { SecretDrop.upload(null, true, secretText.toByteArray(), pc2) } }
    }

    @Test fun aMismatchIsSetAsideAndWrittenOnceMore() = runBlocking {
        val pc = Computer(corruptFirst = true)
        val r = SecretDrop.upload(null, true, secretText.toByteArray(), pc)
        assertEquals(2, pc.writes.size)
        assertNotEquals("a fresh name the second time", pc.writes[0].first, pc.writes[1].first)
        assertEquals(1, pc.others.size)
        assertTrue("set aside, never deleted", pc.others[0].startsWith("mv -n ") && pc.others[0].contains(".failed"))
        assertFalse(pc.others[0].contains(secretText))
        assertTrue(r.path.isNotEmpty())
    }

    @Test fun aFailedSaveSaysWhyAndIsNotRetried() = runBlocking<Unit> {
        val pc = Computer(exit = 4, err = "The secrets folder is a link, so nothing was saved.")
        val e = assertThrows(UploadFailed::class.java) { runBlocking { SecretDrop.upload(null, true, secretText.toByteArray(), pc) } }
        assertEquals("The secrets folder is a link, so nothing was saved.", e.message)
        assertEquals(1, pc.writes.size)
        assertThrows(UploadFailed::class.java) { runBlocking { SecretDrop.upload(null, true, ByteArray(0), pc) } }
        assertThrows(UploadFailed::class.java) { runBlocking { SecretDrop.upload(null, false, ByteArray(65), pc, max = 64) } }
        assertEquals("nothing over the limit is sent", 1, pc.writes.size)
    }

    @Test fun thePathGoesInAtTheCaret() {
        assertEquals("read /p/s.txt " to 14, SecretDrop.insertAt("read ", 5, "/p/s.txt"))
        assertEquals("use /p/s now" to 9, SecretDrop.insertAt("use now", 4, "/p/s"))
        assertEquals("/p/s " to 5, SecretDrop.insertAt("", 0, "/p/s"))
        assertEquals("ab /p/s cd" to 7, SecretDrop.insertAt("ab cd", 2, "/p/s"))
        assertEquals("caret past the end is clamped", "x /p " to 5, SecretDrop.insertAt("x", 99, "/p"))
        assertEquals("draft /p ", SecretDrop.appendTo("draft  ", "/p"))
        assertEquals("/p ", SecretDrop.appendTo("", "/p"))
    }

    // ---- The target choice -------------------------------------------------------------------

    private val here = SecretTarget("s1", "p1", "Mini", "Claude")
    private val sibling = SecretTarget("s1", "p2", "Mini", "Codex")
    private val away = SecretTarget("s2", "p9", "Lab", "Hermes")

    private class Port(val open: SecretTarget?, val listed: MutableSet<SecretTarget>, val runners: Map<String, SecretDrop.Run>) : SecretSender.Port {
        val live = mutableListOf<Pair<SecretTarget, String>>()
        val drafts = mutableListOf<Pair<SecretTarget, String>>()
        /** What a fresh read of a pane returns; by default the pane as listed (or a plain pane with its id). */
        var fresh: (String) -> Pane? = { id -> listed.firstOrNull { it.paneId == id }?.let { it.pane ?: plain(id) } }
        val reads = mutableListOf<String>()
        var inFlight = 0
        val inFlightAtWrite = mutableListOf<Int>()
        override fun present(t: SecretTarget) = t in listed
        /** The connection each server is on now. */
        var connection: Any? = null
        override fun bind(serverId: String) = runners[serverId]?.let { r ->
            SecretSender.Bound(
                SecretDrop.Run { c, stdin -> if (stdin != null) inFlightAtWrite += inFlight; r.run(c, stdin) },
                { id -> reads += id; fresh(id) }, { inFlight++ }, { inFlight-- }, connection,
            )
        }
        override fun isOpen(t: SecretTarget) = t == open
        override fun insertLive(t: SecretTarget, path: String) { live += t to path }
        override fun appendDraft(t: SecretTarget, path: String) { drafts += t to path }
    }

    @Test fun theOpenPaneGetsThePathAtTheCaret() = runBlocking {
        val pc = Computer()
        val port = Port(here, mutableSetOf(here, sibling), mapOf("s1" to pc))
        val bytes = secretText.toByteArray()
        val o = SecretSender(port).send(here, null, true, bytes) as SecretOutcome.Saved
        assertTrue(o.live)
        assertEquals(listOf(here to o.path), port.live)
        assertTrue(port.drafts.isEmpty())
        assertTrue("zeroed after", bytes.all { it == 0.toByte() })
        assertEquals("Saved on Mini as ${o.path}; only the path goes in the chat.", secretNotice(o))
    }

    @Test fun anotherPaneOnThisServerGetsItInItsDraft() = runBlocking {
        val pc = Computer()
        val port = Port(here, mutableSetOf(here, sibling), mapOf("s1" to pc))
        val o = SecretSender(port).send(sibling, "k", true, secretText.toByteArray()) as SecretOutcome.Saved
        assertTrue(o.draft && !o.live)
        assertEquals(listOf(sibling to o.path), port.drafts)
        assertTrue(port.live.isEmpty())
        assertTrue(secretNotice(o).contains("in Codex's message box, not sent"))
    }

    @Test fun aPaneOnAnotherServerIsSavedThere() = runBlocking {
        val mini = Computer()
        val lab = Computer()
        val port = Port(here, mutableSetOf(here, away), mapOf("s1" to mini, "s2" to lab))
        val o = SecretSender(port).send(away, null, true, secretText.toByteArray()) as SecretOutcome.Saved
        assertTrue(mini.writes.isEmpty())
        assertEquals(1, lab.writes.size)
        assertEquals(listOf(away to o.path), port.drafts)
        assertTrue(secretNotice(o).startsWith("Saved on Lab as "))
    }

    @Test fun aTargetGoneBeforeTheSaveGetsNothing() = runBlocking {
        val pc = Computer()
        val port = Port(here, mutableSetOf(here), mapOf("s1" to pc, "s2" to pc))
        val bytes = secretText.toByteArray()
        val o = SecretSender(port).send(sibling, null, true, bytes)
        assertTrue(o is SecretOutcome.Refused)
        assertTrue(pc.writes.isEmpty())
        assertTrue(port.live.isEmpty() && port.drafts.isEmpty())
        assertTrue("zeroed even when refused", bytes.all { it == 0.toByte() })
        // A server that isn't connected: nothing saved either.
        val port2 = Port(here, mutableSetOf(here, away), mapOf("s1" to pc))
        assertTrue(SecretSender(port2).send(away, null, true, secretText.toByteArray()) is SecretOutcome.Refused)
        assertTrue(pc.writes.isEmpty())
    }

    @Test fun aFailedSaveInsertsNothing() = runBlocking {
        val pc = Computer(fileMode = "664")
        val port = Port(here, mutableSetOf(here), mapOf("s1" to pc))
        val o = SecretSender(port).send(here, null, true, secretText.toByteArray()) as SecretOutcome.Refused
        assertTrue(o.why.contains("Nothing went into the message"))
        assertFalse(o.why.contains(secretText))
        assertTrue(port.live.isEmpty() && port.drafts.isEmpty())
    }

    // ---- 2.2.7, Codex's review: one connection, one occupant, never a partial file ------------

    private val claudePane = Pane("p1", "tab-p1", "w1", "claude", Status.IDLE, "", "/fixture", terminalId = "t-p1", sessionId = "sess-A")
    private val bound = SecretTarget("s1", "p1", "Mini", "Claude", claudePane)

    @Test fun anOccupantReplacedBeforeTheSaveGetsNothingSaved() = runBlocking {
        val pc = Computer()
        val port = Port(bound, mutableSetOf(bound), mapOf("s1" to pc))
        port.fresh = { claudePane.copy(sessionId = "sess-B") }
        val bytes = secretText.toByteArray()
        val o = SecretSender(port).send(bound, null, true, bytes)
        assertTrue(o.toString(), o is SecretOutcome.Refused && o.why.contains("nothing was saved"))
        assertTrue("never uploaded", pc.writes.isEmpty())
        assertTrue(port.live.isEmpty() && port.drafts.isEmpty())
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun anOccupantReplacedDuringTheSaveGetsNoPath() = runBlocking {
        val pc = Computer()
        val port = Port(bound, mutableSetOf(bound), mapOf("s1" to pc))
        port.fresh = { if (pc.writes.isEmpty()) claudePane else claudePane.copy(agent = "codex", sessionId = "sess-C") }
        val o = SecretSender(port).send(bound, null, true, secretText.toByteArray()) as SecretOutcome.Saved
        assertFalse(o.live || o.draft)
        assertTrue("no path in any message box", port.live.isEmpty() && port.drafts.isEmpty())
        assertEquals("read before and after the save", listOf("p1", "p1"), port.reads)
        assertTrue(secretNotice(o).endsWith("Claude closed or changed, so it went into no message."))
    }

    @Test fun aPaneThatCantBeReadBackGetsNoPath() = runBlocking {
        val pc = Computer()
        val port = Port(bound, mutableSetOf(bound), mapOf("s1" to pc))
        port.fresh = { if (pc.writes.isEmpty()) claudePane else throw IllegalStateException("The SSH connection changed.") }
        val o = SecretSender(port).send(bound, null, true, secretText.toByteArray()) as SecretOutcome.Saved
        assertFalse(o.live || o.draft)
        assertTrue(port.live.isEmpty() && port.drafts.isEmpty())
    }

    @Test fun aReplacedConnectionRefusesTheSave() = runBlocking {
        val changed = SecretDrop.Run { _, _ -> throw com.billmongan.herdrdroid.ssh.ConnectionChanged() }
        val port = Port(bound, mutableSetOf(bound), mapOf("s1" to changed))
        val o = SecretSender(port).send(bound, null, true, secretText.toByteArray())
        assertTrue(o.toString(), o is SecretOutcome.Refused && o.why.contains("connection to Mini changed, so nothing was saved"))
        assertTrue(port.live.isEmpty() && port.drafts.isEmpty())
        assertEquals(0, port.inFlight)
    }

    @Test fun theSaveCountsAsASendInFlight() = runBlocking {
        val pc = Computer()
        val port = Port(bound, mutableSetOf(bound), mapOf("s1" to pc))
        val o = SecretSender(port).send(bound, null, true, secretText.toByteArray())
        assertTrue(o is SecretOutcome.Saved && o.live)
        assertEquals("the upload ran while counted", listOf(1), port.inFlightAtWrite)
        assertEquals("released after", 0, port.inFlight)
    }

    /** The real command in a real shell: an upload cut short never gets the final name. */
    @Test fun aCutShortUploadLeavesOnlyThePartFile() {
        val sh = listOf("/bin/sh", "/usr/bin/sh").map { java.io.File(it) }.firstOrNull { it.canExecute() }
        org.junit.Assume.assumeTrue("needs a POSIX shell", sh != null)
        val home = java.nio.file.Files.createTempDirectory("hd227-secret").toFile()
        try {
            fun run(name: String, declared: Int, stdin: ByteArray): Int {
                val p = ProcessBuilder(sh!!.path, "-c", SecretDrop.command(name, declared)).redirectErrorStream(true)
                p.environment()["HOME"] = home.path
                val proc = p.start()
                proc.outputStream.use { it.write(stdin) }
                proc.inputStream.readBytes()
                return proc.waitFor()
            }
            val dir = java.io.File(home, SecretDrop.DIR)
            val bytes = secretText.toByteArray()
            assertEquals(5, run("cut.txt", bytes.size, bytes.copyOf(bytes.size / 2)))
            assertFalse("no file at the final name", java.io.File(dir, "cut.txt").exists())
            assertTrue("the partial copy stays under .part", java.io.File(dir, "cut.txt.part").exists())
            assertEquals(0, run("whole.txt", bytes.size, bytes))
            assertArrayEquals(bytes, java.io.File(dir, "whole.txt").readBytes())
            assertFalse(java.io.File(dir, "whole.txt.part").exists())
        } finally {
            home.deleteRecursively()
        }
    }

    /** Codex's review of 5b66c71: a reconnect between choosing the pane and Send refuses the save. */
    @Test fun aReconnectSinceThePaneWasChosenRefusesTheSave() = runBlocking {
        val chosenOn = Any()
        val target = bound.copy(connection = chosenOn)
        val pc = Computer()
        val port = Port(target, mutableSetOf(target), mapOf("s1" to pc))
        port.connection = Any()
        val bytes = secretText.toByteArray()
        val o = SecretSender(port).send(target, null, true, bytes)
        assertTrue(o.toString(), o is SecretOutcome.Refused && o.why.contains("reconnected after you chose Claude, so nothing was saved"))
        assertTrue("never uploaded", pc.writes.isEmpty())
        assertTrue(port.live.isEmpty() && port.drafts.isEmpty())
        assertTrue(bytes.all { it == 0.toByte() })
        port.connection = chosenOn
        assertTrue("the same connection saves", SecretSender(port).send(target, null, true, secretText.toByteArray()) is SecretOutcome.Saved)
    }

    /** omp's audit: a picked file is read into buffers that are all zeroed; only the exact copy handed on is left. */
    @Test fun aPickedFilesReadBuffersAreAllZeroed() {
        val content = ByteArray(150 * 1024) { (it % 251 + 1).toByte() }
        val seen = mutableListOf<ByteArray>()
        val out = SecretDrop.readBounded(java.io.ByteArrayInputStream(content), SecretDrop.MAX_BYTES) { seen += it }
        assertArrayEquals(content, out)
        assertTrue("it grew past its first buffer", seen.size >= 3)
        assertTrue("every internal buffer is zeroed", seen.all { b -> b.all { it == 0.toByte() } })
        assertTrue("the result is not one of them", seen.none { it === out })
        // One past the limit is read, so the sender can refuse a file that is too big.
        assertTrue(SecretDrop.readBounded(java.io.ByteArrayInputStream(ByteArray(4096) { 1 }), 1024).size > 1024)
        val failing = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = throw java.io.IOException("gone")
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (n++ > 2) throw java.io.IOException("gone"); java.util.Arrays.fill(b, off, off + len, 7); return len }
        }
        val kept = mutableListOf<ByteArray>()
        assertThrows(java.io.IOException::class.java) { SecretDrop.readBounded(failing, SecretDrop.MAX_BYTES) { kept += it } }
        assertTrue("zeroed on a failed read too", kept.all { b -> b.all { it == 0.toByte() } })
    }
}
