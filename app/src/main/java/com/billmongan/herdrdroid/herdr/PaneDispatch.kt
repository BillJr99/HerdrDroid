package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.sync.Mutex

/** Serializes one pane's app-initiated input, including its preflight reads. */
internal class PaneDispatch {
    private class Lane(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val lanes = mutableMapOf<Pair<String, String>, Lane>()

    private fun retain(key: Pair<String, String>) = synchronized(lanes) {
        lanes.getOrPut(key) { Lane() }.also { it.users++ }
    }

    private fun release(key: Pair<String, String>, lane: Lane) = synchronized(lanes) {
        if (--lane.users == 0) lanes.remove(key)
    }

    fun tryAcquire(key: Pair<String, String>): (() -> Unit)? {
        val lane = retain(key)
        if (!lane.mutex.tryLock()) {
            release(key, lane)
            return null
        }
        return { lane.mutex.unlock(); release(key, lane); Unit }
    }

    suspend fun acquire(key: Pair<String, String>): () -> Unit {
        val lane = retain(key)
        try {
            lane.mutex.lock()
        } catch (failure: Throwable) {
            release(key, lane)
            throw failure
        }
        return {
            lane.mutex.unlock()
            release(key, lane)
        }
    }

    suspend fun <T> run(key: Pair<String, String>, block: suspend () -> T): T {
        val unlock = acquire(key)
        try {
            return block()
        } finally {
            unlock()
        }
    }
}
