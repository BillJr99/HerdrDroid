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
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private var job: Job? = null
    private var watched = mapOf<String, Server>()
    var everyMs = 15_000L
    /** True while the phone's screen is on (1.4.0): with it off, the loop slows to 3 x [everyMs]. */
    var screenOn: () -> Boolean = { true }
    /** When each server's work sweep last ran: it runs at most every 30 s (90 s, screen off), not on every tick (1.4.0). */
    private val sweptAt = mutableMapOf<String, Long>()
    private val detected = mutableSetOf<String>()
    private val retryAt = mutableMapOf<String, Long>()
    private val retryStep = mutableMapOf<String, Long>()

    private val pokes = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Reads every watched server now instead of after the rest of the gap (2.1.4: the drawer opened). */
    fun poke() { pokes.trySend(Unit) }

    /** 2.2.7, for tests: the MSS the watcher's connection to [serverId] would open with now. */
    internal fun clientMss(serverId: String): Int? = clients[serverId]?.first?.activeMss

    /** Starts (or retargets) watching [servers]; the rest are dropped. */
    fun watch(servers: List<Server>) {
        val next = servers.associateBy { it.id }
        val removed = watched.keys.filter { watched[it] != next[it] }.toSet()
        watched = next
        removed.forEach { id ->
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
        if (job?.isActive != true && clients.isNotEmpty()) {
            job = scope.launch {
                while (isActive && clients.isNotEmpty()) {
                    for ((id, pair0) in clients.toMap()) {
                        val server = watched[id] ?: continue
                        // A server that failed waits a growing time before the next try.
                        if ((retryAt[id] ?: 0L) > System.currentTimeMillis()) continue
                        // An Automatic server is looked at once to see which herdr it runs.
                        var pair = pair0
                        if (server.backend == null && id !in detected) {
                            val d = runCatching { com.billmongan.herdrdroid.herdr.detectBackend(pair.first, server.herdrPath) }.getOrNull()
                            if (clients[id] !== pair0 || watched[id] != server) continue
                            if (d != null) {
                                detected += id
                                if (d.coreRunning && !d.tuiRunning) {
                                    pair = pair.first to Herdr(pair.first, server.herdrPath, com.billmongan.herdrdroid.herdr.Backend.CORE, d.hcPath)
                                    clients[id] = pair
                                }
                            }
                        }
                        val st = try {
                            val raw = pair.second.panes()
                            val gap = if (screenOn()) 30_000L else 90_000L
                            val sweepDue = System.currentTimeMillis() - (sweptAt[id] ?: 0L) >= gap
                            val last = states[id]?.busy ?: emptyMap()
                            val busy = if (!sweepDue) Result.success(last) else busyOf?.let { f ->
                                // Counted as swept only once it worked (1.4.1, from a code review): a failed
                                // sweep keeps the last list and is tried again on the next tick.
                                try { Result.success(f(pair.second)).also { sweptAt[id] = System.currentTimeMillis() } } catch (c: CancellationException) { throw c } catch (e: Exception) { Result.failure(e) }
                            } ?: Result.success(emptyMap())
                            FleetState(server, sweptPanes(raw, Result.success(busy.getOrDefault(last))), null, System.currentTimeMillis(), busy.getOrDefault(last))
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            if (clients[id] !== pair || watched[id] != server) continue
                            pair.first.reset()
                            val wait = ((retryStep[id] ?: 15_000L) * 2).coerceAtMost(10 * 60_000L)
                            retryStep[id] = wait
                            retryAt[id] = System.currentTimeMillis() + wait
                            FleetState(server, states[id]?.panes.orEmpty(), e.message ?: "unreachable", System.currentTimeMillis())
                        }
                        if (clients[id] !== pair || watched[id] != server) continue
                        if (st.error == null) { retryAt.remove(id); retryStep.remove(id) }
                        states = states + (id to st)
                        if (st.error == null) onSeen(st)
                    }
                    // 2.1.4: a poke (the drawer just opened) starts the next round at once.
                    withTimeoutOrNull(if (screenOn()) everyMs else everyMs * 3) { pokes.receive() }
                }
            }
        }
    }

    /**
     * Hands over a watched server's live connection (the app is about to open that server), so
     * opening it needs no new sign-in. The watcher makes a fresh one if it watches it again.
     */
    fun take(serverId: String): Pair<HerdrSsh, Herdr>? = clients.remove(serverId)

    /** The herdr client for a watched server, to answer a question there. */
    fun herdr(serverId: String): Herdr? = clients[serverId]?.second

    fun stop() {
        job?.cancel()
        clients.values.forEach { it.first.close() }
        clients.clear()
        states = emptyMap()
    }
}

/** A watched server's panes after its work sweep; a failed sweep leaves herdr's status as it is. */
internal fun sweptPanes(raw: List<Pane>, busy: Result<Map<String, Int>>): List<Pane> =
    com.billmongan.herdrdroid.herdr.withBusy(raw, busy.getOrDefault(emptyMap()))
