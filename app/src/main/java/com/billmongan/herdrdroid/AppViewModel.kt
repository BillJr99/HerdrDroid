package com.billmongan.herdrdroid

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.billmongan.herdrdroid.fleet.Fleet
import com.billmongan.herdrdroid.fleet.FleetState
import com.billmongan.herdrdroid.herdr.Candidate
import com.billmongan.herdrdroid.herdr.DirEntry
import com.billmongan.herdrdroid.herdr.PastSession
import com.billmongan.herdrdroid.herdr.forPhone
import com.billmongan.herdrdroid.transcript.lastReply
import com.billmongan.herdrdroid.transcript.toMarkdown
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.herdr.rankCandidates
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
    data class EditServer(val server: Server?) : Screen
    data object Tabs : Screen
    data class Agent(val paneId: String) : Screen
    data class Settings(val back: Screen) : Screen
    data class Files(val path: String, val back: Screen) : Screen
    data object Dispatch : Screen
    data object Compare : Screen
    /**
     * What runs under a pane: processes, subagents and background tasks. With [kind] "sub" or
     * "task", one subagent's conversation or one task's output ([file]), read-only.
     */
    data class Work(val paneId: String, val kind: String? = null, val file: String? = null, val title: String? = null) : Screen
    /** Search every agent's past conversations on this server. */
    data object Search : Screen
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

    fun connect(server: Server, password: String?, remember: Boolean) {
        if (connecting) return
        connecting = true
        error = null
        val run = ++connectRun
        connectJob = viewModelScope.launch {
            val creds = credentialsFor(server, password)
            val s = HerdrSsh(server.host, server.port, server.user, creds, server.fingerprint, SshKeyVault::provider, server.wsUrl?.takeIf { server.overWebSocket })
            connectingSsh = s
            try {
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
                val h = Herdr(s, server.herdrPath, backend, found.hcPath)
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
                connectNote = null
                var saved = server
                if (remember && password != null) saved = saved.copy(passwordCipher = PasswordVault.encrypt(password))
                if (saved != server) store.save(saved)
                reloadServers()
                closeSession()
                ssh = s
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
                screen = Screen.Tabs
                transcriptPane = null
                refreshPanes()
                prefetchChats()
                if (showQuotas) startQuotaChecks()
                startWatching()
                pendingOpen?.let { p -> pendingOpen = null; if (panes.any { it.id == p }) openPane(p) }
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
                        val list = h.panes()
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
            if ((stored[agentKey]?.at ?: 0L) > now - every) continue
            val items = if (profile.cliCommand != null) {
                runCatching { h.cliQuota(profile) }.getOrNull()
            } else {
                if (!h.canProbeScreens) continue
                val target = list.filter { it.status == Status.IDLE || it.status == Status.DONE }.sortedBy { it.focused }.firstOrNull() ?: continue
                runCatching { h.probeQuota(target.id, profile, working = false) }.getOrNull()?.items
            }
            if (!items.isNullOrEmpty()) {
                val r = QuotaReading(System.currentTimeMillis(), items)
                stored[agentKey] = r
                recordQuota(s.id, agentKey, r)
            }
        }
        prefs.saveQuotas(s.id, stored)
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

    private fun closeSession() {
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

    fun disconnect() {
        fleet?.stop()
        fleet = null
        watchJob?.cancel()
        WatchService.stop(getApplication())
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
            panes = fresh
            current?.let { s -> noticeChanges(s.id, s.label, fresh) { herdr } }
            collectFinishedHelpers(fresh)
            updateWidget(fresh)
            current?.let { serverPanes = serverPanes + (it.id to fresh) }
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
            if (seen.isNotEmpty()) models = models + seen
        }
        // Claude's footer has no model, so its cards take it from Claude's own session log.
        quietly { it.logModels(panes) }?.let { fromLog -> if (fromLog.isNotEmpty()) models = models + fromLog }
        refreshWorkCounts()
        refreshGit()
        current?.let { previewCache[it.id] = previews; modelCache[it.id] = models }
    }

    suspend fun readPane(paneId: String) {
        quietly { it.read(paneId) }?.let { text ->
            paneText = text
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

    fun openPane(paneId: String) {
        paneText = ""
        resetTranscript(paneId)
        screen = Screen.Agent(paneId)
    }

    // ---- Structured conversations ------------------------------------------------------------

    private val readers = setOf("claude", "codex", "pi", "omp", "grok", "opencode", "hermes", "copilot")

    fun hasTranscriptReader(paneId: String) = pane(paneId)?.agent?.lowercase() in readers

    fun resetTranscript(paneId: String) {
        if (transcriptPane == paneId) return
        // Keep the chat being left, so going back to it is instant.
        transcriptPane?.let { old ->
            if (transcriptState == TranscriptMode.ON) transcriptCache[ck(old)] = CachedChat(transcriptItems, transcriptCursor, transcriptMore, transcriptTitle, transcriptStart, transcriptFile)
        }
        transcriptPane = paneId
        val c = transcriptCache[ck(paneId)]
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

    /** One agent's chat kept in memory: what it showed and where its log was read up to. */
    private data class CachedChat(val items: List<Item>, val cursor: String, val more: Boolean, val title: String?, val start: Long = -1L, val file: String? = null)

    /** Every chat read so far, per server and pane; kept until the app exits. */
    private val transcriptCache = java.util.concurrent.ConcurrentHashMap<String, CachedChat>()
    private fun ck(paneId: String) = "${current?.id}|$paneId"
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
            val order = listOf(Status.NEEDS_YOU, Status.WORKING, Status.DONE, Status.IDLE, Status.UNKNOWN)
            for (p in panes.filter { hasTranscriptReader(it.id) }.sortedBy { order.indexOf(it.status) }) {
                if (transcriptCache.containsKey(ck(p.id)) || transcriptPane == p.id) continue
                val r = quietly { it.transcript(p, "", transcriptScript) } ?: continue
                if (!r.ok || transcriptPane == p.id) continue
                val cj = runCatching { org.json.JSONObject(r.cursor) }.getOrNull()
                transcriptCache[ck(p.id)] = CachedChat(Transcript.merge(emptyList(), r.items, true), r.cursor, r.more, r.title,
                    cj?.optLong("start", -1L) ?: -1L, cj?.optString("file")?.ifBlank { null })
            }
        }
    }

    /** Reads what the pane's agent added to its session log since the last read. */
    suspend fun readTranscript(paneId: String) {
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
        if (!foreground || link != Link.CONNECTED || transcriptPane != paneId) return false
        val started = System.currentTimeMillis()
        var got = false
        val ok = runCatching {
            h.followTranscript(p, transcriptCursor, transcriptScript, 120) { r ->
                withContext(Dispatchers.Main) { if (transcriptPane == paneId) { got = true; applyTranscript(paneId, r) } }
            }
        }.isSuccess
        // A stream that ended at once without any read didn't work here; poll instead.
        return ok && (got || System.currentTimeMillis() - started > 5_000)
    }

    private fun applyTranscript(paneId: String, r: com.billmongan.herdrdroid.transcript.TranscriptRead) {
        if (transcriptPane != paneId) return
        if (!r.ok) {
            if (transcriptItems.isEmpty()) transcriptState = TranscriptMode.OFF
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
        val keys = com.billmongan.herdrdroid.herdr.menuKeys(prompt, choice)
        if (keys == null) { answer(paneId, choice.keys, choice.pressEnter); return }
        act(paneId, "Couldn't answer") { h ->
            h.sendKeys(paneId, *keys.toTypedArray())
            readPane(paneId)
            refreshPanes()
        }
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
                probingPanes = probingPanes + target.id
                val result = try {
                    quietly { it.probeQuota(target.id, profile, working = target.status == Status.WORKING) }
                } finally {
                    probingPanes = probingPanes - target.id
                }
                if (result != null) watchForLeftoverScreen(target.id, profile)
                result?.model?.let { models = models + (target.id to it) }
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

    // ---- 0.4.0: alerts, other servers, dispatch, compare, queue, helpers, files ------------------

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
            arr.put(org.json.JSONObject().put("at", r.at).put("used", org.json.JSONObject(r.items.associate { it.label to it.usedPercent })))
            val cutoff = r.at - 8L * 24 * 3600 * 1000
            val kept = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optLong("at") >= cutoff }.takeLast(400)
            all.put(k, org.json.JSONArray(kept))
            prefs.quotaHistory = all.toString()
        }
    }

    /** The open server's readings for this pane's agent, oldest first. */
    fun quotaHistoryFor(pane: Pane?): List<QuotaPoint> {
        val key = QuotaProfiles.key(pane?.agent) ?: return emptyList()
        val sid = current?.id ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONObject(prefs.quotaHistory).optJSONArray("$sid|$key") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val u = o.getJSONObject("used")
                QuotaPoint(o.optLong("at"), u.keys().asSequence().associateWith { u.optDouble(it) })
            }
        }.getOrDefault(emptyList())
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
    private fun startWatching() {
        if (herdr == null) return
        if (watchOthers) {
            val f = fleet ?: Fleet(
                viewModelScope,
                credentials = { s -> credentialsFor(s, savedPassword(s)) },
                keystoreKey = SshKeyVault::provider,
                onSeen = { st -> noticeChanges(st.server.id, st.server.label, st.panes) { fleet?.herdr(st.server.id) } },
            ).also { fleet = it }
            // Other servers are read every 5 seconds while alerts are on, 15 when only listed.
            f.everyMs = if (watching) 5_000L else 15_000L
            f.watch(watchable())
        } else {
            fleet?.stop()
            fleet = null
        }
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
            openPane(paneId)
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
        val s = servers.firstOrNull { it.id == serverId } ?: return
        pendingOpen = paneId
        connect(s, savedPassword(s), remember = false)
    }

    // -- Dispatch and compare --------------------------------------------------------------------

    fun candidates(sensitive: Boolean): List<Candidate> = rankCandidates(panes, ::quotaFor, sensitive)

    /** Sends one task to each target, or queues it for a busy one when [whenIdle] is on. */
    fun dispatch(text: String, targets: List<Pane>, whenIdle: Boolean) {
        for (p in targets) {
            if (whenIdle && p.status == Status.WORKING) enqueue(p.id, text) else sendMessage(p, text)
        }
        if (targets.size > 1) {
            compareTargets = targets.map { it.id }
            compareItems.clear()
            compareCursors.clear()
            screen = Screen.Compare
        } else targets.firstOrNull()?.let { openPane(it.id) }
    }

    var compareTargets by mutableStateOf<List<String>>(emptyList())
        private set
    val compareItems = mutableStateMapOf<String, List<Item>>()
    private val compareCursors = mutableMapOf<String, String>()

    /** Reads each compared pane's log since the last read (each with its own cursor). */
    suspend fun refreshCompare() {
        for (id in compareTargets) {
            val p = pane(id) ?: continue
            val r = quietly { it.transcript(p, compareCursors[id].orEmpty(), transcriptScript) } ?: continue
            if (!r.ok) continue
            compareCursors[id] = r.cursor
            if (r.reset || r.items.isNotEmpty()) compareItems[id] = Transcript.merge(compareItems[id].orEmpty(), r.items, r.reset)
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

    // -- New agents and helpers ------------------------------------------------------------------

    var starting by mutableStateOf<String?>(null)
        private set

    private fun mainWorkspace(): String? = panes.groupingBy { it.workspaceId }.eachCount().maxByOrNull { it.value }?.key

    /**
     * Starts an agent of [kind] in a new tab at [cwd]. A [task] is sent once it is ready. Helpers
     * get a "helper-" label so the list can offer to close them when they are finished.
     */
    fun startAgent(kind: String, cwd: String, label: String, task: String?) {
        if (starting != null) return
        starting = label
        act(null, "Couldn't start $kind") { h ->
            try {
                val name = label.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').take(32).let { if (it.firstOrNull()?.isLetter() == true) it else "a$it" }
                val pane = h.startAgent(kind, cwd, label, name, mainWorkspace())
                refreshPanes()
                if (!task.isNullOrBlank()) pane(pane)?.let {
                    sendMessage(it, task)
                    if (label.startsWith("helper-") && autoCloseHelpers) helperWatch[pane] = HelperRun(label, task, false)
                }
                openPane(pane)
            } finally {
                starting = null
            }
        }
    }

    fun nextHelperLabel(kind: String): String {
        val n = panes.count { it.tabLabel.startsWith("helper-") } + 1
        return "helper-$kind-$n"
    }

    fun closeHelper(p: Pane) {
        act(p.id, "Couldn't close the helper") { h ->
            h.closeTab(p.tabId)
            refreshPanes()
        }
    }

    // -- Helpers that finish -----------------------------------------------------------------------

    private data class HelperRun(val label: String, val task: String, val sawWork: Boolean)

    /** A finished helper's last reply, kept after its tab is closed. */
    data class HelperResult(val id: Long, val label: String, val agent: String, val task: String, val reply: String)

    private val helperWatch = mutableMapOf<String, HelperRun>()
    var helperResults by mutableStateOf<List<HelperResult>>(emptyList())
        private set
    var autoCloseHelpers by mutableStateOf(prefs.autoCloseHelpers)
        private set

    fun changeAutoCloseHelpers(on: Boolean) {
        autoCloseHelpers = on
        prefs.autoCloseHelpers = on
        if (!on) helperWatch.clear()
    }

    /**
     * A helper started with a task is closed once it has worked and gone quiet: its last reply is
     * read from its log into "Helper results" first. One that asks a question is left open.
     */
    private fun collectFinishedHelpers(fresh: List<Pane>) {
        for ((id, run) in helperWatch.toMap()) {
            val p = fresh.firstOrNull { it.id == id }
            if (p == null) { helperWatch.remove(id); continue }
            when {
                p.status == Status.WORKING -> if (!run.sawWork) helperWatch[id] = run.copy(sawWork = true)
                run.sawWork && (p.status == Status.DONE || p.status == Status.IDLE) -> {
                    helperWatch.remove(id)
                    viewModelScope.launch {
                        val h = herdr ?: return@launch
                        val r = runCatching { h.transcript(p, "", transcriptScript) }.getOrNull()
                        val items = if (r?.ok == true) Transcript.merge(emptyList(), r.items, true) else emptyList()
                        val reply = lastReply(items).ifBlank {
                            runCatching { forPhone(h.read(p.id, 60)) }.getOrDefault("").trim()
                        }
                        helperResults = helperResults + HelperResult(System.nanoTime(), run.label, p.agentName, run.task, reply)
                        runCatching { h.closeTab(p.tabId) }
                        refreshPanes()
                    }
                }
            }
        }
    }

    fun dismissHelperResult(r: HelperResult) {
        helperResults = helperResults - r
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
    fun shownStatus(p: Pane): Status =
        if ((p.status == Status.IDLE || p.status == Status.DONE) && (busyCounts[p.id] ?: 0) > 0) Status.WORKING else p.status

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
