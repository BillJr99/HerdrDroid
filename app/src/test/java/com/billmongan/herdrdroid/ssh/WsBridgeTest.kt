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
        val url = server.url("/").toString().replaceFirst("http", "ws")
        val bridge = WsBridge.open(url)
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
        bridge.close()
        assertTrue(!bridge.isOpen)
        server.shutdown()
    }

    @Test fun aRefusedUpgradeIsAnErrorNotAHang() {
        val server = MockWebServer()
        // What an HTTP/2 edge does: a plain 200 instead of 101 Switching Protocols.
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()
        val started = System.currentTimeMillis()
        try {
            WsBridge.open(server.url("/").toString().replaceFirst("http", "ws"))
            fail("expected an error")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("HTTP 200"))
        }
        assertTrue(System.currentTimeMillis() - started < 10_000)
        server.shutdown()
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
        val bridge = WsBridge.open(server.url("/").toString().replaceFirst("http", "ws"))
        Socket("127.0.0.1", bridge.port).use { s ->
            s.soTimeout = 10_000
            val all = s.getInputStream().readBytes() // returns at EOF; a hang would time out
            assertArrayEquals(byteArrayOf(1, 2, 3), all)
        }
        server.shutdown()
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

        val list = ssh.exec("$herdr agent list")
        assertEquals(0, list.exit)
        for (a in listOf("claude", "codex", "grok", "agy")) assertTrue(list.out, list.out.contains("\"$a\""))
        val read = ssh.exec("$herdr pane read p1 --lines 5")
        assertTrue(read.out, read.out.contains("pane output"))

        // A minute of idling: the connection must still be there (no reconnect needed).
        Thread.sleep(65_000)
        val again = ssh.exec("echo still-here")
        assertEquals("still-here", again.out.trim())
        ssh.close()

        // A key the server doesn't know: a clear refusal, quickly, and no password offered.
        val bad = HerdrSsh("unused", 0, user, creds(System.getenv("HD_WS_BADKEY")), fp, { null }, url)
        val t0 = System.currentTimeMillis()
        try {
            bad.exec("true")
            fail("expected the key to be refused")
        } catch (e: AuthRejected) {
            assertTrue(System.currentTimeMillis() - t0 < 20_000)
        }
        bad.close()

        // No key at all: refused before anything is sent.
        val none = HerdrSsh("unused", 0, user, Credentials(null, "pw"), fp, { null }, url)
        try {
            none.exec("true")
            fail("expected a refusal")
        } catch (e: AuthRejected) {
            assertTrue(e.message!!.contains("key only"))
        }

        // The wrong pinned host key stops the connection.
        val wrongPin = HerdrSsh("unused", 0, user, creds(System.getenv("HD_WS_KEY")), "SHA256:" + "A".repeat(43), { null }, url)
        try {
            wrongPin.exec("true")
            fail("expected a host key mismatch")
        } catch (e: HostKeyChanged) {
            assertEquals(fp, e.actual)
        }
    }
}
