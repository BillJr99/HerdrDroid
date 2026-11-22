package com.billmongan.herdrdroid.ui

import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString

/**
 * Copying a chat message or one of its boxes (2.2.0, Bill: "let me long press on a chat bubble and
 * do a copy for text that is boxed in chat view"). Long-press a message for a menu with Copy (the
 * whole message) and Copy for each box in it; long-press a box to copy just that box. What is copied
 * is the text the box shows, without the terminal's gutters, wrap marks or colour codes.
 */
internal object BoxCopy {
    private val ANSI = Regex("""\u001B\[[0-?]*[ -/]*[@-~]|\u001B][^\u0007\u001B]*(?:\u0007|\u001B\\)|\u001B[@-Z\\-_]""")
    /** A terminal box's edge or wrap gutter at the start or end of a row. */
    private const val GUTTERS = "│┃┆┊║|"
    private const val LABEL_MAX = 40

    /**
     * A box's rows as the text it shows: colour codes gone; a gutter down the left (or right) of
     * every row taken off with the space beside it; a row Grok wrapped ("·" at its end) joined with
     * the next; the common indent and trailing spaces dropped, and blank rows at either end.
     */
    fun clean(lines: List<String>): String {
        var rows = lines.flatMap { ANSI.replace(it, "").split('\n') }
        val filled = { rs: List<String> -> rs.filter { it.isNotBlank() } }
        if (filled(rows).isNotEmpty() && filled(rows).all { it.trimStart().first() in GUTTERS }) {
            rows = rows.map { r -> if (r.isBlank()) "" else r.trimStart().drop(1).removePrefix(" ") }
        }
        if (filled(rows).isNotEmpty() && filled(rows).all { it.trimEnd().last() in GUTTERS }) {
            rows = rows.map { r -> if (r.isBlank()) "" else r.trimEnd().dropLast(1).trimEnd() }
        }
        val joined = mutableListOf<String>()
        var wraps = false
        for (r in rows) {
            val t = r.trimEnd()
            if (wraps && joined.isNotEmpty()) joined[joined.size - 1] = joined.last() + " " + t.trimStart()
            else joined += t
            wraps = joined.last().endsWith("·") && joined.last().length > 1
            if (wraps) joined[joined.size - 1] = joined.last().removeSuffix("·").trimEnd()
        }
        val body = joined.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        val cut = body.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
        return body.joinToString("\n") { if (it.isBlank()) "" else it.drop(cut) }
    }

    /** A box's label in the menu: its first line, shortened. */
    fun label(text: String): String {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return if (first.length <= LABEL_MAX) first else first.take(LABEL_MAX - 1).trimEnd() + "…"
    }

    /** The fenced code blocks of a Markdown message, exactly as written, each one a box. */
    fun markdownBoxes(text: String): List<String> =
        if (!text.contains("```") && !text.contains("~~~")) emptyList()
        else Md.parse(text).filterIsInstance<MdBlock.Code>().map { it.code }.filter { it.isNotBlank() }
}

/** Copies [text] to the clipboard and says so briefly; [what] is "Copied" or "Copied box". */
@Composable
internal fun rememberCopier(): (text: String, what: String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return remember(clipboard, context) {
        { text, what ->
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, what, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * A long press that runs [action] (2.2.0), with the haptic tick and the accessibility long-click
 * action. Taps pass through unchanged; a tap target inside (a button, a chip) keeps its own tap, and
 * a long press on an inner box with its own long press goes to that box only.
 */
internal fun Modifier.onLongPress(label: String, action: () -> Unit): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val latest by rememberUpdatedState(action)
    semantics { onLongClick(label) { latest(); true } }
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                latest()
            })
        }
}

/**
 * The long-press menu of a message (2.2.0): Copy for the whole message, Copy for each box in it
 * (labelled by the box's first line), and Select text when [onSelect] is given, which lets you pick
 * part of the message as before.
 */
@Composable
internal fun CopyMenu(open: Boolean, whole: String, boxes: List<String>, onDismiss: () -> Unit, onSelect: (() -> Unit)? = null) {
    val copy = rememberCopier()
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, modifier = Modifier.testTag("copy-menu")) {
        DropdownMenuItem(text = { Text("Copy") }, onClick = { copy(whole, "Copied"); onDismiss() })
        boxes.forEach { b ->
            DropdownMenuItem(text = { Text("Copy box: " + BoxCopy.label(b)) }, onClick = { copy(b, "Copied box"); onDismiss() })
        }
        if (onSelect != null) DropdownMenuItem(text = { Text("Select text") }, onClick = { onSelect(); onDismiss() })
    }
}
