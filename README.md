# HerdrDroid

HerdrDroid is an Android app for talking to the coding agents in a [herdr](https://github.com/herdrdev/herdr) session from a phone. It signs in to the computer running herdr over SSH with a username and password, and every tap becomes one `herdr` command on that computer. Nothing is installed on the computer and no new network port is opened, because the app uses the SSH server that is already there.

## What it does

- **Servers.** You add each computer yourself: a name, its address, its SSH port, your username, and the command that runs herdr there (for example `herdr` or `~/.local/bin/herdr`). Nothing is filled in for you, so the app works with any herdr server you can reach.
- **All tabs.** Every herdr pane is a card with its agent, its status (Needs you, Working, Done, Idle) and the last line it printed. A tab that is waiting on you has an orange border and sorts to the top. The list refreshes every three seconds.
- **Talking to an agent.** Tap a card to see the pane's recent output and send it a message. Messages to an agent go through `herdr agent prompt`, which submits text the way the agent expects. Other panes get the text and Enter. Quick keys send Esc, Tab, the arrows, Ctrl+C (after you confirm) and Enter. **Terminal** switches to an unwrapped view of the screen.
- **An agent needs you.** When herdr reports an agent as blocked, or the bottom of the screen shows a numbered menu or a `(y/n)` question, the app shows the question with one button per answer. Each button says exactly which keys it sends.

## Security

- **Host key pinning.** The first time you connect to a server, the app shows the server's SHA-256 host key fingerprint and asks you to confirm it. After that it refuses to connect if the fingerprint changes. To check the fingerprint on the computer itself, run `ssh-keyscan localhost | ssh-keygen -lf -`.
- **Passwords.** A saved password is encrypted with an AES key that stays in the Android Keystore. You can also ask for a fingerprint or the screen lock before the saved password is used. The password is sent only to the server it belongs to.
- **What a login can do.** A password login is a full shell for that user. The app only runs `herdr` commands, but anyone who can use your unlocked phone and this app can type into every pane. Keep the phone locked, and prefer servers on your own network or a VPN.

## How each action maps to herdr

| In the app | On the computer |
|---|---|
| All tabs | `herdr pane list` |
| A card's last line | `herdr pane read <pane> --source recent --lines 8` |
| A tab's screen | `herdr pane read <pane> --source recent --lines 200` |
| Send a message to an agent | `herdr agent prompt <pane> <text>` |
| Send a message to another pane | `herdr pane send-text <pane> <text>`, then `herdr pane send-keys <pane> enter` |
| Quick keys | `herdr pane send-keys <pane> esc` (or `tab`, `up`, `down`, `ctrl+c`, `enter`) |
| Answer buttons | `herdr pane send-text <pane> <key>`, plus `enter` for a `(y/n)` question |

Every argument is single-quoted for the remote shell, so message text is passed as typed.

## Building

The build needs JDK 17, the Android SDK (platform 34, build-tools 34.0.0) and Gradle 8.9. `build-in-docker.sh` does the whole build in a throwaway `eclipse-temurin:17-jdk` container, keeping the SDK and Gradle caches in a `.cache` folder beside the source:

```bash
bash build-in-docker.sh /path/to/work-folder
```

The signed APK lands in `<work-folder>/out/`. The app is signed with the sideload key in `keystore/`, so a newer build installs over an older one. Keep that file with the source: a build signed with a different key will not install over this one without uninstalling first.

## Layout

| File | What it holds |
|---|---|
| `app/src/main/java/.../ssh/HerdrSsh.kt` | The SSH connection, host key pinning, and running one command at a time |
| `app/src/main/java/.../herdr/Herdr.kt` | The herdr commands, pane parsing, and question detection |
| `app/src/main/java/.../store/ServerStore.kt` | Saved servers and the Keystore password encryption |
| `app/src/main/java/.../AppViewModel.kt` | App state and the connect flow |
| `app/src/main/java/.../ui/` | The four screens: servers, editing a server, all tabs, and one agent |
