package com.billmongan.herdrdroid.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** The port can be advertised when the listener reaches accept, without dialing the remote service. */
internal suspend fun startLoopbackForward(listen: (ServerSocket) -> Unit): Forward {
    val ready = CompletableDeferred<Unit>()
    val socket = object : ServerSocket() {
        override fun accept(): Socket {
            ready.complete(Unit)
            return super.accept()
        }
    }
    var worker: Thread? = null
    fun stop() {
        worker?.interrupt()
        runCatching { socket.close() }
    }
    try {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", 0))
        val port = socket.localPort
        worker = kotlin.concurrent.thread(isDaemon = true, name = "herdrdroid-forward-$port") {
            try {
                listen(socket)
            } catch (failure: Exception) {
                ready.completeExceptionally(failure)
            } finally {
                runCatching { socket.close() }
                ready.completeExceptionally(IOException("The SSH forward stopped before listening."))
            }
        }
        withTimeout(5_000) { ready.await() }
        if (socket.isClosed) throw IOException("The SSH forward closed during startup.")
        return Forward(port, ::stop)
    } catch (failure: Throwable) {
        stop()
        throw failure
    }
}
