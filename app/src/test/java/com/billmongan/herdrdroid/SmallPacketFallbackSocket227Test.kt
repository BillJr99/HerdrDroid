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
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * 2.2.7 (Codex's review of c2ae64c): the small-packets fallback's deferred reconnect resets only
 * the socket that stalled. When that socket was replaced in the same client, or the phone's
 * network changed, while it waited for a send, it does nothing.
 */
@RunWith(AndroidJUnit4::class)
class SmallPacketFallbackSocket227Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    // Loopback port 1 refuses at once, so a reconnect a test starts fails in milliseconds.
    private val a = Server(id = "fixture-sock-a", name = "FixtureA", host = "127.0.0.1", port = 1, user = "u", herdrPath = "herdr")
    private val netBefore = NetworkWatch.changes
    private val changesField = NetworkWatch::class.java.getDeclaredField("changes").apply { isAccessible = true }
    private fun setChanges(n: Int) = changesField.setInt(NetworkWatch, n)

    @After fun close() {
        setChanges(netBefore)
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A herdr client whose send's guard read waits on [readGate]; the pane list read never answers. */
    private class Endpoint(server: Server) : Herdr.Commands {
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null }, null, null, server.id)
        val client = Herdr(ssh, server.herdrPath, Backend.TUI, null, this)
        override val connection: Any? = Any()
        val readGate = CompletableDeferred<Unit>()
        override suspend fun bulk(command: String): ExecResult = CompletableDeferred<ExecResult>().await()
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            if (kotlin.coroutines.coroutineContext[com.billmongan.herdrdroid.ssh.WriteCommand] == null) {
                readGate.await()
                return if ("'--source'" in command) ExecResult(0, "❯ ", "") else ExecResult(1, "", "fixture")
            }
            return ExecResult(0, "", "")
        }
    }

    private val claude = Pane("w1:p1", "w1:t1", "w1", "claude", Status.IDLE, "", "/fixture", scrollOffset = 0, terminalId = "t1")

    private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value
    }

    private fun idle(ms: Long = 0) {
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); Thread.sleep(5) }
    }

    /** A send holds the lane, the fallback's reconnect starts waiting, then [meanwhile] runs and the send ends. */
    private fun fallbackWaitsThen(meanwhile: (Endpoint) -> Unit): Endpoint {
        val ep = Endpoint(a)
        state("current", a)
        field("ssh").set(vm, ep.ssh)
        field("herdr").set(vm, ep.client)
        state("link", Link.CONNECTED)
        state("panes", listOf(claude))
        vm.sendMessage(claude, "HD227_MESSAGE")
        idle(100)
        assertTrue("the send holds the lane", ep.ssh.sendsRunning > 0)
        vm.reconnectForFallback(ep.client)
        idle(300)
        meanwhile(ep)
        ep.readGate.complete(Unit)
        idle(500)
        assertEquals(0, ep.ssh.sendsRunning)
        return ep
    }

    @Test fun aSocketReplacedWhileItWaitedIsNotReset() {
        DiagLog.clear()
        val newer = net.schmizz.sshj.SSHClient()
        val ep = fallbackWaitsThen { e ->
            HerdrSsh::class.java.getDeclaredField("client").apply { isAccessible = true }.set(e.ssh, newer)
        }
        assertSame("the newer socket is still there", newer, ep.ssh.connectionIdentity)
        assertEquals(Link.CONNECTED, vm.link)
        assertTrue(DiagLog.text(), DiagLog.text().contains("fallback-reconnect skipped reason=connection-replaced"))
    }

    @Test fun aNetworkChangeWhileItWaitedSkipsTheReset() {
        DiagLog.clear()
        fallbackWaitsThen { setChanges(netBefore + 1) }
        assertEquals(Link.CONNECTED, vm.link)
        assertTrue(DiagLog.text(), DiagLog.text().contains("fallback-reconnect skipped reason=network-change"))
    }
}
