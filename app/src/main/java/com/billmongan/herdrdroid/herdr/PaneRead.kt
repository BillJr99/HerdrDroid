package com.billmongan.herdrdroid.herdr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 2.2.7 (Bill: "a cross server request to read a pane ... that might help you see and troubleshoot
 * other panes"; then "I'd rather it save on the phone and then I can relay it kind of like with the
 * diagnostic logs"). An agent asks to read a pane on another server by rendering this frame in its
 * chat, as it renders a cross-server message (HDMSG2):
 *
 *     HDREAD1 K7P3QX
 *     HDSRV minipc
 *     HDPANE codex
 *     HDLINES 80                      (optional: 1 to 200; default the visible screen)
 *     HDWHY see why codex stopped     (optional: a short reason)
 *     HDEND K7P3QX
 *
 * The frame is inert: it only puts a card on the phone, with a Yes/No dialog. Yes reads the target
 * pane once, over the app's own connection to that server, and keeps the capture on the phone (see
 * [PaneReadStore]), where Pane reads can open, copy or share it. Bill: "for a cross pane read that
 * should get sent as a message in the agent's pane. As an attachment is fine": the same approval
 * then sends the requesting agent a short message with the capture attached, the way the message
 * box attaches a file (copied to ~/.herdrdroid/inbox on that server, its path in the message),
 * through the guarded send path. The capture's text is never typed into any pane.
 */
internal data class PaneReadFrame(val code: String, val server: String, val pane: String, val lines: Int?, val reason: String) {
    /** What the request asks, for telling a reused code from the same frame seen again. */
    val fingerprint: String get() = sha("$server\n$pane\n${lines ?: ""}\n$reason").take(16)
}

internal object PaneReadLimits {
    const val MAX_LINES = 200
    const val MAX_NAME = 120
    const val MAX_REASON = 200
    /** Captures kept on the phone; older ones are pruned. */
    const val KEEP = 20
}

private fun sha(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun cleanField(s: String, max: Int): String? =
    s.trim().takeIf { it.length <= max && it.none { c -> Character.isISOControl(c) } }

/**
 * Every well-formed read frame in recent pane output, at most one per code. A malformed frame is
 * skipped without hiding the ones around it; a code in two frames that ask different things yields
 * nothing. A line count above [PaneReadLimits.MAX_LINES] is capped to it.
 */
internal fun paneReadFrames(output: String): List<PaneReadFrame> {
    val lines = output.takeLast(65_536).lineSequence().map(::frameLine).toList()
    val found = mutableListOf<PaneReadFrame>()
    val conflicted = mutableSetOf<String>()
    var i = 0
    while (i < lines.size) {
        if (!lines[i].startsWith("HDREAD1 ")) { i++; continue }
        val parsed = parseReadFrame(lines, i, lines[i].removePrefix("HDREAD1 ").trim())
        if (parsed == null) { i++; continue }
        val (frame, next) = parsed
        val same = found.firstOrNull { it.code == frame.code }
        if (same == null) found += frame else if (same != frame) conflicted += frame.code
        i = next
    }
    return found.filter { it.code !in conflicted }
}

private fun parseReadFrame(lines: List<String>, start: Int, code: String): Pair<PaneReadFrame, Int>? {
    if (!CrossServerCode.valid(code)) return null
    val server = lines.getOrNull(start + 1)?.takeIf { it.startsWith("HDSRV ") }?.removePrefix("HDSRV ")
        ?.let { cleanField(it, PaneReadLimits.MAX_NAME) }?.takeIf { it.isNotEmpty() } ?: return null
    val pane = lines.getOrNull(start + 2)?.takeIf { it.startsWith("HDPANE ") }?.removePrefix("HDPANE ")
        ?.let { cleanField(it, PaneReadLimits.MAX_NAME) }?.takeIf { it.isNotEmpty() } ?: return null
    var count: Int? = null
    var reason: String? = null
    var i = start + 3
    while (i < lines.size && i <= start + 5) {
        val line = lines[i]
        when {
            line == "HDEND $code" -> return PaneReadFrame(code, server, pane, count, reason.orEmpty()) to i + 1
            line.startsWith("HDLINES ") && count == null && reason == null -> {
                val n = line.removePrefix("HDLINES ").trim().takeIf { it.length <= 6 && it.all(Char::isDigit) }?.toIntOrNull() ?: return null
                if (n < 1) return null
                count = n.coerceAtMost(PaneReadLimits.MAX_LINES)
            }
            line.startsWith("HDWHY ") && reason == null -> reason = cleanField(line.removePrefix("HDWHY "), PaneReadLimits.MAX_REASON) ?: return null
            else -> return null
        }
        i++
    }
    return null
}

/** Why a pane read was not done. [retry] ones may pass later, so the card stays pending with its code unspent. */
internal enum class PaneReadRefusal(val why: String, val retry: Boolean) {
    OFF("cross-server messaging is off in Settings (Agent cross-server messaging)", false),
    SAME_SERVER("that server is this chat's own server; pane reads are for panes on your other servers", false),
    UNKNOWN_SERVER("no server has that name in HerdrDroid", true),
    NOT_CONNECTED("that server is not connected (it needs Watch my other servers too)", true),
    UNKNOWN_PANE("no pane on that server has that tab label or pane id", true),
    AMBIGUOUS_PANE("more than one pane on that server has that name; ask the agent to use the pane id", false),
    REUSED("this code was already used for a different request", false),
    EXPIRED("the request expired 10 minutes after it appeared", false),
    SOURCE_CHANGED("this chat's pane is no longer open as it was when the request came", true),
    EXISTS("a capture with that name is already on the phone; nothing was overwritten", false),
    READ_FAILED("the pane couldn't be read", false),
    SAVE_FAILED("the capture couldn't be saved on the phone", false),
}

internal enum class PaneReadState { PENDING, READING, SAVED, REFUSED, CANCELLED, EXPIRED }

/** A server as a request can name it, with its panes as the watcher last read them. */
internal data class PaneReadServer(
    val id: String,
    /** The names an agent may use: the server's name in HerdrDroid, or "server N". */
    val names: List<String>,
    /** The agent-facing label, for the capture and the card. */
    val label: String,
    val own: Boolean,
    val connected: Boolean,
    val panes: List<Pane>,
)

/** The pane a request names: by pane id, else tab label, else session name, else agent name. Each tier must match exactly one. */
internal sealed class PaneMatch {
    data class Found(val pane: Pane) : PaneMatch()
    object None : PaneMatch()
    object Ambiguous : PaneMatch()
}

internal fun matchPane(panes: List<Pane>, wanted: String): PaneMatch {
    val w = wanted.trim().lowercase()
    val tiers: List<(Pane) -> String> = listOf(
        { it.id }, { it.tabLabel }, { com.billmongan.herdrdroid.ui.sessionName(it) }, { it.agentName },
    )
    for (tier in tiers) {
        val hits = panes.filter { tier(it).trim().lowercase() == w }
        if (hits.size == 1) return PaneMatch.Found(hits[0])
        if (hits.size > 1) return PaneMatch.Ambiguous
    }
    return PaneMatch.None
}

/** A capture kept on the phone: its header fields and the screen text. */
internal data class PaneCapture(
    val name: String,
    val code: String,
    val server: String,
    val pane: String,
    val paneId: String,
    val time: String,
    val lines: Int,
    val requester: String,
    val requesterServer: String,
    val requesterPane: String,
    val reason: String,
    val body: String,
) {
    /** The whole file as shared or copied: the header, then the screen. */
    val text: String get() = PaneCaptures.header(this) + body
}

internal object PaneCaptures {
    const val RULE = "----"

    fun header(c: PaneCapture) = buildString {
        appendLine("HerdrDroid pane read ${c.code}")
        appendLine("server: ${c.server}")
        appendLine("pane: ${c.pane} (${c.paneId})")
        appendLine("time: ${c.time}")
        appendLine("lines: ${c.lines}")
        appendLine("requested by: ${c.requester}")
        appendLine("requester: ${c.requesterServer} ${c.requesterPane}")
        appendLine("reason: ${c.reason.ifBlank { "none given" }}")
        appendLine(RULE)
    }

    fun parse(name: String, text: String): PaneCapture? {
        val head = text.substringBefore("\n$RULE\n", "").takeIf { it.isNotEmpty() } ?: return null
        val body = text.substringAfter("\n$RULE\n")
        val rows = head.lines()
        val code = rows.firstOrNull()?.removePrefix("HerdrDroid pane read ")?.takeIf(CrossServerCode::valid) ?: return null
        fun f(key: String) = rows.firstOrNull { it.startsWith("$key: ") }?.removePrefix("$key: ")
        val paneRow = f("pane") ?: return null
        val req = f("requester")?.split(' ', limit = 2) ?: return null
        return PaneCapture(
            name, code, f("server") ?: return null, paneRow.substringBeforeLast(" ("), paneRow.substringAfterLast("(").removeSuffix(")"),
            f("time").orEmpty(), f("lines")?.toIntOrNull() ?: 0, f("requested by").orEmpty(),
            req.getOrElse(0) { "" }, req.getOrElse(1) { "" }, f("reason")?.takeUnless { it == "none given" }.orEmpty(), body,
        )
    }

    /** The screen as kept: no styles, no controls but line feeds and tabs, at most [count] (or 200) bottom lines. */
    fun clean(screen: String, count: Int?): String {
        val rows = stripAnsi(screen).replace("\r\n", "\n").filter { it == '\n' || it == '\t' || !Character.isISOControl(it) }
            .lines().dropLastWhile { it.isBlank() }
        return rows.takeLast(count ?: PaneReadLimits.MAX_LINES).joinToString("\n")
    }

    /** The message to the requesting agent; the attachment's path follows it. Never the capture's text. */
    fun message(c: PaneCapture): String = "Pane read: ${c.server}/${c.pane}, ${c.lines} lines (approved)"

    /** The card's line about the attachment send, from the send's live state. */
    fun deliveryNote(
        paneName: String = "this pane", delivered: Boolean = false, pending: Boolean = false, waiting: Boolean = false,
        awaitingLink: Boolean = false, undelivered: Boolean = false, held: String? = null, unavailable: String? = null,
    ): String = when {
        unavailable != null -> "Not sent to the agent: $unavailable. The capture stays on this phone in Pane reads."
        delivered -> "Sent to $paneName with the capture attached."
        undelivered -> "Not sent to the agent" + (held?.let { ": $it" } ?: "") + ". The capture stays on this phone in Pane reads."
        waiting -> "Queued for $paneName" + (held?.let { " ($it)" } ?: "") + "; it goes when the pane is free."
        awaitingLink -> "Queued for $paneName until the connection is back."
        pending -> "Sending to $paneName with the capture attached…"
        else -> "Not sent to the agent. The capture stays on this phone in Pane reads."
    }

    fun iso(at: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(at))
}

/**
 * The captures on the phone, in the app's private storage (like the diagnostic log, never on a
 * computer): one text file each, newest first, at most [keep]. A file is never overwritten.
 */
internal class PaneReadStore(private val dir: File, private val keep: Int = PaneReadLimits.KEEP) {
    var version by mutableStateOf(0)
        private set

    /** Saves [c] as a new file; false when one with that name is already there or it can't be written. Prunes to [keep]. */
    fun save(c: PaneCapture): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val f = File(dir, c.name)
        val ok = runCatching { f.createNewFile() && run { f.writeText(c.text, Charsets.UTF_8); true } }.getOrDefault(false)
        if (ok) prune()
        version++
        return ok
    }

    fun exists(name: String) = File(dir, name).exists()

    fun list(): List<PaneCapture> = files().mapNotNull { f -> runCatching { PaneCaptures.parse(f.name, f.readText(Charsets.UTF_8)) }.getOrNull() }

    fun get(name: String): PaneCapture? = list().firstOrNull { it.name == name }

    /** Clear on Pane reads: every capture goes. */
    fun clear() {
        files().forEach { it.delete() }
        version++
    }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty()
        .sortedWith(compareByDescending<File> { it.name })

    private fun prune() {
        files().drop(keep).forEach { it.delete() }
    }

    companion object {
        /** Sorts by time, then code: "20261123-120000-K7P3QX.txt". */
        fun nameFor(at: Long, code: String): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(at)) + "-$code.txt"
    }
}

/**
 * The read requests seen in open chats, as cards, and what Yes does. The view model supplies the
 * live state through [Port]; [S] is its handle for the requesting pane.
 */
internal class PaneReadRequests<S>(
    private val port: Port<S>,
    private val ledger: CrossServerCodeLedger,
    private val store: PaneReadStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    interface Port<S> {
        /** Cross-server messaging is on, the app is in front and unlocked. */
        fun enabled(): Boolean
        /** The requesting pane is still open as it was. */
        fun sourceCurrent(source: S): Boolean
        fun servers(): List<PaneReadServer>
        /** The target's screen: the visible screen when [lines] is null, else its bottom [lines] rows. */
        suspend fun read(server: PaneReadServer, pane: Pane, lines: Int?): String
        /**
         * Sends the requesting agent [message] with [capture] attached, through the guarded send
         * path (held and retried while the pane is busy, never over a draft, question or panel).
         * Null once the send has started; otherwise why the attachment can't go (the capture stays on the phone).
         */
        suspend fun deliver(source: S, capture: PaneCapture, message: String): String?
        /** A nonsecret [relay] line: codes, ids and counts, never pane text. */
        fun log(message: String)
    }

    data class Card<S>(
        val id: String,
        val source: S,
        val sourceServer: String,
        val sourcePane: String,
        val sourceName: String,
        val frame: PaneReadFrame,
        val deadline: Long,
        val state: PaneReadState = PaneReadState.PENDING,
        val refusal: PaneReadRefusal? = null,
        val note: String? = null,
        /** The capture's name on the phone, once saved. */
        val saved: String? = null,
        /** True once its attachment send to the requesting pane started (the view model reports its progress). */
        val delivering: Boolean = false,
        /** Why the attachment could not go, when it couldn't. */
        val undeliverable: String? = null,
    )

    /** What a dialog was opened for: one request by id and code. */
    data class Approval(val id: String, val code: String)

    var cards by mutableStateOf<List<Card<S>>>(emptyList())
        private set
    private var deferred by mutableStateOf<Set<String>>(emptySet())

    private fun keyOf(server: String, pane: String, code: String) = "read|$server|$pane|$code"

    /** Called with each good read of the open pane. */
    fun noteFrames(source: S, server: String, pane: String, sourceName: String, recent: String) {
        val frames = paneReadFrames(recent)
        if (frames.isEmpty()) return
        val now = clock()
        if (!port.enabled()) {
            // Refused on the card, with nothing recorded: the same frame can ask again once it is turned on.
            for (f in frames) {
                val id = keyOf(server, pane, f.code) + "|off"
                if (cards.any { it.id == id }) continue
                cards = cards + Card(id, source, server, pane, sourceName, f, now, PaneReadState.REFUSED, PaneReadRefusal.OFF,
                    "Not read: ${PaneReadRefusal.OFF.why}.")
                port.log("$pane: pane read ${f.code} refused: OFF")
            }
            return
        }
        cards = cards.filterNot { it.sourceServer == server && it.sourcePane == pane && it.id.endsWith("|off") }
        for (f in frames) {
            val id = keyOf(server, pane, f.code)
            val asked = "$id#${f.fingerprint}"
            val known = cards.firstOrNull { it.id == id }
            if (known != null) {
                if (known.frame != f && cards.none { it.id == asked }) reused(asked, source, server, pane, sourceName, f, now)
                continue
            }
            if (ledger.seen(id) && !ledger.seen(asked)) { reused(asked, source, server, pane, sourceName, f, now); continue }
            val deadline = ledger.admit(id, now) ?: continue
            ledger.admit(asked, now)
            cards = cards + Card(id, source, server, pane, sourceName, f, deadline)
            port.log("$pane: pane read ${f.code} asked for ${f.pane} on ${f.server}, ${f.lines ?: "visible"} lines")
        }
    }

    private fun reused(id: String, source: S, server: String, pane: String, name: String, f: PaneReadFrame, now: Long) {
        ledger.spend(id, now)
        cards = cards + Card(id, source, server, pane, name, f, now, PaneReadState.REFUSED, PaneReadRefusal.REUSED,
            "Not read: ${PaneReadRefusal.REUSED.why}. Ask the agent for a new request.")
        port.log("$pane: pane read ${f.code} refused: REUSED")
    }

    fun cardsFor(server: String, pane: String) = cards.filter { it.sourceServer == server && it.sourcePane == pane }

    fun approvalOf(card: Card<S>): Approval? = cards.firstOrNull { it.id == card.id }
        ?.takeIf { it.state == PaneReadState.PENDING && it.frame.code == card.frame.code }?.let { Approval(it.id, it.frame.code) }

    /** The newest pending request in this chat not put aside, while its pane is open and within its 10 minutes. */
    fun dialogFor(server: String, pane: String): Card<S>? {
        val now = clock()
        return cardsFor(server, pane).lastOrNull {
            it.state == PaneReadState.PENDING && it.id !in deferred && now < it.deadline && port.sourceCurrent(it.source)
        }
    }

    fun defer(card: Card<S>) { deferred = deferred + card.id }

    /** Pending requests past their 10 minutes are Expired; their codes are spent. */
    fun expire() {
        val now = clock()
        cards = cards.map {
            if (it.state == PaneReadState.PENDING && now >= it.deadline) {
                ledger.spend(it.id, now)
                port.log("${it.sourcePane}: pane read ${it.frame.code} expired")
                it.copy(state = PaneReadState.EXPIRED, refusal = PaneReadRefusal.EXPIRED, note = "Not read: ${PaneReadRefusal.EXPIRED.why}.")
            } else it
        }
    }

    fun cancel(card: Card<S>) {
        val live = cards.firstOrNull { it.id == card.id } ?: return
        if (live.state != PaneReadState.PENDING) return
        ledger.spend(live.id, clock())
        update(live.id) { it.copy(state = PaneReadState.CANCELLED, note = "Cancelled. Nothing was read.") }
        port.log("${live.sourcePane}: pane read ${live.frame.code} cancelled")
    }

    fun close(card: Card<S>) {
        if (cards.firstOrNull { it.id == card.id }?.state in setOf(PaneReadState.PENDING, PaneReadState.READING)) return
        cards = cards.filter { it.id != card.id }
    }

    /** Background, lock, a lost link or the setting turned off: every card goes. */
    fun clear() {
        cards = emptyList()
        deferred = emptySet()
    }

    private fun update(id: String, change: (Card<S>) -> Card<S>) {
        cards = cards.map { if (it.id == id) change(it) else it }
    }

    /**
     * Yes on the dialog, or Read on the card. False, with nothing read, when the request is gone,
     * finished, expired, has another code, the setting is off or its pane is no longer open. True
     * when the read started; the code is spent first, so a request reads at most once.
     */
    fun approve(approval: Approval): Boolean {
        expire()
        val card = cards.firstOrNull { it.id == approval.id } ?: return false
        if (card.frame.code != approval.code || card.state != PaneReadState.PENDING) return false
        if (!port.enabled() || !port.sourceCurrent(card.source)) return false
        ledger.spend(card.id, clock())
        update(card.id) { it.copy(state = PaneReadState.READING, note = "Reading…") }
        port.log("${card.sourcePane}: pane read ${card.frame.code} approved")
        scope.launch { perform(card) }
        return true
    }

    private suspend fun perform(card: Card<S>) {
        val f = card.frame
        fun refuse(why: PaneReadRefusal) {
            port.log("${card.sourcePane}: pane read ${f.code} refused: ${why.name}")
            val pending = why.retry && cards.any { it.id == card.id }
            if (pending) ledger.release(card.id, clock())
            update(card.id) {
                if (pending) it.copy(state = PaneReadState.PENDING, refusal = why,
                    note = "Not read: ${why.why}. Retry before the request expires.")
                else it.copy(state = PaneReadState.REFUSED, refusal = why, note = "Not read: ${why.why}.")
            }
            if (pending) deferred = deferred + card.id
        }
        if (!port.enabled()) return refuse(PaneReadRefusal.OFF)
        if (!port.sourceCurrent(card.source)) return refuse(PaneReadRefusal.SOURCE_CHANGED)
        val wanted = f.server.trim().lowercase()
        val server = port.servers().filter { s -> s.names.any { it.trim().lowercase() == wanted } }.let { hits ->
            if (hits.size != 1) return refuse(PaneReadRefusal.UNKNOWN_SERVER)
            hits[0]
        }
        if (server.own) return refuse(PaneReadRefusal.SAME_SERVER)
        if (!server.connected) return refuse(PaneReadRefusal.NOT_CONNECTED)
        val pane = when (val m = matchPane(server.panes, f.pane)) {
            is PaneMatch.Found -> m.pane
            PaneMatch.Ambiguous -> return refuse(PaneReadRefusal.AMBIGUOUS_PANE)
            PaneMatch.None -> return refuse(PaneReadRefusal.UNKNOWN_PANE)
        }
        val at = clock()
        val name = PaneReadStore.nameFor(at, f.code)
        if (store.exists(name)) return refuse(PaneReadRefusal.EXISTS)
        val screen = try {
            port.read(server, pane, f.lines)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
            return refuse(PaneReadRefusal.READ_FAILED)
        }
        val body = PaneCaptures.clean(screen, f.lines)
        val count = if (body.isEmpty()) 0 else body.lines().size
        val capture = PaneCapture(
            name, f.code, server.label, pane.tabLabel.ifBlank { pane.agentName }, pane.id, PaneCaptures.iso(at), count,
            card.sourceName, card.sourceServer, card.sourcePane, f.reason, body,
        )
        if (!store.save(capture)) return refuse(if (store.exists(name)) PaneReadRefusal.EXISTS else PaneReadRefusal.SAVE_FAILED)
        port.log("${card.sourcePane}: pane read ${f.code} saved on the phone: ${server.id}/${pane.id}, $count lines")
        update(card.id) {
            it.copy(state = PaneReadState.SAVED, saved = name,
                note = "Read $count lines of ${capture.pane} on ${server.label}. Kept on this phone in Pane reads.")
        }
        // The one approval covers the delivery; it is attempted once, never again for this code.
        val why = try {
            port.deliver(card.source, capture, PaneCaptures.message(capture))
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            "the attachment couldn't be copied to this chat's server"
        }
        if (why == null) {
            port.log("${card.sourcePane}: pane read ${f.code} attachment send started")
            update(card.id) { it.copy(delivering = true) }
        } else {
            port.log("${card.sourcePane}: pane read ${f.code} not delivered")
            update(card.id) { it.copy(undeliverable = why) }
        }
    }
}
