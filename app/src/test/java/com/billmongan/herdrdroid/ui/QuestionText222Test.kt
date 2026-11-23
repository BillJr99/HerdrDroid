package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.QBlock
import com.billmongan.herdrdroid.herdr.QKind
import com.billmongan.herdrdroid.herdr.QuestionParts
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.herdr.shownChoiceLabel
import com.billmongan.herdrdroid.notify.alertPlan
import com.billmongan.herdrdroid.notify.alertBody
import com.billmongan.herdrdroid.transcript.QuestionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.2.2 (Bill: "Claude is still showing like the greater than sign that it shows as the little
 * arrow for choices and questions ... the rendering could be a little bit nicer on question text"):
 * question text as it is shown, everywhere it is shown.
 */
class QuestionText222Test {
    // ---- One row ------------------------------------------------------------------------------

    @Test fun aFocusMarkerAtTheStartOfARowGoesWhateverItsGlyph() {
        for (m in listOf("❯", "›", "▶", "➤", "⟩", "→", "▸")) assertEquals("Leave it", QuestionText.label("$m Leave it"))
        assertEquals("2. No", QuestionText.line("> 2. No"))
        assertEquals("No", QuestionText.label("> No"))
    }

    @Test fun aGreaterThanSignThatIsContentStays() {
        for (s in listOf("Keep a > b as it is", "Pipe it with cmd -> out", "Map x => y", "Only when n >= 2",
                "Redirect: echo hi > out.txt", "Shift with a >> b")) {
            assertEquals(s, QuestionText.line(s))
            assertEquals(s, QuestionText.label(s))
            assertEquals(s, QuestionText.prose(s))
        }
        // A quote mark in prose is not a focus marker: ">" counts only before a numbered option.
        assertEquals("> quoted words stay", QuestionText.line("> quoted words stay"))
    }

    @Test fun guttersAndBoxEdgesGo() {
        assertEquals("The sender is on a stale hold.", QuestionText.line("│ The sender is on a stale hold."))
        assertEquals("Inside a box", QuestionText.line("┃ │ Inside a box │"))
        assertEquals("Pipe row", QuestionText.line("| Pipe row |"))
        assertEquals("Title", QuestionText.line("╭─ Title ─────╮"))
        assertEquals("", QuestionText.line("──────────────"))
        assertEquals("", QuestionText.line("╰──────────────╯"))
        // A bar inside the text is content.
        assertEquals("cat a | grep b", QuestionText.line("cat a | grep b"))
    }

    @Test fun ansiRemnantsAndControlCharactersGo() {
        assertEquals("Allow it?", QuestionText.line("\u001B[1mAllow it?\u001B[0m"))
        assertEquals("Allow it?", QuestionText.line("[38;5;16mAllow it?[39m"))
        assertEquals("Allow⁠ it?".replace("⁠", ""), QuestionText.line("Allow⁠ it?"))
        // Brackets that are content stay.
        assertEquals("Use list[0] and [x] here", QuestionText.line("Use list[0] and [x] here"))
    }

    @Test fun trailingKeyHintsGoAndKeyNamesInLabelsStay() {
        assertEquals("Pick one", QuestionText.line("Pick one · Enter to select · ↑/↓ to navigate · Esc to cancel"))
        assertEquals("Pick one", QuestionText.line("Pick one (Enter to select)"))
        assertEquals("", QuestionText.line("Enter to select · Tab/Arrow keys to navigate · Esc to cancel"))
        assertEquals("No, and tell Claude what to do differently (esc)", QuestionText.label("No, and tell Claude what to do differently (esc)"))
    }

    @Test fun repeatedSpacesCollapse() {
        assertEquals("Copy the whole state folder", QuestionText.line("Copy   the whole  state  folder"))
    }

    @Test fun aNumberTheCardAlreadyShowsIsNotRepeated() {
        assertEquals("Yes", QuestionText.label("1. Yes", "1"))
        assertEquals("Yes", QuestionText.label("❯ 1. Yes", "1"))
        // Another number is the label's own text.
        assertEquals("2. Second pass", QuestionText.label("2. Second pass", "1"))
        assertEquals("3 retries", QuestionText.label("3 retries", "3"))
    }

    @Test fun stepTitlesLoseTheirBoxes() {
        assertEquals("Backup", QuestionText.title("☐ Backup"))
        assertEquals("Submit", QuestionText.title("✔ Submit"))
    }

    // ---- Reflow -------------------------------------------------------------------------------

    @Test fun softWrappedLinesJoinIntoOneParagraph() {
        val got = QuestionText.reflow(listOf("The sender is on a stale hold for one of the", "helpers, which is also waiting."))
        assertEquals(listOf("The sender is on a stale hold for one of the helpers, which is also waiting."), got)
    }

    @Test fun paragraphBreaksAndListItemsStay() {
        val got = QuestionText.reflow(listOf("First paragraph goes", "on here.", "", "Second one.", "- one item", "- two items", "1. numbered", "2) also"))
        assertEquals(listOf("First paragraph goes on here.", "", "Second one.", "- one item", "- two items", "1. numbered", "2) also"), got)
    }

    @Test fun codeIsNeverJoinedOrChanged() {
        val got = QuestionText.reflow(listOf("Run this:", "\$ make  test  -j4", "    indented();", "then go on."))
        assertEquals(listOf("Run this:", "\$ make  test  -j4", "    indented();", "then go on."), got)
    }

    @Test fun aWordSplitAtAHyphenJoinsWithoutASpace() {
        assertEquals("a well-known path", QuestionText.join("a well-", "known path"))
        assertEquals("pre- and post-checks", QuestionText.join("pre-", "and post-checks"))
        assertEquals("a - b", QuestionText.join("a -", "b"))
        assertEquals("the work-flow step", QuestionText.prose("the work- flow step"))
        assertEquals("pre- and post-checks", QuestionText.prose("pre- and post-checks"))
    }

    @Test fun surroundingBlankLinesGo() {
        assertEquals("Only this.", QuestionText.text("\n\n│ Only this.\n\n"))
    }

    @Test fun inlineCodeIsSplitOutWithoutItsBackticks() {
        assertEquals(listOf("Run " to false, "make test" to true, " first" to false), QuestionText.codeSpans("Run `make test` first"))
        assertEquals(listOf("an odd ` tick" to false), QuestionText.codeSpans("an odd ` tick"))
        assertEquals("Run make test first", withInlineCode("Run `make test` first").text)
    }

    @Test fun theAlertKeepsACommandOnItsOwnLines() {
        val got = QuestionText.alert("│ Confirm the line:\ngit -C /x log --oneline\n")
        assertEquals("Confirm the line:\ngit -C /x log --oneline", got)
        // The command the question asks about stays exactly as it is, spaces and all.
        assertEquals("Run it?\ngo  test ./...", QuestionText.alert("Run it?\ngo  test ./...", listOf("go  test ./...")))
    }

    // ---- The card body --------------------------------------------------------------------------

    @Test fun theCardBodyIsCleanedAndAParagraphRepeatingTheQuestionGoes() {
        val parts = QuestionParts(emptyList(), listOf(
            QBlock(QKind.HEADING, "❯ Which  branch should I use?"),
            QBlock(QKind.TEXT, "Which branch should I use?"),
            QBlock(QKind.TEXT, "│ The work- flow keeps a > b as it is."),
            QBlock(QKind.COMMAND, "git  log  --oneline"),
        ))
        val blocks = CardFormat.format(parts, null).map(CardFormat::plain)
        assertEquals(listOf("Which branch should I use?", "The work-flow keeps a > b as it is.", "git  log  --oneline"), blocks)
    }

    @Test fun optionNotesReadAsOneCleanParagraph() {
        val screen = """
            │ Pick one?

            ❯ 1. Close it
                 Mark it resolved now, the well-
                 known way.
              2. Keep it open
            Enter to select · Esc to cancel
        """.trimIndent()
        val notes = CardFormat.optionNotes(screen, listOf(Choice("Close it", "1", false), Choice("Keep it open", "2", false)))
        assertEquals("Mark it resolved now, the well-known way.", notes["1"])
    }

    // ---- Commands and code inside question text ----------------------------------------------

    private fun layout(vararg blocks: QBlock, screen: String? = null) =
        CardFormat.format(QuestionParts(emptyList(), blocks.toList()), screen)

    @Test fun aMultiLineCommandInTheTextIsItsOwnBlockWithItsLinesExact() {
        val got = layout(QBlock(QKind.HEADING, "Should I run the cleanup now?"),
            QBlock(QKind.TEXT, "Run these first:\n\$ cd /work/repo\n\$ make test | tee out.log\nThen I go on."))
        assertEquals(listOf(CardBlock.Headline("Should I run the cleanup now?"), CardBlock.Prose("Run these first:"),
            CardBlock.Code(listOf("\$ cd /work/repo", "\$ make test | tee out.log")), CardBlock.Prose("Then I go on.")), got)
    }

    @Test fun commandRowsOnTheScreenBecomeOneBlock() {
        val screen = """
            │ Should I run the cleanup now?
            │ It runs:
            │   git -C /work/repo clean -n
            │   git -C /work/repo status --short
        """.trimIndent()
        val got = layout(QBlock(QKind.HEADING, "Should I run the cleanup now?"),
            QBlock(QKind.TEXT, "It runs: git -C /work/repo clean -n git -C /work/repo status --short"), screen = screen)
        assertEquals(CardBlock.Code(listOf("git -C /work/repo clean -n", "git -C /work/repo status --short")), got.last())
        assertTrue(got.toString(), got.none { it is CardBlock.Prose && it.text.contains("git -C") })
    }

    @Test fun aCommandAfterALeadInOnOneLineSplitsOut() {
        val got = layout(QBlock(QKind.HEADING, "Go ahead?"), QBlock(QKind.TEXT, "I will run: git -C /x log --oneline | head -5"))
        assertEquals(listOf(CardBlock.Headline("Go ahead?"), CardBlock.Prose("I will run:"),
            CardBlock.Code(listOf("git -C /x log --oneline | head -5"), wrap = true)), got)
    }

    @Test fun aFencedBlockKeepsItsIndentationAndCharacters() {
        val got = layout(QBlock(QKind.HEADING, "Use this config?"),
            QBlock(QKind.TEXT, "Here it is:\n```json\n{\"a\": 1,\n  \"b\": \"x -> y | z\"}\n```\nthen continue."))
        assertEquals(CardBlock.Code(listOf("{\"a\": 1,", "  \"b\": \"x -> y | z\"}")), got[2])
        assertEquals(CardBlock.Prose("then continue."), got[3])
    }

    @Test fun inlineBackticksStayInTheirSentence() {
        val got = layout(QBlock(QKind.HEADING, "Go?"), QBlock(QKind.TEXT, "It reads `requests_read_files.sh` once."))
        assertEquals(CardBlock.Prose("It reads `requests_read_files.sh` once."), got[1])
    }

    @Test fun aPathIsCode() {
        assertTrue(QuestionText.isCodeLine("/home/user/project/app.py"))
        assertTrue(QuestionText.isCodeLine("~/proj/build.gradle"))
        val got = layout(QBlock(QKind.HEADING, "Edit it?"), QBlock(QKind.TEXT, "Edit the configuration file at this path: /home/user/project/app.py"))
        assertEquals(CardBlock.Code(listOf("/home/user/project/app.py"), wrap = true), got.last())
    }

    @Test fun proseThatOnlyLooksLikeCodeStaysProse() {
        for (s in listOf("Keep a > b as it is.", "Here is the plan:", "make sure it runs.", "git is where docs/notes live.",
                "Run the tests first.", "cd into the folder and look", "The value -> next step is fine")) {
            assertTrue(s, !QuestionText.isCodeLine(s))
            assertEquals(s, listOf(QuestionText.Segment(s, false)), QuestionText.segments(s))
        }
    }

    @Test fun aCommandInAChoiceDescriptionIsItsOwnBlock() {
        val note = QuestionText.note("I run this once:\ngit -C /work/repo fetch --prune\nthen report back.")
        val blocks = CardFormat.noteBlocks(note)
        assertEquals(listOf(CardBlock.Prose("I run this once:"), CardBlock.Code(listOf("git -C /work/repo fetch --prune"), wrap = true),
            CardBlock.Prose("then report back.")), blocks)
    }

    @Test fun theAlertKeepsACommandRowOnItsOwnLine() {
        assertEquals("It runs:\ngit -C /x status --short\nOK to go on.", QuestionText.alert("It runs:\ngit -C /x status --short\nOK to go on."))
    }

    // ---- Glyphs in code blocks ------------------------------------------------------------------

    @Test fun aFramedBlockLosesItsFrameGutterAndMarker() {
        assertEquals(listOf("ls -la /work"), BoxCopy.cleanBlock(listOf("╭──────────────╮", "│ ls -la /work │", "╰──────────────╯")))
        assertEquals(listOf("npm test"), BoxCopy.cleanBlock(listOf("❯ npm test")))
        assertEquals(listOf("cp a/b.txt c/ && ls c"), BoxCopy.cleanBlock(listOf("┃ ┆ cp a/b.txt ·", "┃ ┆ c/ && ls c")))
        assertEquals(listOf("one", "  two"), BoxCopy.cleanBlock(listOf("\u001B[1m│ one\u001B[0m", "│   two")))
    }

    @Test fun codeContentGlyphsStay() {
        for (rows in listOf(listOf("echo a > b", "x -> y", "f => g"), listOf("ps aux | grep java"), listOf("a · b · c"),
                listOf("> quoted line"), listOf("name  │ size", "alpha │ 1"), listOf("if x:", "    return y"))) {
            assertEquals(rows, BoxCopy.cleanBlock(rows))
        }
    }

    private fun codeOf(path: String, agent: String): List<CardBlock.Code> {
        val s = capture(path)
        val p = requireNotNull(detectPrompt(s, true, agent))
        return CardFormat.format(p.shownParts(), s).filterIsInstance<CardBlock.Code>()
    }

    @Test fun grokAndClaudeBoxedCommandsShowWithNoGlyphsAndTheirPipes() {
        for (path in listOf("grok/live-2026-11-21b/heavy-box.txt", "grok/live-2026-11-21b/light-box.txt", "grok/live-2026-11-21b/no-box.txt")) {
            val code = codeOf(path, "grok").single()
            assertEquals(path, listOf("git -C /home/bill/Documents/repos/HerdrDroid show 507e0344560ef35cbd6f843ff81c4aa499376ca7:app/src/main/java/com/billmongan/herdrdroid/ui/ServersScreen.kt | awk 'NR>=114 && NR<=120 {printf \"%d:%s\\n\", NR, \$0}'"), code.lines)
        }
        val claude = codeOf("claude/captured-2.1.329-hook/hook-main.txt", "claude").single()
        assertEquals(listOf("gh api repos/O/R/actions/runs/1/artifacts -q '.artifacts[] | {name, size_in_bytes}'"), claude.lines)
    }

    private fun capture(path: String) = requireNotNull(javaClass.getResource("/screens/$path")).readText()

    @Test fun claudesReviewStepShowsEachAnswerUnderItsQuestion() {
        val s = capture("claude/captured-2.1.329-two-questions/review.txt")
        val p = requireNotNull(detectPrompt(s, true, "claude"))
        val blocks = CardFormat.format(p.shownParts(), s)
        assertTrue(blocks.toString(), blocks.contains(CardBlock.Labelled("Your answer", "Exact path (Recommended)")))
        assertTrue(blocks.toString(), blocks.contains(CardBlock.Labelled("Your answer", "You connect first (Recommended)")))
        assertTrue(blocks.none { CardFormat.plain(it).contains("?: ") })
        val alert = alertBody(p).lines()
        assertEquals("Review your answers", alert.first())
        assertTrue(alert.toString(), "Your answer: Exact path (Recommended)" in alert)
        assertTrue(alert.toString(), alert.none { it.contains("→") || it.contains("●") })
    }

    @Test fun anApprovalAlertShowsItsHeadingThenItsCommandOnALineOfItsOwn() {
        val p = requireNotNull(detectPrompt(capture("claude/captured-2026-11-13/bash-permission.txt"), true, "claude"))
        assertEquals("Do you want to proceed?\nmkdir -p sub && date > sub/now.txt\nCreate the folder and write the date", alertBody(p))
    }

    @Test fun theDisplayLeavesTheAnswerKeysAndTheCardIdentityAlone() {
        for (path in listOf("claude/captured-2.1.329/single-question.txt", "claude/captured-2.1.329-two-questions/q1.txt",
                "claude/captured-2026-11-13/bash-permission.txt")) {
            val s = capture(path)
            val p = requireNotNull(detectPrompt(s, true, "claude"))
            val before = p.copy()
            CardFormat.format(p.shownParts(), s); alertBody(p); alertPlan(p)
            assertEquals(before, p)
            // Each alert button answers with one of the card's own choices, label and keys as read.
            alertPlan(p).buttons.mapNotNull { it.choice }.forEach { assertTrue(it in before.choices) }
        }
    }

    // ---- Every capture ------------------------------------------------------------------------

    private val leadMarker = Regex("""^(?:[❯›▶▸➤⟩→]\s|>\s+\d{1,2}[.)]\s)""")
    private val box = Regex("""[│┃╭╮╰╯┌┐└┘┆║]|[─━═]{3,}""")
    private val ansi = Regex("""\u001B|(?<![\w\]])\[\d{1,3}(?:;\d{1,3})*m""")
    private val hint = Regex("""Enter to select|Esc to cancel|↑/↓ to navigate|Tab to amend|Arrow keys to navigate""", RegexOption.IGNORE_CASE)
    private val dangling = Regex("""\p{L}{2,}- (?!and\b|or\b|nor\b|to\b)\p{Ll}""")

    private fun leaks(where: String, s: String, code: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        if (ansi.containsMatchIn(s)) out += "$where: ANSI remnant in \"$s\""
        if (code) {
            val rows = s.lines().filter { it.isNotBlank() }
            if (rows.isNotEmpty() && rows.all { it.trimStart().first() in "│┃┆┊║" }) out += "$where: gutter on every code row in \"$s\""
            for (l in rows) {
                if (Regex("""^\s*[╭╮╰╯┌┐└┘─━═]+\s*$""").matches(l)) out += "$where: frame row in code \"$l\""
                if (Regex("""^\s*[❯›▶]\s""").containsMatchIn(l)) out += "$where: focus marker in code \"$l\""
                if (l.trimEnd().endsWith(" ·")) out += "$where: wrap mark in code \"$l\""
            }
            return out
        }
        for (l in s.lines()) {
            if (leadMarker.containsMatchIn(l)) out += "$where: focus marker in \"$l\""
            if (box.containsMatchIn(l)) out += "$where: gutter or box character in \"$l\""
            if (hint.containsMatchIn(l)) out += "$where: key hint in \"$l\""
            if (l.contains("  ")) out += "$where: repeated spaces in \"$l\""
            if (dangling.containsMatchIn(l)) out += "$where: dangling hyphen in \"$l\""
            if (l != l.trim()) out += "$where: surrounding space in \"$l\""
        }
        return out
    }

    @Test fun noCaptureShowsALeakAnywhereItsQuestionIsShown() {
        val root = File(requireNotNull(javaClass.classLoader?.getResource("screens")).toURI())
        val found = mutableListOf<String>()
        var cards = 0
        for (f in root.walkTopDown().filter { it.isFile && (it.name.endsWith(".txt") || it.name.endsWith(".ansi")) }.sortedBy { it.path }) {
            val rel = f.relativeTo(root).path
            val agent = f.relativeTo(root).parentFile.parentFile?.name ?: continue
            val e = File(f.path.substringBeforeLast('.') + ".expect")
            val blocked = !e.isFile || e.readText().contains("blocked: true")
            val screen = f.readText()
            val p: Prompt = detectPrompt(screen, blocked, agent) ?: continue
            cards++
            val parts = p.shownParts()
            for (b in CardFormat.format(parts, screen)) found += leaks("$rel card", CardFormat.plain(b), code = b is CardBlock.Code)
            parts.tabs.forEach { found += leaks("$rel pill", QuestionText.title(it.title)) }
            val rows = p.answerRows()
            val notes = CardFormat.optionNotes(screen, p.choices)
            for (c in rows) {
                val label = QuestionText.label(shownChoiceLabel(rows, c), c.keys)
                found += leaks("$rel choice ${c.keys}", label)
                if (Regex("""^${Regex.escape(c.keys)}[.)]\s""").containsMatchIn(label)) found += "$rel choice ${c.keys}: number repeated in \"$label\""
                notes[c.keys]?.let { found += leaks("$rel note ${c.keys}", it) }
            }
            val command = p.parts?.command?.lines().orEmpty()
            val body = alertBody(p)
            found += leaks("$rel alert", body.lines().filterNot { l -> command.any { it.trim() == l.trim() } }.joinToString("\n"))
            // The row marks of Claude's review step ("●", "→") never reach the alert.
            if (Regex("""(^|\s)[●→](\s|$)""").containsMatchIn(body)) found += "$rel alert: row mark in \"$body\""
            alertPlan(p).buttons.forEach { found += leaks("$rel alert button", it.title) }
            if (p.choices.isNotEmpty()) QuestionLog.seen(emptyList(), p, agent, null, 1L).last().parts.forEach { q ->
                found += leaks("$rel history", QuestionText.prose(q.question))
                q.details.forEach { found += leaks("$rel history", QuestionText.prose(it)) }
                q.options.forEach { found += leaks("$rel history option", QuestionText.label(it)) }
                q.title?.let { found += leaks("$rel history title", QuestionText.title(it)) }
            }
        }
        assertTrue("the library has question cards", cards > 50)
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }
}
