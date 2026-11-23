package com.billmongan.herdrdroid.ssh

import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * 2.2.6 (Bill: Laptop2 signed in over the LAN, then large replies stalled; pings from the server
 * to the phone lost every packet of 1200 bytes or more). "Small packets" makes the phone advertise
 * a smaller TCP maximum segment size (MSS) in its SYN, so the server sends replies in segments
 * that fit through the lossy Wi-Fi path. Nothing changes on the server.
 *
 * How: Linux's TCP_MAXSEG socket option, set on the socket after it is created and before it
 * connects, clamps the MSS the SYN advertises. It applies to direct TCP connections only; a
 * WebSocket server's SSH bytes go to a local bridge, so the option would do nothing there.
 */
object SmallPackets {
    /** Linux TCP_MAXSEG (netinet/tcp.h); android.system.OsConstants has no name for it. */
    const val TCP_MAXSEG = 2
    /** The smallest MSS every IPv4 host must accept (RFC 879). */
    const val MIN = 536
    /** A full Ethernet frame (1500) less 40 bytes of IPv4 and TCP headers: no clamp at all. */
    const val MAX = 1460
    /** The default: under the 1200-byte size where Bill's pings began to drop. */
    const val DEFAULT = 1200

    fun clamp(mss: Int): Int = mss.coerceIn(MIN, MAX)
}

/**
 * Sets and reads TCP_MAXSEG on a not-yet-connected [Socket]. The production one is
 * [AndroidMssOps]; tests pass their own so the order (before connect) and failures can be checked
 * on a plain JVM.
 */
interface MssOps {
    /** Sets the MSS on [socket] and returns the route used (a short fixed word); throws on failure. */
    fun set(socket: Socket, mss: Int): String
    /** The socket's current MSS, or null when it can't be read. */
    fun get(socket: Socket): Int?
}

/**
 * The Android route. java.net.Socket has no public way to set TCP_MAXSEG, so the option goes
 * through android.system.Os on the socket's file descriptor:
 *  - API 29 and later: ParcelFileDescriptor.fromSocket returns a dup of the socket's descriptor;
 *    the option is set on the dup (it is the same kernel socket) and the dup is closed.
 *  - API 26 to 28: fromSocket did not dup then (closing it would close the socket itself), so the
 *    descriptor comes from the hidden Socket.getFileDescriptor$() by reflection. Hidden API
 *    checks began at API 28, where this method was on the allowed grey list; if it is refused,
 *    the option is simply not set.
 * Reading the MSS back uses the hidden Os.getsockoptInt by reflection (best effort).
 * Reading SO_TIMEOUT first makes the platform create the socket's descriptor (an unconnected
 * java.net.Socket only creates it when first used).
 */
object AndroidMssOps : MssOps {
    private fun <T> onDescriptor(socket: Socket, block: (FileDescriptor) -> T): Pair<T, String> {
        socket.soTimeout
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val pfd = android.os.ParcelFileDescriptor.fromSocket(socket) ?: throw IOException("no descriptor")
            try {
                return block(pfd.fileDescriptor) to "pfd-dup"
            } finally {
                runCatching { pfd.close() }
            }
        }
        val fd = Socket::class.java.getMethod("getFileDescriptor\$").invoke(socket) as FileDescriptor?
            ?: throw IOException("no descriptor")
        return block(fd) to "reflect"
    }

    override fun set(socket: Socket, mss: Int): String = onDescriptor(socket) {
        android.system.Os.setsockoptInt(it, android.system.OsConstants.IPPROTO_TCP, SmallPackets.TCP_MAXSEG, mss)
    }.second

    /**
     * Os.getsockoptInt is not in the public SDK (setsockoptInt is), so the read-back goes by
     * reflection; where the platform refuses it, the effective MSS is logged as unknown.
     */
    private val getsockoptInt by lazy {
        runCatching {
            android.system.Os::class.java.getMethod("getsockoptInt", FileDescriptor::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    override fun get(socket: Socket): Int? = runCatching {
        val m = getsockoptInt ?: return null
        onDescriptor(socket) { fd: FileDescriptor ->
            m.invoke(null, fd, android.system.OsConstants.IPPROTO_TCP, SmallPackets.TCP_MAXSEG) as Int
        }.first
    }.getOrNull()
}

/**
 * What one connection did with the MSS, for the diagnostic log: names and numbers only.
 * [effective] is the kernel's MSS after connecting (the smaller of ours and the server's).
 */
data class MssReport(val requested: Int, val applied: Boolean, val route: String?, val error: String?, val effective: Int?)

/**
 * Bytes received on the current connection's socket and when the last one came (2.2.6
 * diagnostics): the silence before a link is declared lost, and a session's bytes in.
 */
class InboundCounter {
    private val total = java.util.concurrent.atomic.AtomicLong()
    @Volatile var lastAt: Long = 0L
        private set
    val bytes: Long get() = total.get()
    fun add(n: Int) {
        if (n <= 0) return
        total.addAndGet(n.toLong())
        lastAt = System.currentTimeMillis()
    }
}

private class CountingInput(private val inner: java.io.InputStream, private val counter: InboundCounter) : java.io.FilterInputStream(inner) {
    override fun read(): Int = inner.read().also { if (it >= 0) counter.add(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { counter.add(it) }
}

/**
 * A direct TCP socket for SSHJ. With [mss] set it applies TCP_MAXSEG just before it connects; in
 * every case (2.2.6 diagnostics) it times the TCP connect, reads the socket's facts afterwards,
 * and counts the bytes it receives. Nothing here can fail the connection.
 */
internal class MssSocket(
    private val mss: Int?,
    private val ops: MssOps,
    private val report: (MssReport) -> Unit,
    private val facts: (Long, com.billmongan.herdrdroid.diag.LinkStats.SocketFacts) -> Unit = { _, _ -> },
    private val inbound: InboundCounter? = null,
) : Socket() {
    @Volatile private var counted: java.io.InputStream? = null

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        var route: String? = null
        var error: String? = null
        if (mss != null) try {
            route = ops.set(this, mss)
        } catch (e: Exception) {
            error = e.javaClass.simpleName.ifBlank { "Exception" }
        } catch (e: LinkageError) {
            error = e.javaClass.simpleName.ifBlank { "LinkageError" }
        }
        // A failed option never fails the connection: it connects with the normal MSS.
        val t0 = System.currentTimeMillis()
        super.connect(endpoint, timeout)
        val connectMs = System.currentTimeMillis() - t0
        // Read back whenever possible: with small packets off it still shows the MSS in use.
        val effective = if (mss == null || route != null) runCatching { ops.get(this) }.getOrNull() else null
        val asked = mss
        if (asked != null) runCatching { report(MssReport(asked, route != null, route, error, effective)) }
        runCatching {
            facts(connectMs, com.billmongan.herdrdroid.diag.LinkStats.SocketFacts(
                mss, effective,
                runCatching { sendBufferSize }.getOrNull(), runCatching { receiveBufferSize }.getOrNull(),
                runCatching { tcpNoDelay }.getOrNull(), runCatching { keepAlive }.getOrNull(),
            ))
        }
    }

    override fun getInputStream(): java.io.InputStream {
        val counter = inbound ?: return super.getInputStream()
        counted?.let { return it }
        return synchronized(this) { counted ?: CountingInput(super.getInputStream(), counter).also { counted = it } }
    }
}

/**
 * The socket factory SSHJ's direct TCP transport uses (SSHClient.socketFactory). SSHJ calls
 * [createSocket] with no arguments and then connects the socket with its own connect timeout, so
 * a small-packets MSS ([mss], null when off) is set before the SYN goes out.
 */
class MssSocketFactory(
    mss: Int?,
    private val ops: MssOps = AndroidMssOps,
    private val report: (MssReport) -> Unit = {},
    private val facts: (Long, com.billmongan.herdrdroid.diag.LinkStats.SocketFacts) -> Unit = { _, _ -> },
    private val inbound: InboundCounter? = null,
) : SocketFactory() {
    val mss: Int? = mss?.let { SmallPackets.clamp(it) }

    override fun createSocket(): Socket = MssSocket(mss, ops, report, facts, inbound)

    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
}

/**
 * The automatic fallback (2.2.6). After a sign-in on a direct TCP server with small packets off,
 * a dropped link or a read that timed out within [windowMs] means large replies are probably being
 * lost: the session reconnects once with small packets on. It fires at most once per sign-in
 * ([arm]); the reconnects that follow never arm it again, so it cannot loop.
 */
class SmallPacketFallback(private val windowMs: Long = WINDOW_MS, private val mss: Int = SmallPackets.DEFAULT) {
    private var armedFor: String? = null
    private var armedAt = 0L
    private var fired = false

    /** The phone's network change count when it was armed: a change since then is not lossy Wi-Fi. */
    @Volatile var armedNetChanges = 0
        private set

    /** At each sign-in. [eligible]: a direct TCP server whose connection has small packets off. */
    @Synchronized fun arm(serverId: String, eligible: Boolean, now: Long, netChanges: Int = 0) {
        armedFor = if (eligible) serverId else null
        armedAt = now
        armedNetChanges = netChanges
        fired = false
    }

    /** True when [fire] would fire now; changes nothing. */
    @Synchronized fun wouldFire(serverId: String?, reason: String?, now: Long): Boolean =
        reason != null && serverId != null && !fired && armedFor == serverId && now - armedAt <= windowMs

    @Synchronized fun disarm() {
        armedFor = null
    }

    /** The MSS to reconnect with, once, when [reason] is a trigger for [serverId] inside the window; else null. */
    @Synchronized fun fire(serverId: String?, reason: String?, now: Long): Int? {
        if (!wouldFire(serverId, reason, now)) return null
        fired = true
        armedFor = null
        return mss
    }

    /** Milliseconds since the sign-in that armed it, for the log. */
    @Synchronized fun sinceArmed(now: Long): Long = now - armedAt

    companion object {
        const val WINDOW_MS = 90_000L

        /** "link-drop" for a lost connection, "read-timeout" for a command that ran out of time, else null. */
        fun reasonFor(e: Throwable?): String? = when {
            e == null -> null
            e is LinkDown -> "link-drop"
            e is CommandTimeout -> "read-timeout"
            e is com.billmongan.herdrdroid.herdr.HerdrError && e.exit == -2 && e.message.orEmpty().startsWith(CommandTimeout.PREFIX) -> "read-timeout"
            else -> null
        }
    }
}

/**
 * 2.2.7 (Bill: failures over cellular plus a VPN to his home router, where the tunnel lowers the
 * path MTU and the "too big" replies that PMTU discovery needs are blocked). When the phone's
 * default network is a VPN at connect time, a direct TCP connection uses small packets without
 * waiting for the 2.2.6 fallback. [enabled] mirrors the global setting "Small packets on VPN
 * (automatic)" (on by default); [networkType] is the seam unit tests replace, so they never need a
 * real ConnectivityManager. The type is a word only ("vpn", "wifi", ...), never a name or address.
 */
object VpnSmallPackets {
    @Volatile var enabled: Boolean = true
    @Volatile var networkType: () -> String = { com.billmongan.herdrdroid.diag.NetworkWatch.current() }
}

/**
 * The MSS one connection opens with and why ([reason] is one of the constants below), plus the
 * network type seen when it was chosen; for the connect line. Nonsecret: words and numbers only.
 */
data class MssChoice(val mss: Int?, val reason: String, val net: String) {
    companion object {
        const val SETTING = "setting"
        const val VPN_AUTO = "vpn-auto"
        const val FALLBACK = "fallback"
        const val NONE = "none"

        /**
         * The rules, in order: a WebSocket never sets an MSS; the server's own small-packets
         * setting wins (its value, also on a VPN); then this session's 2.2.6 fallback; then, on a
         * VPN with the automatic setting on, [SmallPackets.DEFAULT]; otherwise the normal MSS.
         */
        fun choose(overWebSocket: Boolean, serverMss: Int?, fallbackMss: Int?, vpnAuto: Boolean, net: String): MssChoice = when {
            overWebSocket -> MssChoice(null, NONE, net)
            serverMss != null -> MssChoice(SmallPackets.clamp(serverMss), SETTING, net)
            fallbackMss != null -> MssChoice(SmallPackets.clamp(fallbackMss), FALLBACK, net)
            vpnAuto && net == "vpn" -> MssChoice(SmallPackets.DEFAULT, VPN_AUTO, net)
            else -> MssChoice(null, NONE, net)
        }
    }
}
