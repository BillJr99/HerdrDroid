package com.billmongan.herdrdroid.fleet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.userauth.keyprovider.KeyProvider

/**
 * What one other server's agents are doing, as last seen. [panes] already carry the busy
 * override: an agent herdr calls idle, done or unknown while its subagents, background tasks or
 * shells still run shows as working ([busy] is that count per pane).
 */
data class FleetState(val server: Server, val panes: List<Pane>, val error: String?, val at: Long, val busy: Map<String, Int> = emptyMap())

/**
 * Watches the servers other than the open one, so one "needs you" list can cover all of them.
 * Each gets its own quiet SSH connection that reads `herdr pane list` every [everyMs]. Only
 * servers that can sign in without a prompt are watched (a key, or a saved password, and not
 * marked for a fingerprint unlock unless it was already unlocked this session).
 */
class Fleet(
    private val scope: CoroutineScope,
    private val credentials: (Server) -> Credentials,
    private val keystoreKey: (String) -> KeyProvider?,
    private val onSeen: (FleetState) -> Unit,
    /**
     * Reads what runs under each agent there (the same sweep as the open server). Any failure
     * (no /proc, an old python, a slow host) falls back to herdr's own status (1.2.8).
     */
    private val busyOf: (suspend (Herdr) -> Map<String, Int>)? = null,
    /** 2.2.7: a server whose session fell back to small packets is watched with them too. */
    private val sessionMss: (String) -> Int? = { null },
) {
    var states by mutableStateOf<Map<String, FleetState>>(emptyMap())
        private set
    private val clients = mutableMapOf<String, Pair<HerdrSsh, Herdr>>()
    private val pollers = mutableMapOf<String, Pair<Job, Channel<Unit>>>()
    // Bound work across servers while allowing a slow watcher to occupy only one slot.
    private val pollSlots = Semaphore(4)
    private var watched = mapOf<String, Server>()
    var everyMs = 15_000L
    /** True while the phone's screen is on (1.4.0): with it off, the loop slows to 3 x [everyMs]. */
    var screenOn: () -> Boolean = { true }
    /** When each server's work sweep last ran: it runs at most every 30 s (90 s, screen off), not on every tick (1.4.0). */
    private val sweptAt = mutableMapOf<String, Long>()
    private val detected = mutableSetOf<String>()
    private val retryAt = mutableMapOf<String, Long>()
    private val retryStep = mutableMapOf<String, Long>()

    /** Reads every watched server now instead of after the rest of the gap (2.1.4: the drawer opened). */
    fun poke() { pollers.values.forEach { (_, wake) -> wake.trySend(Unit) } }

    /** 2.2.7, for tests: the MSS the watcher's connection to [serverId] would open with now. */
    internal fun clientMss(serverId: String): Int? = clients[serverId]?.first?.activeMss

    /** Starts (or retargets) watching [servers]; the rest are dropped. */
    fun watch(servers: List<Server>) {
        val next = servers.associateBy { it.id }
        val removed = watched.keys.filter { watched[it] != next[it] }.toSet()
        watched = next
        removed.forEach { id ->
            pollers.remove(id)?.first?.cancel()
            clients.remove(id)?.first?.close()
            sweptAt.remove(id)
            detected.remove(id)
            retryAt.remove(id)
            retryStep.remove(id)
        }
        states = states.filterKeys { it in next && it !in removed }
        servers.forEach { s ->
            if (s.id !in clients && s.fingerprint != null) {
                val ssh = HerdrSsh(s.host, s.port, s.user, credentials(s), s.fingerprint, keystoreKey, s.wsUrl?.takeIf { s.overWebSocket }, s.smallPacketsMss, s.id)
                    .also { c -> c.sessionFallback = { sessionMss(s.id) } }
                // A server set to herdr-core is watched through hc; Automatic ones through herdr
                // (a watcher never asks which one).
                clients[s.id] = ssh to Herdr(ssh, s.herdrPath, if (s.backend == "core") com.billmongan.herdrdroid.herdr.Backend.CORE else com.billmongan.herdrdroid.herdr.Backend.TUI)
            }
        }
        clients.keys.toList().forEach { id ->
            if (pollers[id]?.first?.isActive != true) {
                val wake = Channel<Unit>(Channel.CONFLATED)
                val poller = scope.launch(start = CoroutineStart.LAZY) {
                    while (isActive) {
                        pollSlots.withPermit { poll(id) }
                        // A poke wakes this server without waiting for the others.
                        withTimeoutOrNull(if (screenOn()) everyMs else everyMs * 3) { wake.receive() }
                    }
                }
                pollers[id] = poller to wake
                poller.start()
            }
        }
    }

    private suspend fun poll(id: String) {
        val server = watched[id] ?: return
        var pair = clients[id] ?: return
        fun stillWatching() = clients[id] === pair && watched[id] == server

        if ((retryAt[id] ?: 0L) > System.currentTimeMillis()) return
        // An Automatic server is looked at once to see which herdr it runs.
        if (server.backend == null && id !in detected) {
            val d = try {
                com.billmongan.herdrdroid.herdr.detectBackend(pair.first, server.herdrPath)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                null
            }
            if (!stillWatching()) return
            if (d != null) {
                detected += id
                if (d.coreRunning && !d.tuiRunning) {
                    pair = pair.first to Herdr(pair.first, server.herdrPath, com.billmongan.herdrdroid.herdr.Backend.CORE, d.hcPath)
                    clients[id] = pair
                }
            }
        }

        val state = try {
            val raw = pair.second.panes()
            if (!stillWatching()) return
            val gap = if (screenOn()) 30_000L else 90_000L
            val sweepDue = System.currentTimeMillis() - (sweptAt[id] ?: 0L) >= gap
            val last = states[id]?.busy.orEmpty()
            val busy = if (!sweepDue) last else busyOf?.let { read ->
                try {
                    read(pair.second).also {
                        if (stillWatching()) sweptAt[id] = System.currentTimeMillis()
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    // Keep the last result and retry the sweep on the next tick.
                    last
                }
            }.orEmpty()
            FleetState(server, sweptPanes(raw, Result.success(busy)), null, System.currentTimeMillis(), busy)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            if (!stillWatching()) return
            pair.first.reset()
            val wait = ((retryStep[id] ?: 15_000L) * 2).coerceAtMost(10 * 60_000L)
            retryStep[id] = wait
            retryAt[id] = System.currentTimeMillis() + wait
            FleetState(server, states[id]?.panes.orEmpty(), e.message ?: "unreachable", System.currentTimeMillis())
        }
        if (!stillWatching()) return
        if (state.error == null) {
            retryAt.remove(id)
            retryStep.remove(id)
        }
        states = states + (id to state)
        if (state.error == null) onSeen(state)
    }

    /**
     * Hands over a watched server's live connection (the app is about to open that server), so
     * opening it needs no new sign-in. The watcher makes a fresh one if it watches it again.
     */
    fun take(serverId: String): Pair<HerdrSsh, Herdr>? {
        val pair = clients.remove(serverId)
        pollers.remove(serverId)?.first?.cancel()
        return pair
    }

    /** The herdr client for a watched server, to answer a question there. */
    fun herdr(serverId: String): Herdr? = clients[serverId]?.second

    fun stop() {
        pollers.values.forEach { (poller, _) -> poller.cancel() }
        pollers.clear()
        clients.values.forEach { it.first.close() }
        clients.clear()
        states = emptyMap()
    }
}

/** A watched server's panes after its work sweep; a failed sweep leaves herdr's status as it is. */
internal fun sweptPanes(raw: List<Pane>, busy: Result<Map<String, Int>>): List<Pane> =
    com.billmongan.herdrdroid.herdr.withBusy(raw, busy.getOrDefault(emptyMap()))
