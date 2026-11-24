package com.billmongan.herdrdroid.voice

import com.billmongan.herdrdroid.store.Server
import java.util.Locale

internal sealed interface VoiceCommand {
    data class Switch(val name: String, val server: String? = null) : VoiceCommand
    data class Choose(val index: Int) : VoiceCommand
    data object ListQuestions : VoiceCommand
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
    "Switch to Codex on Lab Box", "Switch to Lab Box", "Read the latest reply",
    "Repeat that", "List questions", "Read question", "Answer option one", "Confirm answer", "Cancel answer",
    "Send a message to Codex on Lab Box", "Dictate check the build", "Confirm message", "Cancel message",
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
        "list questions", "list pending questions", "list approvals", "list pending approvals" -> VoiceCommand.ListQuestions
        "read the pending question", "read the question", "read pending question", "read question",
        "read the pending approval", "read the approval", "read pending approval", "read approval" -> VoiceCommand.ReadQuestion
        "read the latest reply", "read latest reply", "read reply" -> VoiceCommand.ReadReply
        "repeat", "repeat that", "repeat the last message" -> VoiceCommand.Repeat
        "confirm message" -> VoiceCommand.ConfirmMessage
        "cancel message" -> VoiceCommand.CancelMessage
        "confirm answer", "confirm approval" -> VoiceCommand.Confirm
        "cancel answer", "cancel approval" -> VoiceCommand.Cancel
        else -> {
            val number = Regex("^(?:answer |choose |select )?option (.+)$").matchEntire(words)?.groupValues?.get(1)
            if (number != null) {
                val named = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
                    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
                return (number.toIntOrNull() ?: (named.indexOf(number) + 1))
                    .takeIf { it > 0 }?.let { VoiceCommand.Choose(it - 1) }
            }
            Regex("^send (?:a )?message to (.+)$").matchEntire(words)?.let {
                return paneTarget(it.groupValues[1])?.let { target -> VoiceCommand.Message(target) }
            }
            // Vosk commonly hears the initial consonants of “switch to” as “which to” or “which too.”
            val target = Regex("^(?:switch to|which to|which too) (.+)$").matchEntire(words)?.groupValues?.get(1)
                ?: return null
            paneTarget(target)
        }
    }
}

private val NUMBER_WORDS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
    .withIndex().associate { (i, w) -> w to i.toString() }

private fun voiceWords(text: String): List<String> =
    text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

/**
 * A name as it is matched by voice: lowercase letters and digits only, with each spoken number
 * word as its digits, so "Lab Box Two", "lab-box 2" and "labbox2" are one name.
 */
internal fun voiceName(text: String): String = voiceWords(text).joinToString("") { NUMBER_WORDS[it] ?: it }

/** The same name with a last "to" or "too" heard for "two", or null. */
private fun heardTwo(text: String): String? {
    val w = voiceWords(text)
    if (w.size < 2 || w.last() !in setOf("to", "too")) return null
    return voiceName(w.dropLast(1).joinToString(" ")) + "2"
}

/** The letters a recognizer is surest of: the first one, then the consonants (and the digits). */
private fun skeleton(name: String): String = name.take(1) + name.drop(1).filterNot { it in "aeiouy" }

private fun withNumber(name: String, base: String) = name.length > base.length && name.startsWith(base) && name.drop(base.length).all(Char::isDigit)

/**
 * The saved servers a spoken [spoken] name means, the first rule that matches any: the same
 * [voiceName]; a last "to"/"too" heard for "two"; the name with only a number left off ("Lab Box"
 * for a server named labbox2); then the same letters but vowels (a recognizer's "lob box" for "lab
 * box"), with or without the number, only when it means one server and has at least three
 * consonants. [loose] false: the first two rules only, for a phrase that may name a pane instead
 * (Grok's review: "Switch to Claude" must never open a server named "cloud"). A spoken name never
 * renames a saved server.
 */
internal fun voiceServersNamed(servers: List<Server>, spoken: String, loose: Boolean = true): List<Server> {
    val name = voiceName(spoken)
    if (name.isEmpty()) return emptyList()
    val two = heardTwo(spoken)
    val named = servers.map { it to voiceName(it.label) }
    fun first(vararg rules: (String) -> Boolean): List<Server> {
        for (rule in rules) named.filter { rule(it.second) }.map { it.first }.takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }
    val strict = first({ it == name }, { two != null && it == two })
    if (strict.isNotEmpty() || !loose) return strict
    first({ withNumber(it, name) }).takeIf { it.isNotEmpty() }?.let { return it }
    val heard = listOfNotNull(name, two).map(::skeleton).filter { h -> h.count { it.isLetter() && it !in "aeiouy" } >= 3 }
    return first({ s -> heard.any { h -> skeleton(s) == h || withNumber(skeleton(s), h) } }).takeIf { it.size == 1 }.orEmpty()
}
