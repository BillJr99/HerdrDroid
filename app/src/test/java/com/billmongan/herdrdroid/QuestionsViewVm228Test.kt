package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.questions.EntryState
import com.billmongan.herdrdroid.questions.QuestionEntry
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.2.8: answers from the Questions view go through exactly the checks the pane's own card uses:
 * the target bound to the client, connection, server, visit and occupant; a fresh read that must
 * still show the same card; and the check on the computer against the occupant and screen digests
 * of that read before any key is typed.
 */
@RunWith(AndroidJUnit4::class)
class QuestionsViewVm228Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()

    @Test fun aConfirmedEntryIsAnsweredThroughTheCardsCheckOnTheComputer() = fixture {
        val e = readEntry()
        assertEquals(EntryState.READY, e.state)
        assertTrue("two reads confirmed it", visibleReads >= 2)
        answer(e)
        assertEquals(listOf("y"), sent)
        assertEquals("the digest check ran before the key", 1, cardChecks)
    }

    @Test fun anEntryNeedsTheQuestionsViewOpen() = fixture {
        vm.screen = Screen.Home
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(vm.questionsCardTarget("p1"))
        vm.screen = Screen.Questions
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(vm.questionsCardTarget("p1"))
    }

    @Test fun aDialogClosedBetweenTheListAndTheTapSendsNothing() = fixture {
        val e = readEntry()
        frame = "Ran the command.\n› "
        answer(e)
        assertTrue(sent.isEmpty())
        assertEquals(0, cardChecks)
        assertTrue(vm.notice.orEmpty(), vm.notice.orEmpty().startsWith("Not sent."))
    }

    @Test fun aScreenChangedAfterTheLastReadIsRefusedOnTheComputer() = fixture {
        val e = readEntry()
        beforeCheck = { frame = command("other") }
        answer(e)
        assertTrue(sent.isEmpty())
        assertEquals(1, cardChecks)
        assertEquals(1, cardChecksRefused)
        assertEquals("The question changed; not sent.", vm.notice)
    }

    @Test fun anOccupantChangeAfterTheListSendsNothing() = fixture {
        val e = readEntry()
        terminal = "t2"
        status(Status.NEEDS_YOU)
        val gets = paneGets
        answer(e)
        assertTrue(sent.isEmpty())
        assertEquals("never sent to the computer", 0, cardChecks)
        assertEquals("no retry: not even a fresh read", gets, paneGets)
        assertTrue(vm.notice.orEmpty().startsWith("Not sent."))
        assertEquals("the entry says why", "Not sent. Another agent or session is in this pane now.",
            vm.questionsFeed.entries.single().note)
        // And the list starts that pane again rather than keep the old buttons.
        runBlocking { vm.questionsFeed.refreshNow() }
        val again = vm.questionsFeed.entries.single()
        assertEquals("t2", again.source.pane.terminalId)
        assertEquals(EntryState.READY, again.state)
    }

    @Test fun anEntryGoesStaleOnceTheViewIsLeft() = fixture {
        val e = readEntry()
        vm.screen = Screen.Agent("p1")
        shadowOf(Looper.getMainLooper()).idle()
        vm.screen = Screen.Questions
        shadowOf(Looper.getMainLooper()).idle()
        answer(e)
        assertTrue("a new visit: the old target isn't current", sent.isEmpty())
    }

    @Test fun theEntryShowsAnsweredOnceThePaneMovesOnAndTheBadgeFollows() = fixture {
        val e = readEntry()
        assertEquals(1, vm.openQuestionCount())
        afterSend = "Running npm test…"
        answer(e)
        assertEquals(listOf("y"), sent)
        status(Status.WORKING)
        runBlocking { vm.questionsFeed.refreshNow() }
        assertEquals(EntryState.ANSWERED, vm.questionsFeed.entries.single().state)
        assertEquals(0, vm.openQuestionCount())
    }

    @Test fun noPanesNeedingYouIsAnEmptyListAndNoBadge() = fixture {
        status(Status.IDLE)
        runBlocking { vm.questionsFeed.refreshNow() }
        assertTrue(vm.questionsFeed.entries.isEmpty())
        assertEquals(0, vm.openQuestionCount())
    }

    private fun fixture(test: Fixture.() -> Unit) {
        val f = Fixture()
        try {
            f.test()
        } finally {
            f.vm.questionsFeed.hide()
            f.vm.viewModelScope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class Fixture : Herdr.Commands {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        private val server = Server(id = "fixture-q", name = "FixtureQ", host = "127.0.0.1", port = 1, user = "fixq", herdrPath = "/fixture/herdr")
        private val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        override var connection: Any? = Any()
        var frame = command("alpha")
        var terminal = "t1"
        val sent = mutableListOf<String>()
        var afterSend: String? = null
        var visibleReads = 0
        var paneGets = 0
        var cardChecks = 0
        var cardChecksRefused = 0
        var beforeCheck: (() -> Unit)? = null
        private var statusNow = Status.NEEDS_YOU

        init {
            field("herdr").set(vm, Herdr(ssh, server.herdrPath, Backend.TUI, null, this))
            state("current", server)
            status(Status.NEEDS_YOU)
            field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
            field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
            vm.screen = Screen.Questions
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun status(status: Status) {
            statusNow = status
            state("panes", listOf(Pane("p1", "tab", "w1", "codex", status, "", "/fixture", tabLabel = "codex", scrollOffset = 0, terminalId = terminal)))
        }

        fun readEntry(): QuestionEntry {
            runBlocking { vm.questionsFeed.refreshNow() }
            val e = vm.questionsFeed.entries.single()
            assertEquals(EntryState.READY, e.state)
            val target = e.target as AppViewModel.CardTarget
            assertSame("the same target the card's own checks compare", vm.questionsCardTarget("p1")?.client, target.client)
            return e
        }

        fun answer(e: QuestionEntry) {
            val p = e.prompt!!
            val t = e.target as AppViewModel.CardTarget
            // As the screen does: what went wrong is shown on the entry.
            vm.answerChoice("p1", p, p.choices.first(), target = t, feedback = { vm.questionsFeed.note(e.key, vm.questionsRefusal(t, it)) })
            drainUntil { !vm.answerInFlight("p1") }
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun drainUntil(done: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                shadowOf(Looper.getMainLooper()).idle()
                if (done()) return
                Thread.sleep(1)
            } while (System.nanoTime() < deadline)
            fail("Answer handler did not finish")
        }

        override suspend fun bulk(command: String): ExecResult = ok("""{"result":{"panes":[$pane]}}
            |@@herdrdroid-tabs@@
            |{"result":{"tabs":[]}}
        """.trimMargin())

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            if (expectedConnection != null) assertSame(connection, expectedConnection)
            Regex("""(?s)'p1' '([0-9a-f]{64})' '([0-9a-f]{64})'.*\) && (.*)$""").find(command)?.takeIf { "herdrdroid-check" in command }?.let { m ->
                cardChecks++
                beforeCheck?.invoke()
                val now = com.billmongan.herdrdroid.herdr.CardGuard(
                    com.billmongan.herdrdroid.herdr.cardOccupantDigest(org.json.JSONObject(pane)),
                    com.billmongan.herdrdroid.herdr.cardScreenDigest(frame))
                if (now != com.billmongan.herdrdroid.herdr.CardGuard(m.groupValues[1], m.groupValues[2])) {
                    cardChecksRefused++
                    return ExecResult(com.billmongan.herdrdroid.herdr.CARD_CHANGED_EXIT, "", "")
                }
                var last = ok("")
                for (part in m.groupValues[3].split(" && ")) { last = immediate(part, timeoutSeconds, expectedConnection); if (last.exit != 0) break }
                return last
            }
            return when {
                command.endsWith("'pane' 'get' 'p1'") -> { paneGets++; ok("""{"result":{"pane":$pane}}""") }
                command.contains("'pane' 'read' 'p1' '--source' 'recent'") -> ok(frame)
                command.contains("'pane' 'read' 'p1' '--source' 'visible'") -> { visibleReads++; ok(frame) }
                command.contains("'pane' 'send-keys' 'p1' ") -> {
                    sent += command.substringAfter("'p1' ").replace("'", "")
                    afterSend?.let { frame = it }
                    ok("")
                }
                command.contains("'pane' 'send-text' 'p1' ") -> { sent += "text:" + command.substringAfter("'p1' ").removeSurrounding("'"); ok("") }
                else -> ok("")
            }
        }

        private val pane get() = """{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"codex","agent_status":"${when (statusNow) {
            Status.IDLE -> "idle"; Status.DONE -> "done"; Status.WORKING -> "working"; else -> "blocked"
        }}","cwd":"/fixture","terminal_id":"$terminal","scroll":{"offset_from_bottom":0}}"""
        private fun ok(out: String) = ExecResult(0, out, "")
        private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }
    }

    private companion object {
        fun command(arg: String) = """
            Would you like to run the following command?
            ${'$'} echo $arg
            › 1. Yes, proceed (y)
              2. No, and tell Codex what to do differently (esc)
            Press enter to confirm or esc to cancel
        """.trimIndent()
    }
}
