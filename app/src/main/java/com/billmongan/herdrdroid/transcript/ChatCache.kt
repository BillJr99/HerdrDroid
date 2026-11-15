package com.billmongan.herdrdroid.transcript

import com.billmongan.herdrdroid.diag.DiagLog
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
    /** Each item's time on the computer's clock, by key, where its log has one (1.4.3: questions are placed by it). */
    val times: Map<String, Long> = emptyMap(),
)

/**
 * This chat with one read of its log folded in: new items are added by key, so a read that
 * overlaps what is kept adds nothing twice; a reset (another session or file, or a log that shrank)
 * replaces it all.
 */
fun CachedChat.withRead(r: TranscriptRead): CachedChat {
    val cj = runCatching { JSONObject(r.cursor) }.getOrNull()
    return if (r.reset) CachedChat(Transcript.merge(emptyList(), r.items, true), r.cursor, r.more, r.title ?: title,
        cj?.optLong("start", -1L) ?: -1L, cj?.optString("file")?.ifBlank { null }, agent, Transcript.timesOf(r.items))
    else copy(items = if (r.items.isEmpty()) items else Transcript.merge(items, r.items, false), cursor = r.cursor, title = r.title ?: title,
        times = if (r.items.isEmpty()) times else times + Transcript.timesOf(r.items))
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
        val kept = keep.map { it.optString("k") }.toHashSet()
        val times = JSONObject().also { t -> c.times.forEach { (k, v) -> if (k in kept) t.put(k, v) } }
        return JSONObject().put("v", 1).put("items", JSONArray(keep)).put("cursor", c.cursor).put("times", times)
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
            times = o.optJSONObject("times")?.let { t -> t.keys().asSequence().associateWith { t.optLong(it) }.filterValues { it > 0 } }.orEmpty(),
        )
    }.getOrNull()
}

/**
 * The chats kept in the app's private storage, one sealed file per server and pane (sealed with a
 * key in the Android Keystore; nothing is ever written on the computer). The oldest files go once
 * all of them pass [maxTotal] bytes. A file that can't be opened is kept or set aside, never deleted (1.3.2).
 */
class ChatStore(
    private val dir: File,
    private val seal: (ByteArray, ByteArray?) -> ByteArray,
    private val open: (ByteArray, ByteArray?) -> ByteArray,
    /** True for a file already bound to its pane (ChatVault's "HDv3"); others are bound at their next load. */
    private val bound: (ByteArray) -> Boolean = { true },
    private val maxTotal: Long = 8L * 1024 * 1024,
) {
    /** For callers (and tests) whose seal takes no associated data. */
    constructor(dir: File, seal: (ByteArray) -> ByteArray, open: (ByteArray) -> ByteArray, maxTotal: Long = 8L * 1024 * 1024) :
        this(dir, { b: ByteArray, _: ByteArray? -> seal(b) }, { b: ByteArray, _: ByteArray? -> open(b) }, { true }, maxTotal)

    // 1.4.1 (from a code review): names end in a SHA-256 prefix of the ids, not a 32-bit hash two ids
    // could share, and each seal carries the server id, pane id and kind as associated data, so a
    // file put in another pane's place fails to open instead of showing that pane's chat.
    private fun clean(s: String) = s.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("")
    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(16)
    private fun part(s: String) = clean(s).take(24) + "-" + sha(s)
    private fun prefix(serverId: String) = part(serverId) + "__"
    private fun file(serverId: String, paneId: String) = File(dir, prefix(serverId) + part(paneId) + ".chat")
    /** A pane's question history (1.3.0), beside its chat, sealed the same way and counted in the same total. */
    private fun qfile(serverId: String, paneId: String) = File(dir, prefix(serverId) + part(paneId) + ".q")
    private fun aad(serverId: String, paneId: String, kind: String) = "herdrdroid|$kind|$serverId|$paneId".toByteArray(Charsets.UTF_8)

    // The 1.2.9 to 1.4.0 names (no associated data): read, moved to the new name, and removed only
    // after the new copy was read back.
    private fun oldPart(s: String) = clean(s).take(40) + "-" + Integer.toHexString(s.hashCode())
    private fun oldPrefix(serverId: String) = oldPart(serverId) + "__"
    private fun oldFile(serverId: String, paneId: String, ext: String) = File(dir, oldPrefix(serverId) + oldPart(paneId) + ext)
    private fun ofServer(f: File, serverId: String) = f.name.startsWith(prefix(serverId)) || f.name.startsWith(oldPrefix(serverId))

    private fun kept(f: File) = f.isFile && (f.name.endsWith(".chat") || f.name.endsWith(".q"))

    @Synchronized fun save(serverId: String, paneId: String, chat: CachedChat) {
        runCatching {
            dir.mkdirs()
            writeKept(file(serverId, paneId), oldFile(serverId, paneId, ".chat"), ChatCodec.encode(chat).toByteArray(Charsets.UTF_8), aad(serverId, paneId, "chat"))
        }.onFailure { DiagLog.log("chat", "a kept chat wasn't saved (${it.javaClass.simpleName}); the previous copy stays") }
    }


    /**
     * The kept files whose contents this process loaded (returned to the app) or wrote and read back
     * (1.4.1). A save only writes over, or removes, a file in this set: anything else may hold a
     * newer chat the app never saw, so it is kept.
     */
    private val trusted = HashSet<String>()

    /**
     * Saves [plain] as [f] (1.4.1, from three rounds of code review), never over a copy the app hasn't
     * read:
     *  - an existing [f] whose contents this process never loaded or wrote is left alone (nothing is
     *    written; the in-memory chat stays and is saved once the file has been loaded);
     *  - the new seal is written beside [f] and read back there, and only a copy that opens to [plain]
     *    is moved onto [f] in one atomic step; otherwise the temporary file goes and [f] is untouched;
     *  - [old] (the 1.4.0 name) is removed only when it opens and holds the same text, or when its
     *    contents were what the app loaded, or when it is older than the [f] this save replaced.
     */
    private fun writeKept(f: File, old: File, plain: ByteArray, ad: ByteArray) {
        val existed = f.isFile
        if (existed && f.name !in trusted) {
            DiagLog.log("chat", "a kept file the app hasn't read yet wasn't written over; it is saved once that file has been loaded")
            return
        }
        val before = if (existed) f.lastModified() else 0L
        if (!swapIn(f, plain, ad)) {
            DiagLog.log("chat", "a save didn't read back the same; the kept file stays as it was")
            return
        }
        trusted += f.name
        if (old.isFile) {
            val o = openPlain(old, null)
            if (o != null && (o.contentEquals(plain) || old.name in trusted || (existed && old.lastModified() <= before))) {
                old.delete(); trusted -= old.name
            }
        }
        trim(keep = f)
    }

    /**
     * Seals [plain] into a temporary file beside [f], reads it back there, and only when it opens to
     * [plain] moves it onto [f] in one atomic step (1.4.1, from code review). False, with [f] as it
     * was, otherwise. The temporary file is the app's own and goes either way.
     */
    private fun swapIn(f: File, plain: ByteArray, ad: ByteArray): Boolean {
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        try {
            tmp.writeBytes(seal(plain, ad))
            val back = runCatching { open(tmp.readBytes(), ad) }.getOrNull()
            if (back == null || !back.contentEquals(plain)) return false
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return true
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Opens a kept file. Null when it can't be opened now. Only a file that is damaged for certain
     * (its seal fails to authenticate) is moved to unreadable/ (1.3.2); locked, invalidated, a missing
     * key or a read error keep it where it is.
     */
    private fun openPlain(f: File, ad: ByteArray?): ByteArray? = try { open(f.readBytes(), ad) } catch (e: Exception) {
        if (com.billmongan.herdrdroid.store.Vaults.badSeal(e)) setAside(f)
        null
    }

    /**
     * A pane's file, decoded. The new name first: when it opens, a leftover under the old name is
     * removed (the new one is newer or the same), and a file not yet bound to its pane is sealed
     * again with its data. Otherwise the old name: it is opened without associated data, written
     * under the new name and seal, read back, and only then removed. A failure at any step keeps
     * the old file, so the next load tries again.
     */
    private fun <T> loadPane(serverId: String, paneId: String, kind: String, ext: String, f: File, decode: (String) -> T?): T? {
        val ad = aad(serverId, paneId, kind)
        val legacy = oldFile(serverId, paneId, ext)
        if (f.isFile) {
            val bytes = runCatching { f.readBytes() }.getOrNull()
            val plain = bytes?.let { openPlain(f, ad) }
            val v = plain?.let { p -> runCatching { decode(String(p, Charsets.UTF_8)) }.getOrNull() }
            if (plain != null && v == null) setAside(f)
            if (v != null) {
                trusted += f.name
                // An old copy is removed only when it opens and holds the same, or something older
                // than the new file (1.4.1, from a code review: a guard that lasted one call).
                if (legacy.isFile) {
                    val o = openPlain(legacy, null)
                    if (o != null && (o.contentEquals(plain) || legacy.lastModified() <= f.lastModified())) legacy.delete()
                }
                // Bound to its pane through the same read-back swap (1.4.1, from code review).
                if (!bound(bytes!!)) runCatching { swapIn(f, plain, ad) }
                return v
            }
            // The new file can't be used now (locked, a read error, damaged): an old copy, if there
            // is one, is read instead, and nothing is removed (1.4.1, from a code review).
            if (f.isFile) {
                if (!legacy.isFile) return null
                val o = openPlain(legacy, null) ?: return null
                return runCatching { decode(String(o, Charsets.UTF_8)) }.getOrNull()?.also { trusted += legacy.name }
            }
        }
        if (!legacy.isFile) return null
        val plain = openPlain(legacy, null) ?: return null
        val v = runCatching { decode(String(plain, Charsets.UTF_8)) }.getOrNull()
        if (v == null) { setAside(legacy); return null }
        trusted += legacy.name
        runCatching {
            // Read back before it takes the new name: only a copy that opens to the same text
            // replaces the old one, and the old one goes only after that.
            if (swapIn(f, plain, ad)) { trusted += f.name; legacy.delete(); trusted -= legacy.name }
        }.onFailure { DiagLog.log("chat", "a kept chat wasn't moved to its new name yet (${it.javaClass.simpleName}); the old copy stays") }
        return v
    }

    /**
     * A file that can't be opened goes to unreadable/, never deleted (1.3.2): a key replaced after
     * the old one was invalidated can't open it, but it is still the user's only copy. Removing the
     * server clears that folder with the rest.
     */
    private fun setAside(f: File) {
        val bin = File(dir, "unreadable").apply { mkdirs() }
        if (!f.renameTo(File(bin, f.name))) DiagLog.log("chat", "a file that can't be opened stays where it is")
    }

    @Synchronized fun load(serverId: String, paneId: String): CachedChat? =
        loadPane(serverId, paneId, "chat", ".chat", file(serverId, paneId)) { ChatCodec.decode(it) }

    /** True when the pane has a question history on the phone, whether or not it can be opened now (1.3.2). */
    @Synchronized fun hasQuestions(serverId: String, paneId: String): Boolean =
        qfile(serverId, paneId).isFile || oldFile(serverId, paneId, ".q").isFile

    @Synchronized fun saveQuestions(serverId: String, paneId: String, qs: List<QuestionItem>) {
        runCatching {
            // Never an empty list over a history (1.3.2): clearing one is remove()'s job, not a save's.
            if (qs.isEmpty()) return@runCatching
            dir.mkdirs()
            writeKept(qfile(serverId, paneId), oldFile(serverId, paneId, ".q"), QuestionLog.encode(qs).toByteArray(Charsets.UTF_8), aad(serverId, paneId, "q"))
        }.onFailure { DiagLog.log("chat", "a question history wasn't saved (${it.javaClass.simpleName}); the previous copy stays") }
    }

    @Synchronized fun loadQuestions(serverId: String, paneId: String): List<QuestionItem>? =
        loadPane(serverId, paneId, "q", ".q", qfile(serverId, paneId)) { QuestionLog.decode(it) }

    /** Removes the pane's chat. Its question history goes too, unless [questions] is false (the agent's log was only re-read). */
    @Synchronized fun remove(serverId: String, paneId: String, questions: Boolean = true) {
        file(serverId, paneId).delete(); oldFile(serverId, paneId, ".chat").delete()
        if (questions) { qfile(serverId, paneId).delete(); oldFile(serverId, paneId, ".q").delete() }
    }

    @Synchronized fun removeServer(serverId: String) {
        dir.listFiles()?.filter { ofServer(it, serverId) }?.forEach { it.delete() }
        File(dir, "unreadable").listFiles()?.filter { ofServer(it, serverId) }?.forEach { it.delete() }
    }

    /** Removes the server's files for every pane not in [keep] (closed panes). */
    @Synchronized fun keepOnly(serverId: String, keep: Collection<String>) {
        val names = keep.flatMap { listOf(file(serverId, it).name, qfile(serverId, it).name, oldFile(serverId, it, ".chat").name, oldFile(serverId, it, ".q").name) }.toSet()
        dir.listFiles()?.filter { ofServer(it, serverId) && kept(it) && it.name !in names }?.forEach { it.delete() }
    }

    /**
     * Keeps the kept chats under [maxTotal], oldest first. Only chat files under the current names
     * go: a chat can be read again from the agent's log. Question histories (only on the phone),
     * files under the 1.4.0 names and the file just saved ([keep]) are never trimmed (1.4.1, from
     * code review).
     */
    private fun trim(keep: File? = null) {
        val files = dir.listFiles()?.filter { kept(it) }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > maxTotal && f.name.endsWith(".chat") && f.name != keep?.name && !isOldName(f.name)) f.delete()
        }
    }

    /** A name in the 1.2.9 to 1.4.0 form: its parts end in a short hex hash, not a 16-digit SHA-256 prefix. */
    private fun isOldName(name: String) = !Regex("""-[0-9a-f]{16}__.*-[0-9a-f]{16}\.(chat|q)$""").containsMatchIn(name)
}
