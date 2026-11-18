package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.9: how each agent's question is laid out (Bill: "the arrows before and after the checkbox
 * list of questions is weird. Can you improve the formatting? ... Format questions from other
 * agents nicely too"). Claude's multi-question dialog is recreated from the text Bill saw and the
 * dialog's layout (rule, tab bar, question, options with descriptions, footer).
 */
class QuestionFormatTest {
    private val webSearch = """
        ┃  Web Search
        ┃  Allow OpenCode to search the web for current information?
        ┃  1. Allow search via Example
        ┃  2. Choose another provider
        ┃  3. Disable web search
        ┃  ↑↓ select  enter submit  esc dismiss
        ┃  ~/sample/project
    """.trimIndent()

    @Test fun openCodeWebSearchOffersDirectNumberKeysWithoutAHighlight() {
        val prompt = detectPrompt(webSearch, blocked = true, agent = "opencode-ursinus")!!
        assertEquals("Allow OpenCode to search the web for current information?", prompt.question)
        assertEquals(listOf("Allow search via Example", "Choose another provider", "Disable web search"), prompt.choices.map { it.label })
        assertEquals(listOf("1", "2", "3"), prompt.choices.map { it.keys })
        assertTrue(prompt.choices.none { it.pressEnter || menuKeys(prompt, it) != null })
    }

    @Test fun oldWebSearchPromptDoesNotBecomeALiveCard() {
        val movedOn = "$webSearch\n┃  Search complete.\n┃  ❯ "
        assertNull(detectPrompt(movedOn, blocked = true, agent = "opencode-ursinus"))
    }

    private val marks = listOf("│", "┃", "╭", "╰", "›", "❯", "◆", "(●)", "(○)", "←", "→", "☐", "☒", "✔")

    private fun clean(parts: QuestionParts) {
        parts.blocks.forEach { b -> marks.forEach { m -> assertFalse("'$m' in ${b.text}", b.text.contains(m)) } }
        parts.tabs.forEach { t -> marks.forEach { m -> assertFalse("'$m' in ${t.title}", t.title.contains(m)) } }
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

    @Test fun claudeMultiQuestionDialogBecomesSteps() {
        val p = detectPrompt(claudeMulti, blocked = false)!!
        assertEquals(listOf("Close it", "Keep it open", "Type something."), p.choices.map { it.label })
        val parts = p.shownParts()
        assertEquals(listOf("Close 537", "Stop hook", "Revise 329", "Markup line", "Submit"), parts.tabs.map { it.title })
        assertEquals(listOf(true, false, false, false, false), parts.tabs.map { it.current })
        assertTrue(parts.tabs.last().submit)
        assertEquals("Question 1 of 4", parts.step)
        assertEquals("Close residue 537 (the duplicate stop hook entry)?", parts.heading)
        assertEquals(listOf("Both differences were repaired in the working copy, so closing it only updates the tracker."), parts.details)
        assertNull(parts.command)
        // The text used for notifications has no tab bar in it.
        assertEquals("Close residue 537 (the duplicate stop hook entry)? Both differences were repaired in the working copy, so closing it only updates the tracker.", p.question)
        clean(parts)
    }

    @Test fun answeredQuestionsAreCheckedAndTheNextIsCurrent() {
        val p = detectPrompt(claudeMulti.replace("☐ Close 537", "☒ Close 537").replace("☐ Stop hook", "☒ Stop hook"), blocked = false)!!
        val parts = p.shownParts()
        assertEquals(listOf(true, true, false, false, false), parts.tabs.map { it.done })
        assertEquals("Revise 329", parts.tabs.single { it.current }.title)
        assertEquals("Question 3 of 4", parts.step)
    }

    @Test fun allAnsweredMeansSubmit() {
        val bar = parseTabBar("←  ☒ Close 537  ☒ Stop hook  ☒ Revise 329  ☒ Markup line  ✔ Submit  →")!!.first
        assertTrue(bar.single { it.current }.submit)
        assertEquals("Review and submit (4 questions)", QuestionParts(bar, emptyList()).step)
    }

    @Test fun reviewStepKeepsTheStepsAndPairsTheAnswers() {
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
        val parts = detectPrompt(review, blocked = false)!!.shownParts()
        assertEquals("Review and submit (4 questions)", parts.step)
        assertEquals("Ready to submit your answers?", parts.heading)
        assertEquals(listOf("Review your answers", "Close residue 537?: Close it", "Which markup line should stay?: The first"), parts.details)
        clean(parts)
    }

    @Test fun barMergedIntoTheTextAsBillSawIt() {
        val parts = questionParts(listOf(QLine("← ☐ Close 537 ☐ Stop hook ☐ Revise 329 ☐ Markup line ✔ Submit → Close residue 537 (...)? Both differences were repaired.")))
        assertEquals(5, parts.tabs.size)
        assertEquals("Close residue 537 (...)?", parts.heading)
        assertEquals(listOf("Both differences were repaired."), parts.details)
        clean(parts)
    }

    @Test fun oneQuestionHasNoSteps() {
        val p = detectPrompt(" Do you want to proceed?\n ❯ 1. Yes\n   2. No\n Enter to select · Esc to cancel", blocked = true)!!
        assertTrue(p.shownParts().tabs.isEmpty())
        assertNull(p.shownParts().step)
        assertEquals("Do you want to proceed?", p.shownParts().heading)
    }

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

    @Test fun claudeBashCard() {
        val parts = detectPrompt(claudeBash, blocked = true)!!.shownParts()
        assertEquals("Do you want to proceed?", parts.heading)
        assertEquals("mkdir -p sub && date > sub/now.txt", parts.command)
        assertEquals(listOf("Create the folder and write the date"), parts.details)
        clean(parts)
    }

    @Test fun codexCard() {
        val codex = """
  Would you like to run the following command?
  Environment: local
  Reason: May I rerun this setup check outside the sandbox because sandbox restrictions prevented it from running normally?
  ${'$'} bill-pane-send.py --list | head -2
› 1. Yes, proceed (y)
  2. Yes, and don't ask again for commands that start with `bill-pane-send.py --list` (p)
  3. No, and tell Codex what to do differently (esc)
  Press enter to confirm or esc to cancel
""".trimIndent()
        val parts = detectPrompt(codex, blocked = false)!!.shownParts()
        assertEquals("Would you like to run the following command?", parts.heading)
        assertEquals("bill-pane-send.py --list | head -2", parts.command)
        assertEquals(listOf("Environment: local", "Reason: May I rerun this setup check outside the sandbox because sandbox restrictions prevented it from running normally?"), parts.details)
        assertEquals(QKind.HEADING, parts.blocks[0].kind)
        assertEquals(QKind.COMMAND, parts.blocks[1].kind)
        clean(parts)
    }

    @Test fun grokCard() {
        val grok = """
   ◆ Check SSH to devbox in batch mode… 58s                                     2m46s ⇣61.9k [↓][stop]
  ┃
  ┃  ›  Check SSH to devbox in batch mode
  ┃  │ ssh -o BatchMode=yes -o ConnectTimeout=5 user@192.0.2.26 true; echo "EXIT:${'$'}?"
  ┃  ← → narrow scope
  ┃
  ┃  1 (○) Yes, and don't ask again for anything (always-approve mode)
  ┃  2 (●) Yes, proceed
  ┃  3 (○) No, reject (type to add feedback)
  ┃
  1/3:select  │  Tab:next option  │  Ctrl+o:always-approve  │  Ctrl+c:cancel  │  Esc:scrollback
""".trimIndent()
        val parts = detectPrompt(grok, blocked = true)!!.shownParts()
        assertEquals("Check SSH to devbox in batch mode", parts.heading)
        assertEquals("ssh -o BatchMode=yes -o ConnectTimeout=5 user@192.0.2.26 true; echo \"EXIT:\$?\"", parts.command)
        assertTrue(parts.details.isEmpty())
        clean(parts)
    }

    @Test fun copilotCard() {
        val copilot = """
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
        val p = detectPrompt(copilot, blocked = false)!!
        val parts = p.shownParts()
        assertEquals("Do you want to run this command?", parts.heading)
        assertEquals("mkdir -p sub && date > sub/now.txt", parts.command)
        clean(parts)
    }

    @Test fun antigravityCards() {
        val file = """
● Running (ls ~/.agents/skills | head -3) (ctrl+o to expand)
File access
─────────────────────────────────────────────────────────────────────────────
Read: /home/user/agents/AGENTS.md
Reason: outside workspace
Allow access to this file?
> 1. Yes, allow access
  2. Yes, and always allow non-workspace access
  3. No, deny access
  ↑/↓ Navigate
esc to cancel                                                       Gemini 4 Argon · medium
""".trimIndent()
        val f = detectPrompt(file, blocked = false)!!.shownParts()
        assertEquals("Allow access to this file?", f.heading)
        assertEquals(listOf("Read: /home/user/agents/AGENTS.md", "Reason: outside workspace"), f.details)
        clean(f)

        val cmd = """
Command
──────────

Requesting permission for:
   ssh user@192.0.2.26 true

Run this command?
> 1. Yes, run command
  2. Yes, and always allow in this conversation for commands that start with 'ssh user@192.0.2.26 true'
  3. No, cancel

  ↑/↓ Navigate · tab Amend · ctrl+g edit/expand command
esc to cancel
""".trimIndent()
        val c = detectPrompt(cmd, blocked = false)!!.shownParts()
        assertEquals("Run this command?", c.heading)
        assertEquals("ssh user@192.0.2.26 true", c.command)
        assertTrue(c.details.isEmpty())
        clean(c)
    }

    @Test fun kiroCard() {
        val kiro = """
↓ Shell mkdir -p sub && date > sub/now.txt

──────────────────────────────────────────────────────────────────────────
 shell requires approval
 ❯ Yes, single permission
   Trust, always allow in this session
   No (Tab to edit)
──────────────────────────────────────────────────────────────────────────
 esc to close · ↑↓ to navigate · ↵ to select · Tab to edit
""".trimIndent()
        val parts = detectPrompt(kiro, blocked = false)!!.shownParts()
        assertEquals("Shell requires approval", parts.heading)
        assertEquals("mkdir -p sub && date > sub/now.txt", parts.command)
        clean(parts)
    }

    @Test fun vibeCard() {
        val vibe = """
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
        val parts = detectPrompt(vibe, blocked = false)!!.shownParts()
        assertEquals("Permission for the file_system.bash tool (shell syntax requiring approval: redirection)", parts.heading)
        assertEquals("mkdir -p sub && date > sub/now.txt", parts.command)
        clean(parts)
    }
}
