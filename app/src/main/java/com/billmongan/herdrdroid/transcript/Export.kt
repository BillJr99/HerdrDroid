package com.billmongan.herdrdroid.transcript

/** The agent's reply after the last message you sent, joined from its text items. */
fun lastReply(items: List<Item>): String {
    val lastYou = items.indexOfLast { it is UserItem }
    return items.drop(lastYou + 1).filterIsInstance<TextItem>().joinToString("\n\n") { it.text.trim() }.trim()
}

/**
 * A conversation as a Markdown file: your messages as "You" sections, the agent's replies as
 * written, tool calls as one line each with their output folded into a code block, and
 * attachments named. Thinking is left out.
 */
fun toMarkdown(title: String, agent: String, items: List<Item>): String = buildString {
    append("# ").append(title.ifBlank { "Conversation with $agent" }).append("\n\n")
    var last: String? = null
    fun heading(who: String) {
        if (last != who) {
            append("## ").append(who).append("\n\n")
            last = who
        }
    }
    for (it in items) {
        when (it) {
            is UserItem -> { heading("You"); append(it.text.trim()).append("\n\n") }
            is TextItem -> { heading(agent); append(it.text.trim()).append("\n\n") }
            is ToolItem -> {
                heading(agent)
                append("- **").append(it.name).append("**")
                if (it.title.isNotBlank() && it.title != it.name) append(": ").append(it.title.lines().first().take(160))
                if (it.state == ToolState.ERROR) append(" (failed)")
                append("\n")
                it.out?.trim()?.takeIf { o -> o.isNotEmpty() }?.let { o ->
                    val fence = if (o.contains("```")) "````" else "```"
                    append("\n").append(fence).append("\n").append(o.take(4000)).append("\n").append(fence).append("\n")
                }
                append("\n")
            }
            is ImageItem -> { heading(if (it.user) "You" else agent); append("*Attachment: ").append(it.label).append("*\n\n") }
            is NoteItem -> { append("> ").append(it.text.trim().replace("\n", "\n> ")).append("\n\n"); last = null }
            is QuestionItem -> { append(QuestionLog.plain(it).lines().joinToString("\n") { l -> "> $l" }).append("\n\n"); last = null }
            is ThinkItem -> Unit
        }
    }
}.trimEnd() + "\n"
