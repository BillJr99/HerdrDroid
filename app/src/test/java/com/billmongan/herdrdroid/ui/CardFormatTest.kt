package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.herdr.QBlock
import com.billmongan.herdrdroid.herdr.QKind
import com.billmongan.herdrdroid.herdr.QuestionParts
import com.billmongan.herdrdroid.herdr.detectPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.1.1 (Bill: "text in question cards could be formatted in a more friendly way it's kind of a
 * wall of text and code right now"): the card body's layout, on every screen in the library.
 */
class CardFormatTest {
    private fun screen(path: String) = requireNotNull(javaClass.getResource("/screens/$path")).readText()
    private fun card(path: String, agent: String = path.substringBefore('/')): Pair<List<CardBlock>, String> {
        val s = screen(path)
        val p = requireNotNull(detectPrompt(s, true, agent))
        return CardFormat.format(p.shownParts(), s) to s
    }

    /**
     * The golden layout of every library screen that makes a card; a change shows as a diff. A screen
     * added to the library after the golden file (another branch's capture) is checked only by the
     * test below until the golden file is regenerated from app/build/card-format-actual.txt.
     */
    @Test fun everyLibraryCardMatchesItsGoldenLayout() {
        val actualText = CardFormatGolden.render()
        val golden = CardFormatGolden.sections(requireNotNull(javaClass.getResource("/card-format/golden.txt")).readText())
        val actual = CardFormatGolden.sections(actualText)
        if (actual.filterKeys { it in golden } != golden) {
            File("build").mkdirs()
            File("build/card-format-actual.txt").writeText(actualText)
        }
        assertTrue("screens in the golden file no longer make a card: ${golden.keys - actual.keys}", actual.keys.containsAll(golden.keys))
        assertTrue(golden.size > 50)
        for ((screen, layout) in golden) assertEquals("card layout of $screen changed; the new layout is in app/build/card-format-actual.txt", layout, actual[screen])
    }

    /** Nothing is dropped or reworded: the same characters as the parser's text, less "#" titles and the "△" glyph. */
    @Test fun layoutKeepsEveryCharacterOfTheParsedText() {
        val root = File(requireNotNull(javaClass.classLoader?.getResource("screens")).toURI())
        val strip = "#△$"
        fun chars(s: String) = s.filterNot { it.isWhitespace() || it in strip }.toList().sorted()
        var checked = 0
        for (f in root.walkTopDown().filter { it.isFile && it.name.endsWith(".txt") }) {
            val agent = f.parentFile.parentFile.name
            val s = f.readText()
            val p = detectPrompt(s, true, agent) ?: continue
            val parts = p.shownParts()
            // 2.2.2: Claude's review step shows the answer under its question as "Your answer: …",
            // in place of the parser's "…?: answer"; read here as the ":" it stands for.
            val shown = CardFormat.format(parts, s).joinToString("") {
                if (it is CardBlock.Labelled && it.label == "Your answer") ":" + it.value else CardFormat.plain(it)
            }
            assertEquals(f.path, chars(parts.blocks.joinToString("") { it.text }), chars(shown))
            checked++
        }
        assertTrue(checked > 50)
    }

    @Test fun claudeEditShowsItsLinesAsOneDiff() {
        val (blocks, _) = card("claude/derived-1.4.9/0101-file-edit-create-overwrite-approval-with.txt")
        assertEquals(CardBlock.Headline("Do you want to make this edit to app.py?"), blocks.first())
        val diff = blocks.filterIsInstance<CardBlock.Code>().single { it.diff }
        assertEquals(listOf("12      def greet(name):", "13 -        return \"hi \" + name", "13 +        return f\"Hello, {name}!\""), diff.lines)
        assertTrue(blocks.contains(CardBlock.Code(listOf("src/app.py"), wrap = true)))
    }

    @Test fun planIsATitleAndAListUnderItsLeadIn() {
        val (blocks, _) = card("claude/derived-1.4.9/0107-exit-plan-mode-ready-to-code.txt")
        assertEquals(listOf(
            CardBlock.Headline("Claude has written up a plan and is ready to execute. Would you like to proceed?"),
            CardBlock.Caption("Here is Claude's plan:"),
            CardBlock.Subhead("Add greeting endpoint"),
            CardBlock.Items(listOf(CardItem("1.", "Add GET /hello to src/app.py"), CardItem("2.", "Add a test in tests/test_app.py"))),
        ), blocks)
    }

    @Test fun bulletsBetweenParagraphsBecomeAList() {
        val (blocks, _) = card("claude/derived-1.4.9/0108-enter-plan-mode.txt")
        assertEquals(4, blocks.size)
        assertEquals(listOf("Identify existing patterns", "Design an implementation strategy", "Present a plan for your approval"),
            (blocks[2] as CardBlock.Items).items.map { it.text })
        assertEquals(CardBlock.Prose("No code changes will be made until you approve the plan."), blocks[3])
    }

    @Test fun scriptsKeepTheirLines() {
        val (muse, _) = card("muse/captured-2026-11-15/proceed-approval.txt")
        assertEquals(6, muse.filterIsInstance<CardBlock.Code>().single().lines.size)
        val (grok, _) = card("grok/captured-2026-11-15/python-approval.txt")
        assertEquals("python3 - << 'PY'", grok.filterIsInstance<CardBlock.Code>().single().lines.first())
        val (codex, _) = card("codex/synthetic-2.0/multiline-command-approval.txt")
        assertEquals(7, codex.filterIsInstance<CardBlock.Code>().single().lines.size)
    }

    @Test fun headingLeadInIsACaption() {
        val (blocks, _) = card("codex/derived-1.4.9/0206-request-user-input-multi-question.txt")
        assertEquals(CardBlock.Caption("Question 1/2 Database"), blocks[0])
        assertEquals(CardBlock.Headline("Which database should demo-box use?"), blocks[1])
    }

    @Test fun optionDescriptionsComeFromTheLinesUnderEachOption() {
        val s = screen("claude/captured-2026-11-13/multi-question.txt")
        val p = requireNotNull(detectPrompt(s, true, "claude"))
        assertEquals(mapOf("1" to "Mark 537 resolved now.", "2" to "Leave it for a later pass."), CardFormat.optionNotes(s, p.choices))
        // A label wrapped onto the next line is part of the label, not a description (Copilot's "(Manual").
        val c = screen("copilot/derived-1.4.9/0408-exit-plan-mode.txt")
        val cp = requireNotNull(detectPrompt(c, true, "copilot"))
        assertEquals(emptyMap<String, String>(), CardFormat.optionNotes(c, cp.choices))
        assertEquals(emptyMap<String, String>(), CardFormat.optionNotes(null, p.choices))
    }

    @Test fun labelPartsKeepTheWholeLabel() {
        for (l in listOf("Yes, proceed (y)", "No, and tell Claude what to do differently (esc)", "Approve Once  -  reload now",
            "Yes, and switch to accept edits (auto-approve file edits and common file commands) for this session", "Deny")) {
            val (main, tail) = CardFormat.labelParts(l)
            assertEquals(l, main + tail)
        }
        assertEquals("Yes, proceed" to " (y)", CardFormat.labelParts("Yes, proceed (y)"))
        assertEquals("No" to " (Tab to edit)", CardFormat.labelParts("No (Tab to edit)"))
    }

    /** Without the screen (or when a block isn't on it) each block is laid out from its own text, unchanged. */
    @Test fun withoutTheScreenEachBlockKeepsItsText() {
        val parts = QuestionParts(emptyList(), listOf(
            QBlock(QKind.HEADING, "Do you want to proceed?"),
            QBlock(QKind.COMMAND, "mkdir -p sub && date > sub/now.txt"),
            QBlock(QKind.TEXT, "Reason: outside the workspace"),
            QBlock(QKind.TEXT, "Host:"),
            QBlock(QKind.COMMAND, "a\nb"),
        ))
        assertEquals(listOf(
            CardBlock.Headline("Do you want to proceed?"),
            CardBlock.Code(listOf("mkdir -p sub && date > sub/now.txt"), wrap = true),
            CardBlock.Labelled("Reason", "outside the workspace"),
            CardBlock.Caption("Host:"),
            CardBlock.Code(listOf("a", "b")),
        ), CardFormat.format(parts, null))
        assertEquals(CardFormat.format(parts, null), CardFormat.format(parts, "nothing like it\non this screen"))
    }

    @Test fun diffNeedsBothSidesOrAHunk() {
        assertTrue(CardFormat.isDiff(listOf("@@ -1 +1 @@")))
        assertTrue(CardFormat.isDiff(listOf("-old", "+new")))
        assertTrue(!CardFormat.isDiff(listOf("- /home/user/work/shared/*")))
    }
}
