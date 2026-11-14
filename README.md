# HerdrDroid

HerdrDroid is an Android app for talking to the coding agents in a [herdr](https://github.com/herdrdev/herdr) session from a phone. It signs in to the computer running herdr over SSH, with a key, a password, or both, and every tap becomes one `herdr` command on that computer. Nothing is installed on the computer and no new network port is opened, because the app uses the SSH server that is already there.

## What it does

- **Servers.** You add each computer yourself: a name, its address, its SSH port, your username, and the command that runs herdr there (for example `herdr` or `~/.local/bin/herdr`). Nothing is filled in for you, so the app works with any herdr server you can reach.
- **All tabs.** Every herdr pane is a card with its agent and working folder ("Claude · claude"), its status (Needs you, Working, Done, Idle), the model it is running, and the last line it printed. A tab that is waiting on you has an orange border and sorts to the top. The list refreshes every three seconds.
- **Talking to an agent.** Tap a card to see the pane's recent output and send it a message. Messages to an agent go through `herdr agent prompt`, which submits text the way the agent expects. Other panes get the text and Enter.
- **Reading the screen.** The default **Chat** view reads like a messaging app. The app joins the lines the agent wrapped at the terminal's width back into paragraphs, so they wrap again at the phone's width, with bullets and numbers hanging in their own column. Messages you sent appear as bubbles on the right. Tables, boxes and column-aligned output keep their exact layout in a monospace strip that scrolls sideways, and full-width rules become thin dividers. Scrollbars, right-hand clocks and side panels (such as OpenCode's) are left out, and the agent's own input box and status line move to a small footer. The replies use a reading font you choose in Settings: Inter (sans, the default), Source Serif, or JetBrains Mono. Pinch with two fingers to change the text size, which is remembered for each server. **Terminal** shows the exact screen, unwrapped, in JetBrains Mono.
- **Keys.** Two rows that always fit the screen: **Ctrl**, **Alt** and the four arrows (side by side, like a keyboard's arrow cluster) on top, then Esc, Tab, Backspace, Enter and Ctrl+C (after you confirm). **Keys** in the message box hides or shows them, and the choice is remembered. **Ctrl** and **Alt** work like a phone terminal's extra keys: tap one and it applies to the next key, so Ctrl then R sends Ctrl+R (a letter typed on the keyboard counts as the next key); long-press one and it stays held until you tap it again. They combine with each other and with the other keys, for example Ctrl+Alt+↑.
- **An agent needs you.** When herdr reports an agent as blocked, or the bottom of the screen shows a numbered menu or a `(y/n)` question, the app shows the question with one button per answer. Each button says exactly which keys it sends.
- **Answer types (1.4.0).** A card handles every kind of answer the agents offer:
  - a checkbox list (several options can be picked): each option toggles its box with Space, and Enter or the agent's Submit option sends them;
  - an "Other" or "Type something." option: the app moves to it, then sends what you type there;
  - options that grant standing permission ("always allow", "don't ask again", "trust", "for the rest of this session") carry a caution under the button.
- **The fallback card (1.4.0).** When the screen looks like a question (herdr says the agent waits, or a question footer such as "Enter to select · Esc to cancel" shows) but none of the app's parsers can read its options, the card shows that part of the screen as it is, with generic keys: the option numbers it can see, ↑, ↓, Space, Enter, Esc, Tab and "Type a different answer".
- **Never typing into a busy pane (1.4.0).** Before a message goes into a pane, the app reads the pane's input line. If text is already typed there at the computer, or someone typed in it in the last 4 seconds, the message is held instead of typed over it, and the app says why. Resend sends it anyway. A pane waiting on a question is answered, not typed into, so it is never held.
- **Debug bundle (1.4.0).** **Copy debug bundle** and **Share debug bundle** in an agent's menu put that pane's state on the phone's clipboard or share sheet: the app version, the agent and its version, status and scroll offset, the parser's decision on the last screen, the chat's cursor, the question records, the pane's recent diagnostic lines and its last screen. API keys, tokens, private keys, passwords, web addresses and e-mail addresses are taken out first. Nothing is written on the computer.
- **Questions stay in the chat (1.3.0).** Every question an agent asks is kept in that agent's chat history as plain text, not a card: who asked and how it ended ("answered", "answered elsewhere", "dismissed"), the question (each step of Claude's multi-question dialog in turn, with the step names), any command it shows, the options offered, and your answer marked "(your answer)", or "Your answer: ..." for one you typed. A question answered in the terminal or another app is kept as "answered elsewhere" with no answer filled in, unless the agent's own log recorded it (Claude's AskUserQuestion), in which case the log's answer is shown and the two are one entry. A question that went away while the agent did not go back to work is "dismissed". Agents without a log the app can read (Antigravity, Kiro) keep their questions above the screen view. The history is kept on the phone beside the chat, sealed with the same Keystore key, and goes when the pane closes or its agent changes; nothing is written on the computer. It is in the Markdown export too.

The version number is shown beside the name on the server list and at the end of the status line on the tabs screen.

## Staying connected

When the phone sleeps or the app goes to the background, Android drops the SSH socket. HerdrDroid stops polling while it is in the background, and when it comes back it opens a fresh connection before polling again. Until then the top bar says **Reconnecting…** in grey; nothing else changes on screen.

- A dropped connection never shows a banner by itself. Previews, screen reads and refreshes that fail because of it are retried quietly, with 1, 2, 4 and then 8 seconds between tries.
- Keys, messages and answers you send while it's reconnecting wait in a queue (the top bar shows how many) and go out in order once the link is back.
- A banner appears only for something you have to act on: still unreachable after 15 seconds (with a **Retry** button), a refused sign-in, a changed host key, or a herdr command that itself failed.

## Models

Terminal glyphs the app's fonts cannot draw are swapped for plain ones before anything is shown, so Claude Code's "⏵⏵ auto mode on" reads "» auto mode on" and "⏸ plan mode on" reads "‖ plan mode on" (`forPhone` in `herdr/Herdr.kt`). Without this, Android drew them from a fallback font, and they came out as a pause sign or an emoji.

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

The reasoning level is shown wherever an agent makes it available without a model call. Codex, Grok and Antigravity show it in their footers. Claude Code's footer has neither model nor effort, so during its usage check the app opens `/model` ("1. Default (recommended) ✔ Fable 5.2" and "◐ Medium effort") and closes it with Esc, which changes nothing. Hermes's effort comes from `/reasoning` ("Reasoning effort: medium"), which prints inline. Copilot and OpenCode show no reasoning level anywhere, so their cards show the model only. pi's footer shows "(auto)", which isn't clearly a reasoning level, so pi shows the model only too. These appear once each agent's first check has run.

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

- They run every 10 minutes for Claude Code, every 15 for Grok and Antigravity (whose usage screens close without a trace), and every 30 for the agents that print their usage into the conversation (Codex, Copilot, Hermes). Opening the card list runs any check that is due right away, and tapping refresh checks again (at most once every two minutes per agent). A card shows "Checking usage…" while its check runs.
- They use only a quiet pane (1.3.1, Bill: "herdrdroid occasionally scrolls a pane which causes the actual herdr session to scroll ... Could these occur only when the pane appears to be idle"). Typing the command is the only thing the app does by itself that moves a real pane; every other read uses `herdr pane read`, which moves nothing. A pane is quiet when herdr reports it idle or done (never working, Claude Code included, and never waiting on a question), its visible screen has not changed for 45 seconds (no one typed, scrolled or printed there), the app sent it nothing for 45 seconds, it is not scrolled back (herdr's own `scroll.offset_from_bottom` in `herdr pane list` is 0; where herdr doesn't report it, the bottom of what the pane shows, `--source visible`, must be the bottom of its output), and it was not checked in the last 5 minutes. The screen is sampled on each round while a check is due, so a pane is first found quiet about a minute after it went still; a check with no quiet pane waits for the next round. A pane that is not in front in herdr's own window at the computer is preferred. After the check, the app compares the pane's visible screen with what it showed before and logs whether it is back as it was.
- The app types the command, then reads the input line. It presses Enter only if that line holds exactly the command. If anything else was already typed there, it erases what it typed, leaves the pane as it was, and tries again in five minutes.
- While a check runs in a pane, anything you send to that pane waits for it to finish.
- The app keeps reading for up to six seconds until the usage screen is fully drawn (Antigravity draws its frame first and its numbers a moment later). Esc, which closes Claude's, Grok's and Antigravity's screens, goes out when the screen's own "Esc to cancel" or "Esc close" line is showing. Without that line, Esc goes only to an idle agent, where it can't interrupt anything, never to a working one.
- A check that found no limits (for example a Hermes session that hasn't made a call yet) is tried again in five minutes.

## How the app treats the panes (1.4.0)

- **Reading moves nothing.** Every screen and every bit of scrollback is read with `herdr pane read` (`--source recent --lines N`, which returns scrollback too: 650 lines from a Hermes pane scrolled back 603 rows, checked with herdr 0.9.5), so no scroll or copy-mode key is ever sent just to read. The only keys the app sends by itself are the usage checks' (see Usage limits), and they wait for a quiet pane.
- **The pane's size is never changed.** The app runs `herdr` commands over plain SSH command channels, never as a herdr client attached to the session and never with a terminal of its own (no PTY), so herdr never reflows a pane to the phone's width.
- **Fewer polls.** While the app is behind, the agent screen's own reads stop (the watcher does the checks). With the screen off, the background refresh of the open server slows from every 3 seconds to every 12, watched servers from every 15 seconds to every 45, and the work sweep (what runs under each agent) runs at most every 30 seconds (90 with the screen off) instead of on every refresh. While Watching, a dropped connection is retried in the background too (at most every 15 seconds, 60 with the screen off), so alerts don't stop until the app is opened.
- **Agent versions.** On connecting, the app runs each agent program's own `--version` once (5-second limit, nothing typed into any pane) and keeps the versions for the parsers and the debug bundle, so a card that changes shape in a new version can get its own rule.

## Security

- **Host key pinning.** The first time you connect to a server, the app shows the server's SHA-256 host key fingerprint and asks you to confirm it. After that it refuses to connect if the fingerprint changes. To check the fingerprint on the computer itself, run `ssh-keyscan localhost | ssh-keygen -lf -`.
- **Keys.** Turn on **Use an SSH key** for a server and either make a key on the phone or import a key file. A key made on the phone is an ECDSA P-256 key inside the Android Keystore: it can sign but can never be read or copied out (the Keystore has no Ed25519 before Android 13). The app shows its `authorized_keys` line with Copy and Share buttons. An imported key file, and its passphrase if it has one, are encrypted like a password. With the switch off, the app never offers a key to that server.
- **Key, then password.** With a key turned on, the app offers the key first and the saved password second, so a server that turns the key down still lets you in. If there's no saved password, it asks for one.
- **Passwords.** A saved password is encrypted with an AES key that stays in the Android Keystore. You can also ask for a fingerprint or the screen lock before the saved password or key is used. The password is sent only to the server it belongs to.
- **Fingerprint window (1.3.1).** The Keystore keys for saved passwords, imported key files and their passphrases, and the chats and question history kept on the phone can be used only for 4 hours after you prove who you are: a strong fingerprint or face unlock, or the screen lock (Android 11 and later; before that, any fingerprint or the lock). Unlocking the app counts, and so does unlocking the phone. Within the window the app and its background alerts work as before. After it:
  - the app asks for the fingerprint when it opens;
  - alerts still come, but say "Unlock HerdrDroid to read" instead of the question, with no answer buttons;
  - a watched server that needs its saved password or key to reconnect sends one "isn't being watched" notice instead of failing silently;
  - chats and questions are not read or written until you unlock, and nothing is deleted.
  Upgrading from 1.3.0 or earlier: the first unlock re-seals every saved secret and chat under the new keys, and the old keys are deleted only once all of it is done, so nothing is lost if it stops half way.
  How it keeps data safe (1.3.2, after a code review of 1.3.1): nothing is saved or read while the re-seal runs; each file is replaced with one atomic rename, so a failed rename or a crash leaves the original; a server record is saved only after all its secrets were re-sealed; something even the old key can't open is set aside in an `unreadable` folder, not deleted; any other error stops the re-seal with the old keys kept, to try again at the next unlock; and the old keys are deleted only after a second pass found everything under the new ones. A kept chat or question file is never deleted because it can't be opened: one whose seal fails to authenticate, or that opens but doesn't decode, moves to the `unreadable` folder (removing the server clears it), and a locked phone, a Keystore or disk error, or an invalidated key leaves it where it is. A copy left by a 1.3.1 re-seal that stopped after removing the original becomes the file again. A password typed while the re-seal is unfinished is kept, and the next pass re-seals it. If removing the screen lock invalidates the keys, the app makes new ones for what is saved from then on, and asks for saved passwords again. A phone with no secure screen lock keeps the old keys. Keys made on the phone (ECDSA, for signing in) are not affected: they stay usable, so your servers' `authorized_keys` lines keep working.
- **Files the app leaves on the computer (Bill's decision, 2026-11-14: kept as is).** The app writes only three things on a computer: files you send from the phone go to `~/.herdrdroid/inbox` (or the folder you pick), so the agent can read them; "Recycle" moves a file to `~/scratch/herdrdroid-recycled-<date>` instead of deleting it; and starting an agent in a new folder creates that folder. Chats, questions and settings stay on the phone.
- **What a login can do.** A password login is a full shell for that user. The app only runs `herdr` commands, but anyone who can use your unlocked phone and this app can type into every pane. Keep the phone locked, and prefer servers on your own network or a VPN.

## SSH over a WebSocket

Some computers can be reached only over HTTPS: no SSH port answers from outside, but a web address does. For those, set a server's **Connect through** to **SSH over WebSocket** and give it a `wss://` address. The app carries its ordinary SSH connection inside that WebSocket. Everything else is the same: the pinned host key, signing in with the phone's key, and the herdr commands.

You run a small bridge on the computer that passes WebSocket bytes to its own SSH server. The bridge holds no credential and does no authentication of its own; SSH's handshake (the host key you pin and the key on your phone) is the only gate.

### What the bridge has to do

- **Accept a plain WebSocket upgrade over HTTP/1.1** at its address: RFC 6455, no subprotocol, no special headers, no query string, no HTTP authentication. The app always uses HTTP/1.1. If the bridge sits behind a proxy or CDN, that path must pass an HTTP/1.1 `Upgrade: websocket` through. A front end that answers over HTTP/2 instead replies `200` and never upgrades, and the app reports "The WebSocket upgrade was refused (HTTP 200)".
- **Carry the raw SSH byte stream in binary frames**, both ways, in order. Frame boundaries mean nothing; the bridge may split or join bytes however it likes, but must never drop or reorder them. Text frames are not allowed.
- **Connect each WebSocket to the SSH server** (usually `127.0.0.1:22`), and close one side when the other closes.
- **Answer pings.** The app pings every 20 seconds and SSH sends keep-alives every 15, so an idle connection stays open.
- **Use TLS** (`wss://`) for anything reachable from outside, either in the bridge itself or in a reverse proxy in front of it.

### The SSH server behind it

Because the address is public, set the SSH server to keys only:

```
# /etc/ssh/sshd_config.d/00-keys-only.conf
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
```

Then `sudo sshd -t && sudo systemctl reload ssh`. Add the phone's key line (the server's settings in the app, **Copy** or **Share**) to `~/.ssh/authorized_keys` for the user the app signs in as, with `~/.ssh` at mode 700 and the file at 600. If sshd runs with `UsePAM no` and the account was made without a password, unlock it without giving it a usable one (`sudo usermod -p '*' <user>`); otherwise sshd refuses the locked account even with a valid key.

Read the host key fingerprint on the computer, to type or paste into the app:

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub     # prints SHA256:...
```

### Running the bridge

Either tool works; keep it listening on localhost and put TLS in front.

[websockify](https://github.com/novnc/websockify) (packaged in Debian and Ubuntu as `websockify`):

```bash
websockify 127.0.0.1:8080 127.0.0.1:22
```

[websocat](https://github.com/vi/websocat):

```bash
websocat --binary -E ws-l:127.0.0.1:8080 tcp:127.0.0.1:22
```

Run it under systemd (or your container's supervisor) so it starts again after a restart.

A TLS front end with [Caddy](https://caddyserver.com/), which gets a certificate and passes WebSocket upgrades by itself:

```
ssh.example.org {
    reverse_proxy 127.0.0.1:8080
}
```

Or with nginx (inside an existing `server { listen 443 ssl; ... }` block):

```
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 1h;
    proxy_send_timeout 1h;
}
```

A hosting platform that publishes a container port on an HTTPS address works the same way: publish the bridge's port, and the platform's address is the `wss://` address.

### Checking it from a computer

With `websocat` installed on a laptop, OpenSSH can go through the bridge exactly as the app does:

```bash
ssh -o ProxyCommand="websocat --binary wss://ssh.example.org/" <user>@bridge
```

The name after `@` is only a label here; the connection goes wherever the address points. If that signs in and `herdr --version` runs, the app will connect too. Use the full path to herdr in the app's settings (for example `/home/<user>/.local/bin/herdr`), since a non-interactive SSH command doesn't read `~/.bashrc`.

### In the app

Edit the server, choose **SSH over WebSocket**, and fill in the address (it starts with `wss://`), the username, the herdr command and the host key fingerprint. Then make or import a key and add its line on the computer. The connection is closed whenever the app goes to the background, so a host that sleeps when idle can sleep. If it doesn't answer at first, the app retries for about 30 seconds ("it may be waking up"), and **Cancel** stops that. Settings → **Diagnostics** shows each step of a connection (the upgrade, the first bytes each way, the SSH key exchange) without the address, keys or passwords.

`tools/ws-test-server/` holds a throwaway sshd behind websockify that the end-to-end test uses; its Dockerfile shows a complete working setup.

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

The build runs the parser tests first, then writes the signed APK to `<work-folder>/out/`. The app is signed with the sideload key, so a newer build installs over an older one. A build signed with a different key will not install over this one without uninstalling first, so the key must never change.

The key lives outside the source, in its own folder: `~/Documents/herdrdroid-signing/` (mode 700, files 600), or the folder named by `HD_SIGNING_DIR`. It holds `herdrdroid-sideload.jks` and `signing.properties` (`storePassword`, `keyAlias`, `keyPassword`). `build-in-docker.sh` mounts that folder read-only into the build container and points Gradle at it with `HD_KEYSTORE_DIR`, so no sync or clean of the source folder can reach the key (since 1.4.0, after a source sync deleted the copy kept in `keystore/`). The script stops when the key is missing; it never makes a new one. CI writes the key into `keystore/` from the repository's secrets (`keystore/push-signing-secrets.sh`, which also reads `HD_SIGNING_DIR`). The original is kept off the build machine as well.

## Layout

| File | What it holds |
|---|---|
| `app/src/main/java/.../ssh/HerdrSsh.kt` | The SSH connection, host key pinning, and running one command at a time |
| `app/src/main/java/.../ssh/WsBridge.kt` | SSH over a WebSocket: the local relay between sshj and the WebSocket |
| `app/src/main/assets/transcript.py` | The reader sent to the computer's python3 for agent logs, history, and what runs under a pane |
| `app/src/main/java/.../herdr/Herdr.kt` | The herdr commands, pane parsing, question detection, and the guarded usage check |
| `app/src/main/java/.../herdr/Reflow.kt` | Turning a terminal screen into the chat view: rejoining wrapped paragraphs, tables, your messages, and the footer |
| `app/src/main/java/.../herdr/Quota.kt` | The usage profile for each agent and the parser for their usage screens |
| `app/src/main/java/.../store/ServerStore.kt` | Saved servers, app settings, the Keystore encryption, and keys made on the phone |
| `app/src/main/java/.../AppViewModel.kt` | App state, the connect flow, reconnecting, the send queue, the usage checks, and recording questions and answers |
| `app/src/main/java/.../transcript/Questions.kt` | The question history: what a question card becomes in the chat, how its answer is recorded, merging with Claude's AskUserQuestion in its log, and how it is kept on the phone |
| `app/src/test/java/.../QuestionHistoryTest.kt` | Every agent's card recorded with its options and answer, the multi-question dialog, answered elsewhere and dismissed, no duplicates, placement in the chat, and the sealed store |
| `app/src/main/java/.../ui/` | The screens: servers, editing a server, settings, all tabs, and one agent |
| `app/src/test/java/.../QuotaTest.kt` | The parser run against each agent's captured usage screen |
| `app/src/test/java/.../ReflowTest.kt` | The chat view's parser run against screens shaped like Grok, Claude Code, Codex and OpenCode |
| `app/src/main/res/font/`, `app/src/main/assets/licenses/` | The bundled fonts (Inter, Source Serif 4, JetBrains Mono) and their SIL Open Font License texts |
