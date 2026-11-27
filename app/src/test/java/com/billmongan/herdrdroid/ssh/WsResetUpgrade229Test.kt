package com.billmongan.herdrdroid.ssh

import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthMethod
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class WsResetUpgrade229Test {
    @Test fun resetDuringUpgradeDiscardsTheOldBridgeAndLeavesTheNextOpenUsable() {
        val upgradeRequested = CountDownLatch(1)
        val releaseUpgrade = CountDownLatch(1)
        val oldBridgeClosed = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val firstRequest = upgradeRequested.count == 1L
                if (firstRequest) {
                    upgradeRequested.countDown()
                    if (!releaseUpgrade.await(10, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                }
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("ready".encodeUtf8())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        if (firstRequest) oldBridgeClosed.countDown()
                    }
                })
            }
        }
        server.start()
        val serverId = "reset-during-websocket-upgrade"
        val keyProvider = KeyProvider::class.java
        val fixtureKey = keyProvider.cast(Proxy.newProxyInstance(
            keyProvider.classLoader, arrayOf(keyProvider),
        ) { _, method, _ -> error("The fake SSH client should not use ${method.name}") })
        val ssh = HerdrSsh(
            "127.0.0.1", 1, "fixture", Credentials(KeySource.Keystore("fixture"), null), null, { fixtureKey },
            server.url("/").toString().replaceFirst("http", "ws"), null, serverId,
        )
        val clients = mutableListOf<SSHClient>()
        val bridgeReads = mutableListOf<String>()
        ssh.newClient = { config ->
            object : SSHClient(config) {
                private var connected = false
                private var authenticated = false
                private var bridgeSocket: Socket? = null

                override fun connect(hostname: String?, port: Int) {
                    // Exercise the real successor bridge, with a stand-in for SSH negotiation.
                    val socket = Socket(hostname, port)
                    bridgeSocket = socket
                    socket.soTimeout = 5_000
                    bridgeReads += CharArray(5) { socket.getInputStream().read().toChar() }.concatToString()
                    connected = true
                }

                override fun auth(username: String?, methods: Iterable<AuthMethod>?) { authenticated = true }
                override fun isConnected(): Boolean = connected
                override fun isAuthenticated(): Boolean = authenticated
                override fun disconnect() {
                    connected = false
                    bridgeSocket?.close()
                }
            }.also { clients += it }
        }
        val first = FutureTask { runCatching { runBlocking { ssh.openOnly() } } }
        val worker = Thread(first).apply { isDaemon = true; start() }
        try {
            assertTrue("the first upgrade arrived", upgradeRequested.await(5, TimeUnit.SECONDS))
            ssh.reset()
            assertFalse(ssh.isClosed)
            releaseUpgrade.countDown()

            assertTrue(first.get(10, TimeUnit.SECONDS).exceptionOrNull() is ConnectionChanged)
            assertTrue("the old bridge closed", oldBridgeClosed.await(5, TimeUnit.SECONDS))
            assertEquals(0, SshCounts.openNow(serverId))
            assertEquals(0, SshCounts.openedEver(serverId))
            assertEquals(emptyList<String>(), bridgeReads)

            val successor = runBlocking { ssh.openOnly() }
            assertSame(clients[1], successor)
            assertEquals(listOf("ready"), bridgeReads)
            assertEquals(2, server.requestCount)
            assertSame(successor, runBlocking { ssh.openOnly() })
            assertEquals(1, SshCounts.openNow(serverId))
            assertEquals(1, SshCounts.openedEver(serverId))
        } finally {
            releaseUpgrade.countDown()
            ssh.close()
            worker.join(10_000)
            server.shutdown()
        }
        assertEquals(0, SshCounts.openNow(serverId))
    }
}
