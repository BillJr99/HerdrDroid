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
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.cardAnswerStillFits
import com.billmongan.herdrdroid.herdr.moveKeys
import com.billmongan.herdrdroid.hermes.HermesChat
import com.billmongan.herdrdroid.notify.AppBus
import com.billmongan.herdrdroid.notify.Notify
import com.billmongan.herdrdroid.notify.NotifyAction
import com.billmongan.herdrdroid.notify.notificationAnswerStillFits
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
import com.billmongan.herdrdroid.herdr.recoverPaneRead
import com.billmongan.herdrdroid.herdr.QuotaProfile
import com.billmongan.herdrdroid.herdr.QuotaProfiles
import com.billmongan.herdrdroid.herdr.QuotaReading
import com.billmongan.herdrdroid.herdr.UsageOverlay
import com.billmongan.herdrdroid.herdr.dismissUsageOverlay
import com.billmongan.herdrdroid.herdr.dismissibleUsageFrame
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
private class Queued(
    val paneId: String?,
    val label: String,
    val pendingId: Long?,
    val dispatchKey: Pair<String, String>?,
    val run: suspend (Herdr) -> Unit,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ServerStore(app)
    private val prefs = AppPrefs(app)

    private val chatVisit = com.billmongan.herdrdroid.voice.SelectedChatVisit()
    private var currentScreen by mutableStateOf<Screen>(Screen.Servers)
    var screen: Screen
        get() = currentScreen
        set(value) {
            chatVisit.navigate((currentScreen as? Screen.Agent)?.paneId, (value as? Screen.Agent)?.paneId)
            currentScreen = value
        }
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
    internal var paneTextTarget by mutableStateOf<CardTarget?>(null)
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
    /** The open chat's item times on the computer's clock, by key (1.4.3: questions are placed by them). */
    var transcriptTimes by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    /** When the app first saw each item of the open chat, on the computer's clock (1.4.3). */
    var transcriptSeen by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    /**
     * Each server's phone clock minus its computer's clock, in ms (1.4.3): the smallest of the last
     * readings of (phone time a read arrived) minus (the computer's "now" in it), since the time a read
     * takes to arrive only ever adds to that difference (from review). Compose state, so a chat drawn
     * before the first reading is placed again once it arrives.
     */
    private val offsetSamples = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Long>>()
    var hostOffsets by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    /** The open server's clock offset, for placing questions (phone time) among chat items (computer time). */
    fun hostOffsetMs(): Long = current?.id?.let { hostOffsets[it] } ?: 0L
    private fun noteHostClock(r: com.billmongan.herdrdroid.transcript.TranscriptRead) {
        val sid = current?.id ?: return
        if (r.hostNow <= 0) return
        val q = offsetSamples.getOrPut(sid) { ArrayDeque() }
        val best = synchronized(q) {
            q.addLast(System.currentTimeMillis() - r.hostNow)
            while (q.size > 10) q.removeFirst()
            q.min()
        }
        if (hostOffsets[sid] != best) hostOffsets = hostOffsets + (sid to best)
    }
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
    private var foreground by mutableStateOf(true)
    /** The app is in front (the screens' own loops read nothing while it isn't, 1.4.0). */
    val inFront: Boolean get() = foreground

    /** The phone's screen is on; the background loops slow down while it is off (1.4.0). */
    fun screenOn(): Boolean = runCatching {
        (getApplication<Application>().getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    }.getOrDefault(true)
    private var backgroundAt = 0L
    private var downSince = 0L
    private var reconnectJob: Job? = null
    private var quotaJob: Job? = null
    private val queue = ArrayDeque<Queued>()
    private val quotaLock = Mutex()
    private val lastQuotaTry = mutableMapOf<String, Long>()
    /** Duplicate explicit dismissals must not enqueue another key. */
    private val dismissingUsage = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** When a background check last tried each agent ("server/agent"), read or not, so a screen with no limits on it isn't asked again every round. */
    private val backgroundTry = java.util.concurrent.ConcurrentHashMap<String, Long>()
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
        questionLogs.keys.filter { it.startsWith("${server.id}|") }.forEach { questionLogs.remove(it) }
        store.delete(server.id)
        SshKeyVault.delete(server.keystoreAlias)
        prefs.forgetQuotas(server.id)
        reloadServers()
        screen = Screen.Servers
    }

    fun savedPassword(server: Server): String? =
        server.passwordCipher?.let { c -> runCatching { PasswordVault.decrypt(c) }.onFailure { lockedFor(server, it) }.getOrNull() }

    /** Servers already told "unlock to keep watching" since the last unlock. */
    private val lockedNoticeSent = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * A saved secret couldn't be read because the fingerprint window passed (1.3.1): in the
     * background, one notification per server says to unlock, instead of watching failing silently.
     */
    private fun lockedFor(server: Server, e: Throwable) {
        if (e !is com.billmongan.herdrdroid.store.VaultLocked) return
        com.billmongan.herdrdroid.diag.DiagLog.log("vault", "${server.label}: saved secret locked (fingerprint window passed)")
        if (!foreground && lockedNoticeSent.add(server.id)) Notify.locked(getApplication(), server.id, server.label)
    }

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
                }.onFailure { lockedFor(server, it) }.getOrNull()
            }
            else -> null
        }
        return Credentials(key, password)
    }

    fun connect(server: Server, password: String?, remember: Boolean, openAgent: Boolean = false, voice: Boolean = false) {
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
                // Each agent's version on this computer, once per connection (1.4.0), for the parsers.
                agentVersions = emptyMap()
                viewModelScope.launch { quietly { it.agentVersions() }?.let { v -> if (current?.id == saved.id) agentVersions = v } }
                lastStatus.clear()
                // From the drawer or at startup the agent opens directly, with no stop on the tab list.
                if (!openAgent) screen = Screen.Tabs
                transcriptPane = null
                refreshPanes(allowBackground = voice)
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
    /**
     * True until the fingerprint is given, when the lock is on, and (1.3.1) whenever the saved
     * secrets' 4-hour fingerprint window has passed or they still need re-sealing under the new
     * keys. Nothing is contacted while locked.
     */
    var locked by mutableStateOf(appLock || vaultNeedsUnlock())
        private set

    /** The saved passwords, keys and chats can't be read now, or still need their one-time re-seal. */
    private fun vaultNeedsUnlock(): Boolean = runCatching {
        com.billmongan.herdrdroid.store.Vaults.migrationPending(getApplication()) || com.billmongan.herdrdroid.store.Vaults.locked()
    }.getOrDefault(false)

    fun changeAppLock(on: Boolean) {
        appLock = on
        prefs.appLock = on
    }

    fun unlocked() {
        com.billmongan.herdrdroid.store.Vaults.justUnlocked()
        lockedNoticeSent.clear()
        // The first unlock after 1.3.0 re-seals the saved secrets and chats under the new keys.
        if (com.billmongan.herdrdroid.store.Vaults.migrationPending(getApplication())) viewModelScope.launch(Dispatchers.IO) {
            val r = com.billmongan.herdrdroid.store.Vaults.migrate(getApplication(), store, java.io.File(getApplication<Application>().noBackupFilesDir, "chats"))
            com.billmongan.herdrdroid.diag.DiagLog.log("vault", "migration: $r")
            withContext(Dispatchers.Main) { reloadServers() }
        }
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

    /** Collect due CLI reports and footer readings on the background server round. */
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
            if (!profile.hasPassiveSource) continue
            // Let the open server collect its own footer readings.
            if (s.id == current?.id && profile.cliCommand == null) continue
            backgroundTry["${s.id}/$agentKey"] = now
            val items = try {
                h.readQuota(list.map { it.id }, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
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
        openUsage = null
        hermes?.close()
        hermes = null
        reconnectJob?.cancel()
        quotaJob?.cancel()
        queue.clear()
        pendingSends = pendingSends.filterNot { it.awaitingLink }
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
        // The fingerprint window passed while the app was away: ask again before showing anything.
        if (!locked && vaultNeedsUnlock()) locked = true
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
                // Behind, only while Watching (1.4.0, from a code review: alerts for the open server
                // stopped until the app was opened again), and never for a WebSocket server, which
                // isn't held open in the background; then no faster than every 15 s (60 s, screen off).
                if (!foreground) {
                    if (!watching || current?.overWebSocket == true) { delay(1_000); continue }
                    wait = if (screenOn()) 15_000L else 60_000L
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
                // A second at a time, so coming back to the front ends a long background wait at once
                // (1.4.1, from a code review: the 15 or 60 s gap went on after the app was opened).
                // Checked each second (1.4.1, from a second review: also when the app came to the
                // front during the attempt itself, before the wait began).
                var waited = 0L
                var cutShort = false
                while (waited < wait) {
                    if (foreground && wait > 8_000) { cutShort = true; break }
                    delay(1_000); waited += 1_000
                }
                wait = if (cutShort) 1_000L else (wait * 2).coerceAtMost(8_000)
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
    private suspend fun <T> quietly(allowBackground: Boolean = false, block: suspend (Herdr) -> T): T? {
        if (!canPoll && !(allowBackground && !locked && link == Link.CONNECTED)) return null
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

    private val paneDispatch = com.billmongan.herdrdroid.herdr.PaneDispatch()

    /**
     * Runs something the user tapped. While the link is down it waits in a queue and goes out once
     * the link is back; only a real refusal from herdr is shown.
     */
    private fun act(paneId: String?, label: String, pendingId: Long? = null, input: Boolean = false, run: suspend (Herdr) -> Unit) {
        val key = if (paneId != null && (pendingId != null || input)) current?.id?.let { it to paneId } else null
        viewModelScope.launch {
            if (link != Link.CONNECTED || herdr == null) {
                enqueue(Queued(paneId, label, pendingId, key, run))
                return@launch
            }
            perform(Queued(paneId, label, pendingId, key, run))
        }
    }

    private suspend fun perform(q: Queued) {
        val h = herdr ?: return
        try {
            val key = q.dispatchKey
            if (key == null) q.run(h)
            else paneDispatch.run(key) {
                if (current?.id != key.first) return@run
                val connected = herdr
                if (connected != null && link == Link.CONNECTED) q.run(connected) else enqueue(q)
            }
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
        q.pendingId?.let { id ->
            if (pendingSends.none { it.id == id }) return
            pendingSends = pendingSends.map { if (it.id == id) it.copy(awaitingLink = true) else it }
        }
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

    suspend fun refreshPanes(allowBackground: Boolean = false) {
        val client = herdr
        quietly(allowBackground) { it.panes() }?.let { fresh0 ->
            if (herdr !== client) return
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

    /** How many lines the alerts read, when made and when answered: the same both times (1.4.2). */
    private val NOTIFY_READ_LINES = 80

    /** Why the open pane's screen couldn't be read, when the last read failed (1.4.2); null after a good read. */
    var paneReadProblem by mutableStateOf<String?>(null)
        private set

    private var paneReadSequence = 0L
    private var lastAppliedPaneRead = 0L

    internal data class OpenUsage(val server: String, val pane: String, val visit: Long, val client: Herdr, val agent: String, val overlay: UsageOverlay)
    private var openUsage by mutableStateOf<OpenUsage?>(null)
    private var closingUsage by mutableStateOf<OpenUsage?>(null)

    private fun currentUsage(usage: OpenUsage): Boolean = foreground && canPoll && !locked &&
        current?.id == usage.server && herdr === usage.client && paneVisit == usage.visit &&
        (screen as? Screen.Agent)?.paneId == usage.pane && pane(usage.pane)?.agent == usage.agent

    internal fun usageOverlayFor(paneId: String): OpenUsage? = openUsage?.takeIf {
        it.pane == paneId && paneReadProblem == null && currentUsage(it)
    }

    internal fun usageDismissInFlight(paneId: String): Boolean = closingUsage?.let { it.pane == paneId && currentUsage(it) } == true

    internal fun dismissUsage(usage: OpenUsage) {
        if (usageOverlayFor(usage.pane) != usage || closingUsage != null) return
        val dismissKey = "${usage.server}/${usage.pane}"
        if (!dismissingUsage.add(dismissKey)) return
        closingUsage = usage
        viewModelScope.launch {
            try {
                val sent = quietly { h ->
                    dismissUsageOverlay(usage.overlay, { currentUsage(usage) && usageOverlayFor(usage.pane) == usage },
                        { h.readUsageOverlay(usage.pane, usage.agent) },
                        { h.closeUsageOverlay(usage.pane, usage.agent, usage.overlay) })
                } == true
                if (currentUsage(usage)) {
                    openUsage = null
                    if (sent) {
                        delay(400) // An accepted key isn't proof of closure; wait for a redraw.
                    }
                    if (currentUsage(usage)) {
                        readPane(usage.pane)
                        if (currentUsage(usage) && (!sent || paneTextPane != usage.pane || paneReadProblem != null ||
                                dismissibleUsageFrame(usage.agent, paneText) != null)) {
                            flash("Couldn't confirm that usage closed. Check the pane before trying again.")
                        }
                    }
                }
            } finally {
                closingUsage = null
                dismissingUsage.remove(dismissKey)
            }
        }
    }

    suspend fun readPane(paneId: String) {
        val attempt = ++paneReadSequence
        // Only for the pane open now (1.4.2, from review: a late answer for another pane set or
        // cleared the open pane's problem).
        val serverId = current?.id
        val client = herdr
        val visit = paneVisit
        val started = android.os.SystemClock.elapsedRealtime()
        val cardOwner = cardTarget(paneId)
        fun isOpen() = attempt > lastAppliedPaneRead && current?.id == serverId && herdr === client &&
            paneVisit == visit && (screen as? Screen.Agent)?.paneId == paneId
        fun problem(why: String) {
            if (!isOpen()) return
            lastAppliedPaneRead = attempt
            if (paneReadProblem != why) com.billmongan.herdrdroid.diag.DiagLog.log("read", "$paneId: the screen couldn't be read: $why")
            paneReadProblem = why
            paneTextTarget = null
            openUsage = null
        }
        quietly { h ->
            val waiting = pane(paneId)?.status == Status.NEEDS_YOU
            try {
                readPaneScreen(h, paneId, if (waiting || paneReadProblem != null) 80 else 200)
            } catch (e: HerdrError) {
                problem(e.message?.take(120) ?: "the screen could not be read")
                null
            }
        }?.let { text ->
            if (!isOpen()) return
            val agent = pane(paneId)?.agent
            val liveUsage = if (client != null && serverId != null && agent != null && dismissibleUsageFrame(agent, text) != null) {
                quietly { h -> if (h === client) h.readUsageOverlay(paneId, agent) else null }
                    ?.let { OpenUsage(serverId, paneId, visit, client, agent, it) }
            } else null
            if (!isOpen()) return
            lastAppliedPaneRead = attempt
            paneReadProblem = null
            openUsage = liveUsage
            noteInputLine(paneId, text)
            paneText = text
            paneTextPane = paneId
            paneTextTarget = cardOwner?.takeIf { it.isCurrent(it.client) }
            if (agent in setOf("opencode", "opencode-college", "kiro", "kiro-cli")) {
                footerModel(agent, text)?.takeIf { models[paneId] != it }?.let {
                    models = models + (paneId to it)
                    current?.id?.let { sid -> modelCache[sid] = models }
                    noteModels()
                }
            }
            // What the question card sees, logged when it changes (sizes only, no question text).
            val status = panes.firstOrNull { it.id == paneId }?.status
            val p = com.billmongan.herdrdroid.herdr.detectPrompt(text, status == Status.NEEDS_YOU, pane(paneId)?.agent, versionOf(paneId))
            current?.id?.let { sid -> observeQuestion(sid, paneId, p, status, pane(paneId)?.agentName, text) }
            val dialog = com.billmongan.herdrdroid.herdr.showsQuestionDialog(text)
            val sig = if (p == null && !dialog) "none" else
                "dialog ${if (dialog) "open" else "not seen"}, herdr ${status?.name?.lowercase()}, " +
                    (p?.let { "${it.choices.size} choice(s) [${it.choices.joinToString(",") { c -> c.keys }}], question ${it.question.length} chars${if (it.question.endsWith("?")) " ending in ?" else ""}, highlighted ${it.selected ?: "none"}" } ?: "no card")
            if (lastPromptSig[paneId] != sig) {
                if (p != null) com.billmongan.herdrdroid.diag.DiagLog.log("prompt", "$paneId: card read in ${android.os.SystemClock.elapsedRealtime() - started} ms")
                lastPromptSig[paneId] = sig
                if (sig != "none" || p == null && lastPromptSig.containsKey(paneId)) com.billmongan.herdrdroid.diag.DiagLog.log("prompt", "$paneId: $sig")
            }
        }
    }
    private val lastPromptSig = mutableMapOf<String, String>()

    private val paneVisit: Long get() = chatVisit.revision
    private var paneOpenedAt = 0L
    private var chatShownVisit = -1L

    fun openPane(paneId: String, why: String = "tap") {
        current?.id?.let { prefs.lastAgent = "$it|$paneId" }
        val from = (screen as? Screen.Agent)?.paneId
        if (from == paneId) chatVisit.reopen()
        paneOpenedAt = android.os.SystemClock.elapsedRealtime()
        if (from != null && from != paneId) com.billmongan.herdrdroid.diag.DiagLog.log("nav", "agent view $from -> $paneId ($why)")
        seenInApp["${current?.id}|$paneId"] = System.currentTimeMillis()
        saveSeen()
        paneText = ""
        paneTextPane = paneId
        paneTextTarget = null
        paneReadProblem = null
        openUsage = null
        current?.id?.let { questions(it, paneId) }
        resetTranscript(paneId)
        screen = Screen.Agent(paneId)
    }

    // ---- Structured conversations ------------------------------------------------------------

    private val readers = setOf("claude", "codex", "pi", "omp", "grok", "opencode", "hermes", "copilot", "vibe", "kiro", "muse")

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
            transcriptTimes = c.times
            // A chat kept before 1.4.3 has no item times: read it again from the start, so every item
            // gets its log time before questions are placed among them (1.4.3, from review).
            if (c.items.isNotEmpty() && c.times.isEmpty()) transcriptCursor = ""
            transcriptSeen = c.seen
            transcriptMore = c.more
            transcriptTitle = c.title
            transcriptStart = c.start
            transcriptFile = c.file
            transcriptState = TranscriptMode.ON
            return
        }
        transcriptCursor = ""
        transcriptItems = emptyList()
        transcriptTimes = emptyMap()
        transcriptSeen = emptyMap()
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
                { b, ad -> com.billmongan.herdrdroid.store.ChatVault.seal(b, ad) }, { b, ad -> com.billmongan.herdrdroid.store.ChatVault.open(b, ad) },
                { com.billmongan.herdrdroid.store.ChatVault.isBound(it) })
        }.getOrNull()
    }
    private val persistedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** The panes each server had at the last clean-up of its kept chats. */
    private val keptIds = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /** Writes a chat to the phone's storage, off the main thread. */
    /** Saves of kept chats and questions, one at a time in the order they were asked (1.3.2). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val saveLane = Dispatchers.IO.limitedParallelism(1)
    /** The newest save asked for, per file: an older one still waiting is dropped (1.3.2, from a code review). */
    private val saveGen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val saveCounter = java.util.concurrent.atomic.AtomicLong()

    private fun persistChat(serverId: String, paneId: String, chat: CachedChat) {
        persistedAt["$serverId|$paneId"] = System.currentTimeMillis()
        val key = "chat|$serverId|$paneId"
        val gen = saveCounter.incrementAndGet().also { saveGen[key] = it }
        viewModelScope.launch(saveLane) { if (saveGen[key] == gen) chatStore?.save(serverId, paneId, chat) }
    }

    // ---- Question history (1.3.0) -----------------------------------------------------------------

    /** Each pane's questions, "server|pane" to oldest first; kept on the phone beside its chat. */
    private val questionLogs = androidx.compose.runtime.mutableStateMapOf<String, List<com.billmongan.herdrdroid.transcript.QuestionItem>>()
    /** Reads in a row that showed no question while one was open (one miss can be the screen redrawing). */
    private val questionMisses = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The open server's question history for [paneId], for the chat (read only; [openPane] loads it). */
    fun questionsFor(paneId: String): List<com.billmongan.herdrdroid.transcript.QuestionItem> =
        questionLogs["${current?.id}|$paneId"] ?: emptyList()

    /** The pane's question history, loaded from the phone the first time. */
    private fun questions(serverId: String, paneId: String): List<com.billmongan.herdrdroid.transcript.QuestionItem> =
        loadedQuestions(serverId, paneId) ?: emptyList()

    /**
     * The pane's question history as it is on the phone: loaded (and kept in memory) when it opens,
     * an empty one when there is none yet, and null when a history exists but can't be opened now
     * (locked, or a Keystore or disk error). Null is never cached, so an empty list is never saved
     * over a real history (1.3.2, from a code review: a stale lock check let that happen).
     */
    private fun loadedQuestions(serverId: String, paneId: String): List<com.billmongan.herdrdroid.transcript.QuestionItem>? {
        val k = "$serverId|$paneId"
        questionLogs[k]?.let { return it }
        val store = chatStore ?: return null
        val got = store.loadQuestions(serverId, paneId)
        if (got == null && store.hasQuestions(serverId, paneId)) return null
        return (got ?: emptyList()).also { questionLogs[k] = it }
    }

    private fun putQuestions(serverId: String, paneId: String, qs: List<com.billmongan.herdrdroid.transcript.QuestionItem>) {
        questionLogs["$serverId|$paneId"] = qs
        val key = "q|$serverId|$paneId"
        val gen = saveCounter.incrementAndGet().also { saveGen[key] = it }
        viewModelScope.launch(saveLane) { if (saveGen[key] == gen) chatStore?.saveQuestions(serverId, paneId, qs) }
    }

    /** The newest log item of the pane's chat, which a question seen now is shown after. */
    private fun questionAnchor(serverId: String, paneId: String): String? =
        if (serverId == current?.id && transcriptPane == paneId) transcriptItems.lastOrNull()?.key
        else transcriptCache["$serverId|$paneId"]?.items?.lastOrNull()?.key

    /**
     * Records what a read of the pane's screen showed: a question card adds or updates its record;
     * no card twice in a row while one is open settles it (answered, answered elsewhere, dismissed).
     */
    private fun observeQuestion(serverId: String, paneId: String, p: com.billmongan.herdrdroid.herdr.Prompt?, status: Status?, agent: String?, screenText: String? = null) {
        val k = "$serverId|$paneId"
        // While the history can't be read (locked, or unreadable now), questions aren't recorded (1.3.1, 1.3.2).
        val log = loadedQuestions(serverId, paneId) ?: return
        val now = System.currentTimeMillis()
        // Only a card with options is a question to keep; "This agent is waiting for you." with none is not.
        val card = p?.takeIf { it.choices.isNotEmpty() }
        if (card != null) {
            questionMisses[k] = 0
            // A pane with no readable log keeps the screen just before the question (1.4.3).
            val keepScreen = screenText?.takeIf { pane(paneId)?.agent?.lowercase() !in readers }
            val next = com.billmongan.herdrdroid.transcript.QuestionLog.seen(log, card, agent, questionAnchor(serverId, paneId), now, keepScreen)
            if (next != log) putQuestions(serverId, paneId, next)
            return
        }
        if (log.lastOrNull()?.state != com.billmongan.herdrdroid.transcript.QState.OPEN) return
        val misses = (questionMisses[k] ?: 0) + 1
        questionMisses[k] = misses
        if (misses >= 2) putQuestions(serverId, paneId, com.billmongan.herdrdroid.transcript.QuestionLog.gone(log, status == Status.WORKING, now))
    }

    /** Records the answer the app sent to the pane's open question: an option, typed text, or a key. */
    fun noteAnswer(paneId: String, p: com.billmongan.herdrdroid.herdr.Prompt?, answer: String, serverId: String? = current?.id) {
        val sid = serverId ?: return
        val log = loadedQuestions(sid, paneId) ?: return
        val next = com.billmongan.herdrdroid.transcript.QuestionLog.answered(log, p, answer)
        if (next != log) putQuestions(sid, paneId, next)
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
        questionLogs.remove("$serverId|$paneId")
        viewModelScope.launch(Dispatchers.IO) { chatStore?.remove(serverId, paneId) }
    }

    /** The open chat as it is now. */
    private fun openChatNow(paneId: String) = CachedChat(transcriptItems, transcriptCursor, transcriptMore, transcriptTitle,
        transcriptStart, transcriptFile, pane(paneId)?.agent?.lowercase(), transcriptTimes, transcriptSeen)

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

    /** Folds a background read in, its computer clock noted first so the app's own times use it (1.4.3, from review). */
    private fun mergeRead(c: CachedChat, r: com.billmongan.herdrdroid.transcript.TranscriptRead): CachedChat {
        noteHostClock(r)
        return c.withRead(r, seenNow())
    }

    /** Now on the open server's computer clock: the app's own time for items its log gives none (1.4.3). */
    private fun seenNow(): Long = System.currentTimeMillis() - hostOffsetMs()
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
                // A kept chat with no item times (from before 1.4.3) is read again from the start (1.4.3).
                val r = quietly { it.transcript(p, kept?.takeIf { k -> k.times.isNotEmpty() || k.items.isEmpty() }?.cursor ?: "", transcriptScript) } ?: continue
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
    suspend fun readTranscript(paneId: String, allowBackground: Boolean = false): Boolean {
        // Only the agent on screen: a loop left over from the agent you just left must not switch
        // the chat back to it (that showed its history, and its title, on the new agent).
        if ((screen as? Screen.Agent)?.paneId != paneId) return false
        val p = pane(paneId) ?: return false
        resetTranscript(paneId)
        val visit = paneVisit
        val client = herdr
        val cursor = transcriptCursor
        val r = quietly(allowBackground) { it.transcript(p, cursor, transcriptScript) }
        if (r == null) {
            com.billmongan.herdrdroid.diag.DiagLog.log("chat", "$paneId: the log reader didn't run (${p.agent}, link $link)")
            return false
        }
        if (paneVisit != visit || herdr !== client || transcriptCursor != cursor || (screen as? Screen.Agent)?.paneId != paneId) return false
        applyTranscript(paneId, r)
        return r.ok
    }

    /**
     * Streams the open pane's log for up to two minutes (the reader's follow mode), applying each
     * change as it arrives. Only while the app is in front. True when a stream ran; false when it
     * couldn't start, so the caller polls instead.
     */
    suspend fun followTranscript(paneId: String): Boolean {
        val p = pane(paneId) ?: return false
        val h = herdr ?: return false
        val visit = paneVisit
        if (!foreground || link != Link.CONNECTED || transcriptPane != paneId || (screen as? Screen.Agent)?.paneId != paneId) return false
        val started = System.currentTimeMillis()
        var got = false
        val ok = runCatching {
            h.followTranscript(p, transcriptCursor, transcriptScript, 120) { r ->
                withContext(Dispatchers.Main) {
                    // Switched away: end this stream now rather than let it run on for minutes.
                    if (herdr !== h || paneVisit != visit || transcriptPane != paneId || (screen as? Screen.Agent)?.paneId != paneId) throw kotlinx.coroutines.CancellationException("switched agents")
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
            // Why the chat view stays off is worth a line in the diagnostics (1.4.1).
            if (transcriptItems.isEmpty()) com.billmongan.herdrdroid.diag.DiagLog.log("chat", "$paneId: no chat view (${pane(paneId)?.agent}): ${r.why}")
            if (transcriptItems.isEmpty()) transcriptState = TranscriptMode.OFF
            // A kept cursor the reader can't use any more: read the whole log next time, keeping
            // what is shown until it arrives (1.2.9).
            else if (transcriptCursor.isNotEmpty()) {
                com.billmongan.herdrdroid.diag.DiagLog.log("chat", "$paneId: the reader couldn't continue from the kept cursor (${r.why}); reading the log again")
                transcriptCursor = ""
            }
            return
        }
        // Another session or file starts its times afresh: its keys start over (1.4.3, from review).
        if (r.reset && Transcript.sessionOf(transcriptCursor) != Transcript.sessionOf(r.cursor)) {
            transcriptTimes = emptyMap(); transcriptSeen = emptyMap()
        } else if (r.reset) {
            // The same session read again: a key whose item changed (a rewritten log) loses its old times.
            val fresh = Transcript.merge(emptyList(), r.items, true)
            transcriptTimes = Transcript.keptAcrossReset(transcriptTimes, transcriptItems, fresh)
            transcriptSeen = Transcript.keptAcrossReset(transcriptSeen, transcriptItems, fresh)
        }
        transcriptCursor = r.cursor
        noteHostClock(r)
        if (r.reset || r.items.isNotEmpty()) {
            transcriptItems = Transcript.merge(transcriptItems, r.items, r.reset)
            // Log times where the log has them; otherwise, for a live read's new items, when the app
            // first saw them, kept across reloads. A bulk read makes none up (1.4.3).
            val logged = transcriptTimes + Transcript.timesOf(r.items)
            transcriptTimes = if (r.reset) logged else Transcript.stampSeen(logged, transcriptItems, seenNow(), Transcript.keysOf(r.items))
            transcriptSeen = Transcript.markSeen(transcriptSeen, transcriptItems, Transcript.keysOf(r.items), transcriptTimes, seenNow(), bulk = r.reset)
        }
        if (r.reset) {
            transcriptMore = r.more
            transcriptStart = runCatching { org.json.JSONObject(r.cursor).optLong("start", -1L) }.getOrDefault(-1L)
            transcriptFile = runCatching { org.json.JSONObject(r.cursor).optString("file").ifBlank { null } }.getOrNull()
        }
        r.title?.let { transcriptTitle = it }
        transcriptState = TranscriptMode.ON
        if (chatShownVisit != paneVisit) {
            chatShownVisit = paneVisit
            com.billmongan.herdrdroid.diag.DiagLog.log("chat", "$paneId: first log refresh in ${android.os.SystemClock.elapsedRealtime() - paneOpenedAt} ms")
        }
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
            // A send still waiting (1.4.4) was never typed: a copy of it in the log is another message (from Grok's review).
            val seen = mine.filter { p -> !p.waiting && !p.awaitingLink && (
                sameTextCount(p.text) > p.seenBefore ||
                    (p.text.startsWith("/") && notes.any { n -> n.startsWith(norm(p.text.removePrefix("/").substringBefore(' '))) }))
            }
            // Safety net: an agent that is idle again has taken every message queued before it went
            // idle, so anything sent more than ten seconds ago is no longer waiting.
            val idle = pane(paneId)?.status.let { it == Status.IDLE || it == Status.DONE }
            val now = System.currentTimeMillis()
            // An agent that is idle again has taken everything queued before; a message it still
            // doesn't show more than ten seconds after it was sent was not delivered. It stays,
            // marked, with Resend and Dismiss, instead of disappearing.
            // A send still waiting for the input line to clear (1.4.4) was never typed, so it can't be stale.
            val stale = if (idle) mine.filter { it !in seen && !it.removable && now - it.at > 10_000 } else emptyList()
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
                // The same session as on screen (1.3.0, from a code review): pin the shown file and keep the
                // live cursor's pin fields, or the reader opens the newest session for the folder instead.
                val live = runCatching { org.json.JSONObject(transcriptCursor) }.getOrNull()
                val cur = org.json.JSONObject().put("file", file).put("earlier", transcriptStart).put("pin", live?.optString("pin")?.ifBlank { null } ?: file)
                    .apply { for (k in listOf("pin_sid", "db")) live?.optString(k)?.ifBlank { null }?.let { put(k, it) } }.toString()
                val r = quietly { it.transcript(p, cur, transcriptScript) } ?: return@launch
                if (!r.ok || transcriptPane != paneId) return@launch
                val older = Transcript.merge(emptyList(), r.items, true)
                val have = transcriptItems.map { it.key }.toSet()
                transcriptItems = older.filter { it.key !in have } + transcriptItems
                transcriptTimes = Transcript.timesOf(r.items) + transcriptTimes
                transcriptSeen = Transcript.markSeen(transcriptSeen, transcriptItems, Transcript.keysOf(r.items), transcriptTimes, seenNow(), bulk = true)
                transcriptStart = runCatching { org.json.JSONObject(r.cursor).optLong("start", 0L) }.getOrDefault(0L)
                transcriptMore = r.more
            } finally {
                loadingEarlier = false
            }
        }
    }

    /** A message sent from this phone that the agent's log doesn't show yet (queued, or steering). */
    data class PendingSend(val id: Long, val paneId: String, val text: String, val at: Long, val seenBefore: Int, val undelivered: Boolean = false,
                           /** Why it was held instead of sent (1.4.0: someone is typing in that pane); Resend sends it anyway. */
                           val held: String? = null,
                           /** Held, and being retried quietly until the input line is clear (1.4.4). */
                           val waiting: Boolean = false,
                           val awaitingLink: Boolean = false) {
        val removable: Boolean get() = waiting || awaitingLink || undelivered
    }

    fun removableSendsFor(paneId: String): List<PendingSend> =
        pendingSends.filter { it.paneId == paneId && it.removable }

    val canForceSendPending: Boolean get() = link == Link.CONNECTED && herdr != null

    fun forceSendPending(id: Long) {
        val p = pendingSends.firstOrNull { it.id == id && it.removable } ?: return
        if (!canForceSendPending) return
        // A pane that isn't listed keeps the message, with its Resend (1.4.4, from Grok's review).
        val target = pane(p.paneId) ?: run {
            com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${p.paneId}: Resend while the pane isn't listed; kept")
            flash("Not sent: that pane isn't open on the computer just now.")
            return
        }
        if (hasPendingQuestion(target)) return
        val then = heldThen[id] ?: {}
        dismissPending(id)
        // A deliberate force send bypasses the input-line guard.
        sendMessage(target, p.text, force = true, delivered = then)
    }

    /** For each held send, what to do once it is delivered (see [forceSendPending]). */
    private val heldThen = java.util.concurrent.ConcurrentHashMap<Long, () -> Unit>()

    fun dismissPending(id: Long) {
        val pending = pendingSends.firstOrNull { it.id == id && it.removable } ?: return
        queue.removeAll { it.pendingId == id }
        queuedCount = queue.size
        heldThen.remove(id)
        pendingSends = pendingSends - pending
        if (lastSent == (pending.paneId to pending.text) &&
            pendingSends.none { it.paneId == pending.paneId && it.text == pending.text }
        ) lastSent = null
    }

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
        return pendingSends.filter { it.paneId == paneId && !it.removable && now - it.at < (if (hasLog) 15 * 60_000L else 20_000L) }.map { it.text }
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

    /** The open computer's agent versions (1.4.0): agent kind to version. */
    var agentVersions by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** The version of the agent in [paneId], when known. */
    fun versionOf(paneId: String): String? = pane(paneId)?.agent?.lowercase()?.let { agentVersions[it] }

    /** A short message the screens show once (1.4.0: the debug bundle was copied). */
    var notice by mutableStateOf<String?>(null)

    /**
     * One pane's state as text for a bug report (1.4.0): the app version, the agent, its status and
     * scroll offset, the parser's decision on the last screen (question, choices, highlight, and
     * the fallback card), the chat's cursor and mode, the question records, the last lines of the
     * diagnostic log about this pane, and the last screen. Secrets are taken out by [Redact]. It
     * goes only to this phone's clipboard or share sheet; nothing is written on the computer.
     */
    fun debugBundle(paneId: String): String {
        val p = pane(paneId)
        val screenText = if (paneTextPane == paneId) paneText else ""
        val prompt = com.billmongan.herdrdroid.herdr.detectPrompt(screenText, p?.status == Status.NEEDS_YOU, p?.agent, versionOf(paneId))
        val diag = com.billmongan.herdrdroid.diag.DiagLog.text().lines().filter { it.contains(paneId) }.takeLast(40)
        val raw = buildString {
            appendLine("HerdrDroid ${com.billmongan.herdrdroid.BuildConfig.VERSION_NAME} debug bundle, pane $paneId")
            appendLine("agent: ${p?.agent ?: "none"} (${p?.agentName ?: "?"}) version ${versionOf(paneId) ?: "unknown"}, status ${p?.status}, focused ${p?.focused}, scroll offset ${p?.scrollOffset}")
            appendLine("backend: ${herdr?.backend}, link: $link")
            appendLine()
            appendLine("## Parser decision on the last screen")
            if (prompt == null) appendLine("no question card")
            else {
                appendLine("question: ${prompt.question}")
                appendLine("choices: " + prompt.choices.joinToString(" | ") { "${it.keys}=${it.label}" })
                appendLine("highlighted: ${prompt.selected}, multi-select: ${prompt.multi}, esc rejects: ${prompt.escRejects}, fallback card: ${prompt.region != null}")
                prompt.parts?.let { appendLine("layout: steps ${it.tabs.map { t -> t.title }}, heading ${it.heading}, command ${it.command}") }
            }
            appendLine("last prompt signature: ${lastPromptSig[paneId]}")
            appendLine()
            appendLine("## Chat")
            appendLine("mode ${transcriptState}, open pane ${transcriptPane}, items ${if (transcriptPane == paneId) transcriptItems.size else transcriptCache[ck(paneId)]?.items?.size}")
            appendLine("cursor: ${if (transcriptPane == paneId) transcriptCursor else transcriptCache[ck(paneId)]?.cursor}")
            appendLine("questions: " + questionsFor(paneId).joinToString("; ") { com.billmongan.herdrdroid.transcript.QuestionLog.label(it) })
            appendLine()
            appendLine("## Diagnostic log (this pane)")
            diag.forEach { appendLine(it) }
            appendLine()
            appendLine("## Last screen")
            appendLine(screenText.lines().takeLast(60).joinToString("\n"))
        }
        return com.billmongan.herdrdroid.diag.Redact.text(raw)
    }

    /** When each open pane's input line last held typing (1.4.0, the send guard). */
    private val typedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Notes the input line of a pane's screen as just read: any typing in it is remembered for 4 s. */
    private fun noteInputLine(paneId: String, screen: String) {
        val now = com.billmongan.herdrdroid.herdr.inputLineOf(screen, pane(paneId)?.agent) ?: return
        // Typing is the line changing (1.4.6): text that stays the same from one read to the next
        // (a placeholder the plain read can't tell from typing, such as one of Codex's rotating
        // suggestions) is not someone typing. Text that is there is still held by [holdReason].
        // Kept apart from the line's text (from a review: an empty read cleared the 4-second guard at once).
        // Styled and plain reads are compared only with their own kind, since a placeholder reads as ""
        // in one and as text in the other.
        val styled = screen.contains('\u001B')
        val before = lastInputLine.put(if (styled) "$paneId|styled" else paneId, now)
        if (styled) styledAt[paneId] = System.currentTimeMillis()
        // A plain change while the last styled read (within 2.5 minutes) found the line empty is a
        // placeholder changing (Codex rotates its suggestions), not typing (from Grok's review).
        val styledEmpty = !styled && lastInputLine["$paneId|styled"] == "" &&
            System.currentTimeMillis() - (styledAt[paneId] ?: 0L) < 150_000L
        if (now.isNotEmpty() && before != null && before != now && !styledEmpty) typedAt[paneId] = System.currentTimeMillis()
    }

    /** When each pane was last read with its styles (1.4.6). */
    private val styledAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Each pane's input line at the last read, to tell typing (a change) from text that stays (1.4.6). */
    private val lastInputLine = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Why a message must not be typed into [paneId] now (1.4.0, "never type into a busy pane"): text
     * already in its input line at the computer, or someone typing there in the last 4 seconds.
     * Null to send. A pane waiting on a question is answered, not typed into, so it is never held.
     */
    private fun holdReason(paneId: String, screen: String): String? {
        // The agent's own hint in an empty line ("Press up to edit queued messages") is not typing (1.4.4).
        val now = com.billmongan.herdrdroid.herdr.inputLineOf(screen, pane(paneId)?.agent) ?: return null
        // The row taken for the input line is the highlighted option of a menu (Kiro's unnumbered
        // approval card, "❯ Yes, single permission"): a question, not text typed at the computer.
        // Still not typed into, but said as it is (1.4.1, from a code review).
        if (now.isNotEmpty() && com.billmongan.herdrdroid.herdr.detectUnnumberedMenu(com.billmongan.herdrdroid.herdr.forPhone(screen))?.choices?.any { it.label.trim() == now } == true)
            return "a question is open in it (\"${now.take(40)}\"); answer it first"
        if (now.isNotEmpty()) return "something is already typed in its input line at the computer (\"${now.take(40)}\")"
        val at = typedAt[paneId]
        if (at != null && System.currentTimeMillis() - at < 4_000) return "someone was typing in it a moment ago"
        return null
    }

    fun sendMessage(pane: Pane, text: String, force: Boolean = false, delivered: () -> Unit = {}) {
        lastSent = pane.id to text
        lastSentAt = System.currentTimeMillis()
        val pendingId = System.nanoTime()
        pendingSends = pendingSends + PendingSend(
            pendingId, pane.id, text, lastSentAt,
            if (transcriptPane == pane.id) sameTextCount(text) else 0,
        )
        com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id} (${pane.agent ?: "shell"}, ${pane.status}): ${text.length} chars; link ${link}, ${if (herdr == null) "no client" else "client ready"}")
        act(pane.id, "Couldn't send", pendingId) { h ->
            if (pendingSends.none { it.id == pendingId }) return@act
            run {
                // Behind an earlier held message unless the user explicitly chose Force send.
                val earlier = !force && pendingSends.any { it.paneId == pane.id && it.waiting && it.id != pendingId }
                // Read with its styles (1.4.6), so an agent's dim placeholder isn't taken for typing.
                val screenNow = if (earlier) null else runCatching { h.readStyled(pane.id, 40) }.getOrNull()
                // A pane that can't be read first is not typed into blind (1.4.1, from a code review).
                val now = pane(pane.id) ?: pane
                val why = when {
                    earlier -> "an earlier message to it is still waiting"
                    screenNow.isNullOrBlank() -> "its screen couldn't be read first"
                    blocksNormalSend(now, screenNow) -> "a question is open in it; answer it first"
                    force -> null
                    else -> holdReason(pane.id, screenNow)
                }
                if (why != null) {
                    // Held, then retried quietly (1.4.4, Bill: "have the app do that seamlessly and only
                    // report if it fails"); reported with Resend only if it still can't go in 2 minutes.
                    com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id}: held, $why; retrying every 2 s for up to 2 min")
                    if (pendingSends.none { it.id == pendingId }) return@act
                    pendingSends = pendingSends.map { if (it.id == pendingId) it.copy(held = why, waiting = true, awaitingLink = false) else it }
                    heldThen[pendingId] = delivered
                    startHeldRetry(pane.id)
                    return@act
                }
            }
            // The screen read can suspend while the user removes a reconnect-queued send.
            val pending = pendingSends.firstOrNull { it.id == pendingId } ?: return@act
            if (pending.awaitingLink) {
                pendingSends = pendingSends.map {
                    if (it.id == pendingId) it.copy(awaitingLink = false, at = System.currentTimeMillis()) else it
                }
            }
            try {
                h.sendMessage(pane, text)
                com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id}: delivered to herdr (${h.lastSendRoute})")
                delivered()
            } catch (e: Exception) {
                com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id}: failed: ${com.billmongan.herdrdroid.diag.DiagLog.describe(e)}")
                throw e
            }
            readPane(pane.id)
        }
    }

    /** The quiet retry of held sends, one per pane, so its messages go in the order sent (1.4.4). */
    private val heldJobs = mutableMapOf<String, Job>()

    private fun startHeldRetry(paneId: String) {
        if (heldJobs[paneId]?.isActive == true) return
        val server = current?.id ?: return
        val job = viewModelScope.launch {
            while (isActive) {
                val next = pendingSends.firstOrNull { it.paneId == paneId && it.waiting } ?: break
                retryHeld(next, server)
            }
        }
        heldJobs[paneId] = job
        job.invokeOnCompletion { if (heldJobs[paneId] === job) heldJobs.remove(paneId) }
    }

    /** Retries one held send until it goes, times out, or is cancelled (see [HeldSendRetry]). */
    private suspend fun retryHeld(p: PendingSend, server: String) {
        val log = { m: String -> com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${p.paneId}: $m") }
        val agentName = pane(p.paneId)?.agentName ?: "the agent"
        var missing = 0
        val outcome = com.billmongan.herdrdroid.herdr.HeldSendRetry().run(
            // The 2 minutes start when it reaches the front of the pane's queue (from Grok's review).
            startedAt = com.billmongan.herdrdroid.herdr.HeldSendRetry.monotonicMillis(),
            firstWhy = p.held ?: "held",
            cancelled = {
                missing = if (pane(p.paneId) == null) missing + 1 else 0
                when {
                    pendingSends.none { it.id == p.id && it.waiting } -> "no longer waiting (cancelled, or the agent's log has it)"
                    current?.id != server -> "the computer changed"
                    missing >= 3 -> "the pane closed"
                    else -> null
                }
            },
            check = {
                val h = herdr
                val pn = pane(p.paneId)
                when {
                    link != Link.CONNECTED || h == null -> "the computer isn't connected"
                    pn == null -> "the pane isn't listed just now"
                    // A question that came up since is answered first, never typed into (1.4.4).
                    pn.status == Status.NEEDS_YOU -> "a question is open in it; answer it first"
                    pendingSends.any { it.paneId == p.paneId && it.waiting && it.at < p.at && it.id != p.id } -> "an earlier message to it is still waiting"
                    else -> {
                        // Let HeldSendRetry handle read failures and propagate cancellation.
                        val screen = h.readStyled(p.paneId, 40)
                        noteInputLine(p.paneId, screen)
                        // Checked again after the read, which can take a while (from Grok's review): a
                        // question or a usage check that began meanwhile is never typed into. Nothing
                        // suspends from here to the send, so neither can begin in between.
                        val after = pane(p.paneId)
                        when {
                            after == null -> "the pane isn't listed just now"
                            blocksNormalSend(after, screen) -> "a question is open in it; answer it first"
                            else -> holdReason(p.paneId, screen)
                        }
                    }
                }
            },
            send = {
                val h = herdr ?: throw IllegalStateException("not connected")
                val pn = pane(p.paneId) ?: throw IllegalStateException("the pane closed")
                h.sendMessage(pn, p.text)
                log("delivered to herdr (${h.lastSendRoute})")
            },
            log = log,
            acquire = { paneDispatch.acquire(server to p.paneId) },
        )
        when (outcome) {
            is com.billmongan.herdrdroid.herdr.HeldOutcome.Delivered -> {
                val t = System.currentTimeMillis()
                // Cancel tapped while it was being typed (from Grok's review): it went, so say so.
                if (pendingSends.none { it.id == p.id }) {
                    log("cancel came while it was being typed: it was sent")
                    flash("Sent to $agentName before Cancel took effect")
                    readPane(p.paneId)
                    return
                }
                pendingSends = pendingSends.map { if (it.id == p.id) it.copy(waiting = false, held = null, at = t) else it }
                lastSent = p.paneId to p.text
                lastSentAt = t
                heldThen.remove(p.id)?.invoke()
                readPane(p.paneId)
            }
            is com.billmongan.herdrdroid.herdr.HeldOutcome.TimedOut -> {
                if (pendingSends.none { it.id == p.id && it.waiting }) return
                pendingSends = pendingSends.map { if (it.id == p.id) it.copy(waiting = false, undelivered = true, held = outcome.why) else it }
                error = "Not sent to $agentName: ${outcome.why}, for 2 minutes. Tap Resend to send it anyway."
            }
            is com.billmongan.herdrdroid.herdr.HeldOutcome.Failed -> {
                val why = "sending failed (${com.billmongan.herdrdroid.diag.DiagLog.describe(outcome.error)})"
                pendingSends = pendingSends.map { if (it.id == p.id) it.copy(waiting = false, undelivered = true, held = why) else it }
                error = "Not sent to $agentName: $why. Tap Resend to try again."
            }
            is com.billmongan.herdrdroid.herdr.HeldOutcome.Cancelled -> when {
                // Cancelled by you, or already in the agent's log: nothing to report.
                pendingSends.none { it.id == p.id } -> {}
                // Another computer: its pane ids may name other panes, so the row goes, with a report.
                outcome.why == "the computer changed" -> {
                    heldThen.remove(p.id)
                    pendingSends = pendingSends.filterNot { it.id == p.id }
                    error = "Not sent to $agentName: ${outcome.why} before its input line was clear."
                }
                // The pane closed: reported, and kept as not sent (from Grok's review).
                else -> {
                    pendingSends = pendingSends.map { if (it.id == p.id) it.copy(waiting = false, undelivered = true, held = outcome.why) else it }
                    error = "Not sent to $agentName: ${outcome.why} before its input line was clear."
                }
            }
        }
    }

    /** Explicit terminal input, not an answer to a rendered card. */
    fun sendKeys(paneId: String, vararg keys: String) {
        act(paneId, "Couldn't send the key", input = true) { h ->
            h.sendKeys(paneId, *keys)
            readPane(paneId)
        }
    }

    /**
     * Answers a menu question: moves the highlight with the arrow keys and presses Enter when the
     * highlighted option is known, so a digit is never typed into an option's text box.
     */
    /** The card each pane was last answered on from here, and when (1.4.8). */
    private val answeredCards = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Pair<String, Long>>()

    /** When [sig]'s card on [paneId] was answered, if within [ANSWER_HOLD_MS]; else null. */
    private fun answeredRecently(paneId: String, sig: String, serverId: String? = current?.id): Long? {
        val (was, at) = answeredCards[(serverId ?: return null) to paneId] ?: return null
        return at.takeIf { System.currentTimeMillis() - it < ANSWER_HOLD_MS && com.billmongan.herdrdroid.transcript.QuestionLog.sameCard(was, sig) }
    }

    private suspend fun readPaneScreen(h: Herdr, paneId: String, lines: Int): String =
        recoverPaneRead(
            recent = { h.read(paneId, lines, preserveGlyphs = true) },
            visible = {
                // A viewport scrolled back can show an old approval. Only read it at the bottom.
                if ((pane(paneId)?.scrollOffset ?: 0) > 0) "" else h.readVisible(paneId, preserveGlyphs = true)
            },
            shortRecent = { h.read(paneId, 40, preserveGlyphs = true) },
        )

    /** Read fresh output for the card before accepting an answer. */
    private suspend fun readForCard(h: Herdr, paneId: String, lines: Int = 80): String? =
        try { readPaneScreen(h, paneId, lines) } catch (_: HerdrError) { null }

    /** A card answered this recently is not answered again: the agent may not have redrawn yet (1.4.8). */
    private val ANSWER_HOLD_MS = 8_000L

    private var answeringCards by mutableStateOf<Set<Pair<String, String>>>(emptySet())

    fun answerInFlight(paneId: String): Boolean = (current?.id to paneId) in answeringCards

    // Includes the particular connection and visit so leaving and returning cannot re-arm a send.
    internal data class VoiceTarget(
        val server: String, val client: Herdr, val connection: Any, val visit: Long,
        val pane: String, val agent: String, val cwd: String,
    )

    internal fun voiceTarget(): VoiceTarget? {
        if (locked || link != Link.CONNECTED || (!foreground && current?.overWebSocket == true)) return null
        val pane = (screen as? Screen.Agent)?.paneId?.let(::pane) ?: return null
        val client = herdr ?: return null
        return VoiceTarget(current?.id ?: return null, client, client.connectionIdentity ?: return null,
            paneVisit, pane.id, pane.agent ?: return null, pane.cwd)
    }

    private val voiceComposerDrafts = mutableSetOf<Pair<String, String>>()

    internal fun noteVoiceComposerDraft(server: String, pane: String, hasDraft: Boolean) {
        if (hasDraft) voiceComposerDrafts.add(server to pane) else voiceComposerDrafts.remove(server to pane)
    }

    internal suspend fun sendVoiceMessage(target: VoiceTarget, text: String, allowed: () -> Boolean): String {
        fun allowedHere() = allowed() && voiceTarget() == target && !answerInFlight(target.pane) &&
            pendingSends.none { it.paneId == target.pane && (it.waiting || it.awaitingLink) } &&
            queued.none { it.serverId == target.server && it.paneId == target.pane } &&
            (target.server to target.pane) !in voiceComposerDrafts && prefills[target.pane].isNullOrBlank()
        val refuse = "Not sent. Open the agent's chat and check that it is idle with no question, draft at the computer, or queued message."
        val release = paneDispatch.tryAcquire(target.server to target.pane) ?: return refuse
        val h = target.client
        try {
            if (!allowedHere()) return refuse
            val fresh = quietly(allowBackground = true) { it.panes().singleOrNull { p -> p.id == target.pane } } ?: return refuse
            if (!allowedHere() || fresh.agent != target.agent || fresh.cwd != target.cwd ||
                fresh.status !in setOf(Status.IDLE, Status.DONE) || (fresh.scrollOffset ?: 0) != 0) return refuse
            val visible = quietly(allowBackground = true) { it.readStyled(target.pane, 40) } ?: return refuse
            if (!allowedHere() || visible.isBlank() || blocksNormalSend(fresh, visible) ||
                com.billmongan.herdrdroid.herdr.inputLineOf(visible, fresh.agent) != "" || holdReason(fresh.id, visible) != null) return refuse
            h.sendMessage(fresh, text, allowTerminalFallback = false, expectedConnection = target.connection)
            return "Message sent."
        } catch (c: CancellationException) {
            throw c
        } catch (_: com.billmongan.herdrdroid.ssh.ConnectionChanged) {
            return "Not sent. The SSH connection changed."
        } catch (e: Exception) {
            if (e is LinkDown && herdr === h) linkLost(e)
            return "Could not confirm delivery. Check the chat before trying again."
        } finally {
            release()
        }
    }

    internal suspend fun voiceReplySnapshot(target: VoiceTarget): com.billmongan.herdrdroid.voice.VoiceReplySnapshot? {
        if (voiceTarget() != target || !hasTranscriptReader(target.pane)) return null
        val result = quietly(allowBackground = true) { client ->
            com.billmongan.herdrdroid.voice.attributedReply(
                target.pane, target.agent, target.cwd,
                panes = { if (voiceTarget() == target) client.panes() else emptyList() },
                transcript = { if (voiceTarget() == target) client.transcript(it, "", transcriptScript) else null },
            )
        }
        return result?.takeIf { voiceTarget() == target }
    }

    internal suspend fun refreshVoiceScreen(serverId: String, paneId: String): Boolean {
        val client = herdr ?: return false
        val cardOwner = cardTarget(paneId)
        val visit = paneVisit
        if (current?.id != serverId || link != Link.CONNECTED || (screen as? Screen.Agent)?.paneId != paneId) return false
        val attempt = ++paneReadSequence
        val text = quietly(allowBackground = true) { readForCard(it, paneId) } ?: return false
        if (attempt <= lastAppliedPaneRead || herdr !== client || current?.id != serverId || paneVisit != visit || (screen as? Screen.Agent)?.paneId != paneId) return false
        lastAppliedPaneRead = attempt
        paneReadProblem = null
        openUsage = null // This read is recent output, not an identity-bound visible snapshot.
        paneText = text
        paneTextPane = paneId
        paneTextTarget = cardOwner?.takeIf { it.isCurrent(client) }
        val pane = pane(paneId)
        val prompt = detectPrompt(text, pane?.status == Status.NEEDS_YOU, pane?.agent, versionOf(paneId))
        observeQuestion(serverId, paneId, prompt, pane?.status, pane?.agentName, text)
        return true
    }

    internal fun voiceQuestionKey(paneId: String, prompt: com.billmongan.herdrdroid.herdr.Prompt): String? {
        if (pane(paneId)?.agent != "claude" || prompt.multi || prompt.parts?.command != null ||
            prompt.parts?.tabs?.count { !it.submit } != 1 ||
            prompt.choices.any { com.billmongan.herdrdroid.herdr.grantsStandingPermission(it.label) }) return null
        val sig = com.billmongan.herdrdroid.transcript.QuestionLog.signature(prompt)
        return questionsFor(paneId).lastOrNull {
            it.state == com.billmongan.herdrdroid.transcript.QState.OPEN &&
                com.billmongan.herdrdroid.transcript.QuestionLog.sameCard(it.sig, sig)
        }?.key
    }

    private fun beginAnswer(key: Pair<String, String>): Boolean {
        if (key in answeringCards) return false
        answeringCards = answeringCards + key
        return true
    }

    internal data class CardTarget(val client: Herdr, val connection: Any, val server: String, val visit: Long, val pane: Pane)

    internal fun cardTarget(paneId: String): CardTarget? {
        if ((screen as? Screen.Agent)?.paneId != paneId || locked || link != Link.CONNECTED) return null
        val client = herdr ?: return null
        return CardTarget(client, client.connectionIdentity ?: return null, current?.id ?: return null,
            paneVisit, pane(paneId) ?: return null)
    }

    private fun Pane.sameCardOccupant(other: Pane) = id == other.id && agent == other.agent &&
        sessionId == other.sessionId && terminalId == other.terminalId && tabId == other.tabId &&
        workspaceId == other.workspaceId && cwd == other.cwd

    private fun CardTarget.isCurrent(h: Herdr): Boolean = client === h && cardTarget(pane.id)?.let {
        it.client === client && it.connection === connection && it.server == server && it.visit == visit &&
            it.pane.sameCardOccupant(pane)
    } == true

    private suspend fun CardTarget.freshAnswer(h: Herdr, shown: Prompt): Prompt? {
        if (shown.terminalReview || !isCurrent(h)) return null
        val currentPane = h.cardPane(pane.id, connection) ?: return null
        if (!isCurrent(h) || !pane.sameCardOccupant(currentPane)) return null
        // Last remote read before input; not an atomic compare-and-send.
        val text = h.readCardViewport(pane.id, connection).takeUnless { it.isBlank() } ?: return null
        if (!isCurrent(h)) return null
        return detectPrompt(text, currentPane.status == Status.NEEDS_YOU, currentPane.agent, versionOf(pane.id))
            ?.takeIf { cardAnswerStillFits(shown, it) }
    }

    internal fun answerCardKey(target: CardTarget?, prompt: Prompt, key: String, label: String) {
        answerCardInput(target, prompt, label) { h, t, _ ->
            h.sendKeys(t.pane.id, key, expectedConnection = t.connection)
            true
        }
    }

    private fun answerCardInput(target: CardTarget?, shown: Prompt, label: String, delivered: () -> Unit = {},
        send: suspend (Herdr, CardTarget, Prompt) -> Boolean,
    ) {
        val refused = "Not sent. Refresh the question or open the terminal to answer."
        if (target == null || shown.terminalReview) { flash(refused); return }
        val id = target.pane.id
        answerOnce(id) { h ->
            val fresh = target.freshAnswer(h, shown)
            if (fresh == null || !send(h, target, fresh)) { flash(refused); return@answerOnce }
            delivered()
            noteAnswer(id, shown, label, serverId = target.server)
            readPane(id)
        }
    }

    private fun answerOnce(paneId: String, feedback: ((String) -> Unit)? = null, run: suspend (Herdr) -> Unit) {
        val server = current?.id ?: return
        val client = herdr
        if (client == null || link != Link.CONNECTED) {
            val message = "Not sent. Reconnect and check the question before trying again."
            flash(message)
            feedback?.invoke(message)
            return
        }
        val key = server to paneId
        val release = paneDispatch.tryAcquire(key)
        if (release == null || !beginAnswer(key)) {
            release?.invoke()
            val message = "Another input is already being sent to this pane."
            flash(message)
            feedback?.invoke(message)
            return
        }
        viewModelScope.launch {
            try {
                if (current?.id != server || herdr !== client) {
                    val message = "Not sent. Refresh the question before trying again."
                    flash(message)
                    feedback?.invoke(message)
                    return@launch
                }
                run(client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is LinkDown && herdr === client) linkLost(e)
                val message = "Could not confirm delivery. Refresh the question before trying again."
                flash(message)
                feedback?.invoke(message)
                if (herdr === client && link == Link.CONNECTED) readPane(paneId)
            } finally {
                answeringCards = answeringCards - key
                release()
            }
        }
    }

    internal fun answerChoice(paneId: String, prompt: Prompt, choice: Choice,
        voiceQuestion: String? = null, voiceAllowed: () -> Boolean = { true }, voiceConnection: Any? = null,
        target: CardTarget? = cardTarget(paneId), feedback: ((String) -> Unit)? = null,
    ) {
        // The answer goes into the history only once it was sent (1.3.2, from a code review).
        if (prompt.terminalReview) return
        if (choice.keys.startsWith("grok-privacy:")) {
            answerCardInput(target, prompt, choice.label) { h, t, fresh ->
                if (choice !in fresh.choices) return@answerCardInput false
                answerGrokPrivacy(h, paneId, optIn = choice.keys == com.billmongan.herdrdroid.herdr.GROK_PRIVACY_IN,
                    connection = t.connection)?.let { throw IllegalStateException(it) }
                viewModelScope.launch { refreshPanes() }
                true
            }
            return
        }
        fun report(message: String) { flash(message); feedback?.invoke(message) }
        val agentName = pane(paneId)?.agentName ?: "the agent"
        com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$paneId: tapped \"${choice.label.take(60)}\" (option ${choice.keys}, highlighted ${prompt.selected})")
        answerOnce(paneId, feedback) { h ->
            // Match the displayed card, but calculate its keys from the current highlight.
            val fresh = target?.freshAnswer(h, prompt)
            if (target == null || fresh == null || choice !in fresh.choices) {
                report("Not sent. Refresh the question or open the terminal to answer.")
                readPane(paneId)
                return@answerOnce
            }
            if (voiceQuestion != null && (locked || !voiceAllowed() || voiceQuestionKey(paneId, fresh) != voiceQuestion)) {
                report("The question changed. No answer was sent.")
                return@answerOnce
            }

            // Only the card that was tapped is answered (1.4.8, Bill: "Maybe it is passing the same
            // approval to two inputs?"): Muse's next stage asks with the same options, so a second tap,
            // or a tap that lands after the screen moved on, would answer a question nobody has seen.
            val tappedSig = com.billmongan.herdrdroid.transcript.QuestionLog.signature(prompt)

            val before = answeredRecently(paneId, tappedSig)
            if (before != null) {
                com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$paneId: this card was answered ${(System.currentTimeMillis() - before) / 1000} s ago; not sent twice")
                withContext(Dispatchers.Main) { report("Already answered. Waiting for $agentName.") }
                readPane(paneId)
                return@answerOnce
            }

            // A transport error after any key is sent leaves delivery uncertain. Keep the guard
            // active until the next screen read even when the send did not return a receipt.
            answeredCards[target.server to paneId] = tappedSig to System.currentTimeMillis()
            val keys = com.billmongan.herdrdroid.herdr.menuKeys(fresh, choice)
            val connection = voiceConnection ?: target.connection
            if (keys == null) {
                if (choice.pressEnter) h.typeAndEnter(paneId, choice.keys, connection)
                else h.sendText(paneId, choice.keys, connection)
            } else h.sendKeys(paneId, *keys.toTypedArray(), expectedConnection = connection)
            val sent = keys?.joinToString(" ") { keyName(it) } ?: choice.keys
            com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$paneId: sent $sent")
            noteAnswer(paneId, prompt, choice.label, serverId = target.server)
            // Say what went (1.4.3): a tap that answers shows it at once.
            report("Sent $sent to $agentName: ${choice.label.take(40)}")
            readPane(paneId)
            viewModelScope.launch { refreshPanes() }
        }
    }

    /** A short note on the screen that goes by itself after 5 seconds (1.4.3). */
    private fun flash(text: String) {
        notice = text
        viewModelScope.launch { delay(5_000); if (notice == text) notice = null }
    }

    /** A key as the card names it: "↓", "Enter", "Esc". */
    private fun keyName(k: String) = when (k) {
        "up" -> "↑"; "down" -> "↓"; "left" -> "←"; "right" -> "→"; "enter" -> "Enter"; "esc" -> "Esc"; "space" -> "Space"; "tab" -> "Tab"
        else -> k
    }

    /**
     * Answers Grok's data-sharing banner the way that works (checked on devbox2's Grok 4.7,
     * 2026-11-13): with the input box empty, type /privacy and Enter, which opens "Settings ›
     * Coding data, retention, and training"; then move to Opt in or Opt out with the arrows and
     * press Enter. Returns a problem to show, or null.
     */
    private suspend fun answerGrokPrivacy(h: Herdr, paneId: String, optIn: Boolean, connection: Any? = null): String? {
        var screen = h.read(paneId, 60)
        if (com.billmongan.herdrdroid.herdr.detectGrokPrivacyDialog(screen) == null) {
            val left = com.billmongan.herdrdroid.herdr.grokInputText(screen)
                ?: return "Grok's input box isn't showing, so /privacy wasn't typed. Close what is open in Grok and try again."
            if (left.isNotEmpty()) {
                // Backspaces clear it in any terminal program; then check that it is empty.
                h.sendKeys(paneId, *Array(left.length + 2) { "backspace" }, expectedConnection = connection)
                delay(500)
                if (!com.billmongan.herdrdroid.herdr.grokInputText(h.read(paneId, 60)).isNullOrEmpty())
                    return "Grok's input box still has text in it, so /privacy wasn't typed."
            }
            h.sendText(paneId, "/privacy", connection)
            delay(700)
            h.sendKeys(paneId, "enter", expectedConnection = connection)
            delay(1500)
            screen = h.read(paneId, 60)
        }
        val dialog = com.billmongan.herdrdroid.herdr.detectGrokPrivacyDialog(screen)
            ?: return "Grok's privacy settings didn't open."
        val target = dialog.choices.firstOrNull { it.label == if (optIn) "Opt in" else "Opt out" } ?: return "Grok's privacy settings changed shape."
        val keys = com.billmongan.herdrdroid.herdr.menuKeys(dialog, target) ?: return "Grok's privacy settings have no highlighted choice."
        h.sendKeys(paneId, *keys.toTypedArray(), expectedConnection = connection)
        return null
    }

    /**
     * A typed answer to a "Type something." / "Other" option (1.4.0): move the highlight to that
     * option, verify the move, then type the text and press Enter. Without a known highlight,
     * leave the answer to the terminal instead of guessing where to type it.
     */
    internal fun answerTyped(target: CardTarget?, prompt: Prompt, choice: Choice?, text: String, delivered: () -> Unit) {
        answerCardInput(target, prompt, text, delivered) { h, t, fresh ->
            val id = t.pane.id
            if (choice != null) {
                if (choice !in fresh.choices) return@answerCardInput false
                val move = moveKeys(fresh, choice) ?: return@answerCardInput false
                if (move.isNotEmpty()) {
                    h.sendKeys(id, *move.toTypedArray(), expectedConnection = t.connection)
                    delay(300)
                    val moved = t.freshAnswer(h, prompt) ?: return@answerCardInput false
                    if (moveKeys(moved, choice)?.isEmpty() != true) return@answerCardInput false
                }
            }
            h.typeAndEnter(id, text, t.connection)
            true
        }
    }

    fun answer(paneId: String, keys: String, pressEnter: Boolean, sent: () -> Unit = {}) {
        act(paneId, "Couldn't answer", input = true) { h ->
            if (pressEnter) h.typeAndEnter(paneId, keys) else h.sendText(paneId, keys)
            sent()
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

    /** Refresh due usage from CLI reports and visible footers, never from typed commands. */
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
                val profile = QuotaProfiles.forAgent(list.first().agent) ?: return@launch
                if (!profile.hasPassiveSource || !canPoll || current?.id != serverId) return@launch
                val items = quietly { it.readQuota(list.map { pane -> pane.id }, profile) }
                if (current?.id != serverId) return@launch
                lastQuotaTry[agentKey] = now
                if (!items.isNullOrEmpty()) {
                    val reading = QuotaReading(now, items)
                    quotas = quotas + (agentKey to reading)
                    prefs.saveQuotas(serverId, quotas)
                    recordQuota(serverId, agentKey, reading)
                }
            }
        }
        jobs.joinAll()
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
        f.screenOn = ::screenOn
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
                // In front, the screens refresh panes themselves; behind, this keeps alerts coming.
                val h = herdr
                if (foreground || h == null || current?.overWebSocket == true) { delay(3_000); continue }
                // Behind (1.4.1): wait on herdr for any agent to change state, up to 60 s, instead of
                // asking every few seconds; where herdr can't wait, poll every 3 s (12 s, screen off).
                val changed = runCatching { h.waitForChange(panes, 60_000) }.getOrNull()
                if (changed == null) delay(if (screenOn()) 3_000 else 12_000)
                if (!foreground && herdr === h) runCatching { refreshPanes() }
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
                    val text = runCatching { client()?.read(p.id, NOTIFY_READ_LINES) }.getOrNull().orEmpty()
                    // A screen that couldn't be read: one plain notice, no question and no Reply (1.4.2, from review).
                    if (text.isBlank()) { Notify.unread(app, serverId, serverLabel, p.id, p.agentName); return@launch }
                    val prompt = detectPrompt(text, blocked = true, agent = p.agent)
                    // An agent's error, not a question (1.4.2): its own alert, once per change of state.
                    val error = if (prompt == null) com.billmongan.herdrdroid.herdr.detectAgentError(text) else null
                    if (error != null) { Notify.agentError(app, serverId, serverLabel, p.id, p.agentName, error); return@launch }
                    // "Blocked" with nothing on screen that asks is a stale status, not a question (1.4.2):
                    // no alert. A screen that couldn't be read still alerts, saying so.
                    if (prompt == null && text.isNotBlank()) { com.billmongan.herdrdroid.diag.DiagLog.log("watch", "${p.id}: herdr says blocked, but nothing on screen asks; no alert"); return@launch }
                    withContext(Dispatchers.Main) { if (prompt != null && prompt.choices.isNotEmpty()) observeQuestion(serverId, p.id, prompt, p.status, p.agentName, text) }
                    Notify.needsYou(app, serverId, serverLabel, p.id, p.agentName, prompt?.question ?: "Waiting for you.", prompt?.choices.orEmpty(), p.agent, terminalReview = prompt?.terminalReview == true)
                }
                // Only agents that need you notify (Bill, 2026-11-13); finishing doesn't.
                before == Status.NEEDS_YOU -> {
                    Notify.clear(app, serverId, p.id)
                    // The question was answered (or dropped) away from this screen: settle its record.
                    viewModelScope.launch(Dispatchers.Main) {
                        val log = loadedQuestions(serverId, p.id) ?: return@launch
                        val next = com.billmongan.herdrdroid.transcript.QuestionLog.gone(log, p.status == Status.WORKING, System.currentTimeMillis())
                        if (next != log) putQuestions(serverId, p.id, next)
                    }
                }
            }
        }
    }

    init {
        viewModelScope.launch { AppBus.actions.collect { a -> answerFromNotification(a) } }
        if (!locked) startBackgroundChecks()
    }

    /** A notification button: answer only if the same question is still on screen. */
    private suspend fun answerFromNotification(a: NotifyAction) {
        val app = getApplication<Application>()
        val serverLabel = servers.firstOrNull { it.id == a.serverId }?.label ?: ""
        val h = if (a.serverId == current?.id) herdr else fleet?.herdr(a.serverId)
        if (h == null) {
            // Not connected to that server now (1.4.2, from review: the alert stayed on "Sending").
            Notify.sending(app, a.serverId, a.paneId, "Open HerdrDroid to answer", "The app isn't connected to $serverLabel, so the answer wasn't sent.")
            return
        }
        // One answer per question (1.4.2, from review): taps are handled one after another, so a second
        // tap for a question already answered from its alert is dropped for 15 seconds, before the
        // pane may have redrawn.
        val key = "${a.serverId}|${a.paneId}|${a.question.filterNot { it.isWhitespace() }}"
        val at = notifyAnswered[key]
        if (at != null && System.currentTimeMillis() - at < 15_000) {
            // Already answered: the alert says so rather than staying on "Sending" (1.4.2, from review).
            Notify.sending(app, a.serverId, a.paneId, "Answered", "This question was already answered.")
            return
        }
        val flight = a.serverId to a.paneId
        val release = paneDispatch.tryAcquire(flight)
        if (release == null || !beginAnswer(flight)) {
            release?.invoke()
            Notify.sending(app, a.serverId, a.paneId, "Answer in progress", "An answer is already being sent to this pane.")
            return
        }
        try {
            answerFromNotificationNow(h, a, app, serverLabel) { notifyAnswered[key] = System.currentTimeMillis() }
        } finally {
            answeringCards = answeringCards - flight
            release()
        }
    }

    /** When each question (server, pane, question text) was last answered from its alert. */
    private val notifyAnswered = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun answerFromNotificationNow(h: Herdr, a: NotifyAction, app: Application, serverLabel: String, answered: () -> Unit) {
        // A typed reply: sent as a message (as the answer when the agent is waiting on a question).
        if (a.isReply) {
            runCatching {
                val p = h.panes().firstOrNull { it.id == a.paneId } ?: return
                if (p.agent?.lowercase() in setOf("codex", "hermes")) {
                    val current = readForCard(h, p.id, NOTIFY_READ_LINES)
                        ?.let { detectPrompt(it, p.status == Status.NEEDS_YOU, p.agent) }
                    if (current == null || current.terminalReview || current.question.filterNot(Char::isWhitespace) !=
                        a.question.filterNot(Char::isWhitespace)) {
                        Notify.sending(app, a.serverId, a.paneId, "Open HerdrDroid to answer", "The active question could not be verified.")
                        return
                    }
                }
                h.sendMessage(p, a.keys)
                answered()
                withContext(Dispatchers.Main) { noteAnswer(a.paneId, null, a.keys, a.serverId) }  // after the send
                if (a.serverId == current?.id && transcriptPane == a.paneId) lastSent = a.paneId to a.keys
            }.onFailure {
                Notify.needsYou(app, a.serverId, servers.firstOrNull { it.id == a.serverId }?.label ?: "", a.paneId, "The agent",
                    "Your reply couldn't be sent (${it.message}). Open the app to send it.", emptyList())
            }
            return
        }
        val text = runCatching { readForCard(h, a.paneId, NOTIFY_READ_LINES) }.getOrNull()
        if (text.isNullOrBlank()) {
            // Not "the question changed": the app couldn't see (1.4.2, from review).
            Notify.sending(app, a.serverId, a.paneId, "Open HerdrDroid to answer", "The pane's screen couldn't be read, so the answer wasn't sent.")
            return
        }
        // The same depth and agent the alert was made with (1.4.2, from review).
        val now = detectPrompt(text, blocked = true, agent = a.agent)
        if (!notificationAnswerStillFits(now, a)) {
            // The question's words still on screen but not read as that card: say the app couldn't
            // tell, rather than that it changed.
            val stillThere = a.question.isNotBlank() && text.filterNot { it.isWhitespace() }.contains(a.question.filterNot { it.isWhitespace() }.take(60))
            Notify.sending(app, a.serverId, a.paneId, "Open HerdrDroid to answer",
                if (stillThere) "The app couldn't match your answer to the question on screen, so nothing was sent."
                else "The question changed before your answer arrived, so nothing was sent.")
            return
        }
        // The same card already answered from the app or another alert a moment ago (1.4.8): not twice.
        val cardSig = now?.let { com.billmongan.herdrdroid.transcript.QuestionLog.signature(it) }
        val before = cardSig?.let { answeredRecently(a.paneId, it, a.serverId) }
        if (before != null) {
            com.billmongan.herdrdroid.diag.DiagLog.log("answer", "${a.paneId}: alert tap on a card answered ${(System.currentTimeMillis() - before) / 1000} s ago; not sent twice")
            Notify.sending(app, a.serverId, a.paneId, "Already answered", "")
            return
        }
        var sent = false
        cardSig?.let { answeredCards[a.serverId to a.paneId] = it to System.currentTimeMillis() }
        try {
            val label = now?.choices?.firstOrNull { it.keys == a.keys }?.label ?: if (a.isKey) "Pressed ${a.keys}" else a.keys
            if (a.keys.startsWith("grok-privacy:")) {
                val problem = answerGrokPrivacy(h, a.paneId, optIn = a.keys == com.billmongan.herdrdroid.herdr.GROK_PRIVACY_IN)
                sent = problem == null
                if (sent) { Notify.sending(app, a.serverId, a.paneId, "Answered", label); withContext(Dispatchers.Main) { noteAnswer(a.paneId, now, label, a.serverId) } }
                else Notify.sending(app, a.serverId, a.paneId, "Open HerdrDroid to answer", problem!!)
                return
            }
            val nav = if (!a.isKey) now?.let { p -> p.choices.firstOrNull { it.keys == a.keys }?.let { c -> com.billmongan.herdrdroid.herdr.menuKeys(p, c) } } else null
            if (nav != null) h.sendKeys(a.paneId, *nav.toTypedArray())
            else if (a.isKey) h.sendKeys(a.paneId, a.keys)
            else if (a.pressEnter) h.typeAndEnter(a.paneId, a.keys)
            else h.sendText(a.paneId, a.keys)
            sent = true
            answered()
            Notify.sending(app, a.serverId, a.paneId, "Answered", label)
            // Recorded once it was sent (1.3.2); a failure here is not a failed answer.
            runCatching { withContext(Dispatchers.Main) { noteAnswer(a.paneId, now, label, a.serverId) } }
        } catch (c: CancellationException) {
            // The alert never stays on "Sending" (1.4.2, from review).
            Notify.sending(app, a.serverId, a.paneId, if (sent) "Answered" else "Open HerdrDroid to answer", if (sent) "" else "The answer wasn't sent.")
            throw c
        } catch (e: Exception) {
            // Only a send that didn't go out is reported, so answering again can't send it twice.
            if (!sent) Notify.sending(app, a.serverId, a.paneId, "Open HerdrDroid to answer",
                "Your answer couldn't be sent (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    /** A notification was tapped: open that pane, switching servers if it is on another one. */
    fun openFromNotification(serverId: String, paneId: String) {
        // The "isn't being watched" notice names no pane: opening the app (and unlocking it) is all it asks.
        if (paneId.isBlank()) return
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
    fun switchTo(serverId: String, paneId: String?, voice: Boolean = false) {
        if (serverId == current?.id && herdr != null) { paneId?.let { openPane(it, "drawer") }; return }
        val s = servers.firstOrNull { it.id == serverId } ?: return
        if (!canSignInSilently(s)) { screen = Screen.Servers; return }
        // If that server doesn't answer, come back to the agent you were on (its connection is
        // parked, so this is quick) rather than leaving an empty screen or the server list.
        val backServer = current
        val backPane = (screen as? Screen.Agent)?.paneId
        pendingOpen = paneId
        if (current != null) disconnectQuietly()
        connect(s, savedPassword(s), remember = false, openAgent = true, voice = voice)
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

    data class QueuedPrompt(val id: Long, val serverId: String, val paneId: String, val text: String)

    var queued by mutableStateOf<List<QueuedPrompt>>(emptyList())
        private set
    private var queueJob: Job? = null

    internal fun blocksNormalSend(pane: Pane, screen: String): Boolean =
        pane.status == Status.NEEDS_YOU || dismissibleUsageFrame(pane.agent, screen) != null ||
            (pane.agent != null && detectPrompt(forPhone(screen), false, pane.agent, versionOf(pane.id)) != null)

    fun hasPendingQuestion(pane: Pane): Boolean =
        blocksNormalSend(pane, if (paneTextPane == pane.id && paneReadProblem == null) paneText else "")

    /** Holds a message until the pane's agent is ready, then sends it. */
    fun enqueue(paneId: String, text: String) {
        val serverId = current?.id ?: return
        if (text.isBlank()) return
        queued = queued + QueuedPrompt(System.nanoTime(), serverId, paneId, text)
        if (queueJob?.isActive == true) return
        queueJob = viewModelScope.launch {
            while (isActive && queued.isNotEmpty()) {
                delay(3_000)
                if (queued.none { it.serverId == current?.id } || link != Link.CONNECTED) continue
                refreshPanes()
                if (link != Link.CONNECTED) continue
                for (q in queued.toList()) {
                    if (q !in queued || q.serverId != current?.id) continue
                    val p = pane(q.paneId)
                    if (p == null) continue
                    if ((p.status == Status.IDLE || p.status == Status.DONE) && !hasPendingQuestion(p)) {
                        queued = queued - q
                        sendMessage(p, q.text)
                    }
                }
            }
        }
    }

    /** Skip this app's idle wait. Normal screen and input guards still apply. */
    fun sendQueuedImmediately(q: QueuedPrompt) {
        if (q !in queued || q.serverId != current?.id) return
        val target = pane(q.paneId) ?: return
        if (link != Link.CONNECTED || hasPendingQuestion(target)) {
            flash("Reconnect or answer the pending question before sending.")
            return
        }
        queued = queued - q
        sendMessage(target, q.text)
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
     * every server (the open one and the watched ones).
     */
    private fun trackDone(serverId: String, list: List<Pane>) {
        val tNow = System.currentTimeMillis()
        var changed = false
        list.forEach { p ->
            val k = "$serverId|${p.id}"
            when (p.status) {
                Status.DONE -> if (!doneSince.containsKey(k)) { doneSince[k] = tNow; changed = true }
                Status.WORKING, Status.NEEDS_YOU -> if (doneSince.remove(k) != null) changed = true
                else -> {}
            }
            // An agent open on screen when it finishes has been seen.
            if (serverId == current?.id && (screen as? Screen.Agent)?.paneId == p.id && foreground) { seenInApp[k] = tNow; changed = true }
        }
        if (changed) saveSeen()
    }

    fun shownStatus(p: Pane, serverId: String? = current?.id): Status {
        val here = serverId == current?.id
        // Hermes: a conversation elsewhere (Telegram, its app) working or waiting counts here too.
        if (here && p.agent?.lowercase() == "hermes" && p.status != Status.NEEDS_YOU) hermesState?.let { return it }
        if (here && (p.status == Status.IDLE || p.status == Status.DONE || p.status == Status.UNKNOWN) && (busyCounts[p.id] ?: 0) > 0) return Status.WORKING
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
