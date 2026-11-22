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
 * 2.1.5: a frame the agent renders in the open chat becomes a card; typing its code in that pane's
 * composer confirms and delivers it through the relay, and the code never reaches the pane.
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
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun destinationStatus(s: Status) =
        state(fleet, "states", mapOf(destination.server.id to FleetState(destination.server, listOf(destination.pane.copy(status = s)), null, 0)))

    @Test fun aFrameShowsACardAndTypingTheCodeDeliversItWithoutReachingTheSource() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame()
        assertEquals(sendTo, card.requested)
        assertEquals(exact, card.text)
        assertEquals(destination.server.id, checkNotNull(card.choice).server.id)
        assertEquals(CrossServerState.PENDING, card.state)
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
        // Typed back in any case with spaces around it.
        assertTrue(vm.crossServerCodeReply("p1", "  k7p3qx "))
        assertEquals(CrossServerState.SENT, settle().state)
        assertEquals(listOf(prompt), destination.writes)
        assertTrue("the code never reaches the source pane", source.writes.isEmpty())
        // The same code again is caught and sends nothing more.
        assertTrue(vm.crossServerCodeReply("p1", code))
        vm.readPane("p1")
        assertEquals(1, vm.crossServerCardsFor("p1").size)
        assertEquals(1, destination.writes.size)
        assertTrue(source.writes.isEmpty())
    }

    @Test fun otherTextIsNotCaughtAndTheCardStaysPending() = runBlocking {
        vm.changeAgentMessaging(true)
        frame()
        for (other in listOf("hello", "M4N5P6", "K7P3QX now", "K7P3Q", "")) assertFalse(other, vm.crossServerCodeReply("p1", other))
        // A card's code in another pane's composer is not this pane's.
        assertFalse(vm.crossServerCodeReply("p2", code))
        assertEquals(CrossServerState.PENDING, vm.crossServerCardsFor("p1").single().state)
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
    }

    @Test fun theCodeInTheAgentsOwnOutputConfirmsNothing() = runBlocking {
        vm.changeAgentMessaging(true)
        frame()
        source.frame = fixtureMessageFrame(code, exact, sendTo) + "\nUser: $code\n$code\n❯ $code"
        vm.readPane("p1")
        settle()
        assertEquals(CrossServerState.PENDING, vm.crossServerCardsFor("p1").single().state)
        assertTrue(destination.writes.isEmpty())
    }

    @Test fun cancelSendsNothingAndSpendsTheCode() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame()
        vm.cancelCrossServerCard(card)
        assertEquals(CrossServerState.CANCELLED, vm.crossServerCardsFor("p1").single().state)
        assertTrue(vm.crossServerCodeReply("p1", code))
        vm.closeCrossServerCard(card)
        vm.readPane("p1")
        assertTrue("a cancelled code shows no new card", vm.crossServerCardsFor("p1").isEmpty())
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
    }

    @Test fun anExpiredCardFailsAndAnOldFrameShowsNoCard() = runBlocking {
        vm.changeAgentMessaging(true)
        frame()
        now += 10 * 60_000L
        vm.expireCrossServerCards()
        assertEquals(CrossServerState.EXPIRED, vm.crossServerCardsFor("p1").single().state)
        assertTrue(vm.crossServerCodeReply("p1", code))
        assertTrue(destination.writes.isEmpty())
        // A frame seen first more than ten minutes ago shows no card after the cards are cleared.
        vm.onBackground()
        state(vm, "watchOthers", false)
        vm.onForeground()
        vm.readPane("p1")
        assertTrue(vm.crossServerCardsFor("p1").isEmpty())
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
    }

    @Test fun aSpentCodeShowsNoCardAfterARestart() = runBlocking {
        vm.changeAgentMessaging(true)
        frame()
        assertTrue(vm.crossServerCodeReply("p1", code))
        settle()
        assertEquals(1, destination.writes.size)
        // A second app on the same saved settings: the frame is still on screen.
        val again = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val ledger = field(again, "crossServerCodes").get(again)
            assertTrue((ledger as CrossServerCodeLedger).spent("${source.server.id}|p1|$code"))
        } finally { again.viewModelScope.cancel() }
    }

    @Test fun aNewFrameAfterBackgroundShowsAgainUntilItsTenMinutes() = runBlocking {
        vm.changeAgentMessaging(true)
        frame()
        vm.onBackground()
        assertTrue(vm.crossServerCardsFor("p1").isEmpty())
        state(vm, "watchOthers", false)
        vm.onForeground()
        now += 60_000
        vm.readPane("p1")
        val card = vm.crossServerCardsFor("p1").single()
        assertEquals(CrossServerState.PENDING, card.state)
        assertEquals(5_000_000L + 10 * 60_000L, card.deadline)
    }

    @Test fun noCardWhileMessagingIsOff() = runBlocking {
        source.frame = fixtureMessageFrame(code, exact, sendTo)
        vm.readPane("p1")
        assertTrue(vm.crossServerCardsFor("p1").isEmpty())
        assertFalse(vm.crossServerCodeReply("p1", code))
    }

    @Test fun anUnknownDestinationMustBeChosenBeforeTheCodeSends() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame(to = "Gemini on Elsewhere")
        assertNull(card.choice)
        val choices = vm.crossServerChoices(card)
        assertEquals(listOf(destination.server.id), choices.map { it.server.id })
        assertTrue(vm.crossServerCodeReply("p1", code))
        val waiting = vm.crossServerCardsFor("p1").single()
        assertEquals(CrossServerState.PENDING, waiting.state)
        assertTrue(checkNotNull(waiting.note).contains("Choose a destination"))
        assertTrue(destination.writes.isEmpty())
        vm.chooseCrossServerDestination(waiting, choices.single())
        assertTrue(vm.crossServerCodeReply("p1", code))
        assertEquals(CrossServerState.SENT, settle().state)
        assertEquals(listOf(prompt), destination.writes)
    }

    @Test fun theAgentsNameAloneResolvesWhenItIsUnique() = runBlocking {
        vm.changeAgentMessaging(true)
        val card = frame(to = "claude")
        assertEquals(destination.server.id, checkNotNull(card.choice).server.id)
        assertTrue(vm.crossServerChoices(card).isEmpty())
    }

    @Test fun aBusyDestinationIsRefusedOnTheCardAndTheCodeIsSpent() = runBlocking {
        vm.changeAgentMessaging(true)
        frame()
        destinationStatus(Status.WORKING)
        assertTrue(vm.crossServerCodeReply("p1", code))
        val done = settle()
        assertEquals(CrossServerState.REFUSED, done.state)
        assertEquals(RelayResult.REFUSED.feedback, done.note)
        destinationStatus(Status.IDLE)
        assertTrue(vm.crossServerCodeReply("p1", code))
        settle()
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
    }

    @Test fun aLostAcknowledgementShowsFailedAndNeverReplays() = runBlocking {
        vm.changeAgentMessaging(true)
        frame()
        destination.loseAck = true
        assertTrue(vm.crossServerCodeReply("p1", code))
        val done = settle()
        assertEquals(CrossServerState.FAILED, done.state)
        assertEquals(RelayResult.UNCERTAIN.feedback, done.note)
        assertTrue(vm.crossServerCodeReply("p1", code))
        settle()
        assertEquals(1, destination.writes.size)
    }

    @Test fun aReplacedSourceIsRefused() = runBlocking {
        vm.changeAgentMessaging(true)
        frame()
        source.terminal = "replacement"
        state(vm, "panes", listOf(source.pane))
        assertTrue(vm.crossServerCodeReply("p1", code))
        assertEquals(CrossServerState.REFUSED, settle().state)
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
    }

    /** The 2.1.4 listening mode is gone from the view model and the pane menu. */
    @Test fun theListenMenuAndItsModeAreGone() {
        val names = AppViewModel::class.java.declaredMethods.map { it.name }
        for (old in listOf("armAgentMessage", "canArmAgentMessage", "agentMessage", "confirmAgentMessage", "bindAgentMessage", "dismissAgentMessage")) {
            assertFalse(old, names.any { it == old || it.startsWith("$old\$") })
        }
        val screen = java.io.File("src/main/java/com/billmongan/herdrdroid/ui/AgentScreen.kt")
        if (screen.exists()) {
            val text = screen.readText()
            assertFalse(text.contains("Listen for agent proposal"))
            assertFalse(text.contains("Copy proposal instructions"))
        }
        val review = java.io.File("src/main/java/com/billmongan/herdrdroid/ui/AgentMessageReview.kt")
        if (review.exists()) assertFalse(review.readText().contains("proposalInstructions("))
    }

    private suspend fun frame(to: String = sendTo): AppViewModel.CrossServerCard {
        vm.readPane("p1")
        assertTrue(vm.crossServerCardsFor("p1").isEmpty())
        source.frame = "Sure. Confirm on the phone.\n" + fixtureMessageFrame(code, exact, to)
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
        val server = Server(id = name, name = name, host = "unused.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        val client = Herdr(ssh, server.herdrPath, Backend.TUI, null, this)
        override var connection: Any? = Any()
        var terminal = name
        var frame = "Normal source output"
        var loseAck = false
        val writes = mutableListOf<String>()
        val pane get() = Pane("p1", "tab", "w1", "claude", Status.IDLE, "", "/fixture", scrollOffset = 0, terminalId = terminal)
        override suspend fun bulk(command: String): ExecResult = error("Unexpected bulk command")
        override suspend fun input(command: String, input: ByteArray, expectedConnection: Any): ExecResult {
            assertSame(connection, expectedConnection)
            assertTrue(command.contains("__workcounts__"))
            return ok("""{"ok":true,"busy":{"p1":0}}""")
        }
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            expectedConnection?.let { assertSame(connection, it) }
            return when {
                command.endsWith("'pane' 'get' 'p1'") -> ok("""{"result":{"pane":{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"claude","agent_status":"idle","cwd":"/fixture","terminal_id":"$terminal","scroll":{"offset_from_bottom":0}}}}""")
                "'--source' 'recent'" in command -> ok(frame)
                "'--source' 'visible'" in command -> ok("❯ ")
                "'agent' 'prompt' 'p1'" in command -> { writes += command; if (loseAck) ExecResult(1, "", "lost acknowledgement") else ok("") }
                else -> error("Unexpected fixture command: $command")
            }
        }
        private fun ok(out: String) = ExecResult(0, out, "")
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state(owner: Any, name: String, value: Any?) {
        (field(owner, "$name\$delegate").get(owner) as MutableState<Any?>).value = value
    }
}
