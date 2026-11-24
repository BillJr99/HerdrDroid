package com.billmongan.herdrdroid.herdr

/**
 * The model shown on the third line of the pane screen's title bar (2.1.4), learned without typing
 * into the pane. Sources, best first:
 *  1. [known]: the model the app already holds for the pane (the agent's footer, read with each
 *     preview, or for Claude the latest "model" in its own session log), plus any effort.
 *  2. The open pane's screen: the agent's footer ([footerModel]), then Claude's start-up banner
 *     ("▝▜█████▛▘  Opus 5.5 · Claude Max").
 *  3. The terminal title herdr reports, only when it holds a model id ("claude-opus-5-5",
 *     "gpt-6.2-sol", "gemini-…", "grok-4.7", "mistral-…").
 * Null when none of them names a model: the line is left off rather than guessed.
 */
fun headerModel(agent: String?, known: String?, screen: String = "", terminalTitle: String = ""): String? {
    val found = known?.trim()?.takeIf { it.isNotEmpty() }
        ?: footerModel(agent, screen)
        ?: bannerModel(agent, screen)
        ?: titleModel(terminalTitle)
    return found?.let { tidyHeaderModel(agent, it) }?.takeIf { it.isNotBlank() }
}

/**
 * Grok's composer footer carries its approval mode after the model ("Grok 4.7 (high) ·
 * auto-review"); the title bar shows the model and its effort only ("Grok 4.7 (high)").
 */
fun tidyHeaderModel(agent: String?, model: String): String {
    val m = model.trim()
    return when (agent?.lowercase()) {
        "grok" -> m.replace(Regex("""\s+·\s+(?!(?:none|minimal|low|medium|high|xhigh|max)$)[A-Za-z][\w -]*$""", RegexOption.IGNORE_CASE), "").trim()
        else -> m
    }
}

private val BANNER = Regex("""^\s*[▐▛▜▌▝▘▗▖▙▟█]+\s+((?:Opus|Sonnet|Haiku|Fable)\s+\d+(?:\.\d+)?(?:\s+\(1M(?: context)?\))?)\s+·""")

/** Claude's start-up banner, while it is still on the screen: "▝▜█████▛▘  Opus 5.5 · Claude Max". */
fun bannerModel(agent: String?, screen: String): String? {
    if (agent?.lowercase() != "claude") return null
    return screen.lines().firstNotNullOfOrNull { BANNER.find(it)?.groupValues?.get(1)?.trim() }
}

private val TITLE_MODEL = Regex(
    """(?i)(?<![\w.-])(claude-(?:opus|sonnet|haiku|fable)-\d+(?:-\d{1,2})?(?:-\d{8})?(?:\[1m])?|gpt-\d+(?:\.\d+)?(?:-[a-z0-9]+)*|gemini-[a-z0-9][\w.-]*|grok-\d[\w.-]*|mistral-[a-z0-9][\w.-]*)(?![\w.-])""",
)

/**
 * A model id inside a terminal title. Agents mostly put the conversation's name there ("Select
 * argon model | codex", "user@labbox2: ~"), so only a real model id counts.
 */
fun titleModel(title: String): String? {
    val id = TITLE_MODEL.find(title)?.groupValues?.get(1) ?: return null
    return if (id.startsWith("claude-", ignoreCase = true)) prettyClaudeModel(id.lowercase()) else id
}
