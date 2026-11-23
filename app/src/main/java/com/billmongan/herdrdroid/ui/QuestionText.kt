package com.billmongan.herdrdroid.ui

/**
 * 2.2.2 (Bill: "Claude is still showing like the greater than sign that it shows as the little
 * arrow for choices and questions ... the rendering could be a little bit nicer on question text"):
 * the last cleanup of question text before it is shown, wherever it is shown (the question card's
 * body, each choice and its description, the question pills, the needs-you alert, the question
 * history in the chat and the voice card).
 *
 * Display only. The parsers' text, the card's identity, the history's signature and every key an
 * answer sends are left as they are; only what the eye reads changes. Markers are taken off by
 * where they sit (the start of a row, before its number or label, or the end of a row), never by
 * searching for a symbol inside the text, so a ">" that is content ("a > b", "->", "=>", ">= 2",
 * "> quoted") stays, and a harness that changes its glyph or its gutter still reads clean.
 */
object QuestionText {
    /** ANSI escapes, and the "[0m" or "[38;5;16m" left behind when the escape byte was lost. */
    /** Real escape sequences only: what code may lose (2.2.2 review: code stays byte-exact otherwise). */
    private val ESC = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]|\u001B[@-Z\\-_]""")
    private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]|\u001B[@-Z\\-_]|(?<![\w\]])\[(?:\d{1,3}(?:;\d{1,3}){0,6})?m""")
    private val CONTROL = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F⁠​]""")
    /**
     * A box gutter at the start of a row. A "|" counts only when the row also ends with one
     * ([PIPE_ROW]) or when every row of the text has it ([pipeGutters]): a pipe at a wrap edge
     * ("cat app.log |" over "grep ERROR") is content.
     */
    private val GUTTER = Regex("""^\s*[│┃┆┇╎╏║]\s?""")
    /** Box edges and bars left at the end of a row. */
    private val GUTTER_END = Regex("""\s+[│┃┆║]\s*$|[\s─━═╮╯┐┘╗╝]*[╮╯┐┘╗╝]\s*$""")
    private val PIPE_ROW = Regex("""^\s*\|\s(.*\S)\s\|\s*$""")
    /** A row that is only box drawing (a rule, a corner): it shows nothing. */
    private val BOX_ONLY = Regex("""^[\s─━═╌╍┄┅┈┉│┃┆║╭╮╰╯┌┐└┘├┤┬┴┼╔╗╚╝-]*$""")
    /** Corners and rules at the start of a row ("╭─ Title"). */
    private val BOX_START = Regex("""^[╭╰┌└├╔╚][─━═\s]*""")
    /**
     * A focus marker the screen drew at the start of a row: a terminal arrow with a space after it.
     * ">" counts only before a numbered or boxed option ("> 2. No", "> [ ] Docs"), never before words.
     */
    private val FOCUS = Regex("""^[❯›▶▸➤⟩→]\s+(?=\S)|^>\s+(?=(?:\[[ xX✓✔]]\s+)?\d{1,2}[.)]\s)""")
    /**
     * A focus marker in front of a choice label: a terminal arrow with a space after it, or ">" only
     * before the choice's number or box ("> 2. No"), never the label's own ">" ("> 100 MB").
     */
    private val LABEL_FOCUS = Regex("""^[❯›▶▸➤⟩→]\s+(?=\S)|^>\s+(?=(?:\[[ xX✓✔]]\s+)?\d{1,2}[.)]\s)""")
    private val LEAD_NUMBER = Regex("""^(\d{1,2})[.)]\s+(?=\S)""")
    /** A dialog's key hints, as one row or as a tail ("· Enter to select · Esc to cancel"). */
    private val HINT = Regex(
        """\b(?:enter|return)\s+to\s+(?:select|confirm|submit|send|continue|choose)|↵\s+to\s+(?:select|confirm|submit|send|continue|choose)|\besc(?:ape)?\s+to\s+(?:cancel|reject|go back|close|exit|dismiss|interrupt)|""" +
            """(?:↑/?↓|↑\s*↓|arrow keys|tab/arrow keys|j/k)\s+to\s+(?:navigate|move|select)|\btab\s+to\s+(?:amend|edit|switch|cycle)|\bctrl\+\w+\s+to\s+(?:edit|open|copy|expand|cancel|interrupt|exit)\b|\bspace\s+to\s+(?:toggle|select)""",
        RegexOption.IGNORE_CASE,
    )
    /**
     * Key hints at the end of a row, only as a segment of their own: the whole row, or after a
     * separator ("·", "•", "|", two spaces) or in parentheses. "Re-enter to confirm", "a data
     * center to send" and "use ctrl+c to stop it" are text and stay.
     */
    private val HINT_TAIL = Regex("""(?:^\s*|\s*[·•|]\s*|\s{2,}|\s*\(\s*)(?:${HINT.pattern})\b\s*\)?(?:\s*[·•|]\s*(?:${HINT.pattern})\b\s*\)?)*\s*$""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""[ \t  -  　]{2,}""")
    /** A list item at the start of a line: a bullet or a number with its stop. */
    private val ITEM = Regex("""^(?:[-*•·◦]|\d{1,3}[.)])\s+\S""")
    /** A line that reads as code or a command: kept as it is, never joined. */
    private val CODEISH = Regex("""^(?:\$\s|```|\s{4,}\S)|[{};]\s*$|^(?:[a-z][\w.-]*)(?:\s+-{1,2}[\w-]+)+""")
    /** Words that end a "pre-" style dangling hyphen on purpose ("pre- and post-"). */
    private val OPEN_HYPHEN = setOf("and", "or", "nor", "to", "vs", "versus")

    /** ANSI remnants and invisible control characters out (prose: a lost escape's "[0m" goes too). */
    fun noAnsi(s: String): String = CONTROL.replace(ANSI.replace(s, ""), "")

    /** For code: only real escape sequences and invisible control characters out; "[1m" in code stays. */
    fun escOnly(s: String): String = if (s.indexOf('\u001B') < 0 && !CONTROL.containsMatchIn(s)) s else CONTROL.replace(ESC.replace(s, ""), "")

    /**
     * One row of screen text for display: no ANSI remnants, no gutter, no focus marker at its start,
     * no box edge at its end, no trailing key hint, single spaces. A row of box drawing alone is empty.
     */
    fun line(raw: String): String {
        var l = noAnsi(raw).trimEnd()
        if (BOX_ONLY.matches(l)) return ""
        PIPE_ROW.matchEntire(l)?.let { l = it.groupValues[1] }
        var g = GUTTER.find(l)
        while (g != null && g.range.first == 0) { l = l.substring(g.range.last + 1); g = GUTTER.find(l) }
        l = BOX_START.replace(l, "")
        l = l.replace(GUTTER_END, "").trim()
        l = FOCUS.replace(l, "")
        l = l.replace(HINT_TAIL, "").trim()
        return SPACES.replace(l, " ").trim()
    }

    /**
     * Text for display that may span lines: each row cleaned by [line], then [reflow]ed, so
     * terminal-wrapped rows read as one paragraph and real paragraphs and list items stay apart.
     */
    fun text(raw: String): String = reflow(pipeGutters(raw.lines()).map(::line)).joinToString("\n")

    /** A "|" gutter down every non-blank row (left, right or both) taken off, as [BoxCopy.clean] does. */
    fun pipeGutters(rows: List<String>): List<String> {
        var out = rows
        val filled = { rs: List<String> -> rs.filter { it.isNotBlank() } }
        if (filled(out).size >= 2 && filled(out).all { it.trimStart().startsWith("| ") || it.trim() == "|" })
            out = out.map { if (it.isBlank()) "" else it.trimStart().drop(1).removePrefix(" ") }
        if (filled(out).size >= 2 && filled(out).all { it.trimEnd().endsWith(" |") })
            out = out.map { if (it.isBlank()) "" else it.trimEnd().dropLast(1).trimEnd() }
        return out
    }

    /**
     * Joins terminal-wrapped lines into paragraphs. A blank line is a paragraph break; a list item
     * and a line that reads as code start a line of their own, and code is never joined into
     * prose; a word split at a hyphen ("well-" over "known") joins without a space.
     */
    fun reflow(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var open = false // whether the last output line is prose that a wrapped line may go on
        for (raw in lines) {
            val t = raw.trimEnd()
            if (t.isBlank()) {
                if (out.isNotEmpty() && out.last().isNotEmpty()) out += ""
                open = false; continue
            }
            val code = CODEISH.containsMatchIn(t) || isCodeLine(t)
            val item = ITEM.containsMatchIn(t.trimStart())
            if (open && !code && !item && !realBreak(out.last(), t.trim())) {
                out[out.size - 1] = join(out.last(), t.trim())
                continue
            }
            out += if (code) t else t.trim()
            open = !code
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out
    }

    /**
     * Whether [next] starts a line of its own after [prev] rather than going on from a wrap: [prev]
     * ends a sentence or a "Label: value" and is short (a row that ended well before the width), or
     * [next] starts a new capitalised sentence after one that ended.
     */
    private fun realBreak(prev: String, next: String): Boolean {
        val ended = prev.isNotEmpty() && prev.last() in ".?!:"
        if (ended && prev.length <= SHORT_ROW) return true
        if (LABEL_ROW.containsMatchIn(prev) && prev.length <= SHORT_ROW && next.firstOrNull()?.isUpperCase() == true) return true
        return ended && next.firstOrNull()?.isUpperCase() == true
    }
    private const val SHORT_ROW = 40
    private val LABEL_ROW = Regex("""^[A-Z][A-Za-z ]{0,24}:\s+\S""")

    /** Two wrapped pieces of one paragraph: a dangling hyphen joins without a space. */
    fun join(a: String, b: String): String {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val word = b.takeWhile { it.isLetter() }
        if (a.length >= 2 && a.last() == '-' && a[a.length - 2].isLetter() && word.isNotEmpty() && word.first().isLowerCase() &&
            word.lowercase() !in OPEN_HYPHEN) return a + b
        return "$a $b"
    }

    /**
     * Prose that was already joined into one line (a parser's paragraph): cleaned as one row,
     * with any hyphen left dangling by a join ("work- flow") closed up.
     */
    fun prose(raw: String): String = if (raw.contains('\n')) text(raw) else closeHyphens(line(raw))

    private fun closeHyphens(l: String): String = DANGLING.replace(l) { m ->
        if (m.groupValues[2].lowercase() in OPEN_HYPHEN) m.value else m.groupValues[1] + "-" + m.groupValues[2]
    }
    private val DANGLING = Regex("""(\p{L}{2,})- (\p{Ll}+)""")

    /**
     * A choice's label for its button: its focus marker, gutter and trailing hint off, and the
     * number the card already shows ("1. Yes" on choice 1) not repeated. [keys] is the choice's key.
     */
    fun label(raw: String, keys: String? = null): String {
        var l = noAnsi(raw).trim()
        PIPE_ROW.matchEntire(l)?.let { l = it.groupValues[1] }
        var g = GUTTER.find(l)
        while (g != null && g.range.first == 0) { l = l.substring(g.range.last + 1).trimStart(); g = GUTTER.find(l) }
        l = l.replace(GUTTER_END, "").trim()
        l = LABEL_FOCUS.replace(l, "")
        if (keys != null) {
            val n = LEAD_NUMBER.find(l)
            if (n != null && n.groupValues[1] == keys) l = l.substring(n.range.last + 1)
        }
        l = l.replace(HINT_TAIL, "").trim()
        return SPACES.replace(l, " ").trim().ifEmpty { raw.trim() }
    }

    /** A choice's description (the line under its label): cleaned and joined into one paragraph. */
    fun note(raw: String): String {
        // 2.2.2: a command or fenced block in a description stays on lines of its own, fenced, so
        // [CardFormat.noteBlocks] shows it as a code block; the prose around it is one paragraph each.
        val segs = segments(raw)
        if (segs.none { it.code }) return reflow(pipeGutters(raw.lines()).map(::line)).filter { it.isNotEmpty() }.joinToString("\n") { closeHyphens(it) }
        return segs.joinToString("\n") { if (it.code) "```\n" + (if (it.fenced) it.text else BoxCopy.cleanBlock(it.text.lines()).joinToString("\n")) + "\n```" else it.text }
    }

    /** A step's name in Claude's question pills. */
    fun title(raw: String): String = label(raw).trim('☐', '☒', '✔', '✓', '☑', ' ')

    /**
     * The needs-you alert's text: each line cleaned, wrapped lines joined, a command kept on lines
     * of its own (the alert keeps real newlines), and no blank lines at its ends. A line of [code]
     * (the command the question asks about) is kept exactly, on a line of its own.
     */
    fun alert(raw: String, code: Collection<String> = emptyList()): String {
        val keep = code.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val out = mutableListOf<String>()
        val prose = mutableListOf<String>()
        fun flush() { out += reflow(prose); prose.clear() }
        var inCode = false
        for (l in pipeGutters(noAnsi(raw).lines())) {
            // 2.2.2 review: the row a command wrapped onto stays with the command ("origin/main").
            val how = if (inCode && prose.isEmpty() && l.isNotBlank() && l.trim() !in keep) codeRow(out.last(), l, false) else null
            when {
                how == CodeRow.JOIN -> out[out.size - 1] = joinWrapped(out.last(), l)
                how == CodeRow.NEW_LINE -> out += l.trimEnd()
                l.trim() in keep || CODEISH.containsMatchIn(l) || isCodeLine(l) -> { flush(); out += l.trimEnd(); inCode = true }
                else -> { prose += line(l); inCode = false }
            }
        }
        flush()
        return out.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
    }

    // ---- Commands and code in question text (2.2.2) ----------------------------------------------

    /** A piece of question text: prose, or code to show exactly, in monospace, on lines of its own. */
    data class Segment(val text: String, val code: Boolean, val fenced: Boolean = false)

    /** Program names a command row starts with (after "sudo" or a "./" path). */
    private val COMMANDS = setOf(
        "git", "gh", "npm", "npx", "yarn", "pnpm", "node", "deno", "bun", "python", "python3", "pip", "pip3", "uv", "pytest",
        "docker", "kubectl", "helm", "make", "cmake", "cargo", "rustc", "go", "gradle", "gradlew", "mvn", "java", "javac",
        "kotlinc", "ls", "cd", "cat", "grep", "rg", "find", "sed", "awk", "head", "tail", "sort", "uniq", "wc", "xargs",
        "curl", "wget", "ssh", "scp", "rsync", "rm", "mv", "cp", "mkdir", "rmdir", "touch", "chmod", "chown", "ln", "tar",
        "zip", "unzip", "echo", "printf", "export", "env", "bash", "sh", "zsh", "source", "brew", "apt", "apt-get", "dnf",
        "systemctl", "journalctl", "herdr", "hc", "jq", "yq", "tmux", "adb", "terraform", "ansible", "psql", "mysql", "sqlite3",
    )
    /**
     * Real shell syntax after the program (2.2.2 review: a path or a "/" alone is not enough, so
     * "make the same change in app/src/test" stays prose): a flag, a pipe, a redirect, "&&", "||",
     * ";", "$(", an assignment, or a quoted argument.
     */
    private val SHELL_SYNTAX = Regex("""\s-{1,2}[A-Za-z][\w-]*|\s\|\s|\s\|$|\s\d?>>?\s|\s<\s|&&|\|\||;\s|;$|(?:^|\s)[A-Za-z_][\w.-]*=\S|\$\(|\s'[^']*'|\s"[^"]*"""")
    private val WORDS = Regex("""\s+""")
    private val STRONG = Regex("""\s-{1,2}[A-Za-z]|\||&&""")
    /** A fence's language tag, only a known one, so "```npm run build```" keeps its "npm". */
    private const val FENCE_TAG = """(?:(?:json|jsonc|yaml|yml|toml|sh|bash|zsh|shell|console|text|diff|kotlin|python|py|js|ts)\s)?"""
    private val ONE_ROW_FENCE = Regex("""^\s*```$FENCE_TAG(.+?)```\s*$""")
    private val INLINE_FENCE = Regex("""```$FENCE_TAG(.+?)```""")
    private val LEAD_IN = Regex("""^(.*?\S:)\s+(\S.*)$""")
    /** A row that carries a command on to the next one ("\\", "|", "&&", "||" at its end). */
    private val CARRIES_ON = Regex("""(?:\\|\||&&|\|\|)\s*$""")
    private val CARRIED = Regex("""^\s*(?:\||&&|\|\|)""")
    private val PATH_ONLY = Regex("""^(?:/|~/|\./|\.\./)[\w./~@%+=:-]*[\w/]$""")
    private val JSON_LINE = Regex("""^[\[{].*[:,"].*[\]}],?$""")

    /**
     * Whether one line the agent wrote reads as a command or code (conservative: when unsure it is
     * prose). A "$ " prompt; a line that starts with a known program and has shell syntax after it
     * ("git -C /x log --oneline", "make test | tee out", not "git is where the history lives."); a
     * JSON line. A sentence that only mentions "a > b" or ends with a colon stays prose.
     */
    /** The code of a fence opened and closed on one row ("```npm run build```"), or null. */
    fun oneRowFence(row: String): String? = ONE_ROW_FENCE.matchEntire(row)?.groupValues?.get(1)?.trim()

    /** A line that is code wherever it sits, even inside a sentence: a "$ " prompt or a JSON line. */
    fun isPlainCode(raw: String): Boolean {
        val t = raw.trim()
        return (t.startsWith("$ ") && t.length > 2) || (JSON_LINE.matches(t) && t.contains('"'))
    }

    fun isCodeLine(raw: String): Boolean {
        val t = raw.trim()
        if (t.length < 3) return false
        if (t.startsWith("$ ") && t.length > 2) return true
        if (JSON_LINE.matches(t) && t.contains('"')) return true
        val words = t.split(WORDS)
        // A path on its own ("/home/user/app.py", "~/proj/build.gradle", "./run.sh").
        if (words.size == 1 && PATH_ONLY.matches(t)) return true
        var first = words.first()
        var at = 0
        if (first == "sudo" && words.size > 1) { at = 1; first = words[1] }
        val program = first.removePrefix("./").substringAfterLast('/')
        if (program !in COMMANDS || first.first().isUpperCase()) return false
        val rest = words.drop(at + 1).joinToString(" ")
        if (rest.isEmpty()) return false
        val syntax = SHELL_SYNTAX.containsMatchIn(" $rest")
        // A sentence that starts with a program's name ("make sure it runs.") is prose.
        if (!syntax) return false
        val sentence = t.last() in ".?!" && !STRONG.containsMatchIn(t)
        return !sentence
    }

    /**
     * Whether [row] goes on the command [prev] started, as the terminal wrapped it (2.2.2 review: a
     * box that held only a command's first row copied a command that runs differently): [prev] ends
     * in "\", "|", "&&" or "||", or [row] starts with "|" or "&&", or [row] is a short piece with no
     * sentence about it ("origin/main", "--coverage", "dist/").
     */
    fun continuesCode(prev: String, row: String): Boolean {
        val t = row.trim()
        if (t.isEmpty()) return false
        if (CARRIES_ON.containsMatchIn(prev) || CARRIED.containsMatchIn(t)) return true
        // More flags ("-o install.sh --retry 3", "--since=2024-01-01 -- path/to/file").
        if (t.startsWith("-")) return true
        if (t.first().isUpperCase() || t.last() in ".?!:") return false
        val words = t.split(WORDS)
        // One token only when it is a path, a ref or an assignment ("origin/main", "dist/", "A=1"),
        // never a bare word: "git status --short" over "tonight" is a command and then prose.
        if (words.size == 1) return t.contains('/') || t.contains('=')
        // Several words only when the row has shell syntax of its own, however long it is.
        return SHELL_SYNTAX.containsMatchIn(" $t")
    }

    /**
     * How [row] goes on a code block whose last row is [prev] (2.2.2 review): joined to it when the
     * terminal wrapped the command ([continuesCode]), as a row of its own after a backslash, when it
     * is a command of its own, or when it is deeper and does not continue, or null when it does not
     * go on the block.
     */
    fun codeRow(prev: String, row: String, indented: Boolean): CodeRow? = when {
        prev.trimEnd().endsWith("\\") -> CodeRow.NEW_LINE
        // A command of its own ("$ make test", "git -C /x status") is its own row, at any indent.
        isCodeLine(row) && !row.trimStart().startsWith("-") -> CodeRow.NEW_LINE
        continuesCode(prev, row) -> CodeRow.JOIN
        indented -> CodeRow.NEW_LINE
        else -> null
    }
    enum class CodeRow { JOIN, NEW_LINE }

    /** A command row and the row the terminal wrapped it onto, as one row. */
    fun joinWrapped(prev: String, row: String): String = prev.trimEnd() + " " + row.trim()

    /**
     * [raw] split into prose and code (2.2.2): a fenced block (```...```, one row or many; an open
     * fence ends with the text), a line [isCodeLine] calls code with the rows it wrapped onto, and on
     * one joined line a command after a lead-in ("Run this: git log --oneline"). A row that goes on a
     * sentence ("Should I also" over "make the same change") is never code. Code keeps every
     * character but real escape sequences; prose is cleaned and reflowed as [prose] and [text] do.
     */
    fun segments(raw: String): List<Segment> {
        val src = escOnly(raw)
        val out = mutableListOf<Segment>()
        fun add(piece: String, code: Boolean, fenced: Boolean = false) {
            if (code) { if (piece.isNotBlank()) out += Segment(piece.trimEnd('\n'), true, fenced); return }
            val p = if (piece.contains('\n')) QuestionText.text(piece) else QuestionText.prose(piece)
            if (p.isNotEmpty()) out += Segment(p, false)
        }
        if (!src.contains('\n')) {
            // A fenced block on one joined line: "Use this ```{"a": 1}``` then go on".
            val fence = INLINE_FENCE.find(src)
            if (fence != null) {
                add(src.substring(0, fence.range.first), false); add(fence.groupValues[1].trim(), true, fenced = true); add(src.substring(fence.range.last + 1), false)
                return out
            }
            if (isCodeLine(src)) { add(src.trim(), true); return out }
            val lead = LEAD_IN.find(src)
            if (lead != null && isCodeLine(lead.groupValues[2]) && !lead.groupValues[1].contains("://")) {
                add(lead.groupValues[1], false); add(lead.groupValues[2].trim(), true); return out
            }
            add(src, false); return out
        }
        val prose = mutableListOf<String>()
        val code = mutableListOf<String>()
        var fenced = false
        fun flushProse() { if (prose.isNotEmpty()) add(prose.joinToString("\n"), false); prose.clear() }
        fun flushCode(wasFenced: Boolean = false) {
            if (code.isEmpty()) return
            val indent = if (wasFenced) 0 else code.filter { it.isNotBlank() }.minOfOrNull { l -> l.indexOfFirst { !it.isWhitespace() } } ?: 0
            add(code.joinToString("\n") { if (wasFenced) it else if (it.length >= indent) it.substring(indent).trimEnd() else it.trim() }, true, wasFenced)
            code.clear()
        }
        for (l in src.lines()) {
            val oneRow = if (!fenced) ONE_ROW_FENCE.matchEntire(l) else null
            if (oneRow != null) { flushProse(); flushCode(); code += oneRow.groupValues[1].trim(); flushCode(true); continue }
            if (l.trim().startsWith("```")) {
                if (fenced) { flushCode(true); fenced = false } else { flushProse(); flushCode(); fenced = true }
                continue
            }
            if (fenced) { code += l; continue }
            // A row the terminal wrapped a command onto joins it, so the box copies one whole command;
            // after a backslash, or a deeper command of its own, the row stays a row of its own.
            val how = if (code.isNotEmpty() && l.isNotBlank()) codeRow(code.last(), l, l.takeWhile { it == ' ' }.length >= 4) else null
            if (how == CodeRow.NEW_LINE) { code += l; continue }
            if (how == CodeRow.JOIN) { code[code.size - 1] = joinWrapped(code.last(), l); continue }
            // A row that carries on an unfinished sentence is prose, whatever word it starts with.
            val midSentence = prose.isNotEmpty() && prose.last().trim().let { it.isNotEmpty() && it.last() !in ".?!:" }
            if (isCodeLine(l) && (!midSentence || isPlainCode(l))) { flushProse(); code += l; continue }
            flushCode(); prose += l
        }
        flushProse(); flushCode(fenced)
        return out
    }

    /**
     * A row of Claude's review step as the parser joined it ("Which rule should I add?: Exact path"):
     * the question and the answer given, or null when [detail] is not such a row.
     */
    fun reviewAnswer(detail: String): Pair<String, String>? =
        REVIEW_ROW.matchEntire(detail.trim())?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }
    private val REVIEW_ROW = Regex("""^(.{3,}\?)\s*:\s+(\S.*)$""")

    /**
     * Inline code in prose ("run `make test` first"): the text in pieces, each marked when it is
     * code, so the card can show it monospace. An unmatched backtick is plain text.
     */
    fun codeSpans(s: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        var i = 0
        while (i < s.length) {
            val open = s.indexOf('`', i)
            val close = if (open >= 0) s.indexOf('`', open + 1) else -1
            if (open < 0 || close < 0 || close == open + 1) { out += s.substring(i) to false; break }
            if (open > i) out += s.substring(i, open) to false
            out += s.substring(open + 1, close) to true
            i = close + 1
        }
        return out.filter { it.first.isNotEmpty() }
    }
}
