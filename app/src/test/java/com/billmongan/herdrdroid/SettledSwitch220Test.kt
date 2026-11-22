package com.billmongan.herdrdroid

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2.2.0 (Bill: "when I switch panes the rendering is briefly incorrect while it reads the terminal
 * view"): a switch's first reads are held until two in a row match, or one must show at once, and the
 * pane's last good view stays up meanwhile with "Updating". Neutral fixture text only.
 */
@RunWith(AndroidJUnit4::class)
class SettledSwitch220Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()

    private val mid = "Reply line one\nReply li"
    private val settled = "Reply line one\nReply line two\n\n> "
    private val question = "Allow this command?\n\n  ls -la\n\n> 1. Yes\n  2. No\n\nEsc to cancel"

    private lateinit var f: Fixture

    @Before fun setUp() {
        f = Fixture()
        f.install()
    }

    @After fun tearDown() { f.vm.viewModelScope.cancel() }

    @Test fun aMidRedrawFirstReadIsHeldUntilTwoReadsMatch() = runBlocking {
        f.vm.openPane("plain")
        assertFalse(f.vm.paneViewSettled)
        assertTrue(f.vm.paneUpdating("plain"))
        // The intermediate rendering: before 2.2.0 this first read was drawn at once.
        f.screens["plain"] = mid
        f.vm.readPane("plain")
        assertEquals("", f.vm.paneText)
        assertFalse(f.vm.paneViewSettled)
        // A different read is not settled either.
        f.screens["plain"] = settled
        f.vm.readPane("plain")
        assertEquals("", f.vm.paneText)
        // The same bytes twice: settled, drawn.
        f.vm.readPane("plain")
        assertEquals(settled, f.vm.paneText)
        assertTrue(f.vm.paneViewSettled)
        assertFalse(f.vm.paneUpdating("plain"))
        // After that every read shows as before.
        f.screens["plain"] = "Another reply\n\n> "
        f.vm.readPane("plain")
        assertEquals("Another reply\n\n> ", f.vm.paneText)
    }

    @Test fun theLastSettledScreenShowsWhileANewVisitSettles() = runBlocking {
        f.vm.openPane("plain")
        f.screens["plain"] = settled
        f.vm.readPane("plain"); f.vm.readPane("plain")
        assertEquals(settled, f.vm.paneText)
        f.vm.openPane("other")
        f.vm.openPane("plain")
        // Nothing read yet this visit: the last settled screen stands in, display only.
        assertEquals(settled, f.vm.heldScreenText("plain"))
        assertNull(f.vm.paneTextTarget)
        f.screens["plain"] = mid
        f.vm.readPane("plain")
        assertEquals(settled, f.vm.heldScreenText("plain"))
        f.vm.readPane("plain")
        assertEquals(mid, f.vm.paneText)
        assertNull(f.vm.heldScreenText("plain"))
    }

    @Test fun aQuestionOnTheFirstReadShowsAtOnce() = runBlocking {
        f.screens["waiting"] = question
        f.vm.openPane("waiting")
        f.vm.readPane("waiting")
        assertEquals(question, f.vm.paneText)
        assertTrue(f.vm.paneViewSettled)
        assertNotNull(f.vm.paneTextTarget)
    }

    @Test fun aWorkingPaneStreamsWithoutWaiting() = runBlocking {
        f.screens["busy"] = mid
        f.vm.openPane("busy")
        f.vm.readPane("busy")
        assertEquals(mid, f.vm.paneText)
        assertTrue(f.vm.paneViewSettled)
    }

    @Test fun aScreenThatNeverSettlesShowsTheNewestAfterABoundedNumberOfReads() = runBlocking {
        f.vm.openPane("plain")
        val max = com.billmongan.herdrdroid.herdr.SettleGate.MAX_READS
        for (n in 1 until max) {
            f.screens["plain"] = "Frame $n"
            f.vm.readPane("plain")
            assertEquals("held at read $n", "", f.vm.paneText)
        }
        f.screens["plain"] = "Frame $max"
        f.vm.readPane("plain")
        assertEquals("Frame $max", f.vm.paneText)
        assertTrue(f.vm.paneViewSettled)
    }

    @Test fun anAgentWithAChatLogDoesNotShowTheScreenViewUntilTheLogAnswersOrABoundIsReached() = runBlocking {
        // A codex pane, whose chat log never answers here (the fixture has no log reader).
        f.screens["log"] = settled
        f.vm.openPane("log")
        assertFalse(f.vm.screenViewReady("log"))
        f.vm.readPane("log"); f.vm.readPane("log")
        assertEquals(settled, f.vm.paneText)
        assertFalse("the screen view must not stand in for the chat yet", f.vm.screenViewReady("log"))
        repeat(com.billmongan.herdrdroid.herdr.TRANSCRIPT_WAIT_READS) { f.vm.readPane("log") }
        assertTrue(f.vm.screenViewReady("log"))
        // An agent with no chat log shows its screen view as soon as it settles.
        f.vm.openPane("plain")
        assertTrue(f.vm.screenViewReady("plain"))
    }

    private class Fixture : Herdr.Commands {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val server = Server(id = "fixture", name = "Fixture", host = "test.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
        private val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        override var connection: Any? = Any()
        val screens = mutableMapOf<String, String>()

        fun install() {
            field("herdr").set(vm, Herdr(ssh, "/fixture/herdr", Backend.TUI, null, this))
            state("current", server)
            field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
            field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
            runBlocking { vm.refreshPanes() }
        }

        override suspend fun bulk(command: String): ExecResult {
            if (!(command.contains("'pane' 'list'") && command.contains("'tab' 'list'"))) return ok("")
            return ok("""{"result":{"panes":[${pane("plain", "gemini", "idle", "t1")},${pane("other", "gemini", "idle", "t2")},${pane("waiting", "gemini", "blocked", "t3")},${pane("busy", "gemini", "working", "t4")},${pane("log", "codex", "idle", "t5")}]}}
                |@@herdrdroid-tabs@@
                |{"result":{"tabs":[]}}
            """.trimMargin())
        }

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            val m = Regex("""^'/fixture/herdr' 'pane' 'read' '([^']+)' '--source' '(visible|recent)'.*$""").matchEntire(command)
                ?: return ok("")
            return ok(screens[m.groupValues[1]] ?: "Idle")
        }

        private fun pane(id: String, agent: String, status: String, terminal: String) =
            """{"pane_id":"$id","workspace_id":"w1","tab_id":"tab","agent":"$agent","agent_status":"$status","cwd":"/fixture","terminal_id":"$terminal","scroll":{"offset_from_bottom":0}}"""
        private fun ok(out: String) = ExecResult(0, out, "")
        private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }
    }
}
