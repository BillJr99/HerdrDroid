package com.billmongan.herdrdroid.herdr

import java.util.concurrent.atomic.AtomicInteger

/**
 * One prompt write, checked at the transport boundary right before the command is started.
 * Revoking it before then means nothing is sent; once claimed, the write is in flight and its
 * outcome is only known from the transport's result (2.0.1 review findings 2 and 4).
 */
internal class WritePermit {
    private val state = AtomicInteger(OPEN)

    /** Stops the write if it has not started. True if this call (or an earlier one) revoked it. */
    fun revoke(): Boolean = state.compareAndSet(OPEN, REVOKED) || state.get() == REVOKED

    /** Called once by the transport just before it starts the command. False: do not send. */
    fun claim(): Boolean = state.compareAndSet(OPEN, SUBMITTED)

    val submitted: Boolean get() = state.get() == SUBMITTED
    val revoked: Boolean get() = state.get() == REVOKED

    private companion object {
        const val OPEN = 0
        const val REVOKED = 1
        const val SUBMITTED = 2
    }
}

/** The transport refused a write whose permit was revoked: nothing reached the computer. */
internal class WriteRevoked : Exception("Not sent: the send was cancelled before it reached the computer.")

/** Called by a transport immediately before it starts a write. */
internal fun WritePermit?.admit() {
    if (this != null && !claim()) throw WriteRevoked()
}

/**
 * The live write permits per (server, pane), and the panes being closed. App lock and the end of
 * hands-free revoke every open permit; confirming a close revokes that tab's permits and refuses
 * new ones until the close is over.
 */
internal class WriteGate {
    private val live = mutableMapOf<Pair<String, String>, MutableSet<WritePermit>>()
    private val closing = mutableMapOf<Pair<String, String>, Int>()
    private val epochs = mutableMapOf<Pair<String, String>, Long>()

    /**
     * Changes each time a close of [key] is confirmed. Work that waited for a pane's lane compares
     * it with the value it saw before waiting, so a close confirmed meanwhile is never followed by
     * a write once the close is over (2.0.1 review of findings 1, 2 and 4).
     */
    fun epoch(key: Pair<String, String>): Long = synchronized(this) { epochs[key] ?: 0L }

    /** A permit for one write to [key], or null while that pane is being closed. */
    fun open(key: Pair<String, String>): WritePermit? = synchronized(this) {
        if (key in closing) null else WritePermit().also { live.getOrPut(key) { mutableSetOf() }.add(it) }
    }

    fun finish(key: Pair<String, String>, permit: WritePermit) = synchronized(this) {
        live[key]?.let { it.remove(permit); if (it.isEmpty()) live.remove(key) }
        Unit
    }

    fun isClosing(key: Pair<String, String>): Boolean = synchronized(this) { key in closing }

    fun revokeAll() = synchronized(this) { live.values.flatten().forEach { it.revoke() } }

    /** Marks [keys] as closing and revokes their open permits. Pair with [endClose]. */
    fun beginClose(keys: Collection<Pair<String, String>>) = synchronized(this) {
        for (key in keys) {
            closing[key] = (closing[key] ?: 0) + 1
            epochs[key] = (epochs[key] ?: 0L) + 1
            live[key]?.forEach { it.revoke() }
        }
    }

    fun endClose(keys: Collection<Pair<String, String>>) = synchronized(this) {
        for (key in keys) {
            val n = (closing[key] ?: continue) - 1
            if (n <= 0) closing.remove(key) else closing[key] = n
        }
    }
}
