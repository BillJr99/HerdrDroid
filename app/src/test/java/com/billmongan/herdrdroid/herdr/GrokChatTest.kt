package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.1.2 (Bill: "touch up the rendering view for grok, it's not bad but the occasional glyph").
 * The live fixture is the grok pane's own screen, read with `herdr pane read --source recent
 * --lines 200` (text and ANSI) on 2026-11-20 after a review (home path sanitized). Before this
 * change its header bar came through as a code strip, its "◈" tool rows as prose with the glyph
 * as a bullet, and "Worked for 7m19s" as a reply; idle-after-review.before.chat is that output.
 */
class GrokChatTest {
    private fun resource(path: String) = checkNotNull(javaClass.getResource(path)).readText()
    private val live by lazy { resource("/screens/grok/live-2026-11-20/idle-after-review.txt") }
    private val scan by lazy { resource("/screens/grok/captured-2026-11-15b/scan-approval.txt") }

    /** The pieces as text, one per line, so a whole screen can be compared with a saved one. */
    private fun dump(segs: List<Seg>): String = segs.joinToString("\n") { s ->
        when (s) {
            Seg.Gap -> ""
            Seg.Rule -> "---"
            is Seg.You -> "you: ${s.text}"
            is Seg.Para -> "para[${s.indent}${s.mark?.let { ",$it" } ?: ""}${if (s.detail) ",detail" else ""}]: ${s.text}"
            is Seg.Code -> "code:\n" + s.lines.joinToString("\n") { "  | $it" }
            is Seg.Activity -> "activity: ${s.summary}\n" + s.steps.joinToString("\n") { st ->
                "  - ${st.kind} ${st.title}${st.detail?.let { " ($it)" } ?: ""}${if (st.running) " [running]" else ""}"
            }
            is Seg.Footer -> "footer: " + s.lines.joinToString(" | ")
        }
    }

    private fun matches(segs: List<Seg>, saved: String, out: String) {
        val got = dump(segs)
        val want = javaClass.getResource(saved)?.readText()
        if (want == null) {
            File("build/$out").writeText(got)
            error("no saved chat; wrote build/$out")
        }
        assertEquals(want.trimEnd(), got.trimEnd())
    }

    /** Glyphs that must not start or sit in a Grok chat line: its markers, box edges, timers, buttons. */
    private val leaks = Regex("""^[◆◈◇┃│⚠]|[⇣]|\[stop]|\[Dashboard]|\[Opt (?:out|in)]|[\uE000-\uF8FF]|[\uDB80-\uDBFF]""")

    private fun prose(segs: List<Seg>) =
        segs.filterIsInstance<Seg.Para>().map { it.text } + segs.filterIsInstance<Seg.You>().map { it.text }

    @Test fun liveScreenMatchesItsSavedChat() =
        matches(Reflow.parse(live, grok = true), "/screens/grok/live-2026-11-20/idle-after-review.chat", "grok-live-chat.txt")

    @Test fun liveScreenProseHasNoMetaGlyphs() {
        val segs = Reflow.parse(live, grok = true)
        prose(segs).forEach { assertFalse(it, leaks.containsMatchIn(it)) }
        assertTrue(segs.none { it is Seg.Code })
        assertTrue(segs.none { it is Seg.Para && it.mark == "◈" })
        // The two tool rows are chips; the lone thought keeps its quiet line.
        val chips = segs.filterIsInstance<Seg.Activity>()
        assertEquals(listOf("Explored 3 files, ran 3 commands", "Created grok-rc-review-3.md, ran 13 commands +23"), chips.map { it.summary })
        assertTrue(chips.none { it.running })
        assertTrue(segs.contains(Seg.Para(0, null, "Thought for 2.3s", detail = true)))
        // Header bar and turn marker are quiet rows.
        assertEquals(Seg.Para(0, null, "~/agents/grok · 174k / 256k", detail = true), segs.first())
        assertTrue(segs.contains(Seg.Para(0, null, "Worked for 7m19s", detail = true)))
        assertEquals(Seg.Footer(listOf("Grok 4.7 (high) · auto-review", "Shift+Tab:mode    Ctrl+.:shortcuts")), segs.last())
    }

    @Test fun liveScreenKeepsTheRepliesWordForWord() {
        val segs = Reflow.parse(live, grok = true)
        val paras = segs.filterIsInstance<Seg.Para>().map { it.text }
        assertTrue(paras.contains("This is the final 2.1.1 candidate. I will fetch 48fdc0e, read the RC notes, and review only that diff."))
        assertTrue(paras.contains("Verdict: GO. The review is in /home/user/Documents/workspace/outputs/herdrdroid-2.1.1/grok-rc-review-3.md."))
        assertTrue(paras.contains("REVIEW DONE"))
        assertTrue(paras.any { it.startsWith("The re-review H1 is closed. Alert Reply uses strictNotifyRead (AppViewModel.kt:2773), an 80-line read with no 40-line fallback.") })
        assertTrue(segs.filterIsInstance<Seg.You>().single().text.startsWith("From claude (Herdr on minipc2; tab: claude). HerdrDroid 2.1.1 FINAL"))
    }

    @Test fun ansiReadGivesTheSameChat() {
        val ansi = resource("/screens/grok/live-2026-11-20/idle-after-review.ansi")
        assertEquals(dump(Reflow.parse(live, grok = true)), dump(Reflow.parse(forPhone(ansi), grok = true)))
    }

    @Test fun workingScreenWithApprovalMatchesItsSavedChat() =
        matches(Reflow.parse(scan, grok = true), "/screens/grok/captured-2026-11-15b/scan-approval.chat", "grok-scan-chat.txt")

    @Test fun workingScreenFoldsRunningToolsAndStatusRow() {
        val segs = Reflow.parse(scan, grok = true)
        prose(segs).forEach { assertFalse(it, leaks.containsMatchIn(it)) }
        val chips = segs.filterIsInstance<Seg.Activity>()
        assertEquals(2, chips.size)
        val last = chips.last()
        assertTrue(last.running)
        assertEquals(AgyMeta.Step(AgyMeta.Kind.STATUS, "Scan recordings and compare published pl… 4m28s", "14m29s", running = true), last.steps.last())
        assertTrue(segs.contains(Seg.Para(0, null, "Context 81% full. Compacting…", detail = true)))
        assertEquals(Seg.Para(0, null, "~/agents/grok · 84K / 256K", detail = true), segs.first())
        // The approval box: its title, the command verbatim as code, the choices; no edge or key hints.
        assertTrue(segs.contains(Seg.Para(0, null, "Scan recordings and compare published player")))
        assertEquals("python3 - << 'PY'", segs.filterIsInstance<Seg.Code>().single().lines.first())
        assertEquals(listOf("1.", "2.", "3.", "4."), segs.filterIsInstance<Seg.Para>().mapNotNull { it.mark })
        assertTrue(prose(segs).none { "Ctrl-F" in it || "narrow scope" in it })
        val footer = segs.last() as Seg.Footer
        assertTrue(footer.lines.single().startsWith("1/4:select"))
        // Prose keeps its margin: the box edge further left doesn't indent it.
        assertTrue(segs.filterIsInstance<Seg.Para>().all { it.indent == 0 })
    }

    @Test fun bannerIsOneQuietLine() {
        val segs = Reflow.parse(resource("/screens/grok/captured-2026-11-13/privacy-banner.txt"), grok = true)
        assertTrue(segs.contains(Seg.Para(0, null, "Worked for 4.4s", detail = true)))
        val banner = segs.filterIsInstance<Seg.Para>().single { it.text.startsWith("Help improve Grok") }
        assertTrue(banner.detail)
        assertFalse(banner.text, "[Opt" in banner.text)
        assertTrue(banner.text.endsWith("Read Terms and Privacy Policy."))
        assertTrue(segs.none { it is Seg.Code })
    }

    @Test fun warningReadsAsOne() {
        val segs = Reflow.parse(resource("/screens/grok/errors-1.4.2/quota.txt"), grok = true)
        assertTrue(segs.any { it is Seg.Para && it.text == "Warning: Rate limited: you have reached your usage limit for Grok 4.7. Try again in 41 minutes." })
    }

    @Test fun statusRowParses() {
        assertEquals(AgyMeta.Step(AgyMeta.Kind.STATUS, "Check SSH to devbox in batch mode… 58s", "2m46s", running = true),
            GrokMeta.statusRow("   ◆ Check SSH to devbox in batch mode… 58s                                     2m46s ⇣61.9k [↓][stop]"))
        assertNull(GrokMeta.statusRow("     The download was ⇣ slow, so I stopped."))
        assertNull(GrokMeta.statusRow("     Press [stop] to end it."))
    }

    @Test fun privateUseGlyphsLeaveProseButNotCode() {
        val folder = "\uF07B" // a Nerd Font folder icon
        val screen = listOf("Opened $folder the folder.", "", "┌──────┬──────┐", "│ $folder a │ b    │", "└──────┴──────┘").joinToString("\n")
        val segs = Reflow.parse(screen, grok = true)
        assertEquals("Opened the folder.", segs.filterIsInstance<Seg.Para>().single().text)
        assertEquals("│ $folder a │ b    │", segs.filterIsInstance<Seg.Code>().single().lines[1])
    }

    @Test fun otherAgentsAreUnchanged() {
        // The same screens without the Grok flag keep the old pieces: no chips, the glyph rows as prose.
        for (screen in listOf(live, scan)) {
            assertTrue(Reflow.parse(screen).none { it is Seg.Activity })
            assertTrue(Reflow.parse(screen, antigravity = true).none { it is Seg.Activity && it.grok })
        }
        assertTrue(Reflow.parse(live).any { it is Seg.Para && it.mark == "◈" })
    }

    @Test fun theAgentNameLeavesGrokChatAsItIs() {
        // The app passes the pane's agent with the Grok flag (2.1.2): the Claude and Hermes input box
        // rule must not apply to Grok, so the chat is the same with or without it.
        for (screen in listOf(live, scan)) {
            assertNull(composerBoxStart(screen, "grok"))
            assertEquals(dump(Reflow.parse(screen, grok = true)), dump(Reflow.parse(screen, grok = true, agent = "grok")))
        }
    }

    // ---- 2.1.3: wrapped command rows (Bill: "grok pane still parses a little awkwardly in chat",
    // "Notice the vertical pipe glyphs coming through"). Grok draws a long command on rows with a
    // "┆" gutter and a "·" at the end of each row it wrapped; chat showed each row as prose, glyphs
    // and all. bill-example.txt is his phone example in the approval box layout; multirow-approval.txt
    // is a real capture from 2026-11-14; idle-compacted is the grok pane's own screen on 2026-11-21.

    private val billExample by lazy { resource("/screens/grok/live-2026-11-21/bill-example.txt") }
    private val multirow by lazy { resource("/screens/grok/live-2026-11-21/multirow-approval.txt") }

    /** Every line of text the chat shows: prose, messages, code, chips and footer. */
    private fun shown(segs: List<Seg>): List<String> = segs.flatMap { s ->
        when (s) {
            is Seg.Para -> listOf(s.text)
            is Seg.You -> listOf(s.text)
            is Seg.Code -> s.lines
            is Seg.Activity -> listOf(s.summary) + s.steps.map { it.title }
            is Seg.Footer -> s.lines
            else -> emptyList()
        }
    }

    private val gutters = Regex("""[┆┊│┃╎╏]""")

    @Test fun billsExampleIsItsTitleThenOneCommand() {
        val segs = Reflow.parse(billExample, grok = true)
        assertTrue(segs.contains(Seg.Para(0, null, "Retry removal of temporary CI files")))
        assertEquals(
            listOf("rm -f /tmp/hd-ui-attempt1.log /tmp/hd-ui-attempt1.err /tmp/hd-ci-a.zip /tmp/hd-ci-b.zip; " +
                "rmdir /tmp/hd-ci-a /tmp/hd-ci-b 2>/dev/null; ls /tmp/hd-ui-attempt1.log /tmp/hd-ci-a.zip 2>&1 | head"),
            segs.filterIsInstance<Seg.Code>().single().lines,
        )
        // The title comes right before its command.
        val title = segs.indexOf(Seg.Para(0, null, "Retry removal of temporary CI files"))
        assertTrue(segs[title + 1] is Seg.Code)
        assertEquals(listOf("1.", "2.", "3.", "4."), segs.filterIsInstance<Seg.Para>().mapNotNull { it.mark })
    }

    @Test fun billsExampleShowsNoGutterOrWrapMark() {
        val lines = shown(Reflow.parse(billExample, grok = true))
        lines.forEach { l ->
            assertFalse(l, gutters.containsMatchIn(l))
            assertFalse(l, l.trimEnd().endsWith("·"))
        }
        // A real pipe in the command stays.
        assertTrue(lines.any { "2>&1 | head" in it })
        prose(Reflow.parse(billExample, grok = true)).forEach { assertFalse(it, leaks.containsMatchIn(it)) }
    }

    @Test fun capturedMultiRowCommandIsOneLine() {
        val segs = Reflow.parse(multirow, grok = true)
        assertTrue(segs.contains(Seg.Para(0, null, "List HerdrDroid source files and confirm commit")))
        assertEquals(
            listOf("find /home/user/src/app -type d \\( -name keystore -o -name release -o -name build -o -name .git \\) " +
                "-prune -o -type f -print | sed 's|/home/user/src/app/||' | head -400; echo '---'; " +
                "git -C /home/user/src/app rev-parse HEAD; git -C /home/user/src/app describe --tags --always 2>/dev/null | head -5"),
            segs.filterIsInstance<Seg.Code>().single().lines,
        )
        shown(segs).forEach { assertFalse(it, gutters.containsMatchIn(it) || it.endsWith("·")) }
    }

    private fun box(vararg rows: String) = (listOf("     Next I will run it.", "", "  ┃") + rows.map { "  ┃  $it" } +
        listOf("  ┃", "  ┃  1 (○) Yes, and don't ask again for anything (always-approve mode)", "  ┃  2 (●) Yes, proceed",
            "  ┃  3 (○) No, reject (type to add feedback)", "  ┃")).joinToString("\n")

    @Test fun singleRowCommand() {
        val segs = Reflow.parse(box("›  List the build outputs", "│ ls -la app/build/outputs/apk"), grok = true)
        assertTrue(segs.contains(Seg.Para(0, null, "List the build outputs")))
        assertEquals(listOf("ls -la app/build/outputs/apk"), segs.filterIsInstance<Seg.Code>().single().lines)
    }

    @Test fun separateCommandLinesStaySeparate() {
        val segs = Reflow.parse(box("›  Build and lint", "│ cd /work/app", "│ ./gradlew test·", "┆ && ./gradlew lint", "│ git status --short"), grok = true)
        assertEquals(listOf("cd /work/app", "./gradlew test && ./gradlew lint", "git status --short"), segs.filterIsInstance<Seg.Code>().single().lines)
        shown(segs).forEach { assertFalse(it, gutters.containsMatchIn(it) || it.endsWith("·")) }
    }

    @Test fun titleWithNoCommand() {
        val segs = Reflow.parse(box("›  Check the release notes"), grok = true)
        assertTrue(segs.contains(Seg.Para(0, null, "Check the release notes")))
        assertTrue(segs.none { it is Seg.Code })
        assertEquals(listOf("1.", "2.", "3."), segs.filterIsInstance<Seg.Para>().mapNotNull { it.mark })
    }

    @Test fun wrappedRowsWithoutTheBoxEdge() {
        val screen = listOf("     Removing the downloads.", "", "     ┆ rm -f /tmp/a.zip·", "     ┆ /tmp/b.zip; ls /tmp | head").joinToString("\n")
        val segs = Reflow.parse(screen, grok = true)
        assertEquals("Removing the downloads.", segs.filterIsInstance<Seg.Para>().single().text)
        assertEquals(listOf("rm -f /tmp/a.zip /tmp/b.zip; ls /tmp | head"), segs.filterIsInstance<Seg.Code>().single().lines)
    }

    @Test fun liveIdleScreenCompactionNoticeIsQuiet() {
        val txt = resource("/screens/grok/live-2026-11-21/idle-compacted.txt")
        val segs = Reflow.parse(txt, grok = true)
        assertTrue(segs.contains(Seg.Para(0, null, "Context compacted: 206.1k → 29.8k tokens (114.0s)", detail = true)))
        assertTrue(segs.contains(Seg.Para(0, null, "Worked for 17m33s", detail = true)))
        prose(segs).forEach { assertFalse(it, leaks.containsMatchIn(it)) }
        assertTrue(segs.none { it is Seg.Code })
        val ansi = resource("/screens/grok/live-2026-11-21/idle-compacted.ansi")
        assertEquals(dump(segs), dump(Reflow.parse(forPhone(ansi), grok = true)))
    }

    @Test fun billsExampleMatchesItsSavedChat() =
        matches(Reflow.parse(billExample, grok = true), "/screens/grok/live-2026-11-21/bill-example.chat", "grok-bill-example-chat.txt")

    @Test fun wrappedRowsOnlyChangeGrok() {
        // Without the Grok flag the rows read as before.
        assertTrue(Reflow.parse(billExample).filterIsInstance<Seg.Para>().any { "┆" in it.text })
    }

    // The question card for the same screens: Bill's example is how its card read before 2.1.3, the
    // title with every gutter row joined on ("Retry removal of temporary CI files ┆ rm -f ...· ┆ ...").

    @Test fun billsExampleCardIsTitleThenOneCommand() {
        val p = checkNotNull(detectPrompt(billExample, blocked = true, agent = "grok"))
        val parts = p.shownParts()
        assertEquals("Retry removal of temporary CI files", parts.heading)
        assertEquals("rm -f /tmp/hd-ui-attempt1.log /tmp/hd-ui-attempt1.err /tmp/hd-ci-a.zip /tmp/hd-ci-b.zip; " +
            "rmdir /tmp/hd-ci-a /tmp/hd-ci-b 2>/dev/null; ls /tmp/hd-ui-attempt1.log /tmp/hd-ci-a.zip 2>&1 | head", parts.command)
        assertTrue(parts.details.isEmpty())
        for (t in listOf(p.question) + parts.blocks.map { it.text }) {
            assertFalse(t, Regex("""[┆┊╎╏┃]|·(?:\s|$)""").containsMatchIn(t))
        }
        assertEquals(4, p.choices.size)
    }

    @Test fun capturedMultiRowCardIsOneCommand() {
        val parts = checkNotNull(detectPrompt(multirow, blocked = true, agent = "grok")).shownParts()
        assertEquals("List HerdrDroid source files and confirm commit", parts.heading)
        assertEquals(segsCommand(multirow), parts.command)
        assertTrue(parts.details.isEmpty())
    }

    private fun segsCommand(screen: String) = Reflow.parse(screen, grok = true).filterIsInstance<Seg.Code>().single().lines.single()
}
