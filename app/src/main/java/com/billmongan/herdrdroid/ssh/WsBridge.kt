package com.billmongan.herdrdroid.ssh

import com.billmongan.herdrdroid.diag.DiagLog
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Carries one SSH connection over a WebSocket, so a server reachable only on HTTPS can still be
 * opened with the ordinary SSH code. The WebSocket payload is the raw SSH byte stream, sent as
 * binary frames only; frame boundaries mean nothing and the bytes are passed on in arrival order.
 *
 * Order matters, because SSH servers speak first:
 * 1. bind a ServerSocket on 127.0.0.1 (IPv4, explicitly: Android's getLoopbackAddress() is ::1,
 *    and sshj is pointed at 127.0.0.1) and start accepting;
 * 2. open the WebSocket;
 * 3. queue anything that arrives before the local connection exists, and write it to that
 *    connection in arrival order once it does; nothing is ever dropped or cleared;
 * 4. sshj connects to 127.0.0.1:[port].
 *
 * Both directions use bounded queues that block when full, so a fast reader waits for a slow
 * writer instead of losing bytes (a lost byte would fail SSH's MAC check). When the server closes
 * the WebSocket, what it already sent is still delivered before the local connection is closed.
 * Every step is written to [DiagLog], without the address and without payloads beyond the first
 * bytes each way (the SSH identification strings).
 *
 * No credential is involved here. The WebSocket has no authentication of its own; SSH's handshake
 * (the pinned host key and this phone's key) is the only gate.
 */
class WsBridge private constructor(private val url: String) {
    private val id = SEQ.incrementAndGet()
    private val tag = "ws#$id"
    private val closed = AtomicBoolean(false)
    private val wsDone = AtomicBoolean(false)
    private val opened = CountDownLatch(1)
    private val accepted = CountDownLatch(1)
    @Volatile private var openError: Throwable? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var socket: Socket? = null
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(LOOPBACK, 0), 1)
    }
    private val started = System.currentTimeMillis()
    private val bytesIn = AtomicLong()
    private val bytesOut = AtomicLong()
    private val framesIn = AtomicLong()
    private val framesOut = AtomicLong()

    /** Bytes from the WebSocket on their way to sshj. Blocking when full slows the WebSocket reader. */
    private val inbound = ArrayBlockingQueue<ByteString>(INBOUND_FRAMES)

    /** Where sshj connects: always IPv4 loopback, the address the listener is bound to. */
    val host: String = LOOPBACK.hostAddress ?: "127.0.0.1"

    /** The local port sshj connects to. */
    val port: Int = server.localPort

    private fun ms() = System.currentTimeMillis() - started

    private fun log(m: String) = DiagLog.log(tag, "+${ms()}ms $m")

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            log("onOpen: HTTP ${response.code} ${response.protocol}; " + headers(response))
            opened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val n = framesIn.incrementAndGet()
            bytesIn.addAndGet(bytes.size.toLong())
            if (n <= 3) {
                log("onMessage(binary) #$n: ${bytes.size} bytes" + if (n == 1L) ", starts \"${DiagLog.preview(bytes.toByteArray())}\"" else "")
            }
            // offer() waits while the queue is full, which holds back OkHttp's reader (and so the
            // TCP window) until sshj has taken what came before. Nothing is dropped.
            while (!closed.get()) {
                if (inbound.offer(bytes, 200, TimeUnit.MILLISECONDS)) return
            }
            log("onMessage(binary) #$n: ${bytes.size} bytes arrived after the bridge closed")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // The bridge speaks binary only; a text frame would corrupt the SSH stream.
            log("!!! onMessage(TEXT): ${text.length} chars, starts \"${DiagLog.preview(text.toByteArray(), 48)}\". A text frame is not SSH; closing.")
            fail(IOException("The WebSocket sent text instead of SSH bytes."))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            log("onClosing: code $code, reason \"${reason.take(120)}\"")
            webSocket.close(1000, null)
            serverEnded("the server closed the WebSocket ($code)")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log("onClosed: code $code, reason \"${reason.take(120)}\"")
            serverEnded("the WebSocket closed ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log("onFailure: ${DiagLog.describe(t)}; response: " +
                (response?.let { "HTTP ${it.code} ${it.protocol}; " + headers(it) } ?: "none"))
            val why = when {
                response != null && opened.count > 0L -> IOException("The WebSocket upgrade was refused (HTTP ${response.code}).", t)
                else -> t
            }
            if (opened.count > 0L) fail(why) else serverEnded("the WebSocket failed: ${t.javaClass.simpleName}")
        }
    }

    /** Only the handshake headers that say what was negotiated; no cookies, ids or addresses. */
    private fun headers(r: Response): String =
        listOf("Upgrade", "Connection", "Sec-WebSocket-Protocol", "Sec-WebSocket-Extensions", "Server")
            .joinToString(", ") { h -> "$h: ${r.header(h) ?: "-"}" }

    /** A failure before the upgrade finished: reported to [open]'s caller. */
    private fun fail(t: Throwable) {
        if (openError == null) openError = t
        opened.countDown()
        close("failed: ${t.javaClass.simpleName}")
    }

    /**
     * The WebSocket is over. Whatever it already delivered still goes to sshj: the local
     * connection is closed once the queue is empty (or at once when nobody ever connected and
     * the accept wait runs out).
     */
    private fun serverEnded(why: String) {
        if (!wsDone.compareAndSet(false, true)) return
        log("WebSocket ended: $why; ${inbound.size} frames still queued for sshj")
        if (opened.count > 0L) fail(IOException("The WebSocket closed before it opened."))
    }

    private fun start(client: OkHttpClient, timeoutMs: Long) {
        log("listening on $host:$port (bound to ${server.inetAddress.hostAddress}); opening ${DiagLog.hideUrl(url)}")
        // Accepting starts before the WebSocket opens, so early server bytes always have a home.
        Thread(::acceptAndPump, "herdrdroid-ws-bridge-$id").apply { isDaemon = true; start() }
        // No headers, no subprotocol, no query: a plain RFC 6455 upgrade over HTTP/1.1.
        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        if (!opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            log("no upgrade after ${timeoutMs} ms")
            close("open timed out")
            throw IOException("The WebSocket didn't open within ${timeoutMs / 1000} s (the server may be waking up).")
        }
        openError?.let {
            close("open failed")
            throw if (it is IOException) it else IOException(it.message ?: it.javaClass.simpleName, it)
        }
        log("open; sshj may connect to $host:$port")
    }

    /** Accepts sshj's one connection, then pumps both directions until either side ends. */
    private fun acceptAndPump() {
        val s = try {
            server.soTimeout = 30_000
            server.accept().also { log("accepted the local connection from ${it.inetAddress.hostAddress}:${it.port}") }
        } catch (e: Exception) {
            if (!closed.get()) log("accept ended without a connection: ${DiagLog.describe(e)}")
            close("no local connection")
            return
        } finally {
            runCatching { server.close() } // exactly one connection
        }
        s.tcpNoDelay = true
        socket = s
        accepted.countDown()
        if (closed.get()) { runCatching { s.close() }; return }
        // WebSocket -> sshj, in arrival order, including anything queued before the accept.
        val down = Thread({
            var why = "stopped"
            try {
                val out = s.getOutputStream()
                var written = 0L
                while (!closed.get()) {
                    val b = inbound.poll(200, TimeUnit.MILLISECONDS)
                    if (b == null) {
                        if (wsDone.get() && inbound.isEmpty()) { why = "the WebSocket ended and everything it sent was delivered"; break }
                        continue
                    }
                    out.write(b.toByteArray())
                    written += b.size
                    if (inbound.isEmpty()) out.flush()
                    if (written == b.size.toLong()) log("wrote the first ${b.size} bytes to sshj")
                }
            } catch (e: Exception) {
                why = "writing to sshj failed: ${DiagLog.describe(e)}"
            } finally {
                close(why)
            }
        }, "herdrdroid-ws-down-$id").apply { isDaemon = true; start() }
        // sshj -> WebSocket, as binary frames only (never send(String)). OkHttp masks them.
        var why = "stopped"
        try {
            opened.await(30, TimeUnit.SECONDS)
            val input = s.getInputStream()
            val buf = ByteArray(32 * 1024)
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) { why = "sshj closed the local connection"; break }
                val w = ws ?: run { why = "no WebSocket"; null } ?: break
                if (wsDone.get()) { why = "sshj wrote $n bytes after the WebSocket ended"; break }
                // OkHttp queues sends in memory and refuses past 16 MB; wait while it drains.
                while (!closed.get() && w.queueSize() > OUTBOUND_HIGH_WATER) Thread.sleep(5)
                val k = framesOut.incrementAndGet()
                bytesOut.addAndGet(n.toLong())
                val frame = buf.toByteString(0, n)
                if (k <= 3) {
                    log("ws.send(binary) #$k: $n bytes" + if (k == 1L) ", starts \"${DiagLog.preview(frame.toByteArray())}\"" else "")
                }
                if (!w.send(frame)) { why = "ws.send refused frame #$k (the WebSocket is closing or its queue is full)"; break }
            }
        } catch (e: Exception) {
            why = "reading from sshj failed: ${DiagLog.describe(e)}"
        } finally {
            close(why)
            down.interrupt()
        }
    }

    /** Closes the socket, the listener and the WebSocket together. Safe to call more than once. */
    fun close(why: String = "closed by the app") {
        if (!closed.compareAndSet(false, true)) return
        log("closing: $why. In: ${bytesIn.get()} bytes in ${framesIn.get()} frames; out: ${bytesOut.get()} bytes in ${framesOut.get()} frames")
        // A close while the upgrade is still pending (Cancel) ends the wait at once.
        if (opened.count > 0L) { if (openError == null) openError = IOException("The connection was cancelled."); opened.countDown() }
        runCatching { ws?.close(1000, null) }
        runCatching { socket?.close() }
        runCatching { server.close() }
    }

    val isOpen: Boolean get() = !closed.get()

    companion object {
        private const val INBOUND_FRAMES = 256
        private const val OUTBOUND_HIGH_WATER = 1L shl 20
        private val SEQ = AtomicLong()

        /** IPv4 loopback by its address, never getLoopbackAddress() (which is ::1 on Android). */
        val LOOPBACK: InetAddress = Inet4Address.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

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
