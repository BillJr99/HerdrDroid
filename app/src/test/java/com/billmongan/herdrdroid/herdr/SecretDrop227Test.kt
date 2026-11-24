package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.diag.DiagLog
import com.billmongan.herdrdroid.ssh.ExecResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

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
        val c = SecretDrop.command(SecretDrop.fileName("google oauth", text = true, stamp = "20261123-120000", rand = "abcd1234"))
        assertTrue(c.startsWith("umask 077; "))
        assertTrue(c.contains("chmod 700 \"\$HOME\"/'.herdrdroid/secrets'"))
        assertTrue(c.contains("chmod 600 \"\$HOME\"/'.herdrdroid/secrets'/'20261123-120000-abcd1234-google_oauth.txt'"))
        assertTrue("never over an existing file", c.contains("set -C") && c.contains("mv -n"))
        assertTrue("reads the secret from stdin", c.contains("cat > "))
        assertTrue("refuses a linked folder", c.contains("[ -L \"\$HOME\"/'.herdrdroid/secrets' ]"))
        assertTrue("reads back both modes", c.contains("stat -c %a") && c.contains("stat -f %Lp"))
        assertFalse("not the attach inbox", c.contains("inbox"))
        assertThrows(IllegalArgumentException::class.java) { SecretDrop.command("../x") }
        assertThrows(IllegalArgumentException::class.java) { SecretDrop.command(".hidden") }
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
        override fun present(t: SecretTarget) = t in listed
        override fun runner(serverId: String) = runners[serverId]
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
}
