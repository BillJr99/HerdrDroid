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
) : Item

/** What one read of an agent's log returned. */
data class TranscriptRead(
    val ok: Boolean,
    val why: String?,
    val cursor: String,
    val reset: Boolean,
    val more: Boolean,
    val title: String?,
    val items: List<JSONObject>,
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
        )
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }

    /** Turns one raw item into a display item; a tool result is handled by [merge]. */
    fun toItem(j: JSONObject): Item? {
        val k = j.optString("k")
        return when (j.optString("t")) {
            "user" -> UserItem(k, j.optString("text"))
            "text" -> TextItem(k, j.optString("text"))
            "think" -> ThinkItem(k, j.optString("text"), if (j.isNull("ms")) null else j.optLong("ms").takeIf { it > 0 })
            "note" -> NoteItem(k, j.optString("text"))
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
    for (it in items) {
        if (it is ToolItem || it is ThinkItem) run += it else {
            flush()
            rows += OneRow(it)
        }
    }
    flush()
    return rows
}
