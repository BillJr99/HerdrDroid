package com.billmongan.herdrdroid.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's diagnostic log: the last [MAX] lines, in memory only, shown under Settings with Copy,
 * Save and Clear. Nothing secret goes in: no addresses of WebSocket servers, no keys, no
 * passwords, no file contents. Byte previews are limited to the protocol's opening lines (an SSH
 * identification string), and everything after that is counted, not shown.
 */
object DiagLog {
    const val MAX = 1500
    private val lines = ArrayDeque<String>()
    private val clock = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    @Volatile var version = 0L
        private set

    fun log(tag: String, message: String) {
        val line = "${clock.get()!!.format(Date())} [$tag] $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
            version++
        }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun size(): Int = synchronized(lines) { lines.size }

    fun clear() = synchronized(lines) { lines.clear(); version++ }

    /** A WebSocket address as it may appear in the log: the scheme only. */
    fun hideUrl(url: String): String = url.substringBefore("://", "?") + "://<address hidden>"
    private val URL_IN_TEXT = Regex("""\b(?:wss?|https?)://\S+""", RegexOption.IGNORE_CASE)

    /** Up to [max] bytes as text, with \r, \n and anything unprintable escaped. */
    fun preview(bytes: ByteArray, max: Int = 64): String = buildString {
        for (b in bytes.take(max)) {
            val c = b.toInt() and 0xff
            when {
                c == 0x0d -> append("\\r")
                c == 0x0a -> append("\\n")
                c in 0x20..0x7e -> append(c.toChar())
                else -> append("\\x%02x".format(c))
            }
        }
        if (bytes.size > max) append("…")
    }

    /** Throwable class and message, with the causes, for one log line. */
    fun describe(t: Throwable?): String {
        if (t == null) return "none"
        val parts = mutableListOf<String>()
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth < 4) {
            // Any web address in a message is hidden, as [hideUrl] does for the bridge's own address
            // (1.3.0, from a code review: an OkHttp failure message carried the WebSocket address).
            parts += e.javaClass.name + ": " + URL_IN_TEXT.replace(e.message ?: "") { m -> hideUrl(m.value) }
            e = e.cause.takeIf { it !== e }
            depth++
        }
        return parts.joinToString(" <- caused by ")
    }
}
