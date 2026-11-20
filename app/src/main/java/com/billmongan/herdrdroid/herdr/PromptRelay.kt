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

    suspend fun send(target: Target, text: String, authorizedWrite: WritePermit = WritePermit(), allowed: () -> Boolean): RelayResult {
        val key = target.server to target.pane.id
        fun valid() = allowed() && isCurrent(target) && !gate.isClosing(key) && clearToSend(target.server, target.pane.id)
        val clean = text.isNotBlank() && text.none { it != '\n' && it != '\t' && it.isISOControl() }
        if (!clean || !valid()) return RelayResult.REFUSED
        val release = dispatch.tryAcquire(key) ?: return RelayResult.REFUSED
        val permit = gate.open(key, authorizedWrite) ?: run { release(); return RelayResult.REFUSED }
        try {
            val port = target.port
            suspend fun idlePane(): Pane? = port.pane(target.pane.id, target.connection)?.takeIf {
                sameOccupant(target.pane, it) && it.status in setOf(Status.IDLE, Status.DONE) &&
                    it.scrollOffset == 0 && !it.backgroundBusy
            }
            if (!valid()) return RelayResult.REFUSED
            val fresh = idlePane() ?: return RelayResult.REFUSED
            if (!valid() || port.hasWork(fresh, target.connection) || !valid()) return RelayResult.REFUSED
            val viewport = port.viewport(fresh.id, target.connection)
            if (!valid() || dismissibleUsageFrame(fresh.agent, viewport) != null || detectPrompt(forPhone(viewport), false, fresh.agent) != null)
                return RelayResult.REFUSED
            val line = inputLineOf(viewport, fresh.agent)
            if (line != "") return if (!line.isNullOrEmpty() || composerDraftOf(viewport, fresh.agent) != null) RelayResult.DRAFT else RelayResult.REFUSED
            // This recheck narrows, but does not eliminate, the server-side read/write race.
            val ready = idlePane() ?: return RelayResult.REFUSED
            if (!valid()) return RelayResult.REFUSED
            port.send(ready, text, target.connection, permit)
            return RelayResult.SENT
        } catch (c: CancellationException) {
            // Cancelled after the write started: it may have arrived, so it is reported and never
            // retried. Cancelled before: nothing was sent and the cancellation goes on.
            if (permit.submitted) return RelayResult.UNCERTAIN
            throw c
        } catch (_: WriteRevoked) {
            return RelayResult.REFUSED
        } catch (_: ConnectionChanged) {
            return if (permit.submitted) RelayResult.UNCERTAIN else RelayResult.REFUSED
        } catch (_: Exception) {
            return if (permit.submitted) RelayResult.UNCERTAIN else RelayResult.REFUSED
        } finally {
            gate.finish(key, permit)
            release()
        }
    }
}
