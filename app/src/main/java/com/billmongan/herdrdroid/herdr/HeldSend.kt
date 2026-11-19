package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
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
        while (true) {
            if (now() - startedAt >= windowMs) {
                log("still held after ${(now() - startedAt) / 1000} s ($checks checks): $why; reported")
                return HeldOutcome.TimedOut(why, checks)
            }
            sleep(everyMs)
            val release = acquire()
            try {
                cancelled()?.let { log("retry stopped: $it"); return HeldOutcome.Cancelled(it) }
                val next = try {
                    check()
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    "its screen couldn't be read"
                }
                checks++
                if (next != null) {
                    if (next != why) log("still held: $next")
                    why = next
                    continue
                }
                // A dismissal during the screen read still stops the send.
                cancelled()?.let { log("retry stopped: $it"); return HeldOutcome.Cancelled(it) }
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
                release()
            }
        }
    }
}
