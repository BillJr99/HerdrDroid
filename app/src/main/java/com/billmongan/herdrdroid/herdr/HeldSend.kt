package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** How a held send ended (1.4.4). */
sealed class HeldOutcome {
    /** Typed into the pane once its input line was clear. */
    data class Delivered(val afterMs: Long, val checks: Int) : HeldOutcome()
    /** Still held at the deadline: [why] is the last reason. */
    data class TimedOut(val why: String, val checks: Int) : HeldOutcome()
    /** The input line was clear, but sending failed. */
    data class Failed(val error: Exception) : HeldOutcome()
    /** Stopped before sending: dismissed, the pane closed, or the computer changed. */
    data class Cancelled(val why: String) : HeldOutcome()
}

/**
 * Quietly retries a message the send guard held (1.4.4, Bill: "have the app do that seamlessly and
 * only report if it fails"). Every [everyMs] it asks [check] again, which re-reads the pane and
 * gives the reason to keep holding, or null when the input line at the computer is clear; then it
 * sends at once. It stops at [windowMs] after [startedAt], or when [cancelled] gives a reason.
 * The 1.4.0 rule stands: nothing is typed while [check] says something is typed there.
 */
class HeldSendRetry(
    private val windowMs: Long = 120_000L,
    private val everyMs: Long = 2_000L,
    private val now: () -> Long = ::monotonicMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    companion object {
        internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
    }

    suspend fun run(
        startedAt: Long,
        firstWhy: String,
        cancelled: () -> String?,
        check: suspend () -> String?,
        send: suspend () -> Unit,
        log: (String) -> Unit = {},
        acquire: suspend () -> (() -> Unit) = { {} },
    ): HeldOutcome {
        var why = firstWhy
        var checks = 0
        fun remaining() = windowMs - (now() - startedAt)
        fun stopped() = cancelled()?.let {
            log("retry stopped: $it")
            HeldOutcome.Cancelled(it)
        }
        fun timedOut(): HeldOutcome.TimedOut {
            log("still held after ${(now() - startedAt) / 1000} s ($checks checks): $why; reported")
            return HeldOutcome.TimedOut(why, checks)
        }
        // Dismissal can happen during sleep, lock acquisition, or a screen read.
        fun expired(): HeldOutcome = stopped() ?: timedOut()
        while (true) {
            val beforeSleep = remaining()
            if (beforeSleep <= 0) return expired()
            sleep(minOf(everyMs, beforeSleep))
            val lockBudget = remaining()
            if (lockBudget <= 0) return expired()
            var release: (() -> Unit)? = null
            try {
                // Keep ownership outside the timeout scope so a timeout racing with acquisition
                // cannot discard the unlock callback.
                val acquired = withTimeoutOrNull(lockBudget) { release = acquire(); true }
                if (acquired == null || remaining() <= 0) return expired()
                stopped()?.let { return it }
                val readBudget = remaining()
                if (readBudget <= 0) return timedOut()
                // A completed check may return null for a clear screen; distinguish it from
                // cancellation at the deadline. Do not apply this timeout to the send itself.
                val checked = withTimeoutOrNull(readBudget) {
                    Result.success(try {
                        check()
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        "its screen couldn't be read"
                    })
                } ?: return expired()
                val next = checked.getOrThrow()
                checks++
                if (next != null) {
                    if (next != why) log("still held: $next")
                    why = next
                    continue
                }
                // A dismissal during the screen read still stops the send.
                stopped()?.let { return it }
                currentCoroutineContext().ensureActive()
                if (remaining() <= 0) return timedOut()
                return try {
                    send()
                    HeldOutcome.Delivered(now() - startedAt, checks).also {
                        log("input line clear after ${it.afterMs / 1000} s ($checks checks): sent")
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    log("input line clear, but sending failed: ${e.message ?: e.javaClass.simpleName}")
                    HeldOutcome.Failed(e)
                }
            } finally {
                release?.invoke()
            }
        }
    }
}
