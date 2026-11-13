package com.billmongan.herdrdroid.hermes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.transcript.DiffData
import com.billmongan.herdrdroid.transcript.Item
import com.billmongan.herdrdroid.transcript.NoteItem
import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.ThinkItem
import com.billmongan.herdrdroid.transcript.ToolItem
import com.billmongan.herdrdroid.transcript.ToolState
import com.billmongan.herdrdroid.transcript.UserItem
import com.billmongan.herdrdroid.ui.SlashCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One stored Hermes conversation, as the session picker lists it. */
data class HermesSession(
    val id: String,
    val title: String,
    val preview: String,
    val lastActive: Double,
    val source: String,
    val running: Boolean,
    val model: String?,
)

/** Something Hermes is waiting on you for. */
sealed interface Ask {
    val id: Any

    data class Approval(
        override val id: Any,
        val command: String,
        val description: String,
        val choices: List<String>,
    ) : Ask

    data class Question(val qid: String, val text: String, val choices: List<String>)
    data class Clarify(override val id: Any, val questions: List<Question>) : Ask
}

enum class HermesPhase { CONNECTING, PICKER, CHAT, OFFLINE }

/**
 * A native chat with Hermes over its gateway: real streaming, tool progress, approvals and
 * clarifying questions as buttons, Stop and Steer. State is Compose state, read by the screen.
 */
class HermesChat(ssh: HerdrSsh, private val scope: CoroutineScope) {
    private val gw = HermesGateway(ssh)

    var phase by mutableStateOf(HermesPhase.CONNECTING)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    var sessions by mutableStateOf<List<HermesSession>>(emptyList())
        private set
    var loadingSessions by mutableStateOf(false)
        private set
    var items by mutableStateOf<List<Item>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var title by mutableStateOf("Hermes")
        private set
    var asks by mutableStateOf<List<Ask>>(emptyList())
        private set
    var commands by mutableStateOf<List<SlashCommand>>(emptyList())
        private set

    private var sessionId: String? = null
    private var storedId: String? = null
    private var listen: Job? = null
    private var liveText: String? = null
    private var liveThink: String? = null
    private var seq = 0

    fun start() {
        scope.launch { connectAndList() }
    }

    private suspend fun connectAndList() {
        phase = HermesPhase.CONNECTING
        problem = null
        try {
            gw.connect()
            listenToFrames()
            loadCommands()
            if (storedId != null) {
                resume(storedId!!, title)
            } else {
                phase = HermesPhase.PICKER
                refreshSessions()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            phase = HermesPhase.OFFLINE
            problem = e.message ?: e.javaClass.simpleName
        }
    }

    fun reconnect() {
        scope.launch { connectAndList() }
    }

    private fun listenToFrames() {
        listen?.cancel()
        listen = scope.launch {
            gw.frames.collect { f -> runCatching { onFrame(f) } }
        }
    }

    private suspend fun loadCommands() {
        runCatching {
            val r = gw.request("commands.catalog")
            val pairs = r.optJSONArray("pairs") ?: JSONArray()
            commands = (0 until pairs.length()).mapNotNull { i ->
                pairs.optJSONArray(i)?.let { p -> SlashCommand(p.optString(0), p.optString(1)) }
            }.filter { it.name.startsWith("/") }.distinctBy { it.name }
        }
    }

    fun refreshSessions() {
        scope.launch {
            loadingSessions = true
            try {
                val r = gw.request("session.list", JSONObject().put("picker", true).put("limit", 50))
                val arr = r.optJSONArray("sessions") ?: JSONArray()
                sessions = (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    HermesSession(
                        id = s.optString("id"),
                        title = s.optString("title").ifBlank { s.optString("preview").take(60).ifBlank { "Untitled" } },
                        preview = s.optString("last_reply").ifBlank { s.optString("preview") },
                        lastActive = s.optDouble("last_active_at", 0.0),
                        source = s.optString("source"),
                        running = s.optBoolean("running"),
                        model = s.optString("model").ifBlank { null },
                    )
                }
            } catch (e: Exception) {
                problem = e.message
            } finally {
                loadingSessions = false
            }
        }
    }

    /**
     * Opens the newest conversation that came from [source] ("telegram": the chat you have with
     * Hermes in Telegram), once the gateway is connected. Messages sent here continue that same
     * conversation; Telegram itself shows only what is sent there.
     */
    fun openLatest(source: String, label: String) {
        scope.launch {
            problem = null
            var waited = 0
            while (phase == HermesPhase.CONNECTING && waited < 30_000) { kotlinx.coroutines.delay(300); waited += 300 }
            if (phase == HermesPhase.OFFLINE) return@launch
            try {
                val r = gw.request("session.list", JSONObject().put("picker", true).put("limit", 200))
                val arr = r.optJSONArray("sessions") ?: JSONArray()
                val hit = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { it.optString("source").equals(source, ignoreCase = true) }
                    .maxByOrNull { it.optDouble("last_active_at", it.optDouble("started_at", 0.0)) }
                if (hit == null) { problem = "No $label conversation with Hermes yet."; return@launch }
                resume(hit.optString("id"), hit.optString("title").ifBlank { label })
            } catch (e: Exception) {
                problem = "Couldn't open the $label conversation: ${e.message}"
            }
        }
    }

    /** The stored id of the conversation open in live chat, if any. */
    val openId: String? get() = if (phase == HermesPhase.CHAT) storedId ?: sessionId else null

    /**
     * Every live Hermes conversation's state ("working", "waiting", "idle", ...), from the gateway's
     * session.active_list. Null when the gateway can't be asked.
     */
    suspend fun liveStates(): List<Pair<String, String>>? = runCatching {
        if (phase == HermesPhase.CONNECTING || phase == HermesPhase.OFFLINE) return null
        val r = gw.request("session.active_list", JSONObject(), timeoutMs = 15_000)
        val arr = r.optJSONArray("sessions") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { it.optString("title").ifBlank { it.optString("source") } to it.optString("status") }
    }.getOrNull()

    fun showPicker() {
        phase = HermesPhase.PICKER
        refreshSessions()
    }

    fun open(s: HermesSession) {
        scope.launch { resume(s.id, s.title) }
    }

    private suspend fun resume(id: String, name: String) {
        problem = null
        try {
            val r = gw.request("session.resume", JSONObject().put("session_id", id).put("cols", 72), timeoutMs = 60_000)
            sessionId = r.optString("session_id").ifBlank { id }
            storedId = r.optString("stored_session_id").ifBlank { id }
            title = name.ifBlank { "Hermes" }
            items = fromTranscript(r.optJSONArray("messages") ?: JSONArray())
            running = r.optBoolean("running")
            liveText = null
            liveThink = null
            asks = emptyList()
            phase = HermesPhase.CHAT
        } catch (e: Exception) {
            problem = "Couldn't open that conversation: ${e.message}"
        }
    }

    fun newChat() {
        scope.launch {
            problem = null
            try {
                val r = gw.request("session.create", JSONObject().put("cols", 72), timeoutMs = 60_000)
                sessionId = r.optString("session_id")
                storedId = r.optString("stored_session_id").ifBlank { null }
                title = "New chat"
                items = emptyList()
                running = false
                asks = emptyList()
                phase = HermesPhase.CHAT
            } catch (e: Exception) {
                problem = "Couldn't start a chat: ${e.message}"
            }
        }
    }

    fun send(text: String) {
        val sid = sessionId ?: return
        if (running) {
            steer(text)
            return
        }
        items = items + UserItem("u${seq++}", text)
        running = true
        status = ""
        liveText = null
        liveThink = null
        scope.launch {
            try {
                gw.request("prompt.submit", JSONObject().put("session_id", sid).put("text", text), timeoutMs = 60_000)
            } catch (e: Exception) {
                running = false
                items = items + NoteItem("n${seq++}", "Not sent: ${e.message}")
            }
        }
    }

    /** Guidance the agent sees at its next tool boundary, without stopping the turn. */
    fun steer(text: String) {
        val sid = sessionId ?: return
        scope.launch {
            try {
                gw.request("session.steer", JSONObject().put("session_id", sid).put("text", text))
                items = items + NoteItem("n${seq++}", "Steered: $text")
            } catch (e: Exception) {
                items = items + NoteItem("n${seq++}", "Couldn't steer: ${e.message}")
            }
        }
    }

    fun interrupt() {
        val sid = sessionId ?: return
        scope.launch { runCatching { gw.request("session.interrupt", JSONObject().put("session_id", sid)) } }
    }

    fun approve(a: Ask.Approval, choice: String) {
        gw.respond(a.id, JSONObject().put("choice", choice))
        asks = asks - a
    }

    fun answer(c: Ask.Clarify, answers: Map<String, String?>) {
        val o = JSONObject()
        answers.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        gw.respond(c.id, JSONObject().put("answers", o).put("outcome", "submitted"))
        asks = asks - c
    }

    fun cancelAsk(a: Ask) {
        when (a) {
            is Ask.Approval -> gw.respond(a.id, JSONObject().put("choice", "deny"))
            is Ask.Clarify -> gw.respond(a.id, JSONObject().put("outcome", "cancelled"))
        }
        asks = asks - a
    }

    fun close() {
        listen?.cancel()
        gw.close()
    }

    // ---- Frames --------------------------------------------------------------------------------

    private fun onFrame(f: JSONObject) {
        val method = f.optString("method")
        if (method == "_closed") {
            if (phase != HermesPhase.CONNECTING) {
                phase = HermesPhase.OFFLINE
                problem = "The connection to Hermes closed."
                running = false
            }
            return
        }
        if (method != "event" && f.has("id")) {
            onQuestion(f.get("id"), method, f.optJSONObject("params") ?: JSONObject())
            return
        }
        if (method != "event") return
        val p = f.optJSONObject("params") ?: return
        val sid = p.optString("session_id")
        if (sessionId == null || (sid.isNotEmpty() && sid != sessionId)) return
        val payload = p.optJSONObject("payload") ?: JSONObject()
        when (p.optString("type")) {
            "message.delta" -> {
                if (payload.isNull("text")) {
                    liveText = null
                    return
                }
                running = true
                appendText(payload.optString("text"))
            }
            "message.interim" -> {
                if (!payload.optBoolean("already_streamed")) appendText(payload.optString("text"))
                liveText = null
            }
            "reasoning.delta", "thinking.delta" -> appendThink(payload.optString("text"))
            "tool.start" -> {
                liveText = null
                liveThink = null
                val id = payload.optString("tool_id")
                val name = payload.optString("name")
                val args = payload.optJSONObject("args")
                val sent = if (Regex("send|message|notify|reply|deliver").containsMatchIn(name.lowercase())) mediaIn(args) else emptyList()
                items = items + ToolItem(
                    key = "t$id", id = id, name = name,
                    title = if (sent.isNotEmpty()) "Sent " + sent.joinToString(", ") { it.substringAfterLast('/') } else toolTitle(name, payload.optString("context"), args),
                    kind = toolKind(name), input = args?.toString(1)?.take(4000),
                    diff = diffOf(args), patch = null, out = null, state = ToolState.RUNNING,
                    attach = sent,
                )
            }
            "tool.complete" -> {
                val id = payload.optString("tool_id")
                val out = payload.optString("result_text").ifBlank { null }
                    ?: payload.opt("result")?.let { r -> if (r is String) r else r.toString() }?.take(6000)
                    ?: payload.optString("summary").ifBlank { null }
                val inline = payload.optString("inline_diff").ifBlank { null }
                // A local image the call produced or looked at: shown in the chat, and downloadable.
                val image = payload.optString("image_path").ifBlank { null }
                items = items.map {
                    if (it is ToolItem && it.id == id) {
                        it.copy(out = out, patch = inline ?: it.patch, state = ToolState.OK, path = image ?: it.path, image = image ?: it.image)
                    } else it
                }
            }
            "status.update" -> status = payload.optString("text").ifBlank { payload.optString("message") }
            "message.complete" -> {
                val text = payload.optString("text")
                val reused = payload.optBoolean("response_reused")
                if (liveText == null && text.isNotBlank() && !reused && items.lastOrNull() !is TextItem) {
                    items = items + TextItem("a${seq++}", text)
                }
                if (payload.optString("status") == "error" && text.isNotBlank() && liveText != null) {
                    items = items + NoteItem("n${seq++}", text)
                }
                payload.optString("warning").takeIf { it.isNotBlank() }?.let { items = items + NoteItem("n${seq++}", it) }
                liveText = null
                liveThink = null
                running = false
                status = ""
                items = items.map { if (it is ToolItem && it.state == ToolState.RUNNING) it.copy(state = ToolState.OK) else it }
            }
            "request.cancel" -> {
                val id = payload.opt("id") ?: p.opt("id")
                asks = asks.filterNot { it.id.toString() == id?.toString() }
            }
        }
    }

    private fun appendText(delta: String) {
        if (delta.isEmpty()) return
        liveThink = null
        val key = liveText
        if (key == null) {
            val k = "a${seq++}"
            liveText = k
            items = items + TextItem(k, delta)
        } else {
            items = items.map { if (it is TextItem && it.key == key) it.copy(text = it.text + delta) else it }
        }
    }

    private fun appendThink(delta: String) {
        if (delta.isEmpty()) return
        val key = liveThink
        if (key == null) {
            val k = "r${seq++}"
            liveThink = k
            items = items + ThinkItem(k, delta, null)
        } else {
            items = items.map { if (it is ThinkItem && it.key == key) it.copy(text = it.text + delta) else it }
        }
    }

    private fun onQuestion(id: Any, method: String, params: JSONObject) {
        when (method) {
            "approval" -> {
                val ch = params.optJSONArray("choices")
                val choices = if (ch != null && ch.length() > 0) (0 until ch.length()).map { ch.getString(it) } else listOf("once", "session", "always", "deny")
                asks = asks + Ask.Approval(id, params.optString("command"), params.optString("description"), choices)
            }
            "clarify" -> {
                val qs = params.optJSONArray("questions") ?: JSONArray()
                asks = asks + Ask.Clarify(id, (0 until qs.length()).map { i ->
                    val q = qs.getJSONObject(i)
                    val c = q.optJSONArray("choices")
                    Ask.Question(q.optString("qid"), q.optString("question"), if (c == null) emptyList() else (0 until c.length()).map { c.getString(it) })
                })
            }
            // Passwords, secrets and the desktop bridges are not answered from a phone.
            else -> gw.decline(id, method)
        }
    }

    // ---- Stored history -------------------------------------------------------------------------

    private fun fromTranscript(arr: JSONArray): List<Item> {
        val out = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (m.optString("display_kind") == "hidden") continue
            val key = "h" + (if (m.has("row_id")) m.optLong("row_id").toString() else "i$i")
            // The words are in "text" on some paths and in "content" on others (a string, or a list
            // of parts): a stored conversation such as the Telegram one has only "content".
            val words = hermesMessageText(m)
            when (m.optString("role")) {
                "user" -> words.takeIf { it.isNotBlank() }?.let { out += UserItem(key, it) }
                "assistant" -> {
                    m.optString("reasoning").takeIf { it.isNotBlank() }?.let { out += ThinkItem(key + "r", it, null) }
                    words.takeIf { it.isNotBlank() }?.let { out += TextItem(key, it) }
                }
                "tool" -> {
                    val name = m.optString("name").ifBlank { "tool" }
                    val args = m.optJSONObject("args")
                    val content = m.opt("content")?.let { c -> if (c is String) c else c.toString() }
                    out += ToolItem(
                        key, m.optString("tool_call_id"), name, toolTitle(name, m.optString("context"), args), toolKind(name),
                        args?.toString(1)?.take(4000), diffOf(args), null, content?.take(6000), ToolState.OK,
                    )
                }
                else -> words.takeIf { it.isNotBlank() }?.let { out += NoteItem(key, it.lines().first().take(160)) }
            }
        }
        return out
    }

    private fun toolKind(name: String): String {
        val n = name.lowercase()
        return when {
            "terminal" in n || "shell" in n || "exec" in n || n == "bash" || "process" in n -> "shell"
            "read" in n || n == "view" -> "read"
            "write" in n || "patch" in n || "edit" in n || "replace" in n -> "edit"
            "search" in n && "web" in n -> "web"
            "grep" in n || "search" in n || "find" in n || "glob" in n -> "search"
            "web" in n || "fetch" in n || "browser" in n || "url" in n -> "web"
            "delegate" in n || "agent" in n || "subagent" in n -> "agent"
            "todo" in n || "plan" in n -> "plan"
            else -> "other"
        }
    }

    private fun toolTitle(name: String, context: String?, args: JSONObject?): String {
        val ctx = context?.takeIf { it.isNotBlank() }
            ?: args?.let { a -> listOf("command", "path", "file_path", "query", "pattern", "url", "goal", "task").firstNotNullOfOrNull { k -> a.optString(k).ifBlank { null } } }
        val first = ctx?.lines()?.firstOrNull()?.take(90)
        return when (toolKind(name)) {
            "shell" -> "$ " + (first ?: name)
            else -> if (first != null) "$name · $first" else name
        }
    }

    /** MEDIA:/path tags in a tool's arguments (send_message): files Hermes is handing over. */
    private fun mediaIn(args: JSONObject?): List<String> {
        args ?: return emptyList()
        val out = mutableListOf<String>()
        for (k in args.keys()) {
            val v = args.opt(k) as? String ?: continue
            Regex("""MEDIA:((?:~/|/)[^\s"'`<>]+)""").findAll(v).forEach { out += it.groupValues[1].trimEnd('.', ',', ';', ':', ')', ']') }
        }
        return out.distinct()
    }

    private fun diffOf(args: JSONObject?): DiffData? {
        args ?: return null
        val old = args.optString("old_string").ifBlank { args.optString("old_text") }
        val new = args.optString("new_string").ifBlank { args.optString("new_text") }
        if (old.isBlank() && new.isBlank()) return null
        return DiffData(args.optString("path").ifBlank { args.optString("file_path") }.ifBlank { null }, old.take(4000), new.take(4000))
    }
}

/** A transcript row's words: "text", else "content" as a string or as a list of text parts. */
fun hermesMessageText(m: JSONObject): String {
    m.optString("text").takeIf { m.has("text") && !m.isNull("text") && it.isNotBlank() }?.let { return it }
    return when (val c = m.opt("content")) {
        is String -> c
        is JSONArray -> (0 until c.length()).mapNotNull { k ->
            when (val part = c.opt(k)) {
                is String -> part
                is JSONObject -> part.optString("text").ifBlank { null }
                else -> null
            }
        }.joinToString("\n")
        else -> ""
    }
}
