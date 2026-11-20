package com.billmongan.herdrdroid.herdr

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Base64

/** An inert, one-use proposal in selected-pane output, never a command or a target selector. */
internal fun agentMessageFrame(output: String, code: String): String? {
    if (output.length > 65_536) return null
    val start = "HDMSG1 $code"
    val end = "HDEND $code"
    val payload = StringBuilder()
    var state = 0
    for (raw in output.lineSequence()) {
        // Some agent UIs indent fenced text, and a terminal can add right padding.
        val line = raw.trim(' ')
        when {
            line == start -> { if (state != 0) return null; state = 1 }
            line == end -> { if (state != 1) return null; state = 2 }
            state == 1 -> {
                if (!line.startsWith("HD> ")) return null
                val chunk = line.substring(4)
                if (chunk.length !in 1..40 || payload.length + chunk.length > 1_368) return null
                payload.append(chunk)
            }
        }
    }
    if (state != 2 || payload.isEmpty()) return null
    val encoded = payload.toString()
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
