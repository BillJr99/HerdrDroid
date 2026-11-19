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
}

internal enum class RelayResult(val feedback: String) {
    SENT("Message sent."),
    REFUSED("Not sent. Check that the destination is still connected and idle, with no question, draft, or queued message."),
    UNCERTAIN("Could not confirm delivery. Check the destination chat before trying again."),
}

internal class PromptRelay(
    private val dispatch: PaneDispatch,
    private val portFor: (String) -> PromptRelayPort?,
    private val clearToSend: (String, String) -> Boolean,
) {
    data class Target(val server: String, val pane: Pane, val port: PromptRelayPort, val connection: Any)

    private fun sameOccupant(a: Pane, b: Pane) = a.id == b.id && a.tabId == b.tabId &&
        a.workspaceId == b.workspaceId && a.agent == b.agent && a.cwd == b.cwd &&
        a.terminalId == b.terminalId && a.sessionId == b.sessionId

    fun isCurrent(target: Target) = portFor(target.server)?.owner === target.port.owner &&
        target.port.connectionIdentity === target.connection

    suspend fun bind(server: String, selected: Pane): Target? {
        if (selected.agent.isNullOrBlank() || (selected.sessionId.isNullOrBlank() && selected.terminalId.isNullOrBlank())) return null
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

    suspend fun send(target: Target, text: String, allowed: () -> Boolean): RelayResult {
        fun valid() = allowed() && isCurrent(target) && clearToSend(target.server, target.pane.id)
        val clean = text.isNotBlank() && text.none { it != '\n' && it != '\t' && it.isISOControl() }
        if (!clean || !valid()) return RelayResult.REFUSED
        val release = dispatch.tryAcquire(target.server to target.pane.id) ?: return RelayResult.REFUSED
        var submitting = false
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
            if (!valid() || inputLineOf(viewport, fresh.agent) != "" ||
                dismissibleUsageFrame(fresh.agent, viewport) != null || detectPrompt(forPhone(viewport), false, fresh.agent) != null)
                return RelayResult.REFUSED
            // This recheck narrows, but does not eliminate, the server-side read/write race.
            val ready = idlePane() ?: return RelayResult.REFUSED
            if (!valid()) return RelayResult.REFUSED
            submitting = true
            port.send(ready, text, target.connection)
            return RelayResult.SENT
        } catch (c: CancellationException) {
            throw c
        } catch (_: ConnectionChanged) {
            return RelayResult.REFUSED
        } catch (_: Exception) {
            return if (submitting) RelayResult.UNCERTAIN else RelayResult.REFUSED
        } finally {
            release()
        }
    }
}
