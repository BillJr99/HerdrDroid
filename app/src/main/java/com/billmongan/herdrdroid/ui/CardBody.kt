package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.herdr.QuestionParts

/** Code longer than this shows its first lines and "Show all". */
internal const val CARD_CODE_LINES = 8

/**
 * A question card's body (2.1.1): the question bold and first, paragraphs rewrapped, lists as
 * lists, and commands, paths and diffs in monospace blocks. "Show raw" puts back the parser's text
 * exactly as before, so nothing is hidden or reworded.
 */
@Composable
internal fun CardBody(parts: QuestionParts, screen: String?) {
    val blocks = remember(parts, screen) { CardFormat.format(parts, screen) }
    var raw by remember(parts) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (raw) parts.blocks.forEach { b ->
                    Text(b.text, fontFamily = Fonts.Mono, fontSize = 13.sp, lineHeight = 18.sp, color = Palette.Ink,
                        modifier = Modifier.testTag("card-raw"))
                } else blocks.forEachIndexed { i, b -> CardBlockView(b, first = i == 0) }
            }
        }
        TextButton(onClick = { raw = !raw }, modifier = Modifier.testTag("card-raw-toggle")) {
            Text(if (raw) "Show formatted" else "Show raw", fontSize = 12.5.sp)
        }
    }
}

@Composable
internal fun CardBlockView(b: CardBlock, first: Boolean) {
    when (b) {
        is CardBlock.Headline -> Text(withInlineCode(b.text), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp,
            color = Palette.Ink, modifier = Modifier.testTag("card-headline"))
        is CardBlock.Caption -> Text(b.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted,
            modifier = Modifier.padding(top = if (first) 0.dp else 2.dp))
        is CardBlock.Subhead -> Text(b.text, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, color = Palette.Ink)
        is CardBlock.Prose -> Text(withInlineCode(b.text), fontSize = 15.sp, lineHeight = 21.sp, color = Palette.Ink)
        is CardBlock.Labelled -> Text(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Palette.Muted)) { append(b.label + ": ") }
            append(withInlineCode(b.value))
        }, fontSize = 14.5.sp, lineHeight = 20.sp, color = Palette.Ink)
        is CardBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            b.items.forEach { item ->
                Row {
                    val ordered = item.mark.first().isDigit()
                    Text(if (ordered) item.mark else "•", fontSize = 15.sp, lineHeight = 21.sp, color = Palette.Muted,
                        modifier = Modifier.widthIn(min = if (ordered) 24.dp else 16.dp).padding(end = 4.dp))
                    Text(withInlineCode(item.text), fontSize = 15.sp, lineHeight = 21.sp, color = Palette.Ink)
                }
            }
        }
        is CardBlock.Code -> CardCode(b)
    }
}

private enum class DiffKind { ADD, DEL, HUNK, NONE }

private fun diffKind(line: String): DiffKind {
    val t = line.trim().replaceFirst(Regex("""^\d+\s+"""), "")
    return when {
        t.startsWith("@@") -> DiffKind.HUNK
        t.startsWith("+") && !t.startsWith("+++") -> DiffKind.ADD
        t.startsWith("-") && !t.startsWith("---") -> DiffKind.DEL
        else -> DiffKind.NONE
    }
}

/** A command, path or diff: monospace on the code background, sideways scroll, long ones folded. */
@Composable
internal fun CardCode(b: CardBlock.Code) {
    var all by remember(b) { mutableStateOf(false) }
    val long = b.lines.size > CARD_CODE_LINES + 2
    val shown = if (long && !all) b.lines.take(CARD_CODE_LINES) else b.lines
    // 2.2.0: long-press the box to copy all of it (every line, folded or not), as it shows. 2.2.2: the
    // lines are already clean (BoxCopy.cleanBlock in CardFormat), so the copy is exactly what shows.
    val copy = rememberCopier()
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.Code, border = BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.fillMaxWidth().testTag(if (b.diff) "card-diff" else "card-code")
            .onLongPress("Copy this box") { copy(b.lines.joinToString("\n"), "Copied box") }) {
        DisableSelection { Column {
            if (b.wrap) {
                // One line: a long path or command wraps (at "/" and spaces) instead of running off the side.
                Text(b.lines.joinToString("\n"), fontFamily = Fonts.Mono, fontSize = 13.5.sp, lineHeight = 19.sp,
                    color = Palette.Ink, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            } else {
                // One Text, so the script copies as a whole (long-press, 2.2.0); diff lines take their colours.
                val text = run {
                    buildAnnotatedString {
                        shown.forEachIndexed { i, line ->
                            if (i > 0) append('\n')
                            when (if (b.diff) diffKind(line) else DiffKind.NONE) {
                                DiffKind.ADD -> withStyle(SpanStyle(color = Palette.DiffAddInk, background = Palette.DiffAdd)) { append(line) }
                                DiffKind.DEL -> withStyle(SpanStyle(color = Palette.DiffDelInk, background = Palette.DiffDel)) { append(line) }
                                DiffKind.HUNK -> withStyle(SpanStyle(color = Palette.Blue)) { append(line) }
                                DiffKind.NONE -> append(line)
                            }
                        }
                    }
                }
                Text(text, fontFamily = Fonts.Mono, fontSize = 13.sp, lineHeight = 19.sp, color = Palette.Ink, softWrap = false,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp))
            }
            if (long) TextButton(onClick = { all = !all }, modifier = Modifier.testTag("card-code-more")) {
                Text(if (all) "Show fewer lines" else "Show all ${b.lines.size} lines", fontSize = 12.5.sp)
            }
        } }
    }
}

/**
 * An option's label for its button: its words, a key hint or tail in [quiet]. 2.2.2: shown through
 * [QuestionText.label] (no focus marker, gutter, trailing hint or repeated number [keys]); the
 * choice's own label, which its answer checks, is unchanged.
 */
internal fun optionLabel(label: String, quiet: Color, keys: String? = null): AnnotatedString {
    val (rawMain, rawTail) = CardFormat.labelParts(label)
    val main = QuestionText.label(rawMain, keys)
    val tail = if (rawTail.isEmpty()) "" else " " + QuestionText.label(rawTail)
    return buildAnnotatedString {
        append(withInlineCode(main))
        if (tail.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = quiet)) { append(tail) }
    }
}

/**
 * 2.2.2: prose with its inline code ("run `make test` first") in monospace on the code
 * background, without the backticks; text with no code is returned as it is.
 */
internal fun withInlineCode(text: String): AnnotatedString {
    val spans = QuestionText.codeSpans(text)
    if (spans.none { it.second }) return AnnotatedString(text)
    return buildAnnotatedString {
        spans.forEach { (t, code) ->
            if (code) withStyle(SpanStyle(fontFamily = Fonts.Mono, background = Palette.Code)) { append(t) } else append(t)
        }
    }
}

/**
 * 2.2.2: an option's description under its label: smaller and quieter prose, with any command or
 * fenced block in it as its own monospace box (long-press copies exactly the code).
 */
@Composable
internal fun OptionNote(note: String, color: Color) {
    val blocks = remember(note) { CardFormat.noteBlocks(note) }
    Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { b ->
            if (b is CardBlock.Code) CardCode(b)
            else Text(withInlineCode(CardFormat.plain(b)), fontSize = 13.sp, lineHeight = 18.sp, color = color,
                modifier = Modifier.testTag("choice-note"))
        }
    }
}
