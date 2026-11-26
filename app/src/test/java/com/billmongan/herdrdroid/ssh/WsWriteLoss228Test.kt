package com.billmongan.herdrdroid.ssh

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthMethod
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WsWriteLoss228Test {
    @Test fun anAbruptWebSocketLossAfterExecStartsNeverReplaysTheWrite() = runBlocking {
        val opens = AtomicInteger()
        val execs = AtomicInteger()
        val sawPeerClose = AtomicBoolean()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            server.enqueue(MockResponse().setResponseCode(503))
            server.start()
            val relay = LoopbackRelay(server.port)
            val url = "ws://127.0.0.1:${relay.port}/"
            val fixtureKey = KeyProvider::class.java.cast(Proxy.newProxyInstance(
                KeyProvider::class.java.classLoader, arrayOf(KeyProvider::class.java)
            ) { _, method, _ -> error("The fake SSH client should not use ${method.name}") })
            val ssh = HerdrSsh("127.0.0.1", 1, "fixture", Credentials(KeySource.Keystore("fixture"), null),
                null, { fixtureKey }, url)
            ssh.newClient = { cfg ->
                opens.incrementAndGet()
                object : SSHClient(cfg) {
                    private var wire: Socket? = null
                    override fun connect(hostname: String?, port: Int) {
                        wire = Socket(hostname, port).apply { soTimeout = 3_000 }
                    }
                    override fun auth(username: String?, methods: Iterable<AuthMethod>?) {}
                    override fun isConnected() = wire?.isClosed == false
                    override fun isAuthenticated() = isConnected
                    override fun disconnect() { wire?.close() }
                    override fun startSession(): Session = Session::class.java.cast(Proxy.newProxyInstance(
                        Session::class.java.classLoader, arrayOf(Session::class.java)
                    ) { _, method, _ ->
                        when (method.name) {
                            "exec" -> {
                                execs.incrementAndGet()
                                relay.drop()
                                sawPeerClose.set(checkNotNull(wire).getInputStream().read() == -1)
                                throw IOException("fixture lost the reply after accepting exec")
                            }
                            "close" -> null
                            else -> error("Unexpected session call ${method.name}")
                        }
                    })
                }
            }
            try {
                val trace = SendTrace()
                val failed = runCatching {
                    withContext(trace + WriteCommand.MARK) { ssh.execNow("fixture", timeoutSeconds = 5) }
                }
                assertTrue(failed.exceptionOrNull().toString(), failed.exceptionOrNull() is LinkDown)
                assertTrue(trace.started)
                assertTrue("The real bridge closed the loopback wire promptly", sawPeerClose.get())
                assertEquals(1, execs.get())
                assertEquals(1, opens.get())
                assertEquals(1, server.requestCount)
            } finally {
                ssh.close()
                relay.close()
            }
        }
    }

    private class LoopbackRelay(private val targetPort: Int) {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = listener.localPort
        @Volatile private var upstream: Socket? = null
        @Volatile private var downstream: Socket? = null

        init {
            kotlin.concurrent.thread(isDaemon = true, name = "fixture-ws-relay") {
                try {
                    val client = listener.accept().also { downstream = it }
                    val peer = Socket("127.0.0.1", targetPort).also { upstream = it }
                    kotlin.concurrent.thread(isDaemon = true, name = "fixture-ws-relay-up") { pump(client, peer) }
                    pump(peer, client)
                } catch (_: IOException) {
                    drop()
                }
            }
        }

        private fun pump(src: Socket, dest: Socket) {
            try {
                src.getInputStream().copyTo(dest.getOutputStream())
            } catch (_: IOException) {
                // In this fixture the transport is deliberately cut before the reply.
            } finally {
                drop()
            }
        }

        fun drop() {
            runCatching { downstream?.close() }
            runCatching { upstream?.close() }
        }

        fun close() {
            runCatching { listener.close() }
            drop()
        }
    }
}
