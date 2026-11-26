package com.billmongan.herdrdroid.ssh

import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.method.AuthMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SshUploadDeadline228Test {
    @Test fun aStalledUploadUsesTheCommandDeadlineAndReleasesItsLane() = runBlocking {
        val closed = CountDownLatch(1)
        val wrote = AtomicInteger()
        val session = fakeSession(
            input = sink {
                wrote.incrementAndGet()
                closed.await(3, TimeUnit.SECONDS)
                throw IOException("fixture receiver did not accept input")
            },
            close = closed::countDown,
            exit = null,
        )
        val ssh = ssh(session, fakeSession(sink {}))
        try {
            val result = ssh.execInput("fixture", byteArrayOf(1), timeoutSeconds = 1)
            assertEquals(-2, result.exit)
            assertTrue(result.err, result.err.startsWith(CommandTimeout.PREFIX))
            assertEquals(1, wrote.get())
            assertEquals(0L, closed.count)
            assertEquals(0, ssh.execInput("next fixture", byteArrayOf(2)).exit)
        } finally {
            ssh.close()
        }
    }

    @Test fun aPeerCanWriteToStderrBeforeItAcceptsTheUpload() = runBlocking {
        val stderrRead = CountDownLatch(1)
        val stderr = object : InputStream() {
            override fun read(): Int {
                stderrRead.countDown()
                return -1
            }
        }
        val session = fakeSession(sink {
            if (!stderrRead.await(3, TimeUnit.SECONDS)) throw IOException("fixture waiting for stderr drain")
        }, stderr = stderr)
        val ssh = ssh(session)
        try {
            assertEquals(0, ssh.execInput("fixture", byteArrayOf(3), timeoutSeconds = 1).exit)
        } finally {
            ssh.close()
        }
    }

    private fun sink(writeBytes: () -> Unit): OutputStream = object : OutputStream() {
        override fun write(b: Int) = writeBytes()
    }

    private fun ssh(vararg sessions: Session): HerdrSsh {
        val count = AtomicInteger()
        return HerdrSsh("127.0.0.1", 1, "fixture", Credentials(null, "fixture"), null, { null }).apply {
            newClient = { cfg ->
                object : SSHClient(cfg) {
                    override fun connect(hostname: String?, port: Int) {}
                    override fun auth(username: String?, methods: Iterable<AuthMethod>?) {}
                    override fun isConnected() = true
                    override fun isAuthenticated() = true
                    override fun startSession(): Session = sessions[count.getAndIncrement()]
                    override fun disconnect() {}
                }
            }
        }
    }

    private fun fakeSession(
        input: OutputStream,
        stderr: InputStream = ByteArrayInputStream(byteArrayOf()),
        close: () -> Unit = {},
        exit: Int? = 0,
    ): Session {
        val command = proxy(Session.Command::class.java) { method ->
            when (method) {
                "getOutputStream" -> input
                "getInputStream" -> ByteArrayInputStream(byteArrayOf())
                "getErrorStream" -> stderr
                "getExitStatus" -> exit
                "join" -> null
                else -> error("Unexpected command call $method")
            }
        }
        return proxy(Session::class.java) { method ->
            when (method) {
                "exec" -> command
                "close" -> close().let { null }
                else -> error("Unexpected session call $method")
            }
        }
    }

    private fun <T> proxy(type: Class<T>, handle: (String) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> handle(method.name) })
}
