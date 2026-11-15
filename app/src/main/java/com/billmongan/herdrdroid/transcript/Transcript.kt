package com.billmongan.herdrdroid.transcript

import org.json.JSONArray
import org.json.JSONObject

/** One thing in a conversation, as the chat screen draws it. [key] stays the same across reads. */
sealed interface Item {
    val key: String
}

data class UserItem(override val key: String, val text: String, val sending: Boolean = false) : Item
data class TextItem(override val key: String, val text: String) : Item
data class ThinkItem(override val key: String, val text: String, val ms: Long?) : Item
data class NoteItem(override val key: String, val text: String) : Item

/**
 * An attachment in the conversation: an image or any other file or blob. It lives inside the
 * agent's log ([logFile], [logOffset], [logIndex], a JSON array of block indexes), in OpenCode's
 * blob table ([blobDb], [blobHash]), or as a file on the computer ([path]). Its bytes are fetched
 * only when it is shown or opened. Images are drawn in the chat; other files get a card with
 * Download and Share. [user] marks one you attached.
 */
data class ImageItem(
    override val key: String,
    val logFile: String? = null,
    val logOffset: Long = 0,
    val logIndex: String? = null,
    val path: String? = null,
    val mime: String = "image/png",
    val kb: Int = 0,
    val user: Boolean = false,
    val blobDb: String? = null,
    val blobHash: String? = null,
    val name: String? = null,
) : Item {
    val isImage get() = mime.startsWith("image/") && !mime.contains("svg")
    val label get() = name ?: path?.substringAfterLast('/') ?: if (isImage) "image" else "attachment"
}

enum class ToolState { RUNNING, OK, ERROR }

/** The before and after of an edit, for the diff card. */
data class DiffData(val path: String?, val old: String, val new: String)

data class ToolItem(
    override val key: String,
    val id: String?,
    val name: String,
    val title: String,
    val kind: String,
    val input: String?,
    val diff: DiffData?,
    val patch: String?,
    val out: String?,
    val state: ToolState,
    /** The file the call read or wrote, so it can be opened, saved or shared. */
    val path: String? = null,
    /** An image file the call produced, shown in the chat. */
    val image: String? = null,
    /** Files the call handed to the user (SendUserFile), shown in the chat as attachments. */
    val attach: List<String> = emptyList(),
) : Item

/** A MIME type from a file name, for attachments known only by path. */
fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "pdf" -> "application/pdf"
    "html", "htm" -> "text/html"
    "md" -> "text/markdown"
    "csv" -> "text/csv"
    "json" -> "application/json"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    "txt", "log", "py", "kt", "ts", "js", "sh", "yaml", "yml", "toml" -> "text/plain"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    else -> "application/octet-stream"
}

/** What one read of an agent's log returned. */
data class TranscriptRead(
    val ok: Boolean,
    val why: String?,
    val cursor: String,
    val reset: Boolean,
    val more: Boolean,
    val title: String?,
    val items: List<JSONObject>,
    /** The computer's clock when it answered, in epoch ms (1.4.3); 0 when the reader didn't say. */
    val hostNow: Long = 0L,
)

object Transcript {
    fun parseRead(json: String): TranscriptRead {
        val o = JSONObject(json)
        if (!o.optBoolean("ok")) {
            return TranscriptRead(false, o.optString("why").ifBlank { null }, "", false, false, null, emptyList())
        }
        val arr: JSONArray = o.optJSONArray("items") ?: JSONArray()
        return TranscriptRead(
            ok = true,
            why = null,
            cursor = (o.optJSONObject("cur") ?: JSONObject()).toString(),
            reset = o.optBoolean("reset"),
            more = o.optBoolean("more"),
            title = o.optString("title").takeIf { !o.isNull("title") && it.isNotBlank() },
            items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) },
            hostNow = o.optLong("now", 0L),
        )
    }

    /** Each raw item's time ("ts", epoch ms on the computer's clock) by key, where the log has one (1.4.3). */
    fun timesOf(raw: List<JSONObject>): Map<String, Long> =
        raw.mapNotNull { j -> j.optString("k").ifEmpty { null }?.let { k -> j.optLong("ts", 0L).takeIf { it > 0 }?.let { k to it } } }.toMap()

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }

    /** Turns one raw item into a display item; a tool result is handled by [merge]. */
    fun toItem(j: JSONObject): Item? {
        val k = j.optString("k")
        return when (j.optString("t")) {
            "user" -> UserItem(k, j.optString("text"))
            "text" -> TextItem(k, j.optString("text"))
            "think" -> ThinkItem(k, j.optString("text"), if (j.isNull("ms")) null else j.optLong("ms").takeIf { it > 0 })
            "note" -> NoteItem(k, j.optString("text"))
            "image" -> {
                val src = j.optJSONObject("src")
                ImageItem(
                    key = k,
                    logFile = src?.optString("file")?.ifEmpty { null },
                    logOffset = src?.optLong("off") ?: 0,
                    logIndex = src?.optJSONArray("idx")?.toString(),
                    path = j.str("path"),
                    mime = j.optString("mime").ifBlank { "image/png" },
                    kb = j.optInt("kb"),
                    user = j.optBoolean("user"),
                    blobDb = src?.optString("db")?.ifEmpty { null },
                    blobHash = src?.optString("hash")?.ifEmpty { null },
                    name = j.str("name"),
                )
            }
            "tool" -> {
                val d = j.optJSONObject("diff")
                ToolItem(
                    key = k,
                    id = j.str("id"),
                    name = j.optString("name"),
                    title = j.optString("title").ifBlank { j.optString("name") },
                    kind = j.optString("kind").ifBlank { "other" },
                    input = j.str("input"),
                    diff = d?.let { DiffData(it.str("path"), it.optString("old"), it.optString("new")) },
                    patch = j.str("patch"),
                    out = j.str("out"),
                    state = when (j.optString("state")) {
                        "ok" -> ToolState.OK
                        "err" -> ToolState.ERROR
                        else -> ToolState.RUNNING
                    },
                    path = j.str("path"),
                    image = j.str("image"),
                    attach = j.optJSONArray("attach")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } } }.orEmpty(),
                )
            }
            else -> null
        }
    }

    /**
     * Folds new raw items into the list. An item with a key already present replaces it in place
     * (OpenCode and Hermes rewrite a reply while it streams); a tool result fills in the call it
     * answers. Results for calls that fell outside the window shown are dropped.
     */
    fun merge(prev: List<Item>, raw: List<JSONObject>, reset: Boolean): List<Item> {
        val list = if (reset) mutableListOf() else prev.toMutableList()
        val index = HashMap<String, Int>(list.size * 2)
        list.forEachIndexed { i, it -> index[it.key] = i }
        for (j in raw) {
            if (j.optString("t") == "result") {
                val ref = j.optString("ref").ifEmpty { null } ?: continue
                val at = list.indexOfLast { it is ToolItem && it.id == ref }
                if (at >= 0) {
                    val t = list[at] as ToolItem
                    val err = j.optBoolean("err")
                    list[at] = t.copy(out = if (j.isNull("out")) t.out else j.optString("out"), state = if (err) ToolState.ERROR else ToolState.OK)
                }
                continue
            }
            val item = toItem(j) ?: continue
            // One card per tool call (1.3.0, from a code review): Copilot logs a call both in its
            // message's toolRequests and again at tool.execution_start, under different keys. The
            // second fills in the first, so the result lands on the card that is shown.
            if (item is ToolItem && item.id != null && index[item.key] == null) {
                val same = list.indexOfLast { it is ToolItem && it.id == item.id }
                if (same >= 0) {
                    val t = list[same] as ToolItem
                    list[same] = t.copy(input = t.input ?: item.input, name = t.name.ifBlank { item.name }, title = t.title.ifBlank { item.title })
                    continue
                }
            }
            val at = index[item.key]
            if (at != null) {
                val old = list[at]
                // A re-sent call keeps the output it already has.
                list[at] = if (item is ToolItem && old is ToolItem && item.out == null && old.out != null) {
                    item.copy(out = old.out, state = old.state)
                } else {
                    item
                }
            } else {
                index[item.key] = list.size
                list += item
            }
        }
        return list
    }

    /** Text to copy or share for an item. */
    fun plain(item: Item): String = when (item) {
        is UserItem -> item.text
        is TextItem -> item.text
        is ThinkItem -> item.text
        is NoteItem -> item.text
        is QuestionItem -> QuestionLog.plain(item)
        is ImageItem -> item.path ?: "[image]"
        is ToolItem -> buildString {
            append(item.title)
            item.input?.let { append("\n\n").append(it) }
            item.patch?.let { append("\n\n").append(it) }
            item.diff?.let { append("\n\n--- before\n").append(it.old).append("\n+++ after\n").append(it.new) }
            item.out?.let { append("\n\n").append(it) }
        }
    }
}

/**
 * The chat broken into turns for drawing: your message, then everything the agent did in reply.
 * The agent's tool calls and reasoning between two pieces of its text are gathered into one
 * collapsible "activity" row, the way the commercial chat apps show work in progress.
 */
sealed interface Row {
    val key: String
}

data class OneRow(val item: Item) : Row {
    override val key get() = item.key
}

data class ActivityRow(override val key: String, val items: List<Item>) : Row {
    val tools get() = items.filterIsInstance<ToolItem>()
    val running get() = tools.any { it.state == ToolState.RUNNING }
    val failed get() = tools.count { it.state == ToolState.ERROR }
}

fun rowsOf(items: List<Item>): List<Row> {
    val rows = mutableListOf<Row>()
    val run = mutableListOf<Item>()
    fun flush() {
        if (run.isEmpty()) return
        rows += if (run.size == 1 && run[0] is ThinkItem) OneRow(run[0]) else ActivityRow("act:" + run.first().key, run.toList())
        run.clear()
    }
    val produced = mutableListOf<ImageItem>()
    for (it in items) {
        if (it is ToolItem || it is ThinkItem) {
            run += it
            // An image file a tool wrote is shown in the chat after that stretch of work.
            if (it is ToolItem && it.image != null && it.state == ToolState.OK) {
                produced += ImageItem("img:" + it.key, path = it.image, mime = mimeFor(it.image))
            }
            if (it is ToolItem) {
                it.attach.forEachIndexed { i, f ->
                    produced += ImageItem("att:${it.key}:$i", path = f, mime = mimeFor(f), name = f.substringAfterLast('/'))
                }
            }
        } else {
            flush()
            produced.forEach { p -> rows += OneRow(p) }
            produced.clear()
            rows += OneRow(it)
        }
    }
    flush()
    produced.forEach { p -> rows += OneRow(p) }
    return rows
}
