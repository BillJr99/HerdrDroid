package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.detectPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.8 (Bill: "improve the parsing of question boxes in agy and all panes, make sure all the
 * question text is visible and well formatted especially code"): one long question per harness,
 * with paragraphs, a shell block and a Python block (fenced, or drawn as Markdown with the code
 * indented), from screens/<agent>/derived-2.2.8-format/. The card must show all of it: the
 * question, every paragraph in full, and each code block as its own monospace block with every row
 * and its indentation; the options are their labels only. Approval dialogs show a command of
 * several rows as one block, row by row.
 */
class QuestionFormat228Test {
    private val p1 = "The release build fails on its second step, after the cache is restored. " +
        "This is the part of scripts/build.sh that runs there:"
    private val p2 = "The same check could run as one call instead:"
    private val p3 = "Each file is compiled on its own today, so one bad file stops the loop before the tests run. " +
        "The single call reports every bad file at once."
    private val q = "Should I keep the loop as it is, or replace it with one compileall call?"
    private val shell = listOf("for f in src/*.py; do", "    python3 -m py_compile \"\$f\" || exit 1", "done", "make -C build test JOBS=4")
    private val python = listOf("import compileall, sys", "ok = compileall.compile_dir(\"src\", quiet=1)", "if not ok:", "    sys.exit(1)")

    private fun read(path: String) = requireNotNull(javaClass.getResource("/screens/$path")) { path }.readText()

    private fun card(path: String, blocked: Boolean = true): Triple<Prompt, List<CardBlock>, String> {
        val s = read(path)
        val p = requireNotNull(detectPrompt(s, blocked, path.substringBefore('/'))) { "no card for $path" }
        return Triple(p, CardFormat.format(p.shownParts(), s), s)
    }

    private fun text(blocks: List<CardBlock>) = blocks.filter { it !is CardBlock.Code }.joinToString("\n") { CardFormat.plain(it) }
        .replace(Regex("\\s+"), " ")

    /** The whole question on the card, each code block whole, in its own box, rows and indentation kept. */
    private fun assertWholeQuestion(path: String, heading: String = q) {
        val (p, blocks, _) = card(path)
        val shown = text(blocks)
        assertTrue("$path headline: $blocks", blocks.first() is CardBlock.Headline && CardFormat.plain(blocks.first()).endsWith(heading))
        for (para in listOf(p1, p2, p3)) assertTrue("$path lost \"$para\": $shown", shown.contains(para))
        val code = blocks.filterIsInstance<CardBlock.Code>()
        assertEquals("$path code blocks: $code", listOf(shell, python), code.map { it.lines })
        // Only labels: no hint row, footer or description in any of them.
        for (c in p.choices) assertFalse("$path label \"${c.label}\"", Regex("""Enter to|esc to|↑|↓|navigate|Leave scripts|Replace the loop""", RegexOption.IGNORE_CASE).containsMatchIn(c.label))
        // Nothing of the code is in the prose around it.
        assertFalse("$path code leaked into the text: $shown", shown.contains("py_compile") || shown.contains("compile_dir"))
    }

    @Test fun antigravityQuestionShowsEveryParagraphAndBothCodeBlocks() {
        assertWholeQuestion("agy/derived-2.2.8-format/long-code-question.txt")
        assertWholeQuestion("agy/derived-2.2.8-format/long-indented-question.txt")
        val (p, blocks, s) = card("agy/derived-2.2.8-format/long-code-question.txt")
        assertEquals(listOf("Keep the loop", "Use compileall", "Write-in"), p.choices.map { it.label })
        assertEquals("Leave scripts/build.sh as it is; the first bad file stops the build.", CardFormat.optionNotes(s, p.choices)["1"])
        assertTrue(blocks.none { it is CardBlock.Prose && CardFormat.plain(it).startsWith("sys.exit") })
    }

    @Test fun antigravityCommandApprovalKeepsEveryRowOfTheCommand() {
        val (p, blocks, _) = card("agy/derived-2.2.8-format/long-command-approval.txt")
        assertEquals(CardBlock.Headline("Run this command?"), blocks.first())
        assertEquals(listOf(shell), blocks.filterIsInstance<CardBlock.Code>().map { it.lines })
        assertTrue(blocks.contains(CardBlock.Labelled("Reason", "outside workspace")))
        assertEquals(4, p.choices.size)
    }

    @Test fun claudeQuestionTallerThanThirtyRowsKeepsItsTabAndAllItsText() {
        assertWholeQuestion("claude/derived-2.2.8-format/long-code-question.txt")
        assertWholeQuestion("claude/derived-2.2.8-format/long-indented-question.txt")
        val (p, blocks, s) = card("claude/derived-2.2.8-format/long-code-question.txt")
        // The tab's title is not text of the question.
        assertFalse(text(blocks).contains("Build loop"))
        assertEquals("Replace the loop with the Python call above and keep the make line.", CardFormat.optionNotes(s, p.choices)["2"])
    }

    @Test fun codexQuestionShowsAllOfItsTextAndStaysTerminalOnly() {
        val (p, _, _) = card("codex/derived-2.2.8-format/long-code-question.txt")
        assertTrue(p.terminalReview && p.codexQuestion && p.choices.isEmpty())
        assertWholeQuestion("codex/derived-2.2.8-format/long-code-question.txt")
    }

    @Test fun grokQuestionCardShowsItsWholeFrame() {
        assertWholeQuestion("grok/derived-2.2.8-format/long-code-question.txt")
        assertWholeQuestion("grok/derived-2.2.8-format/long-indented-question.txt")
    }

    @Test fun ompAndPiAskLeadKeepsItsCodeBlocks() {
        for (path in listOf("omp/derived-2.2.8-format/long-code-question.txt", "omp/derived-2.2.8-format/long-indented-question.txt",
                "pi/derived-2.2.8-format/long-code-question.txt")) {
            assertWholeQuestion(path, heading = "1. $q")
        }
    }

    @Test fun copilotVibeAndOpenCodeQuestionsShowTheirWholeBox() {
        assertWholeQuestion("copilot/derived-2.2.8-format/long-code-question.txt")
        assertWholeQuestion("vibe/derived-2.2.8-format/long-code-question.txt")
        assertWholeQuestion("opencode/derived-2.2.8-format/long-code-question.txt")
    }

    @Test fun hermesHeredocShowsAsOneScriptWithItsIndentation() {
        val (p, blocks, _) = card("hermes/derived-2.2.8-format/long-command-approval.txt", blocked = false)
        assertEquals(listOf(listOf("python3 -B - <<'PY'") + python + "PY"), blocks.filterIsInstance<CardBlock.Code>().map { it.lines })
        assertEquals(listOf("Allow once", "Allow for this session", "Add to permanent allowlist", "Deny"), p.choices.map { it.label })
    }
}
