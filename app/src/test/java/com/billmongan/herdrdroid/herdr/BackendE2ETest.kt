package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ssh.KeySource
import com.billmongan.herdrdroid.ssh.UnconfirmedHostKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The app's real herdr code against a throwaway server running both the herdr TUI and herdr-core
 * (the Herdr App's server), with a stand-in `claude` that echoes what it is sent. Skipped unless
 * HD_HC_HOST (the server), HD_HC_KEY (a private key file it accepts) and HD_HC_USER are set; see
 * tools/hc-e2e for the server.
 */
class BackendE2ETest {
    private val host = System.getenv("HD_HC_HOST")
    private val user = System.getenv("HD_HC_USER") ?: "dev"
    private val port = System.getenv("HD_HC_PORT")?.toIntOrNull() ?: 22

    private fun connect(): HerdrSsh {
        val creds = Credentials(KeySource.Imported(File(System.getenv("HD_HC_KEY")).readText(), null), null)
        // First contact reports the host key; pin it the way the app does after you confirm it.
        val fp = try {
            runBlocking { HerdrSsh(host, port, user, creds, null, { null }).exec("true") }
            null
        } catch (e: UnconfirmedHostKey) {
            e.fingerprint
        }
        return HerdrSsh(host, port, user, creds, fp, { null })
    }

    @Test fun bothBackendsDoEverythingTheAppUses() = runBlocking {
        assumeTrue("no herdr test server", !host.isNullOrBlank())
        val ssh = connect()
        try {
            val d = detectBackend(ssh, "herdr")
            assertTrue("herdr TUI running", d.tuiRunning)
            assertTrue("herdr-core running", d.coreRunning)
            assertNotNull("hc found", d.hcPath)
            for (backend in listOf(Backend.TUI, Backend.CORE)) runFlow(ssh, backend, d.hcPath)
        } finally {
            ssh.close()
        }
    }

    private suspend fun runFlow(ssh: HerdrSsh, backend: Backend, hcPath: String?) {
        val h = Herdr(ssh, "herdr", backend, hcPath)
        val tag = backend.name.lowercase()
        h.check()
        assertTrue("$tag: the stand-in agent is found", "claude" in h.installedAgents())

        // New session: on a fresh herdr this also makes the first workspace.
        val label = "e2e-$tag"
        val pane = h.startAgent("claude", "~/e2e", label, "e2e$tag", null)
        delay(1_500)
        val listed = h.panes().firstOrNull { it.id == pane }
        assertNotNull("$tag: the new session is listed", listed)
        assertEquals("$tag: it runs claude", "claude", listed!!.agent)
        assertTrue("$tag: in the folder asked for (${listed.cwd})", listed.cwd.endsWith("/e2e"))

        // Talking to it: a message with Enter, typed keys, and the screen read back.
        h.typeAndEnter(pane, "hello $tag")
        delay(1_200)
        assertTrue("$tag: the message arrived", h.read(pane, 40).contains("you said: hello $tag"))
        h.sendText(pane, "ok")
        h.sendKeys(pane, "enter")
        delay(1_200)
        assertTrue("$tag: typed keys arrived", h.read(pane, 40).contains("you said: ok"))
        assertTrue("$tag: previews read the screen", h.previews(listOf(pane))[pane]?.tail?.contains("you said") == true)
        // The mode chip's key, accepted by both.
        h.sendKeys(pane, "shift+tab")

        // Rename where the backend can; herdr-core says it can't instead of pretending.
        if (h.canRename) {
            h.renameTab(listed.tabId, "$label-renamed")
            delay(800)
            assertEquals("$tag: renamed", "$label-renamed", h.panes().first { it.id == pane }.tabLabel)
        } else {
            assertFalse(h.canRename)
            assertTrue("$tag: rename refused", runCatching { h.renameTab(listed.tabId, "x") }.isFailure)
        }

        // Close session: gone from the list, folder untouched.
        h.closeTab(listed.tabId)
        delay(1_000)
        assertTrue("$tag: closed", h.panes().none { it.id == pane })
        assertEquals("$tag: folder kept", 0, ssh.exec("test -d \"\$HOME/e2e\"").exit)
    }
}
