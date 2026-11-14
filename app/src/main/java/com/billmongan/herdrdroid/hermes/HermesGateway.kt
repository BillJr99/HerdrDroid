package com.billmongan.herdrdroid.hermes

import com.billmongan.herdrdroid.ssh.Forward
import com.billmongan.herdrdroid.ssh.HerdrSsh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HermesError(message: String) : Exception(message)

/**
 * A client for Hermes's own gateway, the JSON-RPC socket its terminal UI and desktop app use.
 *
 * `hermes gateway attach --json`, run over SSH, gives the gateway's loopback address and a
 * one-use ticket (valid 30 seconds). The phone reaches that address through an SSH port forward,
 * so the connection arrives from 127.0.0.1 exactly as a local client's would; nothing on the
 * computer is changed or exposed. The ticket is kept only in memory for the one connection.
 */
class HermesGateway(private val ssh: HerdrSsh) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    private var fwd: Forward? = null
    private val ids = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    /** Events (`method: "event"`) and questions from Hermes (approval, clarify, …). */
    private val _frames = MutableSharedFlow<JSONObject>(extraBufferCapacity = 512)
    val frames: SharedFlow<JSONObject> = _frames

    @Volatile var open = false
        private set
    /** Set when the socket closes on its own; the screen reconnects. */
    @Volatile var closedReason: String? = null
        private set

    suspend fun connect() {
        close()
        closedReason = null
        val r = ssh.exec(
            "H=\$(command -v hermes 2>/dev/null || echo \"\$HOME/.local/bin/hermes\"); \"\$H\" gateway attach --json",
            timeoutSeconds = 45,
        )
        val attach = runCatching { JSONObject(r.out.trim()) }.getOrElse {
            throw HermesError("Couldn't reach Hermes on this computer (${r.err.trim().lines().lastOrNull() ?: "no answer"}).")
        }
        if (attach.optString("state") != "ready") {
            throw HermesError("Hermes's gateway isn't ready: " + (attach.optString("detail").ifBlank { attach.optString("state") }))
        }
        val url = attach.getString("url")
        val (host, port, path) = parseGatewayUrl(url)
        val protos: JSONArray = attach.getJSONArray("protocols")
        val f = ssh.forward(host, port)
        fwd = f
        val ready = CompletableDeferred<Unit>()
        val req = Request.Builder()
            .url("ws://127.0.0.1:${f.localPort}$path")
            .header("Host", (if (host.contains(':')) "[$host]" else host) + ":$port")
            .header("Sec-WebSocket-Protocol", (0 until protos.length()).joinToString(", ") { protos.getString(it) })
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                val method = o.optString("method")
                if (method == "event" && o.optJSONObject("params")?.optString("type") == "gateway.ready") {
                    ready.complete(Unit)
                }
                if (o.has("id") && method.isEmpty() && (o.has("result") || o.has("error"))) {
                    waiting.remove(o.get("id").toString())?.complete(o)
                } else {
                    _frames.tryEmit(o)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                shut("closed ($code)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready.completeExceptionally(t)
                shut(t.message ?: t.javaClass.simpleName)
            }
        })
        try {
            withTimeout(20_000) { ready.await() }
        } catch (e: Exception) {
            close()
            throw HermesError("Hermes's gateway didn't answer: ${e.message}")
        }
        request("client.capabilities", JSONObject().put("server_requests", true))
    }

    private fun shut(why: String) {
        if (!open && closedReason != null) return
        open = false
        closedReason = why
        waiting.values.forEach { it.completeExceptionally(HermesError("The connection to Hermes closed.")) }
        waiting.clear()
        _frames.tryEmit(JSONObject().put("method", "_closed").put("reason", why))
    }

    /** Sends one JSON-RPC request and returns its result, or throws with Hermes's own message. */
    suspend fun request(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 30_000): JSONObject {
        val socket = ws ?: throw HermesError("Not connected to Hermes.")
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<JSONObject>()
        waiting[id.toString()] = d
        socket.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString())
        val reply = try {
            withTimeout(timeoutMs) { d.await() }
        } finally {
            waiting.remove(id.toString())
        }
        reply.optJSONObject("error")?.let { e ->
            throw HermesError(e.optString("message").ifBlank { "Hermes refused $method." })
        }
        return reply.optJSONObject("result") ?: JSONObject()
    }

    /** Answers a question Hermes asked (approval, clarify). */
    fun respond(id: Any, result: JSONObject) {
        ws?.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
    }

    /** Declines a question this app doesn't answer, so the agent doesn't wait out a timeout. */
    fun decline(id: Any, method: String) {
        ws?.send(
            JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", -32601).put("message", "HerdrDroid does not answer $method"))
                .toString(),
        )
    }

    fun close() {
        open = false
        runCatching { ws?.close(1000, "bye") }
        ws = null
        fwd?.close()
        fwd = null
    }
}

/**
 * The host, port and path (with any query) of the address `hermes gateway attach` gives (1.4.0,
 * from a code review: only exactly ws://host:port/path was accepted). An IPv6 host may be in
 * brackets; with no path, "/" is used. A wss:// gateway is refused with a clear message: the app
 * reaches the gateway through its SSH tunnel at 127.0.0.1, where the gateway's TLS certificate
 * wouldn't match.
 */
fun parseGatewayUrl(url: String): Triple<String, Int, String> {
    val u = url.trim()
    if (u.startsWith("wss://", ignoreCase = true)) throw HermesError("Hermes's gateway uses wss://, which the app can't reach through its SSH tunnel.")
    val m = Regex("""^ws://(\[[0-9A-Fa-f:.]+]|[^:/?#\[\]]+)(?::(\d+))?([/?#].*)?$""", RegexOption.IGNORE_CASE).matchEntire(u)
        ?: throw HermesError("Unexpected gateway address.")
    val host = m.groupValues[1].removePrefix("[").removeSuffix("]")
    val port = m.groupValues[2].toIntOrNull() ?: 80
    val rest = m.groupValues[3]
    val path = when {
        rest.isEmpty() -> "/"
        rest.startsWith("/") -> rest
        else -> "/$rest"
    }
    return Triple(host, port, path)
}
