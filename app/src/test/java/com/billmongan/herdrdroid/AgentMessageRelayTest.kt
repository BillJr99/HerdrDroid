package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.fleet.Fleet
import com.billmongan.herdrdroid.fleet.FleetState
import com.billmongan.herdrdroid.herdr.*
import com.billmongan.herdrdroid.ssh.*
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AgentMessageRelayTest {
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val source = Endpoint("Source")
    private val destination = Endpoint("Destination")
    private val exact = "Please review the build.\nKeep  both spaces."

    init {
        // 2.1.4's notice to every agent would write to both fixtures as soon as messaging is on;
        // this test is about the relay alone (CrossServerNoticeDeliveryTest covers the notice).
        vm.changeCrossServerNotice(false)
        vm.onForeground()
        field(vm, "herdr").set(vm, source.client)
        state(vm, "servers", listOf(source.server, destination.server))
        state(vm, "current", source.server)
        state(vm, "panes", listOf(source.pane))
        vm.screen = Screen.Agent("p1")
        val fleet = Fleet(vm.viewModelScope, { Credentials(null, null) }, { null }, {})
        state(fleet, "states", mapOf(destination.server.id to FleetState(destination.server, listOf(destination.pane), null, 0)))
        field(fleet, "clients").set(fleet, mutableMapOf(destination.server.id to (destination.ssh to destination.client)))
        field(vm, "fleet").set(vm, fleet)
    }

    @After fun close() {
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun selectedPaneProposalDoesNotWriteUntilConfirmedAndUsesOnlyTheOtherServer() = runBlocking {
        assertFalse(vm.armAgentMessage("p1"))
        vm.changeAgentMessaging(true)
        val request = propose()
        val selected = vm.agentMessageDestinations(request).single()
        assertEquals(destination.server.id, selected.server.id)
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
        val bound = checkNotNull(vm.bindAgentMessage(request, selected))
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
        assertEquals(RelayResult.SENT, vm.confirmAgentMessage(request, bound))
        assertEquals(RelayResult.REFUSED, vm.confirmAgentMessage(request, bound))
        vm.readPane("p1")
        assertNull(vm.agentMessage("p1"))
        assertTrue(source.writes.isEmpty())
        assertEquals(listOf("'/fixture/herdr' 'agent' 'prompt' 'p1' '$exact'"), destination.writes)
    }

    @Test fun lifecycleNavigationAndReplacementInvalidateOldProposal() = runBlocking {
        vm.changeAgentMessaging(true)
        var request = propose()
        var bound = checkNotNull(vm.bindAgentMessage(request, vm.agentMessageDestinations(request).single()))
        vm.changeAgentMessaging(false)
        vm.changeAgentMessaging(true)
        assertEquals(RelayResult.REFUSED, vm.confirmAgentMessage(request, bound))
        request = propose()
        bound = checkNotNull(vm.bindAgentMessage(request, vm.agentMessageDestinations(request).single()))
        vm.screen = Screen.Tabs
        vm.screen = Screen.Agent("p1")
        assertEquals(RelayResult.REFUSED, vm.confirmAgentMessage(request, bound))
        request = propose()
        bound = checkNotNull(vm.bindAgentMessage(request, vm.agentMessageDestinations(request).single()))
        source.terminal = "replacement"
        assertEquals(RelayResult.REFUSED, vm.confirmAgentMessage(request, bound))
        assertTrue(source.writes.isEmpty() && destination.writes.isEmpty())
    }

    @Test fun replacementDuringDestinationChecksAndLostAcknowledgementNeverReplay() = runBlocking {
        vm.changeAgentMessaging(true)
        var request = propose()
        var bound = checkNotNull(vm.bindAgentMessage(request, vm.agentMessageDestinations(request).single()))
        destination.afterViewport = { destination.terminal = "replacement" }
        assertEquals(RelayResult.REFUSED, vm.confirmAgentMessage(request, bound))
        assertTrue(destination.writes.isEmpty())
        destination.terminal = "Destination"
        destination.afterViewport = null
        request = propose()
        bound = checkNotNull(vm.bindAgentMessage(request, vm.agentMessageDestinations(request).single()))
        destination.loseAck = true
        assertEquals(RelayResult.UNCERTAIN, vm.confirmAgentMessage(request, bound))
        assertEquals(RelayResult.REFUSED, vm.confirmAgentMessage(request, bound))
        assertEquals(1, destination.writes.size)
    }

    @Test fun backgroundDuringValidationCancelsTheSendAndDoesNotRestoreIt() = runBlocking {
        vm.changeAgentMessaging(true)
        val request = propose()
        val bound = checkNotNull(vm.bindAgentMessage(request, vm.agentMessageDestinations(request).single()))
        destination.afterViewport = { vm.onBackground() }
        assertEquals(RelayResult.REFUSED, vm.confirmAgentMessage(request, bound))
        assertFalse(vm.armAgentMessage("p1"))
        // No Fleet socket is opened by this fixture when it comes back.
        state(vm, "watchOthers", false)
        vm.onForeground()
        vm.readPane("p1")
        assertNull(vm.agentMessage("p1"))
        assertTrue(destination.writes.isEmpty())
    }

    private suspend fun propose(): AppViewModel.AgentMessage {
        assertTrue(vm.armAgentMessage("p1"))
        val armed = checkNotNull(vm.agentMessage("p1"))
        assertNull(armed.text)
        // Old terminal output cannot be used under a fresh arm.
        vm.readPane("p1")
        assertNull(vm.agentMessage("p1")?.text)
        source.frame = fixtureMessageFrame(armed.code, exact)
        vm.readPane("p1")
        return checkNotNull(vm.agentMessage("p1")).also { assertEquals(exact, it.text) }
    }

    private class Endpoint(name: String) : Herdr.Commands {
        val server = Server(id = name, name = name, host = "unused.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        val client = Herdr(ssh, server.herdrPath, Backend.TUI, null, this)
        override var connection: Any? = Any()
        var terminal = name
        var frame = "Normal source output"
        var afterViewport: (() -> Unit)? = null
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
                "'--source' 'visible'" in command -> { afterViewport?.invoke(); ok("❯ ") }
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
