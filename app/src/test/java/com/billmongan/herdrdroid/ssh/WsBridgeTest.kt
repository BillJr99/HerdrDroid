package com.billmongan.herdrdroid.ssh

import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.Random

class WsBridgeTest {
    /** A WebSocket server that sends back every binary frame it gets, cut into different-sized pieces. */
    private fun echoServer(): MockWebServer {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Frame boundaries must not matter: re-cut the bytes into 1000-byte pieces.
                var i = 0
                while (i < bytes.size) {
                    val n = minOf(1000, bytes.size - i)
                    webSocket.send(bytes.substring(i, i + n))
                    i += n
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        server.start()
        return server
    }

    @Test fun bytesArriveIntactAndInOrderBothWays() {
        val server = echoServer()
        try {
            val url = server.url("/").toString().replaceFirst("http", "ws")
            val bridge = WsBridge.open(url)
            try {
                val data = ByteArray(6 * 1024 * 1024).also { Random(7).nextBytes(it) }
                Socket("127.0.0.1", bridge.port).use { s ->
                    // Write everything on one thread while reading on this one, as sshj does.
                    val writer = Thread { s.getOutputStream().apply { write(data); flush() } }.apply { start() }
                    val got = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    val input = s.getInputStream()
                    while (got.size() < data.size) {
                        val n = input.read(buf)
                        if (n < 0) break
                        got.write(buf, 0, n)
                    }
                    writer.join()
                    assertEquals(data.size, got.size())
                    assertArrayEquals(data, got.toByteArray())
                }
            } finally {
                bridge.close()
            }
            assertTrue(!bridge.isOpen)
        } finally {
            server.shutdown()
        }
    }

    @Test fun aRefusedUpgradeIsAnErrorNotAHang() {
        val server = MockWebServer()
        // What an HTTP/2 edge does: a plain 200 instead of 101 Switching Protocols.
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()
        try {
            val started = System.currentTimeMillis()
            try {
                WsBridge.open(server.url("/").toString().replaceFirst("http", "ws")).close()
                fail("expected an error")
            } catch (e: IOException) {
                assertTrue(e.message!!, e.message!!.contains("HTTP 200"))
            }
            assertTrue(System.currentTimeMillis() - started < 10_000)
        } finally {
            server.shutdown()
        }
    }

    @Test fun onlyWebSocketAddressesAreAccepted() {
        try {
            WsBridge.open("https://example.invalid/")
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("wss://"))
        }
    }

    @Test fun aServerThatClosesEndsTheLocalSocket() {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(byteArrayOf(1, 2, 3).toByteString())
                webSocket.close(1000, "bye")
            }
        }))
        server.start()
        try {
            val bridge = WsBridge.open(server.url("/").toString().replaceFirst("http", "ws"))
            try {
                Socket("127.0.0.1", bridge.port).use { s ->
                    s.soTimeout = 10_000
                    val all = s.getInputStream().readBytes() // returns at EOF; a hang would time out
                    assertArrayEquals(byteArrayOf(1, 2, 3), all)
                }
            } finally {
                bridge.close()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun theServerSpeaksFirstAndALateClientStillGetsEveryByteInOrder() {
        com.billmongan.herdrdroid.diag.DiagLog.clear()
        val banner = "SSH-2.0-OpenSSH_9.2p1 Debian-2+deb12u10\r\n".toByteArray()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Like sshd behind a bridge: the identification string at once, in two frames.
                webSocket.send(banner.toByteString(0, 10))
                webSocket.send(banner.toByteString(10, banner.size - 10))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                webSocket.send(("got " + bytes.utf8()).toByteArray().toByteString())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        server.start()
        try {
            val bridge = WsBridge.open(server.url("/").toString().replaceFirst("http", "ws"))
            try {
                // The bridge listens on IPv4 loopback, which is where sshj is pointed.
                assertEquals("127.0.0.1", bridge.host)
                Thread.sleep(500) // the server's bytes arrive before anyone connects
                Socket(bridge.host, bridge.port).use { s ->
                    s.soTimeout = 10_000
                    val input = s.getInputStream()
                    val got = ByteArray(banner.size)
                    var n = 0
                    while (n < got.size) { val k = input.read(got, n, got.size - n); if (k < 0) break; n += k }
                    assertArrayEquals(banner, got)
                    s.getOutputStream().apply { write("SSH-2.0-test\r\n".toByteArray()); flush() }
                    val reply = ByteArray(18)
                    n = 0
                    while (n < reply.size) { val k = input.read(reply, n, reply.size - n); if (k < 0) break; n += k }
                    assertEquals("got SSH-2.0-test\r\n", String(reply))
                }
            } finally {
                bridge.close()
            }
            val log = com.billmongan.herdrdroid.diag.DiagLog.text()
            assertTrue(log, log.contains("onOpen: HTTP 101"))
            assertTrue(log, log.contains("onMessage(binary) #1: 10 bytes, starts \"SSH-2.0-Op\""))
            assertTrue(log, log.contains("ws.send(binary) #1: 14 bytes, starts \"SSH-2.0-test\\r\\n\""))
            // The address never goes in the log.
            assertTrue(log, !log.contains(server.hostName + ":" + server.port) && log.contains("<address hidden>"))
        } finally {
            server.shutdown()
        }
    }

    /**
     * End to end against a real sshd behind a WebSocket, only when one is given:
     * HD_WS_URL, HD_WS_KEY (a private key file), HD_WS_BADKEY (one the server doesn't know),
     * HD_WS_FP (the host key's SHA256 fingerprint), HD_WS_USER and HD_WS_HERDR.
     */
    @Test fun sshOverAWebSocketEndToEnd() = runBlocking {
        val url = System.getenv("HD_WS_URL")
        assumeTrue("no test server", !url.isNullOrBlank())
        val fp = System.getenv("HD_WS_FP")
        val user = System.getenv("HD_WS_USER") ?: "node"
        val herdr = System.getenv("HD_WS_HERDR") ?: "/home/node/.local/bin/herdr"
        fun creds(path: String) = Credentials(KeySource.Imported(File(path).readText(), null), "not-a-password")
        val ssh = HerdrSsh("unused", 0, user, creds(System.getenv("HD_WS_KEY")), fp, { null }, url)
        try {
            val list = ssh.exec("$herdr agent list")
            assertEquals(0, list.exit)
            for (a in listOf("claude", "codex", "grok", "agy")) assertTrue(list.out, list.out.contains("\"$a\""))
            val read = ssh.exec("$herdr pane read p1 --lines 5")
            assertTrue(read.out, read.out.contains("pane output"))

            // A minute of idling: the connection must still be there (no reconnect needed).
            Thread.sleep(65_000)
            val again = ssh.exec("echo still-here")
            assertEquals("still-here", again.out.trim())
        } finally {
            ssh.close()
        }

        // A key the server doesn't know: a clear refusal, quickly, and no password offered.
        val bad = HerdrSsh("unused", 0, user, creds(System.getenv("HD_WS_BADKEY")), fp, { null }, url)
        val t0 = System.currentTimeMillis()
        try {
            bad.exec("true")
            fail("expected the key to be refused")
        } catch (e: AuthRejected) {
            assertTrue(System.currentTimeMillis() - t0 < 20_000)
        } finally {
            bad.close()
        }

        // No key at all: refused before anything is sent.
        val none = HerdrSsh("unused", 0, user, Credentials(null, "pw"), fp, { null }, url)
        try {
            none.exec("true")
            fail("expected a refusal")
        } catch (e: AuthRejected) {
            assertTrue(e.message!!.contains("key only"))
        } finally {
            none.close()
        }

        // The wrong pinned host key stops the connection.
        val wrongPin = HerdrSsh("unused", 0, user, creds(System.getenv("HD_WS_KEY")), "SHA256:" + "A".repeat(43), { null }, url)
        try {
            wrongPin.exec("true")
            fail("expected a host key mismatch")
        } catch (e: HostKeyChanged) {
            assertEquals(fp, e.actual)
        } finally {
            wrongPin.close()
        }
    }
}
