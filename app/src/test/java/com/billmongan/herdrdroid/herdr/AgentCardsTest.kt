package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Approval cards from every agent that draws one, captured on devbox2 on 2026-11-13 (paths
 * shortened). Copilot, Kiro and Vibe were run in throwaway folders and every card was rejected;
 * Codex's and Antigravity's came from the live panes. pi, Oh My Pi, vanilla OpenCode and Hermes ran
 * the same command without asking, so they draw no card. Claude's and Grok's are in GrokQuestionTest
 * and the older tests. [blocked] is false where herdr may not report the agent as blocked.
 */
class AgentCardsTest {
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

    private val agy = """
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

    private val copilot = """
│ Create timestamp file                                                                                                                              │
│ ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────── │
│ ╭────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────╮ │
│ │ mkdir -p sub && date > sub/now.txt                                                                                                             │ │
│ ╰────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────╯ │
│                                                                                                                                                    │
│ Do you want to run this command?                                                                                                                   │
│                                                                                                                                                    │
│ ❯ 1. Yes                                                                                                                                           │
│   2. Yes, and don't ask again for `mkdir` in this directory (/tmp/t/copilot)                                                          │
│   3. No, and tell Copilot what to do differently (Esc to stop)                                                                                     │
│                                                                                                                                                    │
│ ↑/↓ to navigate · enter to select · esc to cancel                                                                                                  │
╰────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────╯
""".trimIndent()

    private val copilotTrust = """
│ the rest of this session.                                                                                                                          │
│                                                                                                                                                    │
│ Git commands run in this folder may use configuration from the enclosing repository and execute programs configured by that repository.            │
│                                                                                                                                                    │
│ Do you trust the files in this folder?                                                                                                             │
│                                                                                                                                                    │
│ ❯ 1. Yes                                                                                                                                           │
│   2. Yes, and remember this folder for future sessions                                                                                             │
│   3. No (Esc)                                                                                                                                      │
│                                                                                                                                                    │
│ ↑/↓ to navigate · enter to select · esc to cancel                                                                                                  │
╰────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────╯
""".trimIndent()

    private val kiro = """
↓ Shell mkdir -p sub && date > sub/now.txt

──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 shell requires approval
 ❯ Yes, single permission
   Trust, always allow in this session
   No (Tab to edit)
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 esc to close · ↑↓ to navigate · ↵ to select · Tab to edit
""".trimIndent()

    private val vibe = """

⠉⠁ Running command… (3s Esc/Ctrl+C to interrupt)
┌────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ Permission for the file_system.bash tool (shell syntax requiring approval: redirection)                                                            │
│ mkdir -p sub && date > sub/now.txt                                                                                                                 │
│                                                                                                                                                    │
│   1. Allow once                                                                                                                                    │
│   2. Allow for remainder of this session                                                                                                           │
│   3. Always allow                                                                                                                                  │
│   4. Deny                                                                                                                                          │
│                                                                                                                                                    │
│ ↑↓/jk navigate  Enter select  Esc reject                                                                                                           │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
/tmp/t/vibe                                                                                       usage ▏      0% · ctx ▍      11.8k/202k
""".trimIndent()

    @Test fun codexCard() {
        val p = detectPrompt(codex, blocked = false)!!
        assertEquals(3, p.choices.size)
        assertEquals("1", p.selected)
        assertTrue(p.question, p.question.startsWith("Would you like to run the following command?"))
        // Each option's own key, named at the end of its label (1.4.2): "(y)", "(p)", "(esc)".
        assertEquals(listOf("y"), menuKeys(p, p.choices[0]))
        assertEquals(listOf("p"), menuKeys(p, p.choices[1]))
        assertEquals(listOf("esc"), menuKeys(p, p.choices[2]))
    }

    @Test fun antigravityCard() {
        val p = detectPrompt(agy, blocked = false)!!
        assertEquals(listOf("Yes, allow access", "Yes, and always allow non-workspace access", "No, deny access"), p.choices.map { it.label })
        assertEquals("1", p.selected)
        assertTrue(p.question, p.question.endsWith("Allow access to this file?"))
        assertEquals(listOf("down", "down", "enter"), menuKeys(p, p.choices[2]))
    }

    @Test fun copilotCard() {
        val p = detectPrompt(copilot, blocked = false)!!
        assertEquals(3, p.choices.size)
        assertEquals("1", p.selected)
        // 1.2.5: the command in the box above is part of the question.
        assertTrue(p.question, p.question.endsWith("Do you want to run this command?"))
        assertTrue(p.question, p.question.contains("mkdir -p sub && date > sub/now.txt"))
        // Enter on "No, and tell Copilot ... (Esc to stop)" does nothing in Copilot; Esc cancels.
        assertEquals(listOf("esc"), menuKeys(p, p.choices[2]))
        assertEquals(listOf("enter"), menuKeys(p, p.choices[0]))
    }

    @Test fun copilotFolderTrustCard() {
        val p = detectPrompt(copilotTrust, blocked = false)!!
        assertEquals(3, p.choices.size)
        assertEquals("Yes", p.choices[0].label)
        assertEquals(listOf("esc"), menuKeys(p, p.choices[2]))
    }

    @Test fun kiroCardHasNoNumbers() {
        val p = detectPrompt(kiro, blocked = false)!!
        assertEquals(listOf("Yes, single permission", "Trust, always allow in this session", "No (Tab to edit)"), p.choices.map { it.label })
        assertEquals("shell requires approval: mkdir -p sub && date > sub/now.txt", p.question)
        // Checked in Kiro 3.3: Down, Down, Enter rejected the command.
        assertEquals(listOf("down", "down", "enter"), menuKeys(p, p.choices[2]))
    }

    // Antigravity's command card, live on 2026-11-13: blank lines separate the command from the
    // question, which before 1.2.5 left the question box with only "Run this command?".
    private val agyCommand = """
● Ran 3 commands (bill-pane-send.py --list | head -1 · bill-agent-tasks.py list · ssh bill
○ Exploring 1 file (AGENTS.md) (ctrl+o to expand)

Command
──────────

Requesting permission for:
   ssh user@192.0.2.26 true

Run this command?
> 1. Yes, run command
  2. Yes, and always allow in this conversation for commands that start with 'ssh user@192.0.2.26 true'
  3. Yes, and always allow for commands that start with 'ssh user@192.0.2.26 true' (Persist to settings.json)
  4. No, cancel

  ↑/↓ Navigate · tab Amend · ctrl+g edit/expand command
esc to cancel
""".trimIndent()

    @Test fun antigravityCommandCardShowsTheCommand() {
        val p = detectPrompt(agyCommand, blocked = false)!!
        assertEquals(4, p.choices.size)
        assertEquals("Requesting permission for: ssh user@192.0.2.26 true Run this command?", p.question)
        assertEquals(listOf("down", "down", "down", "enter"), menuKeys(p, p.choices[3]))
    }

    @Test fun activityAboveACardIsNotPartOfTheQuestion() {
        val p = detectPrompt(codex.replace("  Would you like", "• Running bill-pane-send.py --list\n\n  Would you like"), blocked = true)!!
        assertTrue(p.question, p.question.startsWith("Would you like to run the following command?"))
    }

    @Test fun vibeCardHasNoMarker() {
        val p = detectPrompt(vibe, blocked = false)!!
        assertEquals(listOf("Allow once", "Allow for remainder of this session", "Always allow", "Deny"), p.choices.map { it.label })
        assertTrue(p.question, p.question.startsWith("Permission for the file_system.bash tool"))
        assertEquals("1", p.selected)
        assertEquals(listOf("enter"), menuKeys(p, p.choices[0]))
        assertEquals(listOf("down", "enter"), menuKeys(p, p.choices[1]))
        // Digits do nothing in Vibe and its highlight wraps; Esc rejects wherever the highlight is.
        assertEquals(listOf("esc"), menuKeys(p, p.choices[3]))
    }
}
