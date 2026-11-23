package com.billmongan.herdrdroid.herdr

/**
 * 2.2.7 (Bill: "if I paste a giant thing of text like the diagnostic log I think it's cutting that
 * off ... make sure larger pastes become file attachments automatically at the appropriate size so
 * we never actually truncate anything. do this transparently"). An 8,795-character paste arrived
 * at Claude cut mid-line, so a message is never sent larger than [PART_MAX_BYTES]:
 * - up to that size it goes as one message;
 * - up to [MAX_PARTS] parts it goes as numbered parts, "(1/3) ...", split on line boundaries, each
 *   sent only after the one before was delivered;
 * - anything larger goes as a .txt file in the computer's inbox (checked by size and SHA-256), with
 *   a one-line message pointing at it.
 * Nothing is asked and nothing is dropped: the parts, without their labels, join back to the text.
 */
internal object LongText {
    /** The largest single message, in UTF-8 bytes: under a terminal's 4,096-byte line, with room for a label. */
    const val PART_MAX_BYTES = 3_500
    /** More parts than this and the text goes as a file instead. */
    const val MAX_PARTS = 4

    sealed class Plan {
        data class Single(val text: String) : Plan()
        /** Each part already carries its "(i/n) " label. */
        data class Parts(val parts: List<String>) : Plan()
        data class Attachment(val text: String) : Plan()
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    fun label(i: Int, n: Int) = "($i/$n) "

    fun plan(text: String): Plan {
        if (bytes(text) <= PART_MAX_BYTES) return Plan.Single(text)
        val chunks = split(text, PART_MAX_BYTES - 12) ?: return Plan.Attachment(text)
        if (chunks.size > MAX_PARTS) return Plan.Attachment(text)
        return Plan.Parts(chunks.mapIndexed { i, c -> label(i + 1, chunks.size) + c })
    }

    /** The parts without their labels, joined: always the original text. */
    fun join(parts: List<String>): String = parts.joinToString("") { it.replaceFirst(Regex("""^\(\d+/\d+\) """), "") }

    /**
     * Splits [text] into chunks of at most [max] bytes, at line ends where it can (a line longer
     * than that is cut between characters). Null when it would need more than [MAX_PARTS] chunks.
     * Every character is kept: the chunks concatenate to [text].
     */
    fun split(text: String, max: Int): List<String>? {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var curBytes = 0
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.clear(); curBytes = 0 } }
        // Lines with their own "\n" kept, so joining gives the text back exactly.
        val lines = Regex("""[^\n]*\n|[^\n]+$""").findAll(text).map { it.value }.toList()
        for (line in lines) {
            val lb = bytes(line)
            if (curBytes + lb <= max) { cur.append(line); curBytes += lb; continue }
            flush()
            if (lb <= max) { cur.append(line); curBytes = lb; continue }
            // A line longer than a part: cut it between characters (never inside a surrogate pair).
            var i = 0
            while (i < line.length) {
                val sb = StringBuilder()
                var b = 0
                while (i < line.length) {
                    val cp = line.codePointAt(i)
                    val s = String(Character.toChars(cp))
                    val sbs = bytes(s)
                    if (b + sbs > max) break
                    sb.append(s); b += sbs; i += s.length
                }
                if (i < line.length) out += sb.toString() else { cur.append(sb); curBytes = b }
            }
            if (out.size > MAX_PARTS) return null
        }
        flush()
        return if (out.size > MAX_PARTS) null else out
    }

    /** "55.7 KB" style size for the one-line message. */
    fun size(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(java.util.Locale.US, bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
        else -> "$bytes bytes"
    }
}

/**
 * 2.2.7: a slash command typed for the agent ("/model", "/usage now"), not text that only starts
 * with a slash. An attachment's path ("/home/bill/.herdrdroid/inbox/…") sent on its own switched the
 * chat to the terminal view, and Claude Code took it for an unknown command.
 */
internal fun isSlashCommand(text: String): Boolean = Regex("""^/[A-Za-z][A-Za-z0-9_:.-]*(\s|$)""").containsMatchIn(text.trimStart())

/**
 * 2.2.7: the message box after an attachment arrived: its path added to what is typed. With
 * nothing typed it reads "Attached file: <path>", so the message never starts with a path.
 */
internal fun withAttachment(message: String, path: String): String =
    if (message.isBlank()) "Attached file: $path " else message.trimEnd() + " " + path + " "
