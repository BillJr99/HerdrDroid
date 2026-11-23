package com.billmongan.herdrdroid.diag

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 2.2.6 (Bill: "let's also log more to diagnostics in case this continues to fail"). The extra
 * `[link]` lines: connect stage timings, socket facts, per-read byte counts and timings, link
 * health, reconnects and a per-session summary. The same rules as [LinkDiag]: opaque server tags,
 * fixed words, numbers and class names only; never a host, address, URL, network name, credential,
 * command text, command output, pane title or transcript.
 */

/** The class names of [t] and its causes, outermost first ("LinkDown<SocketTimeoutException"); never messages. */
fun errorChain(t: Throwable?, max: Int = 5): String {
    if (t == null) return "none"
    val names = mutableListOf<String>()
    var e: Throwable? = t
    while (e != null && names.size < max) {
        names += e.javaClass.simpleName.ifBlank { "Exception" }
        e = e.cause.takeIf { it !== e }
    }
    return names.joinToString("<")
}

/**
 * Names a read for the diagnostic log ("list-read", "log-read", "probe"). Set with
 * withContext(ReadLabel(...)) around a herdr command; [com.billmongan.herdrdroid.ssh.HerdrSsh]
 * reads it from the coroutine and logs one line for the read. Unlabelled commands are not logged.
 */
class ReadLabel(val label: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReadLabel>
}

/** How one read went: names and numbers only. */
data class ReadStats(
    val label: String,
    val bytes: Long,
    val firstByteMs: Long?,
    val totalMs: Long,
    val channelMs: Long?,
    val exit: Int?,
    /** "no" when it finished in time, "no-first-byte" or "partial" when it timed out. */
    val timeout: String,
)

/**
 * Times one command's channel: when it opened, the first byte (stdout or stderr) and the bytes
 * that arrived. Fed by the stream readers on their own threads, so every field is guarded.
 */
class ReadMeter(private val clock: () -> Long = System::currentTimeMillis) {
    private val start = clock()
    private var channelAt: Long? = null
    private var firstAt: Long? = null
    private var bytes = 0L

    @Synchronized fun channelOpen() { if (channelAt == null) channelAt = clock() }

    @Synchronized fun onBytes(n: Int) {
        if (n <= 0) return
        if (firstAt == null) firstAt = clock()
        bytes += n
    }

    @Synchronized fun bytes(): Long = bytes

    @Synchronized fun finish(label: String, exit: Int?, timedOut: Boolean): ReadStats = ReadStats(
        label = label,
        bytes = bytes,
        firstByteMs = firstAt?.let { it - start },
        totalMs = clock() - start,
        channelMs = channelAt?.let { it - start },
        exit = exit,
        timeout = when {
            !timedOut -> "no"
            bytes == 0L -> "no-first-byte"
            else -> "partial"
        },
    )
}

/**
 * Keeps a repeating line to one per [everyMs] per key, as 2.2.5 does for quiet failures, and
 * counts what it held back so the next line can say so.
 */
class RateLimit(private val everyMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val last = HashMap<String, Long>()
    private val held = HashMap<String, Int>()

    /** Null when the line should be held back; else how many were held back since the last one. */
    @Synchronized fun admit(key: String): Int? {
        val now = clock()
        val at = last[key]
        if (at != null && now - at < everyMs) {
            held[key] = (held[key] ?: 0) + 1
            return null
        }
        last[key] = now
        return held.remove(key) ?: 0
    }
}

object LinkStats {
    /** A completed read once per 30 s per server and label; a timed-out or failed one once per 10 s. */
    private val okReads = RateLimit(30_000L)
    private val badReads = RateLimit(10_000L)

    fun readLine(serverId: String?, r: ReadStats, held: Int = 0): String = buildString {
        append("server=").append(LinkDiag.serverTag(serverId))
        append(" read=").append(r.label)
        append(" bytes=").append(r.bytes)
        append(" ttfb_ms=").append(r.firstByteMs ?: "none")
        append(" total_ms=").append(r.totalMs)
        r.channelMs?.let { append(" chan_ms=").append(it) }
        append(" exit=").append(r.exit ?: "none")
        append(" timeout=").append(r.timeout)
        if (r.timeout == "partial") append(" stalled_after_bytes=").append(r.bytes)
        if (held > 0) append(" similar_held=").append(held)
    }

    fun read(serverId: String?, r: ReadStats) {
        val bad = r.timeout != "no" || (r.exit ?: 0) != 0
        val key = "${LinkDiag.serverTag(serverId)}|${r.label}|${if (bad) r.timeout else "ok"}"
        val held = (if (bad) badReads else okReads).admit(key) ?: return
        DiagLog.log("link", readLine(serverId, r, held))
    }

    /**
     * The per-connection lines (connect timings, socket facts, MSS) once per 30 s per server and
     * kind, so a watcher that reconnects often can't crowd the log; the next line says how many
     * were held back.
     */
    private val perConnection = RateLimit(30_000L)

    private fun admitConnection(serverId: String?, kind: String, line: String) {
        val held = perConnection.admit("${LinkDiag.serverTag(serverId)}|$kind") ?: return
        DiagLog.log("link", if (held > 0) "$line similar_held=$held" else line)
    }

    fun connect(serverId: String?, line: String) = admitConnection(serverId, "connect", line)

    fun socket(serverId: String?, f: SocketFacts) = admitConnection(serverId, "socket", socketLine(serverId, f))

    fun mss(serverId: String?, r: com.billmongan.herdrdroid.ssh.MssReport) =
        admitConnection(serverId, if (r.applied) "mss-ok" else "mss-failed", LinkDiag.mssLine(serverId, r))

    /** Stage timings of one connect, in ms; null where a stage did not happen (a WebSocket has no TCP stage here). */
    data class ConnectTimes(
        val tcpMs: Long?,
        /** Version exchange and the algorithm lists (until the server's KEXINIT was read). */
        val identKexinitMs: Long?,
        /** Until the server's key exchange reply (the large one) arrived and its host key was checked. */
        val kexReplyMs: Long?,
        /** Until the new keys were in use. */
        val newKeysMs: Long?,
        val authMs: Long?,
    )

    fun connectLine(serverId: String?, transport: String, t: ConnectTimes, algs: String?, net: String, netChanges: Int, keepAliveSeconds: Int? = null): String = buildString {
        append("server=").append(LinkDiag.serverTag(serverId))
        append(" connect transport=").append(transport)
        fun ms(k: String, v: Long?) { append(' ').append(k).append('=').append(v ?: "none") }
        ms("tcp_ms", t.tcpMs)
        ms("ident_kexinit_ms", t.identKexinitMs)
        ms("kex_reply_ms", t.kexReplyMs)
        ms("newkeys_ms", t.newKeysMs)
        ms("auth_ms", t.authMs)
        algs?.let { append(' ').append(it) }
        keepAliveSeconds?.let { append(" keepalive_s=").append(it) }
        append(" net=").append(net).append(" net_changes=").append(netChanges)
    }

    /** The socket after connecting: buffers, options and the MSS asked for and in effect. */
    fun socketLine(serverId: String?, f: SocketFacts): String = buildString {
        append("server=").append(LinkDiag.serverTag(serverId))
        append(" socket mss_requested=").append(f.requestedMss ?: "none")
        append(" mss_effective=").append(f.effectiveMss ?: "unknown")
        append(" sndbuf=").append(f.sendBuffer ?: "unknown")
        append(" rcvbuf=").append(f.receiveBuffer ?: "unknown")
        append(" nodelay=").append(f.noDelay ?: "unknown")
        append(" so_keepalive=").append(f.keepAlive ?: "unknown")
    }

    data class SocketFacts(
        val requestedMss: Int?, val effectiveMss: Int?, val sendBuffer: Int?, val receiveBuffer: Int?,
        val noDelay: Boolean?, val keepAlive: Boolean?,
    )

    /** A link declared lost: the error's class chain, the silence before it, and the keepalive setting. */
    fun lostLine(serverId: String?, e: Throwable?, sinceLastByteMs: Long?, keepAliveSeconds: Int, bytesIn: Long): String = buildString {
        append("server=").append(LinkDiag.serverTag(serverId))
        append(" lost error=").append(errorChain(e))
        append(" since_last_byte_ms=").append(sinceLastByteMs ?: "none")
        append(" keepalive_s=").append(keepAliveSeconds)
        // SSHJ's keepalive is a heartbeat the server never answers, so missed replies can't be
        // counted; the keepalive intervals that passed with nothing received stand in for them.
        append(" silent_keepalives=").append(if (sinceLastByteMs == null || keepAliveSeconds <= 0) "none" else sinceLastByteMs / (keepAliveSeconds * 1000L))
        append(" bytes_in=").append(bytesIn)
    }

    fun reconnectLine(serverId: String?, attempt: Int, backoffMs: Long, reason: String, outcome: String, ms: Long, e: Throwable? = null): String = buildString {
        append("server=").append(LinkDiag.serverTag(serverId))
        append(" reconnect attempt=").append(attempt)
        append(" backoff_ms=").append(backoffMs)
        append(" reason=").append(reason)
        append(" result=").append(outcome)
        append(" ms=").append(ms)
        if (e != null) append(" error=").append(errorChain(e))
    }

    /** Per session: counted from sign-in to disconnect. */
    class Session(val serverId: String, val startedAt: Long, val netChangesAtStart: Int) {
        @Volatile var reconnectAttempts = 0
        @Volatile var reconnectSuccesses = 0
        @Volatile var fallbackUsed = false
    }

    fun summaryLine(s: Session, stalls: Long, bytesIn: Long, netChanges: Int, now: Long, why: String): String = buildString {
        append("server=").append(LinkDiag.serverTag(s.serverId))
        append(" session-summary end=").append(why)
        append(" duration_s=").append((now - s.startedAt) / 1000)
        append(" reconnect_attempts=").append(s.reconnectAttempts)
        append(" reconnect_successes=").append(s.reconnectSuccesses)
        append(" stalls=").append(stalls)
        append(" bytes_in=").append(bytesIn)
        append(" fallback=").append(if (s.fallbackUsed) "yes" else "no")
        append(" net_changes=").append((netChanges - s.netChangesAtStart).coerceAtLeast(0))
    }
}

/**
 * The phone's active network, by type only ("wifi", "cellular", "vpn", "ethernet", "other",
 * "none"), and how many times the default network changed. Never a network name (SSID), BSSID or
 * address: only the transport flags of the capabilities are read.
 */
object NetworkWatch {
    @Volatile var type: String = "unknown"
        private set
    @Volatile var changes: Int = 0
        private set
    private var lastNetwork: Any? = null
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /** One default network seen with its transports; a different network or type counts as a change. */
    @Synchronized fun observe(network: Any?, newType: String) {
        val first = lastNetwork == null && type == "unknown"
        if (!first && network == lastNetwork && newType == type) return
        lastNetwork = network
        if (!first) changes++
        type = newType
        DiagLog.log("link", "network=$type changes=$changes")
    }

    fun typeOf(vpn: Boolean, wifi: Boolean, cellular: Boolean, ethernet: Boolean): String = when {
        vpn -> "vpn"
        wifi -> "wifi"
        cellular -> "cellular"
        ethernet -> "ethernet"
        else -> "other"
    }

    fun start(context: android.content.Context) {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                    observe(network, typeOf(
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN),
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI),
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR),
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET),
                    ))
                }
                override fun onLost(network: android.net.Network) = observe(null, "none")
            })
        }.onFailure { DiagLog.log("link", "network=unknown watch=failed error=${errorChain(it)}") }
    }
}
