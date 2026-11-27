package com.billmongan.herdrdroid.ssh

import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFollowLoopback229Test {
    @Test
    fun cancellationClosesOnlyTheFollowChannelAfterDeliveringSplitUtf8() = runBlocking {
        val followClosed = CompletableDeferred<Unit>()
        val followOutput = CompletableDeferred<OutputStream>()
        val followConnection = CompletableDeferred<Any>()
        val siblingConnection = CompletableDeferred<Any>()
        val lines = Channel<String>(Channel.UNLIMITED)
        val multibyte = "🙂".toByteArray(Charsets.UTF_8)
        val hostKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = { user, _, _ -> user == "fixture" }
            commandFactory = { _, name ->
                object : Command {
                    private lateinit var output: OutputStream
                    private lateinit var exit: ExitCallback

                    override fun setInputStream(input: InputStream) = Unit
                    override fun setErrorStream(error: OutputStream) = Unit
                    override fun setOutputStream(out: OutputStream) { output = out }
                    override fun setExitCallback(callback: ExitCallback) { exit = callback }

                    override fun start(channel: ChannelSession, env: Environment) {
                        when (name) {
                            "follow-fixture" -> {
                                followConnection.complete(channel.session)
                                output.write("first\n".toByteArray() + multibyte.copyOfRange(0, 2))
                                output.flush()
                                followOutput.complete(output)
                            }
                            "sibling-fixture" -> {
                                siblingConnection.complete(channel.session)
                                output.write("sibling\n".toByteArray())
                                output.flush()
                                exit.onExit(0)
                            }
                            else -> exit.onExit(127)
                        }
                    }

                    override fun destroy(channel: ChannelSession) {
                        if (name == "follow-fixture") followClosed.complete(Unit)
                    }
                }
            }
        }
        server.use {
            server.start()
            val ssh = HerdrSsh(
                host = "127.0.0.1", port = server.port, user = "fixture",
                credentials = Credentials(null, "fixture"),
                pinnedFingerprint = sha256Fingerprint(hostKey.public), keystoreKey = { null },
                vpnAuto = { false }, networkType = { "test" },
                cellularAuto = { false }, overCellular = { false }, wifiAuto = { false },
            )
            try {
                withTimeout(15_000) {
                    val job = launch { ssh.execStream("follow-fixture", byteArrayOf()) { lines.send(it) } }
                    val output = followOutput.await()
                    assertEquals("first", lines.receive())
                    output.write(multibyte.copyOfRange(2, multibyte.size) + byteArrayOf(10) + multibyte.copyOfRange(0, 2))
                    output.flush()
                    assertEquals("🙂", lines.receive())
                    job.cancel()
                    withTimeout(3_000) {
                        job.join()
                        followClosed.await()
                    }
                    assertTrue(job.isCancelled)
                    assertTrue("Cancellation emitted an incomplete line", lines.tryReceive().isFailure)
                    assertEquals(ExecResult(0, "sibling\n", ""), ssh.exec("sibling-fixture", timeoutSeconds = 3))
                    assertSame(followConnection.await(), siblingConnection.await())
                }
            } finally {
                ssh.close()
            }
        }
    }
}
