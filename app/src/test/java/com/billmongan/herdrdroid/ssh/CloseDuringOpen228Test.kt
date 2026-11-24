package com.billmongan.herdrdroid.ssh

import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.method.AuthMethod
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 2.2.8 (Codex's review 1): the app's clear closes a connection while its TCP connect or sign-in
 * is still running. The client being opened is registered before its connect, so the close
 * disconnects it; when the connect or sign-in finishes anyway, the late connection is dropped,
 * never kept or counted, and the count of open connections goes back to zero.
 */
class CloseDuringOpen228Test {
    /** A client with no network: its connect and sign-in run the given steps and succeed. */
    private class Stand(cfg: DefaultConfig, val connectStep: () -> Unit, val authStep: () -> Unit) : SSHClient(cfg) {
        @Volatile var disconnects = 0
        @Volatile var signedIn = false
        override fun connect(hostname: String?, port: Int) { connectStep() }
        override fun auth(username: String?, methods: Iterable<AuthMethod>?) { authStep(); signedIn = true }
        override fun isConnected(): Boolean = disconnects == 0
        override fun isAuthenticated(): Boolean = signedIn && disconnects == 0
        override fun disconnect() { disconnects++; runCatching { super.disconnect() } }
    }

    private fun ssh(server: String) = HerdrSsh("127.0.0.1", 1, "u", Credentials(null, "pw"), null, { null }, null, null, server)

    private fun lost(ssh: HerdrSsh) = runCatching { runBlocking { ssh.exec("true", 5) } }.exceptionOrNull()

    @Test fun aCloseDuringTheSignInDropsTheLateConnection() {
        SshCounts.clear()
        val ssh = ssh("close-auth")
        val made = mutableListOf<Stand>()
        ssh.newClient = { cfg -> Stand(cfg, {}, { ssh.close() }).also { made += it } }
        assertTrue("the command fails as a lost link", lost(ssh) is LinkDown)
        assertEquals("one client, and no second open after the close", 1, made.size)
        assertTrue("the late socket was disconnected", made.single().disconnects >= 1)
        assertEquals("nothing kept open", 0, SshCounts.openNow("close-auth"))
        assertEquals("never counted", 0, SshCounts.openedEver("close-auth"))
        assertTrue(ssh.isClosed)
    }

    @Test fun aCloseDuringTheConnectDropsItToo() {
        SshCounts.clear()
        val ssh = ssh("close-connect")
        val made = mutableListOf<Stand>()
        ssh.newClient = { cfg -> Stand(cfg, { ssh.close() }, { fail("no sign-in after the close") }).also { made += it } }
        assertTrue(lost(ssh) is LinkDown)
        assertEquals(1, made.size)
        assertTrue(made.single().disconnects >= 1)
        assertEquals(0, SshCounts.openNow("close-connect"))
        assertEquals(0, SshCounts.openedEver("close-connect"))
    }

    /** The race itself: the close comes from another thread while the sign-in waits on the network. */
    @Test fun aCloseFromAnotherThreadReachesTheOpenInFlight() {
        SshCounts.clear()
        val ssh = ssh("close-race")
        val inAuth = CountDownLatch(1)
        val release = CountDownLatch(1)
        val made = mutableListOf<Stand>()
        ssh.newClient = { cfg -> Stand(cfg, {}, { inAuth.countDown(); release.await(5, TimeUnit.SECONDS) }).also { made += it } }
        var failure: Throwable? = null
        val t = Thread { failure = lost(ssh) }.apply { start() }
        assertTrue(inAuth.await(5, TimeUnit.SECONDS))
        ssh.close()
        assertTrue("the close disconnected the socket still signing in", made.single().disconnects >= 1)
        release.countDown()
        t.join(5_000)
        assertTrue(failure is LinkDown)
        assertEquals(1, made.size)
        assertEquals(0, SshCounts.openNow("close-race"))
        assertEquals(0, SshCounts.openedEver("close-race"))
    }

    /** A connection opened before the close is counted, and the close takes the count back to zero. */
    @Test fun aCloseAfterTheOpenClosesWhatItHolds() {
        SshCounts.clear()
        val ssh = ssh("close-after")
        val made = mutableListOf<Stand>()
        ssh.newClient = { cfg -> Stand(cfg, {}, {}).also { made += it } }
        runBlocking { ssh.openOnly() }
        assertEquals(1, SshCounts.openNow("close-after"))
        ssh.close()
        assertEquals(0, SshCounts.openNow("close-after"))
        assertTrue(made.single().disconnects >= 1)
        val before = made.size
        assertTrue("closed for good: no new connection", runCatching { runBlocking { ssh.openOnly() } }.exceptionOrNull() is LinkDown)
        assertEquals(before, made.size)
    }
}
