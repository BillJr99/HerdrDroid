package com.billmongan.herdrdroid

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.ui.PromptCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1000dp")
class PanePrefetchTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val compose = createComposeRule()

    @Test fun warmApprovalRendersBeforeLiveReadButHasNoAnswerControls() = withFixture { f ->
        lateinit var frame: String
        compose.runOnUiThread {
            f.refresh()
            assertEquals(listOf("visible:waiting"), f.reads)
            assertEquals("", f.vm.paneText)
            assertNull(f.vm.paneTextTarget)
            f.vm.openPane("waiting")
            frame = checkNotNull(f.vm.prefetchedPaneText("waiting"))
            assertNull(f.vm.paneTextTarget)
            assertTrue(f.vm.questionsFor("waiting").isEmpty())
        }
        val prompt = checkNotNull(detectPrompt(frame, blocked = true, agent = "codex"))
        assertTrue(prompt.choices.isNotEmpty())
        compose.setContent {
            PromptCard(prompt, "Codex", onChoice = { error("Preview cannot answer") },
                onKey = { error("Preview cannot send keys") }, onTypeAnswer = { error("Preview cannot type") },
                readOnlyPreview = true)
        }
        compose.onNodeWithText("Preview").assertExists()
        compose.onNodeWithText("Type a different answer").assertDoesNotExist()
        compose.onNodeWithText("Your answer").assertDoesNotExist()
        assertEquals(listOf("visible:waiting"), f.reads)
        compose.runOnUiThread {
            runBlocking { f.vm.readPane("waiting") }
            assertNull(f.vm.prefetchedPaneText("waiting"))
            assertNotNull(f.vm.paneTextTarget)
            assertTrue(f.vm.questionsFor("waiting").isNotEmpty())
        }
        assertEquals(listOf("visible:waiting", "recent:waiting"), f.reads)
    }

    @Test fun captureIsSingleFlightAndLateResultCannotEnterAVisit() = withFixture { f ->
        compose.runOnUiThread {
            val capture = CompletableDeferred<Unit>()
            f.hold = capture
            f.refresh()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
            f.refresh()
            assertEquals(listOf("visible:waiting"), f.reads)
            f.vm.openPane("waiting")
            capture.complete(Unit)
            f.vm.openPane("selected")
            f.vm.openPane("waiting")
        }
        assertNoPreview(f)
    }

    @Test fun staleCaptureDoesNotWarmTheNextVisit() = withFixture { f ->
        compose.runOnUiThread {
            f.refresh()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
            f.vm.openPane("waiting")
        }
        assertNoPreview(f)
    }

    @Test fun changingConnectionCannotReuseTheSamePaneId() = withFixture { f ->
        compose.runOnUiThread {
            f.refresh()
            f.connection = Any()
            f.vm.openPane("waiting")
        }
        assertNoPreview(f)
    }

    @Test fun changingServerCannotReuseTheSamePaneIdEvenOnTheSameClient() = withFixture { f ->
        compose.runOnUiThread {
            f.refresh()
            f.state("current", f.server.copy(id = "other-fixture"))
            f.vm.openPane("waiting")
        }
        assertNoPreview(f)
    }

    @Test fun replacedOccupantDoesNotReuseTheOldCapture() = withFixture { f ->
        compose.runOnUiThread {
            f.refresh()
            f.terminal = "other-terminal"
            f.refresh()
            f.vm.openPane("waiting")
        }
        assertNoPreview(f)
    }

    @Test fun failedCaptureDoesNotRetryOrAffectTheSelectedPaneAndTheNextSlotRotates() = withFixture { f ->
        compose.runOnUiThread {
            f.failRead = true
            f.refresh()
            f.refresh()
            assertEquals(listOf("visible:waiting"), f.reads)
            assertEquals(Link.CONNECTED, f.vm.link)
            assertNull(f.vm.paneReadProblem)
            assertEquals(Screen.Agent("selected"), f.vm.screen)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
            f.failRead = false
            f.refresh()
            assertEquals(listOf("visible:waiting", "visible:idle"), f.reads)
            f.vm.openPane("waiting")
        }
        assertNoPreview(f)
    }

    @Test fun noReadsWhileLockedOrInBackground() = withFixture { f ->
        compose.runOnUiThread {
            f.vm.onBackground()
            f.refresh()
            f.vm.onForeground()
            f.state("locked", true)
            f.refresh()
            assertTrue(f.reads.isEmpty())
        }
    }

    private fun assertNoPreview(f: Fixture) {
        compose.runOnUiThread { assertNull(f.vm.prefetchedPaneText("waiting")) }
    }

    private fun withFixture(body: (Fixture) -> Unit) {
        val approval = checkNotNull(javaClass.getResource("/screens/codex/captured-2026-11-13/run-command.txt")).readText()
        val f = Fixture(approval)
        try {
            compose.runOnUiThread { f.install() }
            body(f)
        } finally {
            f.vm.viewModelScope.cancel()
        }
    }

    private class Fixture(private val approval: String) : Herdr.Commands {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val server = Server(id = "fixture", name = "Fixture", host = "test.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
        private val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        override var connection: Any? = Any()
        val reads = mutableListOf<String>()
        var hold: CompletableDeferred<Unit>? = null
        var terminal = "t2"
        var failRead = false

        fun install() {
            field("herdr").set(vm, Herdr(ssh, "/fixture/herdr", Backend.TUI, null, this))
            state("current", server)
            field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
            field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
            vm.openPane("selected")
        }

        fun refresh() = runBlocking { vm.refreshPanes() }

        override suspend fun bulk(command: String): ExecResult {
            assertTrue(command, command.contains("'pane' 'list'") && command.contains("'tab' 'list'"))
            return ok("""{"result":{"panes":[${pane("selected", "working", "t1")},${pane("waiting", "blocked", terminal)},${pane("idle", "idle", "t3")},${pane("scrolled", "blocked", "t4", 1)}]}}
                |@@herdrdroid-tabs@@
                |{"result":{"tabs":[]}}
            """.trimMargin())
        }

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            val m = Regex("""^'/fixture/herdr' 'pane' 'read' '([^']+)' '--source' '(visible|recent)'(?: '--lines' '\d+')?(?: '--format' 'ansi')?$""")
                .matchEntire(command) ?: throw AssertionError("Unexpected command: $command")
            val (id, source) = m.destructured
            reads += "$source:$id"
            if (expectedConnection != null) {
                assertSame(connection, expectedConnection)
                assertEquals(1L, timeoutSeconds)
                hold?.await()
            }
            if (failRead) return ExecResult(-2, "", "fixture timed out")
            return ok(if (id == "waiting") approval else "Idle")
        }

        private fun pane(id: String, status: String, terminal: String, scroll: Int = 0) =
            """{"pane_id":"$id","workspace_id":"w1","tab_id":"tab","agent":"codex","agent_status":"$status","cwd":"/fixture","terminal_id":"$terminal","scroll":{"offset_from_bottom":$scroll}}"""
        private fun ok(out: String) = ExecResult(0, out, "")
        private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }
    }
}
