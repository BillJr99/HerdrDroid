package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.ConnectionChanged
import kotlinx.coroutines.CancellationException

internal interface PromptRelayPort {
    val owner: Any get() = this
    val connectionIdentity: Any?
    suspend fun pane(id: String, connection: Any): Pane?
    suspend fun hasWork(pane: Pane, connection: Any): Boolean
    suspend fun viewport(id: String, connection: Any): String
    suspend fun send(pane: Pane, text: String, connection: Any)

    /**
     * Sends only if [permit] is still open when the write starts. A real transport claims it after
     * it holds the connection; this default claims it immediately before [send].
     */
    suspend fun send(pane: Pane, text: String, connection: Any, permit: WritePermit) {
        permit.admit()
        send(pane, text, connection)
    }
}

/** Who is in a pane: same pane, tab, workspace, agent, folder, terminal and agent session. */
internal data class Occupant(
    val pane: String, val tab: String, val workspace: String, val agent: String?, val cwd: String,
    val terminalId: String?, val sessionId: String?,
) {
    /** Can be told apart from a replacement in the same place (2.0.1 finding 1). */
    val stable: Boolean get() = !agent.isNullOrBlank() && !(sessionId.isNullOrBlank() && terminalId.isNullOrBlank())

    companion object {
        fun of(p: Pane) = Occupant(p.id, p.tabId, p.workspaceId, p.agent, p.cwd, p.terminalId, p.sessionId)
    }
}

internal fun sameOccupant(a: Pane, b: Pane) = Occupant.of(a) == Occupant.of(b)

internal fun hasStableOccupant(p: Pane) = Occupant.of(p).stable

internal enum class RelayResult(val feedback: String) {
    SENT("Message sent."),
    REFUSED("Not sent. Check that the destination is still connected and idle, with no question, draft, or queued message."),
    UNCERTAIN("Could not confirm delivery. Check the destination chat before trying again."),
    /** 2.1.0: text is typed in the destination's input box; its chat shows it with Send draft and Clear draft. */
    DRAFT("Not sent. Text is already typed in the destination's input box at the computer. Open that agent's chat to send or clear the draft, then try again."),
}

/**
 * 2.2.1: why a relayed message was not sent, so the card can say which check failed (Bill: "it pops
 * up a card saying that the message was not sent and nothing happens"). Nothing was typed in any of
 * these cases. [transient] refusals can pass on a later try (the destination busy, a link down);
 * the others cannot pass for this proposal (a different session in a pane, a bad message).
 */
internal enum class RelayRefusal(val why: String, val transient: Boolean) {
    TEXT("the message is empty or holds control characters", false),
    STOPPED("the send was stopped before it went (the chat, the app or the approval changed)", true),
    LINK("the connection to that server changed or is not ready", true),
    CLOSING("that pane is being closed", true),
    LANE("HerdrDroid sees it busy, or has a queued message, an answer or a draft of its own waiting for it", true),
    IN_FLIGHT("another message to that pane is being sent", true),
    UNREACHABLE("that agent is not reachable now (its server is not connected, or the pane is gone)", true),
    REPLACED("a different session is now in that pane", false),
    NOT_IDLE("it is not idle at the bottom of its screen (working, waiting on you, or scrolled back)", true),
    BACKGROUND("it is running background work that typing could interrupt, or that work could not be checked", true),
    USAGE("a usage panel is open in it", true),
    QUESTION("a question or dialog is open in it", true),
    NO_INPUT("its input box is not on screen, so it can't be checked for a draft", true),
    DRAFT("text is already typed in its input box at the computer", true),
    FAILED("its pane or screen couldn't be read", true),
    SOURCE_CHANGED("this chat's pane is no longer open as it was when the proposal came", true),
    SOURCE_REPLACED("a different session is now in this chat's pane", false),
}

/**
 * 2.2.1: agents whose input box takes typed text while their own background work runs. Claude Code
 * keeps its composer live while background shells, tasks or helpers run ("3 shells" in its footer,
 * "Waiting for 2 background agents to finish"), and a message typed then is an ordinary prompt. For
 * these, background work alone does not hold a relayed message: herdr's own status must still be
 * idle or done, the box empty and no question open. Every other agent still waits for its
 * background work, where typing could interrupt it.
 */
internal fun takesInputDuringBackgroundWork(agent: String?): Boolean = agent.equals("claude", true)

/**
 * A watched pane, after the app's work sweep ([withBusy]), that a relay may write to: idle or done
 * with no background work, or with only background work in an agent that takes input meanwhile.
 * The relay rechecks herdr's own status on a fresh read before anything is typed.
 */
internal fun idleForRelay(p: Pane): Boolean =
    if (p.backgroundBusy) takesInputDuringBackgroundWork(p.agent)
    else p.status == Status.IDLE || p.status == Status.DONE

internal class PromptRelay(
    private val dispatch: PaneDispatch,
    private val portFor: (String) -> PromptRelayPort?,
    private val gate: WriteGate = WriteGate(),
    private val clearToSend: (String, String) -> Boolean,
) {
    data class Target(val server: String, val pane: Pane, val port: PromptRelayPort, val connection: Any)

    fun isCurrent(target: Target) = portFor(target.server)?.owner === target.port.owner &&
        target.port.connectionIdentity === target.connection

    suspend fun bind(server: String, selected: Pane): Target? {
        if (!hasStableOccupant(selected)) return null
        val port = portFor(server) ?: return null
        val connection = port.connectionIdentity ?: return null
        return try {
            val fresh = port.pane(selected.id, connection) ?: return null
            Target(server, fresh, port, connection).takeIf { sameOccupant(selected, fresh) && isCurrent(it) }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
    }

    suspend fun send(
        target: Target, text: String, authorizedWrite: WritePermit = WritePermit(),
        refused: (RelayRefusal) -> Unit = {}, allowed: () -> Boolean,
    ): RelayResult {
        val key = target.server to target.pane.id
        fun invalid(): RelayRefusal? = when {
            !allowed() -> RelayRefusal.STOPPED
            !isCurrent(target) -> RelayRefusal.LINK
            gate.isClosing(key) -> RelayRefusal.CLOSING
            !clearToSend(target.server, target.pane.id) -> RelayRefusal.LANE
            else -> null
        }
        fun refuse(why: RelayRefusal): RelayResult {
            refused(why)
            return if (why == RelayRefusal.DRAFT) RelayResult.DRAFT else RelayResult.REFUSED
        }
        val clean = text.isNotBlank() && text.none { it != '\n' && it != '\t' && it.isISOControl() }
        if (!clean) return refuse(RelayRefusal.TEXT)
        invalid()?.let { return refuse(it) }
        val release = dispatch.tryAcquire(key) ?: return refuse(RelayRefusal.IN_FLIGHT)
        val permit = gate.open(key, authorizedWrite) ?: run { release(); return refuse(RelayRefusal.CLOSING) }
        try {
            val port = target.port
            // 2.2.1: background work alone does not hold an agent that takes input meanwhile.
            val waitsForBackground = !takesInputDuringBackgroundWork(target.pane.agent)
            var notIdle: RelayRefusal = RelayRefusal.NOT_IDLE
            suspend fun idlePane(): Pane? {
                val p = port.pane(target.pane.id, target.connection)
                notIdle = when {
                    p == null -> RelayRefusal.NOT_IDLE
                    !sameOccupant(target.pane, p) -> RelayRefusal.REPLACED
                    p.status !in setOf(Status.IDLE, Status.DONE) || p.scrollOffset != 0 -> RelayRefusal.NOT_IDLE
                    p.backgroundBusy && waitsForBackground -> RelayRefusal.BACKGROUND
                    else -> return p
                }
                return null
            }
            invalid()?.let { return refuse(it) }
            val fresh = idlePane() ?: return refuse(notIdle)
            invalid()?.let { return refuse(it) }
            if (waitsForBackground && port.hasWork(fresh, target.connection)) return refuse(RelayRefusal.BACKGROUND)
            invalid()?.let { return refuse(it) }
            val viewport = port.viewport(fresh.id, target.connection)
            invalid()?.let { return refuse(it) }
            if (dismissibleUsageFrame(fresh.agent, viewport) != null ||
                (fresh.agent in setOf("grok", "agy") && usageDialogHint(fresh.agent, viewport)))
                return refuse(RelayRefusal.USAGE)
            if (detectPrompt(forPhone(viewport), false, fresh.agent) != null) return refuse(RelayRefusal.QUESTION)
            // 2.2.1: the screen says a turn is running ("esc to interrupt" under the box), whatever herdr says.
            if (composerFooterSaysWorking(viewport)) return refuse(RelayRefusal.NOT_IDLE)
            val line = inputLineOf(viewport, fresh.agent)
            // A draft of several rows whose first row is empty is a draft too.
            val draft = composerDraftOf(viewport, fresh.agent) != null
            if (!line.isNullOrEmpty() || draft) return refuse(RelayRefusal.DRAFT)
            if (line == null) return refuse(RelayRefusal.NO_INPUT)
            // This recheck narrows, but does not eliminate, the server-side read/write race.
            val ready = idlePane() ?: return refuse(notIdle)
            invalid()?.let { return refuse(it) }
            port.send(ready, text, target.connection, permit)
            return RelayResult.SENT
        } catch (c: CancellationException) {
            // Cancelled after the write started: it may have arrived, so it is reported and never
            // retried. Cancelled before: nothing was sent and the cancellation goes on.
            if (permit.submitted) return RelayResult.UNCERTAIN
            throw c
        } catch (_: WriteRevoked) {
            return refuse(RelayRefusal.STOPPED)
        } catch (_: ConnectionChanged) {
            return if (permit.submitted) RelayResult.UNCERTAIN else refuse(RelayRefusal.LINK)
        } catch (_: Exception) {
            return if (permit.submitted) RelayResult.UNCERTAIN else refuse(RelayRefusal.FAILED)
        } finally {
            gate.finish(key, permit)
            release()
        }
    }
}
