package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.diag.DiagLog
import com.billmongan.herdrdroid.diag.LinkDiag
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.HerdrError
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.2.5, Bill (via Hermes): Laptop2 signed in, herdr 0.9.5 there listed 13 panes, and the app
 * showed none. The fixtures are herdr 0.9.5's real `pane list` and `tab list` output from that
 * computer with every path, title, session id and label replaced.
 */
@RunWith(AndroidJUnit4::class)
class PaneDiscovery225Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()

    private val paneList = fixture("pane-list.json")
    private val tabList = fixture("tab-list.json")
    private val vm by lazy { AppViewModel(ApplicationProvider.getApplicationContext<Application>()) }
    private var vmMade = false
    private val a = Server(id = "fixture-a", name = "FixtureA", host = "127.0.0.1", port = 1, user = "fixa", herdrPath = "herdr")
    private val b = Server(id = "fixture-b", name = "FixtureB", host = "127.0.0.1", port = 2, user = "fixb", herdrPath = "herdr")

    @After fun close() {
        if (vmMade) {
            vm.clearForTest()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // ---- herdr 0.9.5's output --------------------------------------------------------------------

    @Test fun herdr095ListParsesEveryPaneWithItsTabLabel() = runBlocking {
        val fake = Fake { "$paneList\n@@herdrdroid-tabs@@ 0\n$tabList\n" }
        val panes = fake.client.panes()
        assertEquals(13, panes.size)
        assertEquals(13, panes.count { it.agent != null })
        assertTrue(panes.all { it.tabLabel.startsWith("tab-") })
        assertEquals(13, fake.client.lastListCounts!!.raw)
        assertEquals(13, fake.client.lastListCounts!!.parsed)
        // The pane list's own exit status is asked for, not the tab list's.
        val sent = fake.commands.single { "'pane' 'list'" in it }
        assertTrue(sent, "s=\$?; echo \"@@herdrdroid-tabs@@ \$s\"" in sent)
    }

    @Test fun theOldReplyWithNoStatusStillParses() = runBlocking {
        val panes = Fake { "$paneList\n@@herdrdroid-tabs@@\n$tabList\n" }.client.panes()
        assertEquals(13, panes.size)
    }

    @Test fun aFailedPaneListIsAnErrorWithItsExitNotAnEmptyList() = runBlocking {
        val fake = Fake { "\n@@herdrdroid-tabs@@ 2\n$tabList\n" }
        try {
            fake.client.panes()
            fail("expected HerdrError")
        } catch (e: HerdrError) {
            assertEquals(2, e.exit)
        }
    }

    @Test fun aLineBeforeTheJsonAndOneUnreadablePaneLoseNothingElse() = runBlocking {
        val bad = paneList.replaceFirst("\"pane_id\":\"", "\"pane_idx\":\"")
        val panes = Fake { "notice: something herdr printed\n$bad\n@@herdrdroid-tabs@@ 0\n$tabList\n" }.let { f ->
            f.client.panes().also { assertEquals(13, f.client.lastListCounts!!.raw) }
        }
        assertEquals(12, panes.size)
    }

    // ---- applying a read ---------------------------------------------------------------------------

    @Test fun aReadFinishingAfterTheServerChangedIsDropped() = runBlocking {
        vmMade = true
        val onB = listOf(Pane("w9:p9", "w9:t9", "w9", "codex", Status.IDLE, "codex fixture", "/fixture", scrollOffset = 0, terminalId = "t9"))
        val fakeB = Fake { "{\"result\":{\"panes\":[]}}\n@@herdrdroid-tabs@@ 0\n" }
        lateinit var fakeA: Fake
        fakeA = Fake {
            // While A's list is on its way, the user opens B.
            field("herdr").set(vm, fakeB.client)
            state("current", b)
            state("panes", onB)
            "$paneList\n@@herdrdroid-tabs@@ 0\n$tabList\n"
        }
        open(a, fakeA)
        vm.refreshPanes()
        assertEquals(b, vm.current)
        assertEquals(onB, vm.panes)
        assertTrue(DiagLog.text().contains("stage=apply result=stale-dropped"))
    }

    @Test fun aReadOnAConnectionAReconnectReplacedIsDroppedAndTheNewOneApplies() = runBlocking {
        vmMade = true
        lateinit var second: Fake
        val first = Fake {
            // The link dropped and came back during the read: a new client for the same server.
            field("herdr").set(vm, second.client)
            "{\"result\":{\"panes\":[]}}\n@@herdrdroid-tabs@@ 0\n"
        }
        second = Fake { "$paneList\n@@herdrdroid-tabs@@ 0\n$tabList\n" }
        val kept = listOf(Pane("w1:p1", "w1:t1", "w1", "claude", Status.IDLE, "claude fixture", "/fixture", scrollOffset = 0, terminalId = "t1"))
        open(a, first)
        state("panes", kept)
        vm.refreshPanes()
        // The stale empty list did not clear what was shown.
        assertEquals(kept, vm.panes)
        vm.refreshPanes()
        assertEquals(13, vm.panes.size)
        assertTrue(DiagLog.text().contains("raw=13 parsed=13"))
    }

    @Test fun aFailedListReadIsLoggedByStageAndClassWithoutItsOutput() = runBlocking {
        vmMade = true
        val fake = Fake { "private-output-text\n@@herdrdroid-tabs@@ 2\n" }
        open(a, fake)
        vm.refreshPanes()
        val log = DiagLog.text()
        assertTrue(log, log.contains("stage=list-read result=failed") && log.contains("error=HerdrError exit=2"))
        assertFalse(log.contains("private-output-text"))
        assertFalse("no host in the log", log.contains("127.0.0.1"))
        assertTrue(log.contains("server=${LinkDiag.serverTag(a.id)}"))
    }

    @Test fun theServerTagIsOpaque() {
        val tag = LinkDiag.serverTag(a.id)
        assertTrue(tag.matches(Regex("s[0-9a-f]{6}")))
        assertFalse(tag.contains("fixture"))
        assertEquals(tag, LinkDiag.serverTag(a.id))
        assertFalse(tag == LinkDiag.serverTag(b.id))
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("herdr095/$name")!!.readText().trim()

    private fun open(server: Server, fake: Fake) {
        DiagLog.clear()
        field("herdr").set(vm, fake.client)
        state("servers", listOf(a, b))
        state("current", server)
        state("link", Link.CONNECTED)
    }

    private class Fake(private val listReply: () -> String) : Herdr.Commands {
        val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = Herdr(HerdrSsh("127.0.0.1", 3, "fake", Credentials(null, null), null, { null }), "herdr", Backend.TUI, null, this)
        override val connection: Any? = Any()
        override suspend fun bulk(command: String): ExecResult = answer(command)
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = answer(command)
        private fun answer(command: String): ExecResult {
            commands += command
            return if ("'pane' 'list'" in command) ExecResult(0, listReply(), "") else ExecResult(0, "", "")
        }
    }

    private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value
    }
}
