package com.billmongan.herdrdroid.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The server offered a host key the user has not confirmed yet. */
class UnconfirmedHostKey(val fingerprint: String) : Exception("New host key $fingerprint")

/** The server's host key no longer matches the one the user confirmed. */
class HostKeyChanged(val expected: String, val actual: String) :
    Exception("The host key changed. Expected $expected, got $actual")

data class ExecResult(val exit: Int, val out: String, val err: String)

fun sha256Fingerprint(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
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

/** One SSH connection, reused for every herdr command, one command at a time. */
class HerdrSsh(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    private val pinnedFingerprint: String?,
) {
    private val lock = Mutex()
    private var client: SSHClient? = null

    private fun open(): SSHClient {
        client?.let { if (it.isConnected && it.isAuthenticated) return it }
        client?.runCatching { disconnect() }
        val verifier = PinnedVerifier(pinnedFingerprint)
        val c = SSHClient(DefaultConfig())
        c.connectTimeout = 10_000
        c.timeout = 30_000
        c.addHostKeyVerifier(verifier)
        try {
            c.connect(host, port)
        } catch (e: Exception) {
            val seen = verifier.seen
            runCatching { c.disconnect() }
            when {
                seen != null && pinnedFingerprint == null -> throw UnconfirmedHostKey(seen)
                seen != null && pinnedFingerprint != seen -> throw HostKeyChanged(pinnedFingerprint!!, seen)
                else -> throw e
            }
        }
        c.authPassword(user, password)
        c.connection.keepAlive.keepAliveInterval = 20
        client = c
        return c
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
                } catch (e: Exception) {
                    // A dropped connection gets one fresh retry; anything else is reported.
                    client?.runCatching { disconnect() }
                    client = null
                    if (++attempt > 1) throw e
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ExecResult(-1, "", "")
        }
    }

    fun close() {
        client?.runCatching { disconnect() }
        client = null
    }
}
