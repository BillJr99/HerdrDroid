package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ui.Snippet
import com.billmongan.herdrdroid.ui.Speaker
import com.billmongan.herdrdroid.ui.cardLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeaturesTest {
    @Test fun menuAnswersMoveTheHighlight() {
        val p = Prompt("Edit?", listOf(Choice("Yes", "1", false), Choice("Always", "2", false), Choice("No, tell Claude", "3", false)), selected = "3")
        assertEquals(listOf("up", "up", "enter"), menuKeys(p, p.choices[0]))
        assertEquals(listOf("enter"), menuKeys(p, p.choices[2]))
        assertNull(menuKeys(p.copy(selected = null), p.choices[0]))   // no highlight seen: type the digit
        val screen = "Do you want to proceed?\n  1. Yes\n  2. No\n❯ 3. Tell Claude what to do instead"
        assertEquals("3", detectPrompt(screen, blocked = true)!!.selected)
    }

    @Test fun cardsDropStatusChrome() {
        assertEquals("", cardLine("✶ Ebbing… (18s · ↓ 1.3k tokens)"))
        assertEquals("", cardLine("  ⏵⏵ auto mode on (shift+tab to cycle) · esc to interrupt"))
        assertEquals("", cardLine("? for shortcuts"))
        assertEquals("Done. The build passes.", cardLine("Done. The build passes."))
    }

    @Test fun snippetBlanks() {
        val s = Snippet("Review", "Review {{file}} for {{what}}; also {{file}} tests")
        assertEquals(listOf("file", "what"), s.blanks)
        assertEquals("Review a.kt for bugs; also a.kt tests", s.fill(mapOf("file" to "a.kt", "what" to "bugs")))
    }

    @Test fun gitAndSearchParse() {
        val g = parseGit("""{"ok": true, "git": {"/h/app": {"branch": "main", "changed": 2, "ahead": 1, "behind": 0}}}""")
        assertEquals(GitInfo("main", 2, 1, 0), g["/h/app"])
        val hits = parseSearch("""{"ok": true, "hits": [{"agent": "claude", "ref": "/h/a.jsonl", "when": 5, "title": "Fix it", "role": "user", "snippet": "the bridge"}]}""")
        assertEquals("claude", hits[0].agent)
        assertNull(hits[0].db)
    }

    @Test fun speechSkipsMarkdown() {
        assertEquals("Run it now (code) see docs", Speaker.speakable("**Run** it `now`\n```sh\nls\n```\nsee [docs](https://x)"))
    }

    @Test fun longQuestionsAreKeptWhole() {
        val screen = """
╭──────────────────────────────────────────╮
│ Bash command                             │
│   rm -rf build && ./gradlew assemble     │
│ This will delete the build folder and    │
│ rebuild everything. Do you want to       │
│ proceed with this command?               │
│ ❯ 1. Yes                                 │
│   2. No, and tell Claude what to do      │
╰──────────────────────────────────────────╯
"""
        val p = detectPrompt(screen, blocked = true)!!
        assertEquals("Bash command rm -rf build && ./gradlew assemble This will delete the build folder and rebuild everything. Do you want to proceed with this command?", p.question)
    }

    @Test fun cardLinesSkipMoreChrome() {
        assertEquals("", cardLine("✔ Update installed · /restart to apply"))
        assertEquals("", cardLine("──────────────── Demo-box2 ─"))
        assertEquals("", cardLine("❯"))
        assertEquals("All tests pass.", cardLine("│ All tests pass. │"))
    }
}
