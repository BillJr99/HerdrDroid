package com.billmongan.herdrdroid.ssh

import com.hierynomus.sshj.key.KeyAlgorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import java.net.InetSocketAddress
import java.net.ServerSocket
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

data class ExecResult(val exit: Int, val out: String, val err: String)

/** A command still running when its time was up; its channel was closed (1.3.0). */
class CommandTimeout(seconds: Long) : Exception("the command did not finish within $seconds s")
/** A command that printed more than the app takes; its channel was closed (1.3.0). */
class OutputTooLarge(max: Int) : Exception("the command printed more than ${max / (1024 * 1024)} MB")
/** A failure after the command reached the computer: reported, never retried. */
private class AfterStart(cause: Exception) : Exception(cause.message, cause)

/** The most a command's stdout may print (files fetched have their own, larger cap), and stderr. */
private const val MAX_OUT = 16 * 1024 * 1024
private const val MAX_BYTES = 64 * 1024 * 1024
private const val MAX_ERR = 256 * 1024

/** A stream read into memory up to [cap] bytes; [over] once more arrived. Stderr past its cap is dropped, not an error. */
private class Capped(private val cap: Int) {
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

    override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
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
) {
    private val lock = Mutex()
    // Keep bulk commands in order without holding the connection lock during remote I/O.
    private val bulkLock = Mutex()
    private var client: SSHClient? = null
    private var bridge: WsBridge? = null

    private fun config() = DefaultConfig().apply {
        keyAlgorithms = keyAlgorithms.map { if (it.name == "ecdsa-sha2-nistp256") neutralEcdsa(it) else it }
    }

    private fun open(): SSHClient {
        client?.let { if (it.isConnected && it.isAuthenticated && bridge?.isOpen != false) return it }
        client?.runCatching { disconnect() }
        client = null
        bridge?.close()
        bridge = null
        val verifier = PinnedVerifier(pinnedFingerprint)
        val c = SSHClient(config())
        c.connectTimeout = 8_000
        c.timeout = 15_000
        c.addHostKeyVerifier(verifier)
        try {
            if (wsUrl != null) {
                val b = WsBridge.open(wsUrl)
                bridge = b
                DiagLog.log("ssh", "sshj connecting to ${b.host}:${b.port} (the WebSocket bridge)")
                c.connect(b.host, b.port)
            } else {
                DiagLog.log("ssh", "sshj connecting over TCP")
                c.connect(host, port)
            }
            DiagLog.log("ssh", "key exchange done; server says \"${c.transport.serverVersion}\"; host key ${verifier.seen}")
        } catch (e: Exception) {
            val seen = verifier.seen
            DiagLog.log("ssh", "connect failed: ${DiagLog.describe(e)}; host key seen: ${seen ?: "none"}")
            runCatching { c.disconnect() }
            bridge?.close()
            bridge = null
            when {
                seen != null && pinnedFingerprint == null -> throw UnconfirmedHostKey(seen)
                seen != null && pinnedFingerprint != seen -> throw HostKeyChanged(pinnedFingerprint!!, seen)
                else -> throw LinkDown(e)
            }
        }
        try {
            c.auth(user, methods(c))
            DiagLog.log("ssh", "signed in")
        } catch (e: UserAuthException) {
            DiagLog.log("ssh", "sign-in refused: ${DiagLog.describe(e)}")
            runCatching { c.disconnect() }
            bridge?.close(); bridge = null
            throw AuthRejected(e.message ?: "Sign-in was refused")
        } catch (e: AuthRejected) {
            runCatching { c.disconnect() }
            bridge?.close(); bridge = null
            throw e
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            bridge?.close(); bridge = null
            throw LinkDown(e)
        }
        c.connection.keepAlive.keepAliveInterval = 15
        client = c
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
    private fun runOn(c: SSHClient, command: String, input: ByteArray?, timeoutSeconds: Long, maxOut: Int): Triple<Int, ByteArray, ByteArray> {
        val session = c.startSession()
        // A failure from exec itself may come after the computer accepted the command (1.3.2,
        // from a code review), so it is reported, never retried; only a session that wouldn't
        // open is retried.
        val cmd = try { session.exec(command) } catch (e: Exception) { runCatching { session.close() }; throw AfterStart(e) }
        try {
            input?.let { b -> cmd.outputStream.use { it.write(b); it.flush() } }
            val out = Capped(maxOut)
            val err = Capped(MAX_ERR)
            val t1 = kotlin.concurrent.thread(isDaemon = true, name = "ssh-out") { out.drain(cmd.inputStream) }
            val t2 = kotlin.concurrent.thread(isDaemon = true, name = "ssh-err") { err.drain(cmd.errorStream) }
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            while ((t1.isAlive || t2.isAlive) && System.currentTimeMillis() < deadline && !out.over) {
                t1.join(100); if (!t1.isAlive) t2.join(100)
            }
            if (out.over || t1.isAlive || t2.isAlive) {
                runCatching { session.close() }
                t1.join(2_000); t2.join(2_000)
                if (out.over) throw OutputTooLarge(maxOut)
                // Closing often lets both readers finish (a stream that was slow to close): then the
                // command's own result stands (1.3.2, from a code review).
                val exit = cmd.exitStatus
                if (!t1.isAlive && !t2.isAlive && exit != null) return Triple(exit, out.bytes(), err.bytes())
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
    private suspend fun runRetrying(command: String, input: ByteArray?, timeoutSeconds: Long, maxOut: Int): Triple<Int, ByteArray, ByteArray> {
        var attempt = 0
        while (true) {
            var connection: SSHClient? = null
            try {
                val opened = lock.withLock { open() }
                connection = opened
                return runOn(opened, command, input, timeoutSeconds, maxOut)
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
            } catch (e: AfterStart) {
                // It reached the computer: the connection is suspect, but the command is not sent again.
                discard(connection)
                throw LinkDown(e.cause ?: e)
            } catch (e: Exception) {
                discard(connection)
                if (++attempt > 1) throw (e as? LinkDown ?: LinkDown(e))
            }
        }
    }

    /** Another channel may already have replaced a failed connection. Leave the replacement alone. */
    private suspend fun discard(connection: SSHClient?) {
        if (connection == null) return // open() already handled its own failure.
        lock.withLock {
            if (client === connection) {
                connection.runCatching { disconnect() }
                client = null
            }
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
    suspend fun execNow(command: String, timeoutSeconds: Long = 30): ExecResult = withContext(Dispatchers.IO) {
        capped {
            val (exit, out, err) = runRetrying(command, null, timeoutSeconds, MAX_OUT)
            ExecResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
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
        val job = coroutineContext[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { runCatching { session.close() } }
        try {
            val cmd = session.exec(command)
            cmd.outputStream.use { it.write(input); it.flush() }
            // Drain stderr too, so a reader that writes warnings can't fill that pipe and stall (1.3.0).
            kotlin.concurrent.thread(isDaemon = true, name = "ssh-follow-err") { Capped(MAX_ERR).drain(cmd.errorStream) }
            val reader = cmd.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) onLine(line)
            }
            cmd.join(5, TimeUnit.SECONDS)
            cmd.exitStatus ?: -1
        } finally {
            handle?.dispose()
            runCatching { session.close() }
        }
    }

    /** Runs a command and returns its standard output as bytes (for files fetched from the computer), up to [maxBytes]. */
    suspend fun execBytes(command: String, timeoutSeconds: Long = 120, maxBytes: Int = MAX_BYTES): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        try {
            val (exit, out, _) = bulkLock.withLock { runRetrying(command, null, timeoutSeconds, maxBytes) }
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
    suspend fun execInput(command: String, input: ByteArray, timeoutSeconds: Long = 60): ExecResult = withContext(Dispatchers.IO) {
        capped {
            val (exit, out, err) = bulkLock.withLock { runRetrying(command, input, timeoutSeconds, MAX_OUT) }
            ExecResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        }
    }

    /**
     * Forwards a port on this phone's loopback to [remoteHost]:[remotePort] as seen from the
     * computer, over this SSH connection. The connection arrives at the computer from 127.0.0.1,
     * so a service that only listens there (Hermes's gateway) can be reached without exposing it.
     */
    suspend fun forward(remoteHost: String, remotePort: Int): Forward = withContext(Dispatchers.IO) {
        val c = lock.withLock { open() }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", 0))
        val fwd = c.newLocalPortForwarder(Parameters("127.0.0.1", ss.localPort, remoteHost, remotePort), ss)
        val t = Thread({ runCatching { fwd.listen() } }, "herdrdroid-forward-${ss.localPort}")
        t.isDaemon = true
        t.start()
        Forward(ss.localPort) {
            runCatching { fwd.close() }
            runCatching { ss.close() }
        }
    }

    /** Drops the socket so the next command opens a fresh one, without waiting on a dead connection. */
    fun reset() {
        client?.runCatching { disconnect() }
        client = null
        bridge?.close()
        bridge = null
    }

    /** True when this connection goes over a WebSocket. */
    val overWebSocket: Boolean get() = wsUrl != null

    fun close() = reset()
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
