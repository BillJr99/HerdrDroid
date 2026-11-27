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
import com.billmongan.herdrdroid.herdr.activePiQuestion
import com.billmongan.herdrdroid.herdr.isOpenCode
import com.billmongan.herdrdroid.herdr.backgroundSupport
import com.billmongan.herdrdroid.herdr.openCodeBackgroundDrawer
import com.billmongan.herdrdroid.herdr.openCodeEmptyComposer
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.cardAnswerStillFits
import com.billmongan.herdrdroid.herdr.moveKeys
import com.billmongan.herdrdroid.hermes.HermesChat
import com.billmongan.herdrdroid.hermes.HermesLiveState
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
import com.billmongan.herdrdroid.herdr.NeedsYouCadence
import com.billmongan.herdrdroid.herdr.detectBackend
import com.billmongan.herdrdroid.herdr.HerdrError
import com.billmongan.herdrdroid.herdr.isMissingPane
import com.billmongan.herdrdroid.herdr.terminalUnavailable
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.recoverPaneRead
import com.billmongan.herdrdroid.herdr.QuotaProfile
import com.billmongan.herdrdroid.herdr.QuotaProfiles
import com.billmongan.herdrdroid.herdr.QuotaReading
import com.billmongan.herdrdroid.herdr.UsageOverlay
import com.billmongan.herdrdroid.herdr.dismissUsageOverlay
import com.billmongan.herdrdroid.herdr.dismissibleUsageFrame
import com.billmongan.herdrdroid.herdr.hermesUsageEnd
import com.billmongan.herdrdroid.herdr.hermesUsageWaiting
import com.billmongan.herdrdroid.herdr.footerModel
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.RelayResult
import com.billmongan.herdrdroid.herdr.RelayRefusal
import com.billmongan.herdrdroid.herdr.idleForRelay
import com.billmongan.herdrdroid.herdr.takesInputDuringBackgroundWork
import com.billmongan.herdrdroid.herdr.WritePermit
import com.billmongan.herdrdroid.herdr.hasStableOccupant
import com.billmongan.herdrdroid.herdr.sameOccupant
import com.billmongan.herdrdroid.herdr.sameWorkIdentity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    /**
     * 2.2.7: the main screen with no agent open: the chat's place, with the left drawer of every
     * pane, when no server is open or none of its agents is remembered. Back from the server list
     * comes here rather than leaving the app.
     */
    data object Home : Screen
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
    /** 2.2.8: every open question on the servers the app can see, with its answers ([com.billmongan.herdrdroid.ui.QuestionsScreen]). */
    data object Questions : Screen
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
    /** The server open when it was tapped (2.1.1, Grok's RC review M1): it never runs on another one. */
    val serverId: String? = null,
)

/** 2.1.4: how often the cross-server notice loop looks for agents to tell. */
private const val NOTICE_TICK_MS = 30_000L

class AppViewModel internal constructor(
    app: Application,
    // Keep all adapter work in this scope; recheck cancellation at the final submission boundary.
    // Cancellation drops pending work but cannot recall input already submitted to a destination.
    private val runAgentMessagingSession: (suspend (CoroutineScope) -> Unit)?,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, null)

    private val store = ServerStore(app)
    private val prefs = AppPrefs(app)

    /** 2.1.4: the in-app update check, the prompt and the install (Settings, Updates). */
    val updates = com.billmongan.herdrdroid.update.Updater(
        com.billmongan.herdrdroid.update.AppUpdatePrefs(app),
        com.billmongan.herdrdroid.update.OkUpdateHttp(),
        com.billmongan.herdrdroid.update.AndroidUpdatePlatform(app),
        viewModelScope,
        app.cacheDir,
    )
    var agentMessagingEnabled by mutableStateOf(prefs.agentMessagingEnabled)
        private set

    fun changeAgentMessaging(on: Boolean) {
        agentMessagingEnabled = on
        updateAgentMessagingSession()
        prefs.agentMessagingEnabled = on
    }

    /** 2.1.4: "Tell agents about cross-server messaging". It only acts while cross-server messaging is on. */
    var crossServerNotice by mutableStateOf(prefs.crossServerNotice)
        private set

    /** 2.1.4: "Skip agents at or above X% of quota" for the notice; 100 never skips. */
    var crossServerNoticeQuota by mutableStateOf(
        com.billmongan.herdrdroid.herdr.CrossServerNotice.clampThreshold(prefs.crossServerNoticeQuota))
        private set

    fun changeCrossServerNotice(on: Boolean) {
        crossServerNotice = on
        prefs.crossServerNotice = on
        updateNoticeLoop()
    }

    /**
     * 2.2.7: "Small packets on VPN (automatic)", off by default (Bill: "start with normal size
     * packets and fall back per session to small"). While on, a direct TCP connect made when the phone's network is a VPN uses small
     * packets; off restores 2.2.6. It is read at each connect, so it never resets an open link.
     */
    var smallPacketsOnVpn by mutableStateOf(prefs.smallPacketsOnVpn.also { com.billmongan.herdrdroid.ssh.VpnSmallPackets.enabled = it })
        private set

    /** 2.2.7: "Small packets on cellular (automatic)", off by default; read at each connect like the VPN one. */
    var smallPacketsOnCellular by mutableStateOf(prefs.smallPacketsOnCellular.also { com.billmongan.herdrdroid.ssh.VpnSmallPackets.cellularEnabled = it })
        private set

    fun changeSmallPacketsOnCellular(on: Boolean) {
        smallPacketsOnCellular = on
        prefs.smallPacketsOnCellular = on
        com.billmongan.herdrdroid.ssh.VpnSmallPackets.cellularEnabled = on
        com.billmongan.herdrdroid.diag.DiagLog.log("link", "small-packets-on-cellular=${if (on) "on" else "off"}")
    }

    /**
     * 2.2.7 (Bill: "maybe it should be a setting to send small packets [on] Wi-Fi as well"):
     * "Small packets on Wi-Fi (automatic)", off by default; read at each connect like the others.
     * A VPN over Wi-Fi follows the VPN setting.
     */
    var smallPacketsOnWifi by mutableStateOf(prefs.smallPacketsOnWifi.also { com.billmongan.herdrdroid.ssh.VpnSmallPackets.wifiEnabled = it })
        private set

    fun changeSmallPacketsOnWifi(on: Boolean) {
        smallPacketsOnWifi = on
        prefs.smallPacketsOnWifi = on
        com.billmongan.herdrdroid.ssh.VpnSmallPackets.wifiEnabled = on
        com.billmongan.herdrdroid.diag.DiagLog.log("link", "small-packets-on-wifi=${if (on) "on" else "off"}")
    }

    fun changeSmallPacketsOnVpn(on: Boolean) {
        smallPacketsOnVpn = on
        prefs.smallPacketsOnVpn = on
        com.billmongan.herdrdroid.ssh.VpnSmallPackets.enabled = on
        com.billmongan.herdrdroid.diag.DiagLog.log("link", "small-packets-on-vpn=${if (on) "on" else "off"}")
    }

    fun changeCrossServerNoticeQuota(percent: Int) {
        val v = com.billmongan.herdrdroid.herdr.CrossServerNotice.clampThreshold(percent)
        crossServerNoticeQuota = v
        prefs.crossServerNoticeQuota = v
    }

    private val chatVisit = com.billmongan.herdrdroid.voice.SelectedChatVisit()

    /**
     * The screen the server list was opened from while a server is open (2.1.3). Back and the
     * list's back arrow return there. It is kept while the user edits a server or opens Settings
     * from the list, and dropped as soon as they go anywhere else.
     */
    private var serversOrigin by mutableStateOf<Screen?>(null)
    private var currentScreen by mutableStateOf<Screen>(Screen.Servers)
    var screen: Screen
        get() = currentScreen
        set(value) {
            if (currentScreen != value) {
                // 2.1.5: a card stays with its pane's chat; a send in flight stops at the next check.
                crossServerPermit?.revoke()
                openingPanePreview = null
            }
            if (!inServersFlow(value)) serversOrigin = null
            chatVisit.navigate((currentScreen as? Screen.Agent)?.paneId, (value as? Screen.Agent)?.paneId)
            // 2.2.8: every way into or out of the Questions view starts a new visit of it, so its targets go stale.
            if ((currentScreen == Screen.Questions) != (value == Screen.Questions)) {
                questionsVisit++
                if (approveAllPending?.target?.questionsVisit?.let { it >= 0 } == true) cancelApproveAll()
            }
            currentScreen = value
        }
    var servers by mutableStateOf(store.all().sortedBy { it.label.lowercase() })
        private set
    var current by mutableStateOf<Server?>(null)
        private set

    /**
     * The server whose chat the agent screen shows (2.1.1). The screen is keyed by it with the pane
     * id, so the composer's text and other per-chat state never carry over to a pane with the same
     * id on another server. Unlike [current] it is not cleared for the moment a switch between
     * servers takes, so the screen is rebuilt once, for the new server.
     */
    var chatServerId by mutableStateOf<String?>(null)
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
    private var linkSince = System.currentTimeMillis()

    /** Changes [link] and logs why (2.2.5): opaque server tag, the states, a fixed reason word, timing. */
    private fun linkTo(to: Link, reason: String) {
        val now = System.currentTimeMillis()
        if (to != link) {
            com.billmongan.herdrdroid.diag.LinkDiag.transition(current?.id, link.name, to.name, reason, now - linkSince)
            linkSince = now
        }
        link = to
    }
    var linkProblem by mutableStateOf<String?>(null)
        private set

    /**
     * 2.2.6: the automatic small-packets fallback. Armed at each sign-in to a direct TCP server
     * with small packets off; a dropped link or a timed-out read soon after makes this session
     * reconnect once with a smaller MSS (see [com.billmongan.herdrdroid.ssh.SmallPacketFallback]).
     */
    private companion object SmallPacketWaits {
        /** The longest the fallback's reconnect waits for a send in flight (the send lane's own timeout is 30 s). */
        const val FALLBACK_SEND_WAIT_MS = 45_000L
        /** 2.2.7: how long after a network change the server lights are checked again (a VPN takes a moment to come up). */
        const val NETWORK_RECHECK_MS = 4_000L
    }
    // 2.2.7 (Bill: "have it kick in anytime we are disconnected"): no window after sign-in; any
    // unexpected drop or stall of the session's direct TCP link falls back, once per session.
    internal val smallPacketFallback = com.billmongan.herdrdroid.ssh.SmallPacketFallback(windowMs = Long.MAX_VALUE)
    internal var smallPacketClock: () -> Long = System::currentTimeMillis
    /** The server whose session fell back to small packets, for the one-tap "keep it on" offer; null when none. */
    var smallPacketOffer by mutableStateOf<String?>(null)
        private set

    /**
     * When [e] is a fallback trigger inside the window after sign-in, switches this session to
     * small packets (the next connection uses them), logs it and offers to keep it; true when it fired.
     */
    internal fun maybeSmallPacketFallback(e: Throwable?, failing: HerdrSsh?): Boolean {
        val server = current ?: return false
        val s = ssh ?: return false
        // Only the connection that failed, and only while it is still the open one: a read on the
        // server just left that fails after a switch never touches the new server (pre-review H1).
        if (failing == null || failing !== s) return false
        val reason = com.billmongan.herdrdroid.ssh.SmallPacketFallback.reasonFor(e) ?: return false
        val now = smallPacketClock()
        if (!smallPacketFallback.wouldFire(server.id, reason, now)) return false
        // A drop from a network change or from going to the background is not lossy Wi-Fi.
        // 2.2.7: automatic small packets (VPN, cellular or Wi-Fi) were, or will now be, in effect: nothing to fall back to.
        val auto = com.billmongan.herdrdroid.ssh.MssChoice.AUTOMATIC
        val skip = when {
            s.lastMssChoice?.reason?.takeIf { it in auto } != null -> s.lastMssChoice!!.reason
            s.mssChoice().reason in auto -> s.mssChoice().reason
            com.billmongan.herdrdroid.diag.NetworkWatch.changes != smallPacketFallback.armedNetChanges -> "network-change"
            !foreground -> "background"
            else -> null
        }
        if (skip != null) {
            // 2.2.7: a network change defers the fallback to the next connect on the new network
            // (armed again from now) rather than dropping it for the sign-in.
            if (skip == "network-change") deferToNextSignIn() else smallPacketFallback.disarm()
            com.billmongan.herdrdroid.diag.DiagLog.log("link", com.billmongan.herdrdroid.diag.LinkDiag.fallbackSkippedLine(server.id, skip, reason))
            return false
        }
        val mss = smallPacketFallback.fire(server.id, reason, now) ?: return false
        s.useSmallPackets(mss)
        // 2.2.7: the choice holds for this server's session: reconnects, the watcher and the status check use it too.
        sessionSmallPackets[server.id] = mss
        linkSession?.fallbackUsed = true
        com.billmongan.herdrdroid.diag.LinkDiag.fallback(server.id, reason, mss, smallPacketFallback.sinceArmed(now))
        smallPacketOffer = server.id
        return true
    }

    /**
     * 2.2.7: the servers whose session fell back to small packets, server id to MSS. Until that
     * session ends (disconnect) or the phone's network changes, after which connections start
     * with normal packets again.
     */
    private val sessionSmallPackets = java.util.concurrent.ConcurrentHashMap<String, Int>()
    internal fun sessionMssFor(serverId: String): Int? = sessionSmallPackets[serverId]

    /**
     * 2.2.7: the network changed, so the fallback waits for the next successful sign-in on the new
     * network and is armed from then (pre-review M5: a connect or sign-in that fails right after a
     * change says nothing about packet size, and a drop caused by the change itself is not a stall).
     */
    private var rearmOnSignIn = false

    private fun deferToNextSignIn() {
        smallPacketFallback.disarm()
        rearmOnSignIn = true
    }

    /** A reconnect signed in on the new network: the fallback watches it from now, once. */
    internal fun rearmAfterSignIn() {
        if (!rearmOnSignIn) return
        rearmOnSignIn = false
        val server = current ?: return
        val sh = ssh ?: return
        smallPacketFallback.arm(server.id, !sh.overWebSocket && sh.activeMss == null && sh.lastMssChoice?.mss == null, smallPacketClock(),
            com.billmongan.herdrdroid.diag.NetworkWatch.changes)
    }

    private var networkRecheck: Job? = null

    /**
     * 2.2.7: the phone's network changed. The session's small packets end (the next connection
     * starts with normal ones), the fallback is armed again for the new network, and the server
     * list's lights are checked again shortly instead of keeping a stale "Can't reach it".
     */
    internal fun onNetworkChanged(type: String) {
        if (sessionSmallPackets.isNotEmpty() || ssh?.lastMssChoice?.reason == com.billmongan.herdrdroid.ssh.MssChoice.FALLBACK) {
            sessionSmallPackets.clear()
            ssh?.clearSmallPackets()
            com.billmongan.herdrdroid.diag.DiagLog.log("link", "server=${com.billmongan.herdrdroid.diag.LinkDiag.serverTag(current?.id)} small-packets=reset reason=network-change net=$type")
        }
        deferToNextSignIn()
        networkRecheck?.cancel()
        if (type == "none") return
        networkRecheck = viewModelScope.launch {
            delay(NETWORK_RECHECK_MS)
            com.billmongan.herdrdroid.diag.DiagLog.log("check", "recheck reason=network-change net=$type")
            if (foreground && !locked) runCatching { checkServers() }
        }
    }

    /**
     * 2.2.7 (Bill's log: after the switch to cellular plus a VPN, the pane list read got no byte for
     * 26.5 s before the link was declared lost): a read on the open connection with no first byte
     * for [com.billmongan.herdrdroid.ssh.SmallPacketFallback.FIRST_BYTE_STALL_MS] after sign-in
     * falls back to small packets at once, with every fallback guard (this server's open
     * connection only, once per sign-in, never mid-send, never over a WebSocket).
     */
    internal fun onFirstByteStall(failing: HerdrSsh) {
        val h = herdr ?: return
        if (h.transport !== failing) return
        if (maybeSmallPacketFallback(com.billmongan.herdrdroid.ssh.FirstByteStall(), failing)) reconnectForFallback(h)
    }

    /**
     * 2.2.7: a server's light on the server list. While the open server's session is connected it
     * is shown from that session (its agents), whatever the last separate check found, so a
     * session that works is never listed as "Can't reach it".
     */
    fun healthFor(serverId: String): ServerHealth? {
        if (serverId == current?.id && herdr != null) {
            val at = serverHealth[serverId]?.at ?: 0L
            if (link != Link.CONNECTED) return ServerHealth(
                if (link == Link.RECONNECTING) Health.CHECKING else Health.DOWN,
                if (link == Link.RECONNECTING) "Reconnecting" else "Can't reach it", at,
            )
            val list = panes
            val need = list.count { it.status == Status.NEEDS_YOU }
            val w = list.count { it.status == Status.WORKING }
            return if (need > 0) ServerHealth(Health.NEEDS_YOU, "$need need you · $w working", at)
            else ServerHealth(Health.UP, if (w > 0) "Up · $w working" else "Up · agents idle", at)
        }
        return serverHealth[serverId]
    }

    /** The one tap on the offer: small packets stay on for this server from now on. */
    fun keepSmallPacketsOn() {
        val id = smallPacketOffer ?: return
        smallPacketOffer = null
        val stored = store.get(id) ?: return
        val mss = (if (current?.id == id) ssh?.activeMss else null) ?: com.billmongan.herdrdroid.ssh.SmallPackets.DEFAULT
        val saved = stored.copy(smallPackets = true, mss = mss)
        store.save(saved)
        if (current?.id == id) current = current?.copy(smallPackets = true, mss = mss)
        reloadServers()
        com.billmongan.herdrdroid.diag.DiagLog.log("link", "server=${com.billmongan.herdrdroid.diag.LinkDiag.serverTag(id)} small-packets=kept mss=$mss")
    }

    fun dismissSmallPacketOffer() { smallPacketOffer = null }

    /**
     * After a read timed out and the fallback fired: reconnect [h]'s connection with small packets,
     * but only once nothing is being sent on its send lane (pre-review M2), so a message or key
     * press in flight is never cut off. A send that outlives [FALLBACK_SEND_WAIT_MS] has passed its
     * own timeout and is treated as abandoned. Nothing happens if [h] is no longer the open server.
     * 2.2.7 (Codex's review): nor once the socket that stalled was replaced (a newer one in the same
     * client already uses small packets) or the phone's network changed while it waited (that
     * ends the session's small packets), so a newer connection is never cut for an older stall.
     */
    internal fun reconnectForFallback(h: Herdr) {
        val s = h.transport
        val stalled = s.connectionIdentity
        val net = com.billmongan.herdrdroid.diag.NetworkWatch.changes
        viewModelScope.launch {
            val start = System.currentTimeMillis()
            var waited = false
            while (s.sendsRunning > 0 && System.currentTimeMillis() - start < FALLBACK_SEND_WAIT_MS) {
                waited = true
                delay(100)
            }
            if (waited) com.billmongan.herdrdroid.diag.DiagLog.log("link",
                "server=${com.billmongan.herdrdroid.diag.LinkDiag.serverTag(current?.id)} fallback-reconnect waited_for_send=" +
                    (if (s.sendsRunning > 0) "abandoned" else "done") + " ms=${System.currentTimeMillis() - start}")
            if (herdr !== h) return@launch
            val skip = when {
                s.connectionIdentity !== stalled -> "connection-replaced"
                com.billmongan.herdrdroid.diag.NetworkWatch.changes != net -> "network-change"
                else -> null
            }
            if (skip != null) {
                com.billmongan.herdrdroid.diag.DiagLog.log("link",
                    "server=${com.billmongan.herdrdroid.diag.LinkDiag.serverTag(current?.id)} fallback-reconnect skipped reason=$skip")
                return@launch
            }
            linkLost(null, "small-packets-fallback")
        }
    }

    // ---- 2.2.6 link diagnostics ----------------------------------------------------------------

    /** 2.2.7: told on the main thread; the latest view model is the one listening. Kept for [onCleared]. */
    private val networkListener: (String, Int) -> Unit = { type, _ ->
        viewModelScope.launch(Dispatchers.Main) { onNetworkChanged(type) }
    }

    init {
        com.billmongan.herdrdroid.diag.NetworkWatch.start(app)
        com.billmongan.herdrdroid.diag.NetworkWatch.listener = networkListener
    }

    /** Counted from a sign-in to its disconnect, for the session summary line. */
    private var linkSession: com.billmongan.herdrdroid.diag.LinkStats.Session? = null
    /** Why the reconnects now running started (a fixed word), for the reconnect lines. */
    private var reconnectReason = "link-lost"

    private fun beginLinkSession(serverId: String) {
        endLinkSession("replaced")
        linkSession = com.billmongan.herdrdroid.diag.LinkStats.Session(serverId, System.currentTimeMillis(), com.billmongan.herdrdroid.diag.NetworkWatch.changes)
    }

    /** One summary line for the session that is ending, then it is forgotten. */
    private fun endLinkSession(why: String) {
        val session = linkSession ?: return
        linkSession = null
        val sh = ssh
        com.billmongan.herdrdroid.diag.DiagLog.log("link", com.billmongan.herdrdroid.diag.LinkStats.summaryLine(
            session, sh?.stalls?.get() ?: 0L, sh?.inbound?.bytes ?: 0L, com.billmongan.herdrdroid.diag.NetworkWatch.changes, System.currentTimeMillis(), why,
        ))
    }
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
    private var openCodeComposerTarget by mutableStateOf<CardTarget?>(null)
    private var codexSteeringTarget by mutableStateOf<CardTarget?>(null)
    private var codexActivityTarget by mutableStateOf<CardTarget?>(null)
    private var piLiveQuestion by mutableStateOf<Prompt?>(null)
    /**
     * A draft typed in the open pane's input box at the computer, read from its live screen (2.1.0).
     * [actionable]: the draft's end shows and the pane reports who is in it, so Send draft can be offered.
     */
    internal data class PaneDraftShown(
        val target: CardTarget, val text: String, val actionable: Boolean, val clearable: Boolean,
        /** Why Send draft is off now ([com.billmongan.herdrdroid.herdr.draftSendBlock]), or null. */
        val sendNote: String? = null,
    )
    private var paneDraftShown by mutableStateOf<PaneDraftShown?>(null)

    /** The draft card for the open server only (2.1.1): the same pane id on another server is another pane. */
    internal var paneDraft: PaneDraftShown?
        get() = paneDraftShown?.takeIf { it.target.server == current?.id }
        private set(value) { paneDraftShown = value }
    /** "send" or "clear" while a draft action runs, per (server, pane). */
    private var draftActions by mutableStateOf<Map<Pair<String, String>, String>>(emptyMap())
    fun paneDraftBusy(paneId: String): String? = current?.id?.let { draftActions[it to paneId] }
    /**
     * The last styled verdict and what it was read for (2.1.0): the pane, the input line on the plain
     * read, the occupant, the visit and the connection. The live screen is read again only when one
     * of them changes, so a pane showing a placeholder costs one extra read, not one per poll.
     */
    private var draftReadFor: List<Any?>? = null
    private data class HermesUsageRead(val target: CardTarget, val composerReady: Boolean)
    private var hermesUsageRead by mutableStateOf<HermesUsageRead?>(null)
    /** The last message sent from this phone: the server, the pane and the text (2.1.1: with its server). */
    private data class SentMark(val serverId: String?, val paneId: String, val text: String)
    private var lastSentMark by mutableStateOf<SentMark?>(null)

    /**
     * The last message sent to a pane of the open server, as (pane, text). Pane ids are not global
     * (2.1.1, from the 2.1.0 acceptance: a message sent to FixtureB's w1:p1 showed in FixtureA's
     * w1:p1 chat), so a message sent on another server is never shown here.
     */
    val lastSent: Pair<String, String>?
        get() = lastSentMark?.takeIf { it.serverId == current?.id }?.let { it.paneId to it.text }

    private fun markSent(serverId: String?, paneId: String, text: String) { lastSentMark = SentMark(serverId, paneId, text) }
    var lastSentAt by mutableStateOf(0L)
        private set

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
    var transcriptLogTimes by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    /** When the app first saw each item of the open chat, on the computer's clock (1.4.3). */
    var transcriptSeen by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    /** The time shown on each item of the open chat with no time of its own (2.1.4, [Transcript.stampShown]). */
    var transcriptShown by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    /** The time each item of the open chat shows (2.1.4): its own where it has one, else the one the app gave it. */
    val chatTimes: Map<String, Long> get() = Transcript.shownTimes(transcriptLogTimes, transcriptShown)
    /** Each server's UTC offset in minutes, from its last read (2.1.4), for the times Grok draws on its screen. */
    private val hostTzs = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** The open server's UTC offset in minutes, or null before its first read. */
    fun hostTzMinutes(): Int? = current?.id?.let { hostTzs[it] }
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
        r.hostTz?.let { hostTzs[sid] = it }
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
    /**
     * Whether the drawer is pulled out, behind [drawerOpen] (2.1.4). Declared here, before `init`,
     * because `init` reaches [ensureFleet], which reads it.
     */
    private var drawerShown by mutableStateOf(false)
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

    /** The server list and the screens opened from it: a server's form, and Settings. */
    private fun inServersFlow(s: Screen) =
        s == Screen.Servers || s is Screen.EditServer || (s is Screen.Settings && s.back == Screen.Servers)

    /** Opens the server list (the drawer's "Servers…"), remembering where Back returns to. */
    fun openServers() {
        val from = screen
        screen = Screen.Servers
        if (!inServersFlow(from)) serversOrigin = from
    }

    /**
     * Where Back leaves the server list for: the screen it was opened from, or the main screen.
     * 2.2.7 (Bill: "when I hit the back button on my phone it's still exits the app back to the
     * desktop rather than going back to the app main screen"): never out of the app. With no
     * origin (the list shown at startup, after a disconnect or a failed connect, or for a locked
     * server) it is [mainScreen]: the open server's last agent, else [Screen.Home].
     */
    val serversBack: Screen
        get() {
            val from = serversOrigin
            return when {
                // The pane list is not the chat: Back from it goes to the chat's main screen too.
                from == null || from == Screen.Tabs -> mainScreen()
                from is Screen.Agent && (current == null || panes.none { it.id == from.paneId }) -> mainScreen()
                else -> from
            }
        }

    /** 2.2.7: the chat with the left drawer: the open server's last agent when it is still there, else [Screen.Home]. */
    internal fun mainScreen(): Screen {
        val last = prefs.lastAgent.split("|", limit = 2)
        val cur = current
        return if (cur != null && last.getOrNull(0) == cur.id && panes.any { it.id == last.getOrNull(1) }) Screen.Agent(last[1])
            else Screen.Home
    }

    /**
     * The machine menu's "All servers…" (2.2.5, Bill: Back from the list left the app). It used to
     * disconnect, which made the list the app's main screen, so Back closed the app. It now opens
     * the list over the open server, like the drawer's "Servers…": Back returns to this screen.
     */
    fun showAllServers() = openServers()

    /** Back on the server list (2.2.7: always somewhere in the app; true for the callers that ask). */
    fun leaveServers(): Boolean {
        screen = serversBack
        return true
    }

    /**
     * Closes a server's form without saving (Cancel, or Back), back to the server list. A new
     * server's key made in the form goes with it ([newServerId] is that draft's id).
     */
    fun closeServerForm(newServerId: String?) {
        if (newServerId != null) forgetGeneratedKey(newServerId)
        screen = Screen.Servers
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
        if (connecting) {
            val want = pendingOpen
            cancelConnect()
            pendingOpen = want
        }
        pendingHostKey = null
        pendingBackendChoice = null
        needPasswordFor = null
        connecting = true
        error = null
        val run = ++connectRun
        connectJob = viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            var stage = com.billmongan.herdrdroid.diag.LinkDiag.Stage.LOGIN
            val creds = credentialsFor(server, password)
            val s = HerdrSsh(server.host, server.port, server.user, creds, server.fingerprint, SshKeyVault::provider, server.wsUrl?.takeIf { server.overWebSocket }, server.smallPacketsMss, server.id,
                sessionFallbackMss = sessionMssFor(server.id))
            // 2.2.7: a read with no first byte for a while falls back early (see [onFirstByteStall]).
            s.firstByteStallListener = { viewModelScope.launch(Dispatchers.Main) { onFirstByteStall(s) } }
            connectingSsh = s
            try {
                // Back to the server just left: its connection is kept open for a few minutes, so
                // it is used again at once after one quick check, with no sign-in or detection.
                // Or the watcher's own live connection to it, so switching servers from the drawer
                // is quick too.
                val kept = takeParked(server.id)
                    ?: fleet?.take(server.id)?.let { (fs, fh) -> Parked(server.id, fs, fh, fh.backend, System.currentTimeMillis()) }
                val reused = kept != null && runCatching { kotlinx.coroutines.withTimeout(6_000) { kept.herdr.check() } }.isSuccess
                if (kept != null && !reused) kept.ssh.close()
                if (!isActive || run != connectRun) {
                    if (reused) kept?.ssh?.close()
                    s.close()
                    return@launch
                }
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
                    // 2.2.5: the probe's first command opens the connection, so a failure before it
                    // answers is the sign-in's (login); after, the probe's.
                    stage = com.billmongan.herdrdroid.diag.LinkDiag.Stage.PROBE
                    val found = detectBackend(s, server.herdrPath)
                    if (!isActive || run != connectRun) { s.close(); return@launch }
                    com.billmongan.herdrdroid.diag.LinkDiag.stage(run.toLong(), server.id, com.billmongan.herdrdroid.diag.LinkDiag.Stage.LOGIN, "ok", System.currentTimeMillis() - t0,
                        mapOf("transport" to s.transportName, "algs" to s.negotiated?.let { "[" + it + "]" }))
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
                    com.billmongan.herdrdroid.diag.LinkDiag.stage(run.toLong(), server.id, com.billmongan.herdrdroid.diag.LinkDiag.Stage.PROBE, "ok", System.currentTimeMillis() - t0,
                        mapOf("backend" to backend.name.lowercase(), "tui" to (if (found.tuiRunning) "running" else if (found.tuiInstalled) "installed" else "absent"),
                            "core" to (if (found.coreRunning) "running" else if (found.coreInstalled) "installed" else "absent")))
                    stage = com.billmongan.herdrdroid.diag.LinkDiag.Stage.CHANNEL
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
                            if (!isActive || run != connectRun || !server.overWebSocket || ++tries > 4) throw e
                            connectNote = "The server didn't answer yet (it may be waking up). Trying again in ${wait / 1000} s…"
                            delay(wait)
                            wait *= 2
                        }
                    }
                }
                if (!isActive || run != connectRun) { sNow.close(); return@launch }
                connectNote = null
                com.billmongan.herdrdroid.diag.LinkDiag.stage(run.toLong(), server.id, com.billmongan.herdrdroid.diag.LinkDiag.Stage.CHANNEL, "ok", System.currentTimeMillis() - t0,
                    mapOf("reused" to reused, "transport" to sNow.transportName, "backend" to h.backend.name.lowercase()))
                // 2.2.5: an attempt that a newer one replaced (or Cancel stopped) applies nothing.
                if (run != connectRun) {
                    com.billmongan.herdrdroid.diag.LinkDiag.stage(run.toLong(), server.id, com.billmongan.herdrdroid.diag.LinkDiag.Stage.APPLY, "stale-attempt-dropped", System.currentTimeMillis() - t0)
                    sNow.close()
                    return@launch
                }
                stage = com.billmongan.herdrdroid.diag.LinkDiag.Stage.APPLY
                var saved = server
                if (remember && password != null) saved = saved.copy(passwordCipher = PasswordVault.encrypt(password))
                if (saved != server) store.save(saved)
                reloadServers()
                closeSession()
                ssh = sNow
                herdr = h
                current = saved
                chatServerId = saved.id
                linkTo(Link.CONNECTED, "signed-in")
                linkProblem = null
                // 2.2.6: a direct TCP session without small packets may fall back to them once.
                beginLinkSession(saved.id)
                // 2.2.7: not when this connection already used small packets (setting or vpn-auto).
                smallPacketFallback.arm(saved.id, !sNow.overWebSocket && sNow.activeMss == null && sNow.lastMssChoice?.mss == null, smallPacketClock(),
                    com.billmongan.herdrdroid.diag.NetworkWatch.changes)
                // A new sign-in is a new session: an offer from an earlier one no longer holds.
                smallPacketOffer = null
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
                // Sends held on this server while another was open go on waiting here (2.1.2).
                resumeHeldSends(saved.id)
                // A usage dialog the app left open here (the app was closed or killed) is closed first (2.1.4).
                retryLeftOpenUsage()
                val want = pendingOpen
                pendingOpen = null
                when {
                    want != null && panes.any { it.id == want } -> openPane(want, "after connecting")
                    // From the drawer or at startup: straight to an agent, the one most worth seeing.
                    openAgent -> bestAgent()?.let { openPane(it, "after connecting") } ?: run { screen = Screen.Tabs; drawerOpen = true }
                }
            } catch (e: UnconfirmedHostKey) {
                if (!isActive || run != connectRun) { s.close(); return@launch }
                com.billmongan.herdrdroid.diag.LinkDiag.stage(run.toLong(), server.id, stage, "failed", System.currentTimeMillis() - t0, mapOf("error" to "UnconfirmedHostKey"))
                s.close()
                pendingHostKey = PendingHostKey(server, password, remember, e.fingerprint)
            } catch (e: HostKeyChanged) {
                if (!isActive || run != connectRun) { s.close(); return@launch }
                com.billmongan.herdrdroid.diag.LinkDiag.stage(run.toLong(), server.id, stage, "failed", System.currentTimeMillis() - t0, mapOf("error" to "HostKeyChanged"))
                s.close()
                error = hostKeyMessage(e)
            } catch (e: AuthRejected) {
                if (!isActive || run != connectRun) { s.close(); return@launch }
                com.billmongan.herdrdroid.diag.LinkDiag.stage(run.toLong(), server.id, stage, "failed", System.currentTimeMillis() - t0, mapOf("error" to "AuthRejected"))
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
                if (!isActive || run != connectRun) { s.close(); return@launch }
                com.billmongan.herdrdroid.diag.LinkDiag.stage(run.toLong(), server.id, stage, "failed", System.currentTimeMillis() - t0,
                    mapOf("error" to com.billmongan.herdrdroid.diag.LinkDiag.errorClass(e), "cause" to e.cause?.let { com.billmongan.herdrdroid.diag.LinkDiag.errorClass(it) }, "exit" to (e as? HerdrError)?.exit))
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
                // 2.2.8 (Bill: "I'm surprised by how many SSH connections HerdrDroid creates"): a server the
                // watcher already holds a live connection to is checked on that one, not on a new one.
                val watched = if (s.id == current?.id) null else fleet?.herdr(s.id)?.takeIf { it.connectionIdentity != null }
                if (s.id == current?.id && herdr != null && link != Link.CONNECTED) {
                    healthFor(s.id)?.let { serverHealth = serverHealth + (s.id to it.copy(at = now)) }
                    return@launch
                }
                val panes: List<Pane>? = if (s.id == current?.id && herdr != null) panes
                else if (watched != null) runCatching {
                    com.billmongan.herdrdroid.ssh.SshCounts.reused(s.id)
                    val listed = watched.panes()
                    val connection = watched.connectionIdentity
                    val sequence = ++modelReadSequence
                    val eligible = listed.filter { it.agent != null && (it.scrollOffset ?: 0) == 0 }
                    val footers = runCatching { watched.previews(eligible.map { it.id }) }.getOrDefault(emptyMap())
                    if (s.id != current?.id && fleet?.herdr(s.id) === watched && watched.connectionIdentity === connection)
                        eligible.forEach { p -> footers[p.id]?.let { recordModelFooter(s.id, p, it.tail, sequence) } }
                    withBusy(listed, runCatching { watched.workCounts(watched.panes0Cache, transcriptScript).second }.getOrDefault(emptyMap()))
                }.getOrNull()
                else runCatching {
                    val ssh = HerdrSsh(s.host, s.port, s.user, credentialsFor(s, savedPassword(s)), s.fingerprint, SshKeyVault::provider, s.wsUrl?.takeIf { s.overWebSocket }, s.smallPacketsMss, s.id,
                        sessionFallbackMss = sessionMssFor(s.id))
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
                }.onFailure {
                    // 2.2.7: why the light went red, and with which packets (names and numbers only).
                    com.billmongan.herdrdroid.diag.DiagLog.log("check", "server=${com.billmongan.herdrdroid.diag.LinkDiag.serverTag(s.id)} result=down " +
                        "reason=${com.billmongan.herdrdroid.diag.LinkDiag.errorClass(it)} mss=${s.smallPacketsMss ?: sessionMssFor(s.id) ?: "normal"} " +
                        "net=${com.billmongan.herdrdroid.diag.NetworkWatch.type} session=${if (s.id == current?.id) link.name.lowercase() else "none"}")
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
        updateAgentMessagingSession()
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
        val withAgent = fresh.filter { it.agent != null && (it.scrollOffset ?: 0) == 0 }
        val sequence = ++modelReadSequence
        val footers = runCatching { h.previews(withAgent.map { it.id }) }.getOrNull().orEmpty()
        val fromLogs = runCatching { h.logModels(withAgent.filter { it.id !in footers ||
            footerModel(it.agent, footers.getValue(it.id).tail) == null }) }.getOrNull().orEmpty()
        if (s.id != current?.id) {
            val fallback = fromLogs.filterKeys { id ->
                val p = withAgent.firstOrNull { it.id == id }
                p != null && lastModelFooter[s.id to id]?.first?.sameCardOccupant(p) != true
            }
            if (fallback.isNotEmpty()) {
                modelCache[s.id] = modelCache[s.id].orEmpty() + fallback
                modelsVersion++
            }
            withAgent.forEach { p -> footers[p.id]?.let { recordModelFooter(s.id, p, it.tail, sequence) } }
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

    /** Transfers the parked connection to a reconnect, or closes it before another server starts. */
    private fun takeParked(serverId: String): Parked? {
        val previous = parked
        parked = null
        parkJob?.cancel()
        return previous?.takeIf {
            it.serverId == serverId && System.currentTimeMillis() - it.at < PARK_MILLIS
        }.also { kept -> if (kept == null) previous?.ssh?.close() }
    }

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
        smallPacketFallback.disarm()
        clearPanePrefetch()
        // The open chat is kept before the connection goes, so a reconnect shows it at once (1.2.9).
        stashOpenChat()
        hermesPoll?.cancel()
        hermesLiveStates = emptyList()
        rawWorkPanes = emptyList()
        workCounts = emptyMap()
        busyCounts = emptyMap()
        work = emptyMap()
        endedProcs = emptyMap()
        workScreens = emptyMap()
        movedToBackground = emptyMap()
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
        linkTo(Link.CONNECTED, "left-quietly")
        linkProblem = null
    }

    fun disconnect() {
        endLinkSession("disconnect")
        smallPacketFallback.disarm()
        smallPacketOffer = null
        fleet?.stop()
        fleet = null
        watchJob?.cancel()
        WatchService.stop(getApplication())
        park()
        closeSession()
        current?.id?.let { sessionSmallPackets.remove(it) }
        current = null
        panes = emptyList()
        quotas = emptyMap()
        linkTo(Link.CONNECTED, "disconnected")
        linkProblem = null
        screen = Screen.Servers
    }

    // ---- Staying connected ------------------------------------------------------------------

    // The existing polling flag defaults to foreground for legacy behavior. Messaging instead
    // waits for the first real foreground event and never runs under background Watching.
    private var agentMessagingForeground by mutableStateOf(false)
    private var agentMessagingActivation: Job? = null
    private var agentMessagingRunning: Job? = null

    private fun updateAgentMessagingSession() {
        updateNoticeLoop()
        if (!agentMessagingEnabled || !agentMessagingForeground || locked) {
            clearCrossServerCards()
            agentMessagingActivation?.cancel()
            agentMessagingActivation = null
            return
        }
        val runSession = runAgentMessagingSession ?: return
        if (agentMessagingActivation != null) return // No automatic restart within this activation.
        val previous = agentMessagingRunning
        val next = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // A canceled activation that never started must not hide older in-flight cleanup.
            previous?.join()
            agentMessagingRunning = coroutineContext[Job]
            runSession(this)
        }
        agentMessagingActivation = next
        next.start()
    }

    /** The app is visible again. After more than a few seconds away the old socket is likely dead,
     *  so it is replaced before any poll can stall on it. */
    fun onForeground(handsFreeActive: Boolean = false) {
        foreground = true
        tuneFleet()
        // Only an already-running, explicitly started session can defer the app prompt.
        // Keystore authentication and any lock already in effect are unchanged.
        if (!handsFreeActive) refreshAppLock()
        if (watchOthers && herdr != null) fleet?.watch(watchable())
        if (herdr != null && backgroundAt > 0) {
            if (current?.overWebSocket == true) {
                if (link != Link.CONNECTED) startReconnect()
            } else if (System.currentTimeMillis() - backgroundAt > 8_000) {
                ssh?.reset()
                linkLost(null, "resume")
            }
        }
        backgroundAt = 0L
        agentMessagingForeground = true
        updateAgentMessagingSession()
    }

    internal fun onHandsFreeEnded() {
        // A confirmed message still waiting for the SSH lock must not go out after hands-free ends.
        writeGate.revokeAll()
        if (foreground) refreshAppLock()
    }

    private fun refreshAppLock() {
        if (!locked && vaultNeedsUnlock()) {
            writeGate.revokeAll()
            locked = true
            updateAgentMessagingSession()
            clearPanePrefetch()
        }
    }

    fun onBackground() {
        agentMessagingForeground = false
        updateAgentMessagingSession()
        foreground = false
        tuneFleet()
        clearPanePrefetch()
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
            linkLost(null, "background")
        }
    }

    private fun linkLost(e: Exception?, reason: String? = null) {
        if (herdr == null) return
        // 2.2.6: soon after sign-in, a drop may be large replies lost on the Wi-Fi; the reconnect
        // below then uses small packets (once per sign-in). Every caller has checked that the
        // failure is the open connection's.
        // 2.2.7 (Grok's review of 8fab7a9): only a real failure counts. Every call with no error
        // is a reconnect the app chose (coming back from the background, a send queued while the
        // link still looked up) and names its reason; silent keepalives arrive as a LinkDown.
        val fellBack = reason == null && e != null && maybeSmallPacketFallback(e, ssh)
        if (link == Link.CONNECTED) {
            // 2.2.6: the error's class chain, the silence before it and the keepalive setting.
            val sh = ssh
            val last = sh?.inbound?.lastAt?.takeIf { it > 0L }
            com.billmongan.herdrdroid.diag.DiagLog.log("link", com.billmongan.herdrdroid.diag.LinkStats.lostLine(
                current?.id, e, last?.let { System.currentTimeMillis() - it }, com.billmongan.herdrdroid.ssh.KEEPALIVE_SECONDS, sh?.inbound?.bytes ?: 0L,
            ))
            reconnectReason = reason ?: if (fellBack) "small-packets-fallback" else if (e == null) "read-timeout" else "link-lost"
            clearCrossServerCards()
            clearPanePrefetch()
            linkTo(Link.RECONNECTING, "link-lost:${com.billmongan.herdrdroid.diag.LinkDiag.errorClass(e)}")
            downSince = System.currentTimeMillis()
        }
        e?.let { linkProblem = friendly(it) }
        startReconnect()
    }

    private fun startReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            var wait = 1_000L
            var attempt = 0
            var backoff = 0L
            while (isActive) {
                val h = herdr ?: return@launch
                // Behind, only while Watching (1.4.0, from a code review: alerts for the open server
                // stopped until the app was opened again), and never for a WebSocket server, which
                // isn't held open in the background; then no faster than every 15 s (60 s, screen off).
                if (!foreground) {
                    if (!watching || current?.overWebSocket == true) { delay(1_000); continue }
                    wait = if (screenOn()) 15_000L else 60_000L
                }
                val tryAt = System.currentTimeMillis()
                attempt++
                linkSession?.let { it.reconnectAttempts++ }
                try {
                    ssh?.reset()
                    h.check()
                    if (!isActive || herdr !== h || !foreground && current?.overWebSocket == true) return@launch
                    linkSession?.let { it.reconnectSuccesses++ }
                    com.billmongan.herdrdroid.diag.DiagLog.log("link", com.billmongan.herdrdroid.diag.LinkStats.reconnectLine(
                        current?.id, attempt, backoff, reconnectReason, "ok", System.currentTimeMillis() - tryAt))
                    linkTo(Link.CONNECTED, "reconnected")
                    questionsFeed.poke()
                    // 2.2.7: signed in again after a network change: the fallback watches this connection.
                    rearmAfterSignIn()
                    linkProblem = null
                    flushQueue()
                    refreshPanes()
                    return@launch
                } catch (c: CancellationException) {
                    throw c
                } catch (e: AuthRejected) {
                    if (!isActive || herdr !== h || !foreground && current?.overWebSocket == true) return@launch
                    stopForProblem(authMessage(current, null, e))
                    return@launch
                } catch (e: HostKeyChanged) {
                    if (!isActive || herdr !== h || !foreground && current?.overWebSocket == true) return@launch
                    stopForProblem(hostKeyMessage(e))
                    return@launch
                } catch (e: Exception) {
                    if (!isActive || herdr !== h || !foreground && current?.overWebSocket == true) return@launch
                    // Each failed try once, then (a long outage) every tenth.
                    if (attempt <= 5 || attempt % 10 == 0) com.billmongan.herdrdroid.diag.DiagLog.log("link", com.billmongan.herdrdroid.diag.LinkStats.reconnectLine(
                        current?.id, attempt, backoff, reconnectReason, "failed", System.currentTimeMillis() - tryAt, e))
                    linkProblem = friendly(e)
                    if (System.currentTimeMillis() - downSince > 15_000) linkTo(Link.OFFLINE, "down-over-15s:${com.billmongan.herdrdroid.diag.LinkDiag.errorClass(e)}")
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
                backoff = waited
                wait = if (cutShort) 1_000L else (wait * 2).coerceAtMost(8_000)
            }
        }
    }

    /** The Retry button on the offline banner. */
    fun retryNow() {
        reconnectReason = "retry-pressed"
        clearPanePrefetch()
        reconnectJob?.cancel()
        linkTo(Link.RECONNECTING, "retry-pressed")
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
    /** The last quiet failure logged, per stage and error class, to keep a polling failure to one line a minute (2.2.5). */
    private val quietFailLogged = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun <T> quietly(allowBackground: Boolean = false, block: suspend (Herdr) -> T): T? =
        quietly(allowBackground, null, block)

    private suspend fun <T> quietly(allowBackground: Boolean, stage: com.billmongan.herdrdroid.diag.LinkDiag.Stage?, block: suspend (Herdr) -> T): T? {
        if (!canPoll && !(allowBackground && !locked && link == Link.CONNECTED)) return null
        val h = herdr ?: return null
        return try {
            block(h)
        } catch (c: CancellationException) {
            throw c
        } catch (e: LinkDown) {
            // 2.2.6 (pre-review H1): a read on a connection that is no longer the open one (a
            // server switch closed it) does not reconnect the new one.
            if (herdr === h) linkLost(e)
            null
        } catch (e: AuthRejected) {
            stopForProblem(authMessage(current, null, e)); null
        } catch (e: HostKeyChanged) {
            stopForProblem(hostKeyMessage(e)); null
        } catch (e: Exception) {
            // 2.2.6: a read that timed out soon after sign-in: reconnect once with small packets.
            if (herdr === h && maybeSmallPacketFallback(e, h.transport)) {
                reconnectForFallback(h)
                return null
            }
            // 2.2.5: this used to vanish without a word, so a list that never arrived left no trace.
            if (stage != null) {
                val key = "${stage.label}|${e.javaClass.simpleName}"
                val now = System.currentTimeMillis()
                if (now - (quietFailLogged[key] ?: 0L) >= 60_000L) {
                    quietFailLogged[key] = now
                    com.billmongan.herdrdroid.diag.LinkDiag.stage(connectRun.toLong(), current?.id, stage, "failed",
                        fields = mapOf("error" to com.billmongan.herdrdroid.diag.LinkDiag.errorClass(e), "exit" to (e as? HerdrError)?.exit))
                }
            }
            null
        }
    }

    private val paneDispatch = com.billmongan.herdrdroid.herdr.PaneDispatch()

    /** Open prompt-write permits and panes being closed (2.0.1 findings 2 and 4). */
    private val writeGate = com.billmongan.herdrdroid.herdr.WriteGate()
    private val localPromptSend = com.billmongan.herdrdroid.herdr.LocalPromptSend(paneDispatch, writeGate)
    private val paneClose = com.billmongan.herdrdroid.herdr.PaneClose(paneDispatch, writeGate)

    /**
     * Runs something the user tapped. While the link is down it waits in a queue and goes out once
     * the link is back; only a real refusal from herdr is shown.
     */
    private fun act(paneId: String?, label: String, pendingId: Long? = null, input: Boolean = false, run: suspend (Herdr) -> Unit) {
        val server = current?.id
        // 2.1.1 (Grok's RC review, M1): with no server open (switching to another computer's agent)
        // a pane id names no pane, and the next client to connect may have another pane with that
        // id, so the tap is dropped here, never kept for whichever server comes next.
        if (paneId != null && server == null) {
            com.billmongan.herdrdroid.diag.DiagLog.log("send", "dropped, no computer open: $label")
            pendingId?.let { id -> pendingSends = pendingSends.filterNot { it.id == id } }
            error = "$label: no computer is open, so nothing was sent."
            return
        }
        val key = if (paneId != null && (pendingId != null || input)) server!! to paneId else null
        viewModelScope.launch {
            if (link != Link.CONNECTED || herdr == null) {
                enqueue(Queued(paneId, label, pendingId, key, run, server))
                return@launch
            }
            perform(Queued(paneId, label, pendingId, key, run, server))
        }
    }

    private suspend fun perform(q: Queued) {
        val h = herdr ?: return
        // A pane tap belongs to the server it was made on (2.1.1, Grok's RC review M1), queued or not.
        if (q.paneId != null && q.serverId != current?.id) {
            com.billmongan.herdrdroid.diag.DiagLog.log("send", "dropped, the computer changed: ${q.label}")
            q.pendingId?.let { id ->
                pendingSends = pendingSends.map {
                    if (it.id == id) it.copy(awaitingLink = false, waiting = false, undelivered = true, held = "the computer changed before it went") else it
                }
            }
            return
        }
        // 2.2.6 (Grok's review): notes whether any command of this action reached the computer.
        val trace = com.billmongan.herdrdroid.ssh.SendTrace()
        // The whole action counts as a send in flight, from its guard reads through its write, so
        // the fallback's reconnect can't reset the connection in between (Grok's re-review).
        val lane = h.transport
        lane.beginSend()
        try { kotlinx.coroutines.withContext(trace) {
            val key = q.dispatchKey
            if (key == null) q.run(h)
            else {
                val epoch = writeGate.epoch(key)
                paneDispatch.run(key) {
                    // A close confirmed while this tap waited for the lane drops it, even once the close is over.
                    if (current?.id != key.first || writeGate.isClosing(key) || writeGate.epoch(key) != epoch) return@run
                    val connected = herdr
                    if (connected != null && link == Link.CONNECTED) q.run(connected) else enqueue(q)
                }
            }
        } } catch (c: CancellationException) {
            throw c
        } catch (e: LinkDown) {
            if (trace.started) {
                // It may have reached the computer (the link went, or a reset cut it, mid-command):
                // sending it again could type it twice, so it is reported instead of queued.
                com.billmongan.herdrdroid.diag.DiagLog.log("send", "not sent again, it had started when the link went: ${q.label}")
                q.pendingId?.let { id ->
                    pendingSends = pendingSends.map {
                        if (it.id == id) it.copy(awaitingLink = false, waiting = false, undelivered = true, held = "the link dropped while it was being sent") else it
                    }
                }
                error = "${q.label}: the link dropped while it was being sent. Check the agent before sending it again."
            } else enqueue(q)
            if (herdr === h) linkLost(e)
        } catch (e: AuthRejected) {
            stopForProblem(authMessage(current, null, e))
        } catch (e: HostKeyChanged) {
            stopForProblem(hostKeyMessage(e))
        } catch (e: HerdrError) {
            error = "${q.label}: ${e.message}"
        } catch (e: com.billmongan.herdrdroid.herdr.DeliveryUncertain) {
            // 2.2.8 (Codex's re-review 3): a bound write failed after it began: kept as maybe sent, never resent by itself.
            com.billmongan.herdrdroid.diag.DiagLog.log("send", "a write failed after it began; it may have gone in: ${q.label}")
            q.pendingId?.let { id ->
                pendingSends = pendingSends.map {
                    if (it.id == id) it.copy(awaitingLink = false, waiting = false, undelivered = true, held = MAY_HAVE_SENT) else it
                }
            }
            error = "${q.label}: it may have reached the agent (${e.message}). Check the agent before sending it again."
        } catch (e: Exception) {
            error = "${q.label}: ${friendly(e)}"
        } finally {
            lane.endSend()
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
        if (herdr != null && link == Link.CONNECTED) linkLost(null, "queued-send")
    }

    private suspend fun flushQueue() {
        while (queue.isNotEmpty() && link == Link.CONNECTED) {
            val q = queue.removeFirst()
            queuedCount = queue.size
            perform(q)
        }
    }

    // ---- Panes ------------------------------------------------------------------------------

    /** When each pane last reported an agent, keyed "server|pane". */
    private val agentSeenAt = mutableMapOf<String, Long>()

    suspend fun refreshPanes(allowBackground: Boolean = false) {
        val client = herdr
        val prefetchConnection = client?.connectionIdentity?.takeIf { foreground && !locked }
        val readAt = System.currentTimeMillis()
        val readServer = current?.id
        quietly(allowBackground, com.billmongan.herdrdroid.diag.LinkDiag.Stage.LIST_READ) { it.panes() }?.let { fresh0 ->
            // A list read on a connection that has since been replaced (a reconnect, another server)
            // is dropped: it must not land on whichever server is open now.
            if (herdr !== client || current?.id != readServer) {
                com.billmongan.herdrdroid.diag.LinkDiag.stage(connectRun.toLong(), readServer, com.billmongan.herdrdroid.diag.LinkDiag.Stage.APPLY, "stale-dropped",
                    System.currentTimeMillis() - readAt, mapOf("parsed" to fresh0.size))
                return
            }
            // herdr sometimes reports a pane's agent as missing for a moment (while it redraws, or
            // just after a screen closes). Keep the agent last seen in that pane, so its card keeps
            // its usage and model instead of going blank until the next read.
            val before = panes.associateBy { it.id }
            val t = System.currentTimeMillis()
            val seenServer = current?.id
            fresh0.forEach { p -> if (p.agent != null) agentSeenAt["$seenServer|${p.id}"] = t }
            val fresh = fresh0.map { p ->
                val was = before[p.id]?.agent
                // Only for a minute: an agent that really quit leaves a plain shell behind.
                if (p.agent == null && was != null && t - (agentSeenAt["$seenServer|${p.id}"] ?: 0L) < 60_000) {
                    com.billmongan.herdrdroid.diag.DiagLog.log("pane", "${p.id}: herdr reported no agent; keeping $was")
                    p.copy(agent = was)
                } else p
            }
            val previous = rawWorkPanes.associateBy { it.id }
            val unchanged = fresh.filter { p -> previous[p.id]?.sameWorkIdentity(p) == true }
                .map { it.id }.toSet()
            busyCounts = busyCounts.filterKeys { it in unchanged }
            workCounts = workCounts.filterKeys { it in unchanged }
            work = work.filterKeys { it in unchanged }
            endedProcs = endedProcs.filterKeys { it in unchanged }
            workScreens = workScreens.filterKeys { it in unchanged }
            movedToBackground = movedToBackground.filterKeys { it in unchanged }
            rawWorkPanes = fresh
            maintainChats(before, fresh)
            applyWorkStatus()
            logListCounts(client, readServer, fresh0.size, System.currentTimeMillis() - readAt)
            current?.let { s -> noticeChanges(s.id, s.label, fresh) { herdr } }
            schedulePanePrefetch(prefetchConnection)
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
        }
    }

    /** The counts last logged per server: a list read is logged when they change (2.2.5). */
    private val listCountsLogged = HashMap<String, String>()

    private fun logListCounts(client: Herdr?, serverId: String?, parsed: Int, ms: Long) {
        val raw = client?.lastListCounts?.raw
        val visible = panes.count { it.agent != null }
        val key = "$raw/$parsed/${panes.size}/$visible"
        if (serverId == null || listCountsLogged[serverId] == key) return
        listCountsLogged[serverId] = key
        com.billmongan.herdrdroid.diag.LinkDiag.stage(connectRun.toLong(), serverId, com.billmongan.herdrdroid.diag.LinkDiag.Stage.APPLY, "ok", ms,
            mapOf("raw" to raw, "parsed" to parsed, "shown" to panes.size, "visible" to visible, "backend" to client?.backend?.name?.lowercase()))
    }

    private data class PanePreview(
        val server: String, val client: Herdr, val connection: Any, val pane: Pane,
        val capturedAt: Long, val text: String,
    )

    private val panePreviews = LinkedHashMap<String, PanePreview>()
    private val panePrefetchAttempts = mutableMapOf<String, Long>()
    private var panePrefetchJob: Job? = null
    private var panePrefetchOwner: PanePreview? = null
    private var lastPanePrefetchAt: Long? = null
    private var openingPanePreview by mutableStateOf<PanePreview?>(null)

    private fun clearPanePrefetch() {
        panePrefetchJob?.cancel()
        panePrefetchOwner = null
        panePreviews.clear()
        panePrefetchAttempts.clear()
        openingPanePreview = null
    }

    private fun Pane.canPrefetch(client: Herdr) = agent != null &&
        (client.backend == Backend.CORE || scrollOffset == 0)

    private fun PanePreview.matches(now: Long): Boolean = foreground && !locked && canPoll &&
        current?.id == server && herdr === client && client.connectionIdentity === connection &&
        now - capturedAt < PANE_PREVIEW_TTL_MS && rawWorkPanes.any {
            it.sameCardOccupant(pane) && it.status == pane.status && it.canPrefetch(client)
        }

    /** Speculative only. Never go through the selected read, notification or input paths. */
    private fun schedulePanePrefetch(pollConnection: Any?) {
        val now = android.os.SystemClock.elapsedRealtime()
        panePreviews.values.removeAll { !it.matches(now) }
        if (openingPanePreview?.matches(now) == false) openingPanePreview = null
        if (panePrefetchOwner?.matches(now) == false) panePrefetchJob?.cancel()
        if (!foreground || locked || !canPoll || panePrefetchJob?.isCompleted == false) return
        if (lastPanePrefetchAt?.let { now - it < 3_000L } == true) return
        val h = herdr ?: return
        val sid = current?.id ?: return
        val connection = h.connectionIdentity ?: return
        if (connection !== pollConnection) return
        val selected = (screen as? Screen.Agent)?.paneId
        val eligible = rawWorkPanes.filter { it.id != selected && it.canPrefetch(h) }
        panePrefetchAttempts.keys.retainAll(eligible.map { it.id }.toSet())
        // Visit least-recently tried panes first; on a tie, Needs you then Working.
        // Preserve that rotation even on a failed/blank read so one pane can't starve the others.
        val p = eligible.minWithOrNull(compareBy<Pane>({ panePrefetchAttempts[it.id] ?: -1L }, { it.status.ordinal })) ?: return
        val visit = paneVisit
        lastPanePrefetchAt = now
        panePrefetchAttempts[p.id] = now
        val owner = PanePreview(sid, h, connection, p, now, "")
        val modelSequence = ++modelReadSequence
        panePrefetchOwner = owner
        panePrefetchJob = viewModelScope.launch {
            if (paneVisit != visit || !owner.matches(android.os.SystemClock.elapsedRealtime())) return@launch
            // One short live viewport read on the existing fast channel, no reconnect or fallback.
            val text = try {
                h.readCardViewport(p.id, connection, timeoutSeconds = 1)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                return@launch
            }
            if (text.isBlank() || text.length > 8_192 || paneVisit != visit || !owner.matches(android.os.SystemClock.elapsedRealtime())) return@launch
            recordModelFooter(sid, p, text, modelSequence)
            panePreviews.remove(p.id)
            panePreviews[p.id] = owner.copy(text = text)
            if (panePreviews.size > 8) panePreviews.remove(panePreviews.keys.first())
        }
    }

    /** Display-only screen for the opening visit; never a source of CardTarget or question history. */
    internal fun prefetchedPaneText(paneId: String): String? = openingPanePreview?.takeIf {
        (screen as? Screen.Agent)?.paneId == paneId && it.pane.id == paneId && paneText.isEmpty() &&
            paneReadProblem == null && it.matches(android.os.SystemClock.elapsedRealtime())
    }?.text

    private fun takePanePreview(paneId: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val preview = panePreviews.remove(paneId)?.takeIf { it.matches(now) }
        openingPanePreview = preview
        if (preview == null) return
        // 2.2.2: the preview is the visit's first read for the question card too (shown read-only meanwhile).
        prefetchedPaneText(paneId)?.let { t -> offerQuestionRead(current?.id, paneId, t) }
        viewModelScope.launch {
            delay(PANE_PREVIEW_TTL_MS - (now - preview.capturedAt))
            if (openingPanePreview === preview) openingPanePreview = null
        }
    }

    private val PANE_PREVIEW_TTL_MS = 15_000L

    private var widgetSig = ""

    /** Saves what the home-screen widget shows and redraws it, when something changed. */
    private fun updateWidget(fresh: List<Pane>) {
        val s = current ?: return
        val agents = fresh.filter { it.agent != null }
        val lines = agents.take(5).joinToString("\n") { p ->
            val mark = when (p.status) { Status.NEEDS_YOU -> "● "; Status.WORKING -> "◐ "; else -> "○ " }
            mark + p.agentName + (if (p.folder.isNotBlank()) " · " + p.folder else "") + " · " + com.billmongan.herdrdroid.ui.look(p.status, p.backgroundBusy).label
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

    private var modelReadSequence = 0L
    private val lastModelFooter = mutableMapOf<Pair<String, String>, Pair<Pane, Long>>()

    /** Only a footer that names a model can replace the current label. */
    private fun recordModelFooter(server: String, pane: Pane, text: String, sequence: Long) {
        val model = footerModel(pane.agent, com.billmongan.herdrdroid.herdr.stripAnsi(text)) ?: return
        val key = server to pane.id
        val last = lastModelFooter[key]
        if (last != null && sequence < last.second) return
        lastModelFooter[key] = pane to sequence
        val old = if (server == current?.id) models else modelCache[server].orEmpty()
        if (old[pane.id] == model) return
        val fresh = old + (pane.id to model)
        modelCache[server] = fresh
        if (server == current?.id) {
            models = fresh
            noteModels()
        } else modelsVersion++
    }

    private suspend fun refreshAnsweredModel(target: CardTarget, shown: Prompt) {
        // Read only a model dialog's acknowledged result, never use the highlighted choice as the label.
        if (!shown.question.lines().any { row ->
                Regex("""^\s*(?:(?:select|choose|change|switch)(?:\s+(?:to|a|the|your))*\s+models?\b|model\s+(?:selection|picker)\b)""",
                    RegexOption.IGNORE_CASE).containsMatchIn(row)
            }) return
        val h = target.client
        if (!target.isCurrent(h)) return
        val id = target.pane.id
        val sequence = ++modelReadSequence
        val fresh = quietly { client ->
            if (client !== h) return@quietly null
            val now = h.cardPane(id, target.connection) ?: return@quietly null
            if (!now.sameCardOccupant(target.pane) || now.scrollOffset != 0) return@quietly null
            h.readStyledViewport(id, target.connection, timeoutSeconds = 2)
        }
        if (fresh != null && target.isCurrent(h)) recordModelFooter(target.server, target.pane, fresh, sequence)
    }

    private var lastPreviewsAt = 0L
    private var lastWorkAt = 0L

    suspend fun refreshPreviews() {
        val server = current?.id ?: return
        val client = herdr ?: return
        val connection = client.connectionIdentity
        val snapshot = panes.associateBy { it.id }
        val sequence = ++modelReadSequence
        fun currentRead() = current?.id == server && herdr === client && client.connectionIdentity === connection
        quietly { it.previews(snapshot.keys.toList()) }?.let { peeks ->
            if (!currentRead()) return
            // The last line of real output on each card, looking back past status chrome.
            previews = peeks.mapValues { (id, peek) ->
                val agent = panes.firstOrNull { it.id == id }?.agent
                // Skip the agent's own footer too (the line its model is read from).
                peek.tail.lines().asReversed()
                    .filter { footerModel(agent, it) == null }
                    .map { com.billmongan.herdrdroid.ui.cardLine(it) }.firstOrNull { it.isNotBlank() } ?: ""
            }
            peeks.forEach { (id, peek) ->
                val expected = snapshot[id] ?: return@forEach
                val p = pane(id)?.takeIf { it.sameCardOccupant(expected) && (it.scrollOffset ?: 0) == 0 } ?: return@forEach
                recordModelFooter(server, p, peek.tail, sequence)
            }
        }
        // Claude can report the model in its own reply log, when the current screen has no footer.
        val unproven = snapshot.values.filter { p ->
            lastModelFooter[server to p.id]?.first?.sameCardOccupant(p) != true
        }
        quietly { it.logModels(unproven) }?.let { fromLog ->
            if (!currentRead()) return
            val applicable = fromLog.filterKeys { id ->
                val p = snapshot[id] ?: return@filterKeys false
                pane(id)?.sameCardOccupant(p) == true &&
                    lastModelFooter[server to id]?.first?.sameCardOccupant(p) != true
            }
            if (applicable.isNotEmpty()) { models = models + applicable; noteModels() }
        }
        refreshWorkCounts()
        refreshGit()
        if (currentRead()) { previewCache[server] = previews; modelCache[server] = models }
    }

    /** How many lines the alerts read, when made and when answered: the same both times (1.4.2). */
    private val NOTIFY_READ_LINES = 80

    /** 2.1.4: closing a usage dialog the app opened. */
    private val USAGE_OPEN_MAX_MS = 3 * 60_000L
    private val USAGE_CLOSE_AUTO_TRIES = 3
    /** 2.2.1: between panel Esc presses; longer than Grok's and Claude's double-Esc window (800 ms). */
    private val PANEL_SETTLE_MS = 1_200L
    private val USAGE_NOTICE = "HerdrDroid couldn't confirm that"
    private val BTW_SETTLE_MS = 700L

    /** Why the open pane's screen couldn't be read, when the last read failed (1.4.2); null after a good read. */
    var paneReadProblem by mutableStateOf<String?>(null)
        private set

    private var paneReadSequence = 0L
    private var lastAppliedPaneRead = 0L

    /**
     * Counts pane reads that finished for the open pane (2.1.1). Parts of [cardTarget] are not
     * Compose state (the herdr client, its connection, which a reset leaves empty until the next
     * command), so a menu built from them would not change when they do. Reading this count makes
     * such a menu recompose after each read, the first included.
     */
    private var paneReadsApplied by mutableStateOf(0L)

    internal data class OpenUsage(val server: String, val pane: String, val visit: Long, val client: Herdr, val agent: String, val overlay: UsageOverlay)
    private var openUsage by mutableStateOf<OpenUsage?>(null)
    private var closingUsage by mutableStateOf<OpenUsage?>(null)

    private fun currentUsage(usage: OpenUsage): Boolean = foreground && canPoll && !locked &&
        current?.id == usage.server && herdr === usage.client && paneVisit == usage.visit &&
        (screen as? Screen.Agent)?.paneId == usage.pane && pane(usage.pane)?.agent == usage.agent

    internal fun usageOverlayFor(paneId: String): OpenUsage? = openUsage?.takeIf {
        it.pane == paneId && paneReadProblem == null && currentUsage(it)
    }

    internal fun usageDismissInFlight(paneId: String): Boolean = closingUsage?.let { it.pane == paneId && currentUsage(it) } == true ||
        current?.id?.let { "$it/$paneId" in closingPanels } == true

    internal fun dismissUsage(usage: OpenUsage) {
        if (usageOverlayFor(usage.pane) != usage || closingUsage != null) return
        // One the app opened is closed and checked closed the same way as when you leave it (2.1.4).
        if (openedUsageOf(usage.server, usage.pane) != null) { closeAppUsage(usage.pane, "Dismiss", automatic = false); return }
        // A /btw panel opened in the terminal (2.1.4): its exchange is kept first, then the Close tap
        // closes it the same checked way as one the app opened.
        com.billmongan.herdrdroid.herdr.claudeBtwOverlay(usage.overlay.visible)?.let { b ->
            recordBtw(usage.server, usage.pane, com.billmongan.herdrdroid.herdr.BtwRead(b.question, b.body, b.complete, b.more))
            saveOpenedUsage(openedUsage + OpenedUsage(usage.server, usage.pane, usage.agent, System.currentTimeMillis(), btw = b.question))
            closeAppUsage(usage.pane, "Close", automatic = false)
            return
        }
        // 2.2.1: any other panel is closed and checked closed ([closePanelAndVerify]), at most 2 Esc.
        if (current?.id == usage.server) { closeInfoPanel(usage.server, usage.pane, "Close panel", automatic = false); return }
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
                        if (currentUsage(usage) && (!sent || paneTextPane != usage.pane || paneReadProblem != null || openUsage != null ||
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

    // ---- 2.1.4: usage dialogs the app opened are closed, and checked closed -------------------

    /** A usage dialog the app opened (a /usage sent from the composer) and hasn't confirmed closed. */
    internal data class OpenedUsage(val server: String, val pane: String, val agent: String, val at: Long, val failures: Int = 0,
        /** 2.1.4: the question of a /btw panel the app opened (null for a usage dialog). */
        val btw: String? = null)

    internal var openedUsage by mutableStateOf(loadOpenedUsage())
        private set
    private val closingAppUsage = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** The pane whose terminal view is showing a usage dialog the app opened, while you look at it. */
    @Volatile private var usageViewPane: Pair<String, String>? = null

    private fun loadOpenedUsage(): List<OpenedUsage> = runCatching {
        val a = org.json.JSONArray(prefs.usageLeftOpen)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            OpenedUsage(o.getString("server"), o.getString("pane"), o.getString("agent"), o.optLong("at"), o.optInt("failures"),
                if (o.isNull("btw")) null else o.optString("btw").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    private fun saveOpenedUsage(list: List<OpenedUsage>) {
        openedUsage = list
        prefs.usageLeftOpen = org.json.JSONArray(list.map {
            org.json.JSONObject().put("server", it.server).put("pane", it.pane).put("agent", it.agent).put("at", it.at).put("failures", it.failures).putOpt("btw", it.btw)
        }).toString()
    }

    private fun openedUsageOf(server: String, paneId: String) = openedUsage.firstOrNull { it.server == server && it.pane == paneId }

    /** True while a usage dialog the app opened in [paneId] (on the open server) isn't confirmed closed. */
    internal fun usageLeftOpen(paneId: String): Boolean = current?.id?.let { openedUsageOf(it, paneId) } != null

    /** The chat shows (true) or stops showing (false) the terminal view of a usage dialog it opened. */
    internal fun usageViewShown(paneId: String, shown: Boolean) {
        val sid = current?.id ?: return
        if (shown) usageViewPane = sid to paneId
        else if (usageViewPane == sid to paneId) {
            usageViewPane = null
            if (openedUsageOf(sid, paneId) != null) closeAppUsage(paneId, "you left it")
        }
    }

    /**
     * Closes the usage dialog the app opened in [paneId] and confirms it closed ([closeUsageAndVerify]):
     * at most two Esc presses (2.2.1; three before), each only for its known layout and at least
     * [PANEL_SETTLE_MS] apart, so they never land inside Grok's or Claude's double-Esc window, and
     * nothing else typed. It runs in the
     * pane's dispatch lane, so no other app action on that pane runs meanwhile, and NonCancellable, so
     * leaving the screen or the app doesn't stop it halfway. When it can't confirm the close, the record
     * stays (the next check or send tries again first) and the chat shows a pane notice.
     */
    internal fun closeAppUsage(paneId: String, why: String, automatic: Boolean = true, expectedConnection: Any? = null): Job? {
        val sid = current?.id ?: return null
        val rec = openedUsageOf(sid, paneId) ?: return null
        if (automatic && rec.failures >= USAGE_CLOSE_AUTO_TRIES) return null
        val h = herdr ?: return null
        val connection = expectedConnection ?: h.connectionIdentity ?: return null
        val key = "$sid/$paneId"
        if (!closingAppUsage.add(key)) return null
        return viewModelScope.launch {
            try {
                val outcome = withContext(kotlinx.coroutines.NonCancellable) {
                    paneDispatch.run(sid to paneId) {
                        com.billmongan.herdrdroid.herdr.closeUsageAndVerify(
                            stillCurrent = { current?.id == sid && herdr === h && h.connectionIdentity === connection && pane(paneId)?.agent == rec.agent },
                            readState = { h.readUsageState(paneId, rec.agent, expectedConnection = connection) },
                            sendEscape = { shown -> h.closeUsageOverlay(paneId, rec.agent, shown, expectedConnection = connection) },
                            settle = { delay(PANEL_SETTLE_MS) },
                            maxEscapes = 2,
                        )
                    }
                }
                val name = pane(paneId)?.agentName ?: rec.agent
                com.billmongan.herdrdroid.diag.DiagLog.log("usage", "$paneId: close ($why): " +
                    if (outcome.closed) "closed, ${outcome.escapes} Esc" else "not confirmed, ${outcome.why}")
                val now = openedUsageOf(sid, paneId)
                if (outcome.closed) {
                    saveOpenedUsage(openedUsage.filterNot { it.server == sid && it.pane == paneId })
                    sendNotices = sendNotices.filterNot { it.serverId == sid && it.paneId == paneId && it.text.startsWith(USAGE_NOTICE) }
                } else if (now != null) {
                    saveOpenedUsage(openedUsage.map { if (it === now) it.copy(failures = it.failures + 1) else it })
                    val what = if (rec.btw != null) "side-question panel" else "usage screen"
                    noteSendProblem(sid, paneId, "$USAGE_NOTICE $name's $what closed (${outcome.why}). Nothing will be typed into it " +
                        "until it is; close it with Esc in Terminal, or Dismiss.")
                }
            } finally {
                closingAppUsage.remove(key)
            }
            if (current?.id == sid && (screen as? Screen.Agent)?.paneId == paneId) runCatching { readPane(paneId) }
        }
    }

    // ---- 2.2.1: informational panels over the composer, for every agent ----------------------

    /** (server/pane) keys whose panel close is running. */
    private val closingPanels = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Failed automatic closes per (server/pane); after one, only a Close panel tap tries again. */
    private val panelCloseFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** (server/pane/visit) keys already closed once on opening the pane. */
    private val panelOpenCloses = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** (server/pane/visit) keys whose first shown read was taken: only that read closes a panel on opening. */
    private val panelVisitsRead = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** Why the last close of a panel in (server/pane) didn't finish, for its card. */
    internal var panelCloseProblems by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    internal fun panelCloseProblem(paneId: String): String? = current?.id?.let { panelCloseProblems["$it/$paneId"] }

    /**
     * Why a send to [p] waits on the panel [screen] shows (2.2.1), and, when the panel is positively
     * identified and the agent idle on the open server, starts closing it ([closeInfoPanel]).
     */
    private fun panelHold(server: String?, p: Pane, screen: String): String {
        val panel = com.billmongan.herdrdroid.herdr.infoPanelOn(p.agent, screen) ?: return USAGE_OPEN_HOLD
        if (panel.known && p.status != Status.WORKING && server != null && server == current?.id &&
            (closeInfoPanel(server, p.id, "a message was waiting") != null || "$server/${p.id}" in closingPanels)) {
            return "its ${panel.kind.label} is open; HerdrDroid is closing it first"
        }
        val why = panel.why ?: panelCloseProblems["$server/${p.id}"] ?: "it couldn't be closed from here"
        return "its ${panel.kind.label} may be open ($why); close it at the computer"
    }

    /** Bill opened the pane (2.2.1): a panel left open in it is closed once per visit. */
    private fun closePanelOnOpen(u: OpenUsage) {
        if (openedUsageOf(u.server, u.pane) != null) return // the app's own: its own flow closes it
        if (com.billmongan.herdrdroid.herdr.claudeBtwOverlay(u.overlay.visible) != null) return
        if (!panelOpenCloses.add("${u.server}/${u.pane}/${u.visit}")) return
        closeInfoPanel(u.server, u.pane, "you opened the pane")
    }

    /**
     * Closes an informational panel in [paneId] on [server] and confirms the composer is back
     * ([closePanelAndVerify], 2.2.1): at most two Esc presses, each only for a positively identified
     * panel and only after the server read the same frame twice, in the pane's dispatch lane, and
     * NonCancellable. When it can't confirm, the pane's card says so and offers Close panel and
     * Show terminal. Null when nothing was started (not the open server, already closing, or an
     * automatic close that already failed once).
     */
    internal fun closeInfoPanel(server: String, paneId: String, why: String, automatic: Boolean = true): Job? {
        if (current?.id != server) return null
        val h = herdr ?: return null
        val agent = pane(paneId)?.agent ?: return null
        if (openedUsageOf(server, paneId) != null) return closeAppUsage(paneId, why, automatic)
        val key = "$server/$paneId"
        if (automatic && (panelCloseFailures[key] ?: 0) >= 1) return null
        if (!closingPanels.add(key)) return null
        return viewModelScope.launch {
            try {
                val outcome = withContext(kotlinx.coroutines.NonCancellable) {
                    paneDispatch.run(server to paneId) {
                        com.billmongan.herdrdroid.herdr.closePanelAndVerify(
                            stillCurrent = { current?.id == server && herdr === h && pane(paneId)?.agent == agent && pane(paneId)?.status != Status.WORKING },
                            readState = { h.readPanelState(paneId, agent) },
                            sendEscape = { shown -> h.closeUsageOverlay(paneId, agent, shown) },
                            settle = { delay(PANEL_SETTLE_MS) },
                        )
                    }
                }
                com.billmongan.herdrdroid.diag.DiagLog.log("panel", "$paneId: close ($why): " +
                    if (outcome.closed) "closed, ${outcome.escapes} Esc" else "not confirmed, ${outcome.why}")
                if (outcome.closed) {
                    panelCloseFailures.remove(key)
                    panelCloseProblems = panelCloseProblems - key
                } else {
                    panelCloseFailures.merge(key, 1, Int::plus)
                    panelCloseProblems = panelCloseProblems + (key to (outcome.why ?: "it couldn't be closed"))
                }
            } finally {
                closingPanels.remove(key)
            }
            if (current?.id == server && (screen as? Screen.Agent)?.paneId == paneId) runCatching { readPane(paneId) }
        }
    }

    /** True while the app is closing a usage dialog in [paneId] on [server]. */
    private fun closingAppUsage(server: String?, paneId: String) = server != null && "$server/$paneId" in closingAppUsage

    /** The next check (2.1.4): usage dialogs the app left open on the open server are closed first. */
    private fun retryLeftOpenUsage() {
        val sid = current?.id ?: return
        if (link != Link.CONNECTED) return
        openedUsage.filter { it.server == sid && usageViewPane != sid to it.pane }.forEach { closeAppUsage(it.pane, "a later check") }
    }

    /**
     * After the app typed a usage command, close the dialog when you leave its terminal view, the
     * pane or the app, or after [USAGE_OPEN_MAX_MS] at the most.
     */
    private fun watchOpenedUsage(sid: String, paneId: String) {
        viewModelScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            delay(1_500)
            while (isActive && openedUsageOf(sid, paneId) != null) {
                val viewing = foreground && current?.id == sid && (screen as? Screen.Agent)?.paneId == paneId && usageViewPane == sid to paneId
                val tooLong = android.os.SystemClock.elapsedRealtime() - started > USAGE_OPEN_MAX_MS
                if (!viewing || tooLong) {
                    if (current?.id == sid) closeAppUsage(paneId, if (tooLong) "open for ${USAGE_OPEN_MAX_MS / 60_000} minutes" else "you left it")?.join()
                    break
                }
                delay(1_000)
            }
        }
    }

    /** After the app typed /usage or /btw: a usage dialog is closed when you leave it; a /btw panel is read, then closed. */
    private fun followOpened(rec: OpenedUsage) {
        if (rec.btw != null) followBtw(rec) else watchOpenedUsage(rec.server, rec.pane)
    }

    /**
     * A /btw the app sent (2.1.4): waits for the answer, reads it to the end (scrolling the panel,
     * which only the app's own /btw is), keeps the exchange in the chat, then closes the panel and
     * checks that it closed. The read runs in the pane's dispatch lane, so nothing else is typed in
     * meanwhile; the keeping and the close run whatever happens to the read (finally, NonCancellable).
     */
    private fun followBtw(rec: OpenedUsage) {
        val h = herdr ?: return
        val connection = h.connectionIdentity ?: return
        viewModelScope.launch {
            var got: com.billmongan.herdrdroid.herdr.BtwRead? = null
            try {
                got = paneDispatch.run(rec.server to rec.pane) {
                    var shown: UsageOverlay? = null
                    com.billmongan.herdrdroid.herdr.readBtwToEnd(
                        read = {
                            if (current?.id != rec.server || herdr !== h || h.connectionIdentity !== connection) null
                            else (h.readUsageState(rec.pane, "claude", expectedConnection = connection) as? com.billmongan.herdrdroid.herdr.UsageState.Open)?.overlay
                                ?.let { o -> com.billmongan.herdrdroid.herdr.claudeBtwOverlay(o.visible)?.also { shown = o } }
                        },
                        settle = { delay(BTW_SETTLE_MS) },
                        scrollDown = { _ -> shown?.let { h.scrollBtwOverlay(rec.pane, it, expectedConnection = connection) } ?: false },
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                com.billmongan.herdrdroid.diag.DiagLog.log("btw", "${rec.pane}: reading the side answer failed: ${com.billmongan.herdrdroid.diag.DiagLog.describe(e)}")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    val read = got
                    if (read != null) recordBtw(rec.server, rec.pane, read)
                    else com.billmongan.herdrdroid.diag.DiagLog.log("btw", "${rec.pane}: no side answer was read")
                    if (current?.id == rec.server && herdr === h && h.connectionIdentity === connection)
                        closeAppUsage(rec.pane, "the side answer was read", automatic = false, expectedConnection = connection)?.join()
                }
            }
        }
    }

    /** Keeps a /btw exchange in the pane's chat history (2.1.4). */
    internal fun recordBtw(server: String, paneId: String, read: com.billmongan.herdrdroid.herdr.BtwRead) {
        val log = loadedQuestions(server, paneId) ?: return
        val answer = com.billmongan.herdrdroid.herdr.btwAnswerMarkdown(read.rows)
        val next = com.billmongan.herdrdroid.transcript.QuestionLog.btw(log, read.question, answer, !read.complete || read.more,
            questionAnchor(server, paneId), System.currentTimeMillis())
        if (next != log) putQuestions(server, paneId, next)
    }

    /** The last /btw panel seen in each pane ("server|pane"), to keep one opened in the terminal once it is steady. */
    private val lastBtwSeen = java.util.concurrent.ConcurrentHashMap<String, com.billmongan.herdrdroid.herdr.BtwOverlay>()

    /**
     * A /btw panel opened in the terminal (2.1.4): once its answer is there and two reads show it the
     * same, the part in view is kept in the chat. Nothing is pressed; it stays open with Close on its
     * banner, since you may be reading it there.
     */
    private fun noteSeenBtw(serverId: String?, paneId: String, live: OpenUsage?) {
        if (serverId == null) return
        val k = "$serverId|$paneId"
        val b = live?.overlay?.visible?.let { com.billmongan.herdrdroid.herdr.claudeBtwOverlay(it) }
        if (b == null) { lastBtwSeen.remove(k); return }
        val before = lastBtwSeen.put(k, b)
        if (openedUsageOf(serverId, paneId)?.btw != null) return
        if (b.complete && before == b) recordBtw(serverId, paneId, com.billmongan.herdrdroid.herdr.BtwRead(b.question, b.body, true, b.more))
    }

    /**
     * Why the app must not type [pane]'s usage command now (2.1.4), or null: it opens only on an
     * idle agent with an empty input box, with nothing else from the app under way in that pane.
     */
    private fun usageOpenRefusal(pane: Pane, pendingId: Long, h: Herdr): String? {
        val sid = current?.id
        val now = pane(pane.id) ?: pane
        return when {
            h.backend != Backend.TUI -> "this herdr can't confirm that its usage screen closes again"
            now.status !in setOf(Status.IDLE, Status.DONE) -> "${now.agentName} isn't idle"
            (now.scrollOffset ?: 0) != 0 -> "its pane is scrolled back"
            pendingSends.any { it.isFor(sid, pane.id) && it.id != pendingId && (it.waiting || it.awaitingLink) } -> "an earlier message to it is still waiting"
            answerInFlight(pane.id) || closingAppUsage(sid, pane.id) || dismissingUsage.contains("$sid/${pane.id}") -> "another HerdrDroid action on it is still running"
            sid != null && openedUsageOf(sid, pane.id) != null -> "its usage screen from before isn't confirmed closed"
            else -> null
        }
    }

    suspend fun readPane(paneId: String) {
        // 2.2.8: only the pane open now is read (its result would be dropped anyway); from the Questions
        // view, an answer's follow-up read is the view's own next round instead.
        if ((screen as? Screen.Agent)?.paneId != paneId) {
            if (screen == Screen.Questions) questionsFeed.poke()
            return
        }
        val attempt = ++paneReadSequence
        val modelSequence = ++modelReadSequence
        // Only for the pane open now (1.4.2, from review: a late answer for another pane set or
        // cleared the open pane's problem).
        val serverId = current?.id
        val client = herdr
        val visit = paneVisit
        val started = android.os.SystemClock.elapsedRealtime()
        val originalCardOwner = cardTarget(paneId)
        val readConnection = client?.connectionIdentity
        val readTarget = pane(paneId)
        fun isOpen() = attempt > lastAppliedPaneRead && current?.id == serverId && herdr === client &&
            client?.connectionIdentity === readConnection && paneVisit == visit && (screen as? Screen.Agent)?.paneId == paneId
        fun problem(why: String) {
            if (!isOpen()) return
            lastAppliedPaneRead = attempt
            paneReadsApplied++
            if (paneReadProblem != why) com.billmongan.herdrdroid.diag.DiagLog.log("read", "$paneId: the screen couldn't be read: $why")
            paneReadProblem = why
            openingPanePreview = null
            paneTextTarget = null
            openCodeComposerTarget = null
            codexSteeringTarget = null
            piLiveQuestion = null
            hermesUsageRead = null
            openUsage = null
            paneDraft = null
            draftReadFor = null
        }
        val reading = quietly { h ->
            val waiting = pane(paneId)?.status == Status.NEEDS_YOU
            try {
                val lines = if (waiting || paneReadProblem != null) 80 else 200
                readOpenPaneScreen(h, paneId, lines, readTarget, readConnection, ::isOpen)
            } catch (e: HerdrError) {
                problem(e.message?.take(120) ?: "the screen could not be read")
                null
            }
        }
        if (reading == null && isOpen()) {
            codexSteeringTarget = null
            codexActivityTarget = null
        }
        reading?.let { (recent, originalPaneId) ->
            if (!isOpen()) return
            val cardOwner = originalCardOwner.takeIf { originalPaneId }
            val paneNow = pane(paneId)
            val agent = paneNow?.agent
            // Recent output can omit the current modal even when the read succeeds. Only the
            // guarded live viewport may offer dismissal, and this check itself never sends a key.
            val liveUsage = if (foreground && !locked && client != null && serverId != null &&
                agent != null && paneNow.status != Status.WORKING && (paneNow.scrollOffset ?: 0) == 0) {
                // 2.2.1: any agent whose recent read shows an informational panel gets the guarded read too.
                val anyAgent = com.billmongan.herdrdroid.herdr.usageScreenShown(agent, recent)
                quietly { h -> if (h === client) h.readUsageOverlay(paneId, agent, anyAgent) else null }
                    ?.let { OpenUsage(serverId, paneId, visit, client, agent, it) }
            } else null
            if (!isOpen()) return
            val paneStatus = pane(paneId)?.status
            val blocked = paneStatus == Status.NEEDS_YOU
            val version = versionOf(paneId)
            val preview = detectPrompt(recent, blocked, agent, version)
            fun canUpgrade() = foreground && isOpen() && cardOwner != null &&
                cardOwner.client.backend == Backend.TUI && cardOwner.isCurrent(cardOwner.client) &&
                pane(paneId)?.scrollOffset == 0
            // A nonempty recent capture can omit the entire dialog or just its choices
            // while status still says Working. Probe only the selected, live-bottom viewport.
            val missingCodexCard = agent == "codex" && paneStatus == Status.WORKING && preview?.choices.isNullOrEmpty()
            val recentClaudeModel = preview?.claudeModelControl == true
            val recentCodexCard = agent == "codex" && preview != null && client?.backend == Backend.TUI
            val recentCodexQuestion = recentCodexCard && preview?.codexQuestion == true
            // A blank screen on opening is not a previously shown card, even when Codex is blocked.
            val shownCodexCard = recentCodexCard && paneTextPane == paneId && paneText.isNotBlank() &&
                detectPrompt(paneText, blocked, agent, version) != null
            val requireCodexApprovalIdentity = (missingCodexCard && preview?.terminalReview != true) || recentCodexQuestion
            val unfinishedChoicePreview = agent in setOf("claude", "agy", "grok") &&
                paneStatus == Status.WORKING && preview?.choices.isNullOrEmpty()
            val verifyOpenCode = isOpenCode(agent) && client?.backend == Backend.TUI &&
                (blocked || paneStatus == Status.WORKING || preview != null || openCodeBackgroundDrawer(agent, recent))
            val verifyHermesUsage = agent == "hermes"
            val verifyPi = paneNow != null && requiresLivePiQuestion(paneNow)
            // 2.2.8 (Bill: "the AWS Cloud session failed to render a question ... make that more robust"): a
            // question the recent read shows but whose dialog that read can't prove (its top, or rows above it,
            // in the scrollback) is read again from the live screen, which shows the dialog alone.
            val unprovenPreview = blocked && preview != null && preview.choices.isNotEmpty() && !preview.terminalReview &&
                !com.billmongan.herdrdroid.herdr.questionRead(recent, preview.forCard(), agent, version, blocked).topped
            if (unprovenPreview) com.billmongan.herdrdroid.diag.DiagLog.log("prompt",
                "$paneId: the recent read's dialog isn't proven (${preview!!.choices.size} choice(s)); reading the live screen")
            val liveViewport = if (liveUsage == null &&
                (verifyOpenCode || verifyHermesUsage || verifyPi || recentClaudeModel || recentCodexQuestion || shownCodexCard || missingCodexCard || unfinishedChoicePreview || unprovenPreview ||
                    preview?.terminalReview == true || preview?.topOffScreen == true || (blocked && preview?.choices.isNullOrEmpty())) && canUpgrade()) {
                val owner = checkNotNull(cardOwner)
                quietly { h ->
                    if (h !== owner.client) return@quietly null
                    if ((verifyHermesUsage || verifyPi) && (!com.billmongan.herdrdroid.herdr.hasStableOccupant(owner.pane) ||
                            h.cardPane(paneId, owner.connection)?.sameCardOccupant(owner.pane) != true || !canUpgrade())) return@quietly null
                    // 2.2.8: a far server (a cloud host) can take more than a second to answer.
                    h.readStyledViewport(paneId, owner.connection, timeoutSeconds = 3)
                }
                    ?.takeIf { canUpgrade() && !it.isBlank() }
            } else null
            val liveCard = liveViewport?.let { com.billmongan.herdrdroid.herdr.stripAnsi(it) }
            if (!isOpen()) return
            val liveCandidate = liveCard?.let {
                if (verifyPi) activePiQuestion(it, version)
                else detectPrompt(it, pane(paneId)?.status == Status.NEEDS_YOU, agent, version)
            }
            fun nativeReview(text: String): Boolean = when (agent?.lowercase()) {
                "agy" -> com.billmongan.herdrdroid.herdr.agySubagentQuestion(text) != null ||
                    com.billmongan.herdrdroid.herdr.agySubagentApproval(text)?.terminalReview == true ||
                    com.billmongan.herdrdroid.herdr.agyClippedShellCommand(text) != null
                "grok" -> com.billmongan.herdrdroid.herdr.grokPrivacyOverlay(text) != null
                else -> false
            }
            val recentNativeReview = nativeReview(recent)
            val liveNativeReview = liveCard?.let(::nativeReview) == true
            val livePrompt = liveCandidate?.takeIf {
                verifyOpenCode || it.codexQuestion || liveNativeReview ||
                    (it.choices.isNotEmpty() && !it.terminalReview && (!requireCodexApprovalIdentity || it.approvalIdentity != null)) ||
                    ((requireCodexApprovalIdentity || verifyHermesUsage || agent.equals("claude", true)) && it.terminalReview)
            }
            val liveReport = verifyHermesUsage && liveCard != null && hermesUsageEnd(liveCard) != null
            val recentReport = verifyHermesUsage && hermesUsageEnd(recent) != null
            val verifiedUsage = liveCard?.takeIf { verifyHermesUsage && livePrompt == null &&
                detectPrompt(it, false, "hermes", version) == null }?.let { live ->
                val ready = emptyHermesUsageComposer(live)
                val owner = checkNotNull(cardOwner)
                // Only a previously verified live report can explain a composer-only redraw.
                // Once native status catches up and the report scrolls away, drop that context.
                val continued = (blocked || recentReport) && currentHermesUsage(owner.pane) != null
                if (liveReport || continued && ready) HermesUsageRead(owner, ready) else null
            }
            val recentClarify = preview?.hermesClarify == true
            val requiresLive = verifyOpenCode || recentClaudeModel || recentClarify || recentCodexQuestion || verifyPi
            val codexQuestionCleared = liveCard != null && liveCandidate == null && (recentCodexQuestion ||
                agent == "codex" && com.billmongan.herdrdroid.herdr.hasCodexInputActivity(liveCard))
            // 2.2.8 (Codex's review 1): once the live screen was read for an unproven recent dialog, that read is
            // the card, a closed dialog included: the stale recent dialog never goes on to be confirmed.
            val unprovenAnswered = unprovenPreview && liveCard != null
            if (unprovenAnswered && livePrompt == null) com.billmongan.herdrdroid.diag.DiagLog.log("prompt",
                "$paneId: the live screen shows no dialog; the recent read's is not used")
            val recentClippedClaudeAsk = agent.equals("claude", true) && preview?.terminalReview == true &&
                com.billmongan.herdrdroid.herdr.claudeClippedAskReview(recent) != null
            val useLive = liveCard != null && (livePrompt != null || recentNativeReview || recentClaudeModel || recentClippedClaudeAsk || preview?.topOffScreen == true || unprovenAnswered || verifyPi && preview != null || liveReport || recentReport || verifiedUsage != null || recentClarify || codexQuestionCleared || verifyOpenCode &&
                (blocked || preview != null || openCodeBackgroundDrawer(agent, recent) || openCodeBackgroundDrawer(agent, liveCard)))
            val codexSteeringProven = liveViewport != null && missingCodexCard && liveCandidate == null &&
                com.billmongan.herdrdroid.herdr.codexSteeringReady(liveViewport)
            val shownText = if (useLive || codexSteeringProven) checkNotNull(liveCard) else recent
            // 2.2.7: a Claude dialog taller than the screen is completed from the pane's history when that proves it (herdr/TallDialog.kt).
            val text = if (useLive && agent.equals("claude", true) && cardOwner != null)
                com.billmongan.herdrdroid.herdr.completeTallDialog(shownText, blocked, version) { n ->
                    quietly { h -> if (h === cardOwner.client && canUpgrade()) h.readCardHistory(paneId, n, cardOwner.connection) else null }
                }
            else shownText
            if (text != shownText && !canUpgrade()) return
            if (!isOpen()) return
            // 2.2.0: the first reads after a switch are held until they settle (two identical reads, or
            // a read that must show at once); the last good view stays up meanwhile. See [SettleGate].
            val urgent = com.billmongan.herdrdroid.herdr.urgentRead(
                needsYou = blocked,
                working = paneStatus == Status.WORKING || paneNow?.backgroundBusy == true,
                // 2.2.2: and a card read with the pane's shown status (Hermes's gateway wait), as the screen reads it.
                question = preview != null || livePrompt != null || cardParse(paneId, text) != null,
                error = paneNow != null && com.billmongan.herdrdroid.herdr.agentErrorNotice(text, agent, paneNow.agentName) != null,
                // A draft typed at the computer has its own card (2.1.0), never held either.
                dialog = useLive || liveUsage != null || verifiedUsage != null || com.billmongan.herdrdroid.herdr.showsQuestionDialog(text) ||
                    !com.billmongan.herdrdroid.herdr.inputLineOf(text, agent).isNullOrEmpty() ||
                    com.billmongan.herdrdroid.herdr.composerDraftOf(text, agent) != null,
            )
            val verdict = settleGate.offer(settleKey(serverId, paneId, visit), text, urgent)
            if (verdict.why == com.billmongan.herdrdroid.herdr.SettleGate.Why.CAPPED)
                com.billmongan.herdrdroid.diag.DiagLog.log("read", "$paneId: the screen kept changing over ${verdict.reads} reads; showing the newest")
            else if (verdict.why != null && verdict.reads > 1)
                com.billmongan.herdrdroid.diag.DiagLog.log("read", "$paneId: view settled after ${verdict.reads} reads (${verdict.why.name.lowercase()})")
            paneViewSettled = verdict.show
            // Model labels can update while the conversation text is still settling.
            cardOwner?.takeIf { it.isCurrent(it.client) && it.pane.scrollOffset == 0 }?.let {
                recordModelFooter(it.server, it.pane, text, modelSequence)
            }
            // 2.1.5: a cross-server frame the agent rendered in this chat becomes a card; nothing is sent.
            // A card, so never held by the settle gate (2.2.0).
            noteCrossServerFrames(cardOwner, recent)
            notePaneReadFrames(cardOwner, recent)
            if (!verdict.show) {
                codexSteeringTarget = null
                return
            }
            noteSettledScreen(serverId, paneId, text, visit)
            lastAppliedPaneRead = attempt
            paneReadsApplied++
            openingPanePreview = null
            paneReadProblem = null
            openUsage = liveUsage
            noteSeenBtw(serverId, paneId, liveUsage)
            // 2.2.1: a panel already open when you open the pane is closed; one you open from here stays.
            if (panelVisitsRead.size > 256) { panelVisitsRead.clear(); panelOpenCloses.clear() }
            if (panelVisitsRead.add("$serverId/$paneId/$visit")) liveUsage?.let { closePanelOnOpen(it) }
            noteInputLine(paneId, text)
            paneText = text
            paneTextPane = paneId
            paneTextTarget = cardOwner?.takeIf { it.isCurrent(it.client) && (!requiresLive || useLive) && (!verifyPi || livePrompt != null) }
            // Keep the native placeholder proof separate from the plain screen used by every renderer.
            codexSteeringTarget = paneTextTarget?.takeIf { codexSteeringProven }
            codexActivityTarget = paneTextTarget?.takeIf {
                codexQuestionCleared && useLive && com.billmongan.herdrdroid.herdr.hasCodexInputActivity(text)
            }
            piLiveQuestion = livePrompt?.takeIf { verifyPi && paneTextTarget != null }
            openCodeComposerTarget = paneTextTarget?.takeIf { useLive && verifyOpenCode && openCodeEmptyComposer(text) }
            hermesUsageRead = verifiedUsage

            // What the question card sees, logged when it changes (sizes only, no question text).
            val status = panes.firstOrNull { it.id == paneId }?.status
            val p = when {
                useLive -> livePrompt
                requiresLive -> null // Recent output does not establish that this card is current.
                (status == Status.NEEDS_YOU) == blocked && pane(paneId)?.agent == agent && versionOf(paneId) == version -> preview
                else -> detectPrompt(text, status == Status.NEEDS_YOU, pane(paneId)?.agent, versionOf(paneId))
            }
            // 2.2.2: a question card shows once the reads confirm the card the screen shows (the same
            // parse, from the same status). The history records that card once it shows; while it is
            // still being read, nothing is recorded and no miss is counted against an open question.
            val (cardParsed, cardShows) = offerQuestionRead(serverId, paneId, text)
            if (cardShows == com.billmongan.herdrdroid.herdr.CardShows.CARD || cardShows == com.billmongan.herdrdroid.herdr.CardShows.NONE)
                current?.id?.let { sid -> observeQuestion(sid, paneId, cardParsed, status, pane(paneId)?.agentName, text) }
            else com.billmongan.herdrdroid.diag.DiagLog.log("prompt", "$paneId: question card not confirmed yet")
            val dialog = com.billmongan.herdrdroid.herdr.showsQuestionDialog(text)
            val sig = if (p == null && !dialog) "none" else
                "dialog ${if (dialog) "open" else "not seen"}, herdr ${status?.name?.lowercase()}, " +
                    (p?.let { "${it.choices.size} choice(s) [${it.choices.joinToString(",") { c -> c.keys }}], question ${it.question.length} chars${if (it.question.endsWith("?")) " ending in ?" else ""}, highlighted ${it.selected ?: "none"}" } ?: "no card")
            val sigKey = "$serverId|$paneId"
            if (lastPromptSig[sigKey] != sig) {
                if (p != null) com.billmongan.herdrdroid.diag.DiagLog.log("prompt", "$paneId: card read in ${android.os.SystemClock.elapsedRealtime() - started} ms")
                lastPromptSig[sigKey] = sig
                if (sig != "none" || p == null && lastPromptSig.containsKey(sigKey)) com.billmongan.herdrdroid.diag.DiagLog.log("prompt", "$paneId: $sig")
            }
            readPaneDraft(paneId, text, p, cardOwner) {
                attempt == lastAppliedPaneRead && current?.id == serverId && herdr === client &&
                    paneVisit == visit && (screen as? Screen.Agent)?.paneId == paneId
            }
        }
    }

    /**
     * The draft card (2.1.0): only when the screen just read shows text in the agent's input box is
     * the live viewport read with its styles, so a placeholder is never shown as a draft. Nothing is
     * read or shown otherwise, and nothing is ever typed from here.
     */
    private suspend fun readPaneDraft(paneId: String, text: String, question: Prompt?, owner: CardTarget?, stillShown: () -> Boolean) {
        val now = pane(paneId)
        val agent = now?.agent
        val eligible = owner != null && foreground && !locked && owner.client.backend == Backend.TUI &&
            owner.isCurrent(owner.client) && question == null && agent != null && now.status != Status.NEEDS_YOU &&
            // 2.1.5: a usage panel drawn where the box is (Kiro's /usage) is not text typed at the computer.
            !usageDialogShown(agent, text) &&
            (now.scrollOffset ?: 0) == 0 && (!com.billmongan.herdrdroid.herdr.inputLineOf(text, agent).isNullOrEmpty() ||
                com.billmongan.herdrdroid.herdr.paneDraftOf(text, agent) != null)
        if (!eligible) {
            if (stillShown()) {
                val previous = paneDraft
                val waitingQuestion = question != null || now?.status == Status.NEEDS_YOU
                // A question can cover a draft already verified in this same visit. Keep its
                // existence for the disabled chat card only until we can read the composer again.
                val heldByQuestion = waitingQuestion && owner != null && previous != null &&
                    owner.isCurrent(owner.client) && owner.client === previous.target.client &&
                    owner.connection === previous.target.connection && owner.visit == previous.target.visit &&
                    owner.pane.sameCardOccupant(previous.target.pane)
                if (!heldByQuestion) paneDraft = null
                draftReadFor = null
            }
            return
        }
        val o = checkNotNull(owner)
        val plain = com.billmongan.herdrdroid.herdr.paneDraftOf(text, agent)?.text
            ?: com.billmongan.herdrdroid.herdr.inputLineOf(text, agent)
        val key = listOf(paneId, plain, com.billmongan.herdrdroid.herdr.Occupant.of(o.pane), o.visit, o.connection, now.status)
        if (key == draftReadFor) {
            // Same plain line, occupant and status: the last verdict stands (a draft keeps its card).
            if (paneDraftShown?.target?.pane?.id != paneId && paneDraftShown != null) paneDraft = null
            return
        }
        val live = quietly { h -> if (h === o.client) h.readStyledViewport(paneId, o.connection, timeoutSeconds = 2) else null }
        if (!stillShown()) return
        val draft = live?.takeIf { o.isCurrent(o.client) && detectPrompt(forPhone(it), false, agent, versionOf(paneId)) == null }
            ?.let { com.billmongan.herdrdroid.herdr.paneDraftOf(it, agent) }
        // A failed read is not a verdict: read again next time.
        draftReadFor = key.takeIf { live != null }
        paneDraft = draft?.let {
            val shown = PaneDraftShown(o, it.text, it.bounded && com.billmongan.herdrdroid.herdr.hasStableOccupant(o.pane),
                // A suggestion list may be in the box's buffer: no Clear either.
                // 2.2.8: per agent's clear key (Backspace, or Ctrl+U on a one-row draft).
                o.client.backend == Backend.TUI && com.billmongan.herdrdroid.herdr.clearableDraft(agent, it),
                com.billmongan.herdrdroid.herdr.draftSendBlock(it, now.status, now.agentName))
            paneDraft?.takeIf { d -> d == shown } ?: shown
        }
    }

    private val paneDraftAction by lazy { com.billmongan.herdrdroid.herdr.PaneDraftAction(paneDispatch, writeGate) }

    /** Send draft: Enter once, after the same checks as any write (2.1.0). Only on a tap. */
    internal fun sendPaneDraft(shown: PaneDraftShown) = paneDraftAct(shown, clear = false)

    /** Clear draft: Backspace only, after the user confirmed (2.1.0). Only on a tap. */
    internal fun clearPaneDraft(shown: PaneDraftShown) = paneDraftAct(shown, clear = true)

    private fun paneDraftAct(shown: PaneDraftShown, clear: Boolean) {
        // The card may have been redrawn since the tap; the action itself re-reads the pane and
        // acts only on the text that was shown.
        val target = shown.target
        val lane = target.server to target.pane.id
        if (lane in draftActions) {
            flash("Wait: the last Send draft or Clear draft for this pane is still running.")
            return
        }
        if (!shown.actionable || (clear && !shown.clearable)) return
        val h = target.client
        val agentName = target.pane.agentName
        draftActions = draftActions + (lane to if (clear) "clear" else "send")
        viewModelScope.launch {
            fun allowed() = !locked && foreground && link == Link.CONNECTED && herdr === h && target.isCurrent(h)
            // Through [quietly], so a dropped link reconnects; a failed read is null, a failed write throws.
            val port = object : com.billmongan.herdrdroid.herdr.PaneDraftAction.Port {
                override suspend fun pane(id: String) = quietly { if (it === h) it.cardPane(id, target.connection) else null }
                override suspend fun viewport(id: String) =
                    quietly { if (it === h) it.readStyledViewport(id, target.connection, timeoutSeconds = 3) else null }
                override suspend fun checkedKeys(id: String, keys: List<String>, viewport: String, permit: WritePermit): Boolean {
                    if (!allowed()) return false
                    val bound = quietly { client ->
                        if (client === h) client.boundOccupant(id, target.connection) else null
                    }?.takeIf { it.first.sameCardOccupant(target.pane) && it.first.scrollOffset == 0 } ?: return false
                    if (!allowed()) return false
                    return h.sendCheckedDraftKeys(id, keys, viewport, bound.second, target.connection, permit)
                }
                override fun linkLost(error: LinkDown) {
                    if (herdr === h) this@AppViewModel.linkLost(error)
                }
                override suspend fun pause(ms: Long) = delay(ms)
            }
            val outcome = try {
                if (clear) paneDraftAction.clear(target.server, target.pane, shown.text, ::allowed, port)
                else paneDraftAction.send(target.server, target.pane, shown.text, ::allowed, port)
            } finally {
                draftActions = draftActions - lane
                draftReadFor = null
            }
            com.billmongan.herdrdroid.diag.DiagLog.log("draft", "${target.pane.id}: ${if (clear) "clear" else "send"} ${outcome.name.lowercase()} (${shown.text.length} chars)")
            val said = com.billmongan.herdrdroid.herdr.draftOutcomeText(outcome, agentName, clear)
            when (outcome) {
                com.billmongan.herdrdroid.herdr.PaneDraftAction.Outcome.UNCERTAIN,
                com.billmongan.herdrdroid.herdr.PaneDraftAction.Outcome.PARTIAL -> error = said
                else -> flash(said)
            }
            if (target.isCurrent(h)) readPane(target.pane.id)
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
        openCodeComposerTarget = null
        codexSteeringTarget = null
        piLiveQuestion = null
        paneReadProblem = null
        openUsage = null
        paneDraft = null
        draftReadFor = null
        current?.id?.let { questions(it, paneId) }
        // 2.2.2: reopening the pane whose log already answered in this app run (its alert, its drawer
        // row) keeps that answer: the chat, or the screen view of a pane with no log, shows at once.
        val logKnown = transcriptPane == paneId && transcriptState != TranscriptMode.UNKNOWN
        resetTranscript(paneId)
        screen = Screen.Agent(paneId)
        if (logKnown) transcriptAnsweredVisit = paneVisit
        // 2.2.0: the new visit's view waits for settled reads; the pane's last good view shows meanwhile.
        settleGate.reset(settleKey(current?.id, paneId, paneVisit))
        paneViewSettled = false
        transcriptGaveUp = false
        transcriptWaitReads = 0
        transcriptSlowReads = 0
        takePanePreview(paneId)
    }

    // ---- Confirmed question cards (2.2.2) --------------------------------------------------------

    private val questionConfirm = com.billmongan.herdrdroid.herdr.QuestionConfirm()

    /** Each pane's last read verdict and the card it was for, "server|pane" to decision; memory only. */
    private val cardDecisions = androidx.compose.runtime.mutableStateMapOf<String, com.billmongan.herdrdroid.herdr.CardDecision>()

    /** The status the open pane's card is read with: what the pane shows (2.2.2: the same for the card and its confirmation). */
    /** Only a successful screen read for the current Codex occupant may explain a stale wait. */
    internal fun codexActivityViewport(paneId: String): String? =
        codexActivityTarget?.takeIf { target ->
            paneTextTarget === target && target.pane.id == paneId && paneTextPane == paneId &&
                paneReadProblem == null && pane(paneId)?.scrollOffset == 0 && target.isCurrent(target.client)
        }?.let { paneText }

    private fun cardStatus(pane: Pane): Status {
        val shown = shownStatus(pane)
        return if (shown == Status.NEEDS_YOU && openCodeComposerIsCurrent(pane)) Status.IDLE else shown
    }

    /**
     * The card [text] shows for [paneId], before confirmation (2.2.2): pi's verified live question, or
     * the screen parsed with the pane's shown status. The screen and the read path both use this, so
     * the card confirmed is the card drawn.
     */
    internal fun cardParse(paneId: String, text: String): Prompt? {
        val pane = pane(paneId) ?: return null
        if (requiresLivePiQuestion(pane)) return currentPiQuestion(pane)?.forCard()
        return detectPrompt(text, cardStatus(pane) == Status.NEEDS_YOU, pane.agent, versionOf(paneId))?.forCard()
    }

    /**
     * The card to show for [parsed], read from the open pane's screen with [cardParse] (2.2.2): [parsed]
     * once the reads confirmed it, else a neutral card with no options or keys (or, for a question
     * whose dialog never proved itself, the card that says it couldn't be read).
     */
    fun questionCardFor(paneId: String, parsed: Prompt?, agent: String, screenText: String? = null): Prompt? {
        val key = "${current?.id}|$paneId"
        // A pane no read has reached yet (a screen set without one): as before 2.2.2.
        val shown = if (!questionConfirm.seen(key)) parsed
            else com.billmongan.herdrdroid.herdr.shownCard(parsed, cardDecisions[key], agent)
        if ((shown == null || shown.unreadQuestion) && pane(paneId)?.agent.equals("claude", true) && screenText != null)
            return com.billmongan.herdrdroid.herdr.claudeReviewPreview(screenText, parsed) ?: shown
        return shown
    }

    /** One read of [paneId]'s screen offered to the question confirmation: the card [cardParse] gives for it. */
    private fun offerQuestionRead(serverId: String?, paneId: String, text: String): Pair<Prompt?, com.billmongan.herdrdroid.herdr.CardShows> {
        val key = "$serverId|$paneId"
        val pane = pane(paneId)
        val parsed = cardParse(paneId, text)
        val read = parsed?.let {
            com.billmongan.herdrdroid.herdr.questionRead(text, it, pane?.agent, versionOf(paneId), pane?.let(::cardStatus) == Status.NEEDS_YOU,
                framed = pane != null && requiresLivePiQuestion(pane))
        }
        val verdict = questionConfirm.decide(key, read)
        if (verdict.gaveUp) com.billmongan.herdrdroid.diag.DiagLog.log("prompt", "$paneId: the dialog kept changing; showing the newest complete read")
        val d = com.billmongan.herdrdroid.herdr.CardDecision(parsed, verdict)
        if (cardDecisions[key] != d) cardDecisions[key] = d
        return parsed to verdict.shows
    }

    /**
     * Whether [target]'s open card is still being read (2.2.2): Chat shows "Reading it…" (or that it
     * couldn't read the question) for the card its screen parses to. Voice sends nothing then.
     */
    internal fun voiceCardStillReading(target: CardTarget): Boolean {
        val id = target.pane.id
        if ((screen as? Screen.Agent)?.paneId != id) return false
        // Fails closed: nothing read yet (or only the opening preview), or a card that isn't confirmed
        // for exactly what the screen shows, whatever the pane's target says.
        val text = openCardText(id) ?: return true
        val parsed = cardParse(id, text)?.takeIf { com.billmongan.herdrdroid.herdr.needsConfirmation(it) } ?: return false
        val key = "${current?.id}|$id"
        val d = cardDecisions[key]
        return !(questionConfirm.seen(key) && d != null && d.parsed == parsed && d.verdict.shows == com.billmongan.herdrdroid.herdr.CardShows.CARD)
    }

    /** The text the open pane's card is drawn from (2.2.2): its read screen, or the opening preview before one. */
    private fun openCardText(paneId: String): String? =
        paneText.takeIf { paneTextPane == paneId && it.isNotEmpty() } ?: prefetchedPaneText(paneId)

    // ---- Settled view on a pane switch (2.2.0) ------------------------------------------------

    private val settleGate = com.billmongan.herdrdroid.herdr.SettleGate()
    private fun settleKey(serverId: String?, paneId: String, visit: Long) = "$serverId|$paneId|$visit"

    /** False while the open pane's first reads after a switch are held until they settle. */
    var paneViewSettled by mutableStateOf(true)
        private set

    /** The visit whose chat log has answered (loaded, or said there is none); -1 for none yet. */
    private var transcriptAnsweredVisit by mutableStateOf(-1L)

    /** The chat log reader failed to run [com.billmongan.herdrdroid.herdr.TRANSCRIPT_READER_FAILURES] times this visit (2.2.2). */
    private var transcriptGaveUp by mutableStateOf(false)
    private var transcriptWaitReads = 0
    /** A chat log read is running (2.2.2), and the settled screen reads taken while it ran this visit. */
    private var transcriptReadInFlight = false
    private var transcriptSlowReads = 0

    /** Each pane's last settled screen, "server|pane" to text; memory only, a few panes. */
    private val settledScreens = LinkedHashMap<String, String>()

    private fun noteSettledScreen(serverId: String?, paneId: String, text: String, visit: Long) {
        val key = "$serverId|$paneId"
        settledScreens.remove(key)
        settledScreens[key] = text
        if (settledScreens.size > 12) settledScreens.remove(settledScreens.keys.first())
        // 2.2.2: a log read that hangs (it can wait on its 60 s timeout) gives way to the screen view
        // after a bounded number of settled screen reads taken while it runs.
        if (hasTranscriptReader(paneId) && transcriptReadInFlight && transcriptAnsweredVisit != visit && !transcriptGaveUp &&
            ++transcriptSlowReads >= com.billmongan.herdrdroid.herdr.TRANSCRIPT_SLOW_READS) {
            transcriptGaveUp = true
            com.billmongan.herdrdroid.diag.DiagLog.log("chat", "$paneId: the log reader is still running after $transcriptSlowReads screen reads; showing the screen view")
        }
    }

    /**
     * The pane's last settled screen, display only, while this visit's first reads are held and
     * nothing has been read yet (2.2.0). Never a source of a card, a target or question history.
     */
    internal fun heldScreenText(paneId: String): String? = settledScreens["${current?.id}|$paneId"]?.takeIf {
        !paneViewSettled && (screen as? Screen.Agent)?.paneId == paneId && paneTextPane == paneId && paneText.isEmpty() && paneReadProblem == null
    }

    /** True while the open pane shows its last good view and its fresh one is still settling (2.2.0). */
    fun paneUpdating(paneId: String): Boolean = (screen as? Screen.Agent)?.paneId == paneId && paneReadProblem == null &&
        (!paneViewSettled || (hasTranscriptReader(paneId) && transcriptState == TranscriptMode.ON && transcriptAnsweredVisit != paneVisit))

    /**
     * Whether the screen view may show for [paneId] (2.2.0): not while an agent with a chat log is
     * still waiting for that log's first answer this visit, which showed the terminal-parsed view
     * for a moment before the chat replaced it.
     */
    fun screenViewReady(paneId: String): Boolean = com.billmongan.herdrdroid.herdr.screenViewAllowed(
        hasTranscriptReader(paneId), transcriptAnsweredVisit == paneVisit, transcriptGaveUp)

    // ---- Structured conversations ------------------------------------------------------------

    private val readers = setOf("claude", "codex", "pi", "omp", "grok", "opencode", "hermes", "copilot", "vibe", "kiro", "muse")

    fun hasTranscriptReader(paneId: String) = com.billmongan.herdrdroid.herdr.ruleAgent(pane(paneId)?.agent) in readers

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
            transcriptLogTimes = c.logTimes
            // A chat kept before 1.4.3 has no item times: read it again from the start, so every item
            // gets its log time before questions are placed among them (1.4.3, from review).
            if (c.items.isNotEmpty() && c.times.isEmpty()) transcriptCursor = ""
            transcriptSeen = c.seen
            transcriptShown = c.shown
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
        transcriptLogTimes = emptyMap()
        transcriptSeen = emptyMap()
        transcriptShown = emptyMap()
        transcriptMore = false
        transcriptTitle = null
        transcriptStart = -1L
        transcriptFile = null
        transcriptState = if (hasTranscriptReader(paneId)) TranscriptMode.UNKNOWN else TranscriptMode.OFF
    }

    /**
     * Grok's times on its screen (2.1.4): the open chat's messages that Grok shows a time for, and that
     * have no time of their own yet, take it as their label.
     */
    fun noteScreenClocks(paneId: String, screen: String) {
        if (transcriptPane != paneId || transcriptItems.isEmpty() || com.billmongan.herdrdroid.herdr.canonicalAgent(pane(paneId)?.agent.orEmpty()) != "grok") return
        val found = com.billmongan.herdrdroid.herdr.GrokClock.match(transcriptItems, screen, seenNow(), hostTzMinutes())
            .filterKeys { it !in transcriptLogTimes }
        if (found.isEmpty()) return
        transcriptLogTimes = transcriptLogTimes + found
        current?.id?.let { sid ->
            val c = openChatNow(paneId)
            transcriptCache[ck(paneId)] = c
            if (transcriptCursor.isNotEmpty()) persistChat(sid, paneId, c)
        }
    }

    /** When the app first saw each message on a pane's screen, "server|pane" to key to time (2.1.4). */
    private val screenSeen = androidx.compose.runtime.mutableStateMapOf<String, Map<String, Long>>()
    private fun screenPane(paneId: String) = "$paneId#screen"

    /** The first-seen times of the messages on [paneId]'s screen view (2.1.4); read only, for drawing. */
    fun screenTimesFor(paneId: String): Map<String, Long> = current?.id?.let { screenSeen["$it|$paneId"] }.orEmpty()

    /**
     * Gives the messages on [paneId]'s screen that have no first-seen time one (2.1.4), and keeps them
     * on the phone. The kept times are loaded from the phone the first time.
     */
    fun noteScreenSeen(paneId: String, msgs: List<com.billmongan.herdrdroid.herdr.ScreenTimes.Msg>) {
        val sid = current?.id ?: return
        if (msgs.isEmpty()) return
        val held = screenSeen["$sid|$paneId"]
        val before = held ?: runCatching { chatStore?.load(sid, screenPane(paneId))?.shown }.getOrNull().orEmpty()
        val next = com.billmongan.herdrdroid.herdr.ScreenTimes.stamp(before, msgs, System.currentTimeMillis())
        if (held == null || next !== before) screenSeen["$sid|$paneId"] = next
        if (next === before) return
        persistChat(sid, screenPane(paneId), CachedChat(emptyList(), "", false, null, agent = pane(paneId)?.agent?.lowercase(), shown = next))
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
        transcriptStart, transcriptFile, pane(paneId)?.agent?.lowercase(), transcriptTimes, transcriptSeen, transcriptLogTimes, transcriptShown)

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
            // A pane's screen times (2.1.4) are kept beside its chat, and go with it.
            viewModelScope.launch(Dispatchers.IO) { chatStore?.keepOnly(sid, ids + ids.map(::screenPane)) }
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
        transcriptReadInFlight = true
        val r = try { quietly(allowBackground) { it.transcript(p, cursor, transcriptScript) } } finally { transcriptReadInFlight = false }
        if (r == null) {
            com.billmongan.herdrdroid.diag.DiagLog.log("chat", "$paneId: the log reader didn't run (${p.agent}, link $link)")
            // 2.2.2: a reader that keeps failing to run this visit shows the screen view instead (counted
            // in the reader's own answers, not in screen reads, which came in faster than a slow log).
            if (paneVisit == visit && (screen as? Screen.Agent)?.paneId == paneId && transcriptAnsweredVisit != visit && !transcriptGaveUp &&
                ++transcriptWaitReads >= com.billmongan.herdrdroid.herdr.TRANSCRIPT_READER_FAILURES) {
                transcriptGaveUp = true
                com.billmongan.herdrdroid.diag.DiagLog.log("chat", "$paneId: the log reader failed $transcriptWaitReads times; showing the screen view")
            }
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
        // 2.2.0: the log answered for this visit (with its chat or with none), so the view may settle.
        if ((screen as? Screen.Agent)?.paneId == paneId) transcriptAnsweredVisit = paneVisit
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
            transcriptLogTimes = emptyMap(); transcriptShown = emptyMap()
        } else if (r.reset) {
            // The same session read again: a key whose item changed (a rewritten log) loses its old times.
            val fresh = Transcript.merge(emptyList(), r.items, true)
            transcriptTimes = Transcript.keptAcrossReset(transcriptTimes, transcriptItems, fresh)
            transcriptSeen = Transcript.keptAcrossReset(transcriptSeen, transcriptItems, fresh)
            transcriptLogTimes = Transcript.keptAcrossReset(transcriptLogTimes, transcriptItems, fresh)
            transcriptShown = Transcript.keptAcrossReset(transcriptShown, transcriptItems, fresh)
        }
        transcriptCursor = r.cursor
        noteHostClock(r)
        if (r.reset || r.items.isNotEmpty()) {
            transcriptItems = Transcript.merge(transcriptItems, r.items, r.reset)
            // Log times where the log has them; otherwise, for a live read's new items, when the app
            // first saw them, kept across reloads. A bulk read makes none up (1.4.3).
            val recorded = Transcript.timesOf(r.items)
            // Labels also take the times the reader found in the agent's other files (2.1.4); questions don't.
            transcriptLogTimes = (transcriptLogTimes + Transcript.labelTimesOf(r.items)).filterKeys(Transcript.keysOfItems(transcriptItems)::contains)
            val logged = transcriptTimes + recorded
            transcriptTimes = if (r.reset) logged else Transcript.stampSeen(logged, transcriptItems, seenNow(), Transcript.keysOf(r.items))
            transcriptSeen = Transcript.markSeen(transcriptSeen, transcriptItems, Transcript.keysOf(r.items), transcriptTimes, seenNow(), bulk = r.reset)
            transcriptShown = Transcript.stampShown(transcriptShown, transcriptItems, transcriptLogTimes, transcriptTimes, seenNow())
        }
        if (r.reset) {
            transcriptMore = r.more
            transcriptStart = runCatching { org.json.JSONObject(r.cursor).optLong("start", -1L) }.getOrDefault(-1L)
            transcriptFile = runCatching { org.json.JSONObject(r.cursor).optString("file").ifBlank { null } }.getOrNull()
        }
        r.title?.let { transcriptTitle = it }
        // 2.2.0: the session the terminal is really in, as the reader found it (the chat already follows it).
        val liveKey = ck(paneId)
        if (r.live != null && liveSessions[liveKey] != r.live) liveSessions = liveSessions + (liveKey to r.live)
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
                lastSentMark = null
            }
        }
        // Each message sent from the phone stays on screen until the agent's log has it.
        val mine = pendingSends.filter { it.isFor(current?.id, paneId) }
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
                val recorded = Transcript.timesOf(r.items)
                transcriptLogTimes = (Transcript.labelTimesOf(r.items) + transcriptLogTimes).filterKeys(Transcript.keysOfItems(transcriptItems)::contains)
                transcriptTimes = recorded + transcriptTimes
                transcriptSeen = Transcript.markSeen(transcriptSeen, transcriptItems, Transcript.keysOf(r.items), transcriptTimes, seenNow(), bulk = true)
                transcriptShown = Transcript.stampShown(transcriptShown, transcriptItems, transcriptLogTimes, transcriptTimes, seenNow())
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
                           val awaitingLink: Boolean = false,
                           internal val hermesTarget: Pane? = null,
                           /** The server the pane is on (2.1.1): pane ids repeat across servers. */
                           val serverId: String? = null,
                           /** 2.2.8: a message queued behind a question, for its pane's occupant only (see [BoundSend]). */
                           internal val bound: BoundSend? = null) {
        val removable: Boolean get() = waiting || awaitingLink || undelivered

        /** This send is for [paneId] on [server], never a pane with the same id on another server. */
        fun isFor(server: String?, paneId: String): Boolean = this.paneId == paneId && serverId == server
    }

    fun removableSendsFor(paneId: String): List<PendingSend> =
        pendingSends.filter { it.isFor(current?.id, paneId) && it.removable }

    val canForceSendPending: Boolean get() = link == Link.CONNECTED && herdr != null

    fun forceSendPending(id: Long) {
        val p = pendingSends.firstOrNull { it.id == id && it.removable } ?: return
        if (!canForceSendPending) return
        // A send for another server's pane is never resent here, where the same pane id names another pane (2.1.1).
        if (p.serverId != current?.id) return
        // A pane that isn't listed keeps the message, with its Resend (1.4.4, from Grok's review).
        val target = pane(p.paneId) ?: run {
            com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${p.paneId}: Resend while the pane isn't listed; kept")
            flash("Not sent: that pane isn't open on the computer just now.")
            return
        }
        if (target.status == Status.WORKING && !takesInputDuringBackgroundWork(target.agent)) return
        if (p.bound != null && !p.bound.occupant.sameCardOccupant(target)) { dismissPending(id); occupantMovedDuringSend(p.bound); return }
        if (p.hermesTarget?.sameCardOccupant(target) == false || hasPendingQuestion(target) || waitingForHermesComposer(target.id)) return
        val then = heldThen[id] ?: {}
        dismissPending(id)
        // A deliberate force send bypasses the input-line guard.
        sendMessage(target, p.text, force = true, delivered = then, bound = p.bound)
    }

    /** For each held send, what to do once it is delivered (see [forceSendPending]). */
    private val heldThen = java.util.concurrent.ConcurrentHashMap<Long, () -> Unit>()

    fun dismissPending(id: Long) {
        val pending = pendingSends.firstOrNull { it.id == id && it.removable } ?: return
        queue.removeAll { it.pendingId == id }
        queuedCount = queue.size
        heldThen.remove(id)
        pendingSends = pendingSends - pending
        if (lastSentMark == SentMark(pending.serverId, pending.paneId, pending.text) &&
            pendingSends.none { it.isFor(pending.serverId, pending.paneId) && it.text == pending.text }
        ) lastSentMark = null
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
    fun pendingFor(paneId: String): List<Pair<String, Long>> {
        val now = System.currentTimeMillis()
        val hasLog = transcriptPane == paneId && transcriptState == TranscriptMode.ON
        return pendingSends.filter { it.isFor(current?.id, paneId) && !it.removable && now - it.at < (if (hasLog) 15 * 60_000L else 20_000L) }
            .map { it.text to it.at }
    }

    /**
     * The Stop button: the keys each agent's own program uses to interrupt a turn
     * ([com.billmongan.herdrdroid.herdr.stopKeys]), sent once, at once.
     *
     * 2.2.7 (Bill: "the stop button in chat view doesn't work"): Stop no longer goes the way of a
     * message or a key from the key row (the pane's send queue). It waited there behind any other
     * input to the pane, and while the link was down it was kept and sent after the reconnect,
     * when it could interrupt a later turn. Now it goes on the connection that is up when you tap
     * it, to the same occupant (agent, tab, terminal, session; a scrolled-back pane is fine), or
     * not at all, with a note saying why. It is never held for the screen: no card check, no
     * input-line check.
     */
    fun stopPane(pane: Pane) {
        val h = herdr
        val connection = h?.connectionIdentity
        if (h == null || connection == null || link != Link.CONNECTED || locked) {
            flash("Not stopped: ${pane.agentName} isn't connected just now.")
            return
        }
        val keys = com.billmongan.herdrdroid.herdr.stopKeys(pane.agent)
        val server = current?.id
        // Codex's review: checked again right before the keys, after the occupant read suspended.
        fun stillHere() = !locked && herdr === h && current?.id == server && link == Link.CONNECTED && h.connectionIdentity === connection
        viewModelScope.launch {
            try {
                val live = h.relayPane(pane.id, connection)
                // The occupant, not the folder: a working agent's foreground folder moves as it runs commands.
                if (live == null || live.id != pane.id || live.agent != pane.agent || live.tabId != pane.tabId ||
                    live.terminalId != pane.terminalId || live.sessionId != pane.sessionId) {
                    flash("Not stopped: that pane holds something else now.")
                    return@launch
                }
                if (!stillHere()) {
                    flash("Not stopped: the app locked or the server changed before it went.")
                    return@launch
                }
                h.sendKeys(pane.id, *keys.toTypedArray(), expectedConnection = connection)
                com.billmongan.herdrdroid.diag.DiagLog.log("send", "${pane.id}: stop (${keys.joinToString(" ")})")
                if (herdr === h) readPane(pane.id)
            } catch (c: CancellationException) {
                throw c
            } catch (e: com.billmongan.herdrdroid.ssh.ConnectionChanged) {
                flash("Not stopped: the connection changed. Tap Stop again.")
            } catch (e: LinkDown) {
                flash("Stop may not have reached ${pane.agentName}: the link dropped.")
                if (herdr === h) linkLost(e)
            } catch (e: Exception) {
                flash("Couldn't stop ${pane.agentName}: ${friendly(e)}")
            }
        }
    }

    /**
     * Copies a file picked in the message box to ~/.herdrdroid/inbox on the computer, then hands
     * its path to [done] so the message can point the agent at it.
     */
    /** 2.2.7: a file the computer has, waiting to go into [paneId]'s message box on [serverId] (pre-review L8). */
    data class AttachedPath(val serverId: String, val paneId: String, val path: String)
    var attachedPath by mutableStateOf<AttachedPath?>(null)
        private set
    fun attachedPathTaken(a: AttachedPath) { if (attachedPath == a) attachedPath = null }

    /** The chat's attach: the path goes into [paneId]'s message box when the file is there (see [attachedPath]). */
    fun attach(uri: Uri, paneId: String) {
        val serverId = current?.id ?: return
        attachFile(uri, { attach(uri, paneId) }) { path -> attachedPath = AttachedPath(serverId, paneId, path) }
    }

    /** Hermes's chat and a share into the app: [done] gets the path once the file is there. */
    fun attach(uri: Uri, done: (String) -> Unit) {
        attachFile(uri, { attach(uri, done) }, done)
    }

    private fun attachFile(uri: Uri, retry: () -> Unit, done: (String) -> Unit) {
        if (uploading) return
        val app = getApplication<Application>()
        uploading = true
        attachFailure = null
        act(null, "Couldn't attach the file") { h ->
            var name = "attachment"
            try {
                val (n, bytes) = withContext(Dispatchers.IO) {
                    val n = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "attachment"
                    val b = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    n to b
                }
                name = n
                if (bytes.size > 50 * 1024 * 1024) throw IllegalArgumentException("That file is over 50 MB.")
                // 2.2.7: the message points at the file only once the computer has all of it.
                val receipt = h.uploadVerified(name, bytes)
                notice = "Attached $name (${com.billmongan.herdrdroid.herdr.LongText.size(receipt.size)}), " +
                    (if (receipt.sha256.isEmpty()) "its size checked on the computer (it has no checksum tool)." else "checked on the computer.")
                done(receipt.path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attachFailure = AttachFailure(name, com.billmongan.herdrdroid.diag.DiagLog.describe(e), retry)
            } finally {
                uploading = false
            }
        }
    }

    /** 2.2.7: an attachment that didn't arrive whole: shown with Retry; nothing points at it. */
    data class AttachFailure(val name: String, val why: String, internal val retry: () -> Unit)
    var attachFailure by mutableStateOf<AttachFailure?>(null)

    fun retryAttach() {
        val f = attachFailure ?: return
        attachFailure = null
        f.retry()
    }

    // ---- 2.2.7: Send a secret ([com.billmongan.herdrdroid.herdr.SecretDrop]) -------------------
    // A separate path from the attach above (Bill: "I still like the regular file upload"). The
    // secret lives only in the dialog and the one send; never queued for a lost link, never in a
    // draft, a log or the clipboard. Only its path goes into the chosen pane's message box.

    /**
     * The open pane's path to insert at the caret, once ([secretInsertTaken]). 2.2.7 (Codex's
     * review): with the connection and the pane's occupant the save was checked on, so the message
     * box takes it only while both are still the ones on screen ([secretInsertFor]).
     */
    data class SecretInsert(val serverId: String, val paneId: String, val path: String, val id: Long,
        val connection: Any? = null, val pane: Pane? = null)
    var secretInsert by mutableStateOf<SecretInsert?>(null)
        private set
    fun secretInsertTaken(id: Long) { if (secretInsert?.id == id) secretInsert = null }

    /**
     * The path waiting for [paneId]'s message box, taken: only when it is still [id], on the same
     * server, connection and occupant at this moment (the composer asks as it inserts it).
     */
    fun takeSecretInsert(paneId: String, id: Long): String? {
        val (waiting, path) = secretInsertFor(paneId) ?: return null
        if (waiting != id) return null
        secretInsert = null
        return path
    }

    /** The path for [paneId]'s message box (its id to [secretInsertTaken]), only on the same server, connection and occupant. */
    fun secretInsertFor(paneId: String): Pair<Long, String>? {
        val s = secretInsert ?: return null
        if (s.serverId != current?.id || s.paneId != paneId) return null
        if (s.connection != null && herdr?.connectionIdentity !== s.connection) return null
        val now = pane(paneId) ?: return null
        if (s.pane != null && !com.billmongan.herdrdroid.herdr.sameOccupant(s.pane, now)) return null
        return s.id to s.path
    }
    var secretBusy by mutableStateOf(false)
        private set
    /** A secret saved for another pane: the notice offers to open it. */
    data class SecretSavedElsewhere(val serverId: String, val paneId: String, val text: String)
    var secretElsewhere by mutableStateOf<SecretSavedElsewhere?>(null)

    private fun secretPanes(serverId: String): List<Pane>? = when {
        serverId == current?.id -> panes
        else -> fleetStates[serverId]?.takeIf { st -> st.error == null && st.server == servers.firstOrNull { it.id == serverId } }?.panes
    }

    /** 2.2.7 (Codex's review): with the connection current when the dialog listed it. */
    private fun secretTarget(s: Server, p: Pane, connection: Any?) =
        com.billmongan.herdrdroid.herdr.SecretTarget(s.id, p.id, s.label, com.billmongan.herdrdroid.ui.sessionName(p), p, connection)

    /** Every pane a secret can go to: the open server's first, then each other connected server's. */
    fun secretTargets(): List<com.billmongan.herdrdroid.herdr.SecretTarget> {
        val cur = current ?: return emptyList()
        val conn = herdr?.connectionIdentity
        val here = if (link == Link.CONNECTED && conn != null) panes.map { secretTarget(cur, it, conn) } else emptyList()
        val others = servers.filter { it.id != cur.id && fleet?.herdr(it.id)?.connectionIdentity != null }
            .flatMap { s -> val c = fleet?.herdr(s.id)?.connectionIdentity; secretPanes(s.id).orEmpty().map { secretTarget(s, it, c) } }
        return here + others
    }

    internal val secretSender = com.billmongan.herdrdroid.herdr.SecretSender(object : com.billmongan.herdrdroid.herdr.SecretSender.Port {
        override fun present(t: com.billmongan.herdrdroid.herdr.SecretTarget) = secretPanes(t.serverId)?.any { t.matches(it) } == true
        override fun bind(serverId: String): com.billmongan.herdrdroid.herdr.SecretSender.Bound? {
            val h = when {
                locked -> null
                serverId == current?.id -> herdr?.takeIf { link == Link.CONNECTED }
                else -> fleet?.herdr(serverId)?.takeIf { secretPanes(serverId) != null }
            } ?: return null
            val connection = h.connectionIdentity ?: return null
            return com.billmongan.herdrdroid.herdr.SecretSender.Bound(
                h.secretRunOn(connection), { id -> h.relayPane(id, connection) }, h::beginSecretSend, h::endSecretSend, connection,
            )
        }
        override fun isOpen(t: com.billmongan.herdrdroid.herdr.SecretTarget) =
            t.serverId == current?.id && (screen as? Screen.Agent)?.paneId == t.paneId
        override fun insertLive(t: com.billmongan.herdrdroid.herdr.SecretTarget, path: String) {
            secretInsert = SecretInsert(t.serverId, t.paneId, path, System.nanoTime(), t.connection, t.pane)
        }
        override fun appendDraft(t: com.billmongan.herdrdroid.herdr.SecretTarget, path: String) {
            val key = draftKey(t.serverId, t.paneId)
            composerDrafts[key] = com.billmongan.herdrdroid.herdr.SecretDrop.appendTo(composerDrafts[key].orEmpty(), path)
            saveDrafts()
        }
    })

    private fun finishSecret(o: com.billmongan.herdrdroid.herdr.SecretOutcome) {
        val text = com.billmongan.herdrdroid.herdr.secretNotice(o)
        if (o is com.billmongan.herdrdroid.herdr.SecretOutcome.Saved && o.draft) secretElsewhere = SecretSavedElsewhere(o.target.serverId, o.target.paneId, text)
        else notice = text
    }

    /**
     * Typed text: [bytes] are zeroed once sent or refused. False, and nothing taken, while another
     * secret is still being saved (omp's audit: it was dropped silently); the dialog stays open and says so.
     */
    fun sendSecret(target: com.billmongan.herdrdroid.herdr.SecretTarget, label: String?, bytes: ByteArray): Boolean {
        if (secretBusy) { bytes.fill(0); return false }
        secretBusy = true
        viewModelScope.launch {
            try { finishSecret(secretSender.send(target, label, true, bytes)) } finally { secretBusy = false }
        }
        return true
    }

    /** A picked file: read here (up to the limit), sent, and zeroed; named by [label] or its own name. */
    fun sendSecretFile(target: com.billmongan.herdrdroid.herdr.SecretTarget, label: String?, uri: Uri): Boolean {
        if (secretBusy) return false
        secretBusy = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val read = withContext(Dispatchers.IO) {
                    runCatching {
                        val n = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                        val max = com.billmongan.herdrdroid.herdr.SecretDrop.MAX_BYTES
                        val b = app.contentResolver.openInputStream(uri)?.use { input ->
                            com.billmongan.herdrdroid.herdr.SecretDrop.readBounded(input, max)
                        } ?: ByteArray(0)
                        n to b
                    }
                }
                val (name, bytes) = read.getOrElse { finishSecret(com.billmongan.herdrdroid.herdr.SecretOutcome.Refused("Couldn't read that file, so nothing was saved.")); return@launch }
                finishSecret(secretSender.send(target, label?.takeIf { it.isNotBlank() } ?: name, false, bytes))
            } finally { secretBusy = false }
        }
        return true
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

    // Keep one snapshot so the aggregate wait and its attribution cannot get out of sync.
    private var hermesLiveStates by mutableStateOf<List<HermesLiveState>>(emptyList())
    private val hermesState: Status? get() = when {
        hermesLiveStates.any { it.status == "waiting" } -> Status.NEEDS_YOU
        hermesLiveStates.any { it.status == "working" || it.status == "streaming" || it.status == "starting" } -> Status.WORKING
        else -> null
    }
    private var hermesPoll: Job? = null

    private fun pollHermes() {
        if (hermesPoll?.isActive == true) return
        hermesPoll = viewModelScope.launch {
            while (isActive) {
                val hasHermes = panes.any { it.agent?.lowercase() == "hermes" }
                val before = hermesState
                val states = if (hasHermes && foreground && herdr != null) hermesChat()?.liveStates() else null
                if (!isActive) return@launch // Do not restore a closed server's snapshot after cancellation.
                hermesLiveStates = states.orEmpty()
                val next = hermesState
                if (next != before) {
                    com.billmongan.herdrdroid.diag.DiagLog.log("hermes", "conversations: " +
                        (states?.joinToString { it.status } ?: "gateway not answering") + " -> ${next ?: "pane status"}")
                }
                delay(15_000)
            }
        }
    }

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

    /**
     * 2.2.7: the label an open pane not yet in [panes] had in an earlier tab list of this server
     * (the watcher's, or the last list kept for it), for the title bar's fallback; null when none.
     */
    internal fun paneLabelGuess(id: String): String? {
        val sid = current?.id ?: return null
        val p = (fleetStates[sid]?.panes.orEmpty() + serverPanes[sid].orEmpty()).firstOrNull { it.id == id } ?: return null
        return if (p.agent != null) com.billmongan.herdrdroid.ui.sessionName(p) else p.tabLabel.trim().ifBlank { null }
    }

    /** The open computer's agent versions (1.4.0): agent kind to version. */
    var agentVersions by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** The version of the agent in [paneId], when known. */
    fun versionOf(paneId: String): String? = com.billmongan.herdrdroid.herdr.ruleAgent(pane(paneId)?.agent)?.let { agentVersions[it] }

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
        // 2.2.6: the link's own lines too (opaque tags and numbers only), for a stalling connection.
        // The two sets are disjoint, so no line shows twice (a short pane id can match a link line's digits).
        val logLines = com.billmongan.herdrdroid.diag.DiagLog.text().lines()
        val diag = logLines.filter { it.contains(paneId) && !it.contains(" [link] ") }.takeLast(40) +
            logLines.filter { it.contains(" [link] ") }.takeLast(40)
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
            appendLine("last prompt signature: ${lastPromptSig[ck(paneId)]}")
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

    /**
     * 2.2.7: Export pane screen, read fresh after the tap (see diag/PaneScreenExport.kt). Builds the
     * file's text only; sharing it is a second tap in the dialog. Null without a connected client.
     */
    internal suspend fun paneScreenExport(paneId: String): com.billmongan.herdrdroid.diag.PaneScreenExport? {
        val h = herdr ?: return null
        val p = pane(paneId)
        val (plain, ansi) = h.exportScreens(paneId)
        val prompt = com.billmongan.herdrdroid.herdr.detectPrompt(
            com.billmongan.herdrdroid.herdr.forPhone(ansi), p?.status == Status.NEEDS_YOU, p?.agent, versionOf(paneId))
        return com.billmongan.herdrdroid.diag.PaneScreenExports.build(
            com.billmongan.herdrdroid.BuildConfig.VERSION_NAME, current?.label.orEmpty(), paneId, p, versionOf(paneId),
            plain, ansi, prompt, System.currentTimeMillis())
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
        // Keyed by server and pane (2.1.1): typing in one server's pane never holds another's.
        val key = ck(paneId)
        val before = lastInputLine.put(if (styled) "$key|styled" else key, now)
        if (styled) styledAt[key] = System.currentTimeMillis()
        // A plain change while the last styled read (within 2.5 minutes) found the line empty is a
        // placeholder changing (Codex rotates its suggestions), not typing (from Grok's review).
        val styledEmpty = !styled && lastInputLine["$key|styled"] == "" &&
            System.currentTimeMillis() - (styledAt[key] ?: 0L) < 150_000L
        if (now.isNotEmpty() && before != null && before != now && !styledEmpty) typedAt[key] = System.currentTimeMillis()
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
    // 2.2.7: a message goes as the agent's prompt, which on Oh My Pi and Copilot clears the box with
    // their own Ctrl+U first ([Herdr.sendMessage]), so a blank box there is empty for it, as for a menu write.
    private fun holdReason(paneId: String, screen: String): String? = holdReasonFor(paneId, screen, menuWrite = true)

    /** [holdReason], for a menu write when [menuWrite] (2.2.2: a blank Oh My Pi or Copilot box counts as empty there). */
    private fun holdReasonFor(paneId: String, screen: String, menuWrite: Boolean): String? {
        // The agent's own hint in an empty line ("Press up to edit queued messages") is not typing (1.4.4).
        val agent = pane(paneId)?.agent
        // A draft of several lines can hide the one-row input line (2.1.0): it is still typing.
        val now = com.billmongan.herdrdroid.herdr.inputLineOf(screen, agent, menuWrite)
            ?: return com.billmongan.herdrdroid.herdr.composerDraftOf(screen, agent)?.let { DRAFT_HOLD }
        // The row taken for the input line is the highlighted option of a menu (Kiro's unnumbered
        // approval card, "❯ Yes, single permission"): a question, not text typed at the computer.
        // Still not typed into, but said as it is (1.4.1, from a code review).
        if (now.isNotEmpty() && com.billmongan.herdrdroid.herdr.detectUnnumberedMenu(com.billmongan.herdrdroid.herdr.forPhone(screen))?.choices?.any { it.label.trim() == now } == true)
            return "a question is open in it (\"${now.take(40)}\"); answer it first"
        if (now.isNotEmpty()) return DRAFT_HOLD
        // Rows typed under an empty first row (2.1.0), as the voice and relay sends judge it.
        if (com.billmongan.herdrdroid.herdr.composerDraftOf(screen, agent) != null) return DRAFT_HOLD
        val at = typedAt[ck(paneId)]
        if (at != null && System.currentTimeMillis() - at < 4_000) return "someone was typing in it a moment ago"
        return null
    }

    /** Why a send waits for a draft at the computer (2.1.0): the chat's draft card shows it, with Send draft and Clear draft. */
    private val DRAFT_HOLD = "a draft is typed in its input box at the computer (the chat's Draft in the pane card can send or clear it)"

    internal fun isPaneDraftHold(note: String?): Boolean = note?.contains(DRAFT_HOLD) == true

    /**
     * [failed] (2.2.7, Send now on a queued message): when set, a send that a guard refuses is not
     * held and retried here but handed back (with false: nothing was typed), and a write that fails
     * is handed back with whether it may have reached the computer ([com.billmongan.herdrdroid.ssh.SendTrace]);
     * either way its pending row is removed, so the queued message is the one place it shows.
     */
    fun sendMessage(pane: Pane, text: String, force: Boolean = false, failed: ((String, Boolean) -> Unit)? = null,
                    bound: BoundSend? = null, delivered: () -> Unit = {}) {
        // 2.2.8: a bound message whose pane has another occupant already is never sent (see [BoundSend]).
        if (bound != null && !bound.occupant.sameCardOccupant(pane)) { occupantMovedDuringSend(bound); return }
        // 2.2.7: never truncated. Text too long for one message goes as a checked .txt file.
        when (com.billmongan.herdrdroid.herdr.LongText.plan(text)) {
            is com.billmongan.herdrdroid.herdr.LongText.Plan.Single -> sendOne(pane, text, force, failed, bound, delivered)
            is com.billmongan.herdrdroid.herdr.LongText.Plan.Attachment -> sendAsFile(pane, text, force, failed, delivered, bound)
        }
    }

    /**
     * 2.2.8 (Codex's re-review): a message queued behind a question carries its pane's occupant all the
     * way to the write, through both the short and the long (uploaded file) path and a held retry; it is
     * checked again right before the keys or text go out. [text] is what you typed, for the message box;
     * [queuedId] its queue row, if it still has one.
     */
    data class BoundSend(internal val occupant: Pane, val text: String, val queuedId: Long? = null)

    /** A bound message found another occupant in its pane: back into the message box, with the note; never sent. */
    private fun occupantMovedDuringSend(bound: BoundSend) {
        val paneId = bound.occupant.id
        bound.queuedId?.let { id -> queued = queued.filterNot { it.id == id } }
        val draft = composerDraft(paneId)
        if (!draft.contains(bound.text)) setComposerDraft(paneId, if (draft.isBlank()) bound.text else draft.trimEnd() + "\n" + bound.text)
        com.billmongan.herdrdroid.diag.DiagLog.log("send", "to $paneId: the pane's occupant changed before the write; nothing sent, back to the draft")
        flash(QUEUED_OCCUPANT_CHANGED)
    }

    /**
     * 2.2.8 (Codex's re-review 2): a bound message's write. Its pane's occupant is read on the pinned
     * connection (the app's list can be behind), compared with [bound]'s, and the write goes behind
     * the occupant check on the computer ([com.billmongan.herdrdroid.herdr.Herdr.occupantCheckCommand]).
     * False when another occupant is there (read here, or found by the computer): nothing was typed.
     */
    private suspend fun sendBound(h: Herdr, bound: BoundSend, pane: Pane, text: String, connection: Any?): Boolean {
        val conn = connection ?: h.connectionIdentity ?: throw LinkDown(IllegalStateException("not connected"))
        val (fresh, digest) = h.boundOccupant(pane.id, conn) ?: return false
        if (!bound.occupant.sameCardOccupant(fresh)) return false
        return try {
            h.sendMessage(pane, text, expectedConnection = conn, occupant = digest,
                shellOnly = current?.id?.let { it in noPython3 } == true)
            true
        } catch (_: com.billmongan.herdrdroid.herdr.OccupantChanged) {
            false
        }
    }

    private fun boundMoved(bound: BoundSend?, paneId: String): Boolean =
        bound != null && pane(paneId)?.let { bound.occupant.sameCardOccupant(it) } != true

    /**
     * 2.2.7: text too long for one message goes as a .txt file in the computer's inbox, checked by
     * size and SHA-256, then one short message points at it. If the file can't be put there whole,
     * the text waits in the queue, never cut.
     */
    private fun sendAsFile(pane: Pane, text: String, force: Boolean, failed: ((String, Boolean) -> Unit)?, delivered: () -> Unit, bound: BoundSend? = null) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id}: long text, ${bytes.size} bytes as a file")
        act(pane.id, "Couldn't attach the pasted text", input = true) { h ->
            val receipt = try {
                h.uploadVerified("pasted-text.txt", bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = com.billmongan.herdrdroid.diag.DiagLog.describe(e)
                if (failed != null) failed(why, false) else {
                    // Pre-review M4: it waits for a tap (Send now), never retried by itself every few seconds.
                    enqueue(pane.id, text)
                    queued.lastOrNull()?.let { q -> updateQueued(q.id) { if (bound != null) it.copy(occupant = bound.occupant) else it }; updateQueued(q.id) { it.copy(needsTap = true,
                        note = "Not sent yet: the pasted text couldn't be put on the computer whole ($why). Long-press for Send now to try again.") } }
                }
                return@act
            }
            com.billmongan.herdrdroid.herdr.SentFiles.remember(receipt.path, text)
            val line = com.billmongan.herdrdroid.herdr.LongText.fileLine(receipt)
            // 2.2.8: the upload can take a while; the file's line goes only to the occupant it was for.
            if (boundMoved(bound, pane.id)) { occupantMovedDuringSend(checkNotNull(bound)); return@act }
            sendOne(pane(pane.id) ?: pane, line, force, failed, bound, delivered)
        }
    }

    private fun sendOne(pane: Pane, text: String, force: Boolean = false, failed: ((String, Boolean) -> Unit)? = null,
                        bound: BoundSend? = null, delivered: () -> Unit = {}) {
        val serverId = current?.id
        markSent(serverId, pane.id, text)
        lastSentAt = System.currentTimeMillis()
        val pendingId = System.nanoTime()
        pendingSends = pendingSends + PendingSend(
            pendingId, pane.id, text, lastSentAt,
            if (transcriptPane == pane.id) sameTextCount(text) else 0,
            hermesTarget = pane.takeIf { it.agent == "hermes" },
            serverId = serverId,
            bound = bound,
        )
        com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id} (${pane.agent ?: "shell"}, ${pane.status}): ${text.length} chars; link ${link}, ${if (herdr == null) "no client" else "client ready"}")
        act(pane.id, "Couldn't send", pendingId) { h ->
            if (pendingSends.none { it.id == pendingId }) return@act
            val verifyOpenCode = isOpenCode(pane.agent) && h.backend == Backend.TUI
            val verifyHermes = pane.agent == "hermes" && h.backend == Backend.TUI
            var openCodeRead: OpenCodeSendRead? = null
            // 2.1.4: a usage command opens a dialog that only Esc closes, so it goes only to an idle
            // agent with an empty input box, and never through a held retry that types it later.
            // 2.1.4: so does Claude's /btw, whose side answer is read into the chat and then closed.
            val btwAsked = com.billmongan.herdrdroid.herdr.btwQuestion(pane.agent, text)
            val opensUsage = com.billmongan.herdrdroid.herdr.opensUsageDialog(pane.agent, text) || btwAsked != null
            run {
                // Behind an earlier held message unless the user explicitly chose Force send.
                val earlier = !force && pendingSends.any { it.isFor(serverId, pane.id) && it.waiting && it.id != pendingId }
                // A usage dialog the app left open is closed first (2.1.4); the send waits for that.
                val leftOpen = usageLeftOpen(pane.id)
                if (leftOpen) closeAppUsage(pane.id, "a send was waiting", automatic = false)
                // Read with its styles (1.4.6), so an agent's dim placeholder isn't taken for typing.
                val screenNow = if (earlier) null else if (verifyOpenCode || verifyHermes) {
                    openCodeRead = try { readOpenCodeForSend(h, pane) } catch (_: HerdrError) { null }
                    openCodeRead?.text
                } else runCatching { h.readStyled(pane.id, sendReadLines(pane.agent)) }.getOrNull()
                // A pane that can't be read first is not typed into blind (1.4.1, from a code review).
                val now = pane(pane.id) ?: pane
                val why = (if (opensUsage) usageOpenRefusal(pane, pendingId, h) else null) ?: when {
                    earlier -> "an earlier message to it is still waiting"
                    screenNow.isNullOrBlank() -> "its screen couldn't be read first"
                    leftOpen -> USAGE_HOLD
                    usageDialogShown(now.agent, screenNow) -> panelHold(serverId, now, screenNow)
                    now.agent == "hermes" && hermesUsageWaiting(screenNow) -> "Hermes hasn't returned to its composer after Usage"
                    openCodeBackgroundDrawer(now.agent, screenNow) -> "its background drawer is open; close it in Terminal"
                    checkedSendBlocked(now, screenNow, openCodeRead) -> "a question is open in it; answer it first"
                    verifyHermes && now.status != Status.WORKING &&
                        com.billmongan.herdrdroid.herdr.inputLineOf(screenNow, "hermes") == null -> "Hermes's composer isn't visible yet"
                    force && !opensUsage -> null
                    else -> holdReason(pane.id, screenNow)
                }
                if (why != null && failed != null) {
                    // Pre-review L7: the reason only, never screen text quoted in it.
                    com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id}: send now not sent, ${why.replace(Regex("\\(\".*?\"\\)"), "").trim()}; still queued")
                    pendingSends = pendingSends.filterNot { it.id == pendingId }
                    failed(why, false)
                    return@act
                }
                if (why != null && opensUsage) {
                    // Refused, not held: a held usage command would open its dialog later, unwatched.
                    com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id}: usage command not sent, $why")
                    pendingSends = pendingSends.map { if (it.id == pendingId) it.copy(held = why, waiting = false, undelivered = true, awaitingLink = false) else it }
                    serverId?.let { noteSendProblem(it, pane.id, "Not sent to ${pane.agentName}: ${text.substringBefore(' ')} opens " +
                        (if (btwAsked != null) "a side-question panel" else "its usage screen") +
                        ", which HerdrDroid opens only when the agent is idle with an empty input box ($why).") }
                    return@act
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
            // Recorded before it is typed (2.1.4), so a dialog opened just before the app is closed or
            // killed is still closed first by the next check or send.
            val usageRecord = if (opensUsage && serverId != null && pane.agent != null) {
                OpenedUsage(serverId, pane.id, pane.agent, System.currentTimeMillis(), btw = btwAsked).also { o ->
                    saveOpenedUsage(openedUsage.filterNot { it.server == o.server && it.pane == o.pane } + o)
                }
            } else null
            // 2.2.8: the last check before the write (nothing suspends from here to it): a bound message goes
            // only to its own occupant.
            if (boundMoved(bound, pane.id) || bound != null && openCodeRead?.pane?.let { bound.occupant.sameCardOccupant(it) } == false) {
                pendingSends = pendingSends.filterNot { it.id == pendingId }
                if (usageRecord != null) saveOpenedUsage(openedUsage.filterNot { it === usageRecord })
                occupantMovedDuringSend(checkNotNull(bound))
                return@act
            }
            try {
                val checked = openCodeRead
                if (bound != null && h.backend == Backend.TUI) {
                    // 2.2.8 (Codex's re-review 2): the occupant read again on the pinned connection, never
                    // from the app's list, then checked once more on the computer in the write's own command.
                    if (!sendBound(h, bound, checked?.pane ?: pane, text, checked?.connection)) {
                        pendingSends = pendingSends.filterNot { it.id == pendingId }
                        if (usageRecord != null) saveOpenedUsage(openedUsage.filterNot { it === usageRecord })
                        occupantMovedDuringSend(bound)
                        return@act
                    }
                } else h.sendMessage(checked?.pane ?: pane, text, expectedConnection = checked?.connection)
                com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id}: delivered to herdr (${h.lastSendRoute})")
                delivered()
            } catch (e: Exception) {
                com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${pane.id}: failed: ${com.billmongan.herdrdroid.diag.DiagLog.describe(e)}")
                // It may still have gone in; the close checks the screen and only presses Esc on the dialog.
                if (usageRecord != null) followOpened(usageRecord)
                if (failed != null && e !is CancellationException) {
                    // Removed first, so a replay of this action after a reconnect finds nothing to send.
                    pendingSends = pendingSends.filterNot { it.id == pendingId }
                    failed(com.billmongan.herdrdroid.diag.DiagLog.describe(e), e is com.billmongan.herdrdroid.herdr.DeliveryUncertain ||
                        kotlin.coroutines.coroutineContext[com.billmongan.herdrdroid.ssh.SendTrace]?.started == true)
                }
                throw e
            }
            if (usageRecord != null) followOpened(usageRecord)
            readPane(pane.id)
        }
    }

    /** Why a send waits on a usage dialog or /btw panel the app opened (2.1.4). */
    private val USAGE_HOLD = "its usage screen or side-question panel is still open; HerdrDroid is closing it first"
    /** Why a send waits on a usage dialog or /btw panel open in the pane (2.1.4): typing would go nowhere. */
    private val USAGE_OPEN_HOLD = "its usage screen or side-question panel is open; close it with Esc (or Dismiss) first"

    /** An agent's usage dialog is drawn on [screen], in its known layout or by its close hint (2.1.4). */
    private fun usageDialogShown(agent: String?, screen: String): Boolean =
        com.billmongan.herdrdroid.herdr.usageScreenShown(agent, screen)

    /**
     * How far back a pane is read before text is typed into it. 2.1.1 (Grok's RC review, H2):
     * Claude's sessions list has its only marker near the top of the screen ("Your session moved to
     * the background", line 6 of the 47-line capture), past a 40-line read, so a Claude pane is read
     * as deep as the chat card's own read and [blocksNormalSend] sees the card there too.
     */
    private fun sendReadLines(agent: String?): Int = if (agent.equals("claude", true)) NOTIFY_READ_LINES else 40

    /** The quiet retry of held sends, one per (server, pane), so its messages go in the order sent (1.4.4). */
    private val heldJobs = mutableMapOf<Pair<String, String>, Job>()

    private fun startHeldRetry(paneId: String) {
        val server = current?.id ?: return
        val lane = server to paneId
        if (heldJobs[lane]?.isActive == true) return
        val job = viewModelScope.launch {
            while (isActive) {
                // Another server is open (2.1.2): its sends stay held, and resume when it is open again.
                if (current?.id != server) break
                // Only this server's sends (2.1.1): the same pane id on another server is another pane.
                val next = pendingSends.firstOrNull { it.isFor(server, paneId) && it.waiting } ?: break
                retryHeld(next, server)
            }
        }
        heldJobs[lane] = job
        job.invokeOnCompletion { if (heldJobs[lane] === job) heldJobs.remove(lane) }
    }

    /**
     * Back on [serverId] (2.1.2, from the 2.1.1 acceptance): each of its panes with a send still held
     * while another server was open starts its quiet retry again, under the same guards.
     */
    private fun resumeHeldSends(serverId: String) {
        if (current?.id != serverId) return
        pendingSends.filter { it.serverId == serverId && it.waiting }.map { it.paneId }.distinct().forEach(::startHeldRetry)
    }

    /**
     * A problem with a send, shown only in the chat of the pane it was for, on its own server (2.1.2):
     * pane ids repeat across servers, so a global banner showed FixtureB's on FixtureA's w1:p1.
     */
    data class SendNotice(val serverId: String, val paneId: String, val text: String)
    var sendNotices by mutableStateOf<List<SendNotice>>(emptyList())
        private set

    /** The newest send notice for [paneId] on the open server, or null. */
    fun sendNoticeFor(paneId: String): String? =
        current?.id?.let { sid -> sendNotices.lastOrNull { it.serverId == sid && it.paneId == paneId }?.text }

    fun dismissSendNotice(paneId: String) {
        val sid = current?.id ?: return
        sendNotices = sendNotices.filterNot { it.serverId == sid && it.paneId == paneId }
    }

    private fun noteSendProblem(serverId: String, paneId: String, text: String) {
        if (probePane(serverId, paneId)) return
        sendNotices = sendNotices.filterNot { it.serverId == serverId && it.paneId == paneId } + SendNotice(serverId, paneId, text)
    }

    /** Retries one held send until it goes, times out, or is cancelled (see [HeldSendRetry]). */
    private suspend fun retryHeld(p: PendingSend, server: String) {
        val log = { m: String -> com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${p.paneId}: $m") }
        val agentName = pane(p.paneId)?.agentName ?: "the agent"
        val closeKey = server to p.paneId
        val closeEpoch = writeGate.epoch(closeKey)
        fun closing() = writeGate.isClosing(closeKey) || writeGate.epoch(closeKey) != closeEpoch
        var missing = 0
        var openCodeRead: OpenCodeSendRead? = null
        val outcome = com.billmongan.herdrdroid.herdr.HeldSendRetry().run(
            // The 2 minutes start when it reaches the front of the pane's queue (from Grok's review).
            startedAt = com.billmongan.herdrdroid.herdr.HeldSendRetry.monotonicMillis(),
            firstWhy = p.held ?: "held",
            cancelled = {
                missing = if (pane(p.paneId) == null) missing + 1 else 0
                when {
                    pendingSends.none { it.id == p.id && it.waiting } -> "no longer waiting (cancelled, or the agent's log has it)"
                    current?.id != server -> "the computer changed"
                    p.hermesTarget?.let { original -> pane(p.paneId)?.let { !original.sameCardOccupant(it) } } == true -> "the pane changed"
                    p.bound != null && boundMoved(p.bound, p.paneId) && pane(p.paneId) != null -> "the pane changed"
                    closing() -> "its tab is being closed"
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
                    pn.status == Status.NEEDS_YOU && !(h.backend == Backend.TUI &&
                        (isOpenCode(pn.agent) || pn.agent == "hermes")) -> "a question is open in it; answer it first"
                    pendingSends.any { it.isFor(server, p.paneId) && it.waiting && it.at < p.at && it.id != p.id } -> "an earlier message to it is still waiting"
                    else -> {
                        // Let HeldSendRetry handle read failures and propagate cancellation.
                        val verifyOpenCode = isOpenCode(pn.agent) && h.backend == Backend.TUI
                        val verifyHermes = pn.agent == "hermes" && h.backend == Backend.TUI
                        val checked = if (verifyOpenCode || verifyHermes) readOpenCodeForSend(h, pn) else null
                        openCodeRead = checked
                        val screen = if (verifyOpenCode || verifyHermes) checked?.text ?: return@run "its screen couldn't be read first"
                            else h.readStyled(p.paneId, sendReadLines(pn.agent))
                        noteInputLine(p.paneId, screen)
                        // Checked again after the read, which can take a while (from Grok's review): a
                        // question or a usage check that began meanwhile is never typed into. Nothing
                        // suspends from here to the send, so neither can begin in between.
                        val after = pane(p.paneId)
                        when {
                            // Its tab may have been closed during the read.
                            closing() -> "its tab is being closed"
                            after == null -> "the pane isn't listed just now"
                            // 2.2.8: a bound message waits here until the cancel check sees the change.
                            p.bound != null && !p.bound.occupant.sameCardOccupant(after) -> "the pane changed"
                            // 2.1.4: a usage dialog the app left open is closed first; one open
                            // otherwise is never typed into.
                            usageLeftOpen(p.paneId) -> { closeAppUsage(p.paneId, "a held send was waiting"); USAGE_HOLD }
                            usageDialogShown(after.agent, screen) -> panelHold(server, after, screen)
                            after.agent == "hermes" && hermesUsageWaiting(screen) -> "Hermes hasn't returned to its composer after Usage"
                            openCodeBackgroundDrawer(after.agent, screen) -> "its background drawer is open; close it in Terminal"
                            checkedSendBlocked(after, screen, checked) -> "a question is open in it; answer it first"
                            pn.agent == "hermes" && com.billmongan.herdrdroid.herdr.inputLineOf(screen, "hermes") == null -> "Hermes's composer isn't visible yet"
                            else -> holdReason(p.paneId, screen)
                        }
                    }
                }
            },
            send = {
                val h = herdr ?: throw IllegalStateException("not connected")
                val pn = pane(p.paneId) ?: throw IllegalStateException("the pane closed")
                val checked = openCodeRead
                // 2.2.8: checked by the retry's own check; this is the write's last look, with nothing in between.
                if (p.bound != null && (!p.bound.occupant.sameCardOccupant(pn) || checked?.pane?.let { p.bound.occupant.sameCardOccupant(it) } == false))
                    throw BoundOccupantChanged()
                // 2.2.8 (Codex's re-review 2): read on the pinned connection and checked on the computer too.
                if (p.bound != null && h.backend == Backend.TUI) {
                    if (!sendBound(h, p.bound, checked?.pane ?: pn, p.text, checked?.connection)) throw BoundOccupantChanged()
                } else h.sendMessage(checked?.pane ?: pn, p.text, expectedConnection = checked?.connection)
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
                markSent(server, p.paneId, p.text)
                lastSentAt = t
                heldThen.remove(p.id)?.invoke()
                readPane(p.paneId)
            }
            is com.billmongan.herdrdroid.herdr.HeldOutcome.TimedOut -> {
                if (pendingSends.none { it.id == p.id && it.waiting }) return
                pendingSends = pendingSends.map { if (it.id == p.id) it.copy(waiting = false, undelivered = true, held = outcome.why) else it }
                noteSendProblem(server, p.paneId, "Not sent to $agentName: ${outcome.why}, for 2 minutes. Tap Resend to send it anyway.")
            }
            is com.billmongan.herdrdroid.herdr.HeldOutcome.Failed -> if (p.bound != null && outcome.error is BoundOccupantChanged) {
                pendingSends = pendingSends.filterNot { it.id == p.id }
                heldThen.remove(p.id)
                occupantMovedDuringSend(p.bound)
            } else if (outcome.error is com.billmongan.herdrdroid.herdr.DeliveryUncertain) {
                // 2.2.8 (Codex's re-review 3): the write began, then failed: maybe sent, never resent by itself.
                pendingSends = pendingSends.map { if (it.id == p.id) it.copy(waiting = false, undelivered = true, held = MAY_HAVE_SENT) else it }
                noteSendProblem(server, p.paneId, "Not confirmed: the message to $agentName may have gone in (${outcome.error.message}). Check the agent before sending it again.")
            } else {
                val why = "sending failed (${com.billmongan.herdrdroid.diag.DiagLog.describe(outcome.error)})"
                pendingSends = pendingSends.map { if (it.id == p.id) it.copy(waiting = false, undelivered = true, held = why) else it }
                noteSendProblem(server, p.paneId, "Not sent to $agentName: $why. Tap Resend to try again.")
            }
            is com.billmongan.herdrdroid.herdr.HeldOutcome.Cancelled -> when {
                // Cancelled by you, or already in the agent's log: nothing to report.
                pendingSends.none { it.id == p.id } -> {}
                // Another server was opened (2.1.2, from the 2.1.1 acceptance): the send stays held on its
                // own server, with nothing reported on the other, and [resumeHeldSends] takes it up again
                // when that server is open. It was never typed, so it is never shown as sent.
                outcome.why == "the computer changed" -> log("kept held for when its server is open again")
                // 2.2.8: a bound message whose pane has another occupant goes back to the message box.
                p.bound != null && outcome.why == "the pane changed" -> {
                    pendingSends = pendingSends.filterNot { it.id == p.id }
                    heldThen.remove(p.id)
                    occupantMovedDuringSend(p.bound)
                }
                // The pane closed: reported in its own chat, and kept as not sent (from Grok's review).
                else -> {
                    pendingSends = pendingSends.map { if (it.id == p.id) it.copy(waiting = false, undelivered = true, held = outcome.why) else it }
                    noteSendProblem(server, p.paneId, "Not sent to $agentName: ${outcome.why} before its input line was clear.")
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

    internal data class TerminalFrame(val source: CardTarget, val live: Pane, val text: String)
    private var paintedTerminal by mutableStateOf<TerminalFrame?>(null)
    internal var terminalReadProblem by mutableStateOf<String?>(null)
        private set
    private var terminalReadSequence = 0L
    private var lastTerminalRead = 0L

    internal fun terminalFrame(paneId: String): TerminalFrame? =
        paintedTerminal?.takeIf { it.source.pane.id == paneId && it.source.isCurrent(it.source.client) }

    internal fun clearTerminalFrame() {
        lastTerminalRead = ++terminalReadSequence
        paintedTerminal = null
        terminalReadProblem = null
    }

    internal suspend fun readTerminalPane(paneId: String) {
        val target = cardTarget(paneId) ?: return
        val attempt = ++terminalReadSequence
        fun isCurrent() = attempt > lastTerminalRead && target.isCurrent(target.client)
        var problem: String? = null
        val result = quietly { h ->
            if (h !== target.client || !isCurrent()) return@quietly null
            try {
                h.readTerminalFrame(target.pane, target.connection) { live ->
                    readPaneScreen(h, live.id, 200, live.scrollOffset, target.connection)
                }
            } catch (e: HerdrError) {
                problem = e.message?.take(120)
                null
            }
        }
        if (!isCurrent()) return
        lastTerminalRead = attempt
        paintedTerminal = result?.let { (live, text) -> TerminalFrame(target, live, text) }
        terminalReadProblem = if (result == null) problem ?: "the terminal could not be verified" else null
    }

    private fun terminalFrameMatches(paneId: String, frame: TerminalFrame?, client: Herdr) =
        frame != null && frame.source.isCurrent(client) &&
            terminalFrame(paneId)?.let { com.billmongan.herdrdroid.herdr.sameOccupant(frame.live, it.live) } == true

    /** Keys use the same verified terminal that supplied the displayed screen. */
    internal fun sendTerminalKeys(paneId: String, frame: TerminalFrame?, vararg keys: String) {
        fun stillShowing(h: Herdr) = terminalFrameMatches(paneId, frame, h)
        if (frame == null || !stillShowing(frame.source.client)) {
            error = "Couldn't send the key: wait for a verified read of this terminal and try again."
            return
        }
        act(paneId, "Couldn't send the key", input = true) { h ->
            h.sendTerminalKeys(frame.live, keys.toList(), frame.source.connection) { stillShowing(h) }
            if (stillShowing(h)) readTerminalPane(paneId)
        }
    }

    /** Keep dispatch identity, not the history signature (which groups different steps of a dialog). */
    private val answeredCards = mutableMapOf<Pair<String, String>, Pair<Prompt, Long>>()

    private fun rememberCardAnswer(serverId: String, paneId: String, card: Prompt) {
        answeredCards[serverId to paneId] = card to android.os.SystemClock.elapsedRealtime()
    }

    private fun recentAnswerAge(serverId: String, paneId: String, matches: (Prompt) -> Boolean): Long? {
        val (was, at) = answeredCards[serverId to paneId] ?: return null
        return (android.os.SystemClock.elapsedRealtime() - at).takeIf { it < ANSWER_HOLD_MS && matches(was) }
    }

    private suspend fun readPaneScreen(h: Herdr, paneId: String, lines: Int,
                                       scrollOffset: Int? = pane(paneId)?.scrollOffset, connection: Any? = null): String =
        recoverPaneRead(
            recent = { h.read(paneId, lines, preserveGlyphs = true, expectedConnection = connection) },
            visible = {
                // A viewport scrolled back can show an old approval. Only read it at the bottom.
                if ((scrollOffset ?: 0) > 0) "" else h.readVisible(paneId, preserveGlyphs = true, expectedConnection = connection)
            },
            shortRecent = { h.read(paneId, 40, preserveGlyphs = true, expectedConnection = connection) },
            stopOnMissing = paneId,
        )

    private suspend fun readOpenPaneScreen(h: Herdr, paneId: String, lines: Int, shown: Pane?,
                                           connection: Any?, stillOpen: () -> Boolean): Pair<String, Boolean> =
        try {
            readPaneScreen(h, paneId, lines, connection = connection) to true
        } catch (e: HerdrError) {
            if (h.backend != Backend.TUI || !e.isMissingPane(paneId)) throw e
            if (shown == null || connection == null || !stillOpen()) throw terminalUnavailable()
            val live = h.resolveTerminalPane(shown, connection)
            if (!stillOpen()) throw terminalUnavailable()
            try {
                readPaneScreen(h, live.id, lines, live.scrollOffset, connection) to (live.id == paneId)
            } catch (again: HerdrError) {
                if (!again.isMissingPane(live.id)) throw again
                throw terminalUnavailable()
            }
        }

    /** Read fresh output for the card before accepting an answer. */
    private suspend fun readForCard(h: Herdr, paneId: String, lines: Int = 80): String? =
        try { readPaneScreen(h, paneId, lines) } catch (_: HerdrError) { null }

    /** A card answered this recently is not answered again: the agent may not have redrawn yet (1.4.8). */
    private val ANSWER_HOLD_MS = 8_000L

    private var answeringCards by mutableStateOf<Set<Pair<String, String>>>(emptySet())

    fun answerInFlight(paneId: String): Boolean = (current?.id to paneId) in answeringCards

    // Includes the particular connection and visit so leaving and returning cannot re-arm a send.
    // [occupant] binds a message to the same terminal and agent session, not just the pane (2.0.1 finding 1).
    internal data class VoiceTarget(
        val server: String, val client: Herdr, val connection: Any, val visit: Long,
        val pane: String, val agent: String, val cwd: String,
        val occupant: com.billmongan.herdrdroid.herdr.Occupant,
    )

    internal fun voiceTarget(): VoiceTarget? {
        if (locked || link != Link.CONNECTED || (!foreground && current?.overWebSocket == true)) return null
        val pane = (screen as? Screen.Agent)?.paneId?.let(::pane) ?: return null
        val client = herdr ?: return null
        return VoiceTarget(current?.id ?: return null, client, client.connectionIdentity ?: return null,
            paneVisit, pane.id, pane.agent ?: return null, pane.cwd, com.billmongan.herdrdroid.herdr.Occupant.of(pane))
    }

    private val voiceComposerDrafts = mutableSetOf<Pair<String, String>>()

    internal fun noteVoiceComposerDraft(server: String, pane: String, hasDraft: Boolean) {
        if (hasDraft) voiceComposerDrafts.add(server to pane) else voiceComposerDrafts.remove(server to pane)
    }

    private fun promptLaneClear(serverId: String, paneId: String): Boolean =
        (serverId to paneId) !in answeringCards &&
            queued.none { it.serverId == serverId && it.paneId == paneId } &&
            (serverId to paneId) !in voiceComposerDrafts &&
            // Pending sends belong to the foreground server; pane IDs alone are not global.
            pendingSends.none { it.isFor(serverId, paneId) && (it.waiting || it.awaitingLink) } &&
            prefills[prefillKey(serverId, paneId)].isNullOrBlank()

    private fun relayPort(serverId: String): com.billmongan.herdrdroid.herdr.PromptRelayPort? {
        if (locked || serverId == current?.id) return null
        val saved = servers.firstOrNull { it.id == serverId } ?: return null
        val watched = fleetStates[serverId] ?: return null
        if (watched.error != null || watched.server != saved || (!foreground && saved.overWebSocket)) return null
        return fleet?.herdr(serverId)?.takeUnless { it.backend == com.billmongan.herdrdroid.herdr.Backend.CORE }
            ?.relayPort(transcriptScript)
    }

    private val promptRelay by lazy {
        com.billmongan.herdrdroid.herdr.PromptRelay(paneDispatch, ::relayPort, writeGate, laneClear = ::promptLaneClear) { serverId, paneId ->
            promptLaneClear(serverId, paneId) && fleetStates[serverId]?.panes?.singleOrNull { it.id == paneId }
                ?.let(::idleForRelay) == true
        }
    }

    internal data class RemotePromptTarget(
        val route: com.billmongan.herdrdroid.herdr.PromptRelay.Target,
        val origin: String?, val visit: Long, val screen: Screen,
    )

    internal suspend fun bindRemotePrompt(session: Session): RemotePromptTarget? {
        val origin = current?.id
        val visit = paneVisit
        val source = screen
        val route = promptRelay.bind(session.server.id, session.pane) ?: return null
        return RemotePromptTarget(route, origin, visit, source).takeIf(::remotePromptCurrent)
    }

    internal fun remotePromptCurrent(target: RemotePromptTarget): Boolean =
        target.origin == current?.id && target.visit == paneVisit && target.screen == screen && promptRelay.isCurrent(target.route)

    internal suspend fun sendRemotePrompt(target: RemotePromptTarget, text: String, allowed: () -> Boolean) =
        promptRelay.send(target.route, text) { allowed() && remotePromptCurrent(target) }

    // ---- 2.1.5/2.2.0: cross-server messages approved on the phone ------------------------------
    //
    // The 2.1.4 "Listen for agent proposal" menu, its one-time code and its copied instructions are
    // gone. An agent asked in chat for a cross-server send renders a frame with a code it picks
    // (CrossServerFrame); the open pane's read finds it and shows a card. 2.2.0 (Bill: "have it send
    // a code and then have HerdrDroid pop up a yes/no menu to approve and then if approved it sends
    // that code synthetically to proceed"): the card also opens a Yes/No dialog, and Yes (or Approve
    // on the card) hands the frame's own code to the confirm path (approveCrossServer), bound to the
    // proposal's id, code and destination. The user no longer types the code, and the code is never
    // typed into any pane. The code on the agent's screen proves nothing by itself: the consent is
    // the tap on the phone.

    internal enum class CrossServerState { PENDING, SENDING, SENT, REFUSED, FAILED, DRAFT, CANCELLED, EXPIRED }

    internal data class CrossServerCard(
        /** Server, pane and code: one card per code per pane. */
        val id: String,
        val source: CardTarget,
        val code: String,
        /** The destination as the frame names it. */
        val requested: String,
        val text: String,
        /** Wall-clock deadline: ten minutes after the frame was first seen. */
        val deadline: Long,
        val choice: Session? = null,
        val state: CrossServerState = CrossServerState.PENDING,
        val note: String? = null,
        /**
         * 2.2.2: why the last try was refused, on a card pending again after it. A card that was
         * never tried has none and shows Approve; one with a refusal shows Retry, and Force Send
         * when the refusal is one forcing may pass ([crossServerForceable]).
         */
        val refusal: RelayRefusal? = null,
    )

    /**
     * 2.2.2: Force Send may pass only one refusal, WORKING (herdr says working, or the footer shows
     * "esc to interrupt"), and only for an agent that queues text typed during a turn: Claude Code
     * ([takesForcedInput]). Everything else (background work, a question, a draft, a panel, no
     * input line, scrolled back, unreachable, the link, a replaced session, the lane) and every
     * other agent, OpenCode included, get Retry and Cancel only.
     */
    private fun crossServerForceable(why: RelayRefusal?, agent: String?): Boolean =
        why == RelayRefusal.WORKING && com.billmongan.herdrdroid.herdr.takesForcedInput(agent)

    private var crossServerCards by mutableStateOf<List<CrossServerCard>>(emptyList())
    internal var crossServerClock: () -> Long = System::currentTimeMillis
    private val crossServerCodes = com.billmongan.herdrdroid.herdr.CrossServerCodeLedger(
        { prefs.crossServerCodes }, { prefs.crossServerCodes = it })
    private var crossServerExpiry: Job? = null
    private var crossServerPermit: WritePermit? = null
    /** Proposals whose dialog the user put aside (Back): they stay on their cards, with Approve and Cancel. */
    private var crossServerDeferred by mutableStateOf<Set<String>>(emptySet())

    /** Background, lock, a lost link or messaging turned off: every card goes. Unspent codes may show again on the next read. */
    private fun clearCrossServerCards() {
        paneReads.clear()
        crossServerPermit?.revoke()
        crossServerPermit = null
        crossServerCards = emptyList()
        crossServerDeferred = emptySet()
        crossServerExpiry?.cancel()
        crossServerExpiry = null
    }

    private fun crossServerSourceNow(paneId: String): CardTarget? =
        if (!agentMessagingEnabled || !agentMessagingForeground || locked) null
        else cardTarget(paneId)?.takeIf { it.client.backend == Backend.TUI &&
            it.pane.scrollOffset == 0 && hasStableOccupant(it.pane) }

    /** The card's pane is open now, on the same server, client, connection and occupant. */
    private fun crossServerSourceCurrent(source: CardTarget): Boolean =
        crossServerSourceNow(source.pane.id)?.let { now ->
            now.server == source.server && now.client === source.client && now.connection === source.connection &&
                sameOccupant(now.pane, source.pane)
        } == true

    /** Called with each good read of the open pane. Spent, expired or malformed codes raise no card. */
    private fun noteCrossServerFrames(owner: CardTarget?, recent: String) {
        if (owner == null || !crossServerSourceCurrent(owner)) return
        resolveCrossServerDestinations()
        val items = if (transcriptPane == owner.pane.id && transcriptState == TranscriptMode.ON) transcriptItems else emptyList()
        val frames = com.billmongan.herdrdroid.herdr.crossServerProposalFrames(recent, owner.pane.agent, items)
        if (frames.isEmpty()) return
        val now = crossServerClock()
        var added = false
        for (f in frames) {
            val id = "${owner.server}|${owner.pane.id}|${f.code}"
            if (crossServerCards.any { it.id == id }) continue
            val deadline = crossServerCodes.admit(id, now, f) ?: continue
            val card = CrossServerCard(id, owner, f.code, f.destination, f.text, deadline)
            crossServerCards = crossServerCards + card.copy(choice = crossServerMatches(card).singleOrNull())
            added = true
        }
        if (added) scheduleCrossServerExpiry()
    }

    /**
     * 2.2.0 step 1: a card read before the other server's watcher had its panes kept no destination,
     * and once the name matched exactly one agent the card offered no choice either (one match means
     * no choices), so the typed code only ever said "Choose a destination". A pending card with no
     * destination now takes the single match as soon as there is one.
     */
    private fun resolveCrossServerDestinations() {
        if (crossServerCards.none { it.state == CrossServerState.PENDING && it.choice == null }) return
        crossServerCards = crossServerCards.map { card ->
            if (card.state != CrossServerState.PENDING || card.choice != null) card
            else crossServerMatches(card).singleOrNull()?.let { card.copy(choice = it) } ?: card
        }
    }

    private fun scheduleCrossServerExpiry() {
        crossServerExpiry?.cancel()
        val next = crossServerCards.filter { it.state == CrossServerState.PENDING }.minOfOrNull { it.deadline } ?: return
        crossServerExpiry = viewModelScope.launch {
            delay((next - crossServerClock()).coerceAtLeast(0))
            expireCrossServerCards()
        }
    }

    /** Pending cards past their ten minutes become Expired; their codes are spent. */
    internal fun expireCrossServerCards() {
        val now = crossServerClock()
        var changed = false
        crossServerCards = crossServerCards.map {
            if (it.state == CrossServerState.PENDING && now >= it.deadline) {
                crossServerCodes.spend(it.id, now)
                changed = true
                it.copy(state = CrossServerState.EXPIRED, note = "Expired after 10 minutes. Nothing was sent.")
            } else it
        }
        if (changed) scheduleCrossServerExpiry()
    }

    /** The cards for this pane's chat, on the server open now. */
    internal fun crossServerCardsFor(paneId: String): List<CrossServerCard> {
        val server = current?.id ?: return emptyList()
        return crossServerCards.filter { it.source.server == server && it.source.pane.id == paneId }
    }

    /**
     * Agents on other connected Fleet servers that the phone can relay to, whatever their status. The
     * card's choices and the frame's destination are resolved against these; sending still needs the
     * chosen one to be idle.
     */
    internal fun crossServerReachable(card: CrossServerCard): List<Session> =
        if (!crossServerSourceCurrent(card.source)) emptyList()
        else sessions().filter { it.server.id != card.source.server && relayPort(it.server.id)?.connectionIdentity != null &&
            hasStableOccupant(it.pane) }

    private fun destinationKey(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

    /** The reachable agents the frame's destination names: by "<agent> on <server>" as agents are told, else by the agent's name alone. */
    internal fun crossServerMatches(card: CrossServerCard): List<Session> {
        val reachable = crossServerReachable(card)
        val wanted = destinationKey(card.requested)
        val exact = reachable.filter { destinationKey(agentFacingName(it)) == wanted }
        if (exact.isNotEmpty()) return exact
        return reachable.filter { destinationKey(com.billmongan.herdrdroid.ui.sessionName(it.pane)) == wanted }
    }

    /** What the user may pick from: the matches when more than one, every reachable agent when none, nothing when exactly one. */
    internal fun crossServerChoices(card: CrossServerCard): List<Session> {
        val matches = crossServerMatches(card)
        return when (matches.size) {
            1 -> emptyList()
            0 -> crossServerReachable(card)
            else -> matches
        }
    }

    private fun updateCrossServerCard(id: String, change: (CrossServerCard) -> CrossServerCard) {
        crossServerCards = crossServerCards.map { if (it.id == id) change(it) else it }
    }

    internal fun chooseCrossServerDestination(card: CrossServerCard, choice: Session) {
        val now = crossServerCards.firstOrNull { it.id == card.id } ?: return
        if (now.state != CrossServerState.PENDING || choice !in crossServerChoices(now)) return
        // Another destination has not been tried: the card shows Approve again, not Retry or Force Send.
        updateCrossServerCard(card.id) { it.copy(choice = choice, note = null, refusal = null) }
        // A destination picked on the card is then asked about in the dialog, with the whole message.
        crossServerDeferred = crossServerDeferred - card.id
    }

    internal fun cancelCrossServerCard(card: CrossServerCard) {
        val now = crossServerCards.firstOrNull { it.id == card.id } ?: return
        if (now.state != CrossServerState.PENDING) return
        crossServerCodes.spend(card.id, crossServerClock())
        updateCrossServerCard(card.id) { it.copy(state = CrossServerState.CANCELLED, note = "Cancelled. Nothing was sent.") }
        scheduleCrossServerExpiry()
    }

    /** Takes a finished card off the chat. Its code stays spent. */
    internal fun closeCrossServerCard(card: CrossServerCard) {
        if (crossServerCards.firstOrNull { it.id == card.id }?.state in setOf(CrossServerState.PENDING, CrossServerState.SENDING)) return
        crossServerCards = crossServerCards.filter { it.id != card.id }
    }

    /**
     * What a Yes/No dialog (or a card's Approve) was shown and may approve: one proposal by its id
     * and code, to the destination shown. A dialog keeps the approval it was opened with, so it can
     * never approve a newer proposal, another code or a destination it did not show.
     */
    internal data class CrossServerApproval(val id: String, val code: String, val server: String, val pane: String)

    /** The approval for a pending card with a destination; null otherwise. */
    internal fun crossServerApprovalOf(card: CrossServerCard): CrossServerApproval? {
        val live = crossServerCards.firstOrNull { it.id == card.id } ?: return null
        if (live.state != CrossServerState.PENDING || live.code != card.code) return null
        val to = live.choice ?: return null
        return CrossServerApproval(live.id, live.code, to.server.id, to.pane.id)
    }

    /**
     * The proposal to ask about in a dialog in this pane's chat: the newest pending one with a
     * destination that the user has not put aside, only while its pane is open, the app is in front
     * and unlocked, and within its ten minutes. Older ones wait on their cards and come up in turn.
     */
    internal fun crossServerDialogFor(paneId: String): CrossServerCard? {
        val now = crossServerClock()
        return crossServerCardsFor(paneId).lastOrNull {
            it.state == CrossServerState.PENDING && it.choice != null && it.id !in crossServerDeferred &&
                now < it.deadline && crossServerSourceCurrent(it.source)
        }
    }

    /** Back on the dialog: the proposal stays on its card, pending, and its dialog does not come back by itself. */
    internal fun deferCrossServerDialog(card: CrossServerCard) {
        crossServerDeferred = crossServerDeferred + card.id
    }

    /**
     * Yes on the dialog or Approve on the card. The app supplies the frame's code itself to the
     * confirm path, nothing is typed anywhere, and the code never reaches a pane. Refused (false,
     * nothing sent) when the proposal is gone, finished, expired, has another code or destination
     * than [approval], or its pane is no longer the one open. True when delivery started.
     */
    internal fun approveCrossServer(approval: CrossServerApproval): Boolean {
        expireCrossServerCards()
        val card = crossServerCards.firstOrNull { it.id == approval.id } ?: return false
        if (card.code != approval.code || card.state != CrossServerState.PENDING) return false
        val to = card.choice ?: return false
        if (to.server.id != approval.server || to.pane.id != approval.pane) return false
        if (!crossServerSourceCurrent(card.source)) return false
        return confirmCrossServerCode(card.source.pane.id, approval.code)
    }

    /** 2.2.2: Force Send is offered on a pending card whose last try found the destination busy (working or background work). */
    internal fun crossServerForceOffered(card: CrossServerCard): Boolean {
        val live = crossServerCards.firstOrNull { it.id == card.id } ?: return false
        return live.state == CrossServerState.PENDING && live.choice != null && crossServerForceable(live.refusal, live.choice.pane.agent)
    }

    /**
     * 2.2.2: Send anyway on Force Send's dialog. The same binding as [approveCrossServer] (this
     * proposal's id, code and destination, its pane still open), and only while Force Send is
     * offered. It delivers at most once, the same way: the code is spent before anything is typed.
     * The relay then skips only its idle and background-work checks; every other check still runs.
     */
    internal fun forceCrossServer(approval: CrossServerApproval): Boolean {
        expireCrossServerCards()
        val card = crossServerCards.firstOrNull { it.id == approval.id } ?: return false
        if (card.code != approval.code || card.state != CrossServerState.PENDING || !crossServerForceOffered(card)) return false
        val to = card.choice ?: return false
        if (to.server.id != approval.server || to.pane.id != approval.pane) return false
        if (!crossServerSourceCurrent(card.source)) return false
        return confirmCrossServerCode(card.source.pane.id, approval.code, force = true)
    }

    /**
     * The confirm path (2.1.5's, no longer reachable from the composer): [code] is a pending card's
     * code in this pane's chat. The code is spent before anything is sent, so a card delivers at
     * most once, and its message goes through the guarded relay.
     */
    private fun confirmCrossServerCode(paneId: String, code: String, force: Boolean = false): Boolean {
        if (!com.billmongan.herdrdroid.herdr.CrossServerCode.valid(code)) return false
        expireCrossServerCards()
        val card = crossServerCardsFor(paneId).firstOrNull { it.code == code } ?: return false
        if (card.state != CrossServerState.PENDING || card.choice == null) return false
        updateCrossServerCard(card.id) { it.copy(state = CrossServerState.SENDING, note = "Checking and sending…") }
        crossServerCodes.spend(card.id, crossServerClock())
        if (force) com.billmongan.herdrdroid.diag.DiagLog.log("xserver",
            "${card.source.pane.id}: proposal forced to ${card.choice.server.id}/${card.choice.pane.id} (idle and background checks skipped)")
        viewModelScope.launch {
            var why: RelayRefusal? = null
            val result = try { deliverCrossServer(card, force) { why = it } } catch (c: kotlinx.coroutines.CancellationException) { throw c }
                catch (_: Exception) { RelayResult.REFUSED }
            finishCrossServer(card, result, why ?: RelayRefusal.FAILED.takeIf { result == RelayResult.REFUSED || result == RelayResult.DRAFT }, force)
        }
        return true
    }

    /**
     * 2.2.1: the card says which check stopped the message, on which server and pane. A refusal
     * typed nothing (every refusal is decided before the write's permit is claimed), so a transient
     * one (busy, a draft, a question, the link) leaves the proposal pending with its code unspent
     * and its ten minutes unchanged: Approve on the card tries once more. Its dialog does not pop
     * up again by itself. Sent or uncertain keeps the code spent, so nothing can go twice; so does
     * a refusal that can't pass for this proposal (a different session in either pane).
     */
    private fun finishCrossServer(card: CrossServerCard, result: RelayResult, why: RelayRefusal?, forced: Boolean = false) {
        val to = card.choice?.let { "${agentFacingName(it)} (pane ${it.pane.id})" } ?: "the destination"
        val how = if (forced) " (forced)" else ""
        if (why != null) com.billmongan.herdrdroid.diag.DiagLog.log("xserver",
            "${card.source.pane.id}: proposal$how not sent to ${card.choice?.server?.id}/${card.choice?.pane?.id}: ${why.name}")
        else com.billmongan.herdrdroid.diag.DiagLog.log("xserver", "${card.source.pane.id}: proposal$how ${result.name.lowercase()}")
        val retry = why != null && why.transient && crossServerCardsFor(card.source.pane.id).any { it.id == card.id }
        if (retry) crossServerCodes.release(card.id, crossServerClock())
        updateCrossServerCard(card.id) {
            when {
                why == null -> it.copy(state = when (result) {
                    RelayResult.SENT -> CrossServerState.SENT
                    RelayResult.UNCERTAIN -> CrossServerState.FAILED
                    RelayResult.REFUSED -> CrossServerState.REFUSED
                    RelayResult.DRAFT -> CrossServerState.DRAFT
                }, note = result.feedback)
                retry -> it.copy(state = CrossServerState.PENDING, refusal = why,
                    note = "Not sent to $to: ${why.why}. Nothing was typed. " + if (crossServerForceable(why, card.choice?.pane?.agent))
                        "Retry to try again when it is idle, or Force Send to type it in while it works, before the proposal expires."
                    else "Retry to try again before the proposal expires.")
                else -> it.copy(state = if (result == RelayResult.DRAFT) CrossServerState.DRAFT else CrossServerState.REFUSED,
                    note = "Not sent to $to: ${why.why}. Nothing was typed. This proposal can't be sent; ask the agent for a new one.")
            }
        }
        if (retry) crossServerDeferred = crossServerDeferred + card.id
        scheduleCrossServerExpiry()
    }

    /** The relay with all its checks: the source unchanged, the destination the same occupant and idle, then PromptRelay's own (no draft, size and content). */
    private suspend fun deliverCrossServer(card: CrossServerCard, force: Boolean, refused: (RelayRefusal) -> Unit): RelayResult {
        fun refuse(why: RelayRefusal): RelayResult { refused(why); return RelayResult.REFUSED }
        val chosen = card.choice ?: return refuse(RelayRefusal.UNREACHABLE)
        if (!crossServerSourceCurrent(card.source)) return refuse(RelayRefusal.SOURCE_CHANGED)
        val there = crossServerReachable(card).filter { it.server.id == chosen.server.id && it.pane.id == chosen.pane.id }
        val destination = there.singleOrNull { sameOccupant(it.pane, chosen.pane) }
            ?: return refuse(if (there.isNotEmpty()) RelayRefusal.REPLACED else RelayRefusal.UNREACHABLE)
        destinationRefusal(destination.server.id, destination.pane, force)?.let { return refuse(it) }
        val target = bindRemotePrompt(destination) ?: return refuse(RelayRefusal.LINK)
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        fun admitted() = job?.isActive != false && crossServerSourceCurrent(card.source) &&
            agentMessageDestinationCurrent(target.route.server, target.route.pane, force)
        val permit = WritePermit(::admitted)
        crossServerPermit = permit
        try {
            if (!crossServerSourceCurrent(card.source)) return refuse(RelayRefusal.SOURCE_CHANGED)
            destinationRefusal(target.route.server, target.route.pane, force)?.let { return refuse(it) }
            if (!admitted() || !remotePromptCurrent(target)) return refuse(RelayRefusal.STOPPED)
            val source = card.source
            val fresh = source.client.cardPane(source.pane.id, source.connection) ?: return refuse(RelayRefusal.SOURCE_CHANGED)
            if (!sameOccupant(fresh, source.pane)) return refuse(RelayRefusal.SOURCE_REPLACED)
            return promptRelay.send(target.route, card.text, authorizedWrite = permit, refused = refused, force = force) {
                admitted() && remotePromptCurrent(target)
            }
        } finally {
            permit.revoke()
            if (crossServerPermit === permit) crossServerPermit = null
        }
    }

    private fun agentMessageDestinationCurrent(serverId: String, expected: Pane, force: Boolean = false): Boolean =
        destinationRefusal(serverId, expected, force) == null

    /**
     * Why the watched destination may not be written to now, or null when it may (2.2.1: Claude's
     * background work alone does not hold it). 2.2.2: each reason on its own, and [force] (Claude
     * Code only) skips only WORKING; the pane must still be there with the same occupant.
     */
    private fun destinationRefusal(serverId: String, expected: Pane, force: Boolean = false): RelayRefusal? {
        val now = fleetStates[serverId]?.panes?.singleOrNull { it.id == expected.id } ?: return RelayRefusal.UNREACHABLE
        val forced = force && com.billmongan.herdrdroid.herdr.takesForcedInput(now.agent)
        return when {
            !sameOccupant(now, expected) -> RelayRefusal.REPLACED
            now.status == Status.NEEDS_YOU -> RelayRefusal.QUESTION
            now.backgroundBusy && !takesInputDuringBackgroundWork(now.agent) -> RelayRefusal.BACKGROUND
            now.status == Status.WORKING && !now.backgroundBusy -> if (forced) null else RelayRefusal.WORKING
            !idleForRelay(now) -> RelayRefusal.NOT_IDLE
            else -> null
        }
    }

    // ---- 2.2.7: cross-server pane reads (herdr/PaneRead.kt, ui/PaneReadPanel.kt) -----------------

    /** The captures, in the app's private storage only. */
    internal val paneReadStore by lazy {
        val app = getApplication<Application>()
        com.billmongan.herdrdroid.herdr.PaneReadStore(java.io.File(app.filesDir, "pane-reads"), shared = java.io.File(app.cacheDir, "shared"))
    }

    /** Tries of a pane read's attachment send to a busy pane, 2.5 s apart. */
    private val PANE_READ_SEND_TRIES = 8

    /** Another server's client, as the relay would reach it; null for this chat's own server, or when it is not connected. */
    private fun paneReadClient(serverId: String): Herdr? {
        if (locked || serverId == current?.id) return null
        val saved = servers.firstOrNull { it.id == serverId } ?: return null
        val watched = fleetStates[serverId] ?: return null
        if (watched.error != null || watched.server != saved || (!foreground && saved.overWebSocket)) return null
        return fleet?.herdr(serverId)?.takeIf { it.connectionIdentity != null }
    }

    private val paneReadPort = object : com.billmongan.herdrdroid.herdr.PaneReadRequests.Port<CardTarget> {
        override fun enabled() = agentMessagingEnabled && agentMessagingForeground && !locked
        override fun sourceCurrent(source: CardTarget) = crossServerSourceCurrent(source)
        override fun servers() = this@AppViewModel.servers.let { all -> all.map { s ->
            val label = com.billmongan.herdrdroid.store.agentServerLabel(s, all)
            com.billmongan.herdrdroid.herdr.PaneReadServer(
                s.id, listOfNotNull(s.name.trim().takeIf { it.isNotEmpty() }, label).distinct(), label,
                own = s.id == current?.id, connected = paneReadClient(s.id) != null,
                panes = fleetStates[s.id]?.panes.orEmpty(),
            )
        } }
        override suspend fun livePane(server: com.billmongan.herdrdroid.herdr.PaneReadServer, pane: Pane): Pane? {
            val h = paneReadClient(server.id) ?: return null
            return h.relayPane(pane.id, h.connectionIdentity ?: return null)
        }
        override suspend fun read(server: com.billmongan.herdrdroid.herdr.PaneReadServer, pane: Pane, lines: Int?): String {
            val h = paneReadClient(server.id) ?: throw HerdrError("not connected", 0)
            return if (lines == null) h.readVisible(pane.id, preserveGlyphs = true) else h.read(pane.id, lines, preserveGlyphs = true)
        }
        override suspend fun deliver(
            source: CardTarget, capture: com.billmongan.herdrdroid.herdr.PaneCapture, message: String, allowed: () -> Boolean,
        ): String? = deliverPaneRead(source, capture, message, allowed)
        override fun log(message: String) = com.billmongan.herdrdroid.diag.DiagLog.log("relay", message)
    }

    /**
     * 2.2.7 review: the attachment goes on the requesting server's own client and pane ([source]),
     * never "the server open now". [allowed] (the setting, foreground, unlock, the chat still open
     * as it was) and the client's connection are checked right before the upload and right before
     * each try of the send. The send is the guarded one (no draft, question, panel or queued message
     * there); a busy pane is tried a few times over about 20 seconds, then it gives up.
     */
    private suspend fun deliverPaneRead(
        source: CardTarget, capture: com.billmongan.herdrdroid.herdr.PaneCapture, message: String, allowed: () -> Boolean,
    ): String? {
        val h = source.client
        fun ok() = allowed() && h.connectionIdentity === source.connection && promptLaneClear(source.server, source.pane.id)
        if (!ok()) return "this chat changed before the attachment went"
        // The message box's own attachment path: the file goes to ~/.herdrdroid/inbox there, its path in the message,
        // checked by size and SHA-256 first, so the message never points at a missing or partial capture.
        val receipt = try {
            h.uploadVerified(capture.name, capture.text.toByteArray(Charsets.UTF_8))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "the capture couldn't be put on that computer whole (${com.billmongan.herdrdroid.diag.DiagLog.describe(e)})"
        }
        val text = "$message ${receipt.path}"
        val port = object : com.billmongan.herdrdroid.herdr.LocalPromptSend.Port {
            override suspend fun pane(id: String) =
                quietly(allowBackground = true) { if (it === h) it.cardPane(id, source.connection) else null }
            override suspend fun viewport(id: String) =
                quietly(allowBackground = true) { if (it === h) it.readStyled(id, sendReadLines(source.pane.agent)) else null }
            override fun blocked(fresh: Pane, viewport: String): Boolean {
                if ((fresh.backgroundBusy && !takesInputDuringBackgroundWork(fresh.agent)) || blocksNormalSend(fresh, viewport)) return true
                if (com.billmongan.herdrdroid.herdr.composerFooterSaysWorking(viewport)) return true
                // 2.2.7: a blank Oh My Pi or Copilot box is empty for a prompt, which clears it first.
                val line = com.billmongan.herdrdroid.herdr.inputLineOf(viewport, fresh.agent, menuWrite = true)
                if (line != "" || com.billmongan.herdrdroid.herdr.composerDraftOf(viewport, fresh.agent) != null) return true
                return holdReason(fresh.id, viewport) != null
            }
            override suspend fun send(fresh: Pane, text: String, permit: com.billmongan.herdrdroid.herdr.WritePermit) =
                h.sendPermitted(fresh, text, source.connection, permit)
        }
        repeat(PANE_READ_SEND_TRIES) { n ->
            if (n > 0) delay(2_500)
            if (!ok()) return "this chat changed before the message went (the file is in ~/.herdrdroid/inbox there)"
            val result = localPromptSend.send(source.server, com.billmongan.herdrdroid.herdr.Occupant.of(source.pane), text, ::ok, port)
            (result.failure as? LinkDown)?.let { if (herdr === h) linkLost(it) }
            when (result.outcome) {
                com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.SENT -> return null
                com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.REFUSED -> {}
                com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.UNCERTAIN -> return "HerdrDroid couldn't confirm it went; check the chat"
                com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.NO_IDENTITY -> return "this server doesn't report which session is in the pane"
                com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.CONNECTION_CHANGED -> return "the connection changed"
            }
        }
        return "the pane stayed busy (a draft, question, panel or turn was in the way)"
    }

    internal val paneReads by lazy {
        com.billmongan.herdrdroid.herdr.PaneReadRequests(paneReadPort, crossServerCodes, paneReadStore, viewModelScope) { crossServerClock() }
    }

    /** A read frame in the open pane, at its live bottom, while the app is in front and unlocked; refused on its card when the setting is off. */
    private fun notePaneReadFrames(owner: CardTarget?, recent: String) {
        if (owner == null || !agentMessagingForeground || locked || owner.client.backend != Backend.TUI) return
        val now = cardTarget(owner.pane.id) ?: return
        if (now.client !== owner.client || now.connection !== owner.connection || now.server != owner.server ||
            !sameOccupant(now.pane, owner.pane) || now.pane.scrollOffset != 0 || !hasStableOccupant(now.pane)) return
        val label = current?.let { com.billmongan.herdrdroid.store.agentServerLabel(it, servers) }.orEmpty()
        // Oh My Pi and pi mark no reply on screen: a frame from them counts only when their own chat log
        // shows it in their reply after your latest message (Codex's review); with no log, never.
        val agent = owner.pane.agent?.lowercase()
        val fromLog = agent in setOf("omp", "pi")
        val logged = fromLog && transcriptPane == owner.pane.id && transcriptState == TranscriptMode.ON
        val items = if (logged) transcriptItems else emptyList()
        paneReads.noteFrames(owner, owner.server, owner.pane.id, "${com.billmongan.herdrdroid.ui.sessionName(owner.pane)} on $label", recent, owner.pane.agent,
            trusted = { f -> !fromLog || (logged && com.billmongan.herdrdroid.herdr.replyHasReadFrame(items, f)) })
    }

    // ---- 2.1.4: telling agents about cross-server messaging ------------------------------------

    private val noticeLedger = com.billmongan.herdrdroid.herdr.NoticeLedger(
        { prefs.crossServerNoticeLedger }, { prefs.crossServerNoticeLedger = it })
    internal var noticeClock: () -> Long = System::currentTimeMillis
    internal val notifier = com.billmongan.herdrdroid.herdr.CrossServerNotifier(noticeLedger, { noticeClock() })
    /** Off in tests that drive [noticeTick] themselves. */
    internal var noticeLoopAllowed = true
    /**
     * The wait between notice passes. It suspends without holding the main thread: no work is
     * queued on the main looper while it waits. Tests replace it, so no real 30-second delay loop
     * runs in a test that does not ask for one.
     */
    internal var noticeTicker: suspend () -> Unit = { delay(NOTICE_TICK_MS) }
    private var noticeLoop: Job? = null
    private val noticeLock = Mutex()
    private val noticeForced = mutableSetOf<String>()

    private fun noticeActive() = agentMessagingEnabled && crossServerNotice && agentMessagingForeground && !locked

    /**
     * The notice loop runs only while cross-server messaging and the notice are both on, the app is
     * in the foreground and unlocked; any change away from that cancels it. Each pass reads Compose
     * state, so the pass itself runs on the main thread, but nothing runs between passes.
     */
    private fun updateNoticeLoop() {
        if (!noticeActive()) {
            noticeLoop?.cancel()
            noticeLoop = null
            synchronized(noticeForced) { noticeForced.clear() }
            return
        }
        if (!noticeLoopAllowed || noticeLoop?.isActive == true) return
        // The first pass is posted at once, so turning the feature on tells the agents, but it
        // never runs inside the call (a settings tap or a lifecycle callback) that started it.
        noticeLoop = viewModelScope.launch(Dispatchers.Main) {
            while (isActive && noticeActive()) {
                noticeTick()
                noticeTicker()
            }
        }
    }

    /** "Tell agents now": the next tick ignores the hourly limit for every agent, and only that. */
    fun tellAgentsNow() {
        if (!noticeActive()) return
        synchronized(noticeForced) { noticeForced += noticeRecipients().map { "${it.server.id}|${it.pane.id}" } }
        viewModelScope.launch { noticeTick() }
    }

    /** Whether the notice can reach [s]: an identifiable agent on a connected server. */
    private fun noticeReachable(s: Session): Boolean = hasStableOccupant(s.pane) && if (s.current)
        s.server.id == current?.id && link == Link.CONNECTED && herdr?.let { it.backend != Backend.CORE && it.connectionIdentity != null } == true
    else relayPort(s.server.id)?.connectionIdentity != null

    private fun noticeRecipients(): List<Session> =
        sessions().filter { !it.pane.isProbe && noticeReachable(it) }

    private fun noticeQuota(s: Session): QuotaReading? {
        val key = QuotaProfiles.key(s.pane.agent) ?: return null
        return (if (s.current) quotas else serverQuotas[s.server.id].orEmpty())[key]
    }

    /**
     * An agent as text other agents receive name it: its session name and the server's
     * agent-facing label (the server's name, or "server N"; never the account, host or port).
     */
    internal fun agentFacingName(s: Session): String =
        "${com.billmongan.herdrdroid.ui.sessionName(s.pane)} on ${com.billmongan.herdrdroid.store.agentServerLabel(s.server, servers)}"

    /** The candidates with the notice each would get: names of agents on other servers only. */
    internal fun noticeCandidates(): List<Pair<Session, com.billmongan.herdrdroid.herdr.CrossServerNotifier.Candidate>> {
        val all = noticeRecipients()
        return all.mapNotNull { s ->
            val others = all.filter { it.server.id != s.server.id }
                .map(::agentFacingName)
            val text = com.billmongan.herdrdroid.herdr.CrossServerNotice.text(others) ?: return@mapNotNull null
            val raw = if (s.current) pane(s.pane.id) ?: s.pane else s.pane
            val hold = !idleForRelay(raw) ||
                !promptLaneClear(s.server.id, s.pane.id) ||
                com.billmongan.herdrdroid.herdr.CrossServerNotice.quotaSkips(noticeQuota(s), crossServerNoticeQuota)
            s to com.billmongan.herdrdroid.herdr.CrossServerNotifier.Candidate(s.server.id, s.pane, text, hold)
        }
    }

    /** One pass: each due agent gets the notice through the guarded send paths, one at a time. */
    internal suspend fun noticeTick() {
        if (!noticeActive() || !noticeLock.tryLock()) return
        try {
            for ((s, c) in noticeCandidates()) {
                if (!noticeActive()) return
                val forced = synchronized(noticeForced) { c.key in noticeForced }
                val decision = notifier.decide(c, forced)
                if (decision != com.billmongan.herdrdroid.herdr.CrossServerNotifier.Decision.SEND) {
                    // Already sent: the forced tap has nothing left to do for this agent.
                    if (decision == com.billmongan.herdrdroid.herdr.CrossServerNotifier.Decision.ALREADY_SENT)
                        synchronized(noticeForced) { noticeForced -= c.key }
                    continue
                }
                synchronized(noticeForced) { noticeForced -= c.key }
                val result = sendNotice(s, c.text)
                when (result.outcome) {
                    NoticeOutcome.POSSIBLY_SENT -> {
                        // Persist before waiting for a receipt. Cancellation must not cause a replay.
                        notifier.sent(c)
                        if (result.confirm?.invoke() == true) notifier.sent(c, confirmed = true)
                    }
                    NoticeOutcome.HELD -> Unit
                    NoticeOutcome.REFUSED -> if (notifier.refused(c, forced)) noteSendProblem(s.server.id, s.pane.id,
                        "Cross-server notice was not sent to ${agentFacingName(s)}: ${result.why}. " +
                            "Tap Tell agents now in Settings to try again.")
                }
            }
        } finally {
            noticeLock.unlock()
        }
    }

    private enum class NoticeOutcome { POSSIBLY_SENT, HELD, REFUSED }
    private data class NoticeDelivery(
        val outcome: NoticeOutcome,
        val why: String = "",
        val confirm: (suspend () -> Boolean)? = null,
    )

    private suspend fun noticeConfirmation(
        pane: Pane,
        text: String,
        current: suspend () -> Boolean,
        read: suspend (String) -> com.billmongan.herdrdroid.transcript.TranscriptRead?,
    ): (suspend () -> Boolean)? {
        if (com.billmongan.herdrdroid.herdr.ruleAgent(pane.agent) !in readers ||
            com.billmongan.herdrdroid.herdr.LongText.plan(text) !is com.billmongan.herdrdroid.herdr.LongText.Plan.Single
        ) return null
        suspend fun <T> safely(action: suspend () -> T): T? = try {
            action()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
        val receipt = kotlinx.coroutines.withTimeoutOrNull(2_500) {
            com.billmongan.herdrdroid.herdr.NoticeReceipt.capture(pane.sessionId) { cursor ->
                if (safely(current) == true) safely { read(cursor) } else null
            }
        } ?: return null
        return {
            com.billmongan.herdrdroid.herdr.confirmNotice(receipt, text, { safely(current) == true }) { cursor ->
                safely { read(cursor) }
            }
        }
    }

    /** Keep ambiguous writes deduplicated, and count only proven pre-input refusals against retry. */
    private suspend fun sendNotice(s: Session, text: String): NoticeDelivery {
        val held = NoticeDelivery(NoticeOutcome.HELD)
        if (!s.current) {
            val route = promptRelay.bind(s.server.id, s.pane) ?: return held
            val confirm = noticeConfirmation(route.pane, text, {
                noticeActive() && promptRelay.isCurrent(route) &&
                    route.port.pane(route.pane.id, route.connection)?.let { sameOccupant(it, route.pane) } == true
            }) { cursor -> route.port.noticeHistory(route.pane, cursor, route.connection) }
            var refusal: com.billmongan.herdrdroid.herdr.RelayRefusal? = null
            return when (promptRelay.send(route, text, refused = { refusal = it }) {
                noticeActive() && promptRelay.isCurrent(route)
            }) {
                RelayResult.SENT, RelayResult.UNCERTAIN -> NoticeDelivery(NoticeOutcome.POSSIBLY_SENT, confirm = confirm)
                RelayResult.DRAFT, RelayResult.REFUSED ->
                    refusal?.takeIf { it == com.billmongan.herdrdroid.herdr.RelayRefusal.AGENT_NOT_FOUND }
                        ?.let { NoticeDelivery(NoticeOutcome.REFUSED, it.why) } ?: held
            }
        }
        val h = herdr ?: return held
        val connection = h.connectionIdentity ?: return held
        val server = s.server.id
        fun allowed() = noticeActive() && current?.id == server && herdr === h && h.connectionIdentity === connection &&
            link == Link.CONNECTED && promptLaneClear(server, s.pane.id)
        val port = object : com.billmongan.herdrdroid.herdr.LocalPromptSend.Port {
            override suspend fun pane(id: String) =
                quietly(allowBackground = true) { if (it === h) it.panes().singleOrNull { p -> p.id == id } else null }
            override suspend fun viewport(id: String) =
                quietly(allowBackground = true) { if (it === h) it.readStyled(id, sendReadLines(s.pane.agent)) else null }
            override fun blocked(fresh: Pane, viewport: String): Boolean {
                if ((fresh.backgroundBusy && !takesInputDuringBackgroundWork(fresh.agent)) ||
                    blocksNormalSend(fresh, viewport)) return true
                // 2.2.1 (Grok's review): background work no longer holds a Claude here, so a turn the
                // screen shows running ("esc to interrupt" under the box) is held, as the relay holds it.
                if (com.billmongan.herdrdroid.herdr.composerFooterSaysWorking(viewport)) return true
                // 2.2.7: a blank Oh My Pi or Copilot box is empty for a prompt, which clears it first.
                val line = com.billmongan.herdrdroid.herdr.inputLineOf(viewport, fresh.agent, menuWrite = true)
                if (line != "" || com.billmongan.herdrdroid.herdr.composerDraftOf(viewport, fresh.agent) != null) return true
                return holdReason(fresh.id, viewport) != null
            }
            override suspend fun send(fresh: Pane, text: String, permit: com.billmongan.herdrdroid.herdr.WritePermit) =
                h.sendPermitted(fresh, text, connection, permit)
            // 2.2.7: a notice too long to type goes as a checked file.
            override suspend fun upload(name: String, bytes: ByteArray) = h.uploadVerified(name, bytes)
        }
        val confirm = noticeConfirmation(s.pane, text, {
            noticeActive() && current?.id == server && herdr === h && h.connectionIdentity === connection &&
                h.relayPane(s.pane.id, connection)?.let { sameOccupant(it, s.pane) } == true
        }) { cursor -> h.noticeHistory(s.pane, cursor, transcriptScript, connection) }
        val result = localPromptSend.send(server, com.billmongan.herdrdroid.herdr.Occupant.of(s.pane), text, ::allowed, port)
        (result.failure as? LinkDown)?.let { if (herdr === h) linkLost(it) }
        if (result.failure?.let { com.billmongan.herdrdroid.herdr.promptTargetNotFound(it) } == true) {
            return NoticeDelivery(NoticeOutcome.REFUSED,
                com.billmongan.herdrdroid.herdr.RelayRefusal.AGENT_NOT_FOUND.why)
        }
        return when (result.outcome) {
            com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.SENT,
            com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.UNCERTAIN ->
                NoticeDelivery(NoticeOutcome.POSSIBLY_SENT, confirm = confirm)
            else -> held
        }
    }

    internal suspend fun sendVoiceMessage(target: VoiceTarget, text: String, allowed: () -> Boolean): String {
        fun allowedHere() = allowed() && voiceTarget() == target && promptLaneClear(target.server, target.pane)
        val refuse = "Not sent. Open the agent's chat and check that it is idle with no question, draft at the computer, or queued message."
        val h = target.client
        var draftSeen = false
        val port = object : com.billmongan.herdrdroid.herdr.LocalPromptSend.Port {
            override suspend fun pane(id: String) =
                quietly(allowBackground = true) { if (it === h) it.panes().singleOrNull { p -> p.id == id } else null }
            override suspend fun viewport(id: String) =
                quietly(allowBackground = true) { if (it === h) it.readStyled(id, sendReadLines(target.agent)) else null }
            override fun blocked(fresh: Pane, viewport: String): Boolean {
                if (blocksNormalSend(fresh, viewport)) return true
                // 2.2.7: a blank Oh My Pi or Copilot box is empty for a prompt, which clears it first.
                val line = com.billmongan.herdrdroid.herdr.inputLineOf(viewport, fresh.agent, menuWrite = true)
                if (!line.isNullOrEmpty() || com.billmongan.herdrdroid.herdr.composerDraftOf(viewport, fresh.agent) != null) {
                    draftSeen = true
                    return true
                }
                return line != "" || holdReason(fresh.id, viewport) != null
            }
            override suspend fun send(fresh: Pane, text: String, permit: com.billmongan.herdrdroid.herdr.WritePermit) =
                h.sendPermitted(fresh, text, target.connection, permit)
            // 2.2.7: a dictated message too long to type goes as a checked file.
            override suspend fun upload(name: String, bytes: ByteArray) = h.uploadVerified(name, bytes)
        }
        val result = localPromptSend.send(target.server, target.occupant, text, ::allowedHere, port)
        (result.failure as? LinkDown)?.let { if (herdr === h) linkLost(it) }
        return when (result.outcome) {
            com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.SENT -> "Message sent."
            com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.REFUSED ->
                if (draftSeen) "Not sent. Text is already typed in the agent's input box at the computer. Its chat shows the draft with Send draft and Clear draft." else refuse
            com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.NO_IDENTITY ->
                "Not sent. This server doesn't report which session is in the pane, so a voice message can't be checked against the one read back."
            com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.CONNECTION_CHANGED -> "Not sent. The SSH connection changed."
            com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.UNCERTAIN -> "Could not confirm delivery. Check the chat before trying again."
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

    internal data class VoiceCardRead(val prompt: Prompt?)

    internal fun voiceCardTarget(): CardTarget? = voiceTarget()?.let { cardTarget(it.pane) }

    internal fun voiceCardCurrent(target: CardTarget): Boolean =
        voiceTarget() != null && target.isCurrent(target.client)

    internal suspend fun readVoiceCard(target: CardTarget): VoiceCardRead? =
        readVoiceViewport(target.pane, target.client, target.connection, versionOf(target.pane.id)) {
            voiceCardCurrent(target)
        }

    /** If Chat already shows an actionable card, a spoken option must mean the same thing on it. */
    internal fun voiceCardFitsChat(target: CardTarget, live: Prompt): Boolean {
        if (!voiceCardCurrent(target) || voiceCardStillReading(target)) return false
        // 2.2.2: fails closed. The open pane's card must be confirmed and must mean the same as the
        // live read; with no confirmed, actionable card on the open pane, voice sends nothing.
        val displayed = openCardText(target.pane.id)?.let { cardParse(target.pane.id, it) }?.let { parsed ->
            val key = "${current?.id}|${target.pane.id}"
            val d = cardDecisions[key]
            parsed.takeIf { !com.billmongan.herdrdroid.herdr.needsConfirmation(it) ||
                (questionConfirm.seen(key) && d != null && d.parsed == it && d.verdict.shows == com.billmongan.herdrdroid.herdr.CardShows.CARD) }
        } ?: return false
        if (displayed.choices.isEmpty() || displayed.terminalReview) return false
        return cardAnswerStillFits(displayed, live)
    }

    // Fleet discovery is read-only. A drawer row or cached status alone cannot verify a card.
    internal suspend fun readListedVoiceCard(session: Session): VoiceCardRead? {
        val (server, pane) = session
        val client = clientFor(server.id) ?: return null
        val connection = client.connectionIdentity ?: return null
        return readVoiceViewport(pane, client, connection, versionOf(pane.id).takeIf { session.current }) {
            !locked && (foreground || !server.overWebSocket) && clientFor(server.id) === client &&
                client.connectionIdentity === connection &&
                if (session.current) current == server && link == Link.CONNECTED &&
                    pane(pane.id)?.sameCardOccupant(pane) == true
                else fleetStates[server.id]?.let { it.server == server && it.error == null &&
                    it.panes.singleOrNull { other -> other.id == pane.id }?.sameCardOccupant(pane) == true } == true
        }
    }

    private suspend fun readVoiceViewport(pane: Pane, client: Herdr, connection: Any, version: String?,
        current: () -> Boolean,
    ): VoiceCardRead? {
        if (!current() || client.backend != Backend.TUI || (pane.terminalId == null && pane.sessionId == null)) return null
        return try {
            val fresh = client.cardPane(pane.id, connection) ?: return null
            if (!current() || !pane.sameCardOccupant(fresh)) return null
            // 2.2.2: voice reads, speaks and answers only a confirmed card: the viewport is read again
            // until the same dialog shows twice (or more, for one with no footer), never from one read.
            val gate = com.billmongan.herdrdroid.herdr.QuestionConfirm()
            var card: Prompt? = null
            for (n in 0..com.billmongan.herdrdroid.herdr.FALLBACK_READS) {
                if (n > 0) delay(VOICE_CONFIRM_PAUSE_MS)
                val text = client.readCardViewport(pane.id, connection)
                if (!current()) return null
                val parsed = if (text.isBlank()) null else detectPrompt(text, fresh.status == Status.NEEDS_YOU, fresh.agent, version)
                    ?.takeIf { it.terminalReview || it.choices.isNotEmpty() }
                if (parsed == null) { if (n == 0) return VoiceCardRead(null); gate.decide("voice", null); continue }
                val v = gate.decide("voice", com.billmongan.herdrdroid.herdr.questionRead(text, parsed, fresh.agent, version, fresh.status == Status.NEEDS_YOU))
                if (v.shows == com.billmongan.herdrdroid.herdr.CardShows.CARD) { card = parsed; break }
            }
            VoiceCardRead(card)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // ---- The Questions view (2.2.8) -----------------------------------------------------------

    /**
     * 2.2.8: a card target for a pane on the open server while the Questions view is open. The same
     * client, connection, server, visit and occupant binding as the open pane's [cardTarget], so an
     * answer from the view goes through the same checks (fresh read, occupant, screen digest).
     * Leaving the view (or opening a pane) changes the visit, and a target made here goes stale.
     */
    internal fun questionsCardTarget(paneId: String): CardTarget? {
        if (screen != Screen.Questions || locked || link != Link.CONNECTED) return null
        val client = herdr ?: return null
        return CardTarget(client, client.connectionIdentity ?: return null, current?.id ?: return null,
            paneVisit, pane(paneId)?.takeUnless { it.isProbe } ?: return null, questionsVisit)
    }

    /** 2.2.8: the Questions view's visit: bumped on every way into or out of it, and when it is hidden. */
    private var questionsVisit = 0L

    /** The target an entry for [src] answers through: the open server's pane only, with the same occupant; else null. */
    internal fun questionTargetFor(src: com.billmongan.herdrdroid.questions.QuestionSource): CardTarget? =
        if (!src.current || src.serverId != current?.id) null
        else questionsCardTarget(src.pane.id)?.takeIf { it.pane.sameCardOccupant(src.pane) }

    /**
     * The panes needing you now, on the open server and the watched ones, as the drawer lists them;
     * and, as candidates, Oh My Pi and pi panes herdr calls working, whose approval or Ask dialog
     * waits while herdr says so (the pane's card shows them whatever herdr reports).
     */
    private fun questionSources(): List<com.billmongan.herdrdroid.questions.QuestionSource> =
        sessions().filterNot { it.pane.isProbe }.mapNotNull {
            val waiting = it.status == Status.NEEDS_YOU
            val candidate = !waiting && it.status == Status.WORKING && it.pane.agent?.lowercase() in QUESTION_CANDIDATE_AGENTS
            if (!waiting && !candidate) null
            else com.billmongan.herdrdroid.questions.QuestionSource(it.server.id, it.server.label, it.current, it.pane, candidate = candidate,
                connection = if (it.current && link == Link.CONNECTED) herdr?.connectionIdentity else null)
        }

    /**
     * One pane read for the Questions view, over the server's shared connection: the target bound
     * first (open server only), then the pane's own record and its live viewport, read until the same
     * dialog shows twice. Styled where the highlight is only a colour (OpenCode, Muse); a tall Claude
     * dialog rebuilt whole as its card does. Null when the read failed; [QuestionRead.unreadable] when
     * this pane can't be read at all; [QuestionRead.timedOut] when the dialog never read the same twice.
     */
    private suspend fun readQuestionSource(src: com.billmongan.herdrdroid.questions.QuestionSource): com.billmongan.herdrdroid.questions.QuestionRead? {
        val session = sessions().firstOrNull { it.server.id == src.serverId && it.pane.id == src.pane.id && it.current == src.current } ?: return null
        val (server, pane) = session
        if (pane.isProbe) return null
        val client = clientFor(server.id) ?: return null
        val connection = client.connectionIdentity ?: return null
        fun unreadable(why: String) = com.billmongan.herdrdroid.questions.QuestionRead(null, null, unreadable = why)
        if (client.backend != Backend.TUI) return unreadable("This computer's herdr can't show the pane's screen to the app. Open the pane to answer.")
        if (pane.terminalId == null && pane.sessionId == null) return unreadable("The app can't tell which agent is in this pane. Open the pane to answer.")
        val target = questionTargetFor(src)
        val version = versionOf(pane.id).takeIf { session.current }
        fun stillCurrent() = !locked && !probePane(server.id, pane.id) &&
            (foreground || !server.overWebSocket) && clientFor(server.id) === client &&
            client.connectionIdentity === connection &&
            if (session.current) current == server && link == Link.CONNECTED && pane(pane.id)?.sameCardOccupant(pane) == true
            else fleetStates[server.id]?.let { it.server == server && it.error == null &&
                it.panes.singleOrNull { other -> other.id == pane.id }?.sameCardOccupant(pane) == true } == true
        return try {
            if (!stillCurrent()) return null
            val fresh = client.cardPane(pane.id, connection)
                ?: return unreadable("The pane is scrolled back at the computer, so its question isn't on screen. Open the pane to answer.")
            if (!stillCurrent() || !pane.sameCardOccupant(fresh)) return null
            val agent = fresh.agent
            val blocked = fresh.status == Status.NEEDS_YOU
            val styled = com.billmongan.herdrdroid.herdr.isOpenCode(agent) || agent.equals("muse", true)
            val gate = com.billmongan.herdrdroid.herdr.QuestionConfirm()
            var reviewPreview: Prompt? = null
            for (n in 0..com.billmongan.herdrdroid.herdr.FALLBACK_READS) {
                if (n > 0) delay(VOICE_CONFIRM_PAUSE_MS)
                val raw = client.readStyledViewport(pane.id, connection)
                if (!stillCurrent()) return null
                var plain = com.billmongan.herdrdroid.herdr.stripAnsi(raw)
                var parsed = when {
                    plain.isBlank() -> null
                    agent == "pi" && !blocked -> activePiQuestion(plain, version)
                    else -> detectPrompt(if (styled) raw else plain, blocked, agent, version)
                }
                // A Claude dialog taller than the screen: rebuilt whole from the history, as its card is.
                if (parsed != null && parsed.topOffScreen && agent.equals("claude", true)) {
                    plain = com.billmongan.herdrdroid.herdr.completeTallDialog(plain, blocked, version) { k ->
                        if (stillCurrent()) client.readCardHistory(pane.id, k, connection) else null
                    }
                    if (!stillCurrent()) return null
                    parsed = detectPrompt(plain, blocked, agent, version)
                }
                parsed = parsed?.forCard()?.takeIf { it.terminalReview || it.choices.isNotEmpty() }
                reviewPreview = if (agent.equals("claude", true))
                    com.billmongan.herdrdroid.herdr.claudeReviewPreview(plain, parsed) else null
                if (parsed == null) {
                    reviewPreview?.let { return com.billmongan.herdrdroid.questions.QuestionRead(it, null) }
                    // The first read shows no dialog: confirmed none. Later, a dialog that came and went keeps being read.
                    if (n == 0) return com.billmongan.herdrdroid.questions.QuestionRead(null, null)
                    gate.decide("questions", null)
                    continue
                }
                val v = gate.decide("questions", com.billmongan.herdrdroid.herdr.questionRead(plain, parsed, agent, version, blocked))
                if (v.shows == com.billmongan.herdrdroid.herdr.CardShows.CARD) {
                    return com.billmongan.herdrdroid.questions.QuestionRead(parsed, target.takeUnless { parsed.historyReviewOnly })
                }
            }
            reviewPreview?.let { com.billmongan.herdrdroid.questions.QuestionRead(it, null) }
                ?: com.billmongan.herdrdroid.questions.QuestionRead(null, null, timedOut = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal val questionsFeed by lazy { com.billmongan.herdrdroid.questions.QuestionsFeed(
        viewModelScope,
        sources = ::questionSources,
        read = ::readQuestionSource,
        inFlight = { it.current && it.serverId == current?.id && answerInFlight(it.pane.id) },
    ) }

    /**
     * 2.2.8: why a Questions-view answer through [target] was refused, said plainly when the reason is
     * that another agent or session is in the pane now (the target's occupant no longer there); else [message].
     */
    internal fun questionsRefusal(target: CardTarget?, message: String): String {
        // Only a refusal before anything was sent is restated: a "may have been sent" stays exactly as it is.
        if (!message.startsWith("Not sent.") && message != CARD_UPDATED_NOTE) return message
        val now = target?.let { questionsCardTarget(it.pane.id) }
        return if (target != null && now != null && !now.pane.sameCardOccupant(target.pane)) OCCUPANT_REFUSAL else message
    }

    /**
     * The drawer's Questions badge: each pane needing you is one open question, or the questions the
     * view lists, when more (approvals it found on panes herdr calls working). The view's list is
     * forgotten when it is hidden.
     */
    fun openQuestionCount(): Int = maxOf(sessions().count { !it.pane.isProbe && it.status == Status.NEEDS_YOU },
        visibleQuestions().count { it.state != com.billmongan.herdrdroid.questions.EntryState.ANSWERED })

    internal fun visibleQuestions() = questionsFeed.entries.filterNot { probePane(it.source.serverId, it.source.pane.id) }

    internal fun probePane(serverId: String, paneId: String): Boolean =
        (if (serverId == current?.id) pane(paneId) else fleetStates[serverId]?.panes?.singleOrNull { it.id == paneId })
            ?.isProbe == true

    /** The Questions view is on screen (and the app in front): reads run, and the watched servers are read as often as with the drawer out. */
    internal fun questionsShown() {
        questionsFeed.show()
        tuneFleet()
        fleet?.poke()
    }

    internal fun questionsHidden() {
        if (approveAllPending?.target?.questionsVisit?.let { it >= 0 } == true) cancelApproveAll()
        questionsVisit++
        questionsFeed.hide()
        tuneFleet()
    }

    /** The pause between voice's confirmation reads of a card (2.2.2). */
    private val VOICE_CONFIRM_PAUSE_MS = 300L

    private fun beginAnswer(key: Pair<String, String>): Boolean {
        if (key in answeringCards) return false
        answeringCards = answeringCards + key
        return true
    }

    internal data class CardTarget(val client: Herdr, val connection: Any, val server: String, val visit: Long, val pane: Pane,
        /** 2.2.8: the Questions view's visit a target made there belongs to; -1 for the open pane's own card. */
        val questionsVisit: Long = -1L,
    )

    internal fun cardTarget(paneId: String): CardTarget? {
        if ((screen as? Screen.Agent)?.paneId != paneId || locked || link != Link.CONNECTED) return null
        val client = herdr ?: return null
        return CardTarget(client, client.connectionIdentity ?: return null, current?.id ?: return null,
            paneVisit, pane(paneId) ?: return null)
    }

    private fun Pane.sameCardOccupant(other: Pane) = id == other.id && agent == other.agent &&
        sessionId == other.sessionId && terminalId == other.terminalId && tabId == other.tabId &&
        workspaceId == other.workspaceId && cwd == other.cwd

    // 2.2.8: a card answered from the Questions view is current only while that view stays open.
    private fun CardTarget.isCurrent(h: Herdr): Boolean = client === h && (cardTarget(pane.id) ?: questionsCardTarget(pane.id))?.let {
        it.client === client && it.connection === connection && it.server == server && it.visit == visit && it.questionsVisit == questionsVisit &&
            it.pane.sameCardOccupant(pane)
    } == true

    private suspend fun CardTarget.freshAnswer(h: Herdr, shown: Prompt): Prompt? = freshAnswerGuarded(h, shown)?.first

    /**
     * [freshAnswer] with the guard its keys are sent under (2.2.7, Bill: "check right before
     * send"): the occupant and screen digests of the very reads the card was checked against.
     * The guard is null on herdr-core, which has no such reads.
     */
    private suspend fun CardTarget.freshAnswerGuarded(h: Herdr, shown: Prompt,
        /** Whether the fresh card is still the one shown; the dialog's own rule for Oh My Pi's Submit steps. */
        fits: (Prompt, Prompt) -> Boolean = ::cardAnswerStillFits,
    ): Pair<Prompt, com.billmongan.herdrdroid.herdr.CardGuard?>? {
        if (shown.terminalReview || shown.historyReviewOnly || !isCurrent(h) ||
            shown.guardedShortcutApproval && (h.backend != Backend.TUI || !hasStableOccupant(pane))) return null
        val guardedChoice = shown.guardedShortcutApproval || shown.hermesClarify || shown.openCodeQuestionFrame != null
        if (guardedChoice && (!foreground || h.backend != Backend.TUI || pane.scrollOffset != 0 ||
                !com.billmongan.herdrdroid.herdr.hasStableOccupant(pane))) return null
        val (currentPane, occupant) = h.cardPaneGuarded(pane.id, connection) ?: return null
        if (!isCurrent(h) || !pane.sameCardOccupant(currentPane)) return null
        // The last read here; the keys then go with a check of this same read on the computer
        // ([Herdr.sendCardInput]), which types nothing if the pane or its screen changed since.
        if (isOpenCode(pane.agent) && currentPane.scrollOffset != 0) return null
        // Muse's quota warning shows its highlight only in colour (2.1.0): read with styles to find it.
        val raw = h.readStyledViewport(pane.id, connection)
        val text = (if (isOpenCode(pane.agent) || shown.museQuota) raw
            else com.billmongan.herdrdroid.herdr.stripAnsi(raw)).takeUnless { it.isBlank() } ?: return null
        if (!isCurrent(h) || guardedChoice && !foreground) return null
        val live = if (pane.agent == "pi" && h.backend == Backend.TUI &&
            (pane.status == Status.WORKING || currentPane.status == Status.WORKING)) activePiQuestion(text, versionOf(pane.id))
        else detectPrompt(text, currentPane.status == Status.NEEDS_YOU, currentPane.agent, versionOf(pane.id))
        // Scrollback may explain a command, but only the live, complete card can authorize input.
        val guard = occupant?.let { com.billmongan.herdrdroid.herdr.CardGuard(it,
            com.billmongan.herdrdroid.herdr.cardGuardScreen(raw, live, currentPane.agent)) }
        return live?.takeIf { !it.terminalReview && !it.historyReviewOnly && fits(shown, it) }?.let { it to guard }
    }

    internal fun answerCardKey(target: CardTarget?, prompt: Prompt, key: String, label: String,
        /** 2.2.8: told why nothing was sent (the Questions view shows it on the entry). */
        feedback: ((String) -> Unit)? = null,
    ) {
        if (prompt.guardedShortcutApproval) return
        // OpenCode's question dialog (2.1.2): Esc dismisses it; no other key goes in from the card.
        if (prompt.openCodeQuestionFrame != null && key != "esc") { flash("Not sent. Refresh the question or open the terminal to answer."); feedback?.invoke("Not sent. Refresh the question or open the terminal to answer."); return }
        var guard: com.billmongan.herdrdroid.herdr.CardGuard? = null
        answerCardInput(target, prompt, label, guarded = prompt.openCodeQuestionFrame != null, onGuard = { guard = it }, feedback = feedback) { h, t, fresh ->
            sendCheckedCardInput(h, t.server, t.pane.id, fresh, com.billmongan.herdrdroid.herdr.CardInput.Keys(listOf(key)), guard, t.connection,
                remembered = false)
            true
        }
    }

    /**
     * 2.2.7 (Bill: "implement a fallback/pure shell version for when python3 is missing ... and
     * silently auto fail over to it with a notification toast"): the servers found this app session
     * to have no python3. Their card answers go straight to the shell check; each is told once.
     */
    private val noPython3: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    internal fun shellCheckFallback(server: String) {
        if (!noPython3.add(server)) return
        val label = current?.takeIf { it.id == server }?.label ?: servers.firstOrNull { it.id == server }?.label ?: "this computer"
        com.billmongan.herdrdroid.diag.DiagLog.log("answer", "server=${com.billmongan.herdrdroid.diag.LinkDiag.serverTag(server)} card-check=shell reason=no-python3")
        val text = shellCheckToast(label)
        viewModelScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(getApplication<Application>(), text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 2.2.7 (Bill: "not get stuck"): Submit on Oh My Pi's Ask dialog. Down, one step at a time,
     * each sent under the check of the read before it, until a fresh read shows the highlight on
     * Submit (the same dialog, every question answered); then Enter there. Down only moves the
     * highlight; nothing is answered or sent before Enter on Submit.
     */
    private suspend fun submitOmpAsk(h: Herdr, t: CardTarget, shown: Prompt, first: Prompt,
        firstGuard: com.billmongan.herdrdroid.herdr.CardGuard?): Boolean {
        val dialog = shown.ompAsk ?: return false
        // Codex's review: Submit only answers the app can tell apart on every read (no typed Other, none missing).
        if (!dialog.verifiable) return false
        // 2.2.7 (Codex's review): the same questions with the same answers as at the tap, or nothing is sent.
        fun same(a: Prompt, b: Prompt) = b.ompAsk?.questions == a.ompAsk?.questions && a.ompAsk != null && b.ompAsk != null && com.billmongan.herdrdroid.herdr.OmpAsk.sameAnswers(a.ompAsk!!, b.ompAsk!!) &&
            b.ompAsk?.allAnswered == true
        var live = first
        var guard = firstGuard
        repeat(dialog.questions.size * 12 + 4) {
            val state = live.ompAsk ?: return false
            if (!state.allAnswered) return false
            if (state.onSubmit) {
                sendCheckedCardInput(h, t.server, t.pane.id, live, com.billmongan.herdrdroid.herdr.CardInput.Keys(listOf("enter")), guard, t.connection, remembered = false)
                return true
            }
            sendCheckedCardInput(h, t.server, t.pane.id, live, com.billmongan.herdrdroid.herdr.CardInput.Keys(listOf("down")), guard, t.connection, remembered = false)
            delay(250)
            val next = t.freshAnswerGuarded(h, shown, ::same) ?: run { readPane(t.pane.id); return false }
            live = next.first
            guard = next.second
        }
        return false
    }

    /**
     * 2.2.7 (Bill, "check right before send"): a card's answer through [Herdr.sendCardInput]. When
     * the check on the computer typed nothing, the card is no longer held as answered (a new tap on
     * the same question can go), and the error says so: [answerOnce] reports it and reads the card again.
     */
    private suspend fun sendCheckedCardInput(
        h: Herdr, server: String, paneId: String, fresh: Prompt, input: com.billmongan.herdrdroid.herdr.CardInput,
        guard: com.billmongan.herdrdroid.herdr.CardGuard?, connection: Any, permit: com.billmongan.herdrdroid.herdr.WritePermit? = null,
        remembered: Boolean = true,
    ) {
        try {
            h.sendCardInput(paneId, input, guard, connection, permit, shellOnly = server in noPython3,
                onShellFallback = { shellCheckFallback(server) })
        } catch (e: Exception) {
            if (remembered && (e is com.billmongan.herdrdroid.herdr.CardChanged || e is com.billmongan.herdrdroid.herdr.CardCheckFailed) &&
                answeredCards[server to paneId]?.first == fresh) answeredCards.remove(server to paneId)
            throw e
        }
    }

    /**
     * 2.2.0: each question's text as the app saw it, per dialog (server, pane, occupant and the
     * dialog's tab titles), so a pill tap back to a question is checked against what it showed.
     */
    private val pillHeadings = LinkedHashMap<List<Any?>, MutableMap<Int, String>>()

    /**
     * 2.2.0 (Bill: "let me click on the pill buttons for multi question menus to go back to prior
     * questions or forth to other questions to revise before submitting"): moves the dialog the
     * card [shown] was read from to its question [index] with the dialog's own keys
     * ([com.billmongan.herdrdroid.herdr.QuestionPills]), bound to the card's pane, server,
     * connection and occupant like an answer. Never Enter, Esc or a digit; nothing is answered or
     * submitted. Refused while the pane shows a draft, a usage screen or another dialog.
     */
    internal fun moveToQuestion(target: CardTarget?, shown: Prompt, index: Int, title: String) {
        val pills = com.billmongan.herdrdroid.herdr.QuestionPills
        fun refuse(why: String) {
            com.billmongan.herdrdroid.diag.DiagLog.log("answer", "${target?.pane?.id}: question pill ${index + 1} not moved: $why")
            flash("Not moved: $why. Refresh the question or open the terminal.")
        }
        if (target == null || !pills.tappable(target.pane.agent, shown)) { refuse("the question can't be moved from here"); return }
        val id = target.pane.id
        if (paneDraft?.target?.pane?.id == id) { refuse("the pane has a draft"); return }
        if (usageOverlayFor(id) != null) { refuse("a usage screen is open"); return }
        val tabs = shown.shownParts().tabs
        val dialog = listOf(target.server, id, com.billmongan.herdrdroid.herdr.Occupant.of(target.pane), tabs.map { it.title })
        val headings = pillHeadings.getOrPut(dialog) { mutableMapOf() }
        while (pillHeadings.size > 16) pillHeadings.remove(pillHeadings.keys.first())
        val agentName = pane(id)?.agentName ?: "the agent"
        com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$id: tapped question pill ${index + 1} \"${title.take(40)}\"")
        answerOnce(id) { h ->
            var blocked = false
            var pillGuard: com.billmongan.herdrdroid.herdr.CardGuard? = null
            fun current() = target.isCurrent(h) && paneDraft?.target?.pane?.id != id && usageOverlayFor(id) == null
            val outcome = pills.move(
                target.pane.agent, shown, index, title, headings,
                read = read@{
                    if (!current()) return@read null
                    val (live, occupant) = h.cardPaneGuarded(id, target.connection) ?: return@read null
                    if (!current() || !target.pane.sameCardOccupant(live) || live.scrollOffset != 0) return@read null
                    blocked = live.status == Status.NEEDS_YOU
                    val raw = h.readStyledViewport(id, target.connection)
                    // 2.2.8: each pill key goes under the check of the read just before it.
                    pillGuard = occupant?.let { com.billmongan.herdrdroid.herdr.CardGuard(it, com.billmongan.herdrdroid.herdr.cardScreenDigest(raw, live.agent)) }
                    com.billmongan.herdrdroid.herdr.stripAnsi(raw).takeUnless { it.isBlank() || !current() }
                },
                detect = { detectPrompt(it, blocked, target.pane.agent, versionOf(id)) },
                stillCurrent = ::current,
                send = { keys -> sendCheckedCardInput(h, target.server, id, shown, com.billmongan.herdrdroid.herdr.CardInput.Keys(keys), pillGuard, target.connection, remembered = false) },
                pause = { delay(200) },
            )
            when (outcome) {
                is com.billmongan.herdrdroid.herdr.QuestionPills.Outcome.Moved -> com.billmongan.herdrdroid.diag.DiagLog.log("answer",
                    "$id: moved to question pill ${index + 1} with ${outcome.keys.joinToString(" ") { keyName(it) }}")
                com.billmongan.herdrdroid.herdr.QuestionPills.Outcome.Already -> Unit
                is com.billmongan.herdrdroid.herdr.QuestionPills.Outcome.Refused -> refuse(outcome.why)
                is com.billmongan.herdrdroid.herdr.QuestionPills.Outcome.Missed -> {
                    com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$id: sent ${outcome.keys.joinToString(" ") { keyName(it) }} " +
                        "for question pill ${index + 1}, but the screen did not show it; no more keys sent")
                    flash("$agentName did not show \"${title.take(30)}\". The card shows what is on screen now.")
                }
            }
            readPane(id)
        }
    }

    private fun answerCardInput(target: CardTarget?, shown: Prompt, label: String, delivered: () -> Unit = {},
        /** OpenCode's question dialog (2.1.2): allowed through [freshAnswer]'s guarded checks. */
        guarded: Boolean = false,
        /** 2.2.7: told the guard of the fresh read, for a [send] that goes through [Herdr.sendCardInput]. */
        onGuard: (com.billmongan.herdrdroid.herdr.CardGuard?) -> Unit = {},
        /** 2.2.8: told why nothing was sent. */
        feedback: ((String) -> Unit)? = null,
        send: suspend (Herdr, CardTarget, Prompt) -> Boolean,
    ) {
        val refused = "Not sent. Refresh the question or open the terminal to answer."
        if (target == null || shown.terminalReview || shown.hermesClarify || (shown.openCodeQuestionFrame != null && !guarded)) { flash(refused); feedback?.invoke(refused); return }
        val id = target.pane.id
        answerOnce(id, feedback) { h ->
            val checked = target.freshAnswerGuarded(h, shown)
            val fresh = checked?.first
            onGuard(checked?.second)
            // 2.2.8: the dialog on screen isn't the one the card shows: the card shows it now instead.
            if (fresh == null) { feedback?.invoke(CARD_UPDATED_NOTE); cardUpdated(target.server, id); return@answerOnce }
            if (!send(h, target, fresh)) { flash(refused); feedback?.invoke(refused); return@answerOnce }
            delivered()
            noteAnswer(id, shown, label, serverId = target.server)
            refreshAnsweredModel(target, fresh)
            readPane(id)
        }
    }

    private fun answerOnce(paneId: String, feedback: ((String) -> Unit)? = null, run: suspend (Herdr) -> Unit) {
        val server = current?.id ?: run {
            val message = "Not sent. No server is open."
            flash(message)
            feedback?.invoke(message)
            return
        }
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
            } catch (e: com.billmongan.herdrdroid.herdr.CardChanged) {
                // 2.2.7 (Bill, "check right before send"): nothing was typed. 2.2.8 (Bill: "I don't like
                // the 'question changed, won't send'"): the card shows what is on screen now, with its buttons.
                com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$paneId: the dialog's rows differed at the send; nothing typed, card updated")
                feedback?.invoke(CARD_UPDATED_NOTE)
                if (herdr === client && link == Link.CONNECTED) cardUpdated(server, paneId)
            } catch (e: com.billmongan.herdrdroid.herdr.DeliveryUncertain) {
                // 2.2.8 (Codex's re-review 6): the check passed and the keys began, then failed: never resent.
                com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$paneId: the keys failed after the check passed; they may have gone in")
                flash(CARD_MAY_HAVE_SENT)
                feedback?.invoke(CARD_MAY_HAVE_SENT)
                if (herdr === client && link == Link.CONNECTED) readPane(paneId)
            } catch (e: com.billmongan.herdrdroid.herdr.OccupantChanged) {
                // 2.2.8: another agent or session is in the pane: nothing typed, and no retry.
                com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$paneId: another occupant is in the pane; not sent")
                val message = OCCUPANT_REFUSAL
                flash(message)
                feedback?.invoke(message)
                if (herdr === client && link == Link.CONNECTED) readPane(paneId)
            } catch (e: com.billmongan.herdrdroid.herdr.CardCheckFailed) {
                com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$paneId: the check before the keys failed; not sent")
                val message = "Not sent. The check on the computer failed. Refresh the question or open the terminal to answer."
                flash(message)
                feedback?.invoke(message)
                if (herdr === client && link == Link.CONNECTED) readPane(paneId)
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

    /**
     * Bill (2026-11-25): a tapped approve-from-now-on option waiting on "Approve everything from now on?"
     * ([com.billmongan.herdrdroid.herdr.approveAllAsk]); nothing is sent until [confirmApproveAll].
     */
    internal data class ApproveAllPending(
        val paneId: String, val serverId: String?, val prompt: Prompt, val choice: Choice, val target: CardTarget?,
        val ask: com.billmongan.herdrdroid.herdr.ApproveAllAsk, val feedback: ((String) -> Unit)?,
    )
    internal var approveAllPending by mutableStateOf<ApproveAllPending?>(null)
        private set

    /** Cancel on the confirmation: nothing is sent. */
    fun cancelApproveAll() { approveAllPending = null }

    /**
     * Confirm on the confirmation: the option goes as any card answer does, under the same check of the
     * dialog right before its keys, so a label that changed since the card is no send: the card is
     * redrawn. A label the app can't show whole or reach is never sent from here.
     */
    fun confirmApproveAll() {
        val p = approveAllPending ?: return
        approveAllPending = null
        if (p.ask.reason != null || p.ask.keys == null || p.serverId != current?.id) return
        answerChoice(p.paneId, p.prompt, p.choice, target = p.target, feedback = p.feedback, confirmed = true)
    }

    internal fun answerChoice(paneId: String, prompt: Prompt, choice: Choice,
        voiceAllowed: (() -> Boolean)? = null,
        target: CardTarget? = cardTarget(paneId), confirmed: Boolean = false,
        feedback: ((String) -> Unit)? = null,
    ) {
        // The answer goes into the history only once it was sent (1.3.2, from a code review).
        if (prompt.terminalReview) return
        if (target != null && target.pane.id != paneId) {
            flash("Not sent. The question belongs to another pane."); feedback?.invoke("Not sent. The question belongs to another pane."); return
        }
        if (choice.keys == com.billmongan.herdrdroid.herdr.OmpAsk.SUBMIT) {
            if (voiceAllowed != null) { feedback?.invoke("Open Chat to submit the answers."); return }
            var guard: com.billmongan.herdrdroid.herdr.CardGuard? = null
            answerCardInput(target, prompt, choice.label, onGuard = { guard = it }, feedback = feedback) { h, t, fresh -> submitOmpAsk(h, t, prompt, fresh, guard) }
            return
        }
        if (choice.keys.startsWith("grok-privacy:") && voiceAllowed == null) {
            answerCardInput(target, prompt, choice.label, feedback = feedback) { h, t, fresh ->
                if (choice !in fresh.choices) return@answerCardInput false
                answerGrokPrivacy(h, paneId, optIn = choice.keys == com.billmongan.herdrdroid.herdr.GROK_PRIVACY_IN,
                    connection = t.connection)?.let { throw IllegalStateException(it) }
                viewModelScope.launch { refreshPanes() }
                true
            }
            return
        }
        fun report(message: String) { flash(message); feedback?.invoke(message) }
        // Bill (2026-11-25): an approve-from-now-on option asks "Approve everything from now on?" first;
        // voice never answers one. One the app can't show whole or reach is never sent.
        val ask = com.billmongan.herdrdroid.herdr.approveAllAsk(prompt, choice)
        if (ask != null && (!confirmed || voiceAllowed != null)) {
            if (voiceAllowed != null) { report(ask.reason ?: "Not sent by voice: it approves from now on. Use the card."); return }
            approveAllPending = ApproveAllPending(paneId, current?.id, prompt, choice, target, ask, feedback)
            return
        }
        com.billmongan.herdrdroid.herdr.blockedChoiceReason(choice)?.let { report(it); return }
        if (prompt.clipped) { report(CLIPPED_NOTE); return }
        val agentName = pane(paneId)?.agentName ?: "the agent"
        com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$paneId: tapped \"${choice.label.take(60)}\" (option ${choice.keys}, highlighted ${prompt.selected})")
        answerOnce(paneId, feedback) { h ->
            // Match the displayed card, but calculate its keys from the current highlight.
            val checked = target?.freshAnswerGuarded(h, prompt)
            val fresh = checked?.first
            val guard = checked?.second
            if (target == null) {
                report("Not sent. Refresh the question or open the terminal to answer.")
                readPane(paneId)
                return@answerOnce
            }
            if (fresh == null || (choice !in fresh.choices && choice != fresh.claudeCheckboxAction) ||
                fresh.clipped || com.billmongan.herdrdroid.herdr.blockedChoiceReason(choice) != null) {
                // 2.2.8: the dialog on screen isn't the one the card shows: the card shows it now instead.
                feedback?.invoke(CARD_UPDATED_NOTE)
                cardUpdated(target.server, paneId)
                return@answerOnce
            }
            val keys = if (voiceAllowed == null) com.billmongan.herdrdroid.herdr.menuKeys(fresh, choice)
                else com.billmongan.herdrdroid.voice.voiceCardKeys(fresh, choice)
            if ((fresh.claudeAsk || fresh.claudeCheckboxAction != null) && keys == null) {
                report("Not sent. Open Terminal to verify the selected row.")
                return@answerOnce
            }
            if (fresh.guardedShortcutApproval && (guard == null || keys == null || voiceAllowed != null ||
                    fresh.agyApproval && keys != listOf("ctrl+k"))) {
                report("Not sent. Open Terminal to verify the approval.")
                return@answerOnce
            }
            if (fresh.hermesClarify && (keys == null || voiceAllowed != null)) {
                report("Not sent. Open Chat to choose an answer.")
                return@answerOnce
            }
            if (target.pane.agent == "claude" && choice.label.equals("Submit answers", true) && keys?.lastOrNull() != "enter") {
                report("Not sent. Open Terminal to verify the selected submit row.")
                return@answerOnce
            }
            // Muse's quota warning (2.1.0): only arrows from a highlight seen in the live styled read,
            // never a typed digit.
            if (fresh.museQuota && keys == null) {
                report("Not sent. The app couldn't see which option Muse has highlighted. Open Terminal to choose.")
                return@answerOnce
            }
            if (fresh.openCodePermission && keys == null) {
                report("Not sent. Open Terminal to verify the selected permission button.")
                return@answerOnce
            }
            if (voiceAllowed != null && (!voiceAllowed() || keys == null)) {
                report("The question changed. No answer was sent.")
                return@answerOnce
            }

            val age = recentAnswerAge(target.server, paneId) { cardAnswerStillFits(it, fresh) }
            if (age != null) {
                com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$paneId: this card was answered ${age / 1000} s ago; not sent twice")
                withContext(Dispatchers.Main) { report("Already answered. Waiting for $agentName.") }
                readPane(paneId)
                return@answerOnce
            }

            // A transport error can leave delivery uncertain. Suppress another send to this same
            // card even when no receipt came back; a distinct live card can still be answered.
            val connection = target.connection
            if (voiceAllowed != null) {
                val key = target.server to paneId
                val permit = writeGate.open(key)
                if (permit == null) { report("Not sent. This pane is closing."); return@answerOnce }
                try {
                    if (!voiceAllowed()) { report("No answer was sent."); return@answerOnce }
                    rememberCardAnswer(target.server, paneId, fresh)
                    sendCheckedCardInput(h, target.server, paneId, fresh,
                        com.billmongan.herdrdroid.herdr.CardInput.Keys(checkNotNull(keys)), guard, connection, permit)
                } catch (_: com.billmongan.herdrdroid.herdr.WriteRevoked) {
                    report("Not sent. Voice control stopped or the pane closed.")
                    return@answerOnce
                } finally {
                    writeGate.finish(key, permit)
                }
            } else {
                rememberCardAnswer(target.server, paneId, fresh)
                sendCheckedCardInput(h, target.server, paneId, fresh,
                    if (keys != null) com.billmongan.herdrdroid.herdr.CardInput.Keys(keys)
                    else com.billmongan.herdrdroid.herdr.CardInput.Text(choice.keys, choice.pressEnter), guard, connection)
            }
            val sent = keys?.joinToString(" ") { keyName(it) } ?: choice.keys
            com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$paneId: sent $sent")
            if (voiceAllowed != null && target.isCurrent(h)) {
                // The live card may never have appeared in a chat poll. Record it only after delivery.
                observeQuestion(target.server, paneId, fresh, target.pane.status, agentName)
            }
            // Next and Submit here only advance the dialog, not answer or confirm it.
            if (choice != fresh.claudeCheckboxAction) noteAnswer(paneId, prompt, choice.label, serverId = target.server)
            // Say what went (1.4.3): a tap that answers shows it at once.
            // A sent answer is a note on the screen, not a reason on the Questions view's entry.
            if (voiceAllowed != null) report("Sent $sent to $agentName: ${choice.label.take(40)}") else flash("Sent $sent to $agentName: ${choice.label.take(40)}")
            refreshAnsweredModel(target, fresh)
            readPane(paneId)
            viewModelScope.launch { refreshPanes() }
        }
    }

    /** 2.2.8: [flash] for the screens (a short note that goes by itself). */
    fun flashNote(text: String) = flash(text)

    /**
     * 2.2.8 (Bill: "never show 'question changed, won't send'"): the pane's card as it is on screen now,
     * [cardUpdatedAt] marking it for a moment; a dialog that is gone takes its card with it. Nothing is sent.
     */
    var cardUpdatedAt by mutableStateOf<Pair<String, String>?>(null)
        private set

    private suspend fun cardUpdated(server: String, paneId: String) {
        val mark = server to paneId
        cardUpdatedAt = mark
        flash(CARD_UPDATED_NOTE)
        viewModelScope.launch { delay(4_000); if (cardUpdatedAt == mark) cardUpdatedAt = null }
        readPane(paneId)
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
            if (left.isNotEmpty()) return "Grok's input box already has text, so /privacy wasn't typed."
            h.typeAndEnter(paneId, "/privacy", connection, settleMillis = 700)
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
    internal fun answerTyped(target: CardTarget?, prompt: Prompt, choice: Choice?, text: String,
        /** 2.2.8: told why nothing was sent (the Questions view shows it on the entry). */
        feedback: ((String) -> Unit)? = null,
        delivered: () -> Unit,
    ) {
        if (prompt.guardedShortcutApproval) return
        // OpenCode's question (2.1.2): only its own answer row takes text, opened by that row's digit.
        if (prompt.openCodeQuestionFrame != null) {
            if (prompt.openCodeCustom == null || choice?.keys != prompt.openCodeCustom) { flash("Not sent. Refresh the question or open the terminal to answer."); feedback?.invoke("Not sent. Refresh the question or open the terminal to answer."); return }
            var guard: com.billmongan.herdrdroid.herdr.CardGuard? = null
            answerCardInput(target, prompt, text, delivered, guarded = true, onGuard = { guard = it }, feedback = feedback) { h, t, fresh ->
                typeOpenCodeCustom(h, t.pane.id, fresh, text, t.connection, t to guard) { h.readStyledViewport(t.pane.id, t.connection) }
            }
            return
        }
        // 2.2.7: the moves and the typed answer each go under the check of the read just before them.
        var guard: com.billmongan.herdrdroid.herdr.CardGuard? = null
        answerCardInput(target, prompt, text, delivered, onGuard = { guard = it }, feedback = feedback) { h, t, fresh ->
            val id = t.pane.id
            var live = fresh
            // 2.2.8: Oh My Pi's "type to deny and steer": the text goes straight into its approval box.
            if (choice?.keys == com.billmongan.herdrdroid.herdr.OMP_STEER && choice !in fresh.choices) return@answerCardInput false
            if (choice != null && choice.keys != com.billmongan.herdrdroid.herdr.OMP_STEER) {
                if (choice !in fresh.choices) return@answerCardInput false
                val move = moveKeys(fresh, choice) ?: return@answerCardInput false
                if (move.isNotEmpty()) {
                    sendCheckedCardInput(h, t.server, id, fresh, com.billmongan.herdrdroid.herdr.CardInput.Keys(move), guard, t.connection, remembered = false)
                    delay(300)
                    val moved = t.freshAnswerGuarded(h, prompt) ?: return@answerCardInput false
                    if (moveKeys(moved.first, choice)?.isEmpty() != true) return@answerCardInput false
                    live = moved.first
                    guard = moved.second
                }
            }
            sendCheckedCardInput(h, t.server, id, live, com.billmongan.herdrdroid.herdr.CardInput.Text(text, enter = true), guard, t.connection, remembered = false)
            true
        }
    }

    /**
     * Types [text] as the answer to OpenCode's question [fresh] (2.1.2): its "Type your own answer"
     * digit opens the answer row, a fresh read [reread] must show that row open for the same
     * question, and only then the text goes in with Enter. False when nothing could be typed (the
     * digit may then have opened the row; the text never goes anywhere else).
     */
    private suspend fun typeOpenCodeCustom(h: Herdr, paneId: String, fresh: Prompt, text: String, connection: Any?,
        /** 2.2.7 (Grok's review): from a card, the digit and the text each go under the check of the read before them. */
        card: Pair<CardTarget, com.billmongan.herdrdroid.herdr.CardGuard?>? = null,
        reread: suspend () -> String?): Boolean {
        val digit = fresh.openCodeCustom ?: return false
        val answer = text.trim()
        if (answer.isEmpty() || answer.any { it == '\n' || it == '\r' } || fresh.openCodeQuestionFrame == null) return false
        var textGuard: com.billmongan.herdrdroid.herdr.CardGuard? = null
        val after: String
        if (card != null) {
            val (t, guard) = card
            sendCheckedCardInput(h, t.server, paneId, fresh, com.billmongan.herdrdroid.herdr.CardInput.Keys(listOf(digit)), guard, t.connection, remembered = false)
            delay(300)
            val (live, occupant) = h.cardPaneGuarded(paneId, t.connection) ?: return false
            if (!t.pane.sameCardOccupant(live)) return false
            after = h.readStyledViewport(paneId, t.connection).takeUnless { it.isBlank() } ?: return false
            textGuard = occupant?.let { com.billmongan.herdrdroid.herdr.CardGuard(it, com.billmongan.herdrdroid.herdr.cardScreenDigest(after, live.agent)) }
        } else {
            h.sendKeys(paneId, digit, expectedConnection = connection)
            delay(300)
            after = runCatching { reread() }.getOrNull()?.takeUnless { it.isBlank() } ?: return false
        }
        val open = com.billmongan.herdrdroid.herdr.openCodeQuestionEditing(after)
        if (open == null || open.filterNot(Char::isWhitespace) != fresh.question.filterNot(Char::isWhitespace)) {
            com.billmongan.herdrdroid.diag.DiagLog.log("answer", "$paneId: OpenCode's answer row didn't open for the same question; nothing typed")
            return false
        }
        if (card != null) sendCheckedCardInput(h, card.first.server, paneId, fresh,
            com.billmongan.herdrdroid.herdr.CardInput.Text(answer, enter = true), textGuard, card.first.connection, remembered = false)
        else h.typeAndEnter(paneId, answer, connection)
        return true
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
                // 2.1.4: a usage dialog the app left open is closed before anything else.
                if (canPoll) runCatching { retryLeftOpenUsage() }
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
        // The server the pane was chosen on, fixed before the upload (2.1.1).
        val serverId = current?.id
        if (s.uri == null) {
            prefills[prefillKey(serverId, pane.id)] = text + "\n"
            openPane(pane.id)
            return
        }
        attach(s.uri) { path ->
            prefills[prefillKey(serverId, pane.id)] = (if (text.isNotBlank()) "$text\n\n" else "") + path + " "
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
            sessionMss = ::sessionMssFor,
        ).also { fleet = it }
        f.everyMs = NeedsYouCadence.fleetGapMs(watching, drawerShown, foreground)
        f.screenOn = ::screenOn
        f.watch(watchable())
    }

    /** The other servers' read gap for the drawer and the app's place (2.1.4). */
    private fun tuneFleet() {
        fleet?.everyMs = NeedsYouCadence.fleetGapMs(watching, drawerShown || questionsFeed.visible, foreground)
    }

    private var pushJob: Job? = null
    private val statusPush = com.billmongan.herdrdroid.herdr.StatusPush { android.os.SystemClock.elapsedRealtime() }
    private val backgroundPush = com.billmongan.herdrdroid.herdr.StatusPush { android.os.SystemClock.elapsedRealtime() }

    /**
     * 2.1.4 (Bill: "maybe you can pull in the background a little more often or something like that
     * or even do a push thing"): while the app is in front, one herdr wait on the open server ends
     * when any agent's state changes or a Claude or OpenCode screen shows a dialog, and the list is
     * read at once. The screens' own list polls stay as the safety net. One loop per app, idle
     * (no command) while the app is behind, locked, offline or on herdr-core.
     */
    private fun ensureStatusPush() {
        if (pushJob?.isActive == true) return
        pushJob = viewModelScope.launch {
            while (isActive) {
                val h = herdr
                val list = panes
                if (h == null || !foreground || locked || link != Link.CONNECTED || h.backend == Backend.CORE || list.none { it.agent != null }) {
                    delay(1_000); continue
                }
                val r = try {
                    statusPush.round(h, list, NeedsYouCadence.PUSH_WAIT_MS) {
                        if (foreground && herdr === h) { refreshPanes(); panes } else null
                    }
                } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
                delay(if (r == null) NeedsYouCadence.WAIT_RETRY_MS else NeedsYouCadence.pauseAfter(r.wake != null, r.elapsedMs, NeedsYouCadence.PUSH_WAIT_MS))
            }
        }
    }

    private fun startWatching() {
        if (herdr == null) return
        ensureStatusPush()
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
                // 2.1.4: the wait also watches the Claude and OpenCode screens herdr doesn't report on,
                // and a wake reads the list at once, no more than once every 2 s.
                val r = runCatching {
                    backgroundPush.round(h, panes, NeedsYouCadence.BACKGROUND_WAIT_MS) {
                        if (!foreground && herdr === h) { runCatching { refreshPanes() }; panes } else null
                    }
                }.getOrNull()
                if (r?.wake == null) {
                    delay(if (screenOn()) 3_000 else 12_000)
                    if (!foreground && herdr === h) runCatching { refreshPanes() }
                } else delay(NeedsYouCadence.pauseAfter(true, r.elapsedMs, NeedsYouCadence.BACKGROUND_WAIT_MS))
            }
        }
    }

    /**
     * Compares each pane's status with the last one seen and raises an alert when an agent starts
     * waiting on you, or (if wanted) finishes. The pane open on screen in the foreground is skipped.
     */
    /** Each pane's running alert confirmation (2.2.2), "server|pane" to job: one at a time per pane. */
    private val alertJobs = HashMap<String, Job>()

    private suspend fun readCodexAlertViewport(h: Herdr, pane: Pane, connection: Any): String? {
        if (h.backend != Backend.TUI || pane.scrollOffset?.let { it != 0 } == true) return null
        return try {
            if (h.cardPane(pane.id, connection)?.sameCardOccupant(pane) != true) return null
            h.readCardViewport(pane.id, connection, timeoutSeconds = 3).takeUnless { it.isBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun codexAlertIsQueued(text: String): Boolean =
        com.billmongan.herdrdroid.herdr.codexFollowUpRows(text).isNotEmpty() &&
            detectPrompt(text, blocked = true, agent = "codex") == null

    internal fun noticeChanges(serverId: String, serverLabel: String, seen: List<Pane>, client: () -> Herdr?) {
        if (!watching) {
            alertOrigins.keys.removeAll { it.first == serverId }
            seen.forEach { lastStatus["$serverId|${it.id}"] = it.status }
            return
        }
        val app = getApplication<Application>()
        for (p in seen) {
            val key = "$serverId|${p.id}"
            val before = lastStatus[key]
            lastStatus[key] = p.status
            if (p.isProbe) {
                alertOrigins.remove(serverId to p.id)
                alertJobs.remove(key)?.cancel()
                if (Notify.isShowing(app, serverId, p.id)) Notify.clear(app, serverId, p.id)
                continue
            }
            val codexWaiting = p.agent.equals("codex", true) && p.status == Status.NEEDS_YOU
            if (before == p.status && codexWaiting &&
                alertJobs[key]?.isActive != true && Notify.isShowing(app, serverId, p.id)) {
                alertJobs[key] = viewModelScope.launch {
                    val h = client() ?: return@launch
                    val connection = h.connectionIdentity ?: return@launch
                    val live = readCodexAlertViewport(h, p, connection) ?: return@launch
                    if (!isActive || !watching || lastStatus[key] != Status.NEEDS_YOU ||
                        h !== client() || h.connectionIdentity !== connection || probePane(serverId, p.id)) return@launch
                    if (codexAlertIsQueued(live)) {
                        alertOrigins.remove(serverId to p.id)
                        Notify.clear(app, serverId, p.id)
                    }
                }
                continue
            }
            if (before == null || before == p.status || p.agent == null) continue
            // A new state ends both the old read and its authority to answer.
            alertOrigins.remove(serverId to p.id)
            alertJobs.remove(key)?.cancel()
            val onScreen = foreground && serverId == current?.id && (screen as? Screen.Agent)?.paneId == p.id
            if (onScreen) continue
            when {
                p.status == Status.NEEDS_YOU -> alertJobs.put(key, viewModelScope.launch {
                    // Glyphs kept (2.1.2), as the tap's read keeps them, so the question identity the
                    // alert carries is computed from the same text the tap compares it with.
                    val alertClient = client()
                    val alertConnection = alertClient?.connectionIdentity
                    val live = if (codexWaiting && alertClient != null && alertConnection != null)
                        readCodexAlertViewport(alertClient, p, alertConnection) else null
                    if (!isActive || probePane(serverId, p.id)) return@launch
                    if (live != null && (alertClient !== client() || alertClient?.connectionIdentity !== alertConnection)) return@launch
                    if (live != null && codexAlertIsQueued(live)) {
                        Notify.clear(app, serverId, p.id)
                        return@launch
                    }
                    var text = live ?: runCatching { alertClient?.read(p.id, NOTIFY_READ_LINES, preserveGlyphs = true) }.getOrNull().orEmpty()
                    if (!isActive || probePane(serverId, p.id)) return@launch
                    // A screen that couldn't be read: one plain notice, no question and no Reply (1.4.2, from review).
                    if (text.isBlank()) { Notify.unread(app, serverId, serverLabel, p.id, p.agentName); return@launch }
                    val prompt = detectPrompt(text, blocked = true, agent = p.agent)?.let { if (it.historyReviewOnly) it.forCard() else it }
                    // An agent's error, not a question (1.4.2): its own alert, once per change of state.
                    // 2.2.0: OpenCode's failed turn too, read whole; one already acknowledged raises nothing.
                    val notice = if (prompt == null) com.billmongan.herdrdroid.herdr.agentErrorNotice(text, p.agent, p.agentName) else null
                    if (notice != null) {
                        if (!errorAcknowledged(serverId, p, notice)) Notify.agentError(app, serverId, serverLabel, p.id, p.agentName, notice.summary)
                        return@launch
                    }
                    // "Blocked" with nothing on screen that asks is a stale status, not a question (1.4.2):
                    // no alert. A screen that couldn't be read still alerts, saying so.
                    if (prompt == null && text.isNotBlank()) { com.billmongan.herdrdroid.diag.DiagLog.log("watch", "${p.id}: herdr says blocked, but nothing on screen asks; no alert"); return@launch }
                    // 2.2.2: a question read before its dialog finished drawing could carry chat text. The
                    // alert comes at once, saying only that a question is asked, and is updated in place
                    // (no second sound) once two reads in a row give the same complete dialog.
                    var card = prompt
                    // Past the fingerprint window the alert carries no question at all: nothing to confirm.
                    val neutralFirst = prompt != null && com.billmongan.herdrdroid.herdr.needsConfirmation(prompt) &&
                        !com.billmongan.herdrdroid.store.Vaults.locked()
                    if (prompt != null && neutralFirst) {
                        val neutral = checkNotNull(com.billmongan.herdrdroid.herdr.alertCard(prompt, confirmed = false, agent = p.agentName))
                        Notify.needsYou(app, serverId, serverLabel, p.id, p.agentName, neutral.question, emptyList(), p.agent,
                            terminalReview = true, reply = false, prompt = neutral)
                        // Still the alert to update: watched, waiting, not open on screen, and the neutral alert not tapped or dismissed.
                        fun stillWanted() = watching && !probePane(serverId, p.id) && lastStatus[key] == Status.NEEDS_YOU && Notify.isShowing(app, serverId, p.id) &&
                            !(foreground && serverId == current?.id && (screen as? Screen.Agent)?.paneId == p.id)
                        val got = com.billmongan.herdrdroid.herdr.confirmByReads(text, prompt, p.agent, null) {
                            delay(com.billmongan.herdrdroid.herdr.ALERT_CONFIRM_PAUSE_MS)
                            if (!stillWanted()) null
                            // A failed read is skipped, not the end: the next one may confirm.
                            else if (codexWaiting && alertClient != null && alertConnection != null) {
                                val next = readCodexAlertViewport(alertClient, p, alertConnection)
                                if (next != null && stillWanted() && alertClient === client() &&
                                    alertClient.connectionIdentity === alertConnection && codexAlertIsQueued(next)) {
                                    Notify.clear(app, serverId, p.id)
                                }
                                next.orEmpty()
                            } else runCatching { alertClient?.read(p.id, NOTIFY_READ_LINES, preserveGlyphs = true) }.getOrNull().orEmpty()
                        }
                        if (got == null || !stillWanted()) {
                            com.billmongan.herdrdroid.diag.DiagLog.log("watch", "${p.id}: question not confirmed, or the alert was opened or dismissed; the alert stays as it is")
                            return@launch
                        }
                        card = got.first
                        text = got.second
                    }
                    val asked = card
                    val askedText = text
                    withContext(Dispatchers.Main) { if (asked != null && asked.choices.isNotEmpty()) observeQuestion(serverId, p.id, asked, p.status, p.agentName, askedText) }
                    if (!isActive || probePane(serverId, p.id)) return@launch
                    // 2.1.2: the alert offers the card's own options (Hermes's clarify and OpenCode's
                    // question included), answered through the same checks when tapped.
                    val origin = alertClient?.let { registerAlertOrigin(serverId, p, it, alertConnection) }
                    Notify.needsYou(app, serverId, serverLabel, p.id, p.agentName, asked?.question ?: "Waiting for you.", asked?.choices.orEmpty(), p.agent,
                        terminalReview = asked?.terminalReview == true || origin == null,
                        reply = com.billmongan.herdrdroid.notify.notificationOffersReply(asked), prompt = asked,
                        quietUpdate = neutralFirst, origin = origin.orEmpty())
                })
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

    private class AlertOrigin(val token: String, val pane: Pane, val client: Herdr, val connection: Any) {
        var answeredAt: Long? = null
    }
    private val alertOrigins = LinkedHashMap<Pair<String, String>, AlertOrigin>()

    /** A notification can answer only on the session and connection that supplied its question. */
    internal fun registerAlertOrigin(server: String, pane: Pane, client: Herdr, connection: Any?): String? {
        if (connection == null || !hasStableOccupant(pane) || client.backend != Backend.TUI ||
            !alertSourceCurrent(client, server, connection)) return null
        val key = server to pane.id
        alertOrigins.remove(key)
        while (alertOrigins.size >= 256) alertOrigins.remove(alertOrigins.keys.first())
        val origin = AlertOrigin(java.util.UUID.randomUUID().toString(), pane, client, connection)
        alertOrigins[key] = origin
        return origin.token
    }

    private fun AlertOrigin.currentFor(a: NotifyAction): Boolean =
        alertOrigins[a.serverId to a.paneId] === this && alertSourceCurrent(client, a.serverId, connection)

    private data class AlertViewport(val pane: Pane, val raw: String, val occupant: String) {
        fun prompt() = detectPrompt(
            if (isOpenCode(pane.agent) || pane.agent.equals("muse", true)) raw
            else com.billmongan.herdrdroid.herdr.stripAnsi(raw),
            blocked = true, agent = pane.agent,
        )
        fun guard(prompt: Prompt?) = com.billmongan.herdrdroid.herdr.CardGuard(
            occupant, com.billmongan.herdrdroid.herdr.cardGuardScreen(raw, prompt, pane.agent),
        )
    }

    private fun alertSourceCurrent(h: Herdr, server: String, connection: Any): Boolean =
        !locked && h.connectionIdentity === connection &&
            if (server == current?.id) herdr === h && link == Link.CONNECTED else fleet?.herdr(server) === h

    /** A successful answer may advance a dialog without a Herdr status transition. */
    private fun watchAnsweredAlert(app: Application, address: NotifyAction, origin: AlertOrigin, answered: Prompt) {
        val server = address.serverId
        val paneId = address.paneId
        val key = "$server|$paneId"
        fun stillWanted() =
            watching && origin.currentFor(address) && lastStatus[key] == Status.NEEDS_YOU &&
                !probePane(server, paneId) &&
                !(foreground && server == current?.id && (screen as? Screen.Agent)?.paneId == paneId) &&
                Notify.isShowing(app, server, paneId)

        if (!stillWanted()) return
        alertJobs.remove(key)?.cancel()
        alertJobs[key] = viewModelScope.launch {
            val readAddress = NotifyAction(server, paneId, "", false, "", agent = origin.pane.agent)
            val answeredIdentity = com.billmongan.herdrdroid.notify.alertIdentity(answered)
            val answeredOptions = com.billmongan.herdrdroid.notify.alertOptionsSig(answered.choices)
            // A per-answer check also works when a background watcher is sleeping in a long wait.
            for (pause in listOf(300L, 600L, 1_000L, 1_700L, 2_400L)) {
                delay(pause)
                if (!stillWanted()) return@launch
                try {
                    val first = readAlertViewport(readAddress, origin) ?: continue
                    val next = first.prompt() ?: continue
                    if (com.billmongan.herdrdroid.notify.alertIdentity(next) == answeredIdentity &&
                        com.billmongan.herdrdroid.notify.alertOptionsSig(next.choices) == answeredOptions) continue
                    delay(com.billmongan.herdrdroid.herdr.ALERT_CONFIRM_PAUSE_MS)
                    if (!stillWanted()) return@launch
                    val second = readAlertViewport(readAddress, origin) ?: continue
                    val settled = second.prompt() ?: continue
                    if (first.occupant != second.occupant || !cardAnswerStillFits(next, settled)) continue
                    if (!stillWanted()) return@launch
                    val token = registerAlertOrigin(server, second.pane, origin.client, origin.connection) ?: return@launch
                    val serverLabel = current?.takeIf { it.id == server }?.label
                        ?: servers.firstOrNull { it.id == server }?.label ?: "Server"
                    Notify.needsYou(app, server, serverLabel, paneId, second.pane.agentName, settled.question,
                        settled.choices, second.pane.agent, reply = com.billmongan.herdrdroid.notify.notificationOffersReply(settled),
                        prompt = settled, quietUpdate = true, origin = token)
                    if (settled.choices.isNotEmpty())
                        observeQuestion(server, paneId, settled, Status.NEEDS_YOU, second.pane.agentName, second.raw)
                    return@launch
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    // Keep the original alert on transient read failures.
                }
            }
        }
    }

    private suspend fun readAlertViewport(a: NotifyAction, origin: AlertOrigin): AlertViewport? {
        if (!origin.currentFor(a)) return null
        val (pane, occupant) = origin.client.cardPaneGuarded(a.paneId, origin.connection) ?: return null
        if (occupant == null || !origin.pane.sameCardOccupant(pane) ||
            a.agent != null && !a.agent.equals(pane.agent, true)) return null
        val raw = origin.client.readStyledViewport(a.paneId, origin.connection)
        if (!origin.currentFor(a) || com.billmongan.herdrdroid.herdr.stripAnsi(raw).isBlank()) return null
        return AlertViewport(pane, raw, occupant)
    }

    private suspend fun sendAlertInput(a: NotifyAction, origin: AlertOrigin, view: AlertViewport,
        prompt: Prompt?, input: com.billmongan.herdrdroid.herdr.CardInput) {
        if (!origin.currentFor(a) || probePane(a.serverId, a.paneId))
            throw com.billmongan.herdrdroid.herdr.CardCheckFailed()
        // Bound to precisely this viewport and occupant on the host, under the same connection.
        val lane = a.serverId to a.paneId
        val permit = writeGate.open(lane) ?: throw com.billmongan.herdrdroid.herdr.CardCheckFailed()
        try {
            origin.client.sendCardInput(a.paneId, input, view.guard(prompt), origin.connection, permit,
                shellOnly = a.serverId in noPython3, onShellFallback = { shellCheckFallback(a.serverId) })
        } finally {
            writeGate.finish(lane, permit)
        }
    }

    /** A notification button: answer only if the same question is still on screen. */
    private suspend fun answerFromNotification(a: NotifyAction) {
        val app = getApplication<Application>()
        if (probePane(a.serverId, a.paneId)) {
            Notify.clear(app, a.serverId, a.paneId)
            return
        }
        val currentOrigin = alertOrigins[a.serverId to a.paneId]?.takeIf { it.currentFor(a) }
        if (currentOrigin != null && currentOrigin.token != a.origin) return
        val origin = currentOrigin
        if (origin == null) {
            Notify.sending(app, a.serverId, a.paneId, "Open HerdrDroid to answer",
                "This alert's connection or pane session could not be verified.")
            return
        }
        // One answer per question (1.4.2, from review): taps are handled one after another, so a second
        // tap for a question already answered from its alert is dropped for 15 seconds, before the
        // pane may have redrawn.
        val at = origin.answeredAt
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
            Notify.sending(app, a.serverId, a.paneId, "Sending your answer", a.label.ifEmpty { if (a.isReply) "Reply" else "Esc" })
            answerFromNotificationNow(a, origin, app) { origin.answeredAt = System.currentTimeMillis() }
        } finally {
            answeringCards = answeringCards - flight
            release()
        }
    }

    private suspend fun answerFromNotificationNow(a: NotifyAction, origin: AlertOrigin, app: Application, answered: () -> Unit) {
        fun stillLatest() = alertOrigins[a.serverId to a.paneId] === origin
        fun refuse(reason: String) {
            if (stillLatest()) Notify.sending(app, a.serverId, a.paneId, "Open HerdrDroid to answer", reason)
        }
        try {
            val initial = readAlertViewport(a, origin) ?: run {
                refuse("The pane's live screen could not be verified.")
                return
            }
            val now = initial.prompt()
            if (usageDialogShown(initial.pane.agent, initial.raw) || a.serverId == current?.id && usageLeftOpen(a.paneId)) {
                refuse("${initial.pane.agentName}'s usage screen is open. Close it before answering.")
                return
            }
            if (now == null || now.terminalReview || now.historyReviewOnly || now.clipped || now.guardedShortcutApproval) {
                refuse("The active question could not be verified.")
                return
            }
            if (recentAnswerAge(a.serverId, a.paneId) {
                    if (a.isReply) cardAnswerStillFits(it, now) else notificationAnswerStillFits(it, a)
                } != null) {
                if (stillLatest()) Notify.sending(app, a.serverId, a.paneId, "Already answered", "")
                return
            }
            val label: String
            var destination = initial
            var destinationPrompt: Prompt? = now
            val input: com.billmongan.herdrdroid.herdr.CardInput
            if (a.isReply) {
                fun norm(value: String) = value.filterNot(Char::isWhitespace)
                val text = a.keys.trim()
                val matches = a.question.isNotBlank() && norm(now.question) == norm(a.question) &&
                    a.identity == com.billmongan.herdrdroid.notify.alertIdentity(now) &&
                    a.options == com.billmongan.herdrdroid.notify.alertOptionsSig(now.choices)
                if (!matches || !com.billmongan.herdrdroid.notify.notificationOffersReply(now) ||
                    text.isEmpty() || text.any(Char::isISOControl)) {
                    refuse("The active question or its typed reply could not be verified.")
                    return
                }
                if (a.typedRow.isNotEmpty()) {
                    val row = com.billmongan.herdrdroid.herdr.typedChatPair(now.choices)?.first
                        ?.takeIf { it.keys == a.typedRow && norm(it.label) == norm(a.label) }
                    if (row == null || now.numberKeys && now.selected != row.keys) {
                        refuse("The typed answer row could not be verified.")
                        return
                    }
                    val move = if (now.numberKeys) emptyList() else com.billmongan.herdrdroid.herdr.moveKeys(now, row)
                    if (move == null) { refuse("The typed answer row could not be verified."); return }
                    if (move.isNotEmpty()) {
                        sendAlertInput(a, origin, initial, now, com.billmongan.herdrdroid.herdr.CardInput.Keys(move))
                        delay(300)
                        val reread = readAlertViewport(a, origin)
                        val live = reread?.prompt()
                        if (reread == null || reread.occupant != initial.occupant || live == null ||
                            !cardAnswerStillFits(now, live) || com.billmongan.herdrdroid.herdr.moveKeys(live, row)?.isEmpty() != true) {
                            refuse("The typed answer row could not be verified after moving.")
                            return
                        }
                        destination = reread
                        destinationPrompt = live
                    }
                } else if (now.openCodeQuestionFrame != null) {
                    val digit = now.openCodeCustom ?: run { refuse("The typed answer row could not be verified."); return }
                    sendAlertInput(a, origin, initial, now, com.billmongan.herdrdroid.herdr.CardInput.Keys(listOf(digit)))
                    delay(300)
                    val reread = readAlertViewport(a, origin)
                    if (reread == null || reread.occupant != initial.occupant ||
                        com.billmongan.herdrdroid.herdr.openCodeQuestionEditing(reread.raw)?.let(::norm) != norm(now.question)) {
                        refuse("OpenCode's typed answer row could not be verified.")
                        return
                    }
                    destination = reread
                    destinationPrompt = null
                } else if (now.choices.isNotEmpty()) {
                    refuse("This question has options. Open the app to choose one.")
                    return
                }
                label = text
                input = com.billmongan.herdrdroid.herdr.CardInput.Text(text, enter = true)
            } else {
                val choice = now.choices.firstOrNull { it.keys == a.keys }
                if (a.identity != com.billmongan.herdrdroid.notify.alertIdentity(now) ||
                    a.options != com.billmongan.herdrdroid.notify.alertOptionsSig(now.choices) ||
                    !notificationAnswerStillFits(now, a) ||
                    choice?.let { com.billmongan.herdrdroid.herdr.approvesFromNowOn(it) ||
                        com.billmongan.herdrdroid.herdr.blockedChoiceReason(it) != null } == true ||
                    a.keys == com.billmongan.herdrdroid.herdr.OMP_STEER ||
                    a.keys == com.billmongan.herdrdroid.herdr.OmpAsk.SUBMIT || a.keys.startsWith("grok-privacy:")) {
                    refuse("This option must be answered in the app.")
                    return
                }
                val keys = when {
                    now.openCodePermission -> com.billmongan.herdrdroid.notify.openCodePermissionAlertKeys(now, a)
                    a.isKey -> listOf("esc").takeIf { a.keys == "esc" && !now.hermesClarify }
                    choice != null -> com.billmongan.herdrdroid.herdr.menuKeys(now, choice)
                    else -> null
                }
                if (keys != null) {
                    input = com.billmongan.herdrdroid.herdr.CardInput.Keys(keys)
                } else if (!a.isKey && choice != null && !now.openCodePermission && now.openCodeQuestionFrame == null &&
                    !now.hermesClarify && !now.museQuota && !now.claudeAsk && now.claudeCheckboxAction == null) {
                    input = com.billmongan.herdrdroid.herdr.CardInput.Text(choice.keys, choice.pressEnter)
                } else {
                    refuse("The active option could not be verified.")
                    return
                }
                label = choice?.label ?: "Pressed Esc"
            }
            sendAlertInput(a, origin, destination, destinationPrompt, input)
            if (!stillLatest()) return
            rememberCardAnswer(a.serverId, a.paneId, now)
            answered()
            Notify.sending(app, a.serverId, a.paneId, "Answered", label.take(40))
            watchAnsweredAlert(app, a, origin, now)
            runCatching { noteAnswer(a.paneId, now, label, a.serverId) }
        } catch (c: CancellationException) {
            refuse("The answer could not be confirmed.")
            throw c
        } catch (_: com.billmongan.herdrdroid.herdr.DeliveryUncertain) {
            answered()
            refuse("The answer may have been sent. Check the pane before answering again.")
        } catch (_: Exception) {
            refuse("The active question, pane or connection could not be verified.")
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
        if (serverId == current?.id && herdr != null) {
            if (connecting) cancelConnect()
            paneId?.let { openPane(it, "drawer") }
            return
        }
        val s = servers.firstOrNull { it.id == serverId } ?: return
        if (!canSignInSilently(s)) { cancelConnect(); screen = Screen.Servers; return }
        // If that server doesn't answer, come back to the agent you were on (its connection is
        // parked, so this is quick) rather than leaving an empty screen or the server list.
        val backServer = current
        val backPane = (screen as? Screen.Agent)?.paneId
        pendingOpen = paneId
        if (current != null) disconnectQuietly()
        connect(s, savedPassword(s), remember = false, openAgent = true, voice = voice)
        val run = connectRun
        val job = connectJob
        viewModelScope.launch {
            job?.join()
            if (run == connectRun && current == null && backServer != null && pendingHostKey == null && pendingBackendChoice == null) {
                val why = error
                pendingOpen = backPane
                connect(backServer, savedPassword(backServer), remember = false, openAgent = true)
                val fallbackRun = connectRun
                connectJob?.join()
                if (fallbackRun != connectRun) return@launch
                error = "${s.label} didn't answer" + (why?.let { ": $it" } ?: ".")
            }
        }
    }

    // -- 1.1: every agent on every server, in one drawer ------------------------------------------

    /** One agent in the drawer: the server it is on, and the status the drawer shows. */
    data class Session(val server: Server, val pane: Pane, val status: Status, val pinned: Boolean, val current: Boolean)

    /** Back to the agent last open on this server, or its tab list when there is none. */
    fun goHome() {
        screen = homeScreen()
    }

    private fun homeScreen(): Screen {
        val last = prefs.lastAgent.split("|", limit = 2)
        return if (last.getOrNull(0) == current?.id && panes.any { it.id == last.getOrNull(1) }) Screen.Agent(last[1])
            else if (current != null) Screen.Tabs else Screen.Servers
    }

    /** Whether the drawer is pulled out ([drawerOpen]). */
    /**
     * 2.1.4 (Bill: the drawer's yellow badge lit late): opening the drawer reads the open server's
     * list and pokes the other servers' watcher at once, and both are read more often while it is out.
     */
    var drawerOpen: Boolean
        get() = drawerShown
        set(v) {
            val opened = v && !drawerShown
            drawerShown = v
            tuneFleet()
            if (opened && foreground) {
                fleet?.poke()
                if (herdr != null) viewModelScope.launch { runCatching { refreshPanes() } }
            }
        }

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
        // Pinned at the confirm tap: this client, its connection, and the tab's panes refuse new
        // sends from now on (2.0.1 finding 2).
        val h = clientFor(serverId) ?: return
        val connection = h.connectionIdentity ?: run { error = "Couldn't close it: not connected."; return }
        val known = if (serverId == current?.id) panes else fleetStates[serverId]?.panes.orEmpty()
        val pending = paneClose.confirm(serverId, pane, connection, known)
        viewModelScope.launch {
            val port = object : com.billmongan.herdrdroid.herdr.PaneClose.Port {
                override suspend fun panes(connection: Any) = h.panesOn(connection)
                override suspend fun closeTab(tabId: String, connection: Any) = h.closeTabOn(tabId, connection)
            }
            val result = paneClose.run(pending, port)
            when (result.outcome) {
                com.billmongan.herdrdroid.herdr.PaneClose.Outcome.CLOSED ->
                    com.billmongan.herdrdroid.diag.DiagLog.log("session", "closed \"${pane.tabLabel}\"")
                com.billmongan.herdrdroid.herdr.PaneClose.Outcome.CHANGED ->
                    error = "Not closed: the session changed or the connection was replaced. Check it and try again."
                com.billmongan.herdrdroid.herdr.PaneClose.Outcome.FAILED ->
                    error = "Couldn't close it: ${result.failure?.message}"
            }
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
                // No server answered: only now the server screen, with the last error on it; unless
                // Back already took the user to the main screen (pre-review L9), which stays.
                if (screen != Screen.Home) screen = Screen.Servers
            } finally {
                starting = false
                startingOn = null
            }
        }
    }

    // -- Send when idle --------------------------------------------------------------------------

    data class QueuedPrompt(val id: Long, val serverId: String, val paneId: String, val text: String,
                            internal val hermesTarget: Pane? = null,
                            /** 2.2.7: Send now is sending it (Ctrl+B first, or the message); the idle loop leaves it. */
                            val sending: Boolean = false,
                            /** 2.2.7: a Send now write failed after it may have reached the computer: never sent again by itself. */
                            val mayHaveSent: Boolean = false,
                            /** 2.2.7: why Send now didn't send it, shown on its row. */
                            val note: String? = null,
                            /** 2.2.7: the Send now attempt that owns the row while [sending] (pre-review M2). */
                            internal val sendToken: Long = 0L,
                            /** 2.2.7: when [sending] began; a row stuck sending past [SENDING_STALE_MS] is free again (pre-review L1). */
                            internal val sendingSince: Long = 0L,
                            /** 2.2.7: a send of it failed; it waits for a tap (Send now, Send immediately) instead of the idle loop (pre-review M4). */
                            val needsTap: Boolean = false,
                            /**
                             * 2.2.8 (Codex's review 1): the pane's occupant when the message was queued behind an open
                             * question; it goes only to that occupant (see [occupantChanged]).
                             */
                            internal val occupant: Pane? = null) {
        /** 2.2.8: what a bound row's send carries to the write (see [BoundSend]); null for an unbound row. */
        internal fun bound(): BoundSend? = occupant?.let { BoundSend(it, text, id) }

        /** Being sent now; false once [SENDING_STALE_MS] pass, when the attempt was dropped without a word. */
        fun inFlight(now: Long = System.currentTimeMillis()): Boolean = sending && now - sendingSince < SENDING_STALE_MS

        companion object {
            /** 2.2.7: how long a Send now row may show "Sending now" before it counts as dropped (an upload may take two minutes). */
            const val SENDING_STALE_MS = 5 * 60_000L
        }
    }

    var queued by mutableStateOf<List<QueuedPrompt>>(emptyList())
        private set
    private var queueJob: Job? = null

    internal fun blocksNormalSend(pane: Pane, screen: String, liveOpenCode: Boolean = false): Boolean =
        // 2.2.0: herdr keeps an OpenCode pane blocked after a failed turn (and after /new); its empty
        // input box with no dialog on this screen is not a question, so the message may go in.
        (pane.status == Status.NEEDS_YOU && !(isOpenCode(pane.agent) &&
            ((liveOpenCode && openCodeEmptyComposer(screen)) || com.billmongan.herdrdroid.herdr.openCodeComposerEmpty(screen)))) ||
            openCodeBackgroundDrawer(pane.agent, screen) ||
            usageDialogShown(pane.agent, screen) ||
            (pane.agent == "hermes" && hermesUsageWaiting(screen)) ||
            (pane.agent != null && detectPrompt(forPhone(screen), false, pane.agent, versionOf(pane.id)) != null)

    internal fun openCodeComposerIsCurrent(pane: Pane): Boolean = openCodeComposerTarget?.let {
        paneReadProblem == null && paneTextPane == pane.id && pane.scrollOffset == 0 &&
            it.pane.sameCardOccupant(pane) && it.isCurrent(it.client)
    } == true

    private fun codexSteeringIsCurrent(pane: Pane): Boolean = codexSteeringTarget?.let {
        foreground && paneReadProblem == null && paneTextPane == pane.id && pane.scrollOffset == 0 &&
            it.pane.sameCardOccupant(pane) && it.isCurrent(it.client)
    } == true

    internal fun queuedSendAvailable(pane: Pane?, status: Status, screen: String): Boolean =
        com.billmongan.herdrdroid.herdr.nativeQueuedSendAvailable(pane?.agent, status, screen,
            codexSteeringProven = pane != null && codexSteeringIsCurrent(pane))

    private fun currentHermesUsage(pane: Pane): HermesUsageRead? = hermesUsageRead?.takeIf {
        foreground && paneReadProblem == null && paneTextPane == pane.id && pane.scrollOffset == 0 &&
            it.target.pane.sameCardOccupant(pane) && it.target.isCurrent(it.target.client)
    }

    // A normal Hermes footer and an empty editor, not just a '>' row in output. Never take this
    // exceptional route through a draft, even for Force send.
    private fun emptyHermesUsageComposer(screen: String): Boolean {
        val bottom = com.billmongan.herdrdroid.herdr.stripAnsi(screen).lines().asReversed().asSequence().map { it.trim() }
            .filter { it.isNotBlank() && !it.all { c -> c in "─━═" } }.take(2)
            .toList()
        return bottom.size == 2 && bottom[0].startsWith("☤ ") &&
            (bottom[1].startsWith('❯') || bottom[1].startsWith('›')) &&
            com.billmongan.herdrdroid.herdr.inputLineOf(screen, "hermes") == ""
    }

    /**
     * A wait from Hermes's gateway that could be this pane's own (2.1.5, Bill: "hermes thinks there
     * is a question right now but there is not and so it is stuck"): any conversation waiting, unless
     * every wait is proven to belong to another stored conversation ([hermesWaitsAreElsewhere]). A
     * Telegram or cron turn left on an open approval must not hold an idle pane whose screen asks
     * nothing; the screen's own question, and herdr's blocked status, still hold it.
     */
    private fun hermesGatewayHolds(pane: Pane): Boolean =
        pane.agent == "hermes" && hermesState == Status.NEEDS_YOU && !hermesWaitsAreElsewhere(pane)

    fun hasPendingQuestion(pane: Pane): Boolean {
        if (hermesGatewayHolds(pane)) return true
        val effective = if (pane.status == Status.NEEDS_YOU && currentHermesUsage(pane)?.composerReady == true)
            pane.copy(status = Status.IDLE) else pane
        return blocksNormalSend(effective, if (paneTextPane == pane.id && paneReadProblem == null) paneText else "",
            openCodeComposerIsCurrent(pane))
    }

    internal fun waitingForHermesComposer(paneId: String): Boolean = pane(paneId)?.agent == "hermes" &&
        paneTextPane == paneId && paneReadProblem == null && hermesUsageWaiting(paneText)

    private data class OpenCodeSendRead(val pane: Pane, val connection: Any, val text: String)

    private fun checkedSendBlocked(pane: Pane, screen: String, checked: OpenCodeSendRead?): Boolean {
        if (hermesGatewayHolds(pane)) return true
        val recovered = pane.agent == "hermes" && checked != null &&
            pane.status in hermesUsageSendStates && checked.pane.status in hermesUsageSendStates && emptyHermesUsageComposer(screen) &&
            (hermesUsageEnd(screen) != null || currentHermesUsage(pane)?.target?.connection === checked.connection)
        // Keep the checked pane's true status for transport: agent prompt rejects blocked panes.
        // Only this fresh empty-composer check permits the guarded text/Enter route in that case.
        fun blocked(p: Pane) = blocksNormalSend(
            if (recovered && p.status == Status.NEEDS_YOU) p.copy(status = Status.IDLE) else p,
            screen, checked != null,
        )
        return blocked(pane) || checked?.pane?.let { blocked(it) } == true
    }

    private val hermesUsageSendStates = setOf(Status.NEEDS_YOU, Status.IDLE, Status.DONE)

    private suspend fun readOpenCodeForSend(h: Herdr, expected: Pane): OpenCodeSendRead? {
        val connection = h.connectionIdentity ?: return null
        val server = current?.id ?: return null
        if (!com.billmongan.herdrdroid.herdr.hasStableOccupant(expected)) return null
        fun sameTarget() = herdr === h && h.connectionIdentity === connection && current?.id == server &&
            pane(expected.id)?.sameCardOccupant(expected) == true
        if (!sameTarget()) return null
        val fresh = h.cardPane(expected.id, connection) ?: return null
        if (!fresh.sameCardOccupant(expected) || !sameTarget()) return null
        val text = h.readStyledViewport(expected.id, connection)
        return OpenCodeSendRead(fresh, connection, text).takeIf { text.isNotBlank() && sameTarget() }
    }

    /**
     * 2.2.8 (Codex's review 1): a message queued behind a question goes only to the occupant it was
     * queued for. When another agent or session holds the pane now, it leaves the queue and goes back
     * to the message box, with a note, and is never sent to the new occupant. True when it was moved.
     */
    private fun occupantChanged(q: QueuedPrompt, now: Pane): Boolean {
        val bound = q.occupant ?: return false
        if (bound.sameCardOccupant(now)) return false
        queued = queued.filterNot { it.id == q.id }
        if (sendNowConfirm?.id == q.id) sendNowConfirm = null
        if (resendConfirm?.id == q.id) resendConfirm = null
        val draft = composerDraft(q.paneId)
        setComposerDraft(q.paneId, if (draft.isBlank()) q.text else draft.trimEnd() + "\n" + q.text)
        com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${q.paneId}: the pane's occupant changed; the queued message went back to the draft")
        flash(QUEUED_OCCUPANT_CHANGED)
        return true
    }

    /**
     * Holds a message until the pane's agent is ready, then sends it. [behindQuestion]: queued while a
     * question is open (2.2.8), so bound to the pane's occupant now.
     */
    fun enqueue(
        paneId: String,
        text: String,
        behindQuestion: Boolean = false,
        displayedPane: Pane? = null,
        displayedServer: String? = null,
    ): Boolean {
        val serverId = current?.id ?: return false
        if (text.isBlank()) return false
        val now = pane(paneId)
        if (behindQuestion && (displayedServer != serverId || displayedPane == null ||
                (displayedPane.terminalId.isNullOrBlank() && displayedPane.sessionId.isNullOrBlank()) ||
                now?.sameCardOccupant(displayedPane) != true)) {
            flashNote(QUEUED_OCCUPANT_CHANGED)
            return false
        }
        queued = queued + QueuedPrompt(System.nanoTime(), serverId, paneId, text, now?.takeIf { it.agent == "hermes" },
            occupant = displayedPane?.takeIf { behindQuestion })
        if (queueJob?.isActive == true) return true
        queueJob = viewModelScope.launch {
            while (isActive && queued.isNotEmpty()) {
                delay(3_000)
                if (queued.none { it.serverId == current?.id } || link != Link.CONNECTED) continue
                refreshPanes()
                if (link != Link.CONNECTED) continue
                for (q in queued.toList()) {
                    if (q !in queued || q.serverId != current?.id) continue
                    // 2.2.7: never behind a Send now in flight, after one that may have gone in, or one that failed.
                    if (q.inFlight() || q.mayHaveSent || q.needsTap) continue
                    val p = pane(q.paneId)
                    if (p == null) continue
                    if (occupantChanged(q, p)) continue
                    if (q.hermesTarget?.sameCardOccupant(p) == false) continue
                    // 2.2.0: an OpenCode pane herdr keeps blocked with no dialog on screen counts as ready; the send still checks.
                    val ready = p.status == Status.IDLE || p.status == Status.DONE ||
                        p.status == Status.NEEDS_YOU && (currentHermesUsage(p)?.composerReady == true || p.openCodeStaleBlock)
                    if (ready && !hasPendingQuestion(p) && !waitingForHermesComposer(p.id)) {
                        queued = queued - q
                        sendMessage(p, q.text, bound = q.bound())
                    }
                }
            }
        }
        return true
    }

    /** Skip this app's idle wait. Normal screen and input guards still apply. */
    fun sendQueuedImmediately(row: QueuedPrompt) {
        // 2.2.7: by id (a row's copy may be older than its note), and never while Send now is sending it.
        val q = queued.firstOrNull { it.id == row.id } ?: return
        if (q.inFlight() || q.serverId != current?.id) return
        if (q.mayHaveSent) { resendConfirm = q; return }
        val target = pane(q.paneId) ?: return
        if (occupantChanged(q, target)) return
        if (q.hermesTarget?.sameCardOccupant(target) == false) {
            flash("Not sent: the Hermes pane changed. Cancel the queued message to remove it.")
            return
        }
        if (waitingForHermesComposer(target.id)) {
            flash("Waiting for Hermes to return to its composer after Usage.")
            return
        }
        if (link != Link.CONNECTED || hasPendingQuestion(target)) {
            flash("Reconnect or answer the pending question before sending.")
            return
        }
        queued = queued.filterNot { it.id == q.id }
        sendMessage(target, q.text, bound = q.bound())
    }

    fun cancelQueued(q: QueuedPrompt) {
        queued = queued.filterNot { it.id == q.id }
        if (sendNowConfirm?.id == q.id) sendNowConfirm = null
    }

    // -- Send now (2.2.7) ---------------------------------------------------------------------------

    /** The queued message waiting on the Force Send confirmation, or null. */
    var sendNowConfirm by mutableStateOf<QueuedPrompt?>(null)
        private set

    private fun queuedById(id: Long) = queued.firstOrNull { it.id == id }
    private fun updateQueued(id: Long, change: (QueuedPrompt) -> QueuedPrompt) {
        queued = queued.map { if (it.id == id) change(it) else it }
    }

    /**
     * The long-press "Send now" on a queued message. Every send guard still applies (a question, a
     * panel, a draft, the link); then the route ([com.billmongan.herdrdroid.herdr.sendNowRoute]):
     * an idle agent gets it at once; a Claude Code command showing its background hint is moved to
     * the background with Ctrl+B first; a working Claude Code asks first (Force Send); any other
     * working agent keeps it queued and says why. It leaves the queue only once delivered.
     */
    fun sendQueuedNow(q: QueuedPrompt) {
        val item = queuedById(q.id) ?: return
        if (item.inFlight() || item.serverId != current?.id) return
        // Pre-review L3: a message that may already have reached the agent is sent again only after a confirmation.
        if (item.mayHaveSent && resendConfirm?.id != item.id) { resendConfirm = item; return }
        if (item.mayHaveSent) { resendConfirm = null; updateQueued(item.id) { it.copy(mayHaveSent = false) } }
        val target = pane(item.paneId) ?: run { flash("Not sent: that pane isn't open on the computer just now."); return }
        if (occupantChanged(item, target)) return
        if (item.hermesTarget?.sameCardOccupant(target) == false) {
            flash("Not sent: the Hermes pane changed. Cancel the queued message to remove it.")
            return
        }
        if (waitingForHermesComposer(target.id)) { flash("Waiting for Hermes to return to its composer after Usage."); return }
        if (link != Link.CONNECTED || herdr == null || hasPendingQuestion(target)) {
            flash("Reconnect or answer the pending question before sending.")
            return
        }
        val screen = if (paneTextPane == target.id && paneReadProblem == null) paneText else ""
        val route = com.billmongan.herdrdroid.herdr.sendNowRoute(target.agent, target.status, screen,
            codexSteeringProven = codexSteeringIsCurrent(target))
        com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${target.id}: send now, route ${route.name.lowercase()}")
        when (route) {
            com.billmongan.herdrdroid.herdr.SendNowRoute.NORMAL -> dispatchQueued(item, target)
            com.billmongan.herdrdroid.herdr.SendNowRoute.FORCE_CONFIRM -> {
                if (target.agent == "codex") {
                    sendNowConfirmConnection = herdr?.connectionIdentity
                    sendNowConfirm = item.copy(occupant = target)
                } else sendNowConfirm = item
            }
            com.billmongan.herdrdroid.herdr.SendNowRoute.WAIT_FOR_IDLE -> if (backgroundSupport(target.agent, target.agentName) == null) {
                // 2.2.8: Grok and Agy: move the running command to the background, then send once idle.
                backgroundThenSendWhenIdle(item, target)
            } else {
                val why = com.billmongan.herdrdroid.herdr.sendNowWaitText(target.agentName)
                updateQueued(item.id) { it.copy(note = why) }
                flash(why)
            }
            com.billmongan.herdrdroid.herdr.SendNowRoute.BACKGROUND_THEN_SEND -> backgroundThenSend(item, target)
        }
    }

    /** Send anyway, from the Force Send confirmation: typed in while Claude works, taken after its current step. */
    fun confirmSendNow() {
        val confirmed = sendNowConfirm
        val connection = sendNowConfirmConnection
        val item = confirmed?.let { queuedById(it.id) }
        cancelSendNow()
        if (item == null || item.inFlight() || item.serverId != current?.id) return
        val target = pane(item.paneId) ?: return
        if (link != Link.CONNECTED || hasPendingQuestion(target)) { flash("Reconnect or answer the pending question before sending."); return }
        val confirmedPane = confirmed?.occupant
        if (confirmedPane?.agent == "codex") {
            if (connection == null || herdr?.connectionIdentity !== connection ||
                !confirmedPane.sameCardOccupant(target)) {
                flash("Not sent: the Codex pane or connection changed. The message is still queued.")
                return
            }
            steerQueuedCodex(item, target)
        } else dispatchQueued(item, target)
    }

    private fun steerQueuedCodex(item: QueuedPrompt, target: Pane) {
        val h = herdr ?: return
        val connection = h.connectionIdentity ?: return
        val server = current?.id ?: return
        if (occupantChanged(item, target)) return
        fun refused(why: String) {
            updateQueued(item.id) { it.copy(sending = false, note = "Not sent: $why. Still queued.") }
        }
        if (h.backend != Backend.TUI || !com.billmongan.herdrdroid.herdr.codexSteeringText(item.text)) {
            refused("Codex steering requires a short chat message and a live composer")
            return
        }
        val token = System.nanoTime()
        updateQueued(item.id) {
            it.copy(sending = true, sendToken = token, sendingSince = System.currentTimeMillis(), needsTap = true, note = null)
        }
        fun ownsRow() = queuedById(item.id)?.sendToken == token
        fun allowed() = ownsRow() && !locked && foreground && current?.id == server && herdr === h &&
            h.connectionIdentity === connection && link == Link.CONNECTED && screen == Screen.Agent(target.id)
        viewModelScope.launch {
            var verifiedViewport: String? = null
            var occupant: String? = null
            val port = object : com.billmongan.herdrdroid.herdr.LocalPromptSend.Port {
                override suspend fun pane(id: String): Pane? {
                    val read = h.cardPaneGuarded(id, connection) ?: return null
                    if (occupant != null && occupant != read.second) return null
                    occupant = read.second
                    return read.first
                }
                override suspend fun viewport(id: String) = h.readStyledViewport(id, connection)
                override fun blocked(fresh: Pane, viewport: String): Boolean {
                    if (fresh.status != Status.WORKING || blocksNormalSend(fresh, viewport) ||
                        holdReason(fresh.id, viewport.trimEnd()) != null ||
                        !com.billmongan.herdrdroid.herdr.codexSteeringReady(viewport)) return true
                    verifiedViewport = viewport
                    return false
                }
                override suspend fun send(fresh: Pane, text: String, permit: WritePermit) {
                    if (fresh.status != Status.WORKING || !allowed()) throw com.billmongan.herdrdroid.herdr.CardCheckFailed()
                    val guard = com.billmongan.herdrdroid.herdr.CardGuard(
                        checkNotNull(occupant), com.billmongan.herdrdroid.herdr.codexSteeringGuard(checkNotNull(verifiedViewport))
                            ?: throw com.billmongan.herdrdroid.herdr.CardCheckFailed(),
                    )
                    h.sendCardInput(fresh.id, com.billmongan.herdrdroid.herdr.CardInput.Submit(text), guard,
                        connection, permit, shellOnly = server in noPython3)
                }
            }
            val result = localPromptSend.send(server, com.billmongan.herdrdroid.herdr.Occupant.of(target),
                item.text, ::allowed, port, allowWorking = true)
            (result.failure as? LinkDown)?.let { if (herdr === h) linkLost(it) }
            if (!ownsRow()) return@launch
            when (result.outcome) {
                com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.SENT -> queued = queued.filterNot { it.id == item.id }
                com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.UNCERTAIN -> updateQueued(item.id) {
                    it.copy(sending = false, mayHaveSent = true, note = "Delivery unconfirmed. Check Codex before sending again.")
                }
                else -> refused("the live Codex composer or its occupant could not be verified")
            }
        }
    }

    private var sendNowConfirmConnection: Any? = null

    fun cancelSendNow() {
        sendNowConfirm = null
        sendNowConfirmConnection = null
    }

    /** 2.2.7 (pre-review L3): the queued message that may already have reached the agent, waiting on "Send it again?". */
    var resendConfirm by mutableStateOf<QueuedPrompt?>(null)
        private set

    /** "Send again" on that question: Send now, once more, with every guard. */
    fun confirmResend() {
        val item = resendConfirm?.let { queuedById(it.id) } ?: run { resendConfirm = null; return }
        sendQueuedNow(item)
    }

    fun cancelResend() { resendConfirm = null }

    /** Sends [item] once through [sendMessage] with all its guards; it leaves the queue only when delivered. */
    private fun dispatchQueued(item: QueuedPrompt, target: Pane, token: Long? = null, then: (() -> Unit)? = null) {
        // Pre-review M1 and M2: only a row still queued (not removed meanwhile), and, inside a Send
        // now attempt, only that attempt's own row (a replay or a second tap never sends it again).
        val now = queuedById(item.id) ?: return
        if (token != null && now.sendToken != token) return
        if (occupantChanged(now, target)) return
        updateQueued(item.id) { it.copy(sending = true, sendingSince = System.currentTimeMillis(), needsTap = false, note = null) }
        sendMessage(target, item.text, bound = item.bound(),
            delivered = {
                queued = queued.filterNot { it.id == item.id }
                com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${target.id}: send now delivered; removed from the queue")
                then?.invoke()
            },
            failed = { why, started ->
                updateQueued(item.id) {
                    // Pre-review L10: a refused write is said as it is, not as a dropped link.
                    it.copy(sending = false, mayHaveSent = it.mayHaveSent || started, needsTap = true,
                        note = if (started) "It may have reached the agent ($why). Check the agent before sending it again."
                        else "Not sent: $why. Still queued.")
                }
            })
    }

    /**
     * 2.2.8: Send immediately on a queued message for an agent that takes no input while it works but
     * can move its running command to the background (Grok, Agy): [runBackgrounder] until the agent is
     * idle, then the message goes once with every guard. It stays queued if the move can't be shown.
     */
    private fun backgroundThenSendWhenIdle(item: QueuedPrompt, target: Pane) {
        val client = herdr ?: return
        val server = current?.id
        val token = System.nanoTime()
        updateQueued(item.id) { it.copy(sending = true, sendingSince = System.currentTimeMillis(), sendToken = token, note = "Moving the running task to the background…") }
        viewModelScope.launch {
            val outcome = runBackgrounder(client, server, target, pushLimits) { s -> !com.billmongan.herdrdroid.herdr.agentWorking(pane(target.id)?.status ?: Status.UNKNOWN, s) }
            val now = queuedById(item.id)?.takeIf { it.sending && it.sendToken == token } ?: return@launch
            if (outcome == com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.DONE) {
                val p = pane(target.id)?.takeIf { it.sameCardOccupant(target) }
                if (p != null && current?.id == server) { dispatchQueued(now, p, token); return@launch }
            }
            val why = com.billmongan.herdrdroid.herdr.backgroundOutcomeText(outcome, target.agentName)?.replace("The message is still in its queue.", "")?.trim()
                ?: "the pane changed"
            updateQueued(item.id) { if (it.sendToken == token) it.copy(sending = false, sendToken = 0L, note = "Not sent: $why Still queued.") else it }
        }
    }

    /** How often, and how many times, the screen is read after Ctrl+B for the task to show in the background. */
    internal var backgroundCheckMs = 700L
    private val BACKGROUND_CHECKS = 4

    /**
     * Ctrl+B, then the message (2.2.7). A fresh read must still show Claude's background hint and
     * no question, panel or draft before Ctrl+B goes; then reads must show the hint gone (the
     * command moved to the background) or the agent idle before the message is sent, once.
     */
    private fun backgroundThenSend(item: QueuedPrompt, target: Pane) {
        // Pre-review M2: one attempt per tap, by its token. A replay of this action after a reconnect,
        // or a removed row, finds a different token (or none) and does nothing.
        val token = System.nanoTime()
        updateQueued(item.id) { it.copy(sending = true, sendingSince = System.currentTimeMillis(), sendToken = token, note = "Moving the running task to the background…") }
        fun mine() = queuedById(item.id)?.let { it.sending && it.sendToken == token } == true
        fun fail(why: String) = updateQueued(item.id) { if (it.sendToken == token) it.copy(sending = false, sendToken = 0L, note = "Not sent: $why. Still queued.") else it }
        act(target.id, "Couldn't move the task to the background", input = true) { h ->
            if (!mine()) return@act
            val connection = h.connectionIdentity
            try {
                suspend fun fresh(): String? = connection?.let {
                    runCatching { h.readStyledViewport(target.id, it) }.getOrNull()?.takeIf(String::isNotBlank)
                }
                val before = fresh() ?: return@act fail("its screen couldn't be read first")
                val now = pane(target.id)?.takeIf { it.sameCardOccupant(target) } ?: return@act fail("the pane changed")
                if (checkedSendBlocked(now, before, null) || usageDialogShown(now.agent, before)) return@act fail("a question or panel is open in it")
                holdReason(target.id, before)?.let { return@act fail(it) }
                val bound = connection?.let { c -> h.boundOccupant(target.id, c) }?.takeIf { it.first.sameCardOccupant(target) }
                    ?: return@act fail("the pane changed")
                if (!com.billmongan.herdrdroid.herdr.claudeBackgroundHint(before)) {
                    // The command finished, or went to the background on its own, since the chat's read.
                    if (com.billmongan.herdrdroid.herdr.agentWorking(now.status, before)) {
                        updateQueued(item.id) { it.copy(sending = false, sendToken = 0L, note = null) }
                        sendNowConfirm = queuedById(item.id)
                    } else dispatchQueued(item, now, token)
                    return@act
                }
                // 2.2.8 (Muse's review): behind the computer's own check of the occupant and the screen.
                if (!h.sendBackgroundKey(target.id, com.billmongan.herdrdroid.herdr.BackgroundStep.CLAUDE, before, bound.second, checkNotNull(connection)))
                    return@act fail("the running command finished, or a question opened, before Ctrl+B")
                com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${target.id}: send now pressed Ctrl+B; checking the task moved to the background")
                var moved = false
                for (i in 0 until BACKGROUND_CHECKS) {
                    delay(backgroundCheckMs)
                    val after = fresh() ?: continue
                    val p = pane(target.id) ?: now
                    if (checkedSendBlocked(p, after, null)) return@act fail("a question opened after Ctrl+B")
                    if (!com.billmongan.herdrdroid.herdr.claudeBackgroundHint(after) || !com.billmongan.herdrdroid.herdr.agentWorking(p.status, after)) {
                        moved = true
                        break
                    }
                }
                if (!moved) return@act fail("the task didn't show as moved to the background after Ctrl+B")
                if (!mine()) return@act // removed while the check ran (pre-review M1)
                com.billmongan.herdrdroid.diag.DiagLog.log("send", "to ${target.id}: send now saw the task in the background; sending")
                // 2.2.8: then keep moving foreground commands to the background until Claude takes it.
                dispatchQueued(item, pane(target.id) ?: now, token) { pushUntilTaken(target.id, item.text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never rethrown (pre-review M2): a replay could press Ctrl+B and send again. The row
                // stays queued for the normal path, and a lost link is handled as one.
                fail(com.billmongan.herdrdroid.diag.DiagLog.describe(e))
                if (e is LinkDown && herdr === h) linkLost(e)
            }
        }
    }

    // -- Send immediately on a message in the agent's own queue (2.2.8) ----------------------------

    /** The pending sends the chat shows greyed (in the agent's own queue), oldest first. */
    fun pendingSendsShown(paneId: String): List<PendingSend> {
        val shown = pendingFor(paneId).toSet()
        return pendingSends.filter { it.isFor(current?.id, paneId) && !it.removable && (it.text to it.at) in shown }
    }

    /** Pending sends (by id) whose Send immediately is running. */
    var pushingSends by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** Why Send immediately is off for [paneId]'s greyed messages now, or null when it can run. */
    fun sendImmediatelyBlock(paneId: String): String? {
        val p = pane(paneId) ?: return "That pane isn't open on the computer just now."
        backgroundSupport(p.agent, p.agentName)?.let { return it }
        if (link != Link.CONNECTED || herdr == null) return "Not connected."
        if (hasPendingQuestion(p)) return "Answer ${p.agentName}'s question first."
        return null
    }

    /**
     * Send immediately on a greyed message (2.2.8): it is already typed into the agent's own queue,
     * which the agent reads when its current step ends. A command running in the foreground holds
     * that step, so each time the screen shows one ([foregroundHint]) Ctrl+B moves it to the
     * background, until the message shows in the agent's log or the agent is idle. Never while a
     * question, approval or panel is open; never Esc or Ctrl+C. Bounded by [Backgrounder.Limits].
     */
    fun sendPendingImmediately(text: String, at: Long) {
        val ps = pendingSendsShown((screen as? Screen.Agent)?.paneId ?: return).firstOrNull { it.text == text && it.at == at } ?: return
        sendImmediatelyBlock(ps.paneId)?.let { flash(it); return }
        pushUntilTaken(ps.paneId, ps.text, ps.id)
    }

    internal var pushLimits = com.billmongan.herdrdroid.herdr.Backgrounder.Limits()

    private fun pushUntilTaken(paneId: String, text: String, knownId: Long? = null) {
        val target = pane(paneId) ?: return
        if (backgroundSupport(target.agent, target.agentName) != null) return
        val client = herdr ?: return
        val server = current?.id
        fun pendingId(): Long? = knownId?.takeIf { id -> pendingSends.any { it.id == id } }
            ?: pendingSends.lastOrNull { it.isFor(server, paneId) && !it.removable && it.text == text }?.id
        val id = pendingId() ?: return
        if (id in pushingSends) return
        pushingSends = pushingSends + id
        viewModelScope.launch {
            val outcome = try {
                runBackgrounder(client, server, target, pushLimits) { pendingSends.none { it.id == id } || !com.billmongan.herdrdroid.herdr.agentWorking(pane(paneId)?.status ?: Status.UNKNOWN, it) }
            } finally {
                pushingSends = pushingSends - id
            }
            com.billmongan.herdrdroid.diag.DiagLog.log("send", "to $paneId: send immediately ${outcome.name.lowercase()}")
            com.billmongan.herdrdroid.herdr.backgroundOutcomeText(outcome, target.agentName)?.let { flash(it) }
            if (current?.id == server) readPane(paneId)
        }
    }

    /**
     * 2.2.8: a [com.billmongan.herdrdroid.herdr.Backgrounder] run on [target] until [done]. Every read is
     * fresh and checked here (the occupant, a question, a panel, the server and the link); every key
     * goes through [Herdr.sendBackgroundKey], bound to the occupant digest read at the start, so the
     * computer types it only while its own read still shows the step's hint and no dialog.
     */
    private suspend fun runBackgrounder(
        client: Herdr, server: String?, target: Pane, limits: com.billmongan.herdrdroid.herdr.Backgrounder.Limits,
        onPressed: (com.billmongan.herdrdroid.herdr.BackgroundStep) -> Unit = {},
        permitted: () -> Boolean = { true },
        done: (String) -> Boolean,
    ): com.billmongan.herdrdroid.herdr.Backgrounder.Outcome {
        val paneId = target.id
        val lane = (server ?: return com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.UNREADABLE) to paneId
        val connection = client.connectionIdentity ?: return com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.UNREADABLE
        fun same() = permitted() && !locked && foreground && herdr === client && client.connectionIdentity === connection &&
            current?.id == server && link == Link.CONNECTED
        if (!same()) return com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.TARGET_CHANGED
        val bound = quietly { h -> if (h === client) h.boundOccupant(paneId, connection) else null }
            ?.takeIf { it.first.sameCardOccupant(target) && it.first.scrollOffset == 0 } ?: return com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.BLOCKED
        val port = object : com.billmongan.herdrdroid.herdr.Backgrounder.Port {
            override suspend fun screen(): String? =
                if (!same()) null else quietly { h -> if (h === client) h.readStyledViewport(paneId, connection) else null }?.takeIf { it.isNotBlank() }
            override fun blocked(screen: String): Boolean {
                val p = pane(paneId)?.takeIf { it.sameCardOccupant(target) } ?: return true
                return !same() || hasPendingQuestion(p) || checkedSendBlocked(p, screen, null) || usageDialogShown(p.agent, screen)
            }
            override fun done(screen: String) = done(screen)
            override suspend fun press(step: com.billmongan.herdrdroid.herdr.BackgroundStep, viewport: String): Boolean {
                val release = paneDispatch.tryAcquire(lane) ?: throw com.billmongan.herdrdroid.herdr.CardCheckFailed()
                try {
                    if (!canPoll || blocked(viewport)) return false
                    val permit = writeGate.open(lane, com.billmongan.herdrdroid.herdr.WritePermit(::same))
                        ?: throw com.billmongan.herdrdroid.herdr.CardCheckFailed()
                    try {
                        val went = client.sendBackgroundKey(paneId, step, viewport, bound.second, connection, permit)
                        com.billmongan.herdrdroid.diag.DiagLog.log("send", "to $paneId: Ctrl+B for ${step.check} ${if (went) "went" else "refused by the check"}")
                        if (went) onPressed(step)
                        return went
                    } finally {
                        writeGate.finish(lane, permit)
                    }
                } finally {
                    release()
                }
            }
            override suspend fun pause(ms: Long) = delay(ms)
        }
        return try {
            com.billmongan.herdrdroid.herdr.Backgrounder(target.agent, port, limits).run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.billmongan.herdrdroid.herdr.OccupantChanged) {
            com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.TARGET_CHANGED
        } catch (e: com.billmongan.herdrdroid.herdr.DeliveryUncertain) {
            com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.UNCERTAIN
        } catch (e: com.billmongan.herdrdroid.herdr.CardCheckFailed) {
            com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.CHECK_FAILED
        } catch (e: LinkDown) {
            if (herdr === client) linkLost(e)
            com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.UNREADABLE
        } catch (e: Exception) {
            com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.UNREADABLE
        }
    }

    // -- Foreground and background on the Running under page (2.2.8) ----------------------------

    /** The pane's latest screen read for the Running under page, by pane id. */
    var workScreens by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** Pids the Background button just moved, and when, until a read confirms or a timeout. */
    var movedToBackground by mutableStateOf<Map<String, Pair<Set<Int>, Long>>>(emptyMap())
        private set

    /** Reads the pane's screen for the page's Foreground and Background marks (Claude Code only). */
    suspend fun refreshWorkScreen(paneId: String) {
        val p = pane(paneId) ?: return
        if (p.isProbe || !p.agent.equals("claude", true)) return
        val client = herdr ?: return
        val connection = client.connectionIdentity
        val server = current?.id
        val s = quietly { h -> h.readStyled(paneId, sendReadLines(p.agent)) }?.takeIf { it.isNotBlank() }
        if (herdr !== client || client.connectionIdentity !== connection || current?.id != server ||
            pane(paneId)?.sameWorkIdentity(p) != true) return
        workScreens = if (s == null) workScreens - paneId else workScreens + (paneId to s)
        // A read with no foreground command confirms the move. The prediction expires even if the read fails.
        movedToBackground[paneId]?.let { (_, at) ->
            if (System.currentTimeMillis() - at > 10_000 ||
                (s != null && !com.billmongan.herdrdroid.herdr.claudeBackgroundHint(s)))
                movedToBackground = movedToBackground - paneId
        }
    }

    /** Where each shell under [paneId] runs, with the Background button's move shown at once. */
    internal fun shellPlaces(paneId: String): Map<Int, com.billmongan.herdrdroid.herdr.RunPlace> {
        val p = pane(paneId)
        val procs = work[paneId]?.activeProcs(p?.agent).orEmpty()
        return com.billmongan.herdrdroid.herdr.shellPlaces(p?.agent, workScreens[paneId], procs, movedToBackground[paneId]?.first.orEmpty())
    }

    /** The page's Background buttons in flight, by pane. */
    var backgroundingPanes by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * The page's Background button (2.2.8; Muse's review: never a blind Ctrl+B). Only for agents whose
     * move the screen shows ([backgroundSupport]); others say why and type nothing. Each key goes
     * through [runBackgrounder] (fresh reads, the computer's own check of the occupant and the screen
     * right before it). A shell the screen showed for certain in the foreground is marked Background
     * at once, and the next reads confirm it.
     */
    fun backgroundForeground(paneId: String) = backgroundForeground(paneId) { true }

    internal fun backgroundTerminalTask(paneId: String, frame: TerminalFrame?) {
        if (frame == null || frame.live.id != paneId || !terminalFrameMatches(paneId, frame, frame.source.client)) {
            flash("Wait for a verified read of this pane before moving its task.")
            return
        }
        backgroundForeground(paneId) { terminalFrameMatches(paneId, frame, frame.source.client) }
    }

    private fun backgroundForeground(paneId: String, permitted: () -> Boolean) {
        val p = pane(paneId) ?: return
        backgroundSupport(p.agent, p.agentName, manual = true)?.let { flash(it); return }
        if (hasPendingQuestion(p)) { flash("Not sent: ${p.agentName} has a question open. Answer it first."); return }
        val client = herdr ?: return
        if (paneId in backgroundingPanes) return
        val server = current?.id
        backgroundingPanes = backgroundingPanes + paneId
        viewModelScope.launch {
            var pressed: com.billmongan.herdrdroid.herdr.BackgroundStep? = null
            var fg: Set<Int> = emptySet()
            val muse = p.agent.equals("muse", true)
            var museTask: String? = null
            var musePreviousReceipts = 0
            val outcome = try {
                runBackgrounder(client, server, p, com.billmongan.herdrdroid.herdr.Backgrounder.Limits(maxPresses = if (muse) 1 else 2, maxReads = 8, pollMs = 700),
                    onPressed = { pressed = it }, permitted = permitted) { s ->
                    workScreens = workScreens + (paneId to s)
                    val step = com.billmongan.herdrdroid.herdr.backgroundStep(p.agent, s)
                    val last = pressed
                    if (muse) {
                        if (last == null) {
                            museTask = com.billmongan.herdrdroid.herdr.museForegroundTask(s)
                            musePreviousReceipts = museTask?.let {
                                com.billmongan.herdrdroid.herdr.museBackgroundReceiptCount(s, it)
                            } ?: 0
                        }
                        return@runBackgrounder last != null && museTask?.let {
                            com.billmongan.herdrdroid.herdr.museBackgroundConfirmed(s, it, musePreviousReceipts)
                        } == true
                    }
                    // Before any key: the shells this screen shows in the foreground for certain.
                    if (last == null) fg = com.billmongan.herdrdroid.herdr.shellPlaces(p.agent, s, work[paneId]?.activeProcs(p.agent).orEmpty())
                        .filterValues { it == com.billmongan.herdrdroid.herdr.RunPlace.FOREGROUND }.keys
                    // Moved: the hint the last key answered is gone (Agy: its confirmation is gone too).
                    last != null && step != last && step != com.billmongan.herdrdroid.herdr.BackgroundStep.AGY_CONFIRM
                }
            } finally {
                backgroundingPanes = backgroundingPanes - paneId
            }
            if (outcome == com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.DONE && fg.isNotEmpty())
                movedToBackground = movedToBackground + (paneId to (fg to System.currentTimeMillis()))
            flash(when (outcome) {
                com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.DONE -> "Moved to the background."
                com.billmongan.herdrdroid.herdr.Backgrounder.Outcome.TIMED_OUT -> if (muse && pressed != null)
                    "Ctrl+B reached ${p.agentName}, but the app could not verify that the shell moved."
                    else "Nothing running in the foreground in ${p.agentName} could be moved just now."
                else -> com.billmongan.herdrdroid.herdr.backgroundOutcomeText(outcome, p.agentName) ?: ""
            })
            if (current?.id == server) refreshWork(paneId)
        }
    }

    // -- Work under a pane ---------------------------------------------------------------------------

    /** The latest read of each pane's processes, subagents and background tasks. */
    var work by mutableStateOf<Map<String, com.billmongan.herdrdroid.herdr.WorkInfo>>(emptyMap())
        private set
    /** For the cards: active subagents plus running background tasks, per pane. */
    var workCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    private var busyCounts by mutableStateOf<Map<String, Int>>(emptyMap())
    private var rawWorkPanes = emptyList<Pane>()
    private var workSweep = 0L

    private fun applyWorkStatus() {
        panes = withBusy(rawWorkPanes, busyCounts)
        current?.let {
            trackDone(it.id, panes)
            serverPanes = serverPanes + (it.id to panes)
        }
        updateWidget(panes)
    }

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

    internal fun requiresLivePiQuestion(pane: Pane): Boolean =
        pane.agent == "pi" && pane.status == Status.WORKING && herdr?.backend == Backend.TUI

    internal fun currentPiQuestion(pane: Pane): Prompt? = piLiveQuestion?.takeIf {
        foreground && paneReadProblem == null && paneTextPane == pane.id && pane.scrollOffset == 0 &&
            paneTextTarget?.let { target -> target.pane.sameCardOccupant(pane) && target.isCurrent(target.client) } == true
    }

    fun shownStatus(p: Pane, serverId: String? = current?.id): Status {
        // 2.2.0: herdr's stale "blocked" on an OpenCode pane whose screen shows no dialog is shown as idle.
        if (p.openCodeStaleBlock && p.status == Status.NEEDS_YOU) return Status.IDLE
        val here = serverId == current?.id
        if (here && requiresLivePiQuestion(p) && currentPiQuestion(p) != null) return Status.NEEDS_YOU
        if (here && p.status == Status.NEEDS_YOU && hermesState != Status.NEEDS_YOU) {
            currentHermesUsage(p)?.let { return if (it.composerReady) hermesState ?: Status.IDLE else Status.WORKING }
        }
        // A done, idle or working pane ignores gateway waits proven to belong to other stored
        // conversations (2.1.5; before, only a done one did, and an idle pane looked stuck on a
        // question its screen never showed). The send guards use the same proof ([hermesGatewayHolds]).
        if (here && p.agent?.lowercase() == "hermes" && p.status != Status.NEEDS_YOU) hermesState?.let {
            if (it == Status.NEEDS_YOU) {
                if (!hermesWaitsAreElsewhere(p)) return it
            } else if (p.status != Status.WORKING) return it
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

    private fun hermesWaitsAreElsewhere(pane: Pane): Boolean {
        val key = pane.sessionId?.takeIf { it.isNotBlank() } ?: return false
        val states = hermesLiveStates
        // The pane may be outside this inventory or its reported ID may be stale.
        // Require a unique stored-key match; do not guess from runtime ID or current.
        return states.count { it.sessionKey == key } == 1 &&
            states.none { it.status == "waiting" && (it.sessionKey == null || it.sessionKey == key) }
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
        val p = pane(paneId)?.takeUnless { it.isProbe } ?: return
        val client = herdr ?: return
        val connection = client.connectionIdentity
        quietly { it.work(p, transcriptScript) }?.let { inventory ->
            val reported = if (connection != null && p.agent.equals("claude", true) && hasStableOccupant(p)) quietly { h ->
                if (h !== client) return@quietly null
                val before = h.relayPane(paneId, connection)
                    ?.takeIf { it.scrollOffset == 0 && sameOccupant(p, it) } ?: return@quietly null
                val visible = h.readStyledViewport(paneId, connection, timeoutSeconds = 2)
                val after = h.relayPane(paneId, connection)
                if (after == null || after.scrollOffset != 0 || !sameOccupant(before, after)) return@quietly null
                com.billmongan.herdrdroid.herdr.visibleBackgroundShellCount(p.agent, visible)
            } else null
            if (herdr !== client || client.connectionIdentity !== connection ||
                pane(paneId)?.let { !it.isProbe && it.sameWorkIdentity(p) } != true) return
            val fresh = inventory.copy(reportedBackgroundShells = reported)
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
    suspend fun readHit(hit: com.billmongan.herdrdroid.herdr.SearchHit): Result<com.billmongan.herdrdroid.transcript.TranscriptRead> {
        val h = herdr ?: return Result.failure(IllegalStateException("Not connected."))
        val pane = Pane(id = "search", tabId = "", workspaceId = "", agent = hit.agent, status = Status.UNKNOWN, title = "", cwd = "/")
        val s = PastSession(hit.ref, hit.db, hit.whenSec, hit.title, 0)
        return runCatching {
            val r = h.transcript(pane, s.pinCursor(), transcriptScript) ?: throw IllegalStateException("No reader for ${hit.agent}.")
            if (!r.ok) throw IllegalStateException(r.why ?: "That session couldn't be read.")
            r
        }
    }

    suspend fun refreshWorkCounts() {
        val client = herdr ?: return
        val request = ++workSweep
        val connection = client.connectionIdentity
        val requested = rawWorkPanes.associateBy { it.id }
        quietly { it.workCounts(requested.values.toList(), transcriptScript) }?.let { (counts, busy) ->
            if (herdr !== client || client.connectionIdentity !== connection || request != workSweep) return
            val unchanged = rawWorkPanes.filter { p ->
                requested[p.id]?.sameWorkIdentity(p) == true
            }.map { it.id }.toSet()
            workCounts = counts.filterKeys { it in unchanged }
            busyCounts = busy.filterKeys { it in unchanged }
            applyWorkStatus()
        }
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
    suspend fun readPastSession(paneId: String, s: PastSession): Result<com.billmongan.herdrdroid.transcript.TranscriptRead> {
        val p = pane(paneId) ?: return Result.failure(IllegalStateException("That pane is gone."))
        val h = herdr ?: return Result.failure(IllegalStateException("Not connected."))
        return runCatching {
            val r = h.transcript(p, s.pinCursor(), transcriptScript) ?: throw IllegalStateException("No agent in this pane.")
            if (!r.ok) throw IllegalStateException(r.why ?: "That session couldn't be read.")
            r
        }
    }

    /** Shares a conversation as a Markdown file. */
    fun exportMarkdown(context: android.content.Context, title: String, agent: String, items: List<Item>) {
        val md = toMarkdown(title, agent, items)
        val name = title.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(60).ifBlank { "conversation" } + ".md"
        com.billmongan.herdrdroid.ui.shareBytes(context, md.toByteArray(), name, "text/markdown", open = false)
    }

    // -- Mode, thinking and sessions (2.2.0) --------------------------------------------------------

    /** The session each open pane's terminal is in, by "server|pane", from the log reader. */
    var liveSessions by mutableStateOf<Map<String, com.billmongan.herdrdroid.transcript.LiveSession>>(emptyMap())
        private set

    /** A mode, thinking or session change running from the app, by "server|pane": what it is doing now. */
    var paneControlBusy by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** herdr's session id when the app started a new session or switched, until herdr reports another, by "server|pane". */
    private var sessionAsked by mutableStateOf<Map<String, Pair<String?, Long>>>(emptyMap())

    fun liveSession(paneId: String): com.billmongan.herdrdroid.transcript.LiveSession? = liveSessions[ck(paneId)]

    fun paneControlBusy(paneId: String): String? = paneControlBusy[ck(paneId)]

    /**
     * Whether herdr's record of the pane's session is behind the terminal (2.2.0): the agent's own
     * record names another session, or the app just started or switched one and herdr hasn't heard
     * yet (OpenCode reports a new session only when its first message is sent). Display only.
     */
    fun herdrOutOfDate(paneId: String): Boolean {
        val p = pane(paneId) ?: return false
        val spec = com.billmongan.herdrdroid.herdr.SessionControls.forAgent(p.agent)
        if (spec?.herdrTracks == false) return false
        if (liveSession(paneId)?.herdrStale == true) return true
        val (was, at) = sessionAsked[ck(paneId)] ?: return false
        return p.sessionId == was && System.currentTimeMillis() - at < 30 * 60_000L
    }

    private fun paneIdle(paneId: String, what: String): Pane? {
        val p = pane(paneId) ?: return null
        if (link != Link.CONNECTED || herdr == null) { error = "$what: not connected."; return null }
        if (p.status == Status.WORKING) { error = "${p.agentName} is working. $what once it's done."; return null }
        if (p.status == Status.NEEDS_YOU) { error = "${p.agentName} is waiting for an answer. $what after that."; return null }
        if (paneControlBusy[ck(paneId)] != null) { error = "$what: the last change is still being checked."; return null }
        return p
    }

    private inline fun <T> controlling(paneId: String, what: String, block: () -> T): T {
        val key = ck(paneId)
        paneControlBusy = paneControlBusy + (key to what)
        try { return block() } finally { paneControlBusy = paneControlBusy - key }
    }

    /** What a menu action writes into the pane, through [menuWrite]: keys, or one command and Enter. */
    private sealed interface MenuInput {
        data class Keys(val keys: List<String>) : MenuInput
        data class Command(val text: String) : MenuInput
    }

    /**
     * One menu write (2.2.0, from Grok's review): through the same guarded path as a normal send. The
     * pane's dispatch lane and write gate, a fresh read of the pane and its screen, the same occupant
     * and connection, idle and at the bottom of its scrollback, an empty input line with no draft, no
     * question, dialog or usage panel, and nothing of the app's own waiting to go in. A command goes as
     * herdr's agent prompt (on herdr-core an unforced `hc send`), never typed over a question.
     * Null when it went; otherwise why it didn't.
     */
    private suspend fun menuWrite(paneId: String, expected: com.billmongan.herdrdroid.herdr.Occupant, h: Herdr, connection: Any,
                                  server: String, input: MenuInput, check: ((String) -> String?)? = null): String? {
        fun allowed() = current?.id == server && herdr === h && h.connectionIdentity === connection &&
            link == Link.CONNECTED && promptLaneClear(server, paneId)
        var why: String? = null
        val port = object : com.billmongan.herdrdroid.herdr.LocalPromptSend.Port {
            override suspend fun pane(id: String) =
                quietly(allowBackground = true) { if (it === h) it.panes().singleOrNull { p -> p.id == id } else null }
            override suspend fun viewport(id: String) =
                quietly(allowBackground = true) { if (it === h) it.readStyled(id, sendReadLines(expected.agent)) else null }
            override fun blocked(fresh: Pane, viewport: String): Boolean {
                // 2.2.2: [check] judges the same fresh read (a toggle's mode must still be the one it was chosen for).
                why = menuWriteBlock(fresh, viewport) ?: check?.invoke(viewport)
                return why != null
            }
            override suspend fun send(fresh: Pane, text: String, permit: WritePermit) = when (input) {
                is MenuInput.Keys -> { h.sendPermittedKeys(fresh.id, input.keys, connection, permit); Unit }
                is MenuInput.Command -> h.sendPermitted(fresh, input.text, connection, permit)
            }
        }
        val r = localPromptSend.send(server, expected, (input as? MenuInput.Command)?.text ?: "", ::allowed, port)
        (r.failure as? LinkDown)?.let { if (herdr === h) linkLost(it) }
        return when (r.outcome) {
            com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.SENT -> null
            com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.UNCERTAIN -> "the app couldn't confirm it went in; check the pane"
            com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.NO_IDENTITY -> "herdr doesn't say which session is in the pane, so it can't be checked"
            com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.CONNECTION_CHANGED -> "the SSH connection changed"
            com.billmongan.herdrdroid.herdr.LocalPromptSend.Outcome.REFUSED ->
                why ?: "the pane isn't idle at the bottom of its screen, or the app has something waiting to go in"
        }
    }

    /** Why a menu write must wait, from the fresh read: the checks of a normal send, said plainly. */
    private fun menuWriteBlock(fresh: Pane, viewport: String): String? {
        if (fresh.backgroundBusy) return "it is running work in the background"
        // 2.2.1: an informational panel over the composer is closed first when it is positively known.
        if (com.billmongan.herdrdroid.herdr.infoPanelOn(fresh.agent, viewport) != null) return panelHold(current?.id, fresh, viewport)
        com.billmongan.herdrdroid.herdr.PaneControls.writeBlock(fresh.agent, viewport)?.let { return it }
        if (blocksNormalSend(fresh, viewport)) return "a question, dialog or usage panel is open in it"
        return holdReasonFor(fresh.id, viewport, menuWrite = true)
    }

    /** A menu write that didn't go: said in the pane's chat, nothing sent. */
    private fun menuRefused(paneId: String, what: String, why: String) {
        error = "$what: not sent, because $why."
        com.billmongan.herdrdroid.diag.DiagLog.log("menu", "$paneId: $what refused: $why")
    }

    /** The guarded identity a menu action keeps: this server, this client and connection, this occupant. */
    private data class MenuTarget(val server: String, val h: Herdr, val connection: Any, val occupant: com.billmongan.herdrdroid.herdr.Occupant)

    private fun menuTarget(p: Pane): MenuTarget? {
        val h = herdr ?: return null
        val connection = h.connectionIdentity ?: return null
        val server = current?.id ?: return null
        return MenuTarget(server, h, connection, com.billmongan.herdrdroid.herdr.Occupant.of(p))
    }

    /**
     * Switches the pane's permission mode to [target] (2.2.0): presses the agent's own key one step at a
     * time, each press through [menuWrite], and reads the footer after each, until the footer shows
     * [target]. A target the session's cycle doesn't hold goes on round to the starting mode. It never
     * stops on a mode that approves everything that wasn't the confirmed target: it presses on to the
     * start, and if it can't get back it says so loudly, in the chat and as an alert.
     */
    fun switchMode(paneId: String, target: String, title: String = "Mode") {
        val p = paneIdle(paneId, "Change the ${title.lowercase()}") ?: return
        val spec = com.billmongan.herdrdroid.herdr.PaneControls.modeMenus(p.agent).firstOrNull { it.title == title } ?: return
        val t = menuTarget(p) ?: return
        viewModelScope.launch {
            var last: String? = null
            /** 2.2.2 (from review): a press after which the mode couldn't be read at all. */
            var unread = false
            controlling(paneId, "Changing the ${title.lowercase()}…") {
                val outcome = runCatching {
                    val first = readPaneScreen(t.h, paneId, 60)
                    var mode = spec.read(first)
                    last = mode
                    // 2.2.2: where the agent has a command for this switch, it goes once, as a menu write,
                    // and the screen is read until it shows the target (or the agent's reply names it).
                    // The command is chosen from this read; the guarded write's own fresh read must show
                    // the same mode, or nothing is sent (a toggle would otherwise flip the other way).
                    val cmd = spec.commandFor(mode, target)?.takeIf { mode != null || spec.absolute }
                    if (cmd != null) {
                        val unchanged: (String) -> String? = { v ->
                            val now = spec.read(v)
                            when {
                                now != mode -> "the ${title.lowercase()} changed at the computer"
                                else -> null
                            }
                        }
                        menuCommand(paneId, t, cmd, unchanged)?.let { return@runCatching "$cmd wasn't sent, because $it" }
                        for (i in 0 until 16) {
                            kotlinx.coroutines.delay(300)
                            val after = runCatching { readPaneScreen(t.h, paneId, 60) }.getOrNull() ?: continue
                            val said = spec.replied?.invoke(first, after)
                            val seen = said ?: spec.read(after)
                            if (seen != null) last = seen
                            if (seen == target) return@runCatching null
                            if (com.billmongan.herdrdroid.herdr.PaneControls.unconfirmedApproveAll(spec, seen, target)) return@runCatching "it shows ${seen} after $cmd"
                            if (said != null) return@runCatching "${p.agentName} replied with another mode after $cmd"
                        }
                        // Nothing confirmed: if the command opened the agent's own picker, close it.
                        closeOpenedPicker(paneId, t, p.agentName)?.let { return@runCatching "the screen didn't confirm it after $cmd; $it" }
                        return@runCatching "the screen didn't confirm it after $cmd"
                    }
                    val start = mode ?: return@runCatching "the footer doesn't show the mode, so nothing was sent"
                    // A command-only menu whose command doesn't fit the mode on screen now: nothing is sent.
                    if (spec.key.isEmpty()) return@runCatching "the mode on screen changed, so nothing was sent"
                    val sw = com.billmongan.herdrdroid.herdr.PaneControls.ModeSwitcher(spec, start, target)
                    var step = sw.next(mode)
                    while (true) {
                        when (step) {
                            is com.billmongan.herdrdroid.herdr.PaneControls.Step.Done -> return@runCatching null
                            is com.billmongan.herdrdroid.herdr.PaneControls.Step.Stop -> return@runCatching step.why
                            com.billmongan.herdrdroid.herdr.PaneControls.Step.Press -> {
                                val before = mode
                                menuWrite(paneId, t.occupant, t.h, t.connection, t.server, MenuInput.Keys(listOf(spec.key)))
                                    ?.let { return@runCatching "${spec.key} wasn't sent, because $it" }
                                // The footer redraws after the key; wait for it to change, up to 2.5 s.
                                var seen: String? = before
                                for (i in 0 until 10) {
                                    kotlinx.coroutines.delay(250)
                                    seen = spec.read(readPaneScreen(t.h, paneId, 60))
                                    if (seen != before) break
                                }
                                mode = seen
                                last = seen
                                if (seen == null) {
                                    // 2.2.2 (from review): an unreadable mode after a press is unknown, never
                                    // safe: no more presses, and the alarm below says to check at the computer.
                                    unread = true
                                    return@runCatching "the mode couldn't be read after ${spec.key}"
                                }
                                step = if (seen == before) sw.unchanged(seen) else sw.next(seen)
                            }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }.getOrElse { "the pane couldn't be read (${it.message})" }
                val label = spec.options.firstOrNull { it.name == target }?.label ?: target
                val stuck = com.billmongan.herdrdroid.herdr.PaneControls.unconfirmedApproveAll(spec, last, target)
                when {
                    stuck -> modeAlarm(paneId, p, spec.options.firstOrNull { it.name == last }?.label ?: last.orEmpty(), outcome ?: "")
                    unread -> modeUnknownAlarm(paneId, p, outcome.orEmpty())
                    outcome == null -> notice = "$title: $label (confirmed on screen)"
                    else -> error = "$title not changed to $label: $outcome."
                }
                com.billmongan.herdrdroid.diag.DiagLog.log("mode", "$paneId: to $target: ${outcome ?: "done"}, now $last")
            }
            readPane(paneId)
        }
    }

    /** 2.2.2: a key press left the mode unreadable: not confirmed, said loudly in the chat, as an error and as an alert. */
    private fun modeUnknownAlarm(paneId: String, p: Pane, why: String) {
        if (p.isProbe || current?.id?.let { probePane(it, paneId) } == true) return
        val text = "${p.agentName}'s mode is not confirmed" + (if (why.isNotBlank()) " ($why)" else "") + "; check it at the computer."
        error = text
        current?.id?.let { noteSendProblem(it, paneId, text) }
        runCatching {
            val app = getApplication<Application>()
            com.billmongan.herdrdroid.notify.Notify.agentError(app, current?.id.orEmpty(), current?.name.orEmpty(), paneId, p.agentName, text)
        }
    }

    /**
     * 2.2.2 (from review): after a mode command that wasn't confirmed, a picker the agent opened instead
     * is closed through [com.billmongan.herdrdroid.herdr.closePanelAndVerify]: Esc only while the same
     * picker, with an Esc hint in its rows, is on two reads in a row, at most twice, and only "closed"
     * once the input box is back. Null when nothing was open; otherwise what happened.
     */
    private suspend fun closeOpenedPicker(paneId: String, t: MenuTarget, agentName: String): String? {
        val agent = pane(paneId)?.agent ?: return null
        fun pickerOn(v: String): Boolean {
            val text = com.billmongan.herdrdroid.herdr.forPhone(v)
            val menuOpen = detectPrompt(text, blocked = true, agent = agent)?.takeIf { it.choices.size >= 2 } != null ||
                com.billmongan.herdrdroid.herdr.detectUnnumberedMenu(text) != null
            val escHint = text.lines().filter { it.isNotBlank() }.takeLast(6).any { Regex("""(?i)\besc\b""").containsMatchIn(it) }
            return menuOpen && escHint
        }
        val firstLook = runCatching { readPaneScreen(t.h, paneId, 60) }.getOrNull() ?: return null
        if (com.billmongan.herdrdroid.herdr.inputLineOf(firstLook, agent, menuWrite = true) == "" && !pickerOn(firstLook)) return null
        if (!pickerOn(firstLook)) return "something else is on its screen; check the pane"
        val occupant = t.occupant.toString()
        val outcome = com.billmongan.herdrdroid.herdr.closePanelAndVerify(
            stillCurrent = { current?.id == t.server && herdr === t.h && t.h.connectionIdentity === t.connection && pane(paneId)?.agent == agent },
            readState = {
                val v = readPaneScreen(t.h, paneId, 60)
                when {
                    pickerOn(v) -> com.billmongan.herdrdroid.herdr.PanelState.Open(com.billmongan.herdrdroid.herdr.UsageOverlay(occupant, v))
                    com.billmongan.herdrdroid.herdr.inputLineOf(v, agent, menuWrite = true) == "" -> com.billmongan.herdrdroid.herdr.PanelState.Closed
                    else -> com.billmongan.herdrdroid.herdr.PanelState.Stop("the screen shows something else")
                }
            },
            sendEscape = { shown ->
                val again = readPaneScreen(t.h, paneId, 60)
                if (again != shown.visible) false else { t.h.sendKeys(paneId, "esc"); true }
            },
            settle = { kotlinx.coroutines.delay(PANEL_SETTLE_MS) },
        )
        com.billmongan.herdrdroid.diag.DiagLog.log("mode", "$paneId: picker after a mode command: " +
            if (outcome.closed) "closed, ${outcome.escapes} Esc" else "not closed, ${outcome.why}")
        return if (outcome.closed) "$agentName opened its own picker instead, so it was closed and nothing was switched"
            else "$agentName opened its own picker, and it couldn't be closed (${outcome.why}); close it at the computer"
    }

    /** The mode switch ended on a mode that approves every tool the user didn't pick: said in the chat, as an error and as an alert. */
    private fun modeAlarm(paneId: String, p: Pane, mode: String, why: String) {
        if (p.isProbe || current?.id?.let { probePane(it, paneId) } == true) return
        val text = "${p.agentName} is in $mode, which approves every tool, and the app couldn't switch it back" +
            (if (why.isNotBlank()) " ($why)" else "") + ". Change its mode at the computer now."
        error = text
        current?.id?.let { noteSendProblem(it, paneId, text) }
        runCatching {
            val app = getApplication<Application>()
            com.billmongan.herdrdroid.notify.Notify.agentError(app, current?.id.orEmpty(), current?.name.orEmpty(), paneId, p.agentName, text)
        }
    }

    /**
     * One command typed into the pane through [menuWrite]; null when it went. 2.2.2 (from review): where
     * typed spaces can't be told from an empty box (Oh My Pi, Copilot), the agent's own delete-to-line-
     * start key goes first, through the same guarded write (a no-op on an empty line), so the command
     * never goes in after spaces as a chat message.
     */
    private suspend fun menuCommand(paneId: String, t: MenuTarget, text: String, check: ((String) -> String?)? = null): String? {
        com.billmongan.herdrdroid.herdr.AgentComposers.clearKey(t.occupant.agent)?.let { k ->
            menuWrite(paneId, t.occupant, t.h, t.connection, t.server, MenuInput.Keys(listOf(k)), check)?.let { return "$k wasn't sent, because $it" }
            kotlinx.coroutines.delay(200)
        }
        return menuWrite(paneId, t.occupant, t.h, t.connection, t.server, MenuInput.Command(text), check)
    }

    /** Sets the pane's thinking level with the agent's own command and confirms it from the screen (2.2.0). */
    fun setThinking(paneId: String, level: String) {
        val p = paneIdle(paneId, "Change the thinking level") ?: return
        val spec = com.billmongan.herdrdroid.herdr.PaneControls.thinking(p.agent) ?: return
        if (level !in spec.levels) return
        val t = menuTarget(p) ?: return
        viewModelScope.launch {
            controlling(paneId, "Setting thinking to $level…") {
                val before = runCatching { readPaneScreen(t.h, paneId, 80) }.getOrNull()
                val refused = if (before == null) "the pane couldn't be read" else menuCommand(paneId, t, spec.command(level))
                if (refused != null) { menuRefused(paneId, "Thinking", refused); return@controlling }
                var seen = false
                for (i in 0 until 16) {
                    kotlinx.coroutines.delay(300)
                    val after = runCatching { readPaneScreen(t.h, paneId, 80) }.getOrNull() ?: continue
                    if (com.billmongan.herdrdroid.herdr.PaneControls.thinkingConfirmed(spec, level, before!!, after)) { seen = true; break }
                }
                if (seen) { notice = "Thinking: $level (confirmed on screen)"; efforts = efforts + (paneId to level) }
                else error = "Sent ${spec.command(level)}, but the screen didn't confirm it. Check the pane: the model may not offer $level."
            }
            readPane(paneId)
        }
    }

    /** Starts a new session in the pane with the agent's own command (2.2.0); the chat follows the terminal. */
    fun newSession(paneId: String) {
        val p = paneIdle(paneId, "Start a new session") ?: return
        val spec = com.billmongan.herdrdroid.herdr.SessionControls.forAgent(p.agent) ?: return
        sessionCommand(paneId, p, "New session", spec.newCommand) {}
    }

    /**
     * Switches the pane to past session [s] with the agent's resume command; where that takes no id it
     * opens the agent's own picker and calls [onPicker], so the app reads the picker as a menu.
     */
    fun resumeSession(paneId: String, s: PastSession, onPicker: () -> Unit) {
        val p = paneIdle(paneId, "Switch sessions") ?: return
        val spec = com.billmongan.herdrdroid.herdr.SessionControls.forAgent(p.agent) ?: return
        val cmd = spec.resume(s)
        if (cmd != null) sessionCommand(paneId, p, "Switch sessions", cmd) {}
        else spec.picker?.let { sessionCommand(paneId, p, "Session picker", it, onPicker) }
    }

    /** Opens the agent's own session picker (2.2.0); [onSent] starts reading it as a menu. */
    fun openSessionPicker(paneId: String, onSent: () -> Unit) {
        val p = paneIdle(paneId, "Open the session picker") ?: return
        val cmd = com.billmongan.herdrdroid.herdr.SessionControls.forAgent(p.agent)?.picker ?: return
        sessionCommand(paneId, p, "Session picker", cmd, onSent)
    }

    private fun sessionCommand(paneId: String, p: Pane, what: String, cmd: String, onSent: () -> Unit) {
        val t = menuTarget(p) ?: return
        viewModelScope.launch {
            controlling(paneId, "$what…") {
                val refused = menuCommand(paneId, t, cmd)
                if (refused != null) { menuRefused(paneId, what, refused); return@controlling }
                sessionAsked = sessionAsked + (ck(paneId) to (p.sessionId to System.currentTimeMillis()))
                onSent()
            }
            readPane(paneId)
            rebindSoon(paneId)
        }
    }

    /**
     * Reads the log again a few times after a session change, so the chat rebinds to the terminal's
     * session at once: the reader finds the new session and starts the chat afresh from it.
     */
    private fun rebindSoon(paneId: String) {
        viewModelScope.launch {
            for (wait in listOf(1_500L, 3_000L, 6_000L)) {
                kotlinx.coroutines.delay(wait)
                if ((screen as? Screen.Agent)?.paneId != paneId) return@launch
                readTranscript(paneId)
            }
        }
    }

    // -- Agent errors (2.2.0) ------------------------------------------------------------------------

    /**
     * Errors acknowledged on the phone, by server, pane, session and the error's own identity
     * (2.2.0, Bill: "should clear the error on the pane when I acknowledge the notice in the app").
     * OpenCode draws its error in the transcript, where no key removes it, so clearing it means the
     * app stops showing it: the same error stays cleared, and a new one shows again.
     */
    private val acknowledgedErrors = mutableStateMapOf<String, Long>()
    /** Errors already retried once, by the same key: Retry never sends twice. */
    private val retriedErrors = mutableStateMapOf<String, Long>()

    private fun errorKey(serverId: String?, pane: Pane, notice: com.billmongan.herdrdroid.herdr.AgentErrorNotice) =
        listOf(serverId.orEmpty(), pane.id, pane.sessionId.orEmpty(), notice.identity).joinToString("|")

    internal fun errorAcknowledged(serverId: String?, pane: Pane, notice: com.billmongan.herdrdroid.herdr.AgentErrorNotice): Boolean =
        errorKey(serverId, pane, notice) in acknowledgedErrors

    /** The error card for the open pane, or null when there is none or it was acknowledged. */
    fun errorNoticeFor(pane: Pane, screen: String): com.billmongan.herdrdroid.herdr.AgentErrorNotice? =
        com.billmongan.herdrdroid.herdr.agentErrorNotice(screen, pane.agent, pane.agentName)
            ?.takeUnless { errorAcknowledged(current?.id, pane, it) }

    /** Acknowledge: the card goes, and so does the pane's error alert. Nothing is typed into the pane. */
    fun acknowledgeError(pane: Pane, notice: com.billmongan.herdrdroid.herdr.AgentErrorNotice) {
        val serverId = current?.id
        acknowledgedErrors[errorKey(serverId, pane, notice)] = System.currentTimeMillis()
        com.billmongan.herdrdroid.diag.DiagLog.log("prompt", "${pane.id}: error acknowledged (${notice.summary})")
        if (serverId != null) runCatching { Notify.clear(getApplication<Application>(), serverId, pane.id) }
    }

    /**
     * The message Retry would resend, or null: the message the phone itself last sent to [pane]
     * on this server, when it is exactly the one above OpenCode's failed turn, and it was not
     * retried for this error already. A message from elsewhere (typed at the computer, or the app's
     * own notice) is never resent.
     */
    fun retryTextFor(pane: Pane, notice: com.billmongan.herdrdroid.herdr.AgentErrorNotice): String? {
        val shown = notice.retryText ?: return null
        val (paneId, text) = lastSent ?: return null
        if (paneId != pane.id || errorKey(current?.id, pane, notice) in retriedErrors) return null
        fun norm(t: String) = t.trim().replace(Regex("\\s+"), " ")
        return text.takeIf { norm(it) == norm(shown) }
    }

    /**
     * Retry: the same message once more, through the usual guarded send (a fresh read, no dialog,
     * an empty input box, held while a draft is typed), only while the agent is not working.
     */
    fun retryAfterError(pane: Pane, notice: com.billmongan.herdrdroid.herdr.AgentErrorNotice) {
        val text = retryTextFor(pane, notice) ?: return
        val fresh = pane(pane.id) ?: return
        val shown = shownStatus(fresh)
        if (shown == Status.WORKING || shown == Status.NEEDS_YOU) {
            error = "${pane.agentName} isn't idle. Retry once it is."
            return
        }
        retriedErrors[errorKey(current?.id, pane, notice)] = System.currentTimeMillis()
        com.billmongan.herdrdroid.diag.DiagLog.log("send", "${pane.id}: retry after error (${notice.summary})")
        sendMessage(fresh, text)
    }

    // -- Model -------------------------------------------------------------------------------------

    /** Types a model command into an idle pane and presses Enter (the agent's own picker answers it). */
    fun runCommand(paneId: String, command: String, sent: () -> Unit = {}) {
        val p = paneIdle(paneId, "Change the model") ?: return
        if (p.status != Status.IDLE && p.status != Status.DONE) return
        val t = menuTarget(p) ?: return
        viewModelScope.launch {
            val why = controlling(paneId, "model") {
                menuWrite(paneId, t.occupant, t.h, t.connection, t.server, MenuInput.Command(command))
            }
            if (why != null) menuRefused(paneId, "Change the model", why)
            else if (herdr === t.h && t.connection === t.h.connectionIdentity && (screen as? Screen.Agent)?.paneId == paneId) sent()
        }
    }

    // -- Forwarding -------------------------------------------------------------------------------

    /** Text waiting to be put in a pane's message box, per (server, pane) (2.1.1: pane ids repeat across servers). */
    private val prefills = mutableStateMapOf<String, String>()

    private fun prefillKey(serverId: String?, paneId: String) = "$serverId|$paneId"

    /** Opens [to] with a reply from another agent already in its message box. */
    fun forward(text: String, from: Pane?, to: Pane) {
        prefills[prefillKey(current?.id, to.id)] = "From ${from?.agentName ?: "another agent"}:\n\n" + text.trim() + "\n\n"
        openPane(to.id)
    }

    fun takePrefill(paneId: String): String? = prefills.remove(prefillKey(current?.id, paneId))

    // ---- Unsent drafts in the message box (2.2.7) ------------------------------------------------

    /**
     * 2.2.7 (Bill: "unsent text typed into the message box can disappear when switching screens
     * briefly and returning"): each pane's unsent text, by server and pane, kept here rather than
     * in the screen, so it outlives Settings, the server list, another pane and a configuration
     * change; sealed on the phone for a few hours so a brief app switch that ends the process
     * keeps it too. Never shown in another pane or server, never sent by itself.
     */
    private val composerDrafts = mutableStateMapOf<String, String>()
    /** Text handed to a send and not yet delivered, by the same key (see [composerDraft]). */
    private val draftsInFlight = mutableStateMapOf<String, String>()
    private fun draftKey(serverId: String, paneId: String) = "$serverId|$paneId"
    private val draftStore = app.getSharedPreferences("composer-drafts", android.content.Context.MODE_PRIVATE)
    private var draftSave: Job? = null

    /**
     * The unsent text for [paneId] on the open server. A send that was never delivered (its pending
     * row was removed) gives its text back here; while its pending row is up (with Resend), it
     * does not, so a message never shows in two places to be sent twice.
     */
    fun composerDraft(paneId: String): String {
        val sid = current?.id.orEmpty()
        val key = draftKey(sid, paneId)
        composerDrafts[key]?.let { return it }
        val back = draftsInFlight[key] ?: return ""
        return if (pendingSends.any { it.isFor(sid, paneId) && it.text == back }) "" else back
    }

    fun setComposerDraft(paneId: String, text: String) {
        val sid = current?.id.orEmpty()
        val key = draftKey(sid, paneId)
        if (text.isEmpty()) composerDrafts.remove(key) else composerDrafts[key] = text
        if (text.isNotEmpty()) draftsInFlight.remove(key)
        saveDrafts()
    }

    /** The message box sends [text]: it leaves the box now, and its draft ends once delivered. */
    fun sendFromComposer(pane: Pane, text: String) {
        val sid = current?.id.orEmpty()
        val key = draftKey(sid, pane.id)
        composerDrafts.remove(key)
        draftsInFlight[key] = text
        saveDrafts()
        sendMessage(pane, text) { if (draftsInFlight[key] == text) draftsInFlight.remove(key) }
    }

    /** An explicit clear (or the text moved to the idle queue): gone from the box and the phone. */
    fun clearComposerDraft(paneId: String) {
        val sid = current?.id.orEmpty()
        composerDrafts.remove(draftKey(sid, paneId)); draftsInFlight.remove(draftKey(sid, paneId))
        saveDrafts()
    }

    private fun saveDrafts() {
        draftSave?.cancel()
        val snapshot = composerDrafts.toMap()
        draftSave = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            runCatching {
                if (snapshot.isEmpty()) { draftStore.edit().remove("sealed").apply(); return@runCatching }
                val json = org.json.JSONObject().put("at", System.currentTimeMillis()).put("drafts", org.json.JSONObject(snapshot)).toString()
                val sealed = DraftSeal.seal(json.toByteArray())
                draftStore.edit().putString("sealed", android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP)).apply()
            }
        }
    }

    private fun loadDrafts() {
        runCatching {
            val b64 = draftStore.getString("sealed", null) ?: return
            val json = org.json.JSONObject(String(DraftSeal.open(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP))))
            if (System.currentTimeMillis() - json.optLong("at") > DRAFT_KEEP_MS) { draftStore.edit().remove("sealed").apply(); return }
            val d = json.optJSONObject("drafts") ?: return
            d.keys().forEach { k -> d.optString(k).takeIf { it.isNotEmpty() }?.let { composerDrafts[k] = it } }
        }
    }

    /** How long unsent drafts are kept on the phone after the app last saved them. */
    private val DRAFT_KEEP_MS = 6 * 60 * 60_000L

    init { loadDrafts() }

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

    /**
     * 2.2.8: everything this view model started that its scope doesn't own: the reconnect, the
     * other servers' watcher and their connections, Hermes's chat, and the two process-wide
     * listeners (left alone when a newer view model already holds them).
     */
    override fun onCleared() {
        // 2.2.8 (Bill: "be more robust about managing and closing stale connections"): every
        // connection the app holds is closed when it goes: the open server's, the one kept from a
        // moment ago, one still signing in, and the watcher's (whose loop stops too). The groundwork
        // lane adds the reconnect, the network recheck, Hermes's chat, the updater and the listener.
        reconnectJob?.cancel()
        networkRecheck?.cancel()
        ssh?.close()
        parked?.ssh?.close()
        parked = null
        connectingSsh?.close()
        fleet?.stop()
        fleet = null
        hermes?.close()
        updates.release()
        if (com.billmongan.herdrdroid.diag.NetworkWatch.listener === networkListener) com.billmongan.herdrdroid.diag.NetworkWatch.listener = null
        super.onCleared()
    }
}

/**
 * 2.2.7: how unsent drafts are sealed on the phone: the chats' Keystore key, bound to its own
 * associated data. A seam for tests, where the Android Keystore is not there.
 */
internal object DraftSeal {
    private val AAD = "herdrdroid-composer-drafts".toByteArray()
    @Volatile var seal: (ByteArray) -> ByteArray = { com.billmongan.herdrdroid.store.ChatVault.seal(it, AAD) }
    @Volatile var open: (ByteArray) -> ByteArray = { com.billmongan.herdrdroid.store.ChatVault.open(it, AAD) }
}

/** 2.2.7: the toast the first time a server's card answers fall back to the shell check. */
internal fun shellCheckToast(serverLabel: String) = "python3 isn't on $serverLabel, so answers are checked with the shell version."

/** 2.2.8: the note when a message queued behind a question finds another occupant in the pane. */
internal const val QUEUED_OCCUPANT_CHANGED = "Not sent: another agent or session is in that pane now. Your message is back in the message box."

/** 2.2.8: a held bound message's last look before the write found another occupant; nothing was typed. */
internal class BoundOccupantChanged : Exception("the pane changed")

/** 2.2.8: why a bound send that failed after its write began is kept, not sent again by itself. */
internal const val MAY_HAVE_SENT = "it may have reached the agent; check the agent before sending it again"

/** 2.2.8: an answer refused before anything was sent because another agent or session is in the pane. */
internal const val OCCUPANT_REFUSAL = "Not sent. Another agent or session is in this pane now."

/** 2.2.8: agents whose approval or Ask dialog waits while herdr may call them working: the Questions view reads them too. */
internal val QUESTION_CANDIDATE_AGENTS = setOf("omp", "pi")

/** 2.2.8: a card answer whose keys failed after the check passed. */
internal const val CARD_MAY_HAVE_SENT = "Could not confirm delivery: the answer may have been sent. Check the pane before answering again."

/** 2.2.8: what the app says when the card was brought up to date at a tap instead of sending. */
internal const val CARD_UPDATED_NOTE = "Updated to the question on screen now. Nothing was sent; check it and tap again."

/** 2.2.8: an answer tapped on a card whose command the agent shows only in part. */
internal const val CLIPPED_NOTE = "Not sent. The agent shows only part of this command: tap Show the whole command first."
