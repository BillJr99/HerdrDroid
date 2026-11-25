package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.CancellationException

/**
 * 2.2.8 (Bill, 2026-11-25: "long pressing it allows me to copy it or other things like share, add
 * send immediately to that menu which would background all foreground tasks until it sends"; and
 * "could it indicate what is currently in the foreground vs background ... and refresh as I click
 * background"): moving an agent's foreground command to the background with the agent's own key,
 * as often as it takes, until a goal is met.
 *
 * Only an agent whose screen proves a foreground command is running ([foregroundHint]) gets the key:
 * today Claude Code, which draws "(ctrl+b to run in background)" under a running command. Other
 * agents in [Backgrounding] also take Ctrl+B, but draw no hint the app has verified, so nothing here
 * presses it for them; [backgroundSupport] says why. Each round reads the screen first and never
 * presses the key while a question, approval or panel is open ([Port.blocked]), and never sends Esc
 * or Ctrl+C.
 */
internal class Backgrounder(private val port: Port, private val limits: Limits = Limits()) {
    interface Port {
        /** A fresh read of the pane's screen, or null when it can't be read. */
        suspend fun screen(): String?
        /** A question, approval prompt or panel is open on [screen], or the pane's occupant changed. */
        fun blocked(screen: String): Boolean
        /** The goal is met (the message went, or the agent is idle and the caller can send). */
        fun done(screen: String): Boolean
        /** Presses the background key once. */
        suspend fun press()
        suspend fun pause(ms: Long)
    }

    data class Limits(
        /** Ctrl+B presses at most, over the whole run. */
        val maxPresses: Int = 6,
        /** Reads at most, over the whole run (each [pollMs] apart): about 90 seconds by default. */
        val maxReads: Int = 60,
        val pollMs: Long = 1_500,
    )

    enum class Outcome {
        /** [Port.done] held. */
        DONE,
        /** A question, approval or panel opened: nothing more was pressed. */
        BLOCKED,
        /** The screen could not be read twice running. */
        UNREADABLE,
        /** The hint was still there after a press and a full wait: the key did not take. */
        STUCK,
        /** The reads or presses ran out before the goal. */
        TIMED_OUT,
    }

    /** Runs until [Port.done], pressing the key each time the screen shows a foreground command. */
    suspend fun run(): Outcome {
        var presses = 0
        var misses = 0
        var pressedAt = -1
        for (read in 0 until limits.maxReads) {
            if (read > 0) port.pause(limits.pollMs)
            val screen = try { port.screen() } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            if (screen == null) { if (++misses >= 2) return Outcome.UNREADABLE; continue }
            misses = 0
            if (port.blocked(screen)) return Outcome.BLOCKED
            if (port.done(screen)) return Outcome.DONE
            if (!claudeBackgroundHint(screen)) { pressedAt = -1; continue }
            // The same command still in the foreground three reads after a press: the key didn't take.
            if (pressedAt >= 0 && read - pressedAt < 3) continue
            if (pressedAt >= 0) return Outcome.STUCK
            if (presses >= limits.maxPresses) return Outcome.TIMED_OUT
            port.press()
            presses++
            pressedAt = read
        }
        return Outcome.TIMED_OUT
    }
}

/**
 * Whether the app can move [agent]'s foreground work to the background for Send immediately, and if
 * not, why (2.2.8). Null: it can.
 */
internal fun backgroundSupport(agent: String?, agentName: String): String? = when {
    agent.equals("claude", true) -> null
    Backgrounding.supported(agent) ->
        "$agentName takes Ctrl+B, but its screen shows no sign the app can check that a command moved, so Send immediately is off. Use Terminal."
    else -> "$agentName has no key that moves a running task to the background, so this waits until it is idle."
}

/** True when [screen] shows [agent] running a command in the foreground that Ctrl+B can move. */
internal fun foregroundHint(agent: String?, screen: String): Boolean = agent.equals("claude", true) && claudeBackgroundHint(screen)

/** What the chat says after Send immediately on a message in the agent's own queue (2.2.8). */
internal fun backgroundOutcomeText(outcome: Backgrounder.Outcome, agentName: String): String? = when (outcome) {
    Backgrounder.Outcome.DONE -> null
    Backgrounder.Outcome.BLOCKED -> "Stopped moving tasks to the background: $agentName has a question or panel open. Answer it first."
    Backgrounder.Outcome.UNREADABLE -> "Stopped: $agentName's screen couldn't be read. The message is still in its queue."
    Backgrounder.Outcome.STUCK -> "Ctrl+B didn't move $agentName's running command to the background. The message is still in its queue."
    Backgrounder.Outcome.TIMED_OUT -> "$agentName hasn't taken the message yet. It is still in its queue."
}
