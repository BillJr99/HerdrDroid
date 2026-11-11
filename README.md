# HerdrDroid

HerdrDroid is an Android app for talking to the coding agents in a [herdr](https://github.com/herdrdev/herdr) session from a phone. It signs in to the computer running herdr over SSH, with a key, a password, or both, and every tap becomes one `herdr` command on that computer. Nothing is installed on the computer and no new network port is opened, because the app uses the SSH server that is already there.

## What it does

- **Servers.** You add each computer yourself: a name, its address, its SSH port, your username, and the command that runs herdr there (for example `herdr` or `~/.local/bin/herdr`). Nothing is filled in for you, so the app works with any herdr server you can reach.
- **All tabs.** Every herdr pane is a card with its agent, its status (Needs you, Working, Done, Idle), the model it is running, and the last line it printed. A tab that is waiting on you has an orange border and sorts to the top. The list refreshes every three seconds.
- **Talking to an agent.** Tap a card to see the pane's recent output and send it a message. Messages to an agent go through `herdr agent prompt`, which submits text the way the agent expects. Other panes get the text and Enter.
- **Reading the screen.** The default view wraps ordinary lines at word boundaries and indents each continuation under the first word, so lists and indented code stay lined up. Tables, boxes and column-aligned output keep their exact layout in a strip that scrolls sideways, and full-width rules become thin dividers. Pinch with two fingers to change the text size, which is remembered for each server. **Terminal** shows the exact screen, unwrapped.
- **Keys.** Quick keys send Esc, Tab, the four arrows, Backspace and Enter, and Ctrl+C after you confirm. **Ctrl** and **Alt** work like a phone terminal's extra keys: tap one and it applies to the next key, so Ctrl then R sends Ctrl+R (a letter typed on the keyboard counts as the next key); long-press one and it stays held until you tap it again. They combine with each other and with the quick keys, for example Ctrl+Alt+↑.
- **An agent needs you.** When herdr reports an agent as blocked, or the bottom of the screen shows a numbered menu or a `(y/n)` question, the app shows the question with one button per answer. Each button says exactly which keys it sends.

The version number is shown beside the name on the server list and at the end of the status line on the tabs screen.

## Staying connected

When the phone sleeps or the app goes to the background, Android drops the SSH socket. HerdrDroid stops polling while it is in the background, and when it comes back it opens a fresh connection before polling again. Until then the top bar says **Reconnecting…** in grey; nothing else changes on screen.

- A dropped connection never shows a banner by itself. Previews, screen reads and refreshes that fail because of it are retried quietly, with 1, 2, 4 and then 8 seconds between tries.
- Keys, messages and answers you send while it's reconnecting wait in a queue (the top bar shows how many) and go out in order once the link is back.
- A banner appears only for something you have to act on: still unreachable after 15 seconds (with a **Retry** button), a refused sign-in, a changed host key, or a herdr command that itself failed.

## Models

Each card shows the pane's active model, read from the footer the agent draws at the bottom of its screen. Nothing is sent to the agent for this; it comes from the same reads as the card previews.

| Agent | Footer | Shown as |
|---|---|---|
| Codex | `GPT-6.1-Sol default · ~/agents/codex` | GPT-6.1-Sol · default |
| Antigravity | `? for shortcuts   Gemini 4 Argon · medium` | Gemini 4 Argon · medium |
| Grok | `╰──── Grok 4.7 (high) ─╯` | Grok 4.7 (high) |
| Hermes | `☤ gpt-6.1-sol │ ~124K/500K │ …` | gpt-6.1-sol |
| Copilot CLI | `… / commands · tab next tab   GPT-5.6 Terra` | GPT-5.6 Terra |
| OpenCode | `┃  Build · Big Pickle OpenCode` | Big Pickle OpenCode |
| pi | `0.0%/128k (auto)   (compat-llmproxy) llmproxy/flagship__free` | llmproxy/flagship__free |

Claude Code shows no model in its footer, so its model comes from `/status` ("Model: Default (Fable 5.2)") during its usage check, and appears once that check has run.

## Usage limits

The app keeps each agent's usage limits up to date by itself. Each agent's card shows its tightest limit in two short lines, such as "Week (all) [bar] 50%" and "resets Nov 12, 2:59am · 4m ago · +2 more", and **Usage** on the agent screen lists every limit with its full name. Settings has one switch to turn this off; with it off, the app never asks any agent about usage and shows nothing about it.

It works by agent, not by pane. On each check, the app reads which agent herdr reports in every pane, groups the panes by agent, and asks one pane of each kind. The answer applies to every pane running that agent, so moving an agent to another pane needs no setup. Every built-in check is a command the agent's own program answers, so none of them uses a model call:

| Agent | Command | Closed with |
|---|---|---|
| Claude Code | `/usage` | Esc |
| Codex | `/status` | (prints inline) |
| Copilot CLI | `/usage` | (prints inline) |
| Grok | `/usage` | Esc |
| Antigravity | `/usage` | Esc |
| Hermes | `/usage` ("Weekly: N% remaining … resets in …", once the session has made a call) | (prints inline) |

Grok draws its usage box over the conversation, so the parser reads each percentage only within the box column it sits in, and cuts the label and reset lines above and below it to that column. Status bars with many `│` fields (such as Hermes's footer) and approximate figures such as "~25%" are ignored.

pi and OpenCode have no usage limit to read (OpenCode's `/stats` is only a token history), so panes running them, plain shells, and any agent not in the table are never sent anything.

The checks are careful with the pane:

- They run about every 30 minutes per agent, and when you tap refresh (at most once every two minutes).
- They use a pane that herdr reports as idle or done. Claude Code is the exception: it opens `/usage` and `/status` even while it is working, without interrupting the work, so a working Claude pane is checked too (an idle one is still preferred). A pane that is not in front in herdr's own window at the computer is preferred; the one in front is used only when it is the only pane running that agent.
- The app types the command, then reads the input line. It presses Enter only if that line holds exactly the command. If anything else was already typed there, it erases what it typed, leaves the pane as it was, and tries again in five minutes.
- While a check runs in a pane, anything you send to that pane waits for it to finish.
- Esc, which closes Claude's, Grok's and Antigravity's usage screens, is sent only while that screen's own "Esc to cancel" or "Esc close" line is showing. If the screen didn't open, no Esc is sent, so it can never interrupt an agent that is working.
- A check that found no limits (for example a Hermes session that hasn't made a call yet) is tried again in five minutes.

## Security

- **Host key pinning.** The first time you connect to a server, the app shows the server's SHA-256 host key fingerprint and asks you to confirm it. After that it refuses to connect if the fingerprint changes. To check the fingerprint on the computer itself, run `ssh-keyscan localhost | ssh-keygen -lf -`.
- **Keys.** Turn on **Use an SSH key** for a server and either make a key on the phone or import a key file. A key made on the phone is an ECDSA P-256 key inside the Android Keystore: it can sign but can never be read or copied out (the Keystore has no Ed25519 before Android 13). The app shows its `authorized_keys` line with Copy and Share buttons. An imported key file, and its passphrase if it has one, are encrypted like a password. With the switch off, the app never offers a key to that server.
- **Key, then password.** With a key turned on, the app offers the key first and the saved password second, so a server that turns the key down still lets you in. If there's no saved password, it asks for one.
- **Passwords.** A saved password is encrypted with an AES key that stays in the Android Keystore. You can also ask for a fingerprint or the screen lock before the saved password or key is used. The password is sent only to the server it belongs to.
- **What a login can do.** A password login is a full shell for that user. The app only runs `herdr` commands, but anyone who can use your unlocked phone and this app can type into every pane. Keep the phone locked, and prefer servers on your own network or a VPN.

## How each action maps to herdr

| In the app | On the computer |
|---|---|
| All tabs | `herdr pane list` |
| A card's last line | `herdr pane read <pane> --source recent --lines 8` |
| A tab's screen | `herdr pane read <pane> --source recent --lines 200` |
| Send a message to an agent | `herdr agent prompt <pane> <text>` |
| Send a message to another pane | `herdr pane send-text <pane> <text>`, then `herdr pane send-keys <pane> enter` |
| Quick keys | `herdr pane send-keys <pane> esc` (or `tab`, `left`, `up`, `down`, `right`, `backspace`, `enter`, `ctrl+c`) |
| Ctrl or Alt held | `herdr pane send-keys <pane> ctrl+r` (or `alt+b`, `ctrl+alt+up`, …) |
| A usage check | `herdr pane send-text <pane> /usage`, `herdr pane read …`, then `send-keys enter` and `esc`, or `backspace` to undo |
| Answer buttons | `herdr pane send-text <pane> <key>`, plus `enter` for a `(y/n)` question |

Every argument is single-quoted for the remote shell, so message text is passed as typed.

## Building

The build needs JDK 17, the Android SDK (platform 34, build-tools 34.0.0) and Gradle 8.9. `build-in-docker.sh` does the whole build in a throwaway `eclipse-temurin:17-jdk` container, keeping the SDK and Gradle caches in a `.cache` folder beside the source:

```bash
bash build-in-docker.sh /path/to/work-folder
```

The build runs the usage-parser tests first, then writes the signed APK to `<work-folder>/out/`. The app is signed with the sideload key in `keystore/`, so a newer build installs over an older one. Keep that file with the source: a build signed with a different key will not install over this one without uninstalling first.

The key's passwords are read from `keystore/signing.properties` (`storePassword`, `keyAlias`, `keyPassword`), which git ignores, so neither the build file nor the build script holds them.

## Layout

| File | What it holds |
|---|---|
| `app/src/main/java/.../ssh/HerdrSsh.kt` | The SSH connection, host key pinning, and running one command at a time |
| `app/src/main/java/.../herdr/Herdr.kt` | The herdr commands, pane parsing, question detection, and the guarded usage check |
| `app/src/main/java/.../herdr/Quota.kt` | The usage profile for each agent and the parser for their usage screens |
| `app/src/main/java/.../store/ServerStore.kt` | Saved servers, app settings, the Keystore encryption, and keys made on the phone |
| `app/src/main/java/.../AppViewModel.kt` | App state, the connect flow, reconnecting, the send queue, and the usage checks |
| `app/src/main/java/.../ui/` | The screens: servers, editing a server, settings, all tabs, and one agent |
| `app/src/test/java/.../QuotaTest.kt` | The parser run against each agent's captured usage screen |
