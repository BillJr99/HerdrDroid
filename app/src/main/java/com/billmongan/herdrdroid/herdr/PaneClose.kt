package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.ConnectionChanged
import kotlinx.coroutines.CancellationException

/**
 * Closing a tab, ordered against prompt sends to its panes (2.0.1 finding 2).
 *
 * The order chosen: confirming the close at once refuses every new send to the tab's panes and
 * revokes any send that has not reached the computer yet. A send already submitted finishes
 * first, because the close waits for each pane's dispatch lane; then the tab is closed. No write
 * starts after the close is confirmed. The close runs only over the connection it was confirmed
 * on, and only if the pane still holds the same occupant.
 */
internal class PaneClose(private val dispatch: PaneDispatch, private val gate: WriteGate) {
    interface Port {
        /** The live panes over [connection], or an exception. */
        suspend fun panes(connection: Any): List<Pane>
        suspend fun closeTab(tabId: String, connection: Any)
    }

    enum class Outcome { CLOSED, CHANGED, FAILED }

    class Result(val outcome: Outcome, val failure: Exception? = null)

    /** The confirmed close: marks [known] (the tab's panes as shown) closing, synchronously. */
    inner class Pending internal constructor(
        val server: String, val selected: Pane, val connection: Any, known: Collection<Pane>,
    ) {
        internal val marked = (known.filter { it.tabId == selected.tabId }.map { server to it.id } + (server to selected.id))
            .toSortedSet(compareBy({ it.first }, { it.second }))
        init { gate.beginClose(marked) }
    }

    /** Call on the confirm tap, before any suspension. */
    fun confirm(server: String, selected: Pane, connection: Any, known: Collection<Pane>) = Pending(server, selected, connection, known)

    suspend fun run(pending: Pending, port: Port): Result {
        val held = mutableListOf<() -> Unit>()
        val marked = pending.marked.toMutableSet()
        try {
            for (key in pending.marked) held += dispatch.acquire(key)
            val live = port.panes(pending.connection)
            val now = live.singleOrNull { it.id == pending.selected.id }
            if (now == null || !sameOccupant(pending.selected, now)) return Result(Outcome.CHANGED)
            // A pane that joined the tab since the drawer was drawn is closed with it, so it too
            // must refuse sends first.
            val extra = live.filter { it.tabId == now.tabId }.map { pending.server to it.id }.filter { it !in marked }
                .sortedWith(compareBy({ it.first }, { it.second }))
            if (extra.isNotEmpty()) {
                gate.beginClose(extra)
                marked += extra
                for (key in extra) held += dispatch.acquire(key)
            }
            port.closeTab(now.tabId, pending.connection)
            return Result(Outcome.CLOSED)
        } catch (c: CancellationException) {
            throw c
        } catch (e: ConnectionChanged) {
            return Result(Outcome.CHANGED, e)
        } catch (e: Exception) {
            return Result(Outcome.FAILED, e)
        } finally {
            held.asReversed().forEach { it() }
            gate.endClose(marked)
        }
    }
}
