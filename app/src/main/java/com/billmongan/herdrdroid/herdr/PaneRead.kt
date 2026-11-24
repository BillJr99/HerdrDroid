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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * 2.2.7 (Bill: "a cross server request to read a pane ... that might help you see and troubleshoot
 * other panes"; then "I'd rather it save on the phone and then I can relay it kind of like with the
 * diagnostic logs"). An agent asks to read a pane on another server by rendering this frame in its
 * chat, as it renders a cross-server message (HDMSG2):
 *
 *     HDREAD1 K7P3QX
 *     HDSRV labbox
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

/** A frame found in an agent's reply, with the row that shows it is the agent's reply ([evidence]). */
internal data class PaneReadFound(val frame: PaneReadFrame, val evidence: String)

/** The first row of an agent's reply: Claude's "⏺ " and "● ", Codex's "• ". */
private val REPLY_HEAD = Regex("""^[⏺●•] \S""")
/** A tool call's row, whose output follows it: "⏺ Bash(cat notes.txt)", "• Ran cat notes.txt". */
private val TOOL_HEAD = Regex("""^[⏺●•] (?:[A-Za-z][\w.-]*\(|(?:Ran|Explored|Edited|Read|Searched|Called|Updated|Added|Deleted|Waited|Running)\b)""")
/** Rows of tool output, a box, or a quote: a frame among them is not the agent's own. */
private val NOT_REPLY_ROW = Regex("""^\s*[⎿└│┃╭╰>]""")

/**
 * Every well-formed read frame in recent pane output that the agent wrote in its own reply, at most
 * one per code. 2.2.7 review: a frame is accepted only where the agent's reply shows it: its rows all
 * at one indentation of at most 4 spaces, with no box, quote or tool-output mark, under (or on) the
 * first row of a reply ("⏺ ", "● ", "• ") that is not a tool call, with no tool output between. A
 * frame in tool output (a cat'ed file, a fetched page), in your own message or a relayed message
 * (echoed with "❯" or ">"), or in a box is skipped. A malformed frame is skipped without hiding the
 * ones around it; a code in two frames that ask different things yields nothing. A line count above
 * [PaneReadLimits.MAX_LINES] is capped to it.
 */
internal fun paneReadFinds(output: String): List<PaneReadFound> {
    val rows = output.takeLast(65_536).lines().map { it.replace("\r", "").trimEnd() }
    val found = mutableListOf<PaneReadFound>()
    val conflicted = mutableSetOf<String>()
    var i = 0
    while (i < rows.size) {
        val row = rows[i]
        val marked = row.length > 2 && row[0] in "⏺●•" && row[1] == ' ' && row.substring(2).startsWith("HDREAD1 ")
        val indent = if (marked) 2 else row.length - row.trimStart(' ').length
        if (!(marked || (indent <= 4 && row.substring(indent).startsWith("HDREAD1 ")))) { i++; continue }
        val head = if (marked) row else (i - 1 downTo 0).firstOrNull { rows[it].isNotEmpty() && rows[it][0] != ' ' }?.let { rows[it] }
        val headAt = if (marked) i else (i - 1 downTo 0).firstOrNull { rows[it].isNotEmpty() && rows[it][0] != ' ' } ?: -1
        val ownReply = head != null && REPLY_HEAD.containsMatchIn(head) && !TOOL_HEAD.containsMatchIn(head) &&
            (headAt + 1 until i).none { NOT_REPLY_ROW.containsMatchIn(rows[it]) }
        if (!ownReply) { i++; continue }
        // Every row of the frame at the same indentation, nothing else on it.
        val body = (i until minOf(rows.size, i + 6)).map { k ->
            val r = rows[k]
            if (k == i) r.substring(indent) else if (r.length >= indent && r.take(indent).isBlank() && (indent == r.length || r[indent] != ' ')) r.substring(indent) else ""
        }
        val parsed = parseReadFrame(body, 0, body[0].removePrefix("HDREAD1 ").trim())
        if (parsed == null) { i++; continue }
        val (frame, next) = parsed
        val same = found.firstOrNull { it.frame.code == frame.code }
        if (same == null) found += PaneReadFound(frame, checkNotNull(head).trim().take(120))
        else if (same.frame != frame) conflicted += frame.code
        i += next
    }
    return found.filter { it.frame.code !in conflicted }
}

/** The frames alone, as [paneReadFinds] accepts them. */
internal fun paneReadFrames(output: String): List<PaneReadFrame> = paneReadFinds(output).map { it.frame }

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
    PANE_CHANGED("that pane now holds a different tab, agent or session than HerdrDroid last saw there", false),
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

    /** The card's line about the attachment send. */
    fun deliveryNote(paneName: String, delivered: Boolean, why: String?): String =
        if (delivered) "Sent to $paneName with the capture attached."
        else "Not sent to the agent: ${why ?: "it could not be sent"}. The capture stays on this phone in Pane reads."

    fun iso(at: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(at))
}

/**
 * The captures on the phone, in the app's private storage (like the diagnostic log, never on a
 * computer): one text file each, newest first, at most [keep]. A file is never overwritten.
 */
internal class PaneReadStore(
    private val dir: File,
    private val keep: Int = PaneReadLimits.KEEP,
    /** The share sheet's copies (cacheDir/shared), cleared with the captures. */
    private val shared: File? = null,
) {
    var version by mutableStateOf(0)
        private set

    /** Saves [c] as a new file; false when one with that name is already there or it can't be written (nothing is left behind). Prunes to [keep]. */
    fun save(c: PaneCapture): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val f = File(dir, c.name)
        val created = runCatching { f.createNewFile() }.getOrDefault(false)
        if (!created) return false
        val ok = runCatching { f.writeText(c.text, Charsets.UTF_8); true }.getOrDefault(false)
        if (!ok) f.delete()
        else prune()
        version++
        return ok
    }

    fun exists(name: String) = File(dir, name).exists()

    /** The captures that parse, newest first; an unreadable or empty file is not one. */
    fun list(): List<PaneCapture> = files().mapNotNull { f -> runCatching { PaneCaptures.parse(f.name, f.readText(Charsets.UTF_8)) }.getOrNull() }

    fun get(name: String): PaneCapture? = list().firstOrNull { it.name == name }

    /** Clear on Pane reads: every capture goes, and so do the share sheet's copies. */
    fun clear() {
        dir.listFiles().orEmpty().forEach { it.delete() }
        shared?.listFiles()?.forEach { it.delete() }
        version++
    }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty()
        .sortedWith(compareByDescending<File> { it.name })

    /** Only files that hold a capture count toward [keep]; anything else is removed. */
    private fun prune() {
        val all = files()
        val good = all.filter { f -> runCatching { PaneCaptures.parse(f.name, f.readText(Charsets.UTF_8)) != null }.getOrDefault(false) }
        (all - good.toSet()).forEach { it.delete() }
        good.drop(keep).forEach { it.delete() }
    }

    companion object {
        /** Sorts by time, then code, with the requesting pane so two panes' codes never collide: "20261123-120000-K7P3QX-w1_p1.txt". */
        fun nameFor(at: Long, code: String, requesterPane: String = ""): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(at)) + "-$code" +
                requesterPane.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).let { if (it.isEmpty()) "" else "-$it" } + ".txt"
    }
}

/**
 * The read requests seen in open chats, as cards, and what Yes does. The view model supplies the
 * live state through [Port]; [S] is its handle for the requesting pane (its own server's client,
 * connection and occupant, never "the server open now").
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
        /** The requesting pane is still open as it was, on its own server, client, connection and occupant. */
        fun sourceCurrent(source: S): Boolean
        fun servers(): List<PaneReadServer>
        /** The target pane as its server lists it now (a fresh read, not the watcher's cache); null when it is gone or can't be read. */
        suspend fun livePane(server: PaneReadServer, pane: Pane): Pane?
        /** The target's screen: the visible screen when [lines] is null, else its bottom [lines] rows. */
        suspend fun read(server: PaneReadServer, pane: Pane, lines: Int?): String
        /**
         * Sends the requesting agent [message] with [capture] attached, on [source]'s own client
         * and pane, through the guarded send (never over a draft, question or panel), checking
         * [allowed] right before the upload and right before the send. Null once sent; otherwise
         * why it was not (the capture stays on the phone). It gives up rather than wait for minutes.
         */
        suspend fun deliver(source: S, capture: PaneCapture, message: String, allowed: () -> Boolean): String?
        /** A nonsecret [relay] line: codes and resolved ids and counts, never pane text or names the agent wrote. */
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
        /** The reply row the frame was found under, as the screen shows it. */
        val evidence: String = "",
        val state: PaneReadState = PaneReadState.PENDING,
        val refusal: PaneReadRefusal? = null,
        val note: String? = null,
        /** The capture's name on the phone, once saved. */
        val saved: String? = null,
        /** True while its attachment send to the requesting pane runs. */
        val delivering: Boolean = false,
        /** The attachment's outcome, once it is known. */
        val delivery: String? = null,
    )

    /** What a dialog was opened for: one request by id and code. */
    data class Approval(val id: String, val code: String)

    var cards by mutableStateOf<List<Card<S>>>(emptyList())
        private set
    private val jobs = mutableMapOf<String, Job>()

    private fun keyOf(server: String, pane: String, code: String) = "read|$server|$pane|$code"

    /** Called with each good read of the open pane. */
    fun noteFrames(source: S, server: String, pane: String, sourceName: String, recent: String) {
        val finds = paneReadFinds(recent)
        if (finds.isEmpty()) return
        val now = clock()
        if (!port.enabled()) {
            // Refused on the card. The first sighting is recorded, so turning the setting on later never gives an old frame a fresh 10 minutes.
            for ((f, evidence) in finds) {
                val id = keyOf(server, pane, f.code)
                ledger.admit(id, now)
                ledger.admit("$id#${f.fingerprint}", now)
                if (cards.any { it.id == "$id|off" }) continue
                cards = cards + Card("$id|off", source, server, pane, sourceName, f, now, evidence, PaneReadState.REFUSED,
                    PaneReadRefusal.OFF, "Not read: ${PaneReadRefusal.OFF.why}.")
                port.log("$pane: pane read ${f.code} refused: OFF")
            }
            return
        }
        cards = cards.filterNot { it.sourceServer == server && it.sourcePane == pane && it.id.endsWith("|off") }
        for ((f, evidence) in finds) {
            val id = keyOf(server, pane, f.code)
            val asked = "$id#${f.fingerprint}"
            val known = cards.firstOrNull { it.id == id }
            if (known != null) {
                if (known.frame != f && cards.none { it.id == asked }) reused(asked, source, server, pane, sourceName, f, evidence, now)
                continue
            }
            if (ledger.seen(id) && !ledger.seen(asked)) { reused(asked, source, server, pane, sourceName, f, evidence, now); continue }
            val deadline = ledger.admit(id, now) ?: continue
            ledger.admit(asked, now)
            cards = cards + Card(id, source, server, pane, sourceName, f, deadline, evidence)
            port.log("$pane: pane read ${f.code} asked, ${f.lines ?: "visible"} lines")
        }
    }

    private fun reused(id: String, source: S, server: String, pane: String, name: String, f: PaneReadFrame, evidence: String, now: Long) {
        ledger.spend(id, now)
        cards = cards + Card(id, source, server, pane, name, f, now, evidence, PaneReadState.REFUSED, PaneReadRefusal.REUSED,
            "Not read: ${PaneReadRefusal.REUSED.why}. Ask the agent for a new request.")
        port.log("$pane: pane read ${f.code} refused: REUSED")
    }

    fun cardsFor(server: String, pane: String) = cards.filter { it.sourceServer == server && it.sourcePane == pane }

    /** The approval a Yes/No dialog may be opened with: a pending request, within its 10 minutes, its pane open. */
    fun approvalOf(card: Card<S>): Approval? = cards.firstOrNull { it.id == card.id }
        ?.takeIf { it.state == PaneReadState.PENDING && it.frame.code == card.frame.code && clock() < it.deadline }
        ?.let { Approval(it.id, it.frame.code) }

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
        val live = cards.firstOrNull { it.id == card.id } ?: return
        if (live.state in setOf(PaneReadState.PENDING, PaneReadState.READING) || live.delivering) return
        cards = cards.filter { it.id != card.id }
    }

    /** Background, lock, a lost link or the setting turned off: every card goes, and any read, upload or send under way is stopped. */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        cards = emptyList()
    }

    private fun update(id: String, change: (Card<S>) -> Card<S>) {
        cards = cards.map { if (it.id == id) change(it) else it }
    }

    /**
     * Yes on the dialog. False, with nothing read, when the request is gone, finished, expired, has
     * another code, the setting is off or its pane is no longer open. True when the read started;
     * the code is spent first, so a request reads at most once.
     */
    fun approve(approval: Approval): Boolean {
        expire()
        val card = cards.firstOrNull { it.id == approval.id } ?: return false
        if (card.frame.code != approval.code || card.state != PaneReadState.PENDING) return false
        if (!port.enabled() || !port.sourceCurrent(card.source)) return false
        ledger.spend(card.id, clock())
        update(card.id) { it.copy(state = PaneReadState.READING, note = "Reading…") }
        port.log("${card.sourcePane}: pane read ${card.frame.code} approved")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { perform(card) } finally { jobs.remove(card.id) }
        }
        jobs[card.id] = job
        job.start()
        return true
    }

    private suspend fun perform(card: Card<S>) {
        val f = card.frame
        fun open() = port.enabled() && port.sourceCurrent(card.source)
        fun refuse(why: PaneReadRefusal) {
            port.log("${card.sourcePane}: pane read ${f.code} refused: ${why.name}")
            val pending = why.retry && cards.any { it.id == card.id }
            if (pending) ledger.release(card.id, clock())
            update(card.id) {
                if (pending) it.copy(state = PaneReadState.PENDING, refusal = why,
                    note = "Not read: ${why.why}. Retry before the request expires.")
                else it.copy(state = PaneReadState.REFUSED, refusal = why, note = "Not read: ${why.why}.")
            }
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
        val cached = when (val m = matchPane(server.panes, f.pane)) {
            is PaneMatch.Found -> m.pane
            PaneMatch.Ambiguous -> return refuse(PaneReadRefusal.AMBIGUOUS_PANE)
            PaneMatch.None -> return refuse(PaneReadRefusal.UNKNOWN_PANE)
        }
        // A fresh read of that pane on its server: the same tab and agent as the name matched, or nothing is read.
        val live = try { port.livePane(server, cached) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { null }
            ?: return refuse(PaneReadRefusal.UNKNOWN_PANE)
        if (live.id != cached.id || live.tabId != cached.tabId || live.agent != cached.agent ||
            (hasStableOccupant(cached) && hasStableOccupant(live) && !sameOccupant(cached, live))) return refuse(PaneReadRefusal.PANE_CHANGED)
        // herdr's pane get carries no tab label; the name the request matched stays.
        val pane = live.copy(tabLabel = live.tabLabel.ifBlank { cached.tabLabel })
        val at = clock()
        val name = PaneReadStore.nameFor(at, f.code, card.sourcePane)
        if (store.exists(name)) return refuse(PaneReadRefusal.EXISTS)
        if (!open()) return refuse(PaneReadRefusal.SOURCE_CHANGED)
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
            it.copy(state = PaneReadState.SAVED, saved = name, delivering = true,
                note = "Read $count lines of ${capture.pane} on ${server.label}. Kept on this phone in Pane reads. Sending it to the agent…")
        }
        // The one approval covers the delivery; it is attempted once, never again for this code.
        val job = currentCoroutineContext()[Job]
        val why = try {
            port.deliver(card.source, capture, PaneCaptures.message(capture)) { job?.isActive != false && open() }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
            "the attachment couldn't be copied to this chat's server"
        }
        port.log("${card.sourcePane}: pane read ${f.code} " + if (why == null) "attachment sent" else "attachment not sent")
        update(card.id) {
            it.copy(delivering = false, delivery = PaneCaptures.deliveryNote(card.sourceName.substringBefore(" on "), why == null, why),
                note = "Read $count lines of ${capture.pane} on ${server.label}. Kept on this phone in Pane reads.")
        }
    }
}
