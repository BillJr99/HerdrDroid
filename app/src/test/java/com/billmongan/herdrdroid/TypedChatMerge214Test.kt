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
import com.billmongan.herdrdroid.herdr.TYPED_ANSWER_LABEL
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.herdr.moveKeys
import com.billmongan.herdrdroid.herdr.shownChoiceLabel
import com.billmongan.herdrdroid.herdr.shownChoices
import com.billmongan.herdrdroid.herdr.typedChatPair
import com.billmongan.herdrdroid.notify.AppBus
import com.billmongan.herdrdroid.notify.NotifyAction
import com.billmongan.herdrdroid.notify.alertIdentity
import com.billmongan.herdrdroid.notify.alertOptionsSig
import com.billmongan.herdrdroid.notify.alertPlan
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.1.4, Bill: "some question cards show type something and also chat about this as separate
 * options they seem redundant and if that's the case I'd like to merge them anytime that they
 * would appear". The card and the alert show one "Type your answer"; it answers through the
 * "Type something." row, found on the live screen; "Chat about this" is never sent from it.
 */
@RunWith(AndroidJUnit4::class)
class TypedChatMerge214Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val server = Server(id = "fixture-m", name = "FixtureM", host = "127.0.0.1", port = 1, user = "fixm", herdrPath = "/fixture/herdr")
    private val single = checkNotNull(javaClass.getResource("/screens/claude/captured-2.1.329/single-question.txt")).readText()
    private val omp = checkNotNull(javaClass.getResource("/screens/omp/derived-1.4.9/0602-ask-dialog-questions-tabs-multi-select.txt")).readText()

    private fun ask(highlight: Int, rows: List<String> = listOf("Yes", "Type something.", "Chat about this")) =
        " Keep the file?\n\n" + rows.mapIndexed { i, r -> (if (i + 1 == highlight) " ❯ " else "   ") + "${i + 1}. $r" }.joinToString("\n") +
            "\n\n Enter to select · ↑/↓ to navigate · Esc to cancel"

    @After fun close() {
        vm.clearForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun claudesCapturedQuestionShowsOneTypedOption() {
        val p = checkNotNull(detectPrompt(single, false, "claude"))
        // The parsed rows are unchanged (keys, alert identity and history read them).
        assertEquals(listOf("Cancel and stop the lane (Recommended)", "Cancel and redo it in a container", "Leave it", "Type something.", "Chat about this"),
            p.choices.map { it.label })
        val shown = shownChoices(p.answerRows())
        assertEquals(listOf("Cancel and stop the lane (Recommended)", "Cancel and redo it in a container", "Leave it", TYPED_ANSWER_LABEL),
            shown.map { shownChoiceLabel(p.answerRows(), it) })
        // The merged option is the "Type something." row itself, with its own keys.
        assertEquals(p.choices[3], shown.last())
        // Alert: no "Chat about this" anywhere, the merged row named for the card.
        val plan = alertPlan(p)
        assertTrue(plan.buttons.none { it.choice?.label == "Chat about this" })
        assertTrue(plan.inApp.toString(), plan.inApp.none { it.contains("Chat about this") })
    }

    @Test fun ohMyPisPairMergesToo() {
        val p = checkNotNull(detectPrompt(omp, true, "omp"))
        val pair = checkNotNull(typedChatPair(p.choices))
        assertEquals("Other (type your own)", pair.first.label)
        assertEquals("Chat about this", pair.second.label)
        assertEquals(p.choices.size - 1, shownChoices(p.choices).size)
    }

    @Test fun theAlertOffersOneTypeYourAnswerReplyWithTheOptionSetIdentityOfTheWholeQuestion() {
        val p = checkNotNull(detectPrompt(ask(1), true, "claude"))
        val plan = alertPlan(p)
        assertEquals(listOf("Yes", TYPED_ANSWER_LABEL, "Esc"), plan.buttons.map { it.title })
        val typed = plan.buttons[1]
        assertTrue(typed.typed)
        assertEquals("Type something.", typed.choice!!.label)
        // The tap's strict read of the same question (highlight elsewhere) has the same option set and identity.
        val later = checkNotNull(detectPrompt(ask(3), true, "claude"))
        assertEquals(alertOptionsSig(p.choices), alertOptionsSig(later.choices))
        assertEquals(alertIdentity(p), alertIdentity(later))
        // Without a reply box (a question that takes no typing), the row keeps its own label and no Chat button.
        val noReply = alertPlan(p, reply = false)
        assertEquals(listOf("Yes", "Type something.", "Esc"), noReply.buttons.map { it.title })
        assertFalse(noReply.buttons.any { it.typed })
    }

    @Test fun theMoveTargetsTheTypedRowFromTheLiveHighlightNeverADigit() {
        for ((highlight, want) in listOf(1 to listOf("down"), 2 to emptyList(), 3 to listOf("up"))) {
            val p = checkNotNull(detectPrompt(ask(highlight), true, "claude"))
            val row = checkNotNull(typedChatPair(p.choices)).first
            assertEquals("highlight $highlight", want, moveKeys(p, row))
        }
        // With the rows in another order the same row is found by its label, not its place.
        val other = checkNotNull(detectPrompt(ask(1, listOf("Yes", "No", "Chat about this", "Type something.")), true, "claude"))
        assertEquals(listOf("down", "down", "down"), moveKeys(other, checkNotNull(typedChatPair(other.choices)).first))
    }

    @Test fun aScreenWithOnlyOneOfTheRowsIsUnchanged() {
        val typedOnly = checkNotNull(detectPrompt(ask(1, listOf("Yes", "No", "Type something.")), true, "claude"))
        assertNull(typedChatPair(typedOnly.choices))
        assertEquals(typedOnly.choices, shownChoices(typedOnly.choices))
        assertEquals("Type something.", shownChoiceLabel(typedOnly.choices, typedOnly.choices[2]))
        val plan = alertPlan(typedOnly)
        assertTrue(plan.buttons.none { it.typed || it.title == TYPED_ANSWER_LABEL })
        assertEquals(listOf("Type something."), plan.inApp)
        val chatOnly = checkNotNull(detectPrompt(ask(1, listOf("Yes", "No", "Chat about this")), true, "claude"))
        assertNull(typedChatPair(chatOnly.choices))
        assertEquals(chatOnly.choices, shownChoices(chatOnly.choices))
    }

    // ---- the alert's typed answer, end to end ---------------------------------------------------

    @Test fun theAlertsTypedAnswerMovesToTheTypedRowThenTypes() {
        val fake = Fake(ask(1))
        open(fake)
        val p = checkNotNull(detectPrompt(ask(1), true, "claude"))
        val row = checkNotNull(typedChatPair(p.choices)).first
        pump(1)
        assertTrue(AppBus.emit(NotifyAction(server.id, fake.pane.id, "keep both copies", true, p.question, label = row.label, agent = "claude",
            isReply = true, options = alertOptionsSig(p.choices), identity = alertIdentity(p), typedRow = row.keys)))
        pump(3)
        assertEquals(listOf("keys down", "text keep both copies", "keys enter"), fake.writes)
    }

    @Test fun theAlertsTypedAnswerSendsNothingWhenTheQuestionChanged() {
        val fake = Fake(ask(1))
        open(fake)
        val p = checkNotNull(detectPrompt(ask(1, listOf("No", "Type something.", "Chat about this")), true, "claude"))
        pump(1)
        assertTrue(AppBus.emit(NotifyAction(server.id, fake.pane.id, "keep both copies", true, p.question, label = "Type something.", agent = "claude",
            isReply = true, options = alertOptionsSig(p.choices), identity = alertIdentity(p), typedRow = "2")))
        pump(3)
        assertTrue(fake.writes.toString(), fake.writes.isEmpty())
    }

    private fun open(fake: Fake) {
        field("herdr").set(vm, fake.client)
        state("servers", listOf(server))
        state("current", server)
        state("link", Link.CONNECTED)
        state("panes", listOf(fake.pane))
    }

    private fun pump(seconds: Int) {
        repeat(seconds * 4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)); Thread.sleep(15) }
    }

    private fun field(name: String) = vm.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value
    }

    /** One Claude pane waiting on [screen]'s question; Down moves its highlight. */
    private inner class Fake(@Volatile var screen: String) : Herdr.Commands {
        val ssh = HerdrSsh("127.0.0.1", 2, "fake", Credentials(null, null), null, { null })
        val client = Herdr(ssh, "/fixture/herdr", Backend.TUI, null, this)
        override var connection: Any? = Any()
        val pane = Pane("w1:p1", "w1:t1", "w1", "claude", Status.NEEDS_YOU, "", "/fixture", scrollOffset = 0, terminalId = "t1")
        private val paneJson = """{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","agent":"claude","agent_status":"blocked","cwd":"/fixture","terminal_id":"t1","scroll":{"offset_from_bottom":0}}"""
        val writes = java.util.Collections.synchronizedList(mutableListOf<String>())
        private var highlight = 1

        override suspend fun bulk(command: String): ExecResult = answer(command)
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = answer(command)

        @Synchronized private fun answer(command: String): ExecResult {
            if ("boot_id" in command) return ok("")
            return when {
                "'pane' 'list'" in command -> ok("""{"result":{"panes":[$paneJson]}}
@@herdrdroid-tabs@@
{"result":{"tabs":[{"tab_id":"w1:t1","label":"claude"}]}}""")
                "'pane' 'get' 'w1:p1'" in command -> ok("""{"result":{"pane":$paneJson}}""")
                "'--source' 'recent'" in command || "'--source' 'visible'" in command -> ok(screen)
                "'send-keys'" in command -> {
                    val key = Regex("""'send-keys' 'w1:p1' (.*)$""").find(command)?.groupValues?.get(1)?.replace("'", "") ?: command
                    writes += "keys $key"
                    if (key == "down") { highlight++; screen = ask(highlight) }
                    if (key == "up") { highlight--; screen = ask(highlight) }
                    ok("")
                }
                "'send-text'" in command -> { writes += "text " + (Regex("""'send-text' 'w1:p1' '(.*)'$""").find(command)?.groupValues?.get(1) ?: command); ok("") }
                "'agent' 'prompt'" in command -> { writes += "prompt $command"; ok("") }
                else -> ExecResult(1, "", "not in the fake: $command")
            }
        }
        private fun ok(out: String) = ExecResult(0, out, "")
    }
}
