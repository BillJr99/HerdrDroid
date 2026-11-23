package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.diag.DiagLog
import com.billmongan.herdrdroid.diag.NetworkWatch
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ssh.LinkDown
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** 2.2.6: the view model's small-packets fallback: once per sign-in, only for the open connection, never mid-send. */
@RunWith(AndroidJUnit4::class)
class SmallPacketFallbackVm226Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val a = Server(id = "fixture-mss-a", name = "FixtureA", host = "a.invalid", port = 22, user = "u", herdrPath = "herdr")
    private val b = Server(id = "fixture-mss-b", name = "FixtureB", host = "b.invalid", port = 22, user = "u", herdrPath = "herdr")
    private var now = 10_000L

    @After fun close() {
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A herdr client whose pane list read waits on [gate]; what the gate gives is the read's result. */
    private class Endpoint(server: Server) : Herdr.Commands {
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null }, null, null, server.id)
        val client = Herdr(ssh, server.herdrPath, Backend.TUI, null, this)
        val gate = CompletableDeferred<ExecResult>()
        override val connection: Any? = Any()
        override suspend fun bulk(command: String): ExecResult = gate.await()
        /** Writes seen; with [dropAfterStart] set, each one fails: after its exec (true) or before it (false). */
        val sends = mutableListOf<String>()
        var dropAfterStart: Boolean? = null
        /** When set, the guard read before a send waits on it. */
        var readGate: CompletableDeferred<Unit>? = null
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            val ctx = kotlin.coroutines.coroutineContext
            if (ctx[com.billmongan.herdrdroid.ssh.WriteCommand] == null) {
                // A read (the send's guard read of the screen): it never marks the action as sent.
                readGate?.await()
                return if ("'--source'" in command) ExecResult(0, "❯ ", "") else ExecResult(1, "", "fixture")
            }
            sends += command
            val drop = dropAfterStart ?: return ExecResult(0, "", "")
            // As HerdrSsh does just before a write's exec: from here it may have reached the computer.
            if (drop) ctx[com.billmongan.herdrdroid.ssh.SendTrace]?.started = true
            throw LinkDown(java.io.IOException("reset mid-command"))
        }
    }

    private fun open(e: Endpoint, s: Server) {
        state("current", s)
        field("ssh").set(vm, e.ssh)
        field("herdr").set(vm, e.client)
        state("link", Link.CONNECTED)
        vm.smallPacketClock = { now }
        vm.smallPacketFallback.arm(s.id, !e.ssh.overWebSocket && e.ssh.activeMss == null, now, NetworkWatch.changes)
    }

    private fun signedIn(ssh: HerdrSsh, s: Server = a) {
        vm.saveServer(s)
        state("current", s)
        field("ssh").set(vm, ssh)
        vm.smallPacketClock = { now }
        vm.smallPacketFallback.arm(s.id, !ssh.overWebSocket && ssh.activeMss == null, now, NetworkWatch.changes)
    }

    private fun idle(ms: Long = 0) {
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); Thread.sleep(5) }
    }

    private val timedOut = ExecResult(-2, "", "the command did not finish within 30 s")

    @Test fun aDropSoonAfterSignInSwitchesOnceThenKeepSaves() {
        val ssh = HerdrSsh(a.host, a.port, a.user, Credentials(null, null), null, { null })
        signedIn(ssh)
        now += 5_000
        assertTrue(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("stall")), ssh))
        assertEquals(1200, ssh.activeMss)
        assertEquals(a.id, vm.smallPacketOffer)
        now += 1_000
        assertFalse("never a second time", vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("stall")), ssh))
        vm.keepSmallPacketsOn()
        assertNull(vm.smallPacketOffer)
        assertTrue(vm.current!!.smallPackets)
        assertEquals(1200, vm.servers.single { it.id == a.id }.smallPacketsMss)
    }

    @Test fun notAfterTheWindowNorForOtherErrorsNorAnotherConnection() {
        val ssh = HerdrSsh(a.host, a.port, a.user, Credentials(null, null), null, { null })
        val other = HerdrSsh(a.host, a.port, a.user, Credentials(null, null), null, { null })
        signedIn(ssh)
        assertFalse(vm.maybeSmallPacketFallback(IllegalStateException("parse"), ssh))
        assertFalse("not the open connection", vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("x")), other))
        assertFalse(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("x")), null))
        now += 10 * 60_000
        assertFalse(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("late")), ssh))
        assertNull(ssh.activeMss)
        assertNull(other.activeMss)
        assertNull(vm.smallPacketOffer)
    }

    @Test fun neverOverAWebSocket() {
        val ws = a.copy(wsUrl = "wss://bridge.invalid/ssh")
        val ssh = HerdrSsh("unused", 0, ws.user, Credentials(null, null), null, { null }, ws.wsUrl)
        signedIn(ssh, ws)
        assertFalse(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("drop")), ssh))
        assertNull(vm.smallPacketOffer)
    }

    @Test fun aNetworkChangeOrTheBackgroundSkipsItAndSaysSo() {
        DiagLog.clear()
        val ssh = HerdrSsh(a.host, a.port, a.user, Credentials(null, null), null, { null })
        // The first network seen is the baseline, not a change.
        NetworkWatch.observe(Any(), "fixture-base-${System.nanoTime()}")
        signedIn(ssh)
        NetworkWatch.observe(Any(), "fixture-net-${System.nanoTime()}")
        assertFalse(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("handoff")), ssh))
        assertTrue(DiagLog.text().contains("fallback=skipped reason=network-change trigger=link-drop"))
        // 2.2.7: a network change defers the fallback to the new network instead of dropping it:
        // the next drop there falls back.
        assertTrue("deferred to the new network", vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("again")), ssh))
        assertEquals(1200, ssh.activeMss)

        val other = HerdrSsh(a.host, a.port, a.user, Credentials(null, null), null, { null })
        signedIn(other)
        state("foreground", false)
        assertFalse(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("screen off")), other))
        assertTrue(DiagLog.text().contains("fallback=skipped reason=background trigger=link-drop"))
        assertNull(other.activeMss)
    }

    @Test fun theOfferEndsWithItsSession() {
        val ssh = HerdrSsh(a.host, a.port, a.user, Credentials(null, null), null, { null })
        signedIn(ssh)
        assertTrue(vm.maybeSmallPacketFallback(LinkDown(java.io.IOException("stall")), ssh))
        assertEquals(a.id, vm.smallPacketOffer)
        vm.disconnect()
        assertNull(vm.smallPacketOffer)
    }

    /** Pre-review H1: a read on server A that fails after switching to B leaves B alone. */
    @Test fun aReadInFlightAcrossAServerSwitchFiresNoFallback() {
        for (failure in listOf<(Endpoint) -> Unit>(
            { it.gate.completeExceptionally(LinkDown(java.io.IOException("closed by the switch"))) },
            { it.gate.complete(timedOut) },
        )) {
            val epA = Endpoint(a)
            val epB = Endpoint(b)
            open(epA, a)
            vm.viewModelScope.launch { vm.refreshPanes() }
            idle()
            // The switch: B is open and armed while A's read is still waiting.
            open(epB, b)
            failure(epA)
            idle(200)
            assertNull(vm.smallPacketOffer)
            assertNull(epA.ssh.activeMss)
            assertNull(epB.ssh.activeMss)
            assertEquals(Link.CONNECTED, vm.link)
        }
    }

    /** Pre-review M2: a timed-out read fires the fallback, but the reconnect waits for a send in flight. */
    @Test fun theFallbackReconnectWaitsForASendInFlight() {
        DiagLog.clear()
        val ep = Endpoint(a)
        open(ep, a)
        ep.ssh.beginSend()
        vm.viewModelScope.launch { vm.refreshPanes() }
        idle()
        ep.gate.complete(timedOut)
        idle(500)
        assertEquals(1200, ep.ssh.activeMss)
        assertEquals(a.id, vm.smallPacketOffer)
        assertEquals("no reset while the send runs", Link.CONNECTED, vm.link)
        ep.ssh.endSend()
        idle(300)
        assertEquals(Link.RECONNECTING, vm.link)
        assertTrue(DiagLog.text().contains("fallback-reconnect waited_for_send=done"))
        // Pre-review M1: the reconnect lines keep the fallback as their reason.
        val deadline = System.currentTimeMillis() + 10_000
        while (!DiagLog.text().contains("reconnect attempt=1") && System.currentTimeMillis() < deadline) idle(100)
        assertTrue(DiagLog.text().lines().any { it.contains("reconnect attempt=1") && it.contains("reason=small-packets-fallback") })
    }

    /**
     * Grok's review: a reset while a key press is running on the send lane comes back as LinkDown
     * for a command that already reached the computer. It is reported, never queued and re-sent.
     */
    @Test fun aSendCutOffAfterItStartedIsNeverSentAgain() {
        val ep = Endpoint(a)
        open(ep, a)
        ep.dropAfterStart = true
        vm.sendKeys("w1:p1", "Enter")
        idle(200)
        assertEquals(1, ep.sends.size)
        assertEquals("not queued for the reconnect", 0, vm.queuedCount)
        assertTrue(vm.error.orEmpty().contains("while it was being sent"))
        // Back on the link: nothing waits to go again.
        state("link", Link.CONNECTED)
        idle(200)
        assertEquals(1, ep.sends.size)
    }

    @Test fun aSendThatNeverStartedStillWaitsForTheLink() {
        val ep = Endpoint(a)
        open(ep, a)
        ep.dropAfterStart = false
        vm.sendKeys("w1:p1", "Enter")
        idle(200)
        assertEquals(1, ep.sends.size)
        assertEquals("queued to go once the link is back", 1, vm.queuedCount)
    }

    private val claude = com.billmongan.herdrdroid.herdr.Pane("w1:p1", "w1:t1", "w1", "claude", com.billmongan.herdrdroid.herdr.Status.IDLE, "", "/fixture", scrollOffset = 0, terminalId = "t1")

    private fun sendTo(ep: Endpoint) {
        open(ep, a)
        state("panes", listOf(claude))
        vm.sendMessage(claude, "HD226_MESSAGE")
    }

    /** Grok's re-review: the guard read succeeds, then the link goes before the write's exec: queued once, not undelivered. */
    @Test fun aGuardReadThenADropBeforeTheWriteIsQueued() {
        val ep = Endpoint(a)
        ep.dropAfterStart = false
        sendTo(ep)
        idle(200)
        assertEquals(1, ep.sends.size)
        assertEquals(1, vm.queuedCount)
        assertFalse(vm.pendingSends.single().undelivered)
    }

    @Test fun aGuardReadThenADropAfterTheWriteIsUndeliveredNotQueued() {
        val ep = Endpoint(a)
        ep.dropAfterStart = true
        sendTo(ep)
        idle(200)
        assertEquals(1, ep.sends.size)
        assertEquals(0, vm.queuedCount)
        assertTrue(vm.pendingSends.single().undelivered)
    }

    /** The fallback's reconnect waits through the gap between the guard read and the write. */
    @Test fun theFallbackReconnectWaitsThroughTheGuardReadToWriteGap() {
        val ep = Endpoint(a)
        ep.readGate = CompletableDeferred()
        sendTo(ep)
        idle(100)
        assertTrue("the action holds the send lane from its guard read", ep.ssh.sendsRunning > 0)
        vm.reconnectForFallback(ep.client)
        idle(500)
        assertEquals(Link.CONNECTED, vm.link)
        ep.readGate!!.complete(Unit)
        idle(500)
        assertEquals(listOf(1), listOf(ep.sends.size))
        assertEquals(0, ep.ssh.sendsRunning)
        assertEquals(Link.RECONNECTING, vm.link)
    }

    private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value
    }
}
