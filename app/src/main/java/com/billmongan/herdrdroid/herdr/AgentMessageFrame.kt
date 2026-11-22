package com.billmongan.herdrdroid.herdr

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Base64
import org.json.JSONObject

/**
 * 2.1.5 (Bill: "we will get rid of the listen for agent proposal menu and just replace it with
 * when I ask you to do a cross server send then you reply with a code that I will reply with right
 * there in the chat and that's how we'll confirm"). The agent picks a short code itself and
 * renders this frame in its chat:
 *
 *     HDMSG2 K7P3QX
 *     HDTO Hermes on laptop2
 *     HD> <standard padded Base64 of the message, chunks of at most 40 characters>
 *     HDEND K7P3QX
 *
 * The frame is inert: it only puts a card on the phone. 2.2.0: the phone asks Yes or No, and the
 * message goes nowhere until the phone user taps Yes (or Approve on the card); the app then hands
 * this frame's code to its confirm path itself. Nobody types the code.
 */
internal data class CrossServerFrame(val code: String, val destination: String, val text: String)

internal object CrossServerCode {
    /** No 0/O, 1/I/L: a code can be read off a phone screen without doubt. */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6
    /** A card lives this long after its frame was first seen. */
    const val LIFETIME_MS = 10L * 60_000

    /** A frame's code: exactly six characters from [ALPHABET], as the agent wrote it. */
    fun valid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

}

/**
 * Every well-formed frame in recent pane output, at most one per code. A frame with a malformed
 * code, a bad line, bad Base64 or a bad message is skipped without hiding the frames around it
 * (2.1.4's parser gave up on the whole screen at the first bad line, so the copied instructions'
 * own template, echoed above the agent's reply, hid every real frame). A code that appears in two
 * frames with different contents is ambiguous and yields nothing.
 */
internal fun crossServerFrames(output: String): List<CrossServerFrame> {
    val lines = output.takeLast(65_536).lineSequence().map(::frameLine).toList()
    val found = mutableListOf<CrossServerFrame>()
    val conflicted = mutableSetOf<String>()
    var i = 0
    while (i < lines.size) {
        val code = lines[i].removePrefix("HDMSG2 ").takeIf { lines[i].startsWith("HDMSG2 ") }
        if (code == null) { i++; continue }
        val parsed = parseFrame(lines, i, code)
        if (parsed == null) { i++; continue }
        val (frame, next) = parsed
        val same = found.firstOrNull { it.code == frame.code }
        if (same == null) found += frame else if (same != frame) conflicted += frame.code
        i = next
    }
    return found.filter { it.code !in conflicted }
}

/** One frame starting at [start], with the index after its end line, or null. */
private fun parseFrame(lines: List<String>, start: Int, code: String): Pair<CrossServerFrame, Int>? {
    if (!CrossServerCode.valid(code)) return null
    val to = lines.getOrNull(start + 1)?.takeIf { it.startsWith("HDTO ") }?.removePrefix("HDTO ")?.trim() ?: return null
    if (to.isEmpty() || to.length > 120 || to.any { Character.isISOControl(it) }) return null
    val payload = StringBuilder()
    var i = start + 2
    while (i < lines.size) {
        val line = lines[i]
        if (line == "HDEND $code") {
            if (payload.isEmpty()) return null
            val text = decodeMessage(payload.toString()) ?: return null
            return CrossServerFrame(code, to, text) to i + 1
        }
        if (!line.startsWith("HD> ")) return null
        val chunk = line.substring(4)
        if (chunk.length !in 1..40 || payload.length + chunk.length > 1_368) return null
        payload.append(chunk)
        i++
    }
    return null
}

/** A terminal line as the agent meant it: some agent UIs indent fenced text, mark the reply's first line, or draw a box around code. */
private fun frameLine(raw: String): String {
    var line = raw.trim(' ')
    for (mark in listOf("⏺ ", "● ", "│ ", "┃ ")) if (line.startsWith(mark)) line = line.substring(mark.length).trim(' ')
    for (mark in listOf(" │", " ┃")) if (line.endsWith(mark)) line = line.dropLast(mark.length).trim(' ')
    return line
}

private fun decodeMessage(encoded: String): String? {
    val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
    if (bytes.size > 1_024 || Base64.getEncoder().encodeToString(bytes) != encoded) return null
    val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: CharacterCodingException) { return null }
    return text.takeIf { it.isNotBlank() && it.codePoints().noneMatch { cp ->
        cp != '\n'.code && (Character.isISOControl(cp) || when (Character.getType(cp)) {
            Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
            else -> false
        })
    } }
}

/**
 * Codes the phone has seen, by server, pane and code, with when each was first seen and whether it
 * is spent (sent, refused, failed or cancelled). Stored as JSON, so a frame still on screen after
 * an app restart never raises a second card for a spent or expired code.
 */
internal class CrossServerCodeLedger(private val load: () -> String, private val save: (String) -> Unit) {
    private data class Entry(val firstSeen: Long, val spent: Boolean)

    private val entries: MutableMap<String, Entry> = runCatching {
        val o = JSONObject(load().ifBlank { "{}" })
        o.keys().asSequence().associateWith { k ->
            o.getJSONObject(k).let { Entry(it.getLong("at"), it.getBoolean("spent")) }
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    /** The card's deadline when [key] may show a card now: not spent and not expired. Records the first sighting. */
    fun admit(key: String, now: Long): Long? {
        val e = entries[key] ?: Entry(now, false).also { entries[key] = it; persist(now) }
        if (e.spent) return null
        val deadline = e.firstSeen + CrossServerCode.LIFETIME_MS
        return deadline.takeIf { now < it }
    }

    fun spend(key: String, now: Long) {
        entries[key] = Entry(entries[key]?.firstSeen ?: now, true)
        persist(now)
    }

    fun spent(key: String): Boolean = entries[key]?.spent == true

    private fun persist(now: Long) {
        entries.entries.removeAll { now - it.value.firstSeen > 30L * 24 * 3_600_000 }
        save(JSONObject().apply {
            entries.forEach { (k, e) -> put(k, JSONObject().put("at", e.firstSeen).put("spent", e.spent)) }
        }.toString())
    }
}
