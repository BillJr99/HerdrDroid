package com.billmongan.herdrdroid.voice

import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.grantsStandingPermission
import com.billmongan.herdrdroid.herdr.isCheckbox
import com.billmongan.herdrdroid.herdr.isTypedAnswerChoice
import com.billmongan.herdrdroid.herdr.menuKeys
import java.util.Locale

private val permissionContext = Regex("""\b(approv\w*|permissions?|authoriz\w*|allow|trust|run (?:this|the following) (?:command|tool)|make the following edits)\b""", RegexOption.IGNORE_CASE)
private val once = Regex("""(?:yes(?:, (?:proceed|allow|run it|single permission))?|(?:allow|approve)(?: this stage)? once|allow this time|proceed)""", RegexOption.IGNORE_CASE)
private val denial = Regex("""(?:no(?:, (?:quit|cancel|do not proceed))?|deny|reject|abort the entire command)""", RegexOption.IGNORE_CASE)
private val unsafeChoice = Regex("""\b(?:allow all|for (?:all|future)|until (?:restart|revoked)|for this (?:folder|workspace)|remember (?:my|this)|bypass|skip (?:all|future)|opt[ -](?:in|out)|(?:request|suggest) changes|tell .* what to do differently|feedback|edit answer)\b""", RegexOption.IGNORE_CASE)
private val keyHint = Regex("""\s*\((?:[a-z]|esc(?: [^)]+)?)\)$""", RegexOption.IGNORE_CASE)
private val clipped = Regex("""…|\.\.\.|\b(?:truncated|show full command)\b""", RegexOption.IGNORE_CASE)

/** Voice never falls back to typing a menu number or guessing invisible focus. */
internal fun voiceCardKeys(prompt: Prompt, choice: Choice): List<String>? {
    val parts = prompt.parts
    val tabs = parts?.tabs.orEmpty()
    if (prompt.terminalReview || prompt.multi || choice !in prompt.choices || tabs.count { !it.submit } > 1 ||
        tabs.any { it.current && it.submit } || prompt.choices.any { isCheckbox(it.label) } ||
        isTypedAnswerChoice(choice.label) || grantsStandingPermission(choice.label) ||
        choice.keys.startsWith("grok-privacy:") || choice.label.trim().lowercase(Locale.ROOT) in setOf("submit", "cancel")) return null

    val keys = menuKeys(prompt, choice) ?: return null
    val label = choice.label.trim().replace(keyHint, "").trimEnd('.')
    val visible = prompt.voiceContext()
    if (clipped.containsMatchIn(visible) || clipped.containsMatchIn(label)) return null
    val approval = prompt.approvalIdentity != null || parts?.command != null ||
        permissionContext.containsMatchIn(visible) || prompt.choices.any { grantsStandingPermission(it.label) }
    if (approval && !once.matches(label) && !denial.matches(label) &&
        !(keys == listOf("esc") && label.startsWith("No,", ignoreCase = true))) return null
    if (unsafeChoice.containsMatchIn(label) && keys != listOf("esc")) return null
    if (prompt.horizontal && keys != listOf("esc") && !prompt.numberKeys) return null
    return keys
}

internal fun Prompt.voiceContext(): String =
    (listOf(question) + parts?.blocks.orEmpty().map { it.text }).distinct().joinToString("\n")
