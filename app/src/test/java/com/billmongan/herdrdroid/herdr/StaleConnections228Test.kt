package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ssh.SshCounts
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 2.2.8 (Bill: "be more robust about managing and closing stale connections"; a closed app's watch
 * still held a `herdr agent wait` on the computer). The wait command now ends on the computer as
 * soon as the phone closes its channel, and the connection count per server is kept.
 */
class StaleConnections228Test {
    private class Seam : Herdr.Commands {
        override val connection: Any? = Any()
        override suspend fun bulk(command: String) = ExecResult(0, "", "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?) = ExecResult(0, "", "")
    }

    /** The real wait script in bash with a stand-in herdr whose waits sleep: closing stdin ends it and every wait. */
    @Test fun theRemoteWaitEndsWhenThePhoneClosesItsChannel() {
        val bash = listOf("/bin/bash", "/usr/bin/bash").map { File(it) }.firstOrNull { it.canExecute() }
        assumeTrue("needs bash", bash != null && File("/usr/bin/pkill").canExecute().or(File("/bin/pkill").canExecute()))
        val dir = java.nio.file.Files.createTempDirectory("hd228-wait").toFile()
        try {
            val herdr = File(dir, "herdr")
            herdr.writeText("#!/bin/sh\necho \$\$ >> '${dir.path}/pids'\nexec sleep 30\n")
            herdr.setExecutable(true)
            val h = Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), herdr.path, Backend.TUI, null, Seam())
            val panes = listOf(Pane("c1", "t1", "w1", "claude", Status.WORKING, "", "/w", terminalId = "t"),
                Pane("c2", "t2", "w1", "codex", Status.IDLE, "", "/w", terminalId = "u"))
            val script = checkNotNull(h.waitScript(panes, 25_000))
            val p = ProcessBuilder(bash!!.path, "-c", script).redirectErrorStream(true).start()
            // Both waits started.
            val until = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < until && (File(dir, "pids").takeIf { it.exists() }?.readLines()?.size ?: 0) < 2) Thread.sleep(50)
            val pids = File(dir, "pids").readLines().map { it.trim().toLong() }
            assertEquals(2, pids.size)
            // The phone closes the channel: the command's standard input ends.
            p.outputStream.close()
            assertTrue("the script ended at once", p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(300)
            for (pid in pids) assertFalse("wait $pid was stopped", File("/proc/$pid/stat").takeIf { it.exists() }?.readText()?.let { s -> s.substringAfterLast(") ").firstOrNull() != 'Z' } ?: false)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun connectionsAreCountedFromSignInToClose() {
        SshCounts.clear()
        val a = Any(); val b = Any()
        SshCounts.opened("srv", a)
        assertEquals(1, SshCounts.openNow("srv"))
        // A replacement: the old one is closed before the new one counts.
        SshCounts.closed("srv", a)
        SshCounts.opened("srv", b)
        assertEquals(1, SshCounts.openNow("srv"))
        assertEquals(2, SshCounts.openedEver("srv"))
        SshCounts.closed("srv", b)
        SshCounts.closed("srv", b)
        assertEquals("never below zero", 0, SshCounts.openNow("srv"))
        SshCounts.reused("srv")
        assertEquals(1, SshCounts.reusedEver("srv"))
        // A client that never connected closes to nothing.
        val ssh = HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }, null, null, "srv2")
        ssh.reset(); ssh.close()
        assertEquals(0, SshCounts.openNow("srv2"))
    }
}
