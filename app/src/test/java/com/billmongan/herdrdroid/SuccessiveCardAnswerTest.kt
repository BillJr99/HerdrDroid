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
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.notify.AppBus
import com.billmongan.herdrdroid.notify.NotifyAction
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.transcript.QuestionLog
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class SuccessiveCardAnswerTest {
    @Test fun successiveClaudeTabsWithIdenticalQuestionAndOptionsBothSend() = fixture("claude", firstStep) {
        val first = shown()
        answer(first)
        assertEquals(listOf("enter"), sent)
        assertTrue(vm.notice.orEmpty().startsWith("Sent "))

        frame = secondStep
        val next = shown()
        assertEquals(first.question, next.question)
        assertEquals(first.choices, next.choices)
        assertNotEquals(first.parts?.step, next.parts?.step)
        assertTrue(QuestionLog.sameCard(QuestionLog.signature(first), QuestionLog.signature(next)))
        answer(next)
        assertEquals(vm.notice, listOf("enter", "enter"), sent)
    }

    @Test fun punctuationOnlyChangeInApprovalIsNotAlreadyAnswered() = fixture("codex", command("alpha-beta")) {
        val first = shown()
        answer(first)
        assertEquals(listOf("y"), sent)
        frame = command("alphabeta")
        val next = shown()
        assertNotEquals(first.approvalIdentity, next.approvalIdentity)
        assertTrue(QuestionLog.sameCard(QuestionLog.signature(first), QuestionLog.signature(next)))
        answer(next)
        assertEquals(vm.notice, listOf("y", "y"), sent)
    }

    @Test fun aFreshNotificationAnswerIsNotBlockedByThePreviousAppAnswer() = fixture("claude", firstStep) {
        answer(shown())
        // The button identifies the new option, not just a question shared with the earlier step.
        frame = secondStep.replace("1. Blue", "1. Green")
        alert(shown())
        assertEquals(listOf("enter", "enter"), sent)
    }

    @Test fun oldNotificationCannotAnswerAnIndistinguishableButtonOnTheNextStep() = fixture("claude", firstStep) {
        val first = shown()
        answer(first)
        frame = secondStep
        alert(first)
        assertEquals(listOf("enter"), sent)
    }

    @Test fun unchangedCardStillRejectsSequentialDuplicateAfterHighlightMoves() = fixture("claude", firstStep) {
        val first = shown()
        answer(first)
        frame = firstStep.replace("❯ 1. Blue", "  1. Blue").replace("  2. Orange", "❯ 2. Orange")
        val redraw = shown()
        assertNotEquals(first.selected, redraw.selected)
        answer(redraw)
        assertEquals(listOf("enter"), sent)
        assertTrue(vm.notice.orEmpty().startsWith("Already answered."))
    }

    @Test fun staleApprovalCannotAnswerTheNewCommand() = fixture("codex", command("alpha-beta")) {
        val stale = shown()
        frame = command("alphabeta")
        answer(stale)
        assertTrue(sent.isEmpty())
        assertTrue(vm.notice.orEmpty().startsWith("Not sent."))
    }

    @Test fun staleTargetCannotAnswerAfterOccupantOrConnectionReplacement() = fixture("claude", firstStep) {
        val old = checkNotNull(vm.cardTarget("p1"))
        val prompt = shown()
        terminal = "t2" // Only the remote metadata has changed.
        answer(prompt, old)
        assertTrue(sent.isEmpty())
        assertTrue(vm.notice.orEmpty().startsWith("Not sent."))

        terminal = "t1"
        connection = Any()
        answer(prompt, old)
        assertTrue(sent.isEmpty())
        assertTrue(vm.notice.orEmpty().startsWith("Not sent."))
    }

    private fun fixture(agent: String, frame: String, test: Fixture.() -> Unit) {
        val f = Fixture(agent, frame)
        try { f.test() } finally { f.vm.viewModelScope.cancel() }
    }

    private class Fixture(private val agent: String, var frame: String) : Herdr.Commands {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        private val server = Server(id = "fixture", name = "Fixture", host = "unused.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
        private val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        override var connection: Any? = Any()
        val sent = mutableListOf<String>()
        var terminal = "t1"
        var recentReads = 0

        init {
            field("herdr").set(vm, Herdr(ssh, server.herdrPath, Backend.TUI, null, this))
            state("current", server)
            state("panes", listOf(Pane("p1", "tab", "w1", agent, Status.NEEDS_YOU, "", "/fixture", scrollOffset = 0, terminalId = terminal)))
            field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
            field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
            vm.screen = Screen.Agent("p1")
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun shown() = checkNotNull(detectPrompt(frame, blocked = true, agent = agent)).forCard()
        fun answer(prompt: Prompt, target: AppViewModel.CardTarget = checkNotNull(vm.cardTarget("p1"))) {
            vm.answerChoice("p1", prompt, prompt.choices.first(), target = target)
            drainUntil { !vm.answerInFlight("p1") }
        }

        fun alert(prompt: Prompt) {
            val choice = prompt.choices.first()
            val reads = recentReads
            assertTrue(AppBus.emit(NotifyAction(server.id, "p1", choice.keys, choice.pressEnter, prompt.question,
                label = choice.label, agent = agent)))
            drainUntil { recentReads > reads && !vm.answerInFlight("p1") }
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

        override suspend fun bulk(command: String): ExecResult {
            assertTrue(command, command.contains("'pane' 'list'") && command.contains("'tab' 'list'"))
            return ok("""{"result":{"panes":[$pane]}}
                |@@herdrdroid-tabs@@
                |{"result":{"tabs":[]}}
            """.trimMargin())
        }

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            if (expectedConnection != null) assertSame(connection, expectedConnection)
            return when {
                command.endsWith("'pane' 'get' 'p1'") -> ok("""{"result":{"pane":$pane}}""")
                command.contains("'pane' 'read' 'p1' '--source' 'recent'") -> { recentReads++; ok(frame) }
                command.endsWith("'pane' 'read' 'p1' '--source' 'visible'") -> ok(frame)
                command.contains("def occupant():") && command.endsWith("'/fixture/herdr' 'p1' '$agent'") -> ok("")
                command.contains("'pane' 'send-keys' 'p1' ") -> {
                    sent += command.substringAfter("'p1' ").replace("'", "")
                    ok("")
                }
                else -> throw AssertionError("Unexpected fixture command: $command")
            }
        }

        private val pane get() = """{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"$agent","agent_status":"blocked","cwd":"/fixture","terminal_id":"$terminal","scroll":{"offset_from_bottom":0}}"""
        private fun ok(out: String) = ExecResult(0, out, "")
        private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }
    }

    private companion object {
        val firstStep = """
            ←  ☐ Color  ☐ Size  ✔ Submit  →
            Which option should the sample use?
            ❯ 1. Blue
              2. Orange
              3. Type something.
            Enter to select · Tab/Arrow keys to navigate · Esc to cancel
        """.trimIndent()

        val secondStep = firstStep.replace("☐ Color  ☐ Size", "☒ Color  ☐ Size")

        fun command(arg: String) = """
            Would you like to run the following command?
            ${'$'} echo $arg
            › 1. Yes, proceed (y)
              2. No, and tell Codex what to do differently (esc)
            Press enter to confirm or esc to cancel
        """.trimIndent()
    }
}
