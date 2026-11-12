package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** One block of a Markdown reply. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class ListItem(val ordered: Boolean, val number: String, val depth: Int, val text: String, val checked: Boolean?) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** A small Markdown parser: enough for what coding agents write, and forgiving of the rest. */
object Md {
    private val fence = Regex("""^\s{0,3}(```+|~~~+)\s*([\w+#.-]*).*$""")
    private val heading = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^(\s*)([-*+])\s+(?:\[([ xX])]\s+)?(.*)$""")
    private val ordered = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val rule = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(src: String): List<MdBlock> {
        val lines = src.replace("\r\n", "\n").split("\n")
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        fun flushPara() {
            if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim())
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.matchEntire(line)
            if (f != null) {
                flushPara()
                val marker = f.groupValues[1]
                val lang = f.groupValues[2]
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker.substring(0, 3))) {
                    body.append(lines[i]).append('\n')
                    i++
                }
                out += MdBlock.Code(lang, body.toString().trimEnd('\n'))
                i++
                continue
            }
            if (line.isBlank()) {
                flushPara(); i++; continue
            }
            heading.matchEntire(line)?.let {
                flushPara()
                out += MdBlock.Heading(it.groupValues[1].length, it.groupValues[2])
                i++
                return@let
            }?.also { } ?: run {
                if (rule.matches(line) && para.isEmpty()) {
                    out += MdBlock.Rule; i++; return@run
                }
                // A table: a row of cells followed by a separator row.
                if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                    flushPara()
                    val header = cells(line)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        rows += cells(lines[i]); i++
                    }
                    out += MdBlock.Table(header, rows)
                    return@run
                }
                val b = bullet.matchEntire(line)
                val o = ordered.matchEntire(line)
                when {
                    b != null -> {
                        flushPara()
                        val checked = b.groupValues[3].takeIf { it.isNotEmpty() }?.let { it != " " }
                        out += MdBlock.ListItem(false, "", b.groupValues[1].length / 2, continued(lines, i, b.groupValues[4]).also { i = it.second }.first, checked)
                    }
                    o != null -> {
                        flushPara()
                        out += MdBlock.ListItem(true, o.groupValues[2], o.groupValues[1].length / 2, continued(lines, i, o.groupValues[3]).also { i = it.second }.first, null)
                    }
                    line.trimStart().startsWith(">") -> {
                        flushPara()
                        val q = StringBuilder()
                        while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                            q.append(lines[i].trimStart().removePrefix(">").removePrefix(" ")).append('\n'); i++
                        }
                        out += MdBlock.Quote(q.toString().trim())
                        return@run
                    }
                    else -> {
                        if (para.isNotEmpty()) para.append(if (para.endsWith("  ")) "\n" else " ")
                        para.append(line.trim())
                    }
                }
                i++
            }
        }
        flushPara()
        return out
    }

    /** A list item's own text plus any lazily continued lines under it; returns the last line used. */
    private fun continued(lines: List<String>, start: Int, first: String): Pair<String, Int> {
        val sb = StringBuilder(first)
        var j = start
        while (j + 1 < lines.size) {
            val next = lines[j + 1]
            if (next.isBlank() || bullet.matches(next) || ordered.matches(next) || fence.matches(next) ||
                heading.matches(next) || next.trimStart().startsWith(">") || !next.startsWith("  ")) break
            sb.append(' ').append(next.trim())
            j++
        }
        return sb.toString() to j
    }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }

    private val inline = Regex(
        """(`+)(.+?)\1""" +                              // 1,2 code
            """|\*\*(.+?)\*\*|__(.+?)__""" +              // 3,4 bold
            """|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)""" + // 5 italic
            """|(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?!\w)""" +   // 6 italic
            """|~~(.+?)~~""" +                              // 7 strike
            """|\[([^\]]+)]\((\S+?)(?:\s+"[^"]*")?\)""" +  // 8,9 link
            """|(https?://[^\s)<>\]]+[^\s)<>\].,;:!?'"])"""  // 10 bare URL
    )

    /** Inline Markdown as styled text with tappable links. */
    fun inline(text: String, codeBg: Color, link: Color, mono: FontFamily): AnnotatedString = buildAnnotatedString {
        fun go(s: String) {
            var last = 0
            for (m in inline.findAll(s)) {
                append(s.substring(last, m.range.first))
                val g = m.groupValues
                when {
                    g[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = mono, background = codeBg, fontSize = 0.9.em())) { append(" " + g[2] + " ") }
                    g[3].isNotEmpty() || g[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { go(g[3] + g[4]) }
                    g[5].isNotEmpty() || g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { go(g[5] + g[6]) }
                    g[7].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { go(g[7]) }
                    g[8].isNotEmpty() -> withLink(LinkAnnotation.Url(g[9], TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(g[8]) }
                    g[10].isNotEmpty() -> withLink(LinkAnnotation.Url(g[10], TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(g[10]) }
                }
                last = m.range.last + 1
            }
            append(s.substring(last))
        }
        go(text)
    }

    private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)
}

/** Draws a Markdown reply. [size] is the reading size in sp. */
@Composable
fun MarkdownText(text: String, size: Float, family: FontFamily, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Md.parse(text) }
    val codeBg = Palette.InlineCode
    val link = Palette.Blue
    Column(modifier, verticalArrangement = Arrangement.spacedBy((size * 0.55f).dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    Md.inline(b.text, codeBg, link, Fonts.Mono),
                    fontFamily = Fonts.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size * when (b.level) { 1 -> 1.35f; 2 -> 1.22f; 3 -> 1.1f; else -> 1.0f }).sp,
                    lineHeight = (size * 1.6f).sp,
                    color = Palette.Ink,
                    modifier = Modifier.padding(top = (size * 0.3f).dp),
                )
                is MdBlock.Para -> Text(
                    Md.inline(b.text, codeBg, link, Fonts.Mono),
                    fontFamily = family, fontSize = size.sp, lineHeight = (size * 1.55f).sp, color = Palette.Ink,
                )
                is MdBlock.ListItem -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                    val mark = when {
                        b.checked == true -> "☑"
                        b.checked == false -> "☐"
                        b.ordered -> b.number + "."
                        b.depth > 0 -> "◦"
                        else -> "•"
                    }
                    Text(
                        mark, fontFamily = family, fontSize = size.sp, lineHeight = (size * 1.55f).sp,
                        color = Palette.Muted, modifier = Modifier.widthIn(min = (size * 1.5f).dp).padding(end = 4.dp),
                    )
                    Text(
                        Md.inline(b.text, codeBg, link, Fonts.Mono),
                        fontFamily = family, fontSize = size.sp, lineHeight = (size * 1.55f).sp, color = Palette.Ink,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Palette.Line, RoundedCornerShape(2.dp)))
                    Text(
                        Md.inline(b.text, codeBg, link, Fonts.Mono),
                        fontFamily = family, fontSize = size.sp, lineHeight = (size * 1.55f).sp, color = Palette.Muted,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is MdBlock.Code -> CodeBlock(b.code, b.lang, size)
                is MdBlock.Table -> MdTable(b, size, family)
                MdBlock.Rule -> HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

/** A fenced code block: language label, Copy, and lightly highlighted monospace that scrolls sideways. */
@Composable
fun CodeBlock(code: String, lang: String, size: Float) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    if (copied) androidx.compose.runtime.LaunchedEffect(Unit) { delay(1500); copied = false }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Palette.Code,
        border = BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(Palette.CodeHead).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(lang.ifBlank { "code" }, color = Palette.Muted, fontSize = 12.sp, fontFamily = Fonts.Mono)
                Spacer(Modifier.weight(1f))
                Text(
                    if (copied) "Copied" else "Copy",
                    color = Palette.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { clipboard.setText(AnnotatedString(code)); copied = true }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
            Text(
                remember(code, lang, Palette.dark) { Highlight.code(code, lang) },
                fontFamily = Fonts.Mono,
                fontSize = (size * 0.84f).sp,
                lineHeight = (size * 0.84f * 1.45f).sp,
                softWrap = false,
                color = Palette.Ink,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun MdTable(t: MdBlock.Table, size: Float, family: FontFamily) {
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Palette.Card,
        border = BorderStroke(1.dp, Palette.Line),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            val all = listOf(t.header) + t.rows
            all.forEachIndexed { r, row ->
                Row(Modifier.background(if (r == 0) Palette.CodeHead else Color.Transparent)) {
                    for (c in 0 until cols) {
                        Text(
                            Md.inline(row.getOrElse(c) { "" }, Palette.InlineCode, Palette.Blue, Fonts.Mono),
                            fontFamily = family,
                            fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = (size * 0.9f).sp,
                            lineHeight = (size * 1.35f).sp,
                            color = Palette.Ink,
                            modifier = Modifier.widthIn(min = 64.dp, max = 260.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
                if (r < all.size - 1) HorizontalDivider(color = Palette.Line)
            }
        }
    }
}

/** A generic, fast syntax highlighter: comments, strings, numbers and common keywords. */
object Highlight {
    private val keywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when", "for", "while", "in",
        "is", "null", "true", "false", "import", "package", "private", "public", "override", "suspend", "data",
        "def", "lambda", "None", "True", "False", "from", "as", "with", "try", "except", "finally", "raise", "pass",
        "function", "const", "let", "new", "this", "async", "await", "export", "default", "type", "enum", "struct",
        "impl", "pub", "fn", "mut", "match", "use", "mod", "self", "static", "void", "int", "string", "bool",
        "catch", "throw", "throws", "extends", "implements", "switch", "case", "break", "continue", "do", "elif",
        "then", "fi", "done", "esac", "echo", "local", "export", "sudo", "cd", "git", "and", "or", "not",
    )
    private val token = Regex(
        """(#[^\n]*|//[^\n]*|/\*[\s\S]*?\*/|--[^\n]*)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`[^`]*`)|(\b\d[\d_.xXa-fA-F]*\b)|(\b[A-Za-z_][\w]*\b)"""
    )

    fun code(src: String, lang: String): AnnotatedString = buildAnnotatedString {
        if (src.length > 40_000) {
            append(src); return@buildAnnotatedString
        }
        val diff = lang == "diff" || lang == "patch"
        if (diff) {
            src.lines().forEachIndexed { i, l ->
                if (i > 0) append('\n')
                val c = when {
                    l.startsWith("+") && !l.startsWith("+++") -> Palette.DiffAddInk
                    l.startsWith("-") && !l.startsWith("---") -> Palette.DiffDelInk
                    l.startsWith("@@") -> Palette.Blue
                    else -> null
                }
                if (c != null) withStyle(SpanStyle(color = c)) { append(l) } else append(l)
            }
            return@buildAnnotatedString
        }
        val hashComments = lang in setOf("py", "python", "sh", "bash", "shell", "zsh", "yaml", "yml", "toml", "rb", "ruby", "r", "")
        var last = 0
        for (m in token.findAll(src)) {
            append(src.substring(last, m.range.first))
            val s = m.value
            when {
                m.groups[1] != null && (s.startsWith("//") || s.startsWith("/*") || (s.startsWith("#") && hashComments) || (s.startsWith("--") && lang in setOf("sql", "lua", "hs"))) ->
                    withStyle(SpanStyle(color = Palette.CodeComment, fontStyle = FontStyle.Italic)) { append(s) }
                m.groups[2] != null -> withStyle(SpanStyle(color = Palette.CodeString)) { append(s) }
                m.groups[3] != null -> withStyle(SpanStyle(color = Palette.CodeNumber)) { append(s) }
                m.groups[4] != null && s in keywords -> withStyle(SpanStyle(color = Palette.CodeKeyword, fontWeight = FontWeight.SemiBold)) { append(s) }
                else -> append(s)
            }
            last = m.range.last + 1
        }
        append(src.substring(last))
    }
}
