package com.billmongan.herdrdroid

import com.billmongan.herdrdroid.transcript.CachedChat
import com.billmongan.herdrdroid.transcript.withRead
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.billmongan.herdrdroid.fleet.Fleet
import com.billmongan.herdrdroid.fleet.FleetState
import com.billmongan.herdrdroid.herdr.DirEntry
import com.billmongan.herdrdroid.herdr.PastSession
import com.billmongan.herdrdroid.herdr.forPhone
import com.billmongan.herdrdroid.transcript.lastReply
import com.billmongan.herdrdroid.transcript.toMarkdown
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.hermes.HermesChat
import com.billmongan.herdrdroid.notify.AppBus
import com.billmongan.herdrdroid.notify.Notify
import com.billmongan.herdrdroid.notify.NotifyAction
import com.billmongan.herdrdroid.notify.WatchService
import com.billmongan.herdrdroid.ui.DarkPalette
import androidx.compose.runtime.mutableStateMapOf
import com.billmongan.herdrdroid.transcript.Item
import com.billmongan.herdrdroid.transcript.Transcript
import com.billmongan.herdrdroid.transcript.UserItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.detectBackend
import com.billmongan.herdrdroid.herdr.HerdrError
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.QuotaProfile
import com.billmongan.herdrdroid.herdr.QuotaProfiles
import com.billmongan.herdrdroid.herdr.screenOpen
import com.billmongan.herdrdroid.herdr.QuotaReading
import com.billmongan.herdrdroid.herdr.footerModel
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ssh.AuthRejected
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ssh.HostKeyChanged
import com.billmongan.herdrdroid.ssh.KeySource
import com.billmongan.herdrdroid.ssh.LinkDown
import com.billmongan.herdrdroid.ssh.UnconfirmedHostKey
import com.billmongan.herdrdroid.ssh.authorizedKeysLine
import com.billmongan.herdrdroid.ssh.readKeyFile
import com.billmongan.herdrdroid.store.AppPrefs
import com.billmongan.herdrdroid.store.PasswordVault
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.store.ServerStore
import com.billmongan.herdrdroid.store.SshKeyVault
import com.billmongan.herdrdroid.ui.ReadingFont
import com.billmongan.herdrdroid.ui.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface Screen {
    data object Servers : Screen
    /** Add or edit a server; a new one can start with an address and name found on the network. */
    data class EditServer(val server: Server?, val prefillHost: String? = null, val prefillName: String? = null) : Screen
    data object Tabs : Screen
    data class Agent(val paneId: String) : Screen
    data class Settings(val back: Screen) : Screen
    data class Files(val path: String, val back: Screen) : Screen
    /**
     * What runs under a pane: processes, subagents and background tasks. With [kind] "sub" or
     * "task", one subagent's conversation or one task's output ([file]), read-only.
     */
    data class Work(val paneId: String, val kind: String? = null, val file: String? = null, val title: String? = null) : Screen
    /** Search every agent's past conversations on this server. */
    data object Search : Screen
    /** Every agent's usage on this server, with a week of history. */
    data object Usage : Screen
    /** The pane's past sessions; with [session] set, that one session, read-only. */
    data class History(val paneId: String, val session: PastSession? = null) : Screen
}

/** Whether the open pane's conversation comes from the agent's own log (ON) or its screen (OFF). */
enum class TranscriptMode { UNKNOWN, ON, OFF }

/** The state of the SSH link while a server is open. Only OFFLINE is shown as a problem. */
enum class Link { CONNECTED, RECONNECTING, OFFLINE }

data class PendingHostKey(val server: Server, val password: String?, val remember: Boolean, val fingerprint: String)

/** A tap the user made while the link was down, sent in order once it is back. */
private class Queued(val paneId: String?, val label: String, val run: suspend (Herdr) -> Unit)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ServerStore(app)
    private val prefs = AppPrefs(app)

    var screen by mutableStateOf<Screen>(Screen.Servers)
    var servers by mutableStateOf(store.all().sortedBy { it.label.lowercase() })
        private set
    var current by mutableStateOf<Server?>(null)
        private set
    var connecting by mutableStateOf(false)
        private set

    /** A problem the user has to act on. Dropped connections never land here. */
    var error by mutableStateOf<String?>(null)
    var pendingHostKey by mutableStateOf<PendingHostKey?>(null)
        private set
    /** Set when the server turned down the key and a password is needed to go on. */
    var needPasswordFor by mutableStateOf<Server?>(null)

    var link by mutableStateOf(Link.CONNECTED)
        private set
    var linkProblem by mutableStateOf<String?>(null)
        private set
    var queuedCount by mutableStateOf(0)
        private set

    var panes by mutableStateOf<List<Pane>>(emptyList())
        private set
    var previews by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** The active model in each pane: read from the agent's footer, or for Claude from /status. */
    var models by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** Reasoning effort read by command where the footer has none (Claude, Hermes). */
    var efforts by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Models seen in this agent's panes, now and before (newest first), for the model menu. */
    fun knownModels(agent: String?): List<String> {
        val a = agent?.lowercase() ?: return emptyList()
        val stored = runCatching { org.json.JSONObject(prefs.seenModels).optJSONArray(a) }.getOrNull()
        val before = (0 until (stored?.length() ?: 0)).map { stored!!.getString(it) }
        val now = panes.filter { it.agent?.lowercase() == a }.mapNotNull { models[it.id] }
        return (now + before).filter { it.isNotBlank() && !it.contains(' ') }.distinct().take(8)
    }

    private fun noteModels() {
        runCatching {
            val all = org.json.JSONObject(prefs.seenModels)
            for (p in panes) {
                val m = models[p.id]?.takeIf { it.isNotBlank() && !it.contains(' ') } ?: continue
                val a = p.agent?.lowercase() ?: continue
                val arr = all.optJSONArray(a) ?: org.json.JSONArray()
                val list = listOf(m) + (0 until arr.length()).map { arr.getString(it) }.filter { it != m }
                all.put(a, org.json.JSONArray(list.take(8)))
            }
            prefs.seenModels = all.toString()
        }
    }

    /** The model line for a card: the model, plus the effort when it isn't already part of it. */
    fun modelLine(paneId: String): String? {
        val m = models[paneId]
        val e = efforts[paneId]
        return when {
            m == null -> e?.let { "$it effort" }
            e == null || m.contains(e, ignoreCase = true) -> m
            else -> "$m · $e"
        }
    }
    var paneText by mutableStateOf("")
        private set
    /** The pane [paneText] was read from: a read still on its way when you switch agents is dropped. */
    var paneTextPane by mutableStateOf<String?>(null)
        private set
    var lastSent by mutableStateOf<Pair<String, String>?>(null)
        private set
    private var lastSentAt = 0L

    // ---- The open pane's structured conversation ----------------------------------------------
    var transcriptItems by mutableStateOf<List<Item>>(emptyList())
        private set
    var transcriptState by mutableStateOf(TranscriptMode.UNKNOWN)
        private set
    var transcriptMore by mutableStateOf(false)
        private set
    var transcriptTitle by mutableStateOf<String?>(null)
        private set
    private var transcriptPane: String? = null
    private var transcriptCursor = ""
    private val transcriptScript: ByteArray by lazy {
        getApplication<Application>().assets.open("transcript.py").use { it.readBytes() }
    }
    /** True while a file picked in the message box is being copied to the computer. */
    var uploading by mutableStateOf(false)
        private set
    private var hermes: HermesChat? = null

    var themeMode by mutableStateOf(runCatching { ThemeMode.valueOf(prefs.theme) }.getOrDefault(ThemeMode.SYSTEM))
        private set
    // Usage limits always show on the cards (Bill, 2026-11-12); the old switch is gone.
    var showQuotas by mutableStateOf(true)
        private set
    var readingFont by mutableStateOf(runCatching { ReadingFont.valueOf(prefs.readingFont) }.getOrDefault(ReadingFont.SANS))
        private set
    var showKeys by mutableStateOf(prefs.showKeys)
        private set
    /** The latest usage reading for each kind of agent on the open server. */
    var quotas by mutableStateOf<Map<String, QuotaReading>>(emptyMap())
        private set

    private var ssh: HerdrSsh? = null
    private var herdr: Herdr? = null
    private var foreground = true
    private var backgroundAt = 0L
    private var downSince = 0L
    private var reconnectJob: Job? = null
    private var quotaJob: Job? = null
    private val queue = ArrayDeque<Queued>()
    private val quotaLock = Mutex()
    private val lastQuotaTry = mutableMapOf<String, Long>()
    /**
     * Panes a usage check is typing into right now ("server/pane"), shared by the open server's
     * round and the background server checks, so two checks never type into one pane at once
     * (that sent "/usage/reasoning" to Hermes as one message, 2026-11-13).
     */
    private val typingInto = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** When a background check last tried each agent ("server/agent"), read or not, so a screen with no limits on it isn't asked again every round. */
    private val backgroundTry = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** The pane whose usage is being checked right now, shown on its card as "Checking usage…". */
    var probingPanes by mutableStateOf<Set<String>>(emptySet())
        private set
    private var textSizeJob: Job? = null

    // Reads go on in the background only while the user has alerts ("Watching") turned on.
    // A WebSocket server is read only while the app is in front: an open connection keeps it awake.
    private val canPoll get() = herdr != null && (foreground || (watching && current?.overWebSocket != true)) && link == Link.CONNECTED

    // ---- Servers ----------------------------------------------------------------------------

    fun reloadServers() {
        servers = store.all().sortedBy { it.label.lowercase() }
    }

    fun saveServer(server: Server) {
        store.save(server)
        reloadServers()
        if (current?.id == server.id) current = server
        screen = Screen.Servers
    }

    fun deleteServer(server: Server) {
        // Its kept chats go with it, from memory and from the phone (1.2.9).
        transcriptCache.keys.filter { it.startsWith("${server.id}|") }.forEach { transcriptCache.remove(it) }
        viewModelScope.launch(Dispatchers.IO) { chatStore?.removeServer(server.id) }
        store.delete(server.id)
        SshKeyVault.delete(server.keystoreAlias)
        prefs.forgetQuotas(server.id)
        reloadServers()
        screen = Screen.Servers
    }

    fun savedPassword(server: Server): String? =
        server.passwordCipher?.let { runCatching { PasswordVault.decrypt(it) }.getOrNull() }

    /** True when the server can be opened without typing anything: a key in use, or a saved password. */
    fun canSignInSilently(server: Server) = (server.useKey && server.hasKey) || server.passwordCipher != null

    // ---- Keys -------------------------------------------------------------------------------

    private fun keyComment() = "herdrdroid@" + (Build.MODEL ?: "android").replace(' ', '-').lowercase()

    /** Makes a key for this server inside the phone's Keystore and returns its authorized_keys line. */
    fun generateKey(serverId: String): String? {
        val alias = "herdrdroid_ssh_$serverId"
        SshKeyVault.delete(alias)
        return runCatching {
            SshKeyVault.generate(alias)
            SshKeyVault.publicKey(alias)?.let { authorizedKeysLine(it, keyComment()) }
        }.getOrElse {
            error = "Couldn't make a key on this phone: ${it.message}"
            null
        }
    }

    fun publicKeyLine(serverId: String): String? =
        SshKeyVault.publicKey("herdrdroid_ssh_$serverId")?.let { authorizedKeysLine(it, keyComment()) }

    fun forgetGeneratedKey(serverId: String) = SshKeyVault.delete("herdrdroid_ssh_$serverId")

    /** The authorized_keys line for a server's current key, if it has one. */
    fun keyLineFor(server: Server): String? = when (server.keyKind) {
        "keystore" -> publicKeyLine(server.id)
        "imported" -> server.keyCipher?.let { c ->
            runCatching {
                val pass = server.keyPassCipher?.let { PasswordVault.decrypt(it) }
                authorizedKeysLine(readKeyFile(PasswordVault.decrypt(c), pass), "imported")
            }.getOrNull()
        }
        else -> null
    }

    /** Checks an imported key file opens (with its passphrase) and returns its authorized_keys line. */
    fun checkKeyFile(pem: String, passphrase: String?): Result<String> = runCatching {
        authorizedKeysLine(readKeyFile(pem, passphrase), "imported")
    }.recoverCatching { throw IllegalArgumentException("That key couldn't be opened. Check the passphrase. (${it.message})") }

    // ---- Settings ---------------------------------------------------------------------------

    fun setTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.theme = mode.name
    }

    fun chooseReadingFont(f: ReadingFont) {
        readingFont = f
        prefs.readingFont = f.name
    }

    fun setKeysShown(on: Boolean) {
        showKeys = on
        prefs.showKeys = on
    }

    fun setQuotasShown(on: Boolean) {
        showQuotas = on
        prefs.showQuotas = on
        if (on && herdr != null) startQuotaChecks()
        if (!on) quotaJob?.cancel()
    }

    /** Pinch-to-zoom on the agent screen; saved for this server a moment after the pinch ends. */
    fun setTextSize(size: Float) {
        val s = current ?: return
        val v = size.coerceIn(8f, 24f)
        current = s.copy(textSize = v)
        textSizeJob?.cancel()
        textSizeJob = viewModelScope.launch {
            delay(700)
            current?.let { store.save(it); reloadServers() }
        }
    }

    // ---- Connecting -------------------------------------------------------------------------

    private fun credentialsFor(server: Server, password: String?): Credentials {
        val key = if (!server.useKey) null else when (server.keyKind) {
            "keystore" -> KeySource.Keystore(server.keystoreAlias)
            "imported" -> server.keyCipher?.let { c ->
                runCatching {
                    KeySource.Imported(PasswordVault.decrypt(c), server.keyPassCipher?.let { PasswordVault.decrypt(it) })
                }.getOrNull()
            }
            else -> null
        }
        return Credentials(key, password)
    }

    fun connect(server: Server, password: String?, remember: Boolean, openAgent: Boolean = false) {
        if (connecting) return
        connecting = true
        error = null
        val run = ++connectRun
        connectJob = viewModelScope.launch {
            val creds = credentialsFor(server, password)
            val s = HerdrSsh(server.host, server.port, server.user, creds, server.fingerprint, SshKeyVault::provider, server.wsUrl?.takeIf { server.overWebSocket })
            connectingSsh = s
            try {
                // Back to the server just left: its connection is kept open for a few minutes, so
                // it is used again at once after one quick check, with no sign-in or detection.
                // Or the watcher's own live connection to it, so switching servers from the drawer
                // is quick too.
                val kept = parked?.takeIf { it.serverId == server.id && System.currentTimeMillis() - it.at < PARK_MILLIS }
                    ?: fleet?.take(server.id)?.let { (fs, fh) -> Parked(server.id, fs, fh, fh.backend, System.currentTimeMillis()) }
                parked = null
                parkJob?.cancel()
                val reused = kept != null && runCatching { kotlinx.coroutines.withTimeout(6_000) { kept.herdr.check() } }.isSuccess
                if (kept != null && !reused) kept.ssh.close()
                val sNow: HerdrSsh
                val h: Herdr
                if (reused) {
                    com.billmongan.herdrdroid.diag.DiagLog.log("connect", "reusing the connection kept from a moment ago")
                    s.close()
                    sNow = kept!!.ssh
                    h = kept.herdr
                    activeBackend = kept.backend
                } else {
                    sNow = s
                    // Which herdr: the server's choice, or whichever one is running there.
                    val found = detectBackend(s, server.herdrPath)
                    val backend = when (server.backend) {
                        "tui" -> Backend.TUI
                        "core" -> Backend.CORE
                        else -> when {
                            found.tuiRunning && found.coreRunning -> {
                                s.close()
                                pendingBackendChoice = PendingBackend(server, password, remember)
                                return@launch
                            }
                            found.coreRunning -> Backend.CORE
                            found.tuiRunning -> Backend.TUI
                            found.tuiInstalled -> Backend.TUI
                            found.coreInstalled -> Backend.CORE
                            else -> Backend.TUI
                        }
                    }
                    com.billmongan.herdrdroid.diag.DiagLog.log("connect", "herdr on this computer: TUI ${if (found.tuiRunning) "running" else if (found.tuiInstalled) "installed" else "absent"}, " +
                        "herdr-core ${if (found.coreRunning) "running" else if (found.coreInstalled) "installed" else "absent"}; using ${if (backend == Backend.CORE) "herdr-core" else "herdr"}")
                    activeBackend = backend
                    h = Herdr(s, server.herdrPath, backend, found.hcPath)
                    // A WebSocket server may be asleep and take a while to answer, so a failed
                    // connection is tried again with growing waits before it is reported.
                    var wait = 2_000L
                    var tries = 0
                    while (true) {
                        try {
                            h.check()
                            break
                        } catch (e: LinkDown) {
                            if (!server.overWebSocket || ++tries > 4) throw e
                            connectNote = "The server didn't answer yet (it may be waking up). Trying again in ${wait / 1000} s…"
                            delay(wait)
                            wait *= 2
                        }
                    }
                }
                connectNote = null
                var saved = server
                if (remember && password != null) saved = saved.copy(passwordCipher = PasswordVault.encrypt(password))
                if (saved != server) store.save(saved)
                reloadServers()
                closeSession()
                ssh = sNow
                herdr = h
                current = saved
                link = Link.CONNECTED
                linkProblem = null
                // What was last seen on this server shows at once; fresh reads replace it.
                panes = serverPanes[saved.id].orEmpty()
                previews = previewCache[saved.id].orEmpty()
                models = modelCache[saved.id].orEmpty()
                efforts = emptyMap()
                quotas = prefs.quotas(saved.id).let { q -> q + serverQuotas[saved.id].orEmpty().filter { (k, r) -> (q[k]?.at ?: 0L) < r.at } }
                lastQuotaTry.clear()
                unlockedThisSession += saved.id
                lastStatus.clear()
                // From the drawer or at startup the agent opens directly, with no stop on the tab list.
                if (!openAgent) screen = Screen.Tabs
                transcriptPane = null
                refreshPanes()
                prefetchChats()
                if (showQuotas) startQuotaChecks()
                startWatching()
                pollHermes()
                val want = pendingOpen
                pendingOpen = null
                when {
                    want != null && panes.any { it.id == want } -> openPane(want, "after connecting")
                    // From the drawer or at startup: straight to an agent, the one most worth seeing.
                    openAgent -> bestAgent()?.let { openPane(it, "after connecting") } ?: run { screen = Screen.Tabs; drawerOpen = true }
                }
            } catch (e: UnconfirmedHostKey) {
                s.close()
                pendingHostKey = PendingHostKey(server, password, remember, e.fingerprint)
            } catch (e: HostKeyChanged) {
                s.close()
                error = hostKeyMessage(e)
            } catch (e: AuthRejected) {
                s.close()
                if (server.overWebSocket) {
                    // Key only: there is no password to fall back to.
                    error = authMessage(server, creds, e)
                } else if (password == null && creds.key != null) {
                    error = "The server didn't accept this phone's key. Enter the password to sign in, " +
                        "or add the key to authorized_keys (it's in the server's settings)."
                    needPasswordFor = server
                } else {
                    error = authMessage(server, creds, e)
                }
            } catch (c: CancellationException) {
                // Cancel on the connecting screen: drop the attempt quietly.
                s.close()
                throw c
            } catch (e: Exception) {
                s.close()
                error = friendly(e)
            } finally {
                // A cancelled attempt may finish late; it must not touch a newer one.
                if (run == connectRun) {
                    connecting = false
                    connectNote = null
                    connectingSsh = null
                }
            }
        }
    }

    private var connectJob: Job? = null
    private var connectRun = 0
    private var connectingSsh: HerdrSsh? = null

    /**
     * Stops a connection attempt, including its wake-up retries. The screen is freed at once; an
     * attempt still waiting on the network is closed and its result thrown away.
     */
    fun cancelConnect() {
        connectRun++
        connectJob?.cancel()
        connectJob = null
        connectingSsh?.close()
        connectingSsh = null
        connecting = false
        connectNote = null
        pendingOpen = null
    }

    // -- Server status lights ---------------------------------------------------------------------

    enum class Health { NEEDS_YOU, UP, DOWN, LOCKED, CHECKING }

    /** One server's light on the server list, and a short line saying why. */
    data class ServerHealth(val health: Health, val line: String, val at: Long)

    var serverHealth by mutableStateOf<Map<String, ServerHealth>>(emptyMap())
        private set
    private val healthLock = Mutex()

    /**
     * Checks every saved server once (the server list does this about once a minute while it is
     * on screen): a short SSH connection, the agent list, then disconnect. Servers that would
     * need a typed password or a fingerprint first are not contacted.
     */
    suspend fun checkServers() = healthLock.withLock {
        val now = System.currentTimeMillis()
        val jobs = servers.map { s ->
            viewModelScope.launch {
                // A server that just failed waits out its back-off; its last light stays.
                if (s.id != current?.id && backingOff(s.id)) return@launch
                if (s.fingerprint == null || !canSignInSilently(s)) {
                    // It can't be signed in to without asking, but whether it answers at all can be
                    // checked: a plain connection with no sign-in. Red when it doesn't answer.
                    val answers = reachable(s)
                    serverHealth = serverHealth + (s.id to when {
                        !answers -> ServerHealth(Health.DOWN, "Can't reach it", now)
                        s.fingerprint == null -> ServerHealth(Health.LOCKED, "Reachable · not connected yet", now)
                        else -> ServerHealth(Health.LOCKED, "Reachable · sign in to see agents", now)
                    })
                    return@launch
                }
                serverQuotas = serverQuotas + (s.id to prefs.quotas(s.id))
                val panes: List<Pane>? = if (s.id == current?.id && herdr != null) panes else runCatching {
                    val ssh = HerdrSsh(s.host, s.port, s.user, credentialsFor(s, savedPassword(s)), s.fingerprint, SshKeyVault::provider, s.wsUrl?.takeIf { s.overWebSocket })
                    try {
                        val d = if (s.backend == null) detectBackend(ssh, s.herdrPath) else null
                        val core = s.backend == "core" || (d != null && d.coreRunning && !d.tuiRunning)
                        val h = Herdr(ssh, s.herdrPath, if (core) Backend.CORE else Backend.TUI, d?.hcPath)
                        // Agents whose subagents or background shells still run count as working
                        // here too, so the server list says "working" before its cards are opened.
                        val list = withBusy(h.panes(), runCatching { h.workCounts(h.panes0Cache, transcriptScript).second }.getOrDefault(emptyMap()))
                        // The light goes up now; the usage checks that are due follow in the
                        // background on this same connection, then it closes.
                        // Usage that is due there is checked on every background round, not only at
                        // startup, so every server's cards have current usage ready.
                        val due = s.id !in startupQuotasDone
                        if (due) viewModelScope.launch {
                            try { runCatching { startupQuotas(s, h, list) } } finally { ssh.close() }
                        }
                        if (!due) ssh.close()
                        list
                    } catch (e: Exception) {
                        ssh.close()
                        throw e
                    }
                }.getOrNull()
                panes?.let { serverPanes = serverPanes + (s.id to it) }
                if (s.id != current?.id) { if (panes == null) failed(s.id) else succeeded(s.id) }
                val h = when {
                    panes == null -> ServerHealth(Health.DOWN, "Can't reach it", now)
                    panes.any { it.status == Status.NEEDS_YOU } ->
                        ServerHealth(Health.NEEDS_YOU, "${panes.count { it.status == Status.NEEDS_YOU }} need you · ${panes.count { it.status == Status.WORKING }} working", now)
                    else -> {
                        val w = panes.count { it.status == Status.WORKING }
                        ServerHealth(Health.UP, if (w > 0) "Up · $w working" else "Up · agents idle", now)
                    }
                }
                serverHealth = serverHealth + (s.id to h)
            }
        }
        jobs.forEach { it.join() }
    }

    // -- The app lock ------------------------------------------------------------------------------

    /** Ask for the fingerprint when the app opens (one setting for the whole app). */
    var appLock by mutableStateOf(prefs.appLock ?: servers.any { it.biometric })
        private set
    /** True until the fingerprint is given, when the lock is on. Nothing is contacted while locked. */
    var locked by mutableStateOf(appLock)
        private set

    fun changeAppLock(on: Boolean) {
        appLock = on
        prefs.appLock = on
    }

    fun unlocked() {
        locked = false
        startBackgroundChecks()
        autoOpen()
        pendingNotification?.let { (s, p) -> pendingNotification = null; openFromNotification(s, p) }
    }

    private var pendingNotification: Pair<String, String>? = null

    private var backgroundChecks: Job? = null

    /**
     * Every server's agents, read in the background about once a minute while the app is in
     * front, and kept: the server list, the machine menu and each server's cards show them at once.
     */
    fun startBackgroundChecks() {
        if (backgroundChecks?.isActive == true || locked) return
        ensureFleet()
        backgroundChecks = viewModelScope.launch {
            while (isActive) {
                if (foreground) runCatching { checkServers() }
                delay(60_000)
            }
        }
    }

    // -- Every server at startup: agents, models and usage ------------------------------------------

    /** The agents last seen on each server, for the server list. */
    var serverPanes by mutableStateOf<Map<String, List<Pane>>>(emptyMap())
        private set
    /** The usage readings saved for each server, for the server list. */
    var serverQuotas by mutableStateOf<Map<String, Map<String, QuotaReading>>>(emptyMap())
        private set
    private val startupQuotasDone = mutableSetOf<String>()

    /**
     * The usage readings that are due on another server, checked on each background round: the same
     * checks as the open server's (idle agents only; Enter only when the input line holds exactly
     * the command; Oh My Pi from its command line). Readings are saved for when you open it.
     */
    private suspend fun startupQuotas(s: Server, h: Herdr, fresh: List<Pane>) {
        // One check at a time per server; each reading is asked for again only when it is due.
        if (!startupQuotasDone.add(s.id)) return
        try { startupQuotasRun(s, h, fresh) } finally { startupQuotasDone.remove(s.id) }
    }

    private suspend fun startupQuotasRun(s: Server, h: Herdr, fresh: List<Pane>) {
        val stored = prefs.quotas(s.id).toMutableMap()
        val now = System.currentTimeMillis()
        val byAgent = fresh.mapNotNull { p -> QuotaProfiles.key(p.agent)?.let { it to p } }.groupBy({ it.first }, { it.second })
        for ((agentKey, list) in byAgent) {
            val profile = QuotaProfiles.forAgent(list.first().agent) ?: continue
            val every = profile.everyMinutes * 60_000L
            if (maxOf(stored[agentKey]?.at ?: 0L, backgroundTry["${s.id}/$agentKey"] ?: 0L) > now - every) continue
            // The open server's own round handles its agents; a check from here too could type into the same pane.
            if (s.id == current?.id && profile.cliCommand == null) continue
            backgroundTry["${s.id}/$agentKey"] = now
            val items = if (profile.cliCommand != null) {
                runCatching { h.cliQuota(profile) }.getOrNull()
            } else if (profile.screenUsage != null) {
                // Read off the screen (Vibe's footer): nothing typed, any status will do.
                list.firstNotNullOfOrNull { p -> runCatching { h.screenQuota(p.id, profile) }.getOrNull()?.takeIf { it.isNotEmpty() } }
            } else {
                if (!h.canProbeScreens) continue
                val target = list.filter { it.status == Status.IDLE || it.status == Status.DONE }.sortedBy { it.focused }.firstOrNull() ?: continue
                val typingKey = "${s.id}/${target.id}"
                if (!typingInto.add(typingKey)) continue
                probedAt["${s.id}|${target.id}"] = System.currentTimeMillis()
                try { runCatching { h.probeQuota(target.id, profile, working = false) }.getOrNull()?.also { r ->
                    r.model?.let { m -> modelCache[s.id] = modelCache[s.id].orEmpty() + (target.id to m) }
                }?.items } finally {
                    typingInto.remove(typingKey)
                    probedAt["${s.id}|${target.id}"] = System.currentTimeMillis()
                }
            }
            if (!items.isNullOrEmpty()) {
                val r = QuotaReading(System.currentTimeMillis(), items)
                stored[agentKey] = r
                recordQuota(s.id, agentKey, r)
            }
        }
        prefs.saveQuotas(s.id, stored)
        // Models for the drawer, read only (nothing is typed): every agent's from the footer it
        // draws, and Claude's (whose footer has none) from its session log.
        val withAgent = fresh.filter { it.agent != null }
        val fromFooters = runCatching { h.previews(withAgent.map { it.id }) }.getOrNull().orEmpty()
            .mapNotNull { (id, peek) -> footerModel(withAgent.firstOrNull { it.id == id }?.agent, peek.tail)?.let { id to it } }.toMap()
        val fromLogs = runCatching { h.logModels(fresh) }.getOrNull().orEmpty()
        if (fromFooters.isNotEmpty() || fromLogs.isNotEmpty()) {
            modelCache[s.id] = modelCache[s.id].orEmpty() + fromFooters + fromLogs
            modelsVersion++
        }
        serverQuotas = serverQuotas + (s.id to stored.toMap())
        // The open server's cards take the new readings at once.
        if (s.id == current?.id) quotas = quotas + stored.filter { (k, r) -> (quotas[k]?.at ?: 0L) < r.at }
    }

    /**
     * Whether a server answers, without signing in: a TCP connection to its SSH port, or for a
     * WebSocket server the upgrade (closed at once). Ten seconds at most.
     */
    /** Servers that failed recently, and when to try them again (1, 2, 4… up to 10 minutes). */
    private val backoff = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

    private fun backingOff(id: String) = (backoff[id]?.first ?: 0L) > System.currentTimeMillis()
    private fun failed(id: String) {
        val step = (backoff[id]?.second ?: 30_000L) * 2
        val wait = step.coerceAtMost(10 * 60_000L)
        backoff[id] = (System.currentTimeMillis() + wait) to wait
        com.billmongan.herdrdroid.diag.DiagLog.log("check", "server ${servers.firstOrNull { it.id == id }?.label} unreachable; next try in ${wait / 1000} s")
    }
    private fun succeeded(id: String) { backoff.remove(id) }

    private suspend fun reachable(s: Server): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (s.overWebSocket) {
                com.billmongan.herdrdroid.ssh.WsBridge.open(s.wsUrl!!, timeoutMs = 10_000).close("reachability check")
            } else {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(s.host, s.port), 10_000) }
            }
            true
        }.getOrDefault(false)
    }

    /** Both herdr and herdr-core run on a server set to Automatic: the user picks one. */
    data class PendingBackend(val server: Server, val password: String?, val remember: Boolean)

    var pendingBackendChoice by mutableStateOf<PendingBackend?>(null)
        private set
    /** Which herdr the open server is driven through. */
    var activeBackend by mutableStateOf(Backend.TUI)
        private set

    /** Saves the choice for that server and connects with it. */
    fun chooseBackend(core: Boolean) {
        val p = pendingBackendChoice ?: return
        pendingBackendChoice = null
        val saved = p.server.copy(backend = if (core) "core" else "tui")
        store.save(saved)
        reloadServers()
        connect(saved, p.password, p.remember)
    }

    /**
     * Both: the server stays as the herdr (TUI) entry, and a second entry for the same computer
     * shows its Herdr App (herdr-core) agents. Each keeps its own agents; the drawer lists both.
     */
    fun chooseBothBackends() {
        val p = pendingBackendChoice ?: return
        pendingBackendChoice = null
        val tui = p.server.copy(backend = "tui", name = p.server.name.ifBlank { p.server.host })
        val core = tui.copy(
            id = java.util.UUID.randomUUID().toString(), backend = "core",
            name = tui.name.removeSuffix(" (herdr)") + " (Herdr App)",
        )
        store.save(tui)
        store.save(core)
        reloadServers()
        connect(tui, p.password, p.remember)
    }

    fun dismissBackendChoice() { pendingBackendChoice = null }

    /** Shown while a sleeping server is being retried. */
    var connectNote by mutableStateOf<String?>(null)
        private set

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

    /** A connection kept open after leaving a server, so going back to it is quick. */
    private class Parked(val serverId: String, val ssh: HerdrSsh, val herdr: Herdr, val backend: Backend, val at: Long)
    private var parked: Parked? = null
    private var parkJob: Job? = null
    private val PARK_MILLIS = 5 * 60_000L

    /** Keeps the open server's connection for [PARK_MILLIS], then closes it if unused. */
    private fun park() {
        val s = ssh ?: return
        val h = herdr ?: return
        val id = current?.id ?: return
        parked?.ssh?.close()
        parked = Parked(id, s, h, activeBackend, System.currentTimeMillis())
        ssh = null
        herdr = null
        parkJob?.cancel()
        parkJob = viewModelScope.launch {
            delay(PARK_MILLIS)
            parked?.let { if (it.serverId == id) { it.ssh.close(); parked = null } }
        }
    }

    private fun closeSession() {
        // The open chat is kept before the connection goes, so a reconnect shows it at once (1.2.9).
        stashOpenChat()
        hermesPoll?.cancel()
        hermesState = null
        closeJob?.cancel()
        closeWatch.clear()
        hermes?.close()
        hermes = null
        reconnectJob?.cancel()
        quotaJob?.cancel()
        queue.clear()
        queuedCount = 0
        ssh?.close()
        ssh = null
        herdr = null
    }

    /**
     * Leaves the open server without going back to the server list (switching to an agent on
     * another server): its connection is parked for a quick return and the watcher takes it over.
     */
    private fun disconnectQuietly() {
        watchJob?.cancel()
        park()
        closeSession()
        current = null
        panes = emptyList()
        quotas = emptyMap()
        link = Link.CONNECTED
        linkProblem = null
    }

    fun disconnect() {
        fleet?.stop()
        fleet = null
        watchJob?.cancel()
        WatchService.stop(getApplication())
        park()
        closeSession()
        current = null
        panes = emptyList()
        quotas = emptyMap()
        link = Link.CONNECTED
        linkProblem = null
        screen = Screen.Servers
    }

    // ---- Staying connected ------------------------------------------------------------------

    /** The app is visible again. After more than a few seconds away the old socket is likely dead,
     *  so it is replaced before any poll can stall on it. */
    fun onForeground() {
        foreground = true
        if (watchOthers && herdr != null) fleet?.watch(watchable())
        if (herdr != null && backgroundAt > 0 && System.currentTimeMillis() - backgroundAt > 8_000) {
            ssh?.reset()
            linkLost(null)
        }
    }

    fun onBackground() {
        foreground = false
        backgroundAt = System.currentTimeMillis()
        // The open chat is written to the phone, so reopening the app shows it at once (1.2.9).
        val open = transcriptPane
        val sid = current?.id
        if (open != null && sid != null && transcriptState == TranscriptMode.ON && transcriptCursor.isNotEmpty()) persistChat(sid, open, openChatNow(open))
        // Other servers reached over a WebSocket are let go too (watchable() now leaves them out).
        fleet?.watch(watchable())
        // Close a WebSocket connection right away: holding it open would keep that server awake.
        if (current?.overWebSocket == true) {
            hermes?.close()
            hermes = null
            ssh?.reset()
        }
    }

    private fun linkLost(e: Exception?) {
        if (herdr == null) return
        if (link == Link.CONNECTED) {
            link = Link.RECONNECTING
            downSince = System.currentTimeMillis()
        }
        e?.let { linkProblem = friendly(it) }
        startReconnect()
    }

    private fun startReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            var wait = 1_000L
            while (isActive) {
                val h = herdr ?: return@launch
                if (!foreground) {
                    delay(1_000)
                    continue
                }
                try {
                    ssh?.reset()
                    h.check()
                    link = Link.CONNECTED
                    linkProblem = null
                    flushQueue()
                    refreshPanes()
                    return@launch
                } catch (c: CancellationException) {
                    throw c
                } catch (e: AuthRejected) {
                    stopForProblem(authMessage(current, null, e))
                    return@launch
                } catch (e: HostKeyChanged) {
                    stopForProblem(hostKeyMessage(e))
                    return@launch
                } catch (e: Exception) {
                    linkProblem = friendly(e)
                    if (System.currentTimeMillis() - downSince > 15_000) link = Link.OFFLINE
                }
                delay(wait)
                wait = (wait * 2).coerceAtMost(8_000)
            }
        }
    }

    /** The Retry button on the offline banner. */
    fun retryNow() {
        reconnectJob?.cancel()
        link = Link.RECONNECTING
        downSince = System.currentTimeMillis()
        startReconnect()
    }

    private fun stopForProblem(message: String) {
        val server = current
        disconnect()
        error = message
        if (server != null) reloadServers()
    }

    /** Runs a background read. A dropped link turns into a quiet reconnect; nothing is shown. */
    private suspend fun <T> quietly(block: suspend (Herdr) -> T): T? {
        if (!canPoll) return null
        val h = herdr ?: return null
        return try {
            block(h)
        } catch (c: CancellationException) {
            throw c
        } catch (e: LinkDown) {
            linkLost(e); null
        } catch (e: AuthRejected) {
            stopForProblem(authMessage(current, null, e)); null
        } catch (e: HostKeyChanged) {
            stopForProblem(hostKeyMessage(e)); null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Runs something the user tapped. While the link is down it waits in a queue and goes out once
     * the link is back; only a real refusal from herdr is shown.
     */
    private fun act(paneId: String?, label: String, run: suspend (Herdr) -> Unit) {
        viewModelScope.launch {
            if (link != Link.CONNECTED || herdr == null) {
                enqueue(Queued(paneId, label, run))
                return@launch
            }
            perform(Queued(paneId, label, run))
        }
    }

    private suspend fun perform(q: Queued) {
        val h = herdr ?: return
        // Never type into a pane while the app is checking that agent's usage.
        var waited = 0
        // A usage check types into the pane and closes its screen with Esc: a message typed then
        // would be lost, so it waits for the check to finish completely.
        if (q.paneId != null && q.paneId in probingPanes) com.billmongan.herdrdroid.diag.DiagLog.log("send", "waiting for a usage check in ${q.paneId} to finish")
        while (q.paneId != null && q.paneId in probingPanes && waited < 60_000) {
            delay(200); waited += 200
        }
        try {
            q.run(h)
        } catch (c: CancellationException) {
            throw c
        } catch (e: LinkDown) {
            enqueue(q)
            linkLost(e)
        } catch (e: AuthRejected) {
            stopForProblem(authMessage(current, null, e))
        } catch (e: HostKeyChanged) {
            stopForProblem(hostKeyMessage(e))
        } catch (e: HerdrError) {
            error = "${q.label}: ${e.message}"
        } catch (e: Exception) {
            error = "${q.label}: ${friendly(e)}"
        }
    }

    private fun enqueue(q: Queued) {
        com.billmongan.herdrdroid.diag.DiagLog.log("send", "queued until the link is back: ${q.label}")
        queue.addLast(q)
        queuedCount = queue.size
        if (herdr != null && link == Link.CONNECTED) linkLost(null)
    }

    private suspend fun flushQueue() {
        while (queue.isNotEmpty() && link == Link.CONNECTED) {
            val q = queue.removeFirst()
            queuedCount = queue.size
            perform(q)
        }
    }

    // ---- Panes ------------------------------------------------------------------------------

    private val agentSeenAt = mutableMapOf<String, Long>()

    suspend fun refreshPanes() {
        quietly { it.panes() }?.let { fresh0 ->
            // herdr sometimes reports a pane's agent as missing for a moment (while it redraws, or
            // just after a screen closes). Keep the agent last seen in that pane, so its card keeps
            // its usage and model instead of going blank until the next read.
            val before = panes.associateBy { it.id }
            val t = System.currentTimeMillis()
            fresh0.forEach { p -> if (p.agent != null) agentSeenAt[p.id] = t }
            val fresh = fresh0.map { p ->
                val was = before[p.id]?.agent
                // Only for a minute: an agent that really quit leaves a plain shell behind.
                if (p.agent == null && was != null && t - (agentSeenAt[p.id] ?: 0L) < 60_000) {
                    com.billmongan.herdrdroid.diag.DiagLog.log("pane", "${p.id}: herdr reported no agent; keeping $was")
                    p.copy(agent = was)
                } else p
            }
            current?.id?.let { trackDone(it, fresh) }
            maintainChats(before, fresh)
            panes = withBusy(fresh, busyCounts)
            current?.let { s -> noticeChanges(s.id, s.label, fresh) { herdr } }
            // Footers (each agent's model) every 30 seconds wherever you are in the app; before
            // 1.1.5 only the tab list read them, which the agent-first app rarely shows.
            // What runs under each agent (subagents, background tasks, commands it started) every
            // 6 seconds wherever you are, so an agent whose shell is still busy shows as working.
            if (System.currentTimeMillis() - lastWorkAt > 6_000L) {
                lastWorkAt = System.currentTimeMillis()
                viewModelScope.launch { runCatching { refreshWorkCounts() } }
            }
            if (System.currentTimeMillis() - lastPreviewsAt > 30_000L) {
                lastPreviewsAt = System.currentTimeMillis()
                viewModelScope.launch { runCatching { refreshPreviews() } }
            }
            updateWidget(fresh)
            current?.let { serverPanes = serverPanes + (it.id to panes) }
        }
    }

    private var widgetSig = ""

    /** Saves what the home-screen widget shows and redraws it, when something changed. */
    private fun updateWidget(fresh: List<Pane>) {
        val s = current ?: return
        val agents = fresh.filter { it.agent != null }
        val lines = agents.take(5).joinToString("\n") { p ->
            val mark = when (p.status) { Status.NEEDS_YOU -> "● "; Status.WORKING -> "◐ "; else -> "○ " }
            mark + p.agentName + (if (p.folder.isNotBlank()) " · " + p.folder else "") + " · " + com.billmongan.herdrdroid.ui.look(p.status).label
        }
        val needs = agents.count { it.status == Status.NEEDS_YOU }
        val working = agents.count { it.status == Status.WORKING }
        val sig = "${s.id}|$needs|$working|$lines"
        if (sig == widgetSig) return
        widgetSig = sig
        prefs.widgetState = org.json.JSONObject().put("server", s.label).put("needs", needs).put("working", working)
            .put("lines", lines).put("at", System.currentTimeMillis()).toString()
        runCatching { com.billmongan.herdrdroid.notify.AgentsWidget.update(getApplication()) }
    }

    private val missingModelLogged = mutableSetOf<String>()
    private var lastPreviewsAt = 0L
    private var lastWorkAt = 0L

    suspend fun refreshPreviews() {
        val ids = panes.map { it.id }
        quietly { it.previews(ids) }?.let { peeks ->
            // The last line of real output on each card, looking back past status chrome.
            previews = peeks.mapValues { (id, peek) ->
                val agent = panes.firstOrNull { it.id == id }?.agent
                // Skip the agent's own footer too (the line its model is read from).
                peek.tail.lines().asReversed()
                    .filter { footerModel(agent, it) == null }
                    .map { com.billmongan.herdrdroid.ui.cardLine(it) }.firstOrNull { it.isNotBlank() } ?: ""
            }
            val seen = peeks.mapNotNull { (id, peek) ->
                val agent = panes.firstOrNull { it.id == id }?.agent
                val m = footerModel(agent, peek.tail)
                // Once per pane: a card that should show a model but whose footer gave none. The
                // footer's last line goes in the diagnostic log (a status line, nothing secret).
                if (m == null && agent != null && agent !in setOf("claude") && missingModelLogged.add("${current?.id}|$id")) {
                    val last = peek.tail.lines().map { it.trimEnd() }.lastOrNull { it.isNotBlank() }.orEmpty()
                    com.billmongan.herdrdroid.diag.DiagLog.log("model", "no model in the footer of $agent pane $id; last line: \"${last.trim().take(120)}\"")
                }
                m?.let { id to it }
            }
            if (seen.isNotEmpty()) { models = models + seen; noteModels() }
        }
        // Claude's footer has no model, so its cards take it from Claude's own session log.
        quietly { it.logModels(panes) }?.let { fromLog -> if (fromLog.isNotEmpty()) { models = models + fromLog; noteModels() } }
        refreshWorkCounts()
        refreshGit()
        current?.let { previewCache[it.id] = previews; modelCache[it.id] = models }
    }

    suspend fun readPane(paneId: String) {
        quietly { it.read(paneId) }?.let { text ->
            // Switched to another agent while this read was on its way: it isn't that agent's screen.
            if ((screen as? Screen.Agent)?.paneId != paneId) return
            paneText = text
            paneTextPane = paneId
            // What the question card sees, logged when it changes (sizes only, no question text).
            val status = panes.firstOrNull { it.id == paneId }?.status
            val p = com.billmongan.herdrdroid.herdr.detectPrompt(text, status == Status.NEEDS_YOU)
            val dialog = com.billmongan.herdrdroid.herdr.showsQuestionDialog(text)
            val sig = if (p == null && !dialog) "none" else
                "dialog ${if (dialog) "open" else "not seen"}, herdr ${status?.name?.lowercase()}, " +
                    (p?.let { "${it.choices.size} choice(s) [${it.choices.joinToString(",") { c -> c.keys }}], question ${it.question.length} chars${if (it.question.endsWith("?")) " ending in ?" else ""}, highlighted ${it.selected ?: "none"}" } ?: "no card")
            if (lastPromptSig[paneId] != sig) {
                lastPromptSig[paneId] = sig
                if (sig != "none" || p == null && lastPromptSig.containsKey(paneId)) com.billmongan.herdrdroid.diag.DiagLog.log("prompt", "$paneId: $sig")
            }
        }
    }
    private val lastPromptSig = mutableMapOf<String, String>()

    fun openPane(paneId: String, why: String = "tap") {
        current?.id?.let { prefs.lastAgent = "$it|$paneId" }
        val from = (screen as? Screen.Agent)?.paneId
        if (from != null && from != paneId) com.billmongan.herdrdroid.diag.DiagLog.log("nav", "agent view $from -> $paneId ($why)")
        seenInApp["${current?.id}|$paneId"] = System.currentTimeMillis()
        saveSeen()
        paneText = ""
        paneTextPane = paneId
        resetTranscript(paneId)
        screen = Screen.Agent(paneId)
    }

    // ---- Structured conversations ------------------------------------------------------------

    private val readers = setOf("claude", "codex", "pi", "omp", "grok", "opencode", "hermes", "copilot", "vibe")

    fun hasTranscriptReader(paneId: String) = pane(paneId)?.agent?.lowercase() in readers

    fun resetTranscript(paneId: String) {
        if (transcriptPane == paneId) return
        // Keep the chat being left, so going back to it is instant.
        stashOpenChat()
        transcriptPane = paneId
        val c = cachedChat(paneId)
        if (c != null) {
            // Read ahead on connect (or kept from before): shown at once, then caught up from its cursor.
            transcriptCursor = c.cursor
            transcriptItems = c.items
            transcriptMore = c.more
            transcriptTitle = c.title
            transcriptStart = c.start
            transcriptFile = c.file
            transcriptState = TranscriptMode.ON
            return
        }
        transcriptCursor = ""
        transcriptItems = emptyList()
        transcriptMore = false
        transcriptTitle = null
        transcriptStart = -1L
        transcriptFile = null
        transcriptState = if (hasTranscriptReader(paneId)) TranscriptMode.UNKNOWN else TranscriptMode.OFF
    }

    /** Every chat read so far, per server and pane; kept until the app exits, and on the phone (1.2.9). */
    private val transcriptCache = java.util.concurrent.ConcurrentHashMap<String, CachedChat>()
    private fun ck(paneId: String) = "${current?.id}|$paneId"

    /** The chats kept in the app's private storage, sealed with a Keystore key; null if that can't be set up. */
    private val chatStore: com.billmongan.herdrdroid.transcript.ChatStore? by lazy {
        runCatching {
            com.billmongan.herdrdroid.transcript.ChatStore(java.io.File(getApplication<Application>().noBackupFilesDir, "chats"),
                com.billmongan.herdrdroid.store.ChatVault::seal, com.billmongan.herdrdroid.store.ChatVault::open)
        }.getOrNull()
    }
    private val persistedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** The panes each server had at the last clean-up of its kept chats. */
    private val keptIds = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /** Writes a chat to the phone's storage, off the main thread. */
    private fun persistChat(serverId: String, paneId: String, chat: CachedChat) {
        persistedAt["$serverId|$paneId"] = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) { chatStore?.save(serverId, paneId, chat) }
    }

    /**
     * The chat kept for [paneId] on the open server: in memory, or else from the phone's storage.
     * A copy made for another agent than the one now in the pane is dropped.
     */
    private fun cachedChat(paneId: String): CachedChat? {
        val sid = current?.id ?: return null
        val agent = pane(paneId)?.agent?.lowercase()
        val c = transcriptCache[ck(paneId)] ?: chatStore?.load(sid, paneId)?.also { transcriptCache[ck(paneId)] = it }
        if (c != null && c.agent != null && agent != null && c.agent != agent) {
            forgetChat(sid, paneId)
            return null
        }
        return c
    }

    private fun forgetChat(serverId: String, paneId: String) {
        transcriptCache.remove("$serverId|$paneId")
        viewModelScope.launch(Dispatchers.IO) { chatStore?.remove(serverId, paneId) }
    }

    /** The open chat as it is now. */
    private fun openChatNow(paneId: String) = CachedChat(transcriptItems, transcriptCursor, transcriptMore, transcriptTitle,
        transcriptStart, transcriptFile, pane(paneId)?.agent?.lowercase())

    /** Keeps the open chat, in memory and on the phone, before it is left (another agent, a reconnect, a server switch). */
    private fun stashOpenChat() {
        val old = transcriptPane ?: return
        val sid = current?.id ?: return
        if (transcriptState != TranscriptMode.ON || transcriptCursor.isEmpty()) return
        val c = openChatNow(old)
        transcriptCache[ck(old)] = c
        persistChat(sid, old, c)
    }

    /**
     * Drops the chats of panes that closed, or whose agent changed, after a pane list read; and
     * catches up, in the background, the kept chat of an agent whose status just changed, so it is
     * current when you go back to it.
     */
    private fun maintainChats(before: Map<String, Pane>, fresh: List<Pane>) {
        val sid = current?.id ?: return
        val ids = fresh.map { it.id }.toSet()
        for (k in transcriptCache.keys.filter { it.startsWith("$sid|") }) {
            val pid = k.removePrefix("$sid|")
            val p = fresh.firstOrNull { it.id == pid }
            val c = transcriptCache[k] ?: continue
            if (p == null || (p.agent != null && c.agent != null && p.agent.lowercase() != c.agent)) {
                com.billmongan.herdrdroid.diag.DiagLog.log("chat", "$pid: ${if (p == null) "pane closed" else "agent changed"}; its kept chat is dropped")
                forgetChat(sid, pid)
            }
        }
        if (keptIds[sid] != ids) {
            keptIds[sid] = ids
            viewModelScope.launch(Dispatchers.IO) { chatStore?.keepOnly(sid, ids) }
        }
        for (p in fresh) {
            val was = before[p.id] ?: continue
            if (was.status == p.status || p.id == transcriptPane || !hasTranscriptReader(p.id)) continue
            val c = transcriptCache[ck(p.id)] ?: continue
            viewModelScope.launch { catchUp(sid, p, c) }
        }
    }

    /** Reads what was added to a kept chat's log since its cursor and folds it in. */
    private suspend fun catchUp(sid: String, p: Pane, c: CachedChat) {
        val r = quietly { it.transcript(p, c.cursor, transcriptScript) } ?: return
        if (!r.ok || current?.id != sid || transcriptPane == p.id) return
        val next = mergeRead(c, r)
        transcriptCache["$sid|${p.id}"] = next
        persistChat(sid, p.id, next)
    }

    private fun mergeRead(c: CachedChat, r: com.billmongan.herdrdroid.transcript.TranscriptRead) = c.withRead(r)
    /** Each server's last previews and models, so its cards show at once when it's opened again. */
    private val previewCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val modelCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private var prefetchJob: Job? = null

    /**
     * Reads every agent's chat in the background right after connecting, newest-status first, so
     * opening an agent shows its conversation at once. Memory only; cleared on a server switch.
     */
    private fun prefetchChats() {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            // Read for this server only: a read finishing after a switch must not land under the new one.
            val sid = current?.id
            val order = listOf(Status.NEEDS_YOU, Status.WORKING, Status.DONE, Status.IDLE, Status.UNKNOWN)
            for (p in panes.filter { hasTranscriptReader(it.id) }.sortedBy { order.indexOf(it.status) }) {
                if (transcriptPane == p.id) continue
                // A chat kept from before (this run, or on the phone) is only caught up from its cursor.
                val kept = transcriptCache[ck(p.id)] ?: withContext(Dispatchers.IO) { chatStore?.load(sid ?: return@withContext null, p.id) }
                    ?.takeIf { it.agent == null || p.agent == null || it.agent == p.agent.lowercase() }
                if (current?.id != sid) return@launch
                if (kept != null && transcriptCache[ck(p.id)] == null) transcriptCache[ck(p.id)] = kept
                val r = quietly { it.transcript(p, kept?.cursor ?: "", transcriptScript) } ?: continue
                if (current?.id != sid) return@launch
                if (!r.ok || transcriptPane == p.id) continue
                val base = kept ?: CachedChat(emptyList(), "", false, null, -1L, null, p.agent?.lowercase())
                val next = mergeRead(if (kept == null) base else kept, if (kept == null) r.copy(reset = true) else r)
                transcriptCache[ck(p.id)] = next
                if (sid != null && (kept == null || r.reset || r.items.isNotEmpty())) persistChat(sid, p.id, next)
            }
        }
    }

    /** Reads what the pane's agent added to its session log since the last read. */
    suspend fun readTranscript(paneId: String) {
        // Only the agent on screen: a loop left over from the agent you just left must not switch
        // the chat back to it (that showed its history, and its title, on the new agent).
        if ((screen as? Screen.Agent)?.paneId != paneId) return
        val p = pane(paneId) ?: return
        resetTranscript(paneId)
        val r = quietly { it.transcript(p, transcriptCursor, transcriptScript) } ?: return
        applyTranscript(paneId, r)
    }

    /**
     * Streams the open pane's log for up to two minutes (the reader's follow mode), applying each
     * change as it arrives. Only while the app is in front. True when a stream ran; false when it
     * couldn't start, so the caller polls instead.
     */
    suspend fun followTranscript(paneId: String): Boolean {
        val p = pane(paneId) ?: return false
        val h = herdr ?: return false
        if (!foreground || link != Link.CONNECTED || transcriptPane != paneId || (screen as? Screen.Agent)?.paneId != paneId) return false
        val started = System.currentTimeMillis()
        var got = false
        val ok = runCatching {
            h.followTranscript(p, transcriptCursor, transcriptScript, 120) { r ->
                withContext(Dispatchers.Main) {
                    // Switched away: end this stream now rather than let it run on for minutes.
                    if (transcriptPane != paneId || (screen as? Screen.Agent)?.paneId != paneId) throw kotlinx.coroutines.CancellationException("switched agents")
                    got = true
                    applyTranscript(paneId, r)
                }
            }
        }.isSuccess
        // A stream that ended at once without any read didn't work here; poll instead.
        return ok && (got || System.currentTimeMillis() - started > 5_000)
    }

    private fun applyTranscript(paneId: String, r: com.billmongan.herdrdroid.transcript.TranscriptRead) {
        if (transcriptPane != paneId) return
        if (!r.ok) {
            if (transcriptItems.isEmpty()) transcriptState = TranscriptMode.OFF
            // A kept cursor the reader can't use any more: read the whole log next time, keeping
            // what is shown until it arrives (1.2.9).
            else if (transcriptCursor.isNotEmpty()) {
                com.billmongan.herdrdroid.diag.DiagLog.log("chat", "$paneId: the reader couldn't continue from the kept cursor (${r.why}); reading the log again")
                transcriptCursor = ""
            }
            return
        }
        transcriptCursor = r.cursor
        if (r.reset || r.items.isNotEmpty()) {
            transcriptItems = Transcript.merge(transcriptItems, r.items, r.reset)
        }
        if (r.reset) {
            transcriptMore = r.more
            transcriptStart = runCatching { org.json.JSONObject(r.cursor).optLong("start", -1L) }.getOrDefault(-1L)
            transcriptFile = runCatching { org.json.JSONObject(r.cursor).optString("file").ifBlank { null } }.getOrNull()
        }
        r.title?.let { transcriptTitle = it }
        transcriptState = TranscriptMode.ON
        // Keep the open chat current in memory, and on the phone every few seconds while it changes.
        if (r.reset || r.items.isNotEmpty()) current?.id?.let { sid ->
            val c = openChatNow(paneId)
            transcriptCache[ck(paneId)] = c
            if (System.currentTimeMillis() - (persistedAt["$sid|$paneId"] ?: 0L) > 4_000L) persistChat(sid, paneId, c)
        }
        lastSent?.let { (pid, text) ->
            val want = text.filterNot { it.isWhitespace() }.take(60)
            if (pid == paneId && transcriptItems.takeLast(16).any { it is UserItem && it.text.filterNot { c -> c.isWhitespace() }.take(60) == want }) {
                lastSent = null
            }
        }
        // Each message sent from the phone stays on screen until the agent's log has it.
        val mine = pendingSends.filter { it.paneId == paneId }
        if (mine.isNotEmpty()) {
            // A slash command is logged as a note naming it ("/model opus" shows as "model opus" or
            // "/model opus"), not as your message: a note that came in after it was sent clears it.
            val notes = transcriptItems.takeLast(30).filterIsInstance<com.billmongan.herdrdroid.transcript.NoteItem>().map { norm(it.text.removePrefix("/")) }
            val seen = mine.filter { p ->
                sameTextCount(p.text) > p.seenBefore ||
                    (p.text.startsWith("/") && notes.any { n -> n.startsWith(norm(p.text.removePrefix("/").substringBefore(' '))) })
            }
            // Safety net: an agent that is idle again has taken every message queued before it went
            // idle, so anything sent more than ten seconds ago is no longer waiting.
            val idle = pane(paneId)?.status.let { it == Status.IDLE || it == Status.DONE }
            val now = System.currentTimeMillis()
            // An agent that is idle again has taken everything queued before; a message it still
            // doesn't show more than ten seconds after it was sent was not delivered. It stays,
            // marked, with Resend and Dismiss, instead of disappearing.
            val stale = if (idle) mine.filter { it !in seen && !it.undelivered && now - it.at > 10_000 } else emptyList()
            if (stale.isNotEmpty()) com.billmongan.herdrdroid.diag.DiagLog.log("pending", "${stale.size} sent message(s) not in the agent's log after it went idle: marked as not seen")
            if (seen.isNotEmpty() || stale.isNotEmpty()) {
                pendingSends = pendingSends.filter { it !in seen }.map { if (it in stale) it.copy(undelivered = true) else it }
            }
        }
    }

    // -- Earlier history ---------------------------------------------------------------------------

    /** Where the shown part of the log begins (a byte offset in [transcriptFile]); -1 when unknown. */
    private var transcriptStart = -1L
    private var transcriptFile: String? = null
    var loadingEarlier by mutableStateOf(false)
        private set

    /** True when the open chat's log can be read further back (logs kept as files). */
    val canLoadEarlier: Boolean get() = transcriptMore && transcriptStart > 0 && transcriptFile != null

    /** Reads the 1.5 MB of the log before what is shown and adds it at the top of the chat. */
    fun loadEarlier(paneId: String) {
        val p = pane(paneId) ?: return
        val file = transcriptFile ?: return
        if (loadingEarlier || transcriptStart <= 0) return
        loadingEarlier = true
        viewModelScope.launch {
            try {
                val cur = org.json.JSONObject().put("file", file).put("earlier", transcriptStart).toString()
                val r = quietly { it.transcript(p, cur, transcriptScript) } ?: return@launch
                if (!r.ok || transcriptPane != paneId) return@launch
                val older = Transcript.merge(emptyList(), r.items, true)
                val have = transcriptItems.map { it.key }.toSet()
                transcriptItems = older.filter { it.key !in have } + transcriptItems
                transcriptStart = runCatching { org.json.JSONObject(r.cursor).optLong("start", 0L) }.getOrDefault(0L)
                transcriptMore = r.more
            } finally {
                loadingEarlier = false
            }
        }
    }

    /** A message sent from this phone that the agent's log doesn't show yet (queued, or steering). */
    data class PendingSend(val id: Long, val paneId: String, val text: String, val at: Long, val seenBefore: Int, val undelivered: Boolean = false)

    /** Messages the agent never showed in its log (see [applyTranscript]), for Resend and Dismiss. */
    fun undeliveredFor(paneId: String): List<Pair<Long, String>> =
        pendingSends.filter { it.paneId == paneId && it.undelivered }.map { it.id to it.text }

    fun resendPending(id: Long) {
        val p = pendingSends.firstOrNull { it.id == id } ?: return
        pendingSends = pendingSends - p
        pane(p.paneId)?.let { sendMessage(it, p.text) }
    }

    fun dismissPending(id: Long) { pendingSends = pendingSends.filterNot { it.id == id } }

    var pendingSends by mutableStateOf<List<PendingSend>>(emptyList())
        private set

    /** Letters and digits only, lower case, first 60: so spacing or punctuation can't hide a match. */
    private fun norm(t: String) = t.filter { it.isLetterOrDigit() }.lowercase().take(60)

    /** How many of your messages in the open conversation match [text]. */
    private fun sameTextCount(text: String): Int {
        val want = norm(text)
        return transcriptItems.count { it is UserItem && norm(it.text) == want }
    }

    /**
     * Every message sent from this phone that the agent's log doesn't show yet, oldest first. An
     * agent that is working takes them in turn, so they stay up to 15 minutes; the screen view
     * (agents without a log) shows only the last for 20 seconds.
     */
    fun pendingFor(paneId: String): List<String> {
        val now = System.currentTimeMillis()
        val hasLog = transcriptPane == paneId && transcriptState == TranscriptMode.ON
        return pendingSends.filter { it.paneId == paneId && !it.undelivered && now - it.at < (if (hasLog) 15 * 60_000L else 20_000L) }.map { it.text }
    }

    /** The Stop button: the key each agent's own program uses to interrupt a turn. */
    fun stopPane(pane: Pane) {
        val key = when (pane.agent?.lowercase()) {
            "hermes" -> "ctrl+c"
            else -> "esc"
        }
        sendKeys(pane.id, key)
    }

    /**
     * Copies a file picked in the message box to ~/.herdrdroid/inbox on the computer, then hands
     * its path to [done] so the message can point the agent at it.
     */
    fun attach(uri: Uri, done: (String) -> Unit) {
        if (uploading) return
        val app = getApplication<Application>()
        uploading = true
        act(null, "Couldn't attach the file") { h ->
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val n = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "attachment"
                    val b = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    n to b
                }
                if (bytes.size > 50 * 1024 * 1024) throw IllegalArgumentException("That file is over 50 MB.")
                done(h.upload(name, bytes))
            } finally {
                uploading = false
            }
        }
    }

    // ---- Attachments ----------------------------------------------------------------------------

    /** Recently fetched attachments, up to 32 MB, so scrolling back doesn't fetch them again. */
    private val attachmentCache = object : android.util.LruCache<String, ByteArray>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    /** An attachment's bytes, from the agent's log, OpenCode's blob table, or a file on the computer. */
    suspend fun attachmentBytes(item: com.billmongan.herdrdroid.transcript.ImageItem): Result<ByteArray> {
        val cacheKey = item.path?.let { "file:$it" } ?: item.key
        attachmentCache.get(cacheKey)?.let { return Result.success(it) }
        val h = herdr ?: return Result.failure(IllegalStateException("Not connected."))
        return runCatching {
            when {
                item.logFile != null -> h.logImage(item.logFile, item.logOffset, item.logIndex ?: "[]", transcriptScript)
                item.blobDb != null && item.blobHash != null -> h.blob(item.blobDb, item.blobHash, transcriptScript)
                item.path != null -> h.fetchFile(item.path)
                else -> throw IllegalStateException("Nothing to fetch.")
            }
        }.onSuccess { attachmentCache.put(cacheKey, it) }
    }

    // ---- Hermes through its gateway ------------------------------------------------------------

    /** The native Hermes chat for this server, made and connected on first use. */
    /**
     * What Hermes's conversations are doing, from its gateway (Telegram, the CLI, the app): any one
     * working makes the Hermes pane show Working, any one waiting on you makes it Needs you.
     * Asked every 15 seconds while a server with a Hermes pane is open.
     */
    var hermesState by mutableStateOf<Status?>(null)
        private set
    private var hermesPoll: Job? = null

    private fun pollHermes() {
        if (hermesPoll?.isActive == true) return
        hermesPoll = viewModelScope.launch {
            while (isActive) {
                val hasHermes = panes.any { it.agent?.lowercase() == "hermes" }
                if (hasHermes && foreground && herdr != null) {
                    val states = hermesChat()?.liveStates()
                    val next = when {
                        states == null -> null
                        states.any { it.second == "waiting" } -> Status.NEEDS_YOU
                        states.any { it.second in setOf("working", "streaming", "starting") } -> Status.WORKING
                        else -> null
                    }
                    if (next != hermesState) {
                        com.billmongan.herdrdroid.diag.DiagLog.log("hermes", "conversations: " +
                            (states?.joinToString { "${it.second}" } ?: "gateway not answering") + " -> ${next ?: "pane status"}")
                        hermesState = next
                    }
                } else hermesState = null
                delay(15_000)
            }
        }
    }

    fun hermesChat(): HermesChat? {
        hermes?.let { return it }
        val s = ssh ?: return null
        return HermesChat(s, viewModelScope).also {
            hermes = it
            it.start()
        }
    }

    fun pane(id: String): Pane? = panes.firstOrNull { it.id == id }

    private val lastSentTo = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun sendMessage(pane: Pane, text: String) {
        lastSentTo[pane.id] = System.currentTimeMillis()
        lastSent = pane.id to text
        lastSentAt = System.currentTimeMillis()
        pendingSends = pendingSends + PendingSend(
            System.nanoTime(), pane.id, text, lastSentAt,
            if (transcriptPane == pane.id) sameTextCount(text) else 0,
        )
        com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id} (${pane.agent ?: "shell"}, ${pane.status}): ${text.length} chars; link ${link}, ${if (herdr == null) "no client" else "client ready"}")
        act(pane.id, "Couldn't send") { h ->
            try {
                h.sendMessage(pane, text)
                com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id}: delivered to herdr (${h.lastSendRoute})")
            } catch (e: Exception) {
                com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id}: failed: ${com.billmongan.herdrdroid.diag.DiagLog.describe(e)}")
                throw e
            }
            readPane(pane.id)
        }
    }

    fun sendKeys(paneId: String, vararg keys: String) {
        val k = keys.toList()
        act(paneId, "Couldn't send the key") { h ->
            h.sendKeys(paneId, *k.toTypedArray())
            readPane(paneId)
        }
    }

    /**
     * Answers a menu question: moves the highlight with the arrow keys and presses Enter when the
     * highlighted option is known, so a digit is never typed into an option's text box.
     */
    fun answerChoice(paneId: String, prompt: com.billmongan.herdrdroid.herdr.Prompt, choice: com.billmongan.herdrdroid.herdr.Choice) {
        if (choice.keys.startsWith("grok-privacy:")) {
            act(paneId, "Couldn't answer") { h ->
                answerGrokPrivacy(h, paneId, optIn = choice.keys == com.billmongan.herdrdroid.herdr.GROK_PRIVACY_IN)?.let { throw IllegalStateException(it) }
                readPane(paneId)
                refreshPanes()
            }
            return
        }
        val keys = com.billmongan.herdrdroid.herdr.menuKeys(prompt, choice)
        if (keys == null) { answer(paneId, choice.keys, choice.pressEnter); return }
        act(paneId, "Couldn't answer") { h ->
            h.sendKeys(paneId, *keys.toTypedArray())
            readPane(paneId)
            refreshPanes()
        }
    }

    /**
     * Answers Grok's data-sharing banner the way that works (checked on demo-box2's Grok 4.7,
     * 2026-11-13): with the input box empty, type /privacy and Enter, which opens "Settings ›
     * Coding data, retention, and training"; then move to Opt in or Opt out with the arrows and
     * press Enter. Returns a problem to show, or null.
     */
    private suspend fun answerGrokPrivacy(h: Herdr, paneId: String, optIn: Boolean): String? {
        var screen = h.read(paneId, 60)
        if (com.billmongan.herdrdroid.herdr.detectGrokPrivacyDialog(screen) == null) {
            val left = com.billmongan.herdrdroid.herdr.grokInputText(screen)
                ?: return "Grok's input box isn't showing, so /privacy wasn't typed. Close what is open in Grok and try again."
            if (left.isNotEmpty()) {
                // Backspaces clear it in any terminal program; then check that it is empty.
                h.sendKeys(paneId, *Array(left.length + 2) { "backspace" })
                delay(500)
                if (!com.billmongan.herdrdroid.herdr.grokInputText(h.read(paneId, 60)).isNullOrEmpty())
                    return "Grok's input box still has text in it, so /privacy wasn't typed."
            }
            h.sendText(paneId, "/privacy")
            delay(700)
            h.sendKeys(paneId, "enter")
            delay(1500)
            screen = h.read(paneId, 60)
        }
        val dialog = com.billmongan.herdrdroid.herdr.detectGrokPrivacyDialog(screen)
            ?: return "Grok's privacy settings didn't open."
        val target = dialog.choices.firstOrNull { it.label == if (optIn) "Opt in" else "Opt out" } ?: return "Grok's privacy settings changed shape."
        val keys = com.billmongan.herdrdroid.herdr.menuKeys(dialog, target) ?: return "Grok's privacy settings have no highlighted choice."
        h.sendKeys(paneId, *keys.toTypedArray())
        return null
    }

    fun answer(paneId: String, keys: String, pressEnter: Boolean) {
        act(paneId, "Couldn't answer") { h ->
            if (pressEnter) h.typeAndEnter(paneId, keys) else h.sendText(paneId, keys)
            readPane(paneId)
            refreshPanes()
        }
    }

    // ---- Usage limits -----------------------------------------------------------------------

    private fun startQuotaChecks() {
        if (quotaJob?.isActive == true) return
        quotaJob = viewModelScope.launch {
            delay(300)
            while (isActive) {
                if (showQuotas && canPoll) runCatching { quotaRound(force = false) }
                delay(30_000)
            }
        }
    }

    /** The refresh button: check again now, but not more than once every two minutes per agent. */
    fun refreshQuotasSoon(force: Boolean = true) {
        if (!showQuotas || herdr == null) return
        viewModelScope.launch { runCatching { quotaRound(force = force) } }
    }

    /**
     * For each kind of agent that can report usage without a model call, picks one pane running it
     * that is idle (preferring one not in front at the computer), and asks it. The reading applies to every pane
     * running that agent. Agents with no way to report usage are never sent anything.
     */
    private suspend fun quotaRound(force: Boolean) = quotaLock.withLock {
        val serverId = current?.id ?: return@withLock
        val fresh = quietly { it.panes() } ?: return@withLock
        panes = fresh
        val now = System.currentTimeMillis()
        // Agents with no reading yet first, then the oldest readings.
        val byAgent = fresh.mapNotNull { p -> QuotaProfiles.key(p.agent)?.let { it to p } }.groupBy({ it.first }, { it.second })
            .entries.sortedBy { quotas[it.key]?.at ?: 0L }
        // Every agent is checked at once, each in its own pane, so all usage shows in about
        // the time one check takes.
        val jobs = mutableListOf<Job>()
        for ((agentKey, list) in byAgent) {
            jobs += viewModelScope.launch {
                // Due by the newest of: the last try here, and the saved reading (a startup check or
                // an earlier session counts), so a fresh reading is never asked for again.
                val last = maxOf(lastQuotaTry[agentKey] ?: 0L, quotas[agentKey]?.at ?: 0L)
                val every = (QuotaProfiles.forAgent(list.first().agent)?.everyMinutes ?: 30) * 60_000L
                val due = if (force) now - last > 2 * 60_000 else now - last > every
                if (!due) return@launch
                // Prefer a pane that is not in front at the computer, but use that one if it is the only
                // choice: the check presses Enter only when the input line holds exactly its command.
                // Idle panes first; a working pane only for an agent that answers while working (Claude).
                val profile0 = QuotaProfiles.forAgent(list.first().agent) ?: return@launch
                if (profile0.screenUsage != null) {
                    // Shown on the agent's own screen: read it, type nothing, whatever the status.
                    if (!canPoll) return@launch
                    val items = list.firstNotNullOfOrNull { p -> quietly { it.screenQuota(p.id, profile0) }?.takeIf { it.isNotEmpty() } }
                    lastQuotaTry[agentKey] = now
                    if (!items.isNullOrEmpty()) {
                        quotas = quotas + (agentKey to QuotaReading(now, items))
                        prefs.saveQuotas(serverId, quotas)
                        recordQuota(serverId, agentKey, QuotaReading(now, items))
                    }
                    return@launch
                }
                if (profile0.cliCommand != null) {
                    // Asked from the command line, not the pane: no pane to choose and nothing typed.
                    if (!canPoll) return@launch
                    val items = quietly { it.cliQuota(profile0) }
                    lastQuotaTry[agentKey] = now
                    if (!items.isNullOrEmpty()) {
                        quotas = quotas + (agentKey to QuotaReading(now, items))
                        prefs.saveQuotas(serverId, quotas)
                        recordQuota(serverId, agentKey, QuotaReading(now, items))
                    }
                    return@launch
                }
                // Never an agent open on screen or sent to in the last two minutes: you may be typing to it.
                val openNow = (screen as? Screen.Agent)?.paneId
                val target = list.filter {
                    (it.status == Status.IDLE || it.status == Status.DONE || (profile0.whileWorking && it.status == Status.WORKING)) &&
                        it.id != openNow && (lastSentTo[it.id] ?: 0L) < now - 120_000
                }
                    .sortedWith(compareBy({ it.status == Status.WORKING }, { it.focused }))
                    .firstOrNull()
                if (target == null) {
                    com.billmongan.herdrdroid.diag.DiagLog.log("usage", "$agentKey on ${current?.label}: no pane to check (" +
                        list.joinToString { "${it.id} ${it.status}${if (it.id == openNow) " open" else ""}${if ((lastSentTo[it.id] ?: 0L) >= now - 120_000) " sent-to" else ""}" } + ")")
                    return@launch
                }
                val profile = QuotaProfiles.forAgent(target.agent) ?: return@launch
                if (!canPoll) return@launch
                if (herdr?.canProbeScreens == false) {
                    com.billmongan.herdrdroid.diag.DiagLog.log("usage", "$agentKey on ${current?.label}: herdr-core, usage screens aren't checked")
                    return@launch
                }
                com.billmongan.herdrdroid.diag.DiagLog.log("usage", "$agentKey on ${current?.label}: checking ${target.id} (${target.status})")
                val typingKey = "$serverId/${target.id}"
                if (!typingInto.add(typingKey)) {
                    com.billmongan.herdrdroid.diag.DiagLog.log("usage", "$agentKey on ${current?.label}: ${target.id} is already being checked")
                    return@launch
                }
                probingPanes = probingPanes + target.id
                probedAt["$serverId|${target.id}"] = System.currentTimeMillis()
                val result = try {
                    quietly { it.probeQuota(target.id, profile, working = target.status == Status.WORKING) }
                } finally {
                    probingPanes = probingPanes - target.id
                    typingInto.remove(typingKey)
                    probedAt["$serverId|${target.id}"] = System.currentTimeMillis()
                }
                if (result != null) watchForLeftoverScreen(target.id, profile)
                result?.model?.let { models = models + (target.id to it); noteModels() }
                result?.effort?.let { efforts = efforts + (target.id to it) }
                val items = result?.items
                com.billmongan.herdrdroid.diag.DiagLog.log("usage", "$agentKey on ${current?.label}: ${when {
                    result == null -> "nothing read (command not echoed, or the pane was busy)"
                    items.isNullOrEmpty() -> "screen read, no limits found on it"
                    else -> "${items.size} limit(s) read"
                }}")
                when {
                    // Someone had text in that agent's input line, or the screen had no limits on it this
                    // time (a Hermes session that hasn't made a call yet). Try again in 5 minutes.
                    items.isNullOrEmpty() -> lastQuotaTry[agentKey] = now - (every - 5 * 60_000L).coerceAtLeast(0)
                    else -> {
                        lastQuotaTry[agentKey] = now
                        quotas = quotas + (agentKey to QuotaReading(now, items))
                        prefs.saveQuotas(serverId, quotas)
                        recordQuota(serverId, agentKey, QuotaReading(now, items))
                    }
                }
            }
        }
        jobs.joinAll()
    }

    private val closeWatch = mutableMapOf<String, Pair<QuotaProfile, Long>>()
    private var closeJob: Job? = null

    /**
     * After the app opens an agent's usage or model screen, it keeps an eye on that pane for ten
     * minutes and closes the screen if it is (still, or newly) open: an agent can draw it late,
     * after the check finished. Only the screen the app opened is closed, recognised by its close
     * hint together with its usage numbers or model line ([screenOpen]), so the Esc can't reach
     * a permission prompt or a working agent.
     */
    private fun watchForLeftoverScreen(paneId: String, profile: QuotaProfile) {
        if (profile.closeKeys.isEmpty() || profile.openMarker == null) return
        closeWatch[paneId] = profile to System.currentTimeMillis() + 10 * 60_000
        if (closeJob?.isActive == true) return
        closeJob = viewModelScope.launch {
            while (isActive && closeWatch.isNotEmpty()) {
                delay(3_000)
                val now = System.currentTimeMillis()
                closeWatch.entries.removeAll { it.value.second < now }
                for ((id, pair) in closeWatch.toMap()) {
                    if (id in probingPanes) continue
                    val screen = quietly { it.read(id, 60) } ?: continue
                    if (pair.first.screenOpen(screen)) {
                        quietly { it.sendKeys(id, *pair.first.closeKeys.toTypedArray()) }
                    }
                }
            }
        }
    }

    fun quotaFor(pane: Pane?): QuotaReading? =
        if (!showQuotas) null else QuotaProfiles.key(pane?.agent)?.let { quotas[it] }

    // ---- 0.4.0: alerts, other servers, queue, files ------------------

    var watching by mutableStateOf(prefs.watching)
        private set
    var watchOthers by mutableStateOf(prefs.watchOthers)
        private set
    var alertDone by mutableStateOf(prefs.alertDone)
        private set
    var darkPalette by mutableStateOf(runCatching { DarkPalette.valueOf(prefs.darkPalette) }.getOrDefault(DarkPalette.STANDARD))
        private set
    private val unlockedThisSession = mutableSetOf<String>()
    private val lastStatus = mutableMapOf<String, Status>()
    private var fleet: Fleet? = null
    private var watchJob: Job? = null
    private var pendingOpen: String? = null

    /** What the other watched servers' agents are doing, by server id. */
    val fleetStates: Map<String, FleetState> get() = fleet?.states ?: emptyMap()

    var lightPalette by mutableStateOf(runCatching { com.billmongan.herdrdroid.ui.LightPalette.valueOf(prefs.lightPalette) }.getOrDefault(com.billmongan.herdrdroid.ui.LightPalette.STANDARD))
        private set

    fun chooseLightPalette(p: com.billmongan.herdrdroid.ui.LightPalette) {
        lightPalette = p
        prefs.lightPalette = p.name
    }

    // -- Usage over time ---------------------------------------------------------------------------

    /** One reading for the chart: when, and each limit's percent used. */
    data class QuotaPoint(val at: Long, val used: Map<String, Double>)

    /** Keeps each agent's readings for eight days (at most 400) for the usage charts. */
    private fun recordQuota(serverId: String, agentKey: String, r: QuotaReading) {
        runCatching {
            val all = org.json.JSONObject(prefs.quotaHistory)
            val k = "$serverId|$agentKey"
            val arr = all.optJSONArray(k) ?: org.json.JSONArray()
            val prev = if (arr.length() > 0) arr.getJSONObject(arr.length() - 1).optJSONObject("used") else null
            if (prev != null) usageAlerts(serverId, agentKey, prev, r)
            arr.put(org.json.JSONObject().put("at", r.at).put("used", org.json.JSONObject(r.items.associate { it.label to it.usedPercent })))
            val cutoff = r.at - 8L * 24 * 3600 * 1000
            val kept = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optLong("at") >= cutoff }.takeLast(400)
            all.put(k, org.json.JSONArray(kept))
            prefs.quotaHistory = all.toString()
        }
    }

    var alertUsage by mutableStateOf(prefs.alertUsage)
        private set

    fun changeAlertUsage(on: Boolean) {
        alertUsage = on
        prefs.alertUsage = on
    }

    /**
     * Compares a new reading with the one before for the same agent: a limit that went past 80% or
     * 95% since, or that dropped back (a reset), gets one notification.
     */
    private fun usageAlerts(serverId: String, agentKey: String, prev: org.json.JSONObject, r: QuotaReading) {
        if (!alertUsage) return
        val server = servers.firstOrNull { it.id == serverId }?.label ?: ""
        val name = Pane("", "", "", agentKey, Status.UNKNOWN, "", "").agentName
        for (i in r.items) {
            if (!prev.has(i.label)) continue
            val was = prev.optDouble(i.label)
            val now = i.usedPercent
            when (com.billmongan.herdrdroid.herdr.usageChange(was, now)) {
                is com.billmongan.herdrdroid.herdr.UsageChange.Crossed -> Notify.usage(getApplication(), serverId, agentKey, i.label,
                    "$name is at ${now.toInt()}% · $server", "${i.label}: ${now.toInt()}% used" + (i.resets?.let { ", resets ${com.billmongan.herdrdroid.herdr.shortReset(it)}" } ?: ""))
                com.billmongan.herdrdroid.herdr.UsageChange.Reset -> Notify.usage(getApplication(), serverId, agentKey, i.label,
                    "$name's limit reset · $server", "${i.label}: back to ${now.toInt()}% used (was ${was.toInt()}%)")
                null -> {}
            }
        }
    }

    /** Every agent with readings on the open server: its latest reading and its history. */
    fun usageOverview(): List<Triple<String, QuotaReading, List<QuotaPoint>>> {
        val sid = current?.id ?: return emptyList()
        return quotas.entries.sortedBy { it.key }.map { (k, r) ->
            val hist = runCatching {
                val arr = org.json.JSONObject(prefs.quotaHistory).optJSONArray("$sid|$k") ?: org.json.JSONArray()
                (0 until arr.length()).map { n ->
                    val o = arr.getJSONObject(n)
                    val u = o.getJSONObject("used")
                    QuotaPoint(o.optLong("at"), u.keys().asSequence().associateWith { u.optDouble(it) })
                }
            }.getOrDefault(emptyList())
            Triple(k, r, hist)
        }
    }

    // -- Shared from other apps ----------------------------------------------------------------------

    /** Something shared into HerdrDroid, waiting for an agent to be picked. */
    data class Share(val text: String?, val uri: Uri?)

    var pendingShare by mutableStateOf<Share?>(null)
        private set

    fun receiveShare(text: String?, uri: Uri?) {
        if (text.isNullOrBlank() && uri == null) return
        pendingShare = Share(text?.trim(), uri)
        if (herdr != null && screen !is Screen.Tabs) screen = Screen.Tabs
    }

    fun discardShare() { pendingShare = null }

    /** Puts the shared text (and a shared file, uploaded first) into [pane]'s message box. */
    fun sendShareTo(pane: Pane) {
        val s = pendingShare ?: return
        pendingShare = null
        val text = s.text.orEmpty()
        if (s.uri == null) {
            prefills[pane.id] = text + "\n"
            openPane(pane.id)
            return
        }
        attach(s.uri) { path ->
            prefills[pane.id] = (if (text.isNotBlank()) "$text\n\n" else "") + path + " "
            openPane(pane.id)
        }
    }

    /** Where Settings was scrolled to, kept across the rebuild a theme change causes. */
    var settingsScroll = 0

    // -- Snippets ----------------------------------------------------------------------------------

    var snippets by mutableStateOf(loadSnippets())
        private set

    private fun loadSnippets(): List<com.billmongan.herdrdroid.ui.Snippet> = runCatching {
        val a = org.json.JSONArray(prefs.snippets)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> com.billmongan.herdrdroid.ui.Snippet(o.getString("name"), o.getString("text")) } }
    }.getOrDefault(emptyList())

    private fun saveSnippets(list: List<com.billmongan.herdrdroid.ui.Snippet>) {
        snippets = list
        prefs.snippets = org.json.JSONArray(list.map { org.json.JSONObject().put("name", it.name).put("text", it.text) }).toString()
    }

    fun addSnippet(s: com.billmongan.herdrdroid.ui.Snippet) = saveSnippets(snippets.filterNot { it.name == s.name } + s)
    fun removeSnippet(s: com.billmongan.herdrdroid.ui.Snippet) = saveSnippets(snippets - s)

    fun chooseDarkPalette(p: DarkPalette) {
        darkPalette = p
        prefs.darkPalette = p.name
    }

    fun changeWatching(on: Boolean) {
        watching = on
        prefs.watching = on
        if (on) startWatching() else {
            watchJob?.cancel()
            WatchService.stop(getApplication())
        }
    }

    fun changeWatchOthers(on: Boolean) {
        watchOthers = on
        prefs.watchOthers = on
        startWatching()
    }

    fun changeAlertDone(on: Boolean) {
        alertDone = on
        prefs.alertDone = on
    }

    /** The other servers that can be watched without asking for anything. */
    private fun watchable(): List<Server> = servers.filter {
        it.id != current?.id && it.fingerprint != null && canSignInSilently(it) && true &&
            // A WebSocket server is watched only while the app is in front, so it can sleep otherwise.
            (!it.overWebSocket || foreground)
    }

    /** Starts the other-server watcher and, with alerts on, the background loop and service. */
    /**
     * The watcher for every other server, which fills the drawer. It runs whether or not a server
     * is open: every 5 seconds while alerts are on, 10 otherwise.
     */
    private fun ensureFleet() {
        if (!watchOthers) { fleet?.stop(); fleet = null; return }
        val f = fleet ?: Fleet(
            viewModelScope,
            credentials = { s -> credentialsFor(s, savedPassword(s)) },
            keystoreKey = SshKeyVault::provider,
            onSeen = { st ->
                trackDone(st.server.id, st.panes)
                noticeChanges(st.server.id, st.server.label, st.panes) { fleet?.herdr(st.server.id) }
            },
            // 1.2.8: watched servers get the same work sweep as the open one, so an agent with a
            // running shell there shows as working in the drawer, cards and notifications.
            busyOf = { h -> h.workCounts(h.panes0Cache, transcriptScript).second },
        ).also { fleet = it }
        f.everyMs = if (watching) 5_000L else 10_000L
        f.watch(watchable())
    }

    private fun startWatching() {
        if (herdr == null) return
        ensureFleet()
        if (!watching) return
        Notify.channels(getApplication())
        WatchService.start(getApplication(), "Watching ${current?.label ?: "your server"}" + if (watchOthers && watchable().isNotEmpty()) " and ${watchable().size} more" else "")
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch {
            while (isActive) {
                delay(3_000)
                // In front, the screens refresh panes themselves; behind, this keeps alerts coming.
                if (!foreground && herdr != null && current?.overWebSocket != true) runCatching { refreshPanes() }
            }
        }
    }

    /**
     * Compares each pane's status with the last one seen and raises an alert when an agent starts
     * waiting on you, or (if wanted) finishes. The pane open on screen in the foreground is skipped.
     */
    private fun noticeChanges(serverId: String, serverLabel: String, seen: List<Pane>, client: () -> Herdr?) {
        if (!watching) {
            seen.forEach { lastStatus["$serverId|${it.id}"] = it.status }
            return
        }
        val app = getApplication<Application>()
        for (p in seen) {
            val key = "$serverId|${p.id}"
            val before = lastStatus[key]
            lastStatus[key] = p.status
            if (before == null || before == p.status || p.agent == null) continue
            val onScreen = foreground && serverId == current?.id && (screen as? Screen.Agent)?.paneId == p.id
            if (onScreen) continue
            when {
                p.status == Status.NEEDS_YOU -> viewModelScope.launch {
                    val text = runCatching { client()?.read(p.id, 40) }.getOrNull().orEmpty()
                    val prompt = detectPrompt(text, blocked = true)
                    Notify.needsYou(app, serverId, serverLabel, p.id, p.agentName, prompt?.question ?: "Waiting for you.", prompt?.choices.orEmpty())
                }
                // Only agents that need you notify (Bill, 2026-11-13); finishing doesn't.
                before == Status.NEEDS_YOU -> Notify.clear(app, serverId, p.id)
            }
        }
    }

    init {
        viewModelScope.launch { AppBus.actions.collect { a -> answerFromNotification(a) } }
        if (!locked) startBackgroundChecks()
    }

    /** A notification button: answer only if the same question is still on screen. */
    private suspend fun answerFromNotification(a: NotifyAction) {
        val h = if (a.serverId == current?.id) herdr else fleet?.herdr(a.serverId)
        h ?: return
        val app = getApplication<Application>()
        // A typed reply: sent as a message (as the answer when the agent is waiting on a question).
        if (a.isReply) {
            runCatching {
                val p = h.panes().firstOrNull { it.id == a.paneId } ?: return
                h.sendMessage(p, a.keys)
                if (a.serverId == current?.id && transcriptPane == a.paneId) lastSent = a.paneId to a.keys
            }.onFailure {
                Notify.needsYou(app, a.serverId, servers.firstOrNull { it.id == a.serverId }?.label ?: "", a.paneId, "The agent",
                    "Your reply couldn't be sent (${it.message}). Open the app to send it.", emptyList())
            }
            return
        }
        val text = runCatching { h.read(a.paneId, 40) }.getOrNull() ?: return
        val now = detectPrompt(text, blocked = true)
        val same = now != null && now.question.filterNot { it.isWhitespace() } == a.question.filterNot { it.isWhitespace() } &&
            (a.isKey || now.choices.any { it.keys == a.keys })
        if (!same) {
            Notify.needsYou(app, a.serverId, servers.firstOrNull { it.id == a.serverId }?.label ?: "", a.paneId, "The agent",
                "The question changed before your answer arrived. Open the app to answer.", emptyList())
            return
        }
        runCatching {
            if (a.keys.startsWith("grok-privacy:")) {
                answerGrokPrivacy(h, a.paneId, optIn = a.keys == com.billmongan.herdrdroid.herdr.GROK_PRIVACY_IN)
                return@runCatching
            }
            val nav = if (!a.isKey) now?.let { p -> p.choices.firstOrNull { it.keys == a.keys }?.let { c -> com.billmongan.herdrdroid.herdr.menuKeys(p, c) } } else null
            if (nav != null) h.sendKeys(a.paneId, *nav.toTypedArray())
            else if (a.isKey) h.sendKeys(a.paneId, a.keys)
            else if (a.pressEnter) h.typeAndEnter(a.paneId, a.keys)
            else h.sendText(a.paneId, a.keys)
        }
    }

    /** A notification was tapped: open that pane, switching servers if it is on another one. */
    fun openFromNotification(serverId: String, paneId: String) {
        if (locked) { pendingNotification = serverId to paneId; return }
        if (serverId == current?.id) {
            openPane(paneId, "notification")
            return
        }
        val s = servers.firstOrNull { it.id == serverId } ?: return
        // A fingerprint-locked server opens only if it was already unlocked this session.
        if (!canSignInSilently(s)) {
            screen = Screen.Servers
            return
        }
        pendingOpen = paneId
        connect(s, savedPassword(s), remember = false)
    }

    /**
     * Switches to another server from the machine menu: straight away when it signs in without
     * asking, otherwise back to the server list to sign in there.
     */
    fun switchServer(s: Server) {
        if (canSignInSilently(s) && s.fingerprint != null) switchTo(s.id, null)
        else disconnect()
    }

    /** Switches to another watched server from the "needs you" list. */
    fun switchTo(serverId: String, paneId: String?) {
        if (serverId == current?.id && herdr != null) { paneId?.let { openPane(it, "drawer") }; return }
        val s = servers.firstOrNull { it.id == serverId } ?: return
        if (!canSignInSilently(s)) { screen = Screen.Servers; return }
        // If that server doesn't answer, come back to the agent you were on (its connection is
        // parked, so this is quick) rather than leaving an empty screen or the server list.
        val backServer = current
        val backPane = (screen as? Screen.Agent)?.paneId
        pendingOpen = paneId
        if (current != null) disconnectQuietly()
        connect(s, savedPassword(s), remember = false, openAgent = true)
        viewModelScope.launch {
            connectJob?.join()
            if (current == null && backServer != null && pendingHostKey == null && pendingBackendChoice == null) {
                val why = error
                pendingOpen = backPane
                connect(backServer, savedPassword(backServer), remember = false, openAgent = true)
                connectJob?.join()
                error = "${s.label} didn't answer" + (why?.let { ": $it" } ?: ".")
            }
        }
    }

    // -- 1.1: every agent on every server, in one drawer ------------------------------------------

    /** One agent in the drawer: the server it is on, and the status the drawer shows. */
    data class Session(val server: Server, val pane: Pane, val status: Status, val pinned: Boolean, val current: Boolean)

    /** Back to the agent last open on this server, or its tab list when there is none. */
    fun goHome() {
        val last = prefs.lastAgent.split("|", limit = 2)
        screen = if (last.getOrNull(0) == current?.id && panes.any { it.id == last.getOrNull(1) }) Screen.Agent(last[1])
            else if (current != null) Screen.Tabs else Screen.Servers
    }

    /** Whether the drawer is pulled out. */
    var drawerOpen by mutableStateOf(false)

    private val statusOrder = listOf(Status.NEEDS_YOU, Status.WORKING, Status.DONE, Status.IDLE, Status.UNKNOWN)

    /**
     * Every agent on every server, sorted needs you, working, done, idle; pinned ones first in
     * each group. The open server's agents are live; the others come from the watcher, or the last
     * startup check when the watcher has not reached that server yet.
     */
    fun sessions(): List<Session> {
        val pins = prefs.pinnedSessions.lines().toSet()
        val out = mutableListOf<Session>()
        val cur = current
        if (cur != null) panes.filter { it.agent != null }.forEach { p ->
            out += Session(cur, p, shownStatus(p), "${cur.id}|${p.tabLabel}" in pins, true)
        }
        for (s in servers) {
            if (s.id == cur?.id) continue
            val list = fleetStates[s.id]?.panes ?: serverPanes[s.id].orEmpty()
            list.filter { it.agent != null }.forEach { p -> out += Session(s, p, shownStatus(p, s.id), "${s.id}|${p.tabLabel}" in pins, false) }
        }
        return out.sortedWith(compareBy({ statusOrder.indexOf(it.status) }, { !it.pinned }, { it.server.label.lowercase() }, { it.pane.tabLabel.lowercase() }))
    }

    /** Each server's state for the drawer: reachable or not, how long ago it was seen, how many agents. */
    data class ServerState(val server: Server, val reachable: Boolean?, val sessions: Int, val seenAt: Long?)

    fun serverStates(): List<ServerState> = servers.map { s ->
        when {
            s.id == current?.id -> ServerState(s, link == Link.CONNECTED, panes.count { it.agent != null }, System.currentTimeMillis())
            fleetStates[s.id] != null -> fleetStates[s.id]!!.let { st -> ServerState(s, st.error == null, st.panes.count { it.agent != null }, st.at) }
            serverPanes[s.id] != null -> ServerState(s, null, serverPanes[s.id]!!.count { it.agent != null }, null)
            else -> ServerState(s, null, 0, null)
        }
    }

    /** The model an agent last used, on any server, when the app knows it. */
    fun modelFor(serverId: String, paneId: String): String? =
        if (serverId == current?.id) modelLine(paneId) else modelCache[serverId]?.get(paneId)

    /** Bumped when another server's models are read, so the drawer redraws. */
    var modelsVersion by mutableStateOf(0)
        private set

    fun togglePin(server: Server, pane: Pane) {
        val key = "${server.id}|${pane.tabLabel}"
        val pins = prefs.pinnedSessions.lines().filter { it.isNotBlank() }.toMutableSet()
        if (!pins.add(key)) pins.remove(key)
        prefs.pinnedSessions = pins.joinToString("\n")
        pinVersion++
    }
    /** Bumped when pins change, so the drawer redraws. */
    var pinVersion by mutableStateOf(0)
        private set

    /** The agent most worth opening: one waiting on you, then working, then the rest. */
    private fun bestAgent(): String? = panes.filter { it.agent != null }.minByOrNull { statusOrder.indexOf(shownStatus(it)) }?.id

    /** The herdr client for any server: the open one, or the watcher's. */
    private fun clientFor(serverId: String): Herdr? = if (serverId == current?.id) herdr else fleet?.herdr(serverId)

    /** Which agent programs a server has, for the New session sheet. */
    suspend fun installedAgents(serverId: String): List<String> =
        runCatching { clientFor(serverId)?.installedAgents() }.getOrNull() ?: emptyList()

    var sessionBusy by mutableStateOf<String?>(null)
        private set

    /**
     * Starts [kind] in a new tab named [label] at [folder] on a server, sends [firstMessage] once it
     * is ready, and opens it. Nothing is installed and no folder is deleted, ever.
     */
    fun newSession(serverId: String, kind: String, folder: String, label: String, firstMessage: String?) {
        if (sessionBusy != null) return
        sessionBusy = "Starting $label…"
        viewModelScope.launch {
            try {
                val h = clientFor(serverId) ?: run { error = "Not connected to that server yet."; return@launch }
                val name = label.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').take(32).let { if (it.firstOrNull()?.isLetter() == true) it else "a$it" }
                val ws = if (serverId == current?.id) mainWorkspace() else null
                val pane = h.startAgent(kind, folder.ifBlank { "~" }, label, name, ws)
                com.billmongan.herdrdroid.diag.DiagLog.log("session", "started $kind as \"$label\" on ${servers.firstOrNull { it.id == serverId }?.label}")
                if (!firstMessage.isNullOrBlank()) {
                    val p = h.panes().firstOrNull { it.id == pane }
                    if (p != null) h.sendMessage(p, firstMessage)
                }
                drawerOpen = false
                if (serverId == current?.id) { refreshPanes(); openPane(pane, "new session") } else switchTo(serverId, pane)
            } catch (e: Exception) {
                error = "Couldn't start the session: ${e.message}"
            } finally {
                sessionBusy = null
            }
        }
    }

    private fun mainWorkspace(): String? = panes.groupingBy { it.workspaceId }.eachCount().maxByOrNull { it.value }?.key

    /** Closes a session's tab (ending its agent). Its folder and files are never touched. */
    fun closeSession(serverId: String, pane: Pane) {
        viewModelScope.launch {
            val h = clientFor(serverId) ?: return@launch
            runCatching { h.closeTab(pane.tabId) }
                .onSuccess { com.billmongan.herdrdroid.diag.DiagLog.log("session", "closed \"${pane.tabLabel}\"") }
                .onFailure { error = "Couldn't close it: ${it.message}" }
            if (serverId == current?.id) {
                refreshPanes()
                if ((screen as? Screen.Agent)?.paneId == pane.id) bestAgent()?.let { openPane(it, "after closing") } ?: run { screen = Screen.Tabs; drawerOpen = true }
            }
        }
    }

    /** Whether a session on that server can be renamed (herdr-core cannot). */
    fun canRename(serverId: String): Boolean =
        clientFor(serverId)?.canRename ?: (servers.firstOrNull { it.id == serverId }?.backend != "core")

    fun renameSession(serverId: String, pane: Pane, label: String) {
        viewModelScope.launch {
            val h = clientFor(serverId) ?: return@launch
            runCatching { h.renameTab(pane.tabId, label) }.onFailure { error = "Couldn't rename it: ${it.message}" }
            if (serverId == current?.id) refreshPanes()
        }
    }

    /**
     * At startup: no servers, the server screen; otherwise the last agent, on its server, when that
     * server signs in without asking; otherwise the server screen.
     */
    /** True while startup is still looking for a server that answers; the app shows "Opening your agents". */
    var starting by mutableStateOf(false)
        private set
    var startingOn by mutableStateOf<String?>(null)
        private set

    /** "Servers" pressed on the opening screen: stop trying and show the server list. */
    fun cancelStarting() {
        starting = false
        connectJob?.cancel()
        connecting = false
    }

    fun autoOpen() {
        if (servers.isEmpty() || current != null || connecting || starting) return
        val (sid, pid) = prefs.lastAgent.split("|", limit = 2).let { (it.getOrNull(0) ?: "") to it.getOrNull(1) }
        // The last agent's server first, then every other server that signs in without asking.
        // One that is down (a WebSocket server that is off, say) just moves on to the next; the
        // drawer still lists it as unreachable, and opening it later tries it again.
        val silent = servers.filter { canSignInSilently(it) && it.fingerprint != null }
        val order = silent.filter { it.id == sid } + silent.filter { it.id != sid }.sortedBy { it.overWebSocket }
        if (order.isEmpty()) return
        starting = true
        viewModelScope.launch {
            try {
                for (s in order) {
                    startingOn = s.label
                    error = null
                    pendingOpen = pid?.takeIf { s.id == sid }
                    connect(s, savedPassword(s), remember = false, openAgent = true)
                    connectJob?.join()
                    // A server waiting on a choice (which herdr to use, a new host key) stops here.
                    if (current != null || pendingBackendChoice != null || pendingHostKey != null) return@launch
                    com.billmongan.herdrdroid.diag.DiagLog.log("start", "${s.label} didn't answer at startup; trying the next server")
                }
                // No server answered: only now the server screen, with the last error on it.
                screen = Screen.Servers
            } finally {
                starting = false
                startingOn = null
            }
        }
    }

    // -- Send when idle --------------------------------------------------------------------------

    data class QueuedPrompt(val id: Long, val paneId: String, val text: String)

    var queued by mutableStateOf<List<QueuedPrompt>>(emptyList())
        private set
    private var queueJob: Job? = null

    /** Holds a message until the pane's agent is ready, then sends it. */
    fun enqueue(paneId: String, text: String) {
        queued = queued + QueuedPrompt(System.nanoTime(), paneId, text)
        if (queueJob?.isActive == true) return
        queueJob = viewModelScope.launch {
            while (isActive && queued.isNotEmpty()) {
                delay(3_000)
                refreshPanes()
                for (q in queued.toList()) {
                    val p = pane(q.paneId)
                    if (p == null) { queued = queued - q; continue }
                    if (p.status == Status.IDLE || p.status == Status.DONE) {
                        queued = queued - q
                        sendMessage(p, q.text)
                    }
                }
            }
        }
    }

    fun cancelQueued(q: QueuedPrompt) {
        queued = queued - q
    }

    // -- Work under a pane ---------------------------------------------------------------------------

    /** The latest read of each pane's processes, subagents and background tasks. */
    var work by mutableStateOf<Map<String, com.billmongan.herdrdroid.herdr.WorkInfo>>(emptyMap())
        private set
    /** For the cards: active subagents plus running background tasks, per pane. */
    var workCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var busyCounts by mutableStateOf<Map<String, Int>>(emptyMap())

    /**
     * The status a card shows: an agent herdr calls idle or done while its subagents or background
     * shells still run is shown as working.
     */
    /**
     * Remembers, per server and pane, when it last turned done and when you last had it open, for
     * every server (the open one and the watched ones). A new "done" begins only after real work:
     * the app's own usage check (it types /usage into an idle agent) makes it busy for a moment,
     * and that never counts, so an agent you already opened stays idle.
     */
    private fun trackDone(serverId: String, list: List<Pane>) {
        val tNow = System.currentTimeMillis()
        var changed = false
        list.forEach { p ->
            val k = "$serverId|${p.id}"
            val ownCheck = tNow - (probedAt[k] ?: 0L) < 60_000L
            when (p.status) {
                Status.DONE -> if (!doneSince.containsKey(k)) { doneSince[k] = tNow; changed = true }
                Status.WORKING, Status.NEEDS_YOU -> if (!ownCheck && doneSince.remove(k) != null) changed = true
                else -> {}
            }
            // An agent open on screen when it finishes has been seen.
            if (serverId == current?.id && (screen as? Screen.Agent)?.paneId == p.id && foreground) { seenInApp[k] = tNow; changed = true }
        }
        if (changed) saveSeen()
    }

    /** When the app last typed a usage check into each pane ("server|pane"). */
    private val probedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun shownStatus(p: Pane, serverId: String? = current?.id): Status {
        val here = serverId == current?.id
        // Hermes: a conversation elsewhere (Telegram, its app) working or waiting counts here too.
        if (here && p.agent?.lowercase() == "hermes" && p.status != Status.NEEDS_YOU) hermesState?.let { return it }
        if (here && (p.status == Status.IDLE || p.status == Status.DONE || p.status == Status.UNKNOWN) && (busyCounts[p.id] ?: 0) > 0) return Status.WORKING
        // The app's own usage check made it look busy for a moment: show what it was before.
        val k0 = "$serverId|${p.id}"
        // (The check only types into agents that are idle or done, so it was one or the other.)
        if (p.status == Status.WORKING && System.currentTimeMillis() - (probedAt[k0] ?: 0L) < 60_000L) {
            return if (doneSince.containsKey(k0)) shownStatus(p.copy(status = Status.DONE), serverId) else Status.IDLE
        }
        // herdr's "done" is idle but not yet seen at the computer. Opening the agent here counts as
        // seeing it, and after 30 minutes it counts as idle anyway. (The app never marks it seen in
        // herdr itself: that would switch the pane in front at the computer.)
        if (p.status == Status.DONE) {
            val since = doneSince["$serverId|${p.id}"] ?: return p.status
            val seen = seenInApp["$serverId|${p.id}"] ?: 0L
            if (seen >= since || System.currentTimeMillis() - since > 30 * 60_000L) return Status.IDLE
        }
        return p.status
    }
    private fun withBusy(list: List<Pane>, busy: Map<String, Int>): List<Pane> = com.billmongan.herdrdroid.herdr.withBusy(list, busy)
    // When each pane last turned done, and when you last had it open here ("server|pane"). Kept
    // across launches and reconnects, so an agent you opened stays idle until it works again.
    private val doneSince = loadSeen("done")
    private val seenInApp = loadSeen("seen")

    private fun loadSeen(part: String): MutableMap<String, Long> = runCatching {
        val o = org.json.JSONObject(prefs.seenPanes).optJSONObject(part) ?: org.json.JSONObject()
        o.keys().asSequence().associateWith { o.optLong(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun saveSeen() {
        val cutoff = System.currentTimeMillis() - 7L * 86_400_000
        fun json(m: Map<String, Long>) = org.json.JSONObject(m.filterValues { it >= cutoff })
        runCatching { prefs.seenPanes = org.json.JSONObject().put("done", json(doneSince)).put("seen", json(seenInApp)).toString() }
    }

    suspend fun refreshWork(paneId: String) {
        val p = pane(paneId) ?: return
        quietly { it.work(p, transcriptScript) }?.let { fresh ->
            // Processes that were active and are gone now are kept as finished (the last 30).
            val before = work[paneId]?.activeProcs(p.agent).orEmpty()
            val now = fresh.procs.map { it.pid }.toSet()
            val gone = before.filter { it.pid !in now }.map { EndedProc(it, System.currentTimeMillis()) }
            if (gone.isNotEmpty()) endedProcs = endedProcs + (paneId to (gone + endedProcs[paneId].orEmpty()).take(30))
            work = work + (paneId to fresh)
        }
    }

    /** A process the agent started that has since ended, and when the app noticed. */
    data class EndedProc(val proc: com.billmongan.herdrdroid.herdr.ProcInfo, val endedAt: Long)

    var endedProcs by mutableStateOf<Map<String, List<EndedProc>>>(emptyMap())
        private set

    /** Each card folder's git state, refreshed about every 30 seconds with the previews. */
    var gitInfo by mutableStateOf<Map<String, com.billmongan.herdrdroid.herdr.GitInfo>>(emptyMap())
        private set
    private var gitAt = 0L

    suspend fun refreshGit(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - gitAt < 30_000) return
        gitAt = System.currentTimeMillis()
        val cwds = panes.filter { it.agent != null && it.cwd.isNotBlank() }.map { it.cwd }.distinct()
        quietly { it.gitStatus(cwds, transcriptScript) }?.let { gitInfo = it }
    }

    /** Searches every agent's past conversations on this server. */
    suspend fun searchSessions(query: String): Result<List<com.billmongan.herdrdroid.herdr.SearchHit>> {
        val h = herdr ?: return Result.failure(IllegalStateException("Not connected."))
        return runCatching { h.search(query, transcriptScript) }
    }

    /** One past session found by search, read through the pinned reader (any folder will do). */
    suspend fun readHit(hit: com.billmongan.herdrdroid.herdr.SearchHit): Result<Pair<List<Item>, Boolean>> {
        val h = herdr ?: return Result.failure(IllegalStateException("Not connected."))
        val pane = Pane(id = "search", tabId = "", workspaceId = "", agent = hit.agent, status = Status.UNKNOWN, title = "", cwd = "/")
        val s = PastSession(hit.ref, hit.db, hit.whenSec, hit.title, 0)
        return runCatching {
            val r = h.transcript(pane, s.pinCursor(), transcriptScript) ?: throw IllegalStateException("No reader for ${hit.agent}.")
            if (!r.ok) throw IllegalStateException(r.why ?: "That session couldn't be read.")
            Transcript.merge(emptyList(), r.items, true) to r.more
        }
    }

    suspend fun refreshWorkCounts() {
        quietly { it.workCounts(panes, transcriptScript) }?.let { (c, b) -> workCounts = c; busyCounts = b }
    }

    /** One Claude subagent's log since [cursor] (its file is pinned in the cursor). */
    suspend fun readSubagent(paneId: String, cursor: String): Result<com.billmongan.herdrdroid.transcript.TranscriptRead> {
        val p = pane(paneId) ?: return Result.failure(IllegalStateException("That pane is gone."))
        val h = herdr ?: return Result.failure(IllegalStateException("Not connected."))
        return runCatching {
            val r = h.transcript(p, cursor, transcriptScript) ?: throw IllegalStateException("No agent in this pane.")
            if (!r.ok) throw IllegalStateException(r.why ?: "That subagent's log couldn't be read.")
            r
        }
    }

    suspend fun tailTask(file: String, offset: Long): Result<com.billmongan.herdrdroid.herdr.TailRead> {
        val h = herdr ?: return Result.failure(IllegalStateException("Not connected."))
        return runCatching { h.tail(file, offset, transcriptScript) }
    }

    /** Stops a process (and its children) in a pane, after the user confirmed it. */
    fun stopProcess(paneId: String, pid: Int, force: Boolean, done: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val h = herdr
            val r = if (h == null) Result.failure(IllegalStateException("Not connected.")) else runCatching { h.stopProcess(paneId, pid, force, transcriptScript) }
            com.billmongan.herdrdroid.diag.DiagLog.log("work", "stop ${if (force) "KILL" else "TERM"} pid $pid in $paneId: " +
                r.fold({ "sent to $it process(es)" }, { "failed: ${it.message}" }))
            done(r)
            refreshWork(paneId)
        }
    }

    // -- History and export -------------------------------------------------------------------------

    /** The open pane's agent's past sessions in its folder, newest first. */
    suspend fun pastSessions(paneId: String): Result<List<PastSession>> {
        val p = pane(paneId) ?: return Result.failure(IllegalStateException("That pane is gone."))
        val agent = p.agent ?: return Result.failure(IllegalStateException("No agent in this pane."))
        val h = herdr ?: return Result.failure(IllegalStateException("Not connected."))
        return runCatching { h.sessions(agent, p.cwd, transcriptScript) }
    }

    /** One past session's conversation, read without touching the live one. */
    suspend fun readPastSession(paneId: String, s: PastSession): Result<Pair<List<Item>, Boolean>> {
        val p = pane(paneId) ?: return Result.failure(IllegalStateException("That pane is gone."))
        val h = herdr ?: return Result.failure(IllegalStateException("Not connected."))
        return runCatching {
            val r = h.transcript(p, s.pinCursor(), transcriptScript) ?: throw IllegalStateException("No agent in this pane.")
            if (!r.ok) throw IllegalStateException(r.why ?: "That session couldn't be read.")
            Transcript.merge(emptyList(), r.items, true) to r.more
        }
    }

    /** Shares a conversation as a Markdown file. */
    fun exportMarkdown(context: android.content.Context, title: String, agent: String, items: List<Item>) {
        val md = toMarkdown(title, agent, items)
        val name = title.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(60).ifBlank { "conversation" } + ".md"
        com.billmongan.herdrdroid.ui.shareBytes(context, md.toByteArray(), name, "text/markdown", open = false)
    }

    // -- Model -------------------------------------------------------------------------------------

    /** Types a model command into an idle pane and presses Enter (the agent's own picker answers it). */
    fun runCommand(paneId: String, command: String) {
        val p = pane(paneId) ?: return
        if (p.status == Status.WORKING) {
            error = "${p.agentName} is working. Change the model once it's done."
            return
        }
        answer(paneId, command, pressEnter = true)
    }

    // -- Forwarding -------------------------------------------------------------------------------

    private val prefills = mutableStateMapOf<String, String>()

    /** Opens [to] with a reply from another agent already in its message box. */
    fun forward(text: String, from: Pane?, to: Pane) {
        prefills[to.id] = "From ${from?.agentName ?: "another agent"}:\n\n" + text.trim() + "\n\n"
        openPane(to.id)
    }

    fun takePrefill(paneId: String): String? = prefills.remove(paneId)

    /** The next (or previous) agent pane in the list, for swiping between agents. */
    fun neighbor(paneId: String, step: Int): String? {
        val list = panes.filter { it.agent != null }
        val i = list.indexOfFirst { it.id == paneId }
        if (i < 0 || list.isEmpty()) return null
        return list[(i + step + list.size) % list.size].id
    }

    // -- Files ------------------------------------------------------------------------------------

    suspend fun listDir(path: String): Result<List<DirEntry>> {
        val h = herdr ?: return Result.failure(IllegalStateException("Not connected."))
        return runCatching { h.listDir(path) }
    }

    fun openFiles(path: String) {
        screen = Screen.Files(path, screen)
    }

    /** Runs one file action and reports the outcome to [done] on the main thread. */
    private fun fileAction(label: String, done: (Result<String>) -> Unit, run: suspend (Herdr) -> String) {
        viewModelScope.launch {
            val h = herdr
            val r = if (h == null) Result.failure(IllegalStateException("Not connected.")) else runCatching { run(h) }
            done(r.recoverCatching { throw IllegalStateException("$label: ${it.message}") })
        }
    }

    /** Copies a file picked on the phone into [dir] on the computer. */
    fun uploadInto(dir: String, uri: Uri, done: (Result<String>) -> Unit) {
        val app = getApplication<Application>()
        fileAction("Couldn't upload", done) { h ->
            val (name, bytes) = withContext(Dispatchers.IO) {
                val n = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "upload"
                n to (app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0))
            }
            if (bytes.size > 50 * 1024 * 1024) throw IllegalArgumentException("That file is over 50 MB.")
            h.uploadTo(dir, name, bytes)
        }
    }

    fun makeFolder(dir: String, name: String, done: (Result<String>) -> Unit) =
        fileAction("Couldn't make the folder", done) { h -> h.makeFolder(dir, name); name }

    fun renameFile(path: String, name: String, done: (Result<String>) -> Unit) =
        fileAction("Couldn't rename", done) { h -> h.rename(path, name); name }

    fun recycleFile(path: String, done: (Result<String>) -> Unit) =
        fileAction("Couldn't move it to ~/scratch", done) { h -> h.recycle(path) }

    // ---- Messages ---------------------------------------------------------------------------

    private fun hostKeyMessage(e: HostKeyChanged) =
        "This computer's identity changed since you last connected, so the app stopped. " +
            "Expected ${e.expected} but got ${e.actual}. If you know why it changed, open the " +
            "server's settings and forget the saved fingerprint."

    private fun authMessage(server: Server?, creds: Credentials?, e: Exception): String {
        val usedKey = creds?.key != null || (server?.useKey == true && server.hasKey)
        val usedPw = creds?.password != null || server?.passwordCipher != null
        return when {
            e.message?.startsWith("The imported key") == true || e.message?.startsWith("No key") == true ||
                e.message?.startsWith("This server signs in") == true -> e.message!!
            server?.overWebSocket == true ->
                "The server didn't accept this phone's key. Add the public key from this server's settings to " +
                    "~/.ssh/authorized_keys for ${server.user} on that computer."
            usedKey && usedPw -> "The server didn't accept the key or the password."
            usedKey -> "The server didn't accept this phone's key."
            else -> "The username or password was not accepted."
        }
    }

    private fun friendly(e: Exception): String {
        val root = (e as? LinkDown)?.cause ?: e
        val m = root.message ?: root.javaClass.simpleName
        return when {
            m.contains("WebSocket", false) -> m
            m.contains("timed out", true) || m.contains("timeout", true) ->
                "The computer didn't answer. Check the address, the port, and that you're on the right network."
            m.contains("refused", true) -> "The computer refused the connection. Is SSH running on that port?"
            m.contains("UnknownHost", true) || m.contains("Unable to resolve", true) ->
                "That address couldn't be found."
            m.contains("unreachable", true) -> "The network can't reach that computer right now."
            else -> m
        }
    }

    override fun onCleared() {
        ssh?.close()
        super.onCleared()
    }
}
