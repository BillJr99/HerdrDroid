package com.billmongan.herdrdroid.voice

import com.billmongan.herdrdroid.store.Server
import java.util.Locale

internal sealed interface VoiceCommand {
    data class Switch(val name: String, val server: String? = null) : VoiceCommand
    data class Choose(val index: Int) : VoiceCommand
    data object ReadQuestion : VoiceCommand
    data object ReadReply : VoiceCommand
    data object Confirm : VoiceCommand
    data object Cancel : VoiceCommand
    data object Repeat : VoiceCommand
    data class Message(val pane: Switch) : VoiceCommand
    data class Dictate(val text: String) : VoiceCommand
    data object ConfirmMessage : VoiceCommand
    data object CancelMessage : VoiceCommand
}

internal val voiceCommandHelp = listOf(
    "Switch to Codex on Laptop", "Switch to Laptop", "Read the latest reply",
    "Repeat that", "Read question", "Option two", "Confirm answer", "Cancel answer",
    "Send a message to Codex on Laptop", "Dictate check the build", "Confirm message", "Cancel message",
)

private fun paneTarget(text: String): VoiceCommand.Switch? {
    val name = text.substringBefore(" on ")
    val server = text.substringAfter(" on ", "").ifBlank { null }
    if (name.none(Char::isLetterOrDigit) || text.endsWith(" on") ||
        (server != null && server.none(Char::isLetterOrDigit))) return null
    return VoiceCommand.Switch(name, server)
}

internal fun parseVoiceCommand(text: String): VoiceCommand? {
    Regex("^dictate(?:\\s*:\\s*|\\s+)(.+)$", RegexOption.IGNORE_CASE).matchEntire(text.trim())
        ?.groupValues?.get(1)?.trim()?.takeIf { it.length in 1..500 && it.first().isLetterOrDigit() &&
            it.none(Char::isISOControl) && !it.contains("[unk]", ignoreCase = true) }
        ?.let { return VoiceCommand.Dictate(it) }
    val words = text.lowercase(Locale.ROOT).trim().trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
    return when (words) {
        "read the pending question", "read the question", "read question" -> VoiceCommand.ReadQuestion
        "read the latest reply", "read latest reply", "read reply" -> VoiceCommand.ReadReply
        "repeat", "repeat that", "repeat the last message" -> VoiceCommand.Repeat
        "confirm message" -> VoiceCommand.ConfirmMessage
        "cancel message" -> VoiceCommand.CancelMessage
        "confirm answer" -> VoiceCommand.Confirm
        "cancel answer" -> VoiceCommand.Cancel
        else -> {
            val number = Regex("^(?:choose |select )?option (.+)$").matchEntire(words)?.groupValues?.get(1)
            if (number != null) {
                val named = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
                return (number.toIntOrNull() ?: (named.indexOf(number) + 1))
                    .takeIf { it > 0 }?.let { VoiceCommand.Choose(it - 1) }
            }
            Regex("^send (?:a )?message to (.+)$").matchEntire(words)?.let {
                return paneTarget(it.groupValues[1])?.let { target -> VoiceCommand.Message(target) }
            }
            // Vosk commonly hears the initial consonants of “switch to” as “which to.”
            val target = Regex("^(?:switch to|which to) (.+)$").matchEntire(words)?.groupValues?.get(1)
                ?: Regex("^which too (many pc(?: to| two)?)$").matchEntire(words)?.groupValues?.get(1)
                ?: return null
            paneTarget(target)
        }
    }
}

internal fun voiceName(text: String): String {
    val cleaned = text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    return cleaned.replace(Regex("^(?:mini|many)pc(?:two|too|to)$"), "laptop2")
}

/** The short spoken example is a fallback, not a rename of a saved server or pane. */
internal fun voiceServersNamed(servers: List<Server>, spoken: String): List<Server> {
    val name = voiceName(spoken)
    val matches = servers.filter { voiceName(it.label) == name }
    return if (matches.isEmpty() && (name == "laptop" || name == "manypc")) {
        servers.filter { voiceName(it.label) == "laptop2" }
    } else matches
}
