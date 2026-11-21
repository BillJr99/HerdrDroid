# Question cards: coverage

Every question or approval prompt the supported agents can show, as HerdrDroid reads it (1.4.9). Each row is a screen in the screen library (`app/src/test/resources/screens/<agent>/`), checked on every build by `ScreenLibraryTest` (the options) and `CardCoverageTest` (keys, cautions, refusals). "Captured" screens were read from a live pane; "from the program" screens were built from the exact strings in the agent's own program, with demo content, where there is no live capture yet.

For every card: the options are read in screen order; the highlighted one is the one Enter picks; an option that grants standing permission carries a caution; a refusal ("No", "Deny", "Reject", "Never allow", a deny or block rule) never does; an option marked "(esc)", or a refusal where Esc rejects, sends Esc. A card counts only at the bottom of the screen with nothing of the agent's own after it, so an answered card left in the scrollback is not shown again (1.4.9), and a card answered from the phone is not answered twice (1.4.8).

Claude's question tabs bound the AskUserQuestion card: options are read only below that bar, and conversation after its footer makes it an old card. An echoed numbered message alone supplies no choices. If the dialog is visible but its selected option cannot be read, the app shows the screen fallback instead of borrowing a list from the conversation. The session-log reader takes options from each tool call's JSON and matches results by call id; user-message text is not parsed as options.

Claude Code 2.1.329 (2.1.1): Left on an empty composer ("← for agents") moves Claude's session to the background, and the terminal then shows its sessions list ("Needs input", "1 awaiting input") instead of the session. A question asked then has no dialog on screen, so Chat showed it from the session log with no card and the terminal did not show it. The app now reads that list as its own card: it says the session is in the background, offers **Return to the session (Esc)** (the list's own hint: "esc returns to it"), sends nothing until tapped, and keeps the open question in Chat. Once the session is back, its dialog becomes the usual card. While the list is open, messages wait instead of being typed into it, where they would start a new session. If Claude holds a question back behind a draft ("Claude has a question for you. It shows once you send or clear what you're typing."), the draft card says so. The 2.1.329 dialogs and the sessions list are captured under `screens/claude/captured-2.1.329/`, rendered against a mock API with neutral text; the draft cue is taken from the 2.1.329 program, not yet captured.

When Claude displays a checkbox question's **Next** or **Submit** button, Chat shows it as a separate row after the checkboxes. Tapping a checkbox only toggles it. Tapping **Next** or **Submit** moves to that terminal button and presses Enter; if Claude opens a review, **Submit answers** there takes a separate tap. Chat does not infer a button from the Submit tab or keyboard hints alone. The checkbox fixture is reconstructed from Claude Code 2.1.326's renderer and input handler with sample labels, not captured from a live pane.

Card layout (2.1.1, Bill: "text in question cards could be formatted in a more friendly way it's kind of a wall of text and code right now"): the question shows bold and first, with a short lead-in above it ("Question 1/2  Database") as a caption. Paragraphs wrap at the phone's width, lists show as lists, and "Reason:" style lines show their label in bold. Commands, scripts, paths and edits show in monospace blocks: a one-line command or path wraps, a script keeps its lines and scrolls sideways, an edit shows as a diff with its + and - lines coloured, and a block longer than ten lines shows its first eight with **Show all**. A plan ("# Title" over a numbered list) shows as a title and a list. The line an agent draws under an option ("Mark 537 resolved now.") shows under that option's label, and a key hint such as "(esc)" reads quieter. This is display only: the layout finds each block's text on the pane's screen to get back the lines the parser joined, and when it can't, the block shows as before. The options, their keys and the answer checks are unchanged. **Show raw** under the question shows the parser's text exactly, and **Show terminal** shows the screen. `CardFormatTest` checks the layout of every library card against `app/src/test/resources/card-format/golden.txt`, and checks that the layout keeps every character of the parsed text.

| Agent | Card | Source | Options | Answered by | Standing permission | Esc |
|---|---|---|---|---|---|---|
| Claude Code | bash permission | captured | 3 | ↑/↓ + Enter, Enter, Esc | 1 | yes |
| Claude Code | multi question | captured | 3 | ↑/↓ + Enter, Enter | 0 | no |
| Claude Code | file edit create overwrite approval with | from the program | 3 | ↑/↓ + Enter, Enter, Esc | 1 | yes |
| Claude Code | bash approval scope variants beyond the | from the program | 3 | ↑/↓ + Enter, Enter, Esc | 1 | yes |
| Claude Code | webfetch domain approval | from the program | 3 | ↑/↓ + Enter, Enter, Esc | 1 | yes |
| Claude Code | sandbox network request | from the program | 3 | ↑/↓ + Enter, Enter, Esc | 1 | yes |
| Claude Code | mcp tool approval | from the program | 3 | ↑/↓ + Enter, Enter, Esc | 1 | yes |
| Claude Code | use skill approval | from the program | 3 | ↑/↓ + Enter, Enter, Esc | 1 | yes |
| Claude Code | exit plan mode ready to code | from the program | 4 | ↑/↓ + Enter, Enter | 2 | no |
| Claude Code | enter plan mode | from the program | 2 | ↑/↓ + Enter, Enter | 0 | no |
| Claude Code | askuserquestion multi select review and | from the program | 2 | ↑/↓ + Enter, Enter | 0 | no |
| Claude Code | askuserquestion multi select review and  2 | from the program | 4 | ↑/↓ + Enter, ↑/↓ + Space (toggle) | 0 | no |
| Claude Code | trust this folder | from the program | 2 | ↑/↓ + Enter, Enter | 1 | no |
| Claude Code | custom api key detected | from the program | 2 | ↑/↓ + Enter, Enter | 0 | no |
| Claude Code | usage and model limit dialogs | from the program | 4 | ↑/↓ + Enter, Enter | 0 | no |
| Codex | run command | captured | 3 | its letter shortcut, Esc | 1 | yes |
| Codex | edit approval | captured | 3 | its letter shortcut, Esc | 1 | yes |
| Codex | run command session only and skip varian | from the program | 4 | its letter shortcut, Esc | 1 | yes |
| Codex | network access real wording | from the program | 5 | its letter shortcut, Esc | 2 | yes |
| Codex | mcp app tool approval | from the program | 4 | ↑/↓ + Enter, Enter | 2 | no |
| Codex | grant additional permissions | from the program | 3 | its letter shortcut, Esc | 1 | yes |
| Codex | request user input multi question | from the program | 3 | ↑/↓ + Enter, Enter | 0 | no |
| Codex | request user input: submit with unanswered questions | from the program | 0 in Chat | Terminal only | 0 | no |
| Codex | plan mode implement this plan | from the program | 3 | ↑/↓ + Enter, Enter | 0 | no |
| Codex | rate limit model switch and workspace li | from the program | 3 | ↑/↓ + Enter, Enter | 1 | no |
| Grok | privacy banner | captured | 2 | its letter | 0 | no |
| Grok | ssh permission | captured | 3 | ↑/↓ + Enter, Enter | 1 | no |
| Grok | python approval | captured | 4 | ↑/↓ + Enter, Enter | 1 | no |
| Grok | scan approval | captured | 4 | ↑/↓ + Enter, Enter | 1 | no |
| Grok | file edit approval | from the program | 4 | ↑/↓ + Enter, Enter | 2 | no |
| Grok | mcp tool approval with scope | from the program | 5 | ↑/↓ + Enter, Enter | 2 | no |
| Grok | question card ask user question | from the program | 3 | ↑/↓ + Enter, Enter | 0 | no |
| Grok | plan approval | from the program | 4 | its letter | 0 | no |
| Grok | trust this folder startup | from the program | 2 | its letter | 0 | no |
| GitHub Copilot CLI | run command | captured | 3 | ↑/↓ + Enter, Enter, Esc | 1 | yes |
| GitHub Copilot CLI | file create edit delete move | from the program | 3 | ↑/↓ + Enter, Enter, Esc | 1 | yes |
| GitHub Copilot CLI | shell session approval and sandbox bypas | from the program | 3 | ↑/↓ + Enter, Enter, Esc | 1 | yes |
| GitHub Copilot CLI | mcp tool | from the program | 4 | ↑/↓ + Enter, Enter, Esc | 2 | yes |
| GitHub Copilot CLI | question ask user | from the program | 3 | ↑/↓ + Enter, Enter | 0 | no |
| GitHub Copilot CLI | exit plan mode | from the program | 4 | ↑/↓ + Enter, Enter | 1 | no |
| Antigravity | run command | from the program | 4 | ↑/↓ + Enter, Enter | 2 | no |
| Kiro CLI | shell approval | captured | 3 | ↑/↓ + Enter, Enter | 1 | no |
| Kiro CLI | default page label set | from the program | 4 | ↑/↓ + Enter, Enter | 1 | no |
| Kiro CLI | trust options page and scope page | from the program | 3 | ↑/↓ + Enter, Enter | 3 | no |
| Mistral Vibe | bash permission | captured | 4 | ↑/↓ + Enter, Enter, Esc | 2 | yes |
| Mistral Vibe | trust this folder | from the program | 2 | Enter, Esc | 0 | yes |
| Mistral Vibe | ask user question | from the program | 3 | ↑/↓ + Enter, Enter | 0 | no |
| pi | rm rsync delete gate local extension pi | from the program | 2 | ↑/↓ + Enter, Enter | 0 | no |
| pi | project trust | from the program | 5 | ↑/↓ + Enter, Enter | 3 | no |
| Oh My Pi | plan review exit plan mode | from the program | 5 | ↑/↓ + Enter, Enter | 0 | no |
| Oh My Pi | ask dialog questions tabs multi select | from the program | 4 | ↑/↓ + Enter, Enter | 0 | no |
| OpenCode | permission prompt all tools | from the program | 3 | ←/→ + Enter, Enter, Esc | 1 | yes |
| OpenCode | permission prompt all tools 2 | from the program | 3 | ←/→ + Enter, Enter, Esc | 1 | yes |
| OpenCode | always allow confirmation page | from the program | 2 | ←/→ + Enter, Enter | 0 | no |
| OpenCode | reject with feedback subagent requests | from the program | free text | the screen and a typed answer | 0 | yes |
| OpenCode | question tool | from the program | 3 | ↑/↓ + Enter, Enter | 0 | no |
| Hermes | dangerous command approval | from the program | 4 | ↑/↓ + Enter, Enter | 2 | no |
| Hermes | clarify multi question batch | from the program | 3 | ↑/↓ + Space (toggle) | 0 | no |
| Hermes | slash command confirm | from the program | 3 | ↑/↓ + Enter, Enter | 1 | no |
| Muse Code | command approval | captured | 3 | its letter shortcut, Esc | 1 | yes |
| Muse Code | proceed approval | captured | 2 | its letter shortcut, Esc | 0 | yes |
| Muse Code | other approval kinds | from the program | 4 | its letter shortcut, Esc | 2 | yes |
| Muse Code | workspace trust | from the program | 2 | ↑/↓ + Enter, Enter | 1 | no |

Not verified on a live pane: every "from the program" row. Antigravity's layouts are inferred (its program gives the option text, not the row layout); OpenCode's and Vibe's button rows assume the first button has the focus when the prompt opens, since the focus is drawn only in colour.
