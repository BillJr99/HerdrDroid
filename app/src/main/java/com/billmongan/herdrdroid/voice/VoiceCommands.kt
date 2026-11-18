package com.billmongan.herdrdroid.voice

import java.util.Locale

internal sealed interface VoiceCommand {
    data class Switch(val name: String, val server: String? = null) : VoiceCommand
    data class Choose(val index: Int) : VoiceCommand
    data object ReadQuestion : VoiceCommand
    data object ReadReply : VoiceCommand
    data object Confirm : VoiceCommand
    data object Cancel : VoiceCommand
}

internal fun parseVoiceCommand(text: String): VoiceCommand? {
    val words = text.lowercase(Locale.ROOT).trim().trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
    return when (words) {
        "read the pending question", "read the question", "read question" -> VoiceCommand.ReadQuestion
        "read the latest reply", "read latest reply", "read reply" -> VoiceCommand.ReadReply
        "confirm", "confirm answer" -> VoiceCommand.Confirm
        "cancel", "cancel answer" -> VoiceCommand.Cancel
        else -> {
            val number = Regex("^(?:choose |select )?option (.+)$").matchEntire(words)?.groupValues?.get(1)
            if (number != null) {
                val named = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
                return (number.toIntOrNull() ?: (named.indexOf(number) + 1))
                    .takeIf { it > 0 }?.let { VoiceCommand.Choose(it - 1) }
            }
            val target = words.removePrefix("switch to ").takeIf { it != words && it.isNotBlank() } ?: return null
            VoiceCommand.Switch(target.substringBefore(" on "), target.substringAfter(" on ", "").ifBlank { null })
        }
    }
}

internal fun voiceName(text: String): String {
    val cleaned = text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    return cleaned.replace(Regex("^laptop(?:two|too|to)$"), "laptop2")
}
