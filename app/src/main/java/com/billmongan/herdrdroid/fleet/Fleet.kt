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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.schmizz.sshj.userauth.keyprovider.KeyProvider

/** What one other server's agents are doing, as last seen. */
data class FleetState(val server: Server, val panes: List<Pane>, val error: String?, val at: Long)

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
) {
    var states by mutableStateOf<Map<String, FleetState>>(emptyMap())
        private set
    private val clients = mutableMapOf<String, Pair<HerdrSsh, Herdr>>()
    private var job: Job? = null
    private var watched = mapOf<String, Server>()
    var everyMs = 15_000L

    /** Starts (or retargets) watching [servers]; the rest are dropped. */
    fun watch(servers: List<Server>) {
        watched = servers.associateBy { it.id }
        val ids = watched.keys
        clients.keys.filter { it !in ids }.forEach { id -> clients.remove(id)?.first?.close() }
        states = states.filterKeys { it in ids }
        servers.forEach { s ->
            if (s.id !in clients && s.fingerprint != null) {
                val ssh = HerdrSsh(s.host, s.port, s.user, credentials(s), s.fingerprint, keystoreKey)
                clients[s.id] = ssh to Herdr(ssh, s.herdrPath)
            }
        }
        if (job?.isActive != true && clients.isNotEmpty()) {
            job = scope.launch {
                while (isActive && clients.isNotEmpty()) {
                    for ((id, pair) in clients.toMap()) {
                        val server = watched[id] ?: continue
                        val st = try {
                            FleetState(server, pair.second.panes(), null, System.currentTimeMillis())
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            pair.first.reset()
                            FleetState(server, states[id]?.panes.orEmpty(), e.message ?: "unreachable", System.currentTimeMillis())
                        }
                        states = states + (id to st)
                        if (st.error == null) onSeen(st)
                    }
                    delay(everyMs)
                }
            }
        }
    }

    /** The herdr client for a watched server, to answer a question there. */
    fun herdr(serverId: String): Herdr? = clients[serverId]?.second

    fun stop() {
        job?.cancel()
        clients.values.forEach { it.first.close() }
        clients.clear()
        states = emptyMap()
    }
}
