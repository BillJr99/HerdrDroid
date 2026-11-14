package com.billmongan.herdrdroid.transcript

import com.billmongan.herdrdroid.herdr.GROK_PRIVACY_PROMPT
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.detectPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 1.3.0: questions stay in the chat history as plain text, with the options and the answer (Bill:
 * "the question text and the proposed answers and my actual answer should remain as text in the
 * input output window as part of the chat history ... so I can scroll through and persist it").
 * The screens are the ones the 1.2.9 question-format tests use.
 */
class QuestionHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val marks = listOf("│", "┃", "╭", "╰", "›", "❯", "◆", "(●)", "(○)", "←", "→", "☐", "☒", "✔")
    private fun clean(q: QuestionItem) {
        val all = q.parts.flatMap { listOfNotNull(it.title, it.question, it.command) + it.details + it.options }
        all.forEach { t -> marks.forEach { m -> assertFalse("'$m' in $t", t.contains(m)) } }
    }

    private val claudeMulti = """
────────────────────────────────────────────────────────────────────────────────────────────
←  ☐ Close 537  ☐ Stop hook  ☐ Revise 329  ☐ Markup line  ✔ Submit  →

Close residue 537 (the duplicate stop hook entry)? Both differences were repaired in the
working copy, so closing it only updates the tracker.

❯ 1. Close it
     Mark 537 resolved now.
  2. Keep it open
     Leave it for a later pass.
  3. Type something.

Enter to select · Tab/Arrow keys to navigate · Esc to cancel
""".trimIndent()

    private val claudeBash = """
────────────────────────────────────────────────────────────────────────
 Bash command

   mkdir -p sub && date > sub/now.txt
   Create the folder and write the date

 Do you want to proceed?
 ❯ 1. Yes
   2. Yes, and don't ask again for mkdir commands in /tmp/t
   3. No, and tell Claude what to do differently (esc)

 Esc to cancel · Tab to amend
""".trimIndent()

    private val codex = """
  Would you like to run the following command?
  Environment: local
  Reason: May I rerun this setup check outside the sandbox because sandbox restrictions prevented it from running normally?
  ${'$'} bill-pane-send.py --list | head -2
› 1. Yes, proceed (y)
  2. Yes, and don't ask again for commands that start with `bill-pane-send.py --list` (p)
  3. No, and tell Codex what to do differently (esc)
  Press enter to confirm or esc to cancel
""".trimIndent()

    private val grok = """
   ◆ Check SSH to demo-box in batch mode… 58s                                     2m46s ⇣61.9k [↓][stop]
  ┃
  ┃  ›  Check SSH to demo-box in batch mode
  ┃  │ ssh -o BatchMode=yes -o ConnectTimeout=5 bill@192.0.2.26 true; echo "EXIT:${'$'}?"
  ┃  ← → narrow scope
  ┃
  ┃  1 (○) Yes, and don't ask again for anything (always-approve mode)
  ┃  2 (●) Yes, proceed
  ┃  3 (○) No, reject (type to add feedback)
  ┃
  1/3:select  │  Tab:next option  │  Ctrl+o:always-approve  │  Ctrl+c:cancel  │  Esc:scrollback
""".trimIndent()

    private val copilot = """
│ Create timestamp file                                                                │
│ ──────────────────────────────────────────────────────────────────────────────────── │
│ ╭──────────────────────────────────────────────────────────────────────────────────╮ │
│ │ mkdir -p sub && date > sub/now.txt                                               │ │
│ ╰──────────────────────────────────────────────────────────────────────────────────╯ │
│                                                                                      │
│ Do you want to run this command?                                                     │
│                                                                                      │
│ ❯ 1. Yes                                                                             │
│   2. Yes, and don't ask again for `mkdir` in this directory (/tmp/t/copilot)         │
│   3. No, and tell Copilot what to do differently (Esc to stop)                       │
│                                                                                      │
│ ↑/↓ to navigate · enter to select · esc to cancel                                    │
╰──────────────────────────────────────────────────────────────────────────────────────╯
""".trimIndent()

    private val antigravity = """
Command
──────────

Requesting permission for:
   ssh bill@192.0.2.26 true

Run this command?
> 1. Yes, run command
  2. Yes, and always allow in this conversation for commands that start with 'ssh bill@192.0.2.26 true'
  3. No, cancel

  ↑/↓ Navigate · tab Amend · ctrl+g edit/expand command
esc to cancel
""".trimIndent()

    private val kiro = """
↓ Shell mkdir -p sub && date > sub/now.txt

──────────────────────────────────────────────────────────────────────────
 shell requires approval
 ❯ Yes, single permission
   Trust, always allow in this session
   No (Tab to edit)
──────────────────────────────────────────────────────────────────────────
 esc to close · ↑↓ to navigate · ↵ to select · Tab to edit
""".trimIndent()

    private val vibe = """
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ Permission for the file_system.bash tool (shell syntax requiring approval: redirection)      │
│ mkdir -p sub && date > sub/now.txt                                                           │
│                                                                                              │
│   1. Allow once                                                                              │
│   2. Allow for remainder of this session                                                     │
│   3. Always allow                                                                            │
│   4. Deny                                                                                    │
│                                                                                              │
│ ↑↓/jk navigate  Enter select  Esc reject                                                     │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
""".trimIndent()

    private fun prompt(screen: String, blocked: Boolean = true): Prompt = detectPrompt(screen, blocked)!!

    /** Seen, answered in the app, then gone from the screen twice. */
    private fun answeredIn(screen: String, pick: Int, agent: String): QuestionItem {
        val p = prompt(screen)
        var log = QuestionLog.seen(emptyList(), p, agent, "t1", 1_000)
        log = QuestionLog.answered(log, p, p.choices[pick].label)
        log = QuestionLog.gone(log, working = true, 2_000)
        assertEquals(1, log.size)
        return log.single()
    }

    @Test fun everyAgentsCardIsRecordedWithItsOptionsAndTheAnswer() {
        val cases = listOf(
            Triple(claudeBash, "Claude", "Do you want to proceed?"),
            Triple(codex, "Codex", "Would you like to run the following command?"),
            Triple(grok, "Grok", "Check SSH to demo-box in batch mode"),
            Triple(copilot, "Copilot", "Do you want to run this command?"),
            Triple(antigravity, "Antigravity", "Run this command?"),
            Triple(kiro, "Kiro", "Shell requires approval"),
            Triple(vibe, "Vibe", "Permission for the file_system.bash tool (shell syntax requiring approval: redirection)"),
        )
        for ((screen, agent, question) in cases) {
            val q = answeredIn(screen, 0, agent)
            val part = q.parts.single()
            assertEquals(agent, question, part.question)
            assertEquals(agent, prompt(screen).choices.map { it.label }, part.options)
            assertEquals(agent, part.options[0], part.answer)
            assertEquals(QState.ANSWERED, q.state)
            assertEquals("Question from $agent, answered", QuestionLog.label(q))
            clean(q)
        }
        assertEquals("mkdir -p sub && date > sub/now.txt", answeredIn(claudeBash, 0, "Claude").parts.single().command)
        assertEquals("bill-pane-send.py --list | head -2", answeredIn(codex, 0, "Codex").parts.single().command)
    }

    @Test fun grokPrivacyBannerIsRecorded() {
        var log = QuestionLog.seen(emptyList(), GROK_PRIVACY_PROMPT, "Grok", null, 1_000)
        log = QuestionLog.answered(log, GROK_PRIVACY_PROMPT, "Opt out")
        log = QuestionLog.gone(log, working = false, 2_000)
        val q = log.single()
        assertEquals(GROK_PRIVACY_PROMPT.choices.map { it.label }, q.parts.single().options)
        assertEquals("Opt out", q.parts.single().answer)
        assertEquals(QState.ANSWERED, q.state)
    }

    @Test fun claudeMultiQuestionDialogKeepsEachStepAndItsAnswer() {
        val step1 = prompt(claudeMulti, blocked = false)
        var log = QuestionLog.seen(emptyList(), step1, "Claude", "t1", 1_000)
        assertEquals(listOf("Close 537", "Stop hook", "Revise 329", "Markup line"), log.single().parts.map { it.title })
        assertEquals("Close residue 537 (the duplicate stop hook entry)?", log.single().parts[0].question)
        log = QuestionLog.answered(log, step1, "Close it")
        // The next step is the same dialog: the record is updated, not repeated.
        val step2 = prompt(claudeMulti.replace("☐ Close 537", "☒ Close 537")
            .replace("Close residue 537 (the duplicate stop hook entry)?", "Keep the stop hook?"), blocked = false)
        log = QuestionLog.seen(log, step2, "Claude", "t1", 1_500)
        assertEquals(1, log.size)
        val parts = log.single().parts
        assertEquals("Close it", parts[0].answer)
        assertEquals("Keep the stop hook?", parts[1].question)
        log = QuestionLog.answered(log, step2, "Keep it open")
        assertEquals("Keep it open", log.single().parts[1].answer)
        // The review step: Submit closes the dialog.
        val review = """
────────────────────────────────────────────────────────────────────────────────────────────
←  ☒ Close 537  ☒ Stop hook  ☒ Revise 329  ☒ Markup line  ✔ Submit  →

Review your answers

 ● Close residue 537?
   → Close it
 ● Which markup line should stay?
   → The first

Ready to submit your answers?

❯ 1. Submit answers
  2. Cancel

Enter to select · Tab/Arrow keys to navigate · Esc to cancel
""".trimIndent()
        val rp = prompt(review, blocked = false)
        log = QuestionLog.seen(log, rp, "Claude", "t1", 2_000)
        log = QuestionLog.answered(log, rp, "Submit answers")
        log = QuestionLog.gone(log, working = true, 3_000)
        val q = log.single()
        assertEquals(QState.ANSWERED, q.state)
        assertEquals("Submit answers", q.action)
        assertEquals("Close it", q.parts[0].answer)
        assertEquals("Question from Claude (4 questions), answered", QuestionLog.label(q))
        clean(q)
    }

    @Test fun answersOnTheReviewScreenFillStepsAnsweredInTheTerminal() {
        val review = prompt("""
←  ☒ Close 537  ☒ Stop hook  ✔ Submit  →

Review your answers

 ● Close 537
   → Close it

Ready to submit your answers?

❯ 1. Submit answers
  2. Cancel
""".trimIndent(), blocked = false)
        val q = QuestionLog.seen(emptyList(), review, "Claude", null, 1_000).single()
        assertEquals("Close it", q.parts.first { it.title == "Close 537" }.answer)
        assertNull(q.parts.first { it.title == "Stop hook" }.answer)
    }

    @Test fun goneWithoutAnAnswerFromHereIsAnsweredElsewhereOrDismissed() {
        val p = prompt(claudeBash)
        val open = QuestionLog.seen(emptyList(), p, "Claude", "t1", 1_000)
        val elsewhere = QuestionLog.gone(open, working = true, 2_000).single()
        assertEquals(QState.ELSEWHERE, elsewhere.state)
        assertTrue(elsewhere.parts.all { it.answer == null })
        assertEquals("Question from Claude, answered elsewhere", QuestionLog.label(elsewhere))
        val dismissed = QuestionLog.gone(open, working = false, 2_000).single()
        assertEquals(QState.DISMISSED, dismissed.state)
        assertTrue(dismissed.parts.all { it.answer == null })
    }

    @Test fun aTypedAnswerIsKept() {
        val p = prompt(claudeBash)
        var log = QuestionLog.seen(emptyList(), p, "Claude", "t1", 1_000)
        log = QuestionLog.answered(log, p, "make it sub2 instead")
        val q = QuestionLog.gone(log, working = true, 2_000).single()
        assertEquals("make it sub2 instead", q.parts.single().answer)
        assertTrue(QuestionLog.plain(q).contains("Your answer: make it sub2 instead"))
    }

    @Test fun theSameCardSeenAgainIsOneRecord() {
        val p = prompt(codex)
        var log = QuestionLog.seen(emptyList(), p, "Codex", "t1", 1_000)
        log = QuestionLog.seen(log, p, "Codex", "t2", 3_500)
        assertEquals(1, log.size)
        // A redraw right after it was settled is still the same one.
        log = QuestionLog.gone(log, working = true, 4_000)
        log = QuestionLog.seen(log, p, "Codex", "t2", 6_000)
        assertEquals(1, log.size)
        assertEquals(QState.OPEN, log.single().state)
        // Asked again later, it is a new question.
        log = QuestionLog.gone(log, working = true, 7_000)
        log = QuestionLog.seen(log, p, "Codex", "t9", 7_000 + QuestionLog.SAME_WINDOW_MS + 1)
        assertEquals(2, log.size)
        // A different question while one is open settles the first.
        log = QuestionLog.seen(log, prompt(claudeBash), "Claude", "t9", 99_000)
        assertEquals(3, log.size)
        assertEquals(QState.ELSEWHERE, log[1].state)
    }

    @Test fun questionsSitAfterTheItemTheyWereAskedAfter() {
        val items = listOf<Item>(UserItem("u1", "go"), TextItem("t1", "working on it"), TextItem("t2", "done"))
        val q1 = QuestionLog.seen(emptyList(), prompt(claudeBash), "Claude", "t1", 1_000)
        val q2 = QuestionLog.seen(emptyList(), prompt(codex), "Codex", null, 2_000)
        val shown = QuestionLog.withQuestions(items, q1 + q2)
        assertEquals(listOf("u1", "t1", q1.single().key, "t2", q2.single().key), shown.map { it.key })
        // A pane with no log (Antigravity, Kiro): just the questions, oldest first.
        assertEquals(listOf(q1.single().key, q2.single().key), QuestionLog.withQuestions(emptyList(), q2 + q1).map { it.key })
    }

    @Test fun claudesAskUserQuestionInItsLogMergesWithTheRecord() {
        val input = """{"questions":[{"question":"Which database should we use?","header":"Database","multiSelect":false,
            "options":[{"label":"SQLite","description":"file"},{"label":"Postgres","description":"server"}]}]}"""
        val tool = ToolItem("tool9", "c9", "AskUserQuestion", "AskUserQuestion", "other", input, null, null,
            "User has answered your questions: \"Which database should we use?\"=\"SQLite\". You can now continue.", ToolState.OK)
        val fromLog = QuestionLog.fromTool(tool)!!
        assertEquals("SQLite", fromLog.parts.single().answer)
        assertEquals(listOf("SQLite", "Postgres"), fromLog.parts.single().options)
        // The card the app saw for the same question, answered in the terminal (no answer here).
        val screen = """
 Database

 Which database should we use?

 ❯ 1. SQLite
     file
   2. Postgres
     server
   3. Type something.

 Enter to select · Esc to cancel
""".trimIndent()
        var log = QuestionLog.seen(emptyList(), prompt(screen, blocked = false), "Claude", "t1", 1_000)
        log = QuestionLog.gone(log, working = true, 2_000)
        val items = listOf<Item>(TextItem("t1", "let me ask"), tool, TextItem("t2", "ok, SQLite"))
        val shown = QuestionLog.withQuestions(items, log)
        val qs = shown.filterIsInstance<QuestionItem>()
        assertEquals(1, qs.size)
        assertEquals("SQLite", qs.single().parts.single().answer)
        assertEquals(QState.ANSWERED, qs.single().state)
        assertEquals(listOf("t1", "ask:tool9", "t2"), shown.map { it.key })
        assertTrue(shown.none { it is ToolItem })
    }

    @Test fun anUnreadableRecordedAnswerIsShownAsTheLogHasIt() {
        val tool = ToolItem("tool8", "c8", "AskUserQuestion", "AskUserQuestion", "other",
            """{"questions":[{"question":"Go on?","header":"Go","options":[{"label":"Yes"},{"label":"No"}]}]}""",
            null, null, "The user chose to continue.", ToolState.OK)
        val q = QuestionLog.fromTool(tool)!!
        assertEquals(QState.ANSWERED, q.state)
        assertNull(q.parts.single().answer)
        assertEquals("The user chose to continue.", q.recorded)
    }

    @Test fun keptOnThePhoneAndBack() {
        val p = prompt(claudeMulti, blocked = false)
        var log = QuestionLog.seen(emptyList(), p, "Claude", "t1", 1_000)
        log = QuestionLog.answered(log, p, "Close it")
        log = QuestionLog.gone(log, working = true, 2_000) + QuestionLog.seen(emptyList(), prompt(vibe), "Vibe", null, 3_000)
        assertEquals(log, QuestionLog.decode(QuestionLog.encode(log)))
        // In the sealed store, beside the chat.
        val seal: (ByteArray) -> ByteArray = { b -> b.reversedArray() }
        val store = ChatStore(tmp.root, seal, seal)
        store.saveQuestions("s1", "p1", log)
        assertEquals(log, store.loadQuestions("s1", "p1"))
        val onDisk = tmp.root.listFiles()!!.single { it.name.endsWith(".q") }.readBytes()
        assertFalse(String(onDisk).contains("Close it"))   // only sealed bytes on disk
        // A re-read of the agent's log (the chat file going) keeps the questions; a closed pane drops them.
        store.remove("s1", "p1", questions = false)
        assertNotNull(store.loadQuestions("s1", "p1"))
        store.keepOnly("s1", emptyList())
        assertNull(store.loadQuestions("s1", "p1"))
    }

    @Test fun onlyTheNewestQuestionsAreKept() {
        var log = emptyList<QuestionItem>()
        repeat(QuestionLog.MAX_QUESTIONS + 20) { i ->
            val p = Prompt("Question $i?", listOf(com.billmongan.herdrdroid.herdr.Choice("Yes", "1", false)))
            log = QuestionLog.gone(QuestionLog.seen(log, p, "Codex", null, i * 100_000L), working = true, i * 100_000L + 1)
        }
        val back = QuestionLog.decode(QuestionLog.encode(log))!!
        assertEquals(QuestionLog.MAX_QUESTIONS, back.size)
        assertEquals("Question ${QuestionLog.MAX_QUESTIONS + 19}?", back.last().parts.single().question)
    }
}
