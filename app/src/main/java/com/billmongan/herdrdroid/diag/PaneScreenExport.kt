package com.billmongan.herdrdroid.diag

import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Prompt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 2.2.7 (Bill: "at the very least the diagnostic logs could allow exporting a pane data"): one
 * pane's visible screen, as herdr returns it in plain text and in ANSI, with the agent, its status
 * and what the app's parser made of the screen (a question card and its fields, or none), as one
 * text file. It is built only after an explicit tap on Export pane screen, and goes only to the
 * phone's share sheet after a second tap on Share; nothing is uploaded or written on a computer.
 * Unlike the debug bundle, the screen is not redacted: it is what herdr shows, so the dialog warns
 * that it may hold private content.
 */
internal data class PaneScreenExport(val fileName: String, val text: String) {
    val bytes: ByteArray get() = text.toByteArray(Charsets.UTF_8)
    val size: Int get() = bytes.size
}

internal object PaneScreenExports {
    const val WARNING = "Pane text is not redacted and may contain private content. Check it before you share it."

    /** The file's name: the pane id and the time, with nothing a file name can't hold. */
    fun fileName(paneId: String, at: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
        return "herdrdroid-pane-" + paneId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40) + "-$stamp.txt"
    }

    fun build(
        appVersion: String,
        serverLabel: String,
        paneId: String,
        pane: Pane?,
        agentVersion: String?,
        plain: String,
        ansi: String,
        prompt: Prompt?,
        at: Long,
    ): PaneScreenExport {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(at))
        val text = buildString {
            appendLine("HerdrDroid $appVersion pane screen export")
            appendLine("time: $iso")
            appendLine("server: $serverLabel")
            appendLine("pane: $paneId" + (pane?.tabLabel?.takeIf { it.isNotBlank() }?.let { " (tab $it)" } ?: ""))
            appendLine("agent: ${pane?.agent ?: "none"} (${pane?.agentName ?: "?"}), version ${agentVersion ?: "unknown"}")
            appendLine("status: ${pane?.status ?: "unknown"}, scroll offset ${pane?.scrollOffset ?: "unknown"}, focused ${pane?.focused ?: "unknown"}")
            appendLine()
            appendLine("## Parse result")
            if (prompt == null) appendLine("detected prompt: none")
            else {
                appendLine("detected prompt: yes")
                appendLine("question: ${prompt.question}")
                appendLine("choices: " + prompt.choices.joinToString(" | ") { "${it.keys}=${it.label}" })
                appendLine("highlighted: ${prompt.selected ?: "none"}, multi-select: ${prompt.multi}, esc rejects: ${prompt.escRejects}, fallback card: ${prompt.region != null}")
                prompt.parts?.let { appendLine("layout: steps ${it.tabs.map { t -> t.title }}, heading ${it.heading}, command ${it.command}") }
            }
            appendLine()
            appendLine("## Visible screen, plain text (${plain.lines().size} lines)")
            appendLine(plain.trimEnd('\n'))
            appendLine("## End of plain text")
            appendLine()
            appendLine("## Visible screen, ANSI (${ansi.lines().size} lines; escape codes kept as herdr returned them)")
            appendLine(ansi.trimEnd('\n'))
            appendLine("## End of ANSI")
        }
        return PaneScreenExport(fileName(paneId, at), text)
    }

    /** The size as the dialog shows it. */
    fun sizeLabel(bytes: Int): String = when {
        bytes < 1024 -> "$bytes bytes"
        else -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    }
}
