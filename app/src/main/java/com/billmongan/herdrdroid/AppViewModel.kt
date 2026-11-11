package com.billmongan.herdrdroid

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ssh.HostKeyChanged
import com.billmongan.herdrdroid.ssh.UnconfirmedHostKey
import com.billmongan.herdrdroid.store.PasswordVault
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.store.ServerStore
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Servers : Screen
    data class EditServer(val server: Server?) : Screen
    data object Tabs : Screen
    data class Agent(val paneId: String) : Screen
}

data class PendingHostKey(val server: Server, val password: String, val remember: Boolean, val fingerprint: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ServerStore(app)

    var screen by mutableStateOf<Screen>(Screen.Servers)
    var servers by mutableStateOf(store.all())
        private set
    var current by mutableStateOf<Server?>(null)
        private set
    var connecting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var pendingHostKey by mutableStateOf<PendingHostKey?>(null)
        private set

    var panes by mutableStateOf<List<Pane>>(emptyList())
        private set
    var previews by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var paneText by mutableStateOf("")
        private set
    var lastSent by mutableStateOf<Pair<String, String>?>(null)
        private set

    private var ssh: HerdrSsh? = null
    private var herdr: Herdr? = null

    fun reloadServers() {
        servers = store.all().sortedBy { it.label.lowercase() }
    }

    fun saveServer(server: Server) {
        store.save(server)
        reloadServers()
        screen = Screen.Servers
    }

    fun deleteServer(server: Server) {
        store.delete(server.id)
        reloadServers()
        screen = Screen.Servers
    }

    fun savedPassword(server: Server): String? =
        server.passwordCipher?.let { runCatching { PasswordVault.decrypt(it) }.getOrNull() }

    fun connect(server: Server, password: String, remember: Boolean) {
        if (connecting) return
        connecting = true
        error = null
        viewModelScope.launch {
            val s = HerdrSsh(server.host, server.port, server.user, password, server.fingerprint)
            try {
                val h = Herdr(s, server.herdrPath)
                h.check()
                var saved = server
                if (remember) saved = saved.copy(passwordCipher = PasswordVault.encrypt(password))
                if (saved != server) store.save(saved)
                reloadServers()
                ssh?.close()
                ssh = s
                herdr = h
                current = saved
                panes = emptyList()
                previews = emptyMap()
                screen = Screen.Tabs
                refreshPanes()
            } catch (e: UnconfirmedHostKey) {
                s.close()
                pendingHostKey = PendingHostKey(server, password, remember, e.fingerprint)
            } catch (e: HostKeyChanged) {
                s.close()
                error = "This computer's identity changed since you last connected, so the app stopped. " +
                    "Expected ${e.expected} but got ${e.actual}. If you know why it changed, open the " +
                    "server's settings and forget the saved fingerprint."
            } catch (e: Exception) {
                s.close()
                error = friendly(e)
            } finally {
                connecting = false
            }
        }
    }

    fun acceptHostKey() {
        val p = pendingHostKey ?: return
        pendingHostKey = null
        val pinned = p.server.copy(fingerprint = p.fingerprint)
        store.save(pinned)
        reloadServers()
        connect(pinned, p.password, p.remember)
    }

    fun rejectHostKey() {
        pendingHostKey = null
    }

    fun disconnect() {
        ssh?.close()
        ssh = null
        herdr = null
        current = null
        panes = emptyList()
        screen = Screen.Servers
    }

    suspend fun refreshPanes() {
        val h = herdr ?: return
        try {
            panes = h.panes()
            error = null
        } catch (e: Exception) {
            error = friendly(e)
        }
    }

    suspend fun refreshPreviews() {
        val h = herdr ?: return
        runCatching { previews = h.previews(panes.map { it.id }) }
    }

    suspend fun readPane(paneId: String) {
        val h = herdr ?: return
        try {
            paneText = h.read(paneId)
            error = null
        } catch (e: Exception) {
            error = friendly(e)
        }
    }

    fun openPane(paneId: String) {
        paneText = ""
        screen = Screen.Agent(paneId)
    }

    fun pane(id: String): Pane? = panes.firstOrNull { it.id == id }

    fun sendMessage(pane: Pane, text: String) {
        val h = herdr ?: return
        viewModelScope.launch {
            try {
                h.sendMessage(pane, text)
                lastSent = pane.id to text
                readPane(pane.id)
            } catch (e: Exception) {
                error = "Couldn't send: ${friendly(e)}"
            }
        }
    }

    fun sendKeys(paneId: String, vararg keys: String) {
        val h = herdr ?: return
        viewModelScope.launch {
            try {
                h.sendKeys(paneId, *keys)
                readPane(paneId)
            } catch (e: Exception) {
                error = "Couldn't send the key: ${friendly(e)}"
            }
        }
    }

    fun answer(paneId: String, keys: String, pressEnter: Boolean) {
        val h = herdr ?: return
        viewModelScope.launch {
            try {
                h.sendText(paneId, keys)
                if (pressEnter) h.sendKeys(paneId, "enter")
                readPane(paneId)
                refreshPanes()
            } catch (e: Exception) {
                error = "Couldn't answer: ${friendly(e)}"
            }
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("Auth fail", true) || m.contains("Exhausted available authentication", true) ->
                "The username or password was not accepted."
            m.contains("timed out", true) || m.contains("timeout", true) ->
                "The computer didn't answer. Check the address, the port, and that you're on the right network."
            m.contains("refused", true) -> "The computer refused the connection. Is SSH running on that port?"
            m.contains("UnknownHost", true) || m.contains("Unable to resolve", true) ->
                "That address couldn't be found."
            else -> m
        }
    }

    override fun onCleared() {
        ssh?.close()
        super.onCleared()
    }
}
