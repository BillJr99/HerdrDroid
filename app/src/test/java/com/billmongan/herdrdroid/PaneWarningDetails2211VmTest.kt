package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ApplicationProvider
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.PaneWarningResult
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PaneWarningDetails2211VmTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()

    private val diagnostic = "Couldn't save diagnostic logs to its local database."
    private val generic = "A separate warning."
    private val changed = "A changed warning."

    private fun fixture(block: Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.block() } finally {
            fixture.vm.clearForTest()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun idleReviewCountsOnlyUndismissedTextsAndChangedTextReappears() = fixture {
        seedCount(2)
        show(diagnostic, generic)
        assertEquals(2, vm.warningFor(pane))
        vm.dismissPaneWarning(pane.id, diagnostic)
        assertEquals(1, vm.warningFor(pane))
        vm.dismissPaneWarning(pane.id, generic)
        assertNull(vm.warningFor(pane))
        assertEquals(2, vm.rawWarningFor(pane))
        show(diagnostic, changed)
        assertEquals(listOf(changed), vm.paneWarningReview!!.warnings.map { it.text })
        assertEquals(1, vm.warningFor(pane))
    }

    @Test
    fun diagnosticRestartGuidanceRequiresAConfirmationAndCurrentOccupant() = fixture {
        show(diagnostic, generic)
        assertFalse(vm.confirmWarningRestart())
        vm.requestWarningRestart(pane.id, generic)
        assertNull(vm.warningRestartConfirmation)
        vm.requestWarningRestart(pane.id, diagnostic)
        assertEquals(diagnostic, vm.warningRestartConfirmation?.text)
        replaceOccupant()
        assertNull(vm.warningRestartConfirmation)
        assertFalse(vm.confirmWarningRestart())
        show(diagnostic)
        vm.requestWarningRestart(pane.id, diagnostic)
        assertTrue(vm.confirmWarningRestart())
        assertFalse(vm.confirmWarningRestart())
    }

    @Test
    fun lateWarningReadDoesNotPaintAReplacedPane() = fixture {
        vm.openPaneWarnings(pane.id) { _, allowed ->
            assertTrue(allowed())
            replaceOccupant()
            assertFalse(allowed())
            PaneWarningResult.Ready(listOf(diagnostic))
        }
        assertNull(vm.paneWarningReview)
    }

    @Test
    fun unsuccessfulPanelReadShowsItsReason() = fixture {
        vm.openPaneWarnings(pane.id) { _, _ ->
            PaneWarningResult.Unavailable("The pane is busy.")
        }
        assertEquals("The pane is busy.", vm.paneWarningReview?.issue)
        assertTrue(vm.paneWarningReview!!.warnings.isEmpty())
    }

    private class Fixture : Herdr.Commands {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        private val server = Server(id = "source", name = "Board source", host = "example.invalid", port = 1,
            user = "reader", herdrPath = "herdr")
        private val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        override val connection = Any()
        private val client = Herdr(ssh, server.herdrPath, Backend.TUI, null, this)
        var pane = Pane("p1", "tab", "w1", "codex", Status.IDLE, "", ".", terminalId = "terminal-1")
            private set

        init {
            field("herdr").set(vm, client)
            state("current", server)
            state("link", Link.CONNECTED)
            state("panes", listOf(pane))
            vm.screen = Screen.Agent(pane.id)
        }

        fun show(vararg texts: String) {
            vm.openPaneWarnings(pane.id) { _, allowed ->
                assertTrue(allowed())
                PaneWarningResult.Ready(texts.toList())
            }
        }

        fun replaceOccupant() {
            pane = pane.copy(terminalId = "terminal-2")
            state("panes", listOf(pane))
        }

        fun seedCount(count: Int) {
            val reading = nested("WarningReading", pane, count, android.os.SystemClock.elapsedRealtime())
            state("warningBatch", nested("WarningBatch", server.id, client, connection, mapOf(pane.id to reading)))
        }

        override suspend fun bulk(command: String): ExecResult = error("Unexpected command")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult =
            error("Unexpected command")

        private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        private fun state(name: String, value: Any) {
            (field("${name}\$delegate").get(vm) as MutableState<Any>).value = value
        }

        private fun nested(name: String, vararg args: Any): Any = AppViewModel::class.java.declaredClasses
            .single { it.simpleName == name }.declaredConstructors.single().apply { isAccessible = true }.newInstance(*args)
    }
}
