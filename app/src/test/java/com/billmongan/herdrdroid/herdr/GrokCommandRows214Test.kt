package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ui.CardBlock
import com.billmongan.herdrdroid.ui.CardFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.1.4 (Bill, 2026-11-21: "Herdrdroid still shows vertical bars for grok questions like this,
 * parse then nicely maybe as newlines? Do this for 2.1.4"). His phone showed Grok's permission
 * card for `git -C … show 507e…:…/ServersScreen.kt | awk '…'` as
 * "┆ 507e…ServersScreen.kt |· ┆ awk '…'git -C … show·": the "┆" rows as the heading, then the
 * first row as the command, gutters and wrap marks kept. 2.1.3 read Grok's command rows only
 * inside its heavy "┃" edge; in a light "│ … │" box the first row ("│  │ git …·") was the command
 * and the "┆" rows were text, and a blank row under the "›" title ended the question before it.
 * The fixtures in screens/grok/live-2026-11-21b hold that command in three frames (see their
 * .expect files for which rows are real).
 */
class GrokCommandRows214Test {
    private fun resource(path: String) = checkNotNull(javaClass.getResource(path)).readText()
    private val light by lazy { resource("/screens/grok/live-2026-11-21b/light-box.txt") }
    private val heavy by lazy { resource("/screens/grok/live-2026-11-21b/heavy-box.txt") }
    private val bare by lazy { resource("/screens/grok/live-2026-11-21b/no-box.txt") }
    private val all by lazy { listOf("light" to light, "heavy" to heavy, "bare" to bare) }

    private val title = "Confirm the back arrow click line"
    private val command = "git -C /home/user/Documents/repos/HerdrDroid show " +
        "507e0344560ef35cbd6f843ff81c4aa499376ca7:app/src/main/java/com/billmongan/herdrdroid/ui/ServersScreen.kt | " +
        """awk 'NR>=114 && NR<=120 {printf "%d:%s\n", NR, ${'$'}0}'"""

    /** What 2.1.3 showed Bill: the card's heading and command copied together. */
    private val before213 = "┆ 507e0344560ef35cbd6f843ff81c4aa499376ca7:app/src/main/java/com/billmongan/herdrdroid/ui/ServersScreen.kt |· " +
        """┆ awk 'NR>=114 && NR<=120 {printf "%d:%s\n", NR, ${'$'}0}'git -C /home/user/Documents/repos/HerdrDroid show·"""

    private val marks = Regex("""[┆┊╎╏│┃]|·(?:\s|$)""")

    private fun card(screen: String) = checkNotNull(detectPrompt(screen, blocked = true, agent = "grok"))

    @Test fun billsPromptIsItsTitleThenTheCommandInOrder() {
        for ((name, screen) in all) {
            val p = card(screen)
            val parts = p.shownParts()
            assertEquals(name, title, parts.heading)
            assertEquals(name, command, parts.command)
            assertTrue(name, parts.details.isEmpty())
            assertEquals(name, "$title: $command", p.question)
            for (t in listOf(p.question) + parts.blocks.map { it.text }) assertFalse("$name: $t", marks.containsMatchIn(t))
            // The pipe and the "\n" inside the quotes stay as typed.
            assertTrue(name, parts.command!!.contains("ServersScreen.kt | awk"))
            assertTrue(name, parts.command!!.contains("""%d:%s\n"""))
            assertEquals(name, 4, p.choices.size)
        }
    }

    @Test fun theCardShowsTheHeadingThenOneCommandLine() {
        for ((name, screen) in all) {
            val parts = card(screen).shownParts()
            val blocks = CardFormat.format(parts, screen)
            assertEquals(name, listOf(CardBlock.Headline(title), CardBlock.Code(listOf(command), wrap = true)), blocks)
            val copied = blocks.joinToString("") { CardFormat.plain(it) }
            assertNotEquals(name, before213, copied)
            assertFalse(name, marks.containsMatchIn(copied))
        }
    }

    @Test fun theChatShowsTheTitleThenOneCommandLine() {
        for ((name, screen) in all) {
            val segs = Reflow.parse(screen, grok = true, agent = "grok")
            val at = segs.indexOf(Seg.Para(0, null, title))
            assertTrue("$name: $segs", at >= 0)
            // The title, then (past the box's blank row, in the light box) the command.
            assertEquals(name, Seg.Code(listOf(command)), segs.drop(at + 1).first { it != Seg.Gap })
            assertEquals(name, listOf("1.", "2.", "3.", "4."), segs.filterIsInstance<Seg.Para>().mapNotNull { it.mark })
            val lines = segs.flatMap { s -> when (s) { is Seg.Para -> listOf(s.text); is Seg.Code -> s.lines; else -> emptyList() } }
            lines.forEach { assertFalse("$name: $it", Regex("""[┆┊╎╏┃]|·$""").containsMatchIn(it) || it.startsWith("│")) }
        }
    }

    @Test fun theAnswerKeysAreTheSameInEveryFrame() {
        val keys = all.map { (_, screen) -> card(screen).let { p -> p.choices.map { it.keys to menuKeys(p, it) } } }
        assertTrue(keys.all { it == keys.first() })
        assertEquals(listOf("1", "2", "3", "4"), keys.first().map { it.first })
        // A fresh read of the same screen still fits the card, so its answer goes through.
        for ((_, screen) in all) assertTrue(cardAnswerStillFits(card(screen), card(screen)))
    }

    // ---- rows that Grok did not wrap stay lines of their own ----------------------------------

    private fun lightBox(vararg rows: String): String {
        val w = 90
        fun r(t: String) = (if (t.isEmpty()) "  │" else "  │  $t").padEnd(w) + "│"
        return (listOf("     Next I will run it.", "", "  ╭" + "─".repeat(w - 3) + "╮") + rows.map(::r) +
            listOf(r(""), r("1 (○) Yes, and don't ask again for anything (always-approve mode)"), r("2 (●) Yes, proceed"),
                r("3 (○) No, reject (type to add feedback)"), r(""), "  ╰" + "─".repeat(w - 3) + "╯", "",
                "  1/3:select  │  Tab:next option  │  Ctrl+c:cancel  │  Esc:scrollback")).joinToString("\n")
    }

    @Test fun rowsWithoutAWrapMarkAreLinesOfTheirOwn() {
        val screen = lightBox("›  Build and lint", "│ cd /work/app", "│ ./gradlew test·", "┆ && ./gradlew lint", "┆ git status --short")
        val parts = card(screen).shownParts()
        assertEquals("Build and lint", parts.heading)
        assertEquals("cd /work/app\n./gradlew test && ./gradlew lint\ngit status --short", parts.command)
        assertEquals(listOf(CardBlock.Headline("Build and lint"),
            CardBlock.Code(listOf("cd /work/app", "./gradlew test && ./gradlew lint", "git status --short"))),
            CardFormat.format(parts, screen))
        assertEquals(Seg.Code(listOf("cd /work/app", "./gradlew test && ./gradlew lint", "git status --short")),
            Reflow.parse(screen, grok = true).filterIsInstance<Seg.Code>().single())
    }

    @Test fun aTitleInTheBoxEdgeIsTheHeading() {
        val screen = lightBox("│ ls -la app/build·", "┆ | head").replace("  ╭───", "  ╭─ List the build outputs ─")
        val parts = card(screen).shownParts()
        assertEquals("List the build outputs", parts.heading)
        assertEquals("ls -la app/build | head", parts.command)
    }

    @Test fun aLightBoxTitleRightOverItsCommand() {
        val parts = card(lightBox("›  List the build outputs", "│ ls -la app/build/outputs/apk")).shownParts()
        assertEquals("List the build outputs", parts.heading)
        assertEquals("ls -la app/build/outputs/apk", parts.command)
    }

    @Test fun aTableInGroksReplyIsNotACommand() {
        val screen = listOf("     The sizes:", "", "     ┌──────┬──────┐", "     │      │ b    │", "     │ a    │ c    │", "     └──────┴──────┘").joinToString("\n")
        val code = Reflow.parse(screen, grok = true).filterIsInstance<Seg.Code>().single().lines
        assertEquals(listOf("┌──────┬──────┐", "│      │ b    │", "│ a    │ c    │", "└──────┴──────┘"), code)
    }

    // ---- the 2.1.3 screens ---------------------------------------------------------------------

    @Test fun groksHeredocKeepsItsLinesOnTheCard() {
        // 2.1.4 changes this card: each "│" row of a script is its own line, as on the screen and in
        // chat; 2.1.3 ran them together on one line ("python3 - << 'PY' import gzip, json, re, ...").
        val parts = card(resource("/screens/grok/captured-2026-11-15b/scan-approval.txt")).shownParts()
        assertEquals("Scan recordings and compare published player", parts.heading)
        assertEquals(listOf("python3 - << 'PY'", "import gzip, json, re, pathlib", "files = ["), parts.command!!.lines().take(3))
    }
}
