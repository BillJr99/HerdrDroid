package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.AppViewModel.CrossServerState
import com.billmongan.herdrdroid.fleet.Fleet
import com.billmongan.herdrdroid.fleet.FleetState
import com.billmongan.herdrdroid.herdr.*
import com.billmongan.herdrdroid.ssh.*
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.1.5: a frame the agent renders in the open chat becomes a card. 2.2.0: the card also opens a
 * Yes/No dialog; Yes (or Approve on the card) hands the frame's own code to the confirm path, which
 * delivers through the relay exactly once. Nobody types the code, and it never reaches a pane.
 */
@RunWith(AndroidJUnit4::class)
class AgentMessageRelayTest {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val source = Endpoint("Source")
    private val destination = Endpoint("Destination")
    private val exact = "Please review the build.\nKeep  both spaces."
    private val code = "K7P3QX"
    private val sendTo = "Claude on Destination"
    private val prompt = "'/fixture/herdr' 'agent' 'prompt' 'p1' '$exact'"
    private var now = 5_000_000L
    private lateinit var fleet: Fleet

    init {
        // 2.1.4's notice to every agent would write to both fixtures as soon as messaging is on;
        // this test is about the relay alone (CrossServerNoticeDeliveryTest covers the notice).
        vm.changeCrossServerNotice(false)
        vm.crossServerClock = { now }
        vm.onForeground()
        field(vm, "herdr").set(vm, source.client)
        state(vm, "servers", listOf(source.server, destination.server))
        state(vm, "current", source.server)
        state(vm, "panes", listOf(source.pane))
        vm.screen = Screen.Agent("p1")
        fleet = Fleet(vm.viewModelScope, { Credentials(null, null) }, { null }, {})
        destinationStatus(Status.IDLE)
        field(fleet, "clients").set(fleet, mutableMapOf(destination.server.id to (destination.ssh to destination.client)))
        field(vm, "fleet").set(vm, fleet)
    }

    @After fun close() {
        vm.clearForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun destinationStatus(s: Status) =
        state(fleet, "states", mapOf(destination.server.id to FleetState(destination.server, listOf(destination.pane.copy(status = s)), null, 0)))

    @Test fun aFrameOpensTheDialogAndYesDeliversItOnceWithTheFramesCode() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame()
        assertEquals(sendTo, card.requested)
        assertEquals(exact, card.text)
        assertEquals(destination.server.id, checkNotNull(card.choice).server.id)
        assertEquals(CrossServerState.PENDING, card.state)
        assertEquals(card.id, vm.crossServerDialogFor("p1")?.id)
        val approval = checkNotNull(vm.crossServerApprovalOf(card))
        assertEquals("the app supplies the frame's own code", code, approval.code)
        assertEquals(destination.server.id, approval.server)
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
        assertTrue(vm.approveCrossServer(approval))
        // A second tap while it sends, and again after, sends nothing more.
        assertFalse(vm.approveCrossServer(approval))
        assertEquals(CrossServerState.SENT, settle().state)
        assertFalse(vm.approveCrossServer(approval))
        assertNull(vm.crossServerDialogFor("p1"))
        vm.readPane("p1")
        assertEquals(1, vm.crossServerCardsFor("p1").size)
        assertEquals(listOf(prompt), destination.writes)
        assertTrue("the code never reaches the source pane", source.writes.isEmpty())
    }

    @Test fun noCancelsAndSpendsTheCode() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame()
        val approval = checkNotNull(vm.crossServerApprovalOf(card))
        vm.cancelCrossServerCard(card)
        assertEquals(CrossServerState.CANCELLED, vm.crossServerCardsFor("p1").single().state)
        assertNull(vm.crossServerDialogFor("p1"))
        assertFalse(vm.approveCrossServer(approval))
        vm.closeCrossServerCard(card)
        vm.readPane("p1")
        assertTrue("a cancelled code shows no new card", vm.crossServerCardsFor("p1").isEmpty())
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
    }

    @Test fun backLeavesItPendingOnTheCardWhereApproveStillSends() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame()
        vm.deferCrossServerDialog(card)
        assertNull(vm.crossServerDialogFor("p1"))
        vm.readPane("p1")
        assertNull("a dialog put aside does not come back by itself", vm.crossServerDialogFor("p1"))
        assertEquals(CrossServerState.PENDING, vm.crossServerCardsFor("p1").single().state)
        assertTrue(vm.approveCrossServer(checkNotNull(vm.crossServerApprovalOf(card))))
        assertEquals(CrossServerState.SENT, settle().state)
        assertEquals(listOf(prompt), destination.writes)
    }

    @Test fun theCodeInTheAgentsOwnOutputConfirmsNothing() = runBlocking {
        vm.changeAgentMessaging(true)
        frame()
        source.frame = "⏺ " + fixtureMessageFrame(code, exact, sendTo) + "\nUser: $code\nYes\n$code\n❯ $code"
        vm.readPane("p1")
        settle()
        assertEquals(CrossServerState.PENDING, vm.crossServerCardsFor("p1").single().state)
        assertTrue(destination.writes.isEmpty())
    }

    @Test fun anExpiredProposalIsRefusedAndItsDialogGoes() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame()
        val approval = checkNotNull(vm.crossServerApprovalOf(card))
        now += 10 * 60_000L
        // Past its ten minutes the dialog is gone even before the expiry job has run.
        assertNull(vm.crossServerDialogFor("p1"))
        assertFalse(vm.approveCrossServer(approval))
        assertEquals(CrossServerState.EXPIRED, vm.crossServerCardsFor("p1").single().state)
        assertTrue(destination.writes.isEmpty())
        // A frame seen first more than ten minutes ago shows no card after the cards are cleared.
        vm.onBackground()
        state(vm, "watchOthers", false)
        vm.onForeground()
        vm.readPane("p1")
        assertTrue(vm.crossServerCardsFor("p1").isEmpty())
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
    }

    /** A dialog is bound to its proposal's id, code and destination; it cannot approve another one. */
    @Test fun aStaleDialogCannotApproveAnotherOrNewerProposal() = runBlocking {
        vm.changeAgentMessaging(true)
        vm.readPane("p1")
        val newer = "M4N5P6"
        source.frame = "⏺ " + fixtureMessageFrame(code, exact, sendTo) + "\n" + fixtureMessageFrame(newer, "Second message.", sendTo)
        vm.readPane("p1")
        val (older, latest) = vm.crossServerCardsFor("p1")
        assertEquals(newer, latest.code)
        assertEquals("the newest proposal is asked about first", latest.id, vm.crossServerDialogFor("p1")?.id)
        val olderApproval = checkNotNull(vm.crossServerApprovalOf(older))
        val latestApproval = checkNotNull(vm.crossServerApprovalOf(latest))
        // Mixed bindings are refused and spend nothing.
        assertFalse(vm.approveCrossServer(olderApproval.copy(code = newer)))
        assertFalse(vm.approveCrossServer(latestApproval.copy(code = code)))
        assertFalse(vm.approveCrossServer(olderApproval.copy(id = latest.id)))
        assertFalse(vm.approveCrossServer(latestApproval.copy(pane = "elsewhere")))
        assertFalse(vm.approveCrossServer(latestApproval.copy(server = source.server.id)))
        assertTrue(vm.crossServerCardsFor("p1").all { it.state == CrossServerState.PENDING })
        assertTrue(destination.writes.isEmpty())
        // No on the newest; the older one's dialog comes up next, and nothing was auto-approved.
        vm.cancelCrossServerCard(latest)
        assertEquals(older.id, vm.crossServerDialogFor("p1")?.id)
        assertTrue(destination.writes.isEmpty())
        assertTrue(vm.approveCrossServer(olderApproval))
        repeat(100) { shadowOf(Looper.getMainLooper()).idle(); if (vm.crossServerCardsFor("p1").none { it.state == CrossServerState.SENDING }) return@repeat; Thread.sleep(5) }
        assertEquals(CrossServerState.SENT, vm.crossServerCardsFor("p1").first { it.id == older.id }.state)
        assertEquals(listOf(prompt), destination.writes)
    }

    @Test fun aSpentCodeShowsNoCardAfterARestart() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame()
        assertTrue(vm.approveCrossServer(checkNotNull(vm.crossServerApprovalOf(card))))
        settle()
        assertEquals(1, destination.writes.size)
        // A second app on the same saved settings: the frame is still on screen.
        val again = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val ledger = field(again, "crossServerCodes").get(again)
            assertTrue((ledger as CrossServerCodeLedger).spent("${source.server.id}|p1|$code"))
        } finally { again.clearForTest() }
    }

    @Test fun aNewFrameAfterBackgroundShowsAgainUntilItsTenMinutes() = runBlocking {
        vm.changeAgentMessaging(true)
        frame()
        vm.onBackground()
        assertTrue(vm.crossServerCardsFor("p1").isEmpty())
        assertNull("no dialog in the background", vm.crossServerDialogFor("p1"))
        state(vm, "watchOthers", false)
        vm.onForeground()
        now += 60_000
        vm.readPane("p1")
        val card = vm.crossServerCardsFor("p1").single()
        assertEquals(CrossServerState.PENDING, card.state)
        assertEquals(5_000_000L + 10 * 60_000L, card.deadline)
    }

    @Test fun noDialogOrApprovalWhileLocked() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame()
        val approval = checkNotNull(vm.crossServerApprovalOf(card))
        state(vm, "locked", true)
        assertNull(vm.crossServerDialogFor("p1"))
        assertFalse(vm.approveCrossServer(approval))
        assertTrue(destination.writes.isEmpty())
    }

    @Test fun noCardWhileMessagingIsOff() = runBlocking {
        source.frame = "⏺ " + fixtureMessageFrame(code, exact, sendTo)
        vm.readPane("p1")
        assertTrue(vm.crossServerCardsFor("p1").isEmpty())
        assertNull(vm.crossServerDialogFor("p1"))
    }

    @Test fun anUnknownDestinationMustBeChosenBeforeTheDialogOrApprove() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame(to = "Gemini on Elsewhere")
        assertNull(card.choice)
        assertNull(vm.crossServerApprovalOf(card))
        assertNull(vm.crossServerDialogFor("p1"))
        val choices = vm.crossServerChoices(card)
        assertEquals(listOf(destination.server.id), choices.map { it.server.id })
        assertTrue(destination.writes.isEmpty())
        vm.chooseCrossServerDestination(card, choices.single())
        val chosen = vm.crossServerCardsFor("p1").single()
        assertEquals(chosen.id, vm.crossServerDialogFor("p1")?.id)
        assertTrue(vm.approveCrossServer(checkNotNull(vm.crossServerApprovalOf(chosen))))
        assertEquals(CrossServerState.SENT, settle().state)
        assertEquals(listOf(prompt), destination.writes)
    }

    /**
     * 2.2.0 step 1, the reason the typed code "doesn't really let me send": the frame was read
     * before the other server's watcher had its panes, so the card kept no destination; once the
     * name matched exactly one agent the card offered no choice (one match means no choices) and
     * the typed code only ever answered "Choose a destination". Now the next read fills it in.
     */
    @Test fun aDestinationThatArrivesAfterTheCardIsFilledInAndSends() = runBlocking {
        vm.changeAgentMessaging(true)
        state(fleet, "states", emptyMap<String, FleetState>())
        val card = frame()
        assertNull(card.choice)
        assertNull(vm.crossServerDialogFor("p1"))
        destinationStatus(Status.IDLE)
        // The 2.1.5 dead end: a single match and therefore nothing to choose from.
        assertTrue(vm.crossServerChoices(vm.crossServerCardsFor("p1").single()).isEmpty())
        vm.readPane("p1")
        val filled = vm.crossServerCardsFor("p1").single()
        assertEquals(destination.server.id, checkNotNull(filled.choice).server.id)
        assertEquals(filled.id, vm.crossServerDialogFor("p1")?.id)
        assertTrue(vm.approveCrossServer(checkNotNull(vm.crossServerApprovalOf(filled))))
        assertEquals(CrossServerState.SENT, settle().state)
        assertEquals(listOf(prompt), destination.writes)
    }

    @Test fun theAgentsNameAloneResolvesWhenItIsUnique() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame(to = "claude")
        assertEquals(destination.server.id, checkNotNull(card.choice).server.id)
        assertTrue(vm.crossServerChoices(card).isEmpty())
    }

    /**
     * 2.2.1: a busy destination is a transient refusal. Nothing was typed, so the card says why and
     * stays pending with its code unspent and its deadline unchanged; its dialog does not pop up
     * again, and Approve on the card sends once when the destination is idle.
     */
    @Test fun aBusyDestinationSaysWhyAndCanBeApprovedAgainOnce() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame()
        val approval = checkNotNull(vm.crossServerApprovalOf(card))
        destinationStatus(Status.WORKING)
        assertTrue(vm.approveCrossServer(approval))
        val done = settle()
        assertEquals(CrossServerState.PENDING, done.state)
        assertEquals(card.deadline, done.deadline)
        val note = checkNotNull(done.note)
        assertTrue(note, note.startsWith("Not sent to Claude on Destination (pane p1): " + RelayRefusal.WORKING.why))
        assertTrue(note, "Retry to try again" in note)
        assertNull("no dialog loop after a refusal", vm.crossServerDialogFor("p1"))
        assertFalse(spent(card))
        assertTrue(destination.writes.isEmpty())
        destinationStatus(Status.IDLE)
        assertTrue(vm.approveCrossServer(checkNotNull(vm.crossServerApprovalOf(done))))
        assertEquals(CrossServerState.SENT, settle().state)
        assertFalse(vm.approveCrossServer(approval))
        settle()
        assertEquals(listOf(prompt), destination.writes)
        assertTrue(spent(card))
        assertTrue(source.writes.isEmpty())
    }

    /**
     * Bill's report (2.2.1): CLAUDE2 on the other server keeps background shells running after its
     * turn ("3 shells" in Claude Code 2.1.330's footer). The app's sweep marks it busy the way it
     * marks every idle pane with work ([withBusy]), and the relay refused it with "Not sent. Check
     * that the destination is still connected and idle ...", though Claude takes input meanwhile.
     * Now an idle Claude with an empty box and only background work gets the message, once.
     */
    @Test fun anIdleClaudeWithBackgroundShellsGetsTheMessage() = runBlocking {
        vm.changeAgentMessaging(true)
        destination.busy = 3
        destination.visible = resource("/screens/claude/derived-2.1.330-shells/idle-3-shells.ansi")
        val approval = checkNotNull(vm.crossServerApprovalOf(frame()))
        sweptDestination(3)
        assertTrue(vm.approveCrossServer(approval))
        val done = settle()
        assertEquals(done.note, CrossServerState.SENT, done.state)
        assertEquals(listOf(prompt), destination.writes)
        assertTrue(source.writes.isEmpty())
    }

    /** The captured idle CLAUDE2 (2.1.330): 3 shells and Claude's faint prompt suggestion in the empty box. Sent once. */
    @Test fun anIdleClaudeWithShellsAndADimSuggestionGetsTheMessageOnce() = runBlocking {
        vm.changeAgentMessaging(true)
        destination.busy = 3
        destination.visible = resource("/screens/claude/derived-2.1.330-shells/idle-3-shells-suggestion.ansi")
        val approval = checkNotNull(vm.crossServerApprovalOf(frame()))
        sweptDestination(3)
        assertTrue(vm.approveCrossServer(approval))
        val done = settle()
        assertEquals(done.note, CrossServerState.SENT, done.state)
        assertFalse(vm.approveCrossServer(approval))
        settle()
        assertEquals(listOf(prompt), destination.writes)
        assertTrue(source.writes.isEmpty())
    }

    /** The same Claude in the middle of a turn ("esc to interrupt" among its footer chips) is not typed into, whatever herdr says. */
    @Test fun aClaudeWhoseFooterSaysWorkingIsRefusedWithItsReason() = runBlocking {
        vm.changeAgentMessaging(true)
        destination.busy = 3
        destination.visible = resource("/screens/claude/derived-2.1.330-shells/working-3-shells.ansi")
        val approval = checkNotNull(vm.crossServerApprovalOf(frame()))
        sweptDestination(3)
        assertTrue(vm.approveCrossServer(approval))
        val done = settle()
        assertEquals(CrossServerState.PENDING, done.state)
        assertTrue(checkNotNull(done.note).contains(RelayRefusal.WORKING.why))
        assertTrue(destination.writes.isEmpty())
    }

    /** Agents where typing could interrupt background work still wait for it, and the card says so. */
    @Test fun aHermesWithBackgroundWorkWaitsAndSaysSo() = runBlocking {
        vm.changeAgentMessaging(true)
        destination.agent = "hermes"
        destination.busy = 1
        destinationStatus(Status.IDLE)
        val card = frame(to = "hermes")
        val approval = checkNotNull(vm.crossServerApprovalOf(card))
        sweptDestination(1)
        assertTrue(vm.approveCrossServer(approval))
        val done = settle()
        assertEquals(CrossServerState.PENDING, done.state)
        assertTrue(checkNotNull(done.note).contains(RelayRefusal.BACKGROUND.why))
        assertTrue(destination.writes.isEmpty())
        destination.busy = 0
        destinationStatus(Status.IDLE)
        assertTrue(vm.approveCrossServer(checkNotNull(vm.crossServerApprovalOf(done))))
        assertEquals(CrossServerState.SENT, settle().state)
        assertEquals(1, destination.writes.size)
    }

    /** Each screen-level refusal on an idle destination is named on the card, and nothing is typed. */
    @Test fun screenRefusalsNameTheirCheck() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame()
        for ((screen, why) in listOf(
            "Output only, the input box is not drawn" to RelayRefusal.NO_INPUT,
            "❯ unsent words" to RelayRefusal.DRAFT,
            resource("/screens/claude/captured-2.1.329/single-question.txt") to RelayRefusal.QUESTION,
        )) {
            destination.visible = screen
            val live = vm.crossServerCardsFor("p1").single()
            assertTrue(why.name, vm.approveCrossServer(checkNotNull(vm.crossServerApprovalOf(live))))
            val done = settle()
            assertEquals(why.name, CrossServerState.PENDING, done.state)
            assertTrue(done.note, checkNotNull(done.note).contains(why.why))
            assertFalse(spent(card))
        }
        assertTrue(destination.writes.isEmpty() && source.writes.isEmpty())
    }

    @Test fun aReplacedDestinationIsRefused() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame()
        val approval = checkNotNull(vm.crossServerApprovalOf(card))
        destination.terminal = "replacement"
        destinationStatus(Status.IDLE)
        assertTrue(vm.approveCrossServer(approval))
        val done = settle()
        assertEquals(CrossServerState.REFUSED, done.state)
        // 2.2.1: not re-approvable: the approval was for the session that was there.
        assertTrue(checkNotNull(done.note).contains(RelayRefusal.REPLACED.why))
        assertTrue(spent(card))
        assertFalse(vm.approveCrossServer(approval))
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
    }

    @Test fun aLostAcknowledgementShowsFailedAndNeverReplays() = runBlocking {
        vm.changeAgentMessaging(true)
        val approval = checkNotNull(vm.crossServerApprovalOf(frame()))
        destination.loseAck = true
        assertTrue(vm.approveCrossServer(approval))
        val done = settle()
        assertEquals(CrossServerState.FAILED, done.state)
        assertEquals(RelayResult.UNCERTAIN.feedback, done.note)
        assertFalse(vm.approveCrossServer(approval))
        settle()
        assertEquals(1, destination.writes.size)
    }

    @Test fun aReplacedSourceIsRefused() = runBlocking {
        vm.changeAgentMessaging(true)
        val approval = checkNotNull(vm.crossServerApprovalOf(frame()))
        source.terminal = "replacement"
        state(vm, "panes", listOf(source.pane))
        assertNull(vm.crossServerDialogFor("p1"))
        assertFalse(vm.approveCrossServer(approval))
        settle()
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
    }

    /** The 2.1.4 listening mode and the 2.1.5 typed code are gone from the view model and the screens. */
    @Test fun theListenMenuAndTheTypedCodeAreGone() {
        val names = AppViewModel::class.java.declaredMethods.map { it.name }
        for (old in listOf("armAgentMessage", "canArmAgentMessage", "agentMessage", "confirmAgentMessage", "bindAgentMessage",
                "dismissAgentMessage", "crossServerCodeReply")) {
            assertFalse(old, names.any { it == old || it.startsWith("$old\$") })
        }
        val screen = java.io.File("src/main/java/com/billmongan/herdrdroid/ui/AgentScreen.kt")
        if (screen.exists()) {
            val text = screen.readText()
            assertFalse(text.contains("Listen for agent proposal"))
            assertFalse(text.contains("Copy proposal instructions"))
            assertFalse(text.contains("composerSend") || text.contains("crossServerCodeReply"))
        }
        val review = java.io.File("src/main/java/com/billmongan/herdrdroid/ui/AgentMessageReview.kt")
        if (review.exists()) {
            val text = review.readText()
            assertFalse(text.contains("proposalInstructions("))
            assertFalse(text.contains("fun composerSend"))
            assertFalse(text.contains("type the code") || text.contains("in this chat's message box"))
        }
    }

    // ---- 2.2.2: Retry, Force Send and Cancel on a card pending again after a refusal ------------

    /** A working destination: herdr says so on the watched pane and on a fresh read; the card offers Retry and Force Send. */
    private suspend fun refusedBusy(): AppViewModel.CrossServerCard {
        destination.status = "working"
        destinationStatus(Status.WORKING)
        val card = vm.crossServerCardsFor("p1").singleOrNull() ?: frame()
        assertTrue(vm.approveCrossServer(checkNotNull(vm.crossServerApprovalOf(card))))
        val done = settle()
        assertEquals(CrossServerState.PENDING, done.state)
        assertEquals(RelayRefusal.WORKING, done.refusal)
        assertTrue(vm.crossServerForceOffered(done))
        return done
    }

    @Test fun aFreshCardIsApproveNotRetryAndOffersNoForce() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame()
        assertNull(card.refusal)
        assertFalse(vm.crossServerForceOffered(card))
        assertFalse(vm.forceCrossServer(checkNotNull(vm.crossServerApprovalOf(card))))
        assertEquals(CrossServerState.PENDING, vm.crossServerCardsFor("p1").single().state)
        assertTrue(destination.writes.isEmpty())
    }

    @Test fun retryAfterNotIdleSendsOnceWhenIdle() = runBlocking {
        vm.changeAgentMessaging(true)
        val busy = refusedBusy()
        assertTrue(checkNotNull(busy.note), "Retry to try again when it is idle, or Force Send" in busy.note!!)
        assertFalse(spent(busy))
        destination.status = "idle"
        destinationStatus(Status.IDLE)
        val approval = checkNotNull(vm.crossServerApprovalOf(busy))
        assertTrue(vm.approveCrossServer(approval))
        assertFalse("a second Retry while it sends does nothing", vm.approveCrossServer(approval))
        assertEquals(CrossServerState.SENT, settle().state)
        assertFalse(vm.approveCrossServer(approval))
        assertFalse(vm.forceCrossServer(approval))
        settle()
        assertEquals(listOf(prompt), destination.writes)
        assertTrue(spent(busy))
    }

    /** Claude in the middle of a turn ("esc to interrupt", 3 shells) with an empty box: Force Send types it in once. */
    @Test fun forceSendOnAWorkingClaudeWithAnEmptyBoxSendsExactlyOnce() = runBlocking {
        vm.changeAgentMessaging(true)
        destination.busy = 3
        destination.visible = resource("/screens/claude/derived-2.1.330-shells/working-3-shells.ansi")
        val busy = refusedBusy()
        assertTrue(destination.writes.isEmpty())
        val approval = checkNotNull(vm.crossServerApprovalOf(busy))
        assertTrue(vm.forceCrossServer(approval))
        // Double taps on Send anyway, Retry or Approve while it sends, and after, send nothing more.
        assertFalse(vm.forceCrossServer(approval))
        assertFalse(vm.approveCrossServer(approval))
        val done = settle()
        assertEquals(done.note, CrossServerState.SENT, done.state)
        assertFalse(vm.forceCrossServer(approval))
        assertFalse(vm.approveCrossServer(approval))
        settle()
        assertEquals(listOf(prompt), destination.writes)
        assertTrue("never herdr's own --force", destination.writes.none { "--force" in it })
        assertTrue(spent(busy))
        assertTrue(source.writes.isEmpty())
        // A restart on the same saved settings: the code is spent, so the frame raises no card to send again.
        val again = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            assertTrue((field(again, "crossServerCodes").get(again) as CrossServerCodeLedger).spent(busy.id))
        } finally { again.clearForTest() }
        vm.closeCrossServerCard(done)
        vm.readPane("p1")
        assertTrue(vm.crossServerCardsFor("p1").isEmpty())
        assertEquals(1, destination.writes.size)
    }

    /** Hermes with background work: BACKGROUND is not forceable, so Retry and Cancel only, and a forced call sends nothing. */
    @Test fun backgroundWorkIsNeverForced() = runBlocking {
        vm.changeAgentMessaging(true)
        destination.agent = "hermes"
        destination.busy = 1
        destinationStatus(Status.IDLE)
        val card = frame(to = "hermes")
        sweptDestination(1)
        assertTrue(vm.approveCrossServer(checkNotNull(vm.crossServerApprovalOf(card))))
        val done = settle()
        assertEquals(RelayRefusal.BACKGROUND, done.refusal)
        assertFalse(vm.crossServerForceOffered(done))
        assertFalse(vm.forceCrossServer(checkNotNull(vm.crossServerApprovalOf(done))))
        assertTrue(destination.writes.isEmpty())
    }

    /** Only Claude Code queues typed input during a turn: a working OpenCode (or any other agent) is never offered Force Send. */
    @Test fun aWorkingOpenCodeIsNeverOfferedForce() = runBlocking {
        vm.changeAgentMessaging(true)
        destination.agent = "opencode"
        destination.status = "working"
        destinationStatus(Status.WORKING)
        val card = frame(to = "opencode")
        assertTrue(vm.approveCrossServer(checkNotNull(vm.crossServerApprovalOf(card))))
        val done = settle()
        assertEquals(RelayRefusal.WORKING, done.refusal)
        assertFalse(vm.crossServerForceOffered(done))
        assertFalse(done.note!!.contains("Force Send"))
        assertFalse(vm.forceCrossServer(checkNotNull(vm.crossServerApprovalOf(done))))
        assertTrue(destination.writes.isEmpty())
    }

    /** A question opening between the forced send's first screen read and the write: refused, nothing typed, card pending. */
    @Test fun aDialogOpeningBeforeTheForcedWriteIsRefused() = runBlocking {
        vm.changeAgentMessaging(true)
        destination.busy = 3
        val working = resource("/screens/claude/derived-2.1.330-shells/working-3-shells.ansi")
        destination.visible = working
        val busy = refusedBusy()
        destination.visibleNext.addLast(working)
        destination.visible = resource("/screens/claude/captured-2.1.329/single-question.txt")
        assertTrue(vm.forceCrossServer(checkNotNull(vm.crossServerApprovalOf(busy))))
        val done = settle()
        assertEquals(done.note, CrossServerState.PENDING, done.state)
        assertEquals(RelayRefusal.QUESTION, done.refusal)
        assertTrue("the first read passed and the second was read", destination.visibleNext.isEmpty())
        assertFalse(vm.crossServerForceOffered(done))
        assertFalse(spent(done))
        assertTrue(destination.writes.isEmpty())
    }

    /** A scrolled-back destination says so, offers no Force Send, and a forced call can't loop on it. */
    @Test fun aScrolledBackDestinationSaysSoAndOffersNoForce() = runBlocking {
        vm.changeAgentMessaging(true)
        val busy = refusedBusy()
        destination.scroll = 5
        assertTrue(vm.forceCrossServer(checkNotNull(vm.crossServerApprovalOf(busy))))
        val done = settle()
        assertEquals(CrossServerState.PENDING, done.state)
        assertEquals(RelayRefusal.SCROLLED, done.refusal)
        assertTrue(done.note!!.contains("scrolled back at the computer"))
        assertFalse(vm.crossServerForceOffered(done))
        assertTrue(destination.writes.isEmpty())
    }

    /** herdr refuses a blocked agent before any input (agent_blocked): a transient QUESTION, the code released, the card pending. */
    @Test fun herdrsAgentBlockedIsATransientRefusal() = runBlocking {
        vm.changeAgentMessaging(true)
        destination.busy = 3
        destination.visible = resource("/screens/claude/derived-2.1.330-shells/working-3-shells.ansi")
        val busy = refusedBusy()
        destination.promptError = "error: agent_blocked: the agent is waiting for input"
        assertTrue(vm.forceCrossServer(checkNotNull(vm.crossServerApprovalOf(busy))))
        val done = settle()
        assertEquals(done.note, CrossServerState.PENDING, done.state)
        assertEquals(RelayRefusal.QUESTION, done.refusal)
        assertFalse(spent(done))
        assertTrue(destination.writes.isEmpty())
        // Once herdr takes it, Retry on an idle Claude sends once.
        destination.promptError = null
        destination.status = "idle"
        destinationStatus(Status.IDLE)
        destination.visible = "❯ "
        assertTrue(vm.approveCrossServer(checkNotNull(vm.crossServerApprovalOf(done))))
        assertEquals(CrossServerState.SENT, settle().state)
        assertEquals(1, destination.writes.size)
    }

    /** Force skips only the busy checks: a question, a draft, a panel or no input line still refuse it, and the card stays pending. */
    @Test fun forceSendIsStillRefusedByEveryOtherCheck() = runBlocking {
        vm.changeAgentMessaging(true)
        val panel = buildString {
            appendLine("  ┌──────────────────────────────────────────────────────────────────────┐")
            appendLine("  │ ${"Status".padEnd(68)} │")
            appendLine("  │ ${"Model      sample".padEnd(68)} │")
            appendLine("  │ ${"Esc close".padEnd(68)} │")
            appendLine("  └──────────────────────────────────────────────────────────────────────┘")
            appendLine()
            appendLine("❯ ")
        }
        for ((screen, why) in listOf(
            resource("/screens/claude/captured-2.1.329/single-question.txt") to RelayRefusal.QUESTION,
            "❯ unsent words" to RelayRefusal.DRAFT,
            panel to RelayRefusal.USAGE,
            "Output only, the input box is not drawn" to RelayRefusal.NO_INPUT,
        )) {
            destination.visible = "❯ "
            val busy = refusedBusy()
            destination.visible = screen
            assertTrue(why.name, vm.forceCrossServer(checkNotNull(vm.crossServerApprovalOf(busy))))
            val done = settle()
            assertEquals(why.name, CrossServerState.PENDING, done.state)
            assertEquals(why.name, why, done.refusal)
            assertTrue(done.note, checkNotNull(done.note).contains(why.why))
            assertFalse("${why.name}: no Force Send for this reason", vm.crossServerForceOffered(done))
            assertFalse(why.name, vm.forceCrossServer(checkNotNull(vm.crossServerApprovalOf(done))))
            assertFalse(why.name, spent(done))
        }
        assertTrue(destination.writes.isEmpty() && source.writes.isEmpty())
    }

    @Test fun forceSendIsRefusedForAReplacedDestination() = runBlocking {
        vm.changeAgentMessaging(true)
        val busy = refusedBusy()
        val approval = checkNotNull(vm.crossServerApprovalOf(busy))
        destination.terminal = "replacement"
        destinationStatus(Status.WORKING)
        assertTrue(vm.forceCrossServer(approval))
        val done = settle()
        assertEquals(CrossServerState.REFUSED, done.state)
        assertTrue(checkNotNull(done.note).contains(RelayRefusal.REPLACED.why))
        assertTrue(spent(done))
        assertFalse(vm.forceCrossServer(approval))
        assertTrue(destination.writes.isEmpty())
    }

    /** Refusals other than busy (a draft, a question, no input line) offer Retry and Cancel only. */
    @Test fun forceIsNotOfferedForOtherRefusals() = runBlocking {
        vm.changeAgentMessaging(true)
        frame()
        for ((screen, why) in listOf(
            "❯ unsent words" to RelayRefusal.DRAFT,
            resource("/screens/claude/captured-2.1.329/single-question.txt") to RelayRefusal.QUESTION,
            "Output only, the input box is not drawn" to RelayRefusal.NO_INPUT,
        )) {
            destination.visible = screen
            val live = vm.crossServerCardsFor("p1").single()
            assertTrue(vm.approveCrossServer(checkNotNull(vm.crossServerApprovalOf(live))))
            val done = settle()
            assertEquals(why, done.refusal)
            assertTrue(done.note, "Retry to try again before" in checkNotNull(done.note))
            assertFalse(done.note!!.contains("Force Send"))
            assertFalse(vm.crossServerForceOffered(done))
            assertFalse(vm.forceCrossServer(checkNotNull(vm.crossServerApprovalOf(done))))
        }
        assertTrue(destination.writes.isEmpty())
    }

    private suspend fun frame(to: String = sendTo): AppViewModel.CrossServerCard {
        vm.readPane("p1")
        assertTrue(vm.crossServerCardsFor("p1").isEmpty())
        source.frame = "⏺ Sure. Confirm on the phone.\n" + fixtureMessageFrame(code, exact, to)
        vm.readPane("p1")
        return vm.crossServerCardsFor("p1").single()
    }

    /** Lets a launched delivery finish; returns the card. */
    private fun settle(): AppViewModel.CrossServerCard {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (vm.crossServerCardsFor("p1").singleOrNull()?.state != CrossServerState.SENDING) return vm.crossServerCardsFor("p1").single()
            Thread.sleep(5)
        }
        return vm.crossServerCardsFor("p1").single()
    }

    private class Endpoint(name: String) : Herdr.Commands {
        val server = Server(id = name, name = name, host = "127.0.0.1", port = 1, user = "fixture", herdrPath = "/fixture/herdr")
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        val client = Herdr(ssh, server.herdrPath, Backend.TUI, null, this)
        override var connection: Any? = Any()
        var terminal = name
        var frame = "Normal source output"
        var loseAck = false
        var agent = "claude"
        var busy = 0
        var visible = "❯ "
        /** herdr's agent_status on a fresh `pane get`. */
        var status = "idle"
        /** herdr's scroll offset on a fresh `pane get`. */
        var scroll = 0
        /** 2.2.2: screens the next visible reads return, in turn, before [visible]. */
        val visibleNext = ArrayDeque<String>()
        /** 2.2.2: herdr refuses the agent prompt with this, before any input (agent_blocked). */
        var promptError: String? = null
        val writes = mutableListOf<String>()
        val pane get() = Pane("p1", "tab", "w1", agent, Status.IDLE, "", "/fixture", scrollOffset = 0, terminalId = terminal)
        override suspend fun bulk(command: String): ExecResult = error("Unexpected bulk command")
        override suspend fun input(command: String, input: ByteArray, expectedConnection: Any): ExecResult {
            assertSame(connection, expectedConnection)
            assertTrue(command.contains("__workcounts__"))
            return ok("""{"ok":true,"busy":{"p1":$busy}}""")
        }
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            expectedConnection?.let { assertSame(connection, it) }
            return when {
                command.endsWith("'pane' 'get' 'p1'") -> ok("""{"result":{"pane":{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"$agent","agent_status":"$status","cwd":"/fixture","terminal_id":"$terminal","scroll":{"offset_from_bottom":$scroll}}}}""")
                "'--source' 'recent'" in command -> ok(frame)
                "'--source' 'visible'" in command -> ok(visibleNext.removeFirstOrNull() ?: visible)
                "'agent' 'prompt' 'p1'" in command && promptError != null -> ExecResult(1, "", promptError!!)
                "'agent' 'prompt' 'p1'" in command -> { writes += command; if (loseAck) ExecResult(1, "", "lost acknowledgement") else ok("") }
                else -> error("Unexpected fixture command: $command")
            }
        }
        private fun ok(out: String) = ExecResult(0, out, "")
    }

    private fun resource(path: String) = checkNotNull(javaClass.getResource(path)).readText()

    private fun spent(card: AppViewModel.CrossServerCard) =
        (field(vm, "crossServerCodes").get(vm) as CrossServerCodeLedger).spent(card.id)

    /** The destination as the app's work sweep leaves it: idle in herdr, with [busy] background jobs. */
    private fun sweptDestination(busy: Int) = state(fleet, "states", mapOf(destination.server.id to
        FleetState(destination.server, withBusy(listOf(destination.pane), mapOf("p1" to busy)), null, 0, mapOf("p1" to busy))))

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state(owner: Any, name: String, value: Any?) {
        (field(owner, "$name\$delegate").get(owner) as MutableState<Any?>).value = value
    }
}
