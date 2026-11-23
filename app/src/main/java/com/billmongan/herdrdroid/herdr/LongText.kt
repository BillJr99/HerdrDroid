package com.billmongan.herdrdroid.herdr

/**
 * 2.2.7 (Bill: "make sure larger pastes become file attachments automatically at the appropriate
 * size so we never actually truncate anything. do this transparently"). Measured on laptop2:
 * `herdr pane send-text` delivers 125 KB intact; the ceiling is Linux's per-argument limit
 * (MAX_ARG_STRLEN, 128 KiB) on the SSH command, which holds the shell-quoted text (a ' becomes
 * four characters). So a message whose quoted form is up to [SINGLE_MAX_BYTES] goes as one
 * message, and anything larger goes as a .txt file in the computer's inbox (checked by size and
 * SHA-256) with a one-line message pointing at it. Nothing is asked and nothing is dropped.
 */
internal object LongText {
    /** The largest single message, in UTF-8 bytes of its shell-quoted form. */
    const val SINGLE_MAX_BYTES = 20 * 1024
    /** A guard well under the 128 KiB argument limit for the whole quoted command. */
    const val COMMAND_GUARD_BYTES = 100 * 1024

    sealed class Plan {
        data class Single(val text: String) : Plan()
        data class Attachment(val text: String) : Plan()
    }

    /** The bytes [text] takes on the command line once shell-quoted. */
    fun quotedBytes(text: String): Int = shq(text).toByteArray(Charsets.UTF_8).size

    fun plan(text: String): Plan {
        val q = quotedBytes(text)
        return if (q <= SINGLE_MAX_BYTES && q <= COMMAND_GUARD_BYTES) Plan.Single(text) else Plan.Attachment(text)
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
