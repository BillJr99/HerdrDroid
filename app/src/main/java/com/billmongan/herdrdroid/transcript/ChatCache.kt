package com.billmongan.herdrdroid.transcript

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One agent's chat kept on the phone: what it showed, where its log was read up to ([cursor]),
 * where the shown part begins ([start] in [file]) and which agent it belongs to. Going back to the
 * agent shows these items at once, then reads only what was added after [cursor] (1.2.9, Bill:
 * "Let's cache better so that we don't have to re-pull so much").
 */
data class CachedChat(
    val items: List<Item>,
    val cursor: String,
    val more: Boolean,
    val title: String?,
    val start: Long = -1L,
    val file: String? = null,
    val agent: String? = null,
)

/**
 * This chat with one read of its log folded in: new items are added by key, so a read that
 * overlaps what is kept adds nothing twice; a reset (another session or file, or a log that shrank)
 * replaces it all.
 */
fun CachedChat.withRead(r: TranscriptRead): CachedChat {
    val cj = runCatching { JSONObject(r.cursor) }.getOrNull()
    return if (r.reset) CachedChat(Transcript.merge(emptyList(), r.items, true), r.cursor, r.more, r.title ?: title,
        cj?.optLong("start", -1L) ?: -1L, cj?.optString("file")?.ifBlank { null }, agent)
    else copy(items = if (r.items.isEmpty()) items else Transcript.merge(items, r.items, false), cursor = r.cursor, title = r.title ?: title)
}

/** Turns a [CachedChat] into the JSON kept on the phone and back. The items use the reader's own format. */
object ChatCodec {
    /** The newest items kept on the phone per pane, and their size in characters. */
    const val MAX_ITEMS = 300
    const val MAX_CHARS = 400_000
    /** A tool's output kept on the phone, in characters (the whole output is read again when needed). */
    const val MAX_OUT = 8_000

    /** The raw item [Transcript.toItem] reads back as [item]. A message still being sent is not kept. */
    fun toJson(item: Item): JSONObject? = when (item) {
        is UserItem -> if (item.sending) null else JSONObject().put("t", "user").put("k", item.key).put("text", item.text)
        is TextItem -> JSONObject().put("t", "text").put("k", item.key).put("text", item.text)
        is NoteItem -> JSONObject().put("t", "note").put("k", item.key).put("text", item.text)
        // Questions are kept in their own file beside the chat (ChatStore.saveQuestions), not among the log's items.
        is QuestionItem -> null
        is ThinkItem -> JSONObject().put("t", "think").put("k", item.key).put("text", item.text).putOpt("ms", item.ms)
        is ImageItem -> {
            val src = JSONObject().putOpt("file", item.logFile).put("off", item.logOffset)
                .putOpt("idx", item.logIndex?.let { runCatching { JSONArray(it) }.getOrNull() })
                .putOpt("db", item.blobDb).putOpt("hash", item.blobHash)
            JSONObject().put("t", "image").put("k", item.key).put("src", src).putOpt("path", item.path).put("mime", item.mime)
                .put("kb", item.kb).put("user", item.user).putOpt("name", item.name)
        }
        is ToolItem -> JSONObject().put("t", "tool").put("k", item.key).putOpt("id", item.id).put("name", item.name)
            .put("title", item.title).put("kind", item.kind).putOpt("input", item.input)
            .putOpt("diff", item.diff?.let { JSONObject().putOpt("path", it.path).put("old", it.old).put("new", it.new) })
            .putOpt("patch", item.patch)
            .putOpt("out", item.out?.let { if (it.length > MAX_OUT) it.take(MAX_OUT) + "\n…" else it })
            .put("state", when (item.state) { ToolState.OK -> "ok"; ToolState.ERROR -> "err"; ToolState.RUNNING -> "run" })
            .putOpt("path", item.path).putOpt("image", item.image)
            .putOpt("attach", item.attach.takeIf { it.isNotEmpty() }?.let { JSONArray(it) })
    }

    /**
     * The newest items that fit [MAX_ITEMS] and [MAX_CHARS]. When older ones are left out the copy
     * says there is more and forgets where the shown part began, so "earlier" is never read across
     * a gap.
     */
    fun encode(c: CachedChat): String {
        val keep = ArrayList<JSONObject>()
        var chars = 0
        var dropped = false
        for (it in c.items.asReversed()) {
            val j = toJson(it) ?: continue
            val n = j.toString().length
            if (keep.size >= MAX_ITEMS || (chars + n > MAX_CHARS && keep.isNotEmpty())) { dropped = true; break }
            keep += j
            chars += n
        }
        keep.reverse()
        return JSONObject().put("v", 1).put("items", JSONArray(keep)).put("cursor", c.cursor)
            .put("more", c.more || dropped).putOpt("title", c.title)
            .put("start", if (dropped) -1L else c.start).putOpt("file", c.file).putOpt("agent", c.agent).toString()
    }

    fun decode(s: String): CachedChat? = runCatching {
        val o = JSONObject(s)
        if (o.optInt("v") != 1) return null
        val arr = o.getJSONArray("items")
        CachedChat(
            items = Transcript.merge(emptyList(), (0 until arr.length()).map { arr.getJSONObject(it) }, true),
            cursor = o.getString("cursor"),
            more = o.optBoolean("more"),
            title = if (o.isNull("title")) null else o.optString("title").ifBlank { null },
            start = o.optLong("start", -1L),
            file = if (o.isNull("file")) null else o.optString("file").ifBlank { null },
            agent = if (o.isNull("agent")) null else o.optString("agent").ifBlank { null },
        )
    }.getOrNull()
}

/**
 * The chats kept in the app's private storage, one sealed file per server and pane (sealed with a
 * key in the Android Keystore; nothing is ever written on the computer). The oldest files go once
 * all of them pass [maxTotal] bytes. Any file that can't be opened is removed and read afresh.
 */
class ChatStore(
    private val dir: File,
    private val seal: (ByteArray) -> ByteArray,
    private val open: (ByteArray) -> ByteArray,
    private val maxTotal: Long = 8L * 1024 * 1024,
) {
    private fun part(s: String) = s.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("").take(40) +
        "-" + Integer.toHexString(s.hashCode())
    private fun prefix(serverId: String) = part(serverId) + "__"
    private fun file(serverId: String, paneId: String) = File(dir, prefix(serverId) + part(paneId) + ".chat")
    /** A pane's question history (1.3.0), beside its chat, sealed the same way and counted in the same total. */
    private fun qfile(serverId: String, paneId: String) = File(dir, prefix(serverId) + part(paneId) + ".q")
    private fun kept(f: File) = f.name.endsWith(".chat") || f.name.endsWith(".q")

    @Synchronized fun save(serverId: String, paneId: String, chat: CachedChat) {
        runCatching {
            dir.mkdirs()
            val f = file(serverId, paneId)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(seal(ChatCodec.encode(chat).toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            trim()
        }
    }

    @Synchronized fun load(serverId: String, paneId: String): CachedChat? {
        val f = file(serverId, paneId)
        if (!f.isFile) return null
        val c = runCatching { ChatCodec.decode(String(open(f.readBytes()), Charsets.UTF_8)) }.getOrNull()
        if (c == null) f.delete()
        return c
    }

    @Synchronized fun saveQuestions(serverId: String, paneId: String, qs: List<QuestionItem>) {
        runCatching {
            dir.mkdirs()
            val f = qfile(serverId, paneId)
            if (qs.isEmpty()) { f.delete(); return@runCatching }
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(seal(QuestionLog.encode(qs).toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            trim()
        }
    }

    @Synchronized fun loadQuestions(serverId: String, paneId: String): List<QuestionItem>? {
        val f = qfile(serverId, paneId)
        if (!f.isFile) return null
        val q = runCatching { QuestionLog.decode(String(open(f.readBytes()), Charsets.UTF_8)) }.getOrNull()
        if (q == null) f.delete()
        return q
    }

    /** Removes the pane's chat. Its question history goes too, unless [questions] is false (the agent's log was only re-read). */
    @Synchronized fun remove(serverId: String, paneId: String, questions: Boolean = true) {
        file(serverId, paneId).delete()
        if (questions) qfile(serverId, paneId).delete()
    }

    @Synchronized fun removeServer(serverId: String) {
        dir.listFiles()?.filter { it.name.startsWith(prefix(serverId)) }?.forEach { it.delete() }
    }

    /** Removes the server's files for every pane not in [keep] (closed panes). */
    @Synchronized fun keepOnly(serverId: String, keep: Collection<String>) {
        val names = keep.flatMap { listOf(file(serverId, it).name, qfile(serverId, it).name) }.toSet()
        dir.listFiles()?.filter { it.name.startsWith(prefix(serverId)) && kept(it) && it.name !in names }?.forEach { it.delete() }
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { kept(it) }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > maxTotal) f.delete()
        }
    }
}
