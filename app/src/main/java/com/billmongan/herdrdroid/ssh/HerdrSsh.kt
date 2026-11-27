package com.billmongan.herdrdroid.ssh

import com.hierynomus.sshj.key.KeyAlgorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.billmongan.herdrdroid.herdr.admit
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.signature.SignatureECDSA
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import net.schmizz.sshj.connection.channel.direct.Parameters
import com.billmongan.herdrdroid.diag.DiagLog
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The server offered a host key the user has not confirmed yet. */
class UnconfirmedHostKey(val fingerprint: String) : Exception("New host key $fingerprint")

/** The server's host key no longer matches the one the user confirmed. */
class HostKeyChanged(val expected: String, val actual: String) :
    Exception("The host key changed. Expected $expected, got $actual")

/** The server turned down every sign-in method the app offered. */
class AuthRejected(message: String) : Exception(message)

/** The network path to the server failed: a dropped socket, a timeout, or a refused connection. */
class LinkDown(cause: Throwable) : Exception(cause.message ?: cause.javaClass.simpleName, cause)

/**
 * 2.2.6 (Grok's review of the fallback): marks, for one user action, whether any of its commands
 * reached the computer (its exec request went out). The action runs under withContext(SendTrace());
 * the transport sets [started] just before each command's exec. An action whose command started is
 * never put back in the retry queue after a lost link, so a key or message is never sent twice.
 */
class SendTrace : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    @Volatile var started = false
    companion object Key : kotlin.coroutines.CoroutineContext.Key<SendTrace>
}

/**
 * Marks a command that writes (types, presses keys, closes a tab). [SendTrace] is set only for
 * these, never for the reads an action makes first (Grok's re-review).
 */
class WriteCommand private constructor() : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<WriteCommand> {
        val MARK = WriteCommand()
    }
}

data class ExecResult(val exit: Int, val out: String, val err: String)

/** Reads a streaming SSH channel without waiting for the peer to print when cancelled. */
internal suspend fun readFollowLines(input: InputStream, onLine: suspend (String) -> Unit) {
    val reader = input.bufferedReader(Charsets.UTF_8)
    while (true) {
        val line = try {
            runInterruptible(Dispatchers.IO) { reader.readLine() }
        } catch (error: IOException) {
            // SSHJ reports an interrupted channel read as InterruptedIOException.
            currentCoroutineContext().ensureActive()
            throw error
        } ?: return
        if (line.isNotBlank()) onLine(line)
    }
}

/** A command still running when its time was up; its channel was closed (1.3.0). */
class CommandTimeout(seconds: Long) : Exception("$PREFIX $seconds s") {
    companion object { const val PREFIX = "the command did not finish within" }
}
/** A command that printed more than the app takes; its channel was closed (1.3.0). */
class OutputTooLarge(max: Int) : Exception("the command printed more than ${max / (1024 * 1024)} MB")
/** A failure after the command reached the computer: reported, never retried. */
private class AfterStart(cause: Exception) : Exception(cause.message, cause)

/** The most a command's stdout may print (files fetched have their own, larger cap), and stderr. */
private const val MAX_OUT = 16 * 1024 * 1024
private const val MAX_BYTES = 64 * 1024 * 1024
private const val MAX_ERR = 256 * 1024

/** SSHJ's keepalive (a heartbeat) interval on every connection, in seconds. */
const val KEEPALIVE_SECONDS = 15

/** A stream read into memory up to [cap] bytes; [over] once more arrived. Stderr past its cap is dropped, not an error. */
internal class Capped(private val cap: Int, private val meter: com.billmongan.herdrdroid.diag.ReadMeter? = null) {
    private val buf = java.io.ByteArrayOutputStream()
    @Volatile var over = false
        private set
    private val isErr = cap == MAX_ERR
    fun drain(s: java.io.InputStream) {
        runCatching {
            val b = ByteArray(16 * 1024)
            while (true) {
                val n = s.read(b)
                if (n < 0) break
                meter?.onBytes(n)
                synchronized(buf) {
                    // stderr past its cap is read on and dropped, so the command never stalls on a
                    // full pipe (1.3.2, from a code review); stdout past its cap stops the command.
                    if (buf.size() + n > cap) { if (isErr) return@synchronized; over = true; return@runCatching }
                    buf.write(b, 0, n)
                }
            }
        }
    }
    fun bytes(): ByteArray = synchronized(buf) { buf.toByteArray() }
}

/** A local port forwarded over SSH; [close] stops it. */
class Forward(val localPort: Int, private val stop: () -> Unit) {
    fun close() = stop()
}

/** How the app signs in to one server: a key, a password, or the key first and then the password. */
data class Credentials(val key: KeySource?, val password: String?)

sealed interface KeySource {
    /** A key generated on this phone; the private half never leaves the Android Keystore. */
    data class Keystore(val alias: String) : KeySource

    /** A key file the user imported, already decrypted from the app's vault. */
    data class Imported(val pem: String, val passphrase: String?) : KeySource
}

fun sha256Fingerprint(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/** The one line to add to ~/.ssh/authorized_keys on the server. */
fun authorizedKeysLine(key: PublicKey, comment: String): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    return KeyType.fromKey(key).toString() + " " + Base64.getEncoder().encodeToString(blob) + " " + comment
}

/** Accepts only the fingerprint the user confirmed; reports anything else. */
private class PinnedVerifier(private val pinned: String?) : HostKeyVerifier {
    var seen: String? = null
    /** 2.2.6: when the server's key exchange reply (with its host key) arrived. */
    @Volatile var at: Long? = null

    override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
        at = System.currentTimeMillis()
        val fp = sha256Fingerprint(key)
        seen = fp
        return pinned != null && pinned == fp
    }

    override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> = mutableListOf()
}

/**
 * ECDSA P-256 signing that lets Android pick the provider when the key is used. sshj asks Bouncy
 * Castle by name, and Bouncy Castle cannot sign with a key held inside the Android Keystore.
 * Verifying host keys and encoding signatures still go through sshj's own code.
 */
private class ProviderNeutralEcdsa : Signature {
    private val inner = SignatureECDSA.Factory256().create()
    private var engine: java.security.Signature? = null

    override fun getSignatureName(): String = inner.signatureName
    override fun initVerify(publicKey: PublicKey) {
        engine = null
        inner.initVerify(publicKey)
    }
    override fun initSign(privateKey: PrivateKey) {
        engine = java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }
    }
    override fun update(H: ByteArray) {
        engine?.update(H) ?: inner.update(H)
    }
    override fun update(H: ByteArray, off: Int, len: Int) {
        engine?.update(H, off, len) ?: inner.update(H, off, len)
    }
    override fun sign(): ByteArray = engine?.sign() ?: inner.sign()
    override fun encode(signature: ByteArray): ByteArray = inner.encode(signature)
    override fun verify(sig: ByteArray): Boolean = inner.verify(sig)
}

/** sshj's own ECDSA P-256 algorithm, with only the signing swapped for the provider-neutral one. */
private fun neutralEcdsa(orig: Factory.Named<KeyAlgorithm>) = object : Factory.Named<KeyAlgorithm> {
    override fun getName(): String = orig.name
    override fun create(): KeyAlgorithm = object : KeyAlgorithm by orig.create() {
        override fun newSignature(): Signature = ProviderNeutralEcdsa()
    }
}

private fun oneOff(secret: String) = object : PasswordFinder {
    override fun reqPassword(resource: Resource<*>?): CharArray = secret.toCharArray()
    override fun shouldRetry(resource: Resource<*>?) = false
}

/** Parses a private key file and returns its public half, or throws with a readable reason. */
fun readKeyFile(pem: String, passphrase: String?): PublicKey {
    val provider = SSHClient(DefaultConfig()).loadKeys(pem, null as String?, passphrase?.let { oneOff(it) })
    return provider.public
}

/**
 * One SSH connection, reused for every herdr command, one command at a time. With [wsUrl] set,
 * the SSH bytes travel over that WebSocket ([WsBridge]) instead of a TCP connection to
 * [host]:[port]; everything else, host key pinning and sign-in included, is the same.
 */
class HerdrSsh(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val credentials: Credentials,
    private val pinnedFingerprint: String?,
    private val keystoreKey: (String) -> KeyProvider?,
    private val wsUrl: String? = null,
    /** 2.2.6: the server's small-packets MSS, or null when off. Used on direct TCP only. */
    smallPacketsMss: Int? = null,
    /** 2.2.6: the app's server id, only to tag diagnostic lines (hashed by [LinkDiag]). */
    private val diagServerId: String? = null,
    private val mssOps: MssOps = AndroidMssOps,
    /** 2.2.7: the global "Small packets on VPN (automatic)" setting, read at each connect. */
    private val vpnAuto: () -> Boolean = { VpnSmallPackets.enabled },
    /** 2.2.7: the phone's network type at connect ("vpn", "wifi", ...); a seam for tests. */
    private val networkType: () -> String = { VpnSmallPackets.networkType() },
    /**
     * 2.2.7: this server's session already fell back to small packets (the app's per-session
     * choice), so this connection (a reconnect, the watcher, a status check) starts with them too.
     */
    sessionFallbackMss: Int? = null,
    /** 2.2.7: the global "Small packets on cellular (automatic)" setting, read at each connect. */
    private val cellularAuto: () -> Boolean = { VpnSmallPackets.cellularEnabled },
    /** 2.2.7: whether the network runs over cellular at connect (also under a VPN); a seam for tests. */
    private val overCellular: () -> Boolean = { VpnSmallPackets.cellular() },
    /** 2.2.7: the global "Small packets on Wi-Fi (automatic)" setting, read at each connect. */
    private val wifiAuto: () -> Boolean = { VpnSmallPackets.wifiEnabled },
) {
    /** 2.2.6: the server's own small-packets MSS, or null when its setting is off. */
    private val serverMss: Int? = smallPacketsMss?.let { SmallPackets.clamp(it) }

    /** 2.2.6: the fallback's MSS for this session ([useSmallPackets]), or null when it has not fired. */
    @Volatile private var fallbackMss: Int? = sessionFallbackMss?.let { SmallPackets.clamp(it) }

    /**
     * 2.2.7 (pre-review L4): the session's current choice, asked at each connect, for a connection
     * that outlives it (the watcher): a fallback made later is picked up, and one that ended is dropped.
     */
    @Volatile var sessionFallback: (() -> Int?)? = null

    /**
     * 2.2.7: the MSS a connection made now would use, and why: decided afresh at every connect and
     * reconnect, so a VPN coming up or dropping mid-session takes effect on the next connection
     * only (an open socket's MSS is fixed when it connects).
     */
    fun mssChoice(): MssChoice = MssChoice.choose(
        wsUrl != null, serverMss, fallbackMss ?: sessionFallback?.let { f -> runCatching { f() }.getOrNull() },
        runCatching { vpnAuto() }.getOrDefault(false),
        runCatching { networkType() }.getOrDefault("unknown"),
        runCatching { cellularAuto() }.getOrDefault(false),
        runCatching { overCellular() }.getOrDefault(false),
        runCatching { wifiAuto() }.getOrDefault(false),
    )

    /** The MSS a direct TCP connection uses now; always null over a WebSocket. */
    val activeMss: Int? get() = mssChoice().mss

    /** 2.2.7: what the last connection made by this object chose; null before the first. */
    @Volatile var lastMssChoice: MssChoice? = null
        private set

    /** 2.2.7: whether setsockopt took the last connection's MSS; null when none was asked for. */
    @Volatile private var lastMssSet: Boolean? = null

    /** 2.2.6 fallback: the next connection (after [reset]) uses [mss], for this object's life only. */
    fun useSmallPackets(mss: Int) {
        fallbackMss = SmallPackets.clamp(mss)
    }

    /** 2.2.7: the network changed, so the session's fallback ends: the next connection starts with normal packets. */
    fun clearSmallPackets() {
        fallbackMss = null
    }

    /**
     * 2.2.7: told (on the reading thread) when a read here has had no first byte for
     * [SmallPacketFallback.FIRST_BYTE_STALL_MS]; the app's session uses it to fall back early.
     */
    @Volatile var firstByteStallListener: (() -> Unit)? = null

    /** 2.2.6 diagnostics: bytes received over this object's direct TCP connections, and when the last one came. */
    val inbound = InboundCounter()
    /** 2.2.6 diagnostics: commands that ran out of time on this object's connections. */
    val stalls = java.util.concurrent.atomic.AtomicLong()
    /**
     * 2.2.6: commands running on the send lane ([execNow], and [execInput] bound to a connection).
     * The small-packets fallback waits for these before it resets the connection, so it never cuts
     * off a message or key press being sent.
     */
    private val sendsInFlight = java.util.concurrent.atomic.AtomicInteger()
    val sendsRunning: Int get() = sendsInFlight.get()
    internal fun beginSend() { sendsInFlight.incrementAndGet() }
    internal fun endSend() { sendsInFlight.decrementAndGet() }
    private inline fun <T> asSend(on: Boolean, block: () -> T): T {
        if (!on) return block()
        beginSend()
        try { return block() } finally { endSend() }
    }

    /** The TCP connect time of the last direct connection, for the connect line. */
    @Volatile private var lastTcpMs: Long? = null

    /**
     * The socket factory for the next connection: for direct TCP, an [MssSocketFactory] that sets
     * the small-packets MSS when on and always times the connect, logs the socket's facts and
     * counts bytes in; null over a WebSocket (SSHJ's default socket to the local bridge).
     */
    internal fun socketFactoryForNext(choice: MssChoice = mssChoice()): MssSocketFactory? {
        if (wsUrl != null) return null
        return MssSocketFactory(choice.mss, mssOps, {
            lastMssSet = it.applied
            com.billmongan.herdrdroid.diag.LinkStats.mss(diagServerId, it)
        }, { ms, f ->
            lastTcpMs = ms
            com.billmongan.herdrdroid.diag.LinkStats.socket(diagServerId, f)
        }, inbound)
    }

    private val lock = Mutex()
    // Keep bulk commands in order without holding the connection lock during remote I/O.
    private val bulkLock = Mutex()
    @Volatile private var client: SSHClient? = null
    internal val connectionIdentity: Any? get() = client
    @Volatile private var bridge: WsBridge? = null

    /**
     * 2.2.5: the algorithms the last key exchange settled on (names only), for the diagnostic log.
     * Recorded by an [net.schmizz.sshj.transport.verification.AlgorithmsVerifier] that always
     * accepts: it never changes what is negotiated, and the host key is still checked by
     * [PinnedVerifier].
     */
    @Volatile var negotiated: String? = null
        private set
    /** "tcp" or "ws": how this connection reaches the computer. */
    val transportName: String get() = if (wsUrl != null) "ws" else "tcp"

    private fun config() = DefaultConfig().apply {
        keyAlgorithms = keyAlgorithms.map { if (it.name == "ecdsa-sha2-nistp256") neutralEcdsa(it) else it }
    }

    /** Makes each connection's client: the real one, or a test's stand-in. */
    internal var newClient: (DefaultConfig) -> SSHClient = { SSHClient(it) }

    /**
     * Reset and close both detach an opening client and bridge. Connection publication uses the
     * same gate, so an invalidated attempt is never installed or counted.
     */
    private val gate = Any()
    @Volatile private var closedForGood = false
    private var opening: SSHClient? = null
    private var openingBridge: WsBridge? = null

    /** A reset or close detaches this attempt before disconnecting its resources. */
    private fun verifyOpening(c: SSHClient) {
        if (closedForGood) throw LinkDown(IllegalStateException("The connection was closed"))
        if (opening !== c) throw ConnectionChanged()
    }

    private fun open(): SSHClient {
        val verifier = PinnedVerifier(pinnedFingerprint)
        val (c, old, oldBridge) = synchronized(gate) {
            if (closedForGood) throw LinkDown(IllegalStateException("The connection was closed"))
            client?.let { if (it.isConnected && it.isAuthenticated && bridge?.isOpen != false) return it }
            val next = newClient(config())
            val stale = client
            val staleBridge = bridge
            stale?.let { SshCounts.closed(diagServerId, it) }
            client = null
            bridge = null
            opening = next
            openingBridge = null
            Triple(next, stale, staleBridge)
        }
        try {
            old?.runCatching { disconnect() }
            oldBridge?.close()
            synchronized(gate) { verifyOpening(c) }
            return openWith(c, verifier)
        } finally {
            synchronized(gate) {
                if (opening === c) { opening = null; openingBridge = null }
            }
        }
    }

    private fun openWith(c: SSHClient, verifier: PinnedVerifier): SSHClient {
        var b: WsBridge? = null
        c.connectTimeout = 8_000
        c.timeout = 15_000
        c.addHostKeyVerifier(verifier)
        var kexinitAt: Long? = null
        c.addAlgorithmsVerifier { a ->
            kexinitAt = System.currentTimeMillis()
            negotiated = "kex=${a.keyExchangeAlgorithm} hostkey=${a.signatureAlgorithm} cipher=${a.client2ServerCipherAlgorithm} mac=${a.client2ServerMACAlgorithm} comp=${a.client2ServerCompressionAlgorithm}"
            true
        }
        lastTcpMs = null
        lastMssSet = null
        val choice = mssChoice()
        lastMssChoice = choice
        var t0 = System.currentTimeMillis()
        var connectedAt: Long? = null
        try {
            if (wsUrl != null) {
                val opened = WsBridge.open(wsUrl)
                b = opened
                synchronized(gate) {
                    verifyOpening(c)
                    openingBridge = opened
                }
                t0 = System.currentTimeMillis()
                DiagLog.log("ssh", "sshj connecting to ${opened.host}:${opened.port} (the WebSocket bridge)")
                c.connect(opened.host, opened.port)
            } else {
                socketFactoryForNext(choice)?.let { c.socketFactory = it }
                DiagLog.log("ssh", "sshj connecting over TCP" + (choice.mss?.let { " (small packets, MSS $it, ${choice.reason})" } ?: ""))
                c.connect(host, port)
            }
            connectedAt = System.currentTimeMillis()
            synchronized(gate) { verifyOpening(c) }
            DiagLog.log("ssh", "key exchange done; server says \"${c.transport.serverVersion}\"; host key ${verifier.seen}")
            DiagLog.log("ssh", "negotiated over $transportName: ${negotiated ?: "unknown"}")
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            b?.close()
            if (e is ConnectionChanged || e is LinkDown) throw e
            synchronized(gate) { verifyOpening(c) }
            val seen = verifier.seen
            DiagLog.log("ssh", "connect failed: ${DiagLog.describe(e)}; host key seen: ${seen ?: "none"}")
            when {
                seen != null && pinnedFingerprint == null -> throw UnconfirmedHostKey(seen)
                seen != null && pinnedFingerprint != seen -> throw HostKeyChanged(pinnedFingerprint!!, seen)
                else -> throw LinkDown(e)
            }
        }
        try {
            c.auth(user, methods(c))
            synchronized(gate) { verifyOpening(c) }
            DiagLog.log("ssh", "signed in")
            // 2.2.6: each stage's time, after the TCP connect (or from the bridge being ready).
            val tcp = lastTcpMs
            val tcpDone = t0 + (tcp ?: 0L)
            val hostKeyAt = verifier.at
            val kexinit = kexinitAt
            val connected = connectedAt
            com.billmongan.herdrdroid.diag.LinkStats.connect(diagServerId, com.billmongan.herdrdroid.diag.LinkStats.connectLine(
                diagServerId, transportName,
                com.billmongan.herdrdroid.diag.LinkStats.ConnectTimes(
                    tcpMs = tcp,
                    identKexinitMs = kexinit?.let { it - tcpDone },
                    kexReplyMs = if (kexinit != null && hostKeyAt != null) hostKeyAt - kexinit else null,
                    newKeysMs = if (hostKeyAt != null && connected != null) connected - hostKeyAt else null,
                    authMs = connected?.let { System.currentTimeMillis() - it },
                ),
                negotiated, choice.net, com.billmongan.herdrdroid.diag.NetworkWatch.changes, KEEPALIVE_SECONDS,
                choice.reason, choice.mss, if (choice.mss == null) null else lastMssSet == true,
            ))
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            b?.close()
            if (e is ConnectionChanged || e is LinkDown) throw e
            synchronized(gate) { verifyOpening(c) }
            when (e) {
                is UserAuthException -> {
                    DiagLog.log("ssh", "sign-in refused: ${DiagLog.describe(e)}")
                    throw AuthRejected(e.message ?: "Sign-in was refused")
                }
                is AuthRejected -> throw e
                else -> throw LinkDown(e)
            }
        }
        try {
            c.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
            synchronized(gate) {
                verifyOpening(c)
                bridge = b
                client = c
                opening = null
                openingBridge = null
                SshCounts.opened(diagServerId, c)
            }
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            b?.close()
            throw e
        }
        DiagLog.log("ssh", "connections open to this server: ${SshCounts.openNow(diagServerId)}")
        return c
    }

    /** The key first when this server uses one, then the password when one is known. */
    private fun methods(c: SSHClient): List<AuthMethod> {
        val list = mutableListOf<AuthMethod>()
        when (val k = credentials.key) {
            is KeySource.Keystore -> keystoreKey(k.alias)?.let { list += AuthPublickey(it) }
            is KeySource.Imported -> {
                val provider = runCatching {
                    c.loadKeys(k.pem, null as String?, k.passphrase?.let { oneOff(it) })
                }.getOrElse { throw AuthRejected("The imported key couldn't be read: ${it.message}") }
                list += AuthPublickey(provider)
            }
            null -> {}
        }
        // A WebSocket server signs in with the key only; no password is ever offered to it.
        if (wsUrl == null) credentials.password?.let { list += AuthPassword(oneOff(it)) }
        if (list.isEmpty()) {
            throw AuthRejected(if (wsUrl != null) "This server signs in with a key only, and no key is set up for it." else "No key or password is set up for this server.")
        }
        return list
    }

    /**
     * Runs [command] on a channel of its own (1.3.0, from a code review: before, stdout was read to
     * the end, then stderr, and only then was the timeout applied, so a command that kept writing
     * never timed out and held the connection lock, and a full stderr could stall it).
     *
     * Both streams are read at once, each up to its cap, and the channel is closed when
     * [timeoutSeconds] pass or stdout passes [maxOut] bytes ([CommandTimeout], [OutputTooLarge]).
     * A failure before the command reaches the computer (a dropped connection, a session that won't
     * open) gets one fresh retry; one after it started is reported and never retried, so a message
     * or a key press is never sent twice.
     */
    private fun runOn(c: SSHClient, command: String, input: ByteArray?, timeoutSeconds: Long, maxOut: Int, label: String? = null, trace: SendTrace? = null): Triple<Int, ByteArray, ByteArray> {
        // 2.2.6: bytes and timings of each read, logged for a labelled one (list-read, log-read, probe).
        val meter = com.billmongan.herdrdroid.diag.ReadMeter()
        var exit: Int? = null
        var timedOut = false
        try {
            return runMetered(c, command, input, timeoutSeconds, maxOut, meter, trace, label).also { exit = it.first }
        } catch (e: CommandTimeout) {
            timedOut = true
            stalls.incrementAndGet()
            throw e
        } finally {
            if (label != null) runCatching { com.billmongan.herdrdroid.diag.LinkStats.read(diagServerId, meter.finish(label, exit, timedOut)) }
        }
    }

    private fun runMetered(c: SSHClient, command: String, input: ByteArray?, timeoutSeconds: Long, maxOut: Int, meter: com.billmongan.herdrdroid.diag.ReadMeter, trace: SendTrace?, label: String? = null): Triple<Int, ByteArray, ByteArray> {
        val session = c.startSession()
        meter.channelOpen()
        // A failure from exec itself may come after the computer accepted the command (1.3.2,
        // from a code review), so it is reported, never retried; only a session that wouldn't
        // open is retried.
        // From here the command may reach the computer, even if exec then fails.
        trace?.started = true
        val cmd = try { session.exec(command) } catch (e: Exception) { runCatching { session.close() }; throw AfterStart(e) }
        try {
            val began = System.currentTimeMillis()
            val beganNanos = System.nanoTime()
            val budgetNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds)
            val out = Capped(maxOut, meter)
            val err = Capped(MAX_ERR, meter)
            val t1 = kotlin.concurrent.thread(isDaemon = true, name = "ssh-out") { out.drain(cmd.inputStream) }
            val t2 = kotlin.concurrent.thread(isDaemon = true, name = "ssh-err") { err.drain(cmd.errorStream) }
            val uploadFailure = java.util.concurrent.atomic.AtomicReference<Exception?>()
            // The remote side can emit output before accepting input. Let the streams move together
            // and close the same channel if the deadline expires while its writer is blocked.
            val upload = input?.let { bytes ->
                kotlin.concurrent.thread(isDaemon = true, name = "ssh-in") {
                    try {
                        cmd.outputStream.use { it.write(bytes); it.flush() }
                    } catch (e: Exception) {
                        uploadFailure.set(e)
                    }
                }
            }
            val workers = listOfNotNull(t1, t2, upload)
            // Only pane-list and chat-log reads report a first-byte stall.
            val watch = firstByteStallListener?.takeIf { trace == null && label in FIRST_BYTE_WATCHED }
                ?.let { l -> FirstByteWatch(SmallPacketFallback.FIRST_BYTE_STALL_MS, began, l) }
            while (!out.over && uploadFailure.get() == null && System.nanoTime() - beganNanos < budgetNanos) {
                val active = workers.firstOrNull(Thread::isAlive) ?: break
                active.join(100)
                watch?.tick(System.currentTimeMillis(), meter.bytes())
            }
            if (out.over || uploadFailure.get() != null || workers.any(Thread::isAlive)) {
                val stalledUpload = upload?.isAlive == true && uploadFailure.get() == null
                runCatching { session.close() }
                workers.forEach { it.join(2_000) }
                if (out.over) throw OutputTooLarge(maxOut)
                if (stalledUpload) throw CommandTimeout(timeoutSeconds)
                uploadFailure.get()?.let { throw it }
                // Closing can finish the output streams after a completed command's late exit status.
                val exit = cmd.exitStatus
                if (workers.none(Thread::isAlive) && exit != null) return Triple(exit, out.bytes(), err.bytes())
                throw CommandTimeout(timeoutSeconds)
            }
            cmd.join(5, TimeUnit.SECONDS)
            return Triple(cmd.exitStatus ?: -1, out.bytes(), err.bytes())
        } catch (e: CommandTimeout) {
            throw e
        } catch (e: OutputTooLarge) {
            throw e
        } catch (e: Exception) {
            throw AfterStart(e)
        } finally {
            runCatching { session.close() }
        }
    }

    /** One command, retried once on a fresh connection only if it never reached the computer. */
    private suspend fun runRetrying(
        command: String, input: ByteArray?, timeoutSeconds: Long, maxOut: Int, expectedConnection: Any? = null,
        permit: com.billmongan.herdrdroid.herdr.WritePermit? = null,
    ): Triple<Int, ByteArray, ByteArray> {
        var attempt = 0
        while (true) {
            var connection: SSHClient? = null
            try {
                val opened = lock.withLock {
                    val c = if (expectedConnection == null) open()
                    else requireCurrentConnection(expectedConnection, client?.takeIf {
                        it.isConnected && it.isAuthenticated && bridge?.isOpen != false
                    })
                    // The last check before the command starts: a lock, the end of hands-free or a
                    // confirmed close revokes the permit while this send waited (2.0.1 finding 4).
                    permit.admit()
                    c
                }
                connection = opened
                val ctx = kotlin.coroutines.coroutineContext
                return runOn(opened, command, input, timeoutSeconds, maxOut, ctx[com.billmongan.herdrdroid.diag.ReadLabel]?.label,
                    if (ctx[WriteCommand] != null) ctx[SendTrace] else null)
            } catch (e: UnconfirmedHostKey) {
                throw e
            } catch (e: HostKeyChanged) {
                throw e
            } catch (e: AuthRejected) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: CommandTimeout) {
                throw e
            } catch (e: OutputTooLarge) {
                throw e
            } catch (e: ConnectionChanged) {
                throw e
            } catch (e: com.billmongan.herdrdroid.herdr.WriteRevoked) {
                throw e
            } catch (e: AfterStart) {
                // It reached the computer: the connection is suspect, but the command is not sent again.
                discard(connection)
                throw LinkDown(e.cause ?: e)
            } catch (e: Exception) {
                discard(connection)
                // A permitted write is never replayed: the permit is claimed once.
                if (expectedConnection != null || permit != null || ++attempt > 1) throw (e as? LinkDown ?: LinkDown(e))
            }
        }
    }

    /** Another channel may already have replaced a failed connection. Leave the replacement alone. */
    private suspend fun discard(connection: SSHClient?) {
        if (connection == null) return // open() already handled its own failure.
        lock.withLock {
            val staleBridge = synchronized(gate) {
                if (client !== connection) return@withLock
                client = null
                SshCounts.closed(diagServerId, connection)
                bridge.also { bridge = null }
            }
            connection.runCatching { disconnect() }
            staleBridge?.close()
        }
    }

    /** A command that ran out of time or printed too much: a failed command (exit -2), not a lost connection. */
    private inline fun capped(block: () -> ExecResult): ExecResult = try { block() } catch (e: CommandTimeout) {
        ExecResult(-2, "", e.message.orEmpty())
    } catch (e: OutputTooLarge) {
        ExecResult(-2, "", e.message.orEmpty())
    }

    suspend fun exec(command: String, timeoutSeconds: Long = 30): ExecResult = withContext(Dispatchers.IO) {
        capped {
            val (exit, out, err) = bulkLock.withLock { runRetrying(command, null, timeoutSeconds, MAX_OUT) }
            ExecResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        }
    }

    /**
     * Runs a short command on its own channel without waiting for other commands: the send lane.
     * Only opening the connection takes the lock, so a message goes out while a read is running.
     */
    suspend fun execNow(command: String, timeoutSeconds: Long = 30, expectedConnection: Any? = null): ExecResult =
        execNow(command, timeoutSeconds, expectedConnection, null)

    /** The send lane with a write permit, claimed once the connection is held (2.0.1 finding 4). */
    internal suspend fun execNow(
        command: String, timeoutSeconds: Long, expectedConnection: Any?,
        permit: com.billmongan.herdrdroid.herdr.WritePermit?,
    ): ExecResult = withContext(Dispatchers.IO) {
        // Fails fast on a permit already revoked; the binding check is the claim under the lock.
        if (permit?.revoked == true) throw com.billmongan.herdrdroid.herdr.WriteRevoked()
        asSend(true) {
            capped {
                val (exit, out, err) = runRetrying(command, null, timeoutSeconds, MAX_OUT, expectedConnection, permit)
                ExecResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
            }
        }
    }

    /**
     * Runs a command that keeps printing (the transcript reader's follow mode), handing each line
     * to [onLine] as it arrives, on its own channel. Ends when the command exits; cancelling the
     * calling coroutine closes the channel, which ends the command on the computer too.
     */
    suspend fun execStream(command: String, input: ByteArray, onLine: suspend (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val c = lock.withLock { open() }
        val session = c.startSession()
        try {
            val cmd = session.exec(command)
            cmd.outputStream.use { it.write(input); it.flush() }
            // Drain stderr too, so a reader that writes warnings can't fill that pipe and stall (1.3.0).
            kotlin.concurrent.thread(isDaemon = true, name = "ssh-follow-err") { Capped(MAX_ERR).drain(cmd.errorStream) }
            readFollowLines(cmd.inputStream, onLine)
            cmd.join(5, TimeUnit.SECONDS)
            cmd.exitStatus ?: -1
        } finally {
            runCatching { session.close() }
        }
    }

    /** Runs a command and returns its standard output as bytes (for files fetched from the computer), up to [maxBytes]. */
    suspend fun execBytes(
        command: String,
        timeoutSeconds: Long = 120,
        maxBytes: Int = MAX_BYTES,
        expectedConnection: Any? = null,
    ): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        try {
            val (exit, out, _) = bulkLock.withLock { runRetrying(command, null, timeoutSeconds, maxBytes, expectedConnection) }
            exit to out
        } catch (e: CommandTimeout) {
            -2 to ByteArray(0)
        } catch (e: OutputTooLarge) {
            -2 to ByteArray(0)
        }
    }

    /**
     * Runs a command with [input] on its standard input: how the transcript reader script and an
     * uploaded file reach the computer without anything being installed there.
     */
    suspend fun execInput(command: String, input: ByteArray, timeoutSeconds: Long = 60, expectedConnection: Any? = null): ExecResult = withContext(Dispatchers.IO) {
        asSend(expectedConnection != null) {
            capped {
                val (exit, out, err) = bulkLock.withLock { runRetrying(command, input, timeoutSeconds, MAX_OUT, expectedConnection) }
                ExecResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
            }
        }
    }

    /**
     * Forwards a port on this phone's loopback to [remoteHost]:[remotePort] as seen from the
     * computer, over this SSH connection. The connection arrives at the computer from 127.0.0.1,
     * so a service that only listens there (Hermes's gateway) can be reached without exposing it.
     */
    suspend fun forward(remoteHost: String, remotePort: Int): Forward = withContext(Dispatchers.IO) {
        val c = lock.withLock { open() }
        val forward = startLoopbackForward { socket ->
            c.newLocalPortForwarder(Parameters("127.0.0.1", socket.localPort, remoteHost, remotePort), socket).listen()
        }
        if (client !== c) {
            forward.close()
            throw ConnectionChanged()
        }
        forward
    }

    /** Drops the published connection and any open in flight. The next command can reconnect. */
    fun reset() = dropConnections(permanent = false)

    /** True when this connection goes over a WebSocket. */
    val overWebSocket: Boolean get() = wsUrl != null

    /** Closes this connection for good, including an open in flight. */
    fun close() = dropConnections(permanent = true)

    private fun dropConnections(permanent: Boolean) {
        val (clientsToClose, bridgesToClose) = synchronized(gate) {
            if (permanent) closedForGood = true
            val clients = listOfNotNull(client, opening)
            val bridges = listOfNotNull(bridge, openingBridge)
            client?.let { SshCounts.closed(diagServerId, it) }
            client = null
            bridge = null
            opening = null
            openingBridge = null
            clients to bridges
        }
        // Another open may start after a reset. Close only the resources detached above.
        clientsToClose.forEach { c -> runCatching { c.disconnect() } }
        bridgesToClose.forEach { it.close() }
    }

    /** Opens (or keeps) the connection and runs nothing on it: the connection tests' seam. */
    internal suspend fun openOnly(): Any = withContext(Dispatchers.IO) { lock.withLock { open() } }

    /** True once [close] ran. */
    internal val isClosed: Boolean get() = closedForGood
}

/**
 * A host key fingerprint as typed or pasted ("SHA256:abc…", with or without the prefix, or a whole
 * ssh-keygen line), in the app's form "SHA256:<43 base64 characters>"; null when it isn't one.
 */
fun normalizeFingerprint(text: String): String? {
    val t = text.trim()
    val after = if (t.contains("SHA256:")) t.substringAfter("SHA256:") else t
    val m = Regex("""^([A-Za-z0-9+/]{43})=?(?=$|[^A-Za-z0-9+/=])""").find(after) ?: return null
    return "SHA256:" + m.groupValues[1]
}


internal class ConnectionChanged : IllegalStateException("The SSH connection changed.")

internal fun <T : Any> requireCurrentConnection(expected: Any, current: T?): T {
    if (current == null || current !== expected) throw ConnectionChanged()
    return current
}

/** 2.2.7: the labelled reads the no-first-byte fallback watches (the reads Bill's stall was on). */
internal val FIRST_BYTE_WATCHED = setOf("list-read", "log-read")

/**
 * 2.2.8 (Bill: "I'm surprised by how many SSH connections HerdrDroid creates" and "be more robust
 * about managing and closing stale connections"): the SSH connections the app has open, per server,
 * with how many it ever opened and how many times a check reused one it already had. A connection
 * is counted from its sign-in until it is disconnected (replaced, reset, closed or dropped after a
 * failure). For the diagnostic log and the tests; memory only.
 */
internal object SshCounts {
    private val open = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Any>>()
    private val opened = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val reused = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun key(server: String?) = server ?: "?"

    fun opened(server: String?, connection: Any) {
        open.getOrPut(key(server)) { java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap()) } += connection
        opened.getOrPut(key(server)) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
    }
    fun closed(server: String?, connection: Any) { open[key(server)]?.remove(connection) }
    fun reused(server: String?) { reused.getOrPut(key(server)) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet() }
    fun openNow(server: String?): Int = open[key(server)]?.size ?: 0
    fun openedEver(server: String?): Int = opened[key(server)]?.get() ?: 0
    fun reusedEver(server: String?): Int = reused[key(server)]?.get() ?: 0
    /** Every server's open count, for the debug bundle. */
    fun summary(): String = open.entries.joinToString(" ") { (k, v) -> "${com.billmongan.herdrdroid.diag.LinkDiag.serverTag(k)}=${v.size}" }
    internal fun clear() { open.clear(); opened.clear(); reused.clear() }
}
