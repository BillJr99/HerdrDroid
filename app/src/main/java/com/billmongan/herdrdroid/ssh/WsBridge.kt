package com.billmongan.herdrdroid.ssh

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Carries one SSH connection over a WebSocket, so a server reachable only on HTTPS can still be
 * opened with the ordinary SSH code. The WebSocket payload is the raw SSH byte stream, sent as
 * binary frames; frame boundaries mean nothing and the bytes are passed on in order as they come.
 *
 * It listens on 127.0.0.1 on a free port and accepts exactly one connection, which sshj makes.
 * Bytes are never dropped: both directions go through bounded queues that block when full, so a
 * fast reader waits for a slow writer instead of losing data (a lost byte would fail SSH's MAC
 * check). Closing either side closes everything.
 *
 * No credential is involved here. The WebSocket has no authentication of its own; SSH's handshake
 * (the pinned host key and this phone's key) is the only gate.
 */
class WsBridge private constructor(private val url: String) {
    private val closed = AtomicBoolean(false)
    private val opened = CountDownLatch(1)
    @Volatile private var openError: Throwable? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var socket: Socket? = null
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1)
    }

    /** Bytes from the WebSocket on their way to sshj. Blocking when full slows the WebSocket reader. */
    private val inbound = ArrayBlockingQueue<ByteString>(INBOUND_FRAMES)

    /** The local port sshj connects to. */
    val port: Int get() = server.localPort

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // put() blocks while the queue is full, which holds back OkHttp's reader (and so the
            // TCP window) until sshj has taken what came before. Nothing is dropped.
            while (!closed.get()) {
                if (inbound.offer(bytes, 200, TimeUnit.MILLISECONDS)) return
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // The bridge speaks binary only; a text frame would corrupt the stream.
            fail(IOException("The WebSocket sent text instead of SSH bytes."))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            close()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = close()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val why = when {
                response != null -> IOException("The WebSocket upgrade was refused (HTTP ${response.code}).", t)
                else -> t
            }
            fail(why)
        }
    }

    private fun fail(t: Throwable) {
        if (openError == null) openError = t
        opened.countDown()
        close()
    }

    private fun start(client: OkHttpClient, timeoutMs: Long) {
        // No headers, no subprotocol, no query: a plain RFC 6455 upgrade over HTTP/1.1.
        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        if (!opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            close()
            throw IOException("The WebSocket didn't open within ${timeoutMs / 1000} s (the server may be waking up).")
        }
        openError?.let { close(); throw if (it is IOException) it else IOException(it.message ?: it.javaClass.simpleName, it) }
        Thread(::pump, "herdrdroid-ws-bridge-$port").apply { isDaemon = true; start() }
    }

    /** Accepts sshj's one connection, then pumps both directions until either side ends. */
    private fun pump() {
        val s = try {
            server.soTimeout = 15_000
            server.accept()
        } catch (e: Exception) {
            close(); return
        } finally {
            runCatching { server.close() } // exactly one connection
        }
        s.tcpNoDelay = true
        socket = s
        if (closed.get()) { runCatching { s.close() }; return }
        // WebSocket -> sshj.
        val down = Thread({
            try {
                val out = s.getOutputStream()
                while (!closed.get()) {
                    val b = inbound.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    out.write(b.toByteArray())
                    if (inbound.isEmpty()) out.flush()
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }, "herdrdroid-ws-down-$port").apply { isDaemon = true; start() }
        // sshj -> WebSocket, as binary frames. OkHttp masks client frames itself.
        try {
            val input = s.getInputStream()
            val buf = ByteArray(32 * 1024)
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) break
                val w = ws ?: break
                // OkHttp queues sends in memory and refuses past 16 MB; wait while it drains.
                while (!closed.get() && w.queueSize() > OUTBOUND_HIGH_WATER) Thread.sleep(5)
                if (!w.send(buf.toByteString(0, n))) break
            }
        } catch (_: Exception) {
        } finally {
            close()
            down.interrupt()
        }
    }

    /** Closes the socket, the listener and the WebSocket together. Safe to call more than once. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { ws?.close(1000, null) }
        runCatching { socket?.close() }
        runCatching { server.close() }
        inbound.clear()
    }

    val isOpen: Boolean get() = !closed.get()

    companion object {
        private const val INBOUND_FRAMES = 256
        private const val OUTBOUND_HIGH_WATER = 1L shl 20

        /** One client for every bridge: HTTP/1.1 only (an HTTP/2 edge never upgrades), pings every 20 s. */
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }

        /** Opens the WebSocket at [url] (wss:// or ws://) and returns a bridge ready for sshj. */
        fun open(url: String, timeoutMs: Long = 20_000, http: OkHttpClient = client): WsBridge {
            val u = url.trim()
            require(u.startsWith("wss://", ignoreCase = true) || u.startsWith("ws://", ignoreCase = true)) {
                "The WebSocket address must start with wss://"
            }
            return WsBridge(u).also { it.start(http, timeoutMs) }
        }
    }
}
