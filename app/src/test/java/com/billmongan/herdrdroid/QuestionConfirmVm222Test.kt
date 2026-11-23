package com.billmongan.herdrdroid

import android.app.Application
import androidx.compose.runtime.MutableState
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2.2.2: the open pane's question card shows once two reads in a row give it from a complete dialog.
 * A read with the dialog half drawn (its footer not yet there) and the first complete read show the
 * neutral card; the second complete read shows the question. The pane's text itself is not held.
 * Neutral fixture text only.
 */
@RunWith(AndroidJUnit4::class)
class QuestionConfirmVm222Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()

    private val full = "Earlier reply line.\n\n────────────────────\n Allow this command?\n\n   ls -la\n\n ❯ 1. Yes\n   2. No\n\n Esc to cancel · Tab to amend"
    // Read as a menu only while the pane needs you (an unnumbered menu with no footer of its own).
    private val gatewayMenu = "Earlier reply line.\n\n────────────────────\n Approve this change?\n ❯ Keep the change\n   Undo it\n"
    private val halfDrawn = "Earlier reply line.\n\n❯ 1. Quokka first thing\n  2. Quokka second thing\n\n"

    private lateinit var f: Fixture

    @Before fun setUp() { f = Fixture(); f.install() }
    @After fun tearDown() { f.vm.viewModelScope.cancel() }

    private fun card(pane: String = "ask") = f.vm.questionCardFor(pane, f.vm.cardParse(pane, f.vm.paneText), "Agent")

    @Test fun theCardWaitsForTwoMatchingCompleteReads() = runBlocking {
        f.screens["ask"] = halfDrawn
        f.vm.openPane("ask")
        f.vm.readPane("ask")
        // The read shows at once (the pane needs you), but its card is the neutral one.
        assertEquals(halfDrawn, f.vm.paneText)
        val first = checkNotNull(card())
        assertTrue(first.readingQuestion)
        assertTrue(first.choices.isEmpty())
        assertFalse(first.question.contains("Quokka"))
        f.screens["ask"] = full
        f.vm.readPane("ask")
        assertEquals(full, f.vm.paneText)
        assertTrue("one complete read is not yet confirmed", card()!!.readingQuestion)
        f.vm.readPane("ask")
        val shown = checkNotNull(card())
        assertFalse(shown.readingQuestion)
        assertEquals(detectPrompt(full, true, "gemini"), shown)
        assertEquals(listOf("Yes", "No"), shown.choices.map { it.label })
        // Further identical reads keep it.
        f.vm.readPane("ask")
        assertFalse(card()!!.readingQuestion)
        // A redraw that loses the footer goes back to the neutral card.
        f.screens["ask"] = halfDrawn
        f.vm.readPane("ask")
        assertTrue(card()!!.readingQuestion)
    }

    @Test fun historyRecordsOnlyAConfirmedQuestion() = runBlocking {
        f.screens["ask"] = full
        f.vm.openPane("ask")
        f.vm.readPane("ask")
        assertTrue("one read records nothing", f.vm.questionsFor("ask").isEmpty())
        f.vm.readPane("ask")
        assertFalse(card()!!.readingQuestion)
        assertTrue(f.vm.questionsFor("ask").isNotEmpty())
    }

    /**
     * The review's case: Hermes waits through its gateway while herdr reports the pane idle. The
     * screen draws the card with the pane's shown status (needs you), and an unnumbered menu is read
     * only then; the read path confirms that same card, so it shows instead of "Reading it…" for good.
     */
    @Test fun aHermesGatewayWaitConfirmsTheCardTheScreenShows() = runBlocking {
        f.hermesWaiting()
        f.screens["hermes"] = gatewayMenu
        f.vm.openPane("hermes")
        assertEquals(com.billmongan.herdrdroid.herdr.Status.NEEDS_YOU, f.vm.shownStatus(checkNotNull(f.vm.pane("hermes"))))
        // herdr's own status reads no card here; the shown status does.
        assertEquals(null, detectPrompt(gatewayMenu, false, "hermes"))
        // Its top is proven but it draws no footer: four reads in a row.
        repeat(com.billmongan.herdrdroid.herdr.FALLBACK_READS - 1) {
            f.vm.readPane("hermes")
            assertTrue(card("hermes")!!.readingQuestion)
        }
        f.vm.readPane("hermes")
        val shown = checkNotNull(card("hermes"))
        assertFalse("the card must not stay on Reading it", shown.readingQuestion)
        assertEquals(listOf("Keep the change", "Undo it"), shown.choices.map { it.label })
    }

    private class Fixture : Herdr.Commands {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val server = Server(id = "fixture", name = "Fixture", host = "test.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
        private val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        override var connection: Any? = Any()
        val screens = mutableMapOf<String, String>()

        /** Hermes's gateway reports a wait (for no particular stored conversation). */
        fun hermesWaiting() = state("hermesLiveStates", listOf(com.billmongan.herdrdroid.hermes.HermesLiveState(null, "waiting")))

        fun install() {
            field("herdr").set(vm, Herdr(ssh, "/fixture/herdr", Backend.TUI, null, this))
            state("current", server)
            field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
            field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
            runBlocking { vm.refreshPanes() }
        }

        override suspend fun bulk(command: String): ExecResult {
            if (!(command.contains("'pane' 'list'") && command.contains("'tab' 'list'"))) return ok("")
            return ok("""{"result":{"panes":[${pane("ask", "gemini", "blocked", "t1")},${pane("hermes", "hermes", "idle", "t2")}]}}
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
