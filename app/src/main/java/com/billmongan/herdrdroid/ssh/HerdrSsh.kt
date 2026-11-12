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

/** One SSH connection, reused for every herdr command, one command at a time. */
class HerdrSsh(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val credentials: Credentials,
    private val pinnedFingerprint: String?,
    private val keystoreKey: (String) -> KeyProvider?,
) {
    private val lock = Mutex()
    private var client: SSHClient? = null

    private fun config() = DefaultConfig().apply {
        keyAlgorithms = keyAlgorithms.map { if (it.name == "ecdsa-sha2-nistp256") neutralEcdsa(it) else it }
    }

    private fun open(): SSHClient {
        client?.let { if (it.isConnected && it.isAuthenticated) return it }
        client?.runCatching { disconnect() }
        client = null
        val verifier = PinnedVerifier(pinnedFingerprint)
        val c = SSHClient(config())
        c.connectTimeout = 8_000
        c.timeout = 15_000
        c.addHostKeyVerifier(verifier)
        try {
            c.connect(host, port)
        } catch (e: Exception) {
            val seen = verifier.seen
            runCatching { c.disconnect() }
            when {
                seen != null && pinnedFingerprint == null -> throw UnconfirmedHostKey(seen)
                seen != null && pinnedFingerprint != seen -> throw HostKeyChanged(pinnedFingerprint!!, seen)
                else -> throw LinkDown(e)
            }
        }
        try {
            c.auth(user, methods(c))
        } catch (e: UserAuthException) {
            runCatching { c.disconnect() }
            throw AuthRejected(e.message ?: "Sign-in was refused")
        } catch (e: AuthRejected) {
            runCatching { c.disconnect() }
            throw e
        } catch (e: Exception) {
            runCatching { c.disconnect() }
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
        credentials.password?.let { list += AuthPassword(oneOff(it)) }
        if (list.isEmpty()) throw AuthRejected("No key or password is set up for this server.")
        return list
    }

    suspend fun exec(command: String, timeoutSeconds: Long = 20): ExecResult = withContext(Dispatchers.IO) {
        lock.withLock {
            var attempt = 0
            while (true) {
                try {
                    val c = open()
                    c.startSession().use { session ->
                        val cmd = session.exec(command)
                        val out = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
                        val err = cmd.errorStream.readBytes().toString(Charsets.UTF_8)
                        cmd.join(timeoutSeconds, TimeUnit.SECONDS)
                        return@withLock ExecResult(cmd.exitStatus ?: -1, out, err)
                    }
                } catch (e: UnconfirmedHostKey) {
                    throw e
                } catch (e: HostKeyChanged) {
                    throw e
                } catch (e: AuthRejected) {
                    throw e
                } catch (e: Exception) {
                    // A dropped connection gets one fresh retry; after that it is reported as a link failure.
                    client?.runCatching { disconnect() }
                    client = null
                    if (++attempt > 1) throw (e as? LinkDown ?: LinkDown(e))
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ExecResult(-1, "", "")
        }
    }

    /** Runs a command and returns its standard output as bytes (for files fetched from the computer). */
    suspend fun execBytes(command: String, timeoutSeconds: Long = 120): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        lock.withLock {
            var attempt = 0
            while (true) {
                try {
                    val c = open()
                    c.startSession().use { session ->
                        val cmd = session.exec(command)
                        val out = cmd.inputStream.readBytes()
                        cmd.errorStream.readBytes()
                        cmd.join(timeoutSeconds, TimeUnit.SECONDS)
                        return@withLock (cmd.exitStatus ?: -1) to out
                    }
                } catch (e: UnconfirmedHostKey) {
                    throw e
                } catch (e: HostKeyChanged) {
                    throw e
                } catch (e: AuthRejected) {
                    throw e
                } catch (e: Exception) {
                    client?.runCatching { disconnect() }
                    client = null
                    if (++attempt > 1) throw (e as? LinkDown ?: LinkDown(e))
                }
            }
            @Suppress("UNREACHABLE_CODE")
            -1 to ByteArray(0)
        }
    }

    /**
     * Runs a command with [input] on its standard input: how the transcript reader script and an
     * uploaded file reach the computer without anything being installed there.
     */
    suspend fun execInput(command: String, input: ByteArray, timeoutSeconds: Long = 30): ExecResult = withContext(Dispatchers.IO) {
        lock.withLock {
            var attempt = 0
            while (true) {
                try {
                    val c = open()
                    c.startSession().use { session ->
                        val cmd = session.exec(command)
                        cmd.outputStream.use { it.write(input); it.flush() }
                        val out = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
                        val err = cmd.errorStream.readBytes().toString(Charsets.UTF_8)
                        cmd.join(timeoutSeconds, TimeUnit.SECONDS)
                        return@withLock ExecResult(cmd.exitStatus ?: -1, out, err)
                    }
                } catch (e: UnconfirmedHostKey) {
                    throw e
                } catch (e: HostKeyChanged) {
                    throw e
                } catch (e: AuthRejected) {
                    throw e
                } catch (e: Exception) {
                    client?.runCatching { disconnect() }
                    client = null
                    if (++attempt > 1) throw (e as? LinkDown ?: LinkDown(e))
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ExecResult(-1, "", "")
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
    }

    fun close() = reset()
}
