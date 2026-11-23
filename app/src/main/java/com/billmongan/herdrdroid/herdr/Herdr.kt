package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.transcript.Transcript
import com.billmongan.herdrdroid.transcript.TranscriptRead
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONObject

enum class Status { NEEDS_YOU, WORKING, DONE, IDLE, UNKNOWN }

data class Pane(
    val id: String,
    val tabId: String,
    val workspaceId: String,
    val agent: String?,
    val status: Status,
    val title: String,
    val cwd: String,
    /** herdr's own window has this pane in front, so someone may be typing in it at the computer. */
    val focused: Boolean = false,
    /** The label of the tab the pane is in ("claude", "opencode-2", …). */
    val tabLabel: String = "",
    /**
     * How many rows the pane is scrolled back from the bottom at the computer (herdr's
     * `scroll.offset_from_bottom`); 0 at the bottom, null when herdr doesn't say (1.3.1).
     */
    val scrollOffset: Int? = null,
    val sessionId: String? = null,
    val terminalId: String? = null,
    val backgroundBusy: Boolean = false,
    /**
     * What herdr itself said (done or idle) when the app marks an OpenCode pane as needing you
     * because its screen shows a permission dialog (2.1.2). herdr skips screen detection for
     * OpenCode, so [status] then says NEEDS_YOU for the drawer, the list and the alert only:
     * sends keep the route herdr's own status picks ([sendStatus]), and herdr waits use it too.
     */
    val screenNeedsYouFrom: Status? = null,
    /**
     * herdr calls this OpenCode pane blocked, but its screen shows OpenCode's normal input box and
     * no question or permission dialog (2.2.0): after a failed turn (llmproxy with no free model,
     * for one) herdr keeps the pane blocked until the next turn. [status] stays herdr's, so every
     * send keeps its guards; only the display shows the pane as idle ([com.billmongan.herdrdroid.AppViewModel.shownStatus]).
     */
    val openCodeStaleBlock: Boolean = false,
) {
    /** The status that picks how a message goes out: herdr's own, not the screen's (2.1.2). */
    val sendStatus: Status get() = screenNeedsYouFrom ?: status

    val agentName: String
        get() = when (agent?.lowercase()) {
            null, "" -> title.ifBlank { "Shell" }
            "claude" -> "Claude"
            "codex" -> "Codex"
            "hermes" -> "Hermes"
            "opencode" -> "OpenCode"
            "agy", "antigravity" -> "Antigravity"
            "grok" -> "Grok"
            "pi" -> "pi"
            "omp" -> "Oh My Pi"
            "copilot" -> "Copilot"
            "gemini" -> "Gemini"
            "kiro" -> "Kiro"
            "vibe" -> "Vibe"
            "muse" -> "Muse"
            else -> agent.replaceFirstChar { it.uppercase() }
        }

    val initial: String get() = agentName.take(1).uppercase()
    val folder: String get() = cwd.trimEnd('/').substringAfterLast('/')
}

/** Single-quotes a value for the remote shell. */
fun shq(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

/**
 * A program path from the server settings as one shell word (1.3.0, from a code review: it went
 * into every command unquoted, so a space or a ";" in it broke or ran as a command). "~/" stays
 * the home folder.
 */
fun programPath(p: String): String =
    if (p == "~") "\"\$HOME\"" else if (p.startsWith("~/")) "\"\$HOME\"/" + shq(p.removePrefix("~/")) else shq(p)

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\u001B[()][A-Z0-9]")

/**
 * Pane text without its styles. 2.1.1: Claude Code (2.1.329) marks the current tab of its
 * multi-question bar only by colour, so a styled bar row keeps that mark as [TAB_CURRENT_MARK]
 * before the current tab's box ([markCurrentTab]); [parseTabBar] reads it. Every other row, and a
 * plain read, comes out as before.
 */
fun stripAnsi(s: String): String {
    if (s.indexOf('\u001B') < 0) return s
    if (s.indexOf('←') < 0 || s.indexOf('→') < 0) return ANSI.replace(s, "")
    val rows = s.split('\n')
    if (rows.none(::tabBarCandidate)) return ANSI.replace(s, "")
    return ANSI.replace(rows.joinToString("\n") { row -> if (tabBarCandidate(row)) markCurrentTab(row) ?: row else row }, "")
}

/**
 * Zero-width word joiner: the current tab of Claude's question bar, kept from a styled read
 * (2.1.1). It draws as nothing, so a screen shown with it looks the same.
 */
internal const val TAB_CURRENT_MARK = '\u2060'

private fun tabBarCandidate(row: String): Boolean =
    row.indexOf('\u001B') >= 0 && row.indexOf('←') >= 0 && row.indexOf('→') >= 0 && row.any { it in "☐☒✔✓☑" }

/**
 * Terminal glyphs that the app's fonts lack, mapped to plain ones. Android draws a missing glyph
 * from a fallback font, and some come out as emoji: Claude's "⏵⏵ auto mode on" showed a
 * pause-like sign, and its "⏸ plan mode on" a pause emoji.
 */
private val GLYPHS = listOf(
    "\u23F5\u23F5" to "\u00BB", // ⏵⏵ -> »
    "\u23F5" to "\u203A", // ⏵ -> ›
    "\u23F8" to "\u2016", // ⏸ -> ‖
    "\u23EF" to "\u00BB", // ⏯ -> »
    "\u23F9" to "\u25A0", // ⏹ -> ■
)

/** Pane text as the phone shows it: no ANSI codes, and only glyphs the app's fonts can draw. */
fun forPhone(s: String): String = GLYPHS.fold(stripAnsi(s)) { acc, (from, to) -> acc.replace(from, to) }

/** Every herdr action the app performs, as one command line over SSH. */
/**
 * Which herdr a computer runs: the herdr TUI's server (`herdr`), or herdr-core (`hc`, the server
 * behind Herdr App). The two don't share agents; a server in the app talks to one of them.
 */
enum class Backend { TUI, CORE }

/** What a computer has: which herdr is installed and which one's server is running. */
data class Detected(val tuiInstalled: Boolean, val tuiRunning: Boolean, val coreInstalled: Boolean, val coreRunning: Boolean, val hcPath: String?)

class Herdr internal constructor(
    private val ssh: HerdrSsh, herdrPath: String, val backend: Backend, hcPath: String?,
    private val commands: Commands?,
) {
    constructor(ssh: HerdrSsh, herdrPath: String, backend: Backend = Backend.TUI, hcPath: String? = null) :
        this(ssh, herdrPath, backend, hcPath, null)

    internal interface Commands {
        val connection: Any?
        suspend fun bulk(command: String): com.billmongan.herdrdroid.ssh.ExecResult
        suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): com.billmongan.herdrdroid.ssh.ExecResult
        suspend fun input(command: String, input: ByteArray, expectedConnection: Any): com.billmongan.herdrdroid.ssh.ExecResult =
            error("Unexpected command with stdin")
    }
    // The path is the user's setting; "~" stays unquoted so the remote shell expands it.
    // Left at the default, herdr is looked up the way hc is: on the PATH, else in ~/.local/bin, where
    // its installer puts it (a non-interactive SSH shell often lacks ~/.local/bin on the PATH).
    private val herdr = herdrPath.trim().let { p ->
        if (p.isBlank() || p == "herdr") "\"\$(command -v herdr || echo \"\$HOME/.local/bin/herdr\")\"" else programPath(p)
    }
    private val hc = hcPath?.takeIf { it.isNotBlank() }?.let(::shq) ?: "\"\$(command -v hc || echo \"\$HOME/.local/bin/hc\")\""
    private val core get() = backend == Backend.CORE

    private fun cmd(vararg args: String) = herdr + " " + args.joinToString(" ") { shq(it) }
    private fun hcCmd(vararg args: String) = hc + " " + args.joinToString(" ") { shq(it) }

    /** herdr-core has no way to rename a terminal, so the app hides Rename there. */
    val canRename: Boolean get() = !core

    /**
     * The system python3 by its absolute path when it is there: a non-interactive SSH command
     * doesn't read ~/.bashrc, and `python3` on an interactive PATH may be a virtualenv's.
     */
    private val PY = "\"\$([ -x /usr/bin/python3 ] && echo /usr/bin/python3 || echo python3)\""

    /**
     * The transcript reader sent compressed (about a quarter of its size): python3 inflates it
     * from standard input and runs it, with the same arguments as before.
     */
    private val PYZ = "$PY -c 'import sys,zlib;exec(compile(zlib.decompress(sys.stdin.buffer.read()),\"transcript.py\",\"exec\"))'"
    private var zFrom: ByteArray? = null
    private var zTo: ByteArray? = null

    private fun z(script: ByteArray): ByteArray {
        if (zFrom === script) return zTo!!
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(out, java.util.zip.Deflater(9)).use { it.write(script) }
        return out.toByteArray().also { zFrom = script; zTo = it }
    }

    internal val connectionIdentity: Any? get() = if (commands == null) ssh.connectionIdentity else commands.connection

    /** A command on the send lane: it doesn't wait behind reads. */
    private suspend fun runNow(command: String, timeoutSeconds: Long = 30, expectedConnection: Any? = null, permit: WritePermit? = null): String {
        // The test seam claims the permit itself, so fakes see the same no-write-after-revoke rule.
        val r = commands?.let { permit.admit(); it.immediate(command, timeoutSeconds, expectedConnection) }
            ?: ssh.execNow(command, timeoutSeconds, expectedConnection, permit)
        if (r.exit != 0) {
            val msg = (r.err.ifBlank { r.out }).trim().lines().lastOrNull { it.isNotBlank() } ?: "exit ${r.exit}"
            throw HerdrError(msg, r.exit)
        }
        return r.out
    }

    /**
     * Streams what the agent adds to its log, for up to [seconds]: the reader's follow mode checks
     * about three times a second and prints a line whenever something changed. Returns when the
     * stream ends; [onRead] gets each read.
     */
    suspend fun followTranscript(pane: Pane, cursor: String, script: ByteArray, seconds: Int, onRead: suspend (TranscriptRead) -> Unit) {
        val agent = pane.agent ?: return
        val live = Transcript.withLive(cursor, pane.id, pane.sessionId)
        ssh.execStream("$PYZ __follow__ ${shq(agent)} ${shq(pane.cwd)} ${shq(live)} $seconds", z(script)) { line ->
            runCatching { Transcript.parseRead(line) }.getOrNull()?.let { onRead(it) }
        }
    }

    private suspend fun run(command: String): String {
        val r = commands?.bulk(command) ?: ssh.exec(command)
        if (r.exit != 0) {
            val msg = (r.err.ifBlank { r.out }).trim().lines().lastOrNull { it.isNotBlank() } ?: "exit ${r.exit}"
            throw HerdrError(msg, r.exit)
        }
        return r.out
    }

    /**
     * Each pane's status as herdr itself reported it in the last [panes] read, before the app's own
     * screen promotion (2.1.4): herdr's waits match herdr's state, not the shown one.
     */
    @Volatile var herdrStates: Map<String, Status> = emptyMap()
        private set

    /** The last pane list read, for a follow-up read on the same connection. */
    @Volatile var panes0Cache: List<Pane> = emptyList()
        private set

    suspend fun panes(): List<Pane> {
        if (core) return corePanes(run(hcCmd("ls", "--json")))
        // Panes and tab labels in one round trip; a tab list failure only loses the labels.
        val both = run(cmd("pane", "list") + "; echo @@herdrdroid-tabs@@; " + cmd("tab", "list") + " 2>/dev/null || true")
        val paneJson = both.substringBefore("@@herdrdroid-tabs@@")
        val labels = runCatching {
            val tabs = JSONObject(both.substringAfter("@@herdrdroid-tabs@@").trim()).getJSONObject("result").getJSONArray("tabs")
            (0 until tabs.length()).associate { tabs.getJSONObject(it).let { t -> t.optString("tab_id") to t.optString("label") } }
        }.getOrDefault(emptyMap())
        val root = JSONObject(paneJson)
        val arr = root.getJSONObject("result").getJSONArray("panes")
        val list = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            tuiPane(p).copy(tabLabel = labels[p.optString("tab_id")].orEmpty())
        }
        // herdr doesn't always mark a Claude question dialog (AskUserQuestion) as blocked, so the
        // bottom of each Claude pane that isn't already waiting is checked for the dialog's footer.
        // 2.1.1: the read is long enough to reach past the blank rows a short conversation leaves
        // under a fullscreen dialog (showsQuestionDialog looks at the last rows with text), and
        // Claude's sessions list with a session awaiting input counts as needing you too.
        // 2.1.2: herdr skips screen detection for OpenCode and calls a pane with a permission dialog
        // done or idle, so those panes are read in the same round trip and marked for display only.
        herdrStates = list.associate { it.id to it.status }
        // 2.2.0: an OpenCode pane herdr calls blocked is read too, to tell a real dialog from a stale block.
        val askable = screenAskable(list) + list.filter { isOpenCode(it.agent) && it.status == Status.NEEDS_YOU }
        val marked = if (askable.isEmpty()) list else {
            val tails = runCatching { previews(askable.map { it.id }, CLAUDE_STATUS_READ_LINES) }.getOrDefault(emptyMap())
            markScreenNeedsYou(list, tails.mapValues { it.value.tail })
        }
        val order = listOf(Status.NEEDS_YOU, Status.WORKING, Status.DONE, Status.IDLE, Status.UNKNOWN)
        panes0Cache = marked
        return marked.sortedWith(compareBy({ order.indexOf(it.status) }, { it.id }))
    }

    private fun tuiPane(p: JSONObject): Pane = Pane(
                id = p.getString("pane_id"),
                tabId = p.optString("tab_id"),
                workspaceId = p.optString("workspace_id"),
                agent = p.optString("agent").takeIf { !p.isNull("agent") && it.isNotBlank() }?.let(::canonicalAgent)
                    ?: titleAgent(p.optString("terminal_title_stripped")),
                status = when (p.optString("agent_status")) {
                    "blocked" -> Status.NEEDS_YOU
                    "working" -> Status.WORKING
                    "done" -> Status.DONE
                    "idle" -> Status.IDLE
                    else -> Status.UNKNOWN
                },
                title = p.optString("terminal_title_stripped"),
                cwd = p.optString("foreground_cwd", p.optString("cwd")),
                sessionId = p.optJSONObject("agent_session")?.let { session ->
                    val agent = p.optString("agent")
                    session.optString("value").takeIf {
                        session.optString("kind") == "id" && agent.isNotBlank() &&
                            session.optString("agent") == agent && it.isNotBlank() && !session.isNull("value")
                    }
                },
                focused = p.optBoolean("focused", false),
                scrollOffset = p.optJSONObject("scroll")?.takeIf { it.has("offset_from_bottom") }?.optInt("offset_from_bottom"),
                terminalId = p.opt("terminal_id")?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() },
            )

    internal fun relayPort(script: ByteArray): PromptRelayPort = object : PromptRelayPort {
        override val owner get() = this@Herdr
        override val connectionIdentity get() = this@Herdr.connectionIdentity
        // 2.2.2: a scrolled-back pane comes back with its offset, so the relay can say so (SCROLLED) instead of "not reachable".
        override suspend fun pane(id: String, connection: Any) = relayPane(id, connection)
        override suspend fun viewport(id: String, connection: Any) =
            runNow(cmd("pane", "read", id, "--source", "visible", "--format", "ansi"), 5, connection)
        override suspend fun hasWork(pane: Pane, connection: Any): Boolean {
            val arg = org.json.JSONArray(listOf(listOf(pane.id, pane.agent.orEmpty(), pane.cwd, pane.sessionId.orEmpty()))).toString()
            val command = "$PYZ __workcounts__ ${shq(arg)}"
            val input = z(script)
            val r = commands?.input(command, input, connection) ?: ssh.execInput(command, input, 8, connection)
            if (r.exit != 0) return true
            // Missing or malformed evidence is not an idle result.
            val counts = JSONObject(r.out)
            return !counts.optBoolean("ok") || counts.optJSONObject("busy")?.opt(pane.id) != 0
        }
        override suspend fun send(pane: Pane, text: String, connection: Any) =
            sendMessage(pane, text, allowTerminalFallback = false, expectedConnection = connection)
        // The permit is checked by the SSH transport after it has the connection (2.0.1 finding 4).
        override suspend fun send(pane: Pane, text: String, connection: Any, permit: WritePermit) =
            sendPermitted(pane, text, connection, permit)
        // 2.2.2: Force Send's prompt to a working agent, still herdr's agent prompt (it reaches a working agent), never --force.
        override suspend fun sendForced(pane: Pane, text: String, connection: Any, permit: WritePermit) =
            sendMessage(pane, text, allowTerminalFallback = false, expectedConnection = connection, permit = permit, busyOk = true)
    }

    /** No tab list, status enrichment, bulk lock, or reconnect during a card tap. */
    internal suspend fun cardPane(id: String, connection: Any): Pane? {
        if (core) return corePanes(runNow(hcCmd("ls", "--json"), 5, connection)).singleOrNull { it.id == id }
        val p = JSONObject(runNow(cmd("pane", "get", id), 5, connection)).getJSONObject("result").getJSONObject("pane")
        return tuiPane(p).takeIf { it.id == id && p.optJSONObject("scroll")?.opt("offset_from_bottom") == 0 }
    }

    /** [cardPane], but a scrolled-back pane is returned with its offset rather than as null (2.2.2: the relay refuses it as SCROLLED). */
    internal suspend fun relayPane(id: String, connection: Any): Pane? {
        if (core) return cardPane(id, connection)
        val p = JSONObject(runNow(cmd("pane", "get", id), 5, connection)).getJSONObject("result").getJSONObject("pane")
        val pane = tuiPane(p).takeIf { it.id == id } ?: return null
        val offset = p.optJSONObject("scroll")?.opt("offset_from_bottom")
        return if (offset == 0) pane else pane.copy(scrollOffset = (offset as? Number)?.toInt()?.takeIf { it != 0 } ?: -1)
    }

    /** Only the live viewport, with no fallback into old scrollback. */
    internal suspend fun readCardViewport(id: String, connection: Any, timeoutSeconds: Long = 5): String = stripAnsi(
        readStyledViewport(id, connection, timeoutSeconds)
    )

    internal suspend fun readStyledViewport(id: String, connection: Any, timeoutSeconds: Long = 5): String =
        if (core) runNow(hcCmd("read", id, "--lines", "80"), timeoutSeconds, connection)
        else runNow(visibleSnapshot(id, ansi = true), timeoutSeconds, connection)

    /**
     * The pane's screen as it is shown at the computer (`--source visible`), which moves nothing
     * (1.3.1). Compared with [read] (the bottom of its output) it tells whether someone has
     * scrolled the pane back. herdr-core has one view only.
     */
    suspend fun readVisible(paneId: String, lines: Int = 60): String =
        if (core) read(paneId, lines)
        else forPhone(run(cmd("pane", "read", paneId, "--source", "visible", "--lines", lines.toString())))

    /**
     * The bottom of a pane's screen. On its own channel (1.4.2, Bill's Codex card that showed
     * nothing): a read never waits behind a long command such as a big session log being read, so
     * the screen, and the question on it, show while that runs.
     */
    /**
     * The pane's screen with its styles kept (1.4.6), for telling an agent's dim placeholder from
     * typed text ([isPlaceholderText]). herdr-core, or a herdr without `--format ansi`, gives the
     * plain screen, where only the known hints count.
     */
    // Herdr can scroll idle full-screen agents on a plain-text recent read. The ANSI snapshot
    // preserves terminal scrollback without sending mouse events into the live application.
    private fun recentSnapshot(paneId: String, lines: Int) =
        cmd("pane", "read", paneId, "--source", "recent", "--lines", lines.toString(), "--format", "ansi")

    // Card reads (including recovery) and dispatch must agree on the serializer. Plain
    // visible text trims row endings, which can change the exact command identity.
    private fun visibleSnapshot(paneId: String, ansi: Boolean, lines: Int? = null): String {
        val args = mutableListOf("pane", "read", paneId, "--source", "visible")
        if (lines != null) args += listOf("--lines", lines.toString())
        if (ansi) args += listOf("--format", "ansi")
        return cmd(*args.toTypedArray())
    }

    suspend fun readStyled(paneId: String, lines: Int = 40): String =
        if (core) read(paneId, lines)
        else runCatching { runNow(recentSnapshot(paneId, lines), timeoutSeconds = 5) }
            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; read(paneId, lines) }

    /**
     * Only what the pane shows now (`--source visible`). Card recovery uses ANSI to keep the
     * same glyphs and row endings as dispatch; ordinary readers still get the plain snapshot.
     */
    suspend fun readVisible(paneId: String): String = readVisible(paneId, preserveGlyphs = false)

    internal suspend fun readVisible(paneId: String, preserveGlyphs: Boolean): String =
        if (core) read(paneId, 60, preserveGlyphs) else screenText(
            runNow(visibleSnapshot(paneId, ansi = preserveGlyphs), timeoutSeconds = 5), preserveGlyphs,
        )

    private fun screenText(text: String, preserveGlyphs: Boolean) = if (preserveGlyphs) stripAnsi(text) else forPhone(text)

    /**
     * The notification's strict read (2.1.2) with its styles kept: the same [read] (the bottom
     * [lines] rows, no card-recovery fallback), as ANSI, so an OpenCode permission's highlighted
     * button can be seen before a key goes in. herdr-core gives plain text, where no button counts
     * as highlighted.
     */
    internal suspend fun readStrictStyled(paneId: String, lines: Int): String =
        if (core) read(paneId, lines, preserveGlyphs = true)
        else try {
            runNow(recentSnapshot(paneId, lines), timeoutSeconds = 5)
        } catch (e: HerdrError) {
            if (e.exit == -2) throw e
            runNow(visibleSnapshot(paneId, ansi = true, lines = lines), timeoutSeconds = 5)
        }

    suspend fun read(paneId: String, lines: Int = 200, preserveGlyphs: Boolean = false): String =
        if (core) screenText(runNow(hcCmd("read", paneId, "--lines", lines.toString()), timeoutSeconds = 5), preserveGlyphs)
        else try {
            screenText(runNow(recentSnapshot(paneId, lines), timeoutSeconds = 5), preserveGlyphs)
        } catch (e: HerdrError) {
            if (e.exit == -2) throw e
            screenText(runNow(visibleSnapshot(paneId, ansi = preserveGlyphs, lines = lines), timeoutSeconds = 5), preserveGlyphs)
        }

    /**
     * Waits until any agent pane in [panes] changes state, or [timeoutMs] passes (1.4.1, "fewer
     * polls"): one command that runs `herdr agent wait <pane> --until <each other state>` for every
     * agent pane at once, prints the first that changed and stops the rest. It runs on the send
     * lane, so it never holds up reads or answers. Returns the pane that changed, or null on a timeout,
     * or null when it can't wait here (herdr-core, no agent panes, or an error), so the caller polls.
     */
    suspend fun waitForChange(panes: List<Pane>, timeoutMs: Long): String? = waitForWake(panes, timeoutMs)?.paneId

    /** What ended a [waitForWake]: a pane whose herdr state changed, or ([screen]) whose screen shows a dialog (2.1.4). */
    data class Wake(val paneId: String, val screen: Boolean)

    /**
     * [waitForChange], and also (2.1.4, Bill: the drawer's yellow badge lit late) a wait on the
     * screen of each pane in [screens] for the footer of a Claude or OpenCode dialog herdr doesn't
     * report (`herdr pane wait-output`, which herdr checks on the computer, so nothing crosses the
     * link until it matches). One command on the send lane, as before.
     */
    suspend fun waitForWake(panes: List<Pane>, timeoutMs: Long, screens: List<Pane> = emptyList()): Wake? {
        if (core) return null
        val script = waitScript(panes, timeoutMs, screens) ?: return null
        val command = "bash -c " + shq(script)
        val seconds = timeoutMs / 1000 + 20
        val r = runCatching { commands?.immediate(command, seconds, null) ?: ssh.execNow(command, timeoutSeconds = seconds) }.getOrNull() ?: return null
        // A change is the pane that changed. Anything else (a timeout, a wait that failed, herdr
        // without agent wait) is null, and the caller polls with its usual gap instead of starting
        // another wait straight away (1.4.1, from two code reviews).
        return parseWake(r.out)
    }

    /** The shell script [waitForWake] runs, or null when there is nothing to wait on. */
    internal fun waitScript(panes: List<Pane>, timeoutMs: Long, screens: List<Pane> = emptyList()): String? {
        val agents = panes.filter { it.agent != null }
        if (agents.isEmpty()) return null
        val states = herdrStates
        val body = StringBuilder()
        for (p in agents) {
            val until = waitUntilStates(p, states[p.id]).joinToString(" ") { "--until $it" }
            body.append("( $herdr agent wait ${shq(p.id)} $until --timeout $timeoutMs >/dev/null 2>&1 && echo ${shq("@@chg " + p.id)} ) & pids=\"\$pids \$!\"; ")
        }
        val ids = agents.map { it.id }.toSet()
        for (p in screens.filter { it.id in ids }.distinctBy { it.id }) {
            val regex = screenWakeRegex(p.agent) ?: continue
            // A herdr without wait-output, or one that fails, sleeps out the round instead of ending it,
            // so the state waits still stand.
            val wait = cmd("pane", "wait-output", "--regex", regex, "--source", "visible", "--timeout", timeoutMs.toString(), p.id)
            body.append("( $wait >/dev/null 2>&1 && echo ${shq("@@scr " + p.id)} || sleep ${timeoutMs / 1000 + 5} ) & pids=\"\$pids \$!\"; ")
        }
        // The first wait's own status is the script's (1.4.1, from a second review: a trailing
        // "true" made a failed wait look like a timeout). 2.1.4: the waits still running are stopped
        // with their herdr processes, not only their subshells, so none is left behind on the computer.
        return "pids=; ${body}wait -n 2>/dev/null; rc=\$?; for p in \$pids; do pkill -P \"\$p\" 2>/dev/null; done; kill \$pids 2>/dev/null; exit \$rc"
    }

    /** Each agent's program version here (1.4.0): agent kind to version, for the parsers and the debug bundle. */
    /**
     * The agents' versions (1.4.0). On the send lane, so no read waits for it, and every agent's
     * --version at once under one 10-second cap (1.4.1, from a code review: one at a time on the
     * connection's lock, pane reads waited up to a minute).
     */
    suspend fun agentVersions(): Map<String, String> = runCatching { AgentVersions.parse(ssh.execNow(AgentVersions.script(), timeoutSeconds = 10).out) }.getOrDefault(emptyMap())

    /** The last few lines of every pane, fetched in one round trip. */
    suspend fun previews(ids: List<String>, lines: Int = 12): Map<String, Peek> {
        if (ids.isEmpty()) return emptyMap()
        val script = ids.joinToString("; ") { id ->
            "echo ${shq("@@herdrdroid@@$id")}; ${if (core) hcCmd("read", id, "--lines", lines.toString()) else recentSnapshot(id, lines)} 2>/dev/null"
        }
        return parsePreviews(forPhone((commands?.bulk(script) ?: ssh.exec(script)).out))
    }

    /** Splits [previews]' output at each pane's marker. */
    internal fun parsePreviews(out: String): Map<String, Peek> {
        val result = mutableMapOf<String, Peek>()
        var current: String? = null
        val buf = StringBuilder()
        fun flush() {
            current?.let { id ->
                val line = buf.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !isChrome(it) } ?: ""
                result[id] = Peek(line, buf.toString())
            }
            buf.clear()
        }
        // A read with --format ansi ends without a newline (2.1.2, from the 2.1.1 acceptance), so the
        // next pane's marker can follow the last row of the one before on the same line. Taken only at
        // the line's start, it was missed: every later pane's rows went to the first, whose drawer
        // entry then showed another pane's model line ("GPT-6.2-Sol · default" from FixtureB's b3).
        val mark = "@@herdrdroid@@"
        for (line in out.lines()) {
            val at = line.indexOf(mark)
            if (at >= 0) {
                if (at > 0) buf.appendLine(line.substring(0, at))
                flush()
                current = line.substring(at + mark.length)
            } else {
                buf.appendLine(line)
            }
        }
        flush()
        return result
    }

    /** Sends a message the way an agent expects it; plain panes get the text and Enter. */
    /** How the last message went out, for the diagnostic log. */
    @Volatile var lastSendRoute = ""
        private set

    /**
     * One agent prompt over [connection] that the transport starts only if [permit] is still open
     * when it holds the connection: no forced answer, no typed fallback, no replay (2.0.1 finding 4).
     */
    internal suspend fun sendPermitted(pane: Pane, text: String, connection: Any, permit: WritePermit) =
        sendMessage(pane, text, allowTerminalFallback = false, expectedConnection = connection, permit = permit)

    suspend fun sendMessage(pane: Pane, text: String, allowTerminalFallback: Boolean = true, expectedConnection: Any? = null) =
        sendMessage(pane, text, allowTerminalFallback, expectedConnection, permit = null)

    private suspend fun sendMessage(
        pane: Pane, text: String, allowTerminalFallback: Boolean, expectedConnection: Any?, permit: WritePermit?,
        busyOk: Boolean = false,
    ) {
        // herdr's own status picks the route: an OpenCode pane marked needs-you from its screen
        // keeps the send it had (2.1.2).
        val status = pane.sendStatus
        // 2.2.2: a forced relay may prompt a working agent; never one waiting on you.
        val sendable = if (busyOk) setOf(Status.IDLE, Status.DONE, Status.WORKING) else setOf(Status.IDLE, Status.DONE)
        require(allowTerminalFallback || (pane.agent != null && status in sendable))
        require(permit == null || (!allowTerminalFallback && expectedConnection != null))
        lastSendRoute = when {
            core -> "hc send"
            pane.agent != null && status != Status.NEEDS_YOU -> "herdr agent prompt"
            else -> "herdr pane send-text + enter"
        }
        if (core) {
            // hc send pastes and presses Enter; it refuses a terminal waiting on a question
            // unless forced, and a message sent then is meant as the answer.
            if (status == Status.NEEDS_YOU) runNow(hcCmd("send", "--force", pane.id, text)) else runNow(hcCmd("send", pane.id, text), expectedConnection = expectedConnection, permit = permit)
            return
        }
        if (pane.agent != null && status != Status.NEEDS_YOU) {
            try {
                runNow(cmd("agent", "prompt", pane.id, text), expectedConnection = expectedConnection, permit = permit)
                return
            } catch (e: HerdrError) {
                if (!allowTerminalFallback) throw e
                // Fall through to typing it in, which works in any pane.
                lastSendRoute = "agent prompt refused; typed with send-text + enter"
            }
        }
        runNow(cmd("pane", "send-text", pane.id, text), expectedConnection = expectedConnection)
        runNow(cmd("pane", "send-keys", pane.id, "enter"), expectedConnection = expectedConnection)
    }

    /**
     * Types [text] without pressing Enter. herdr-core has no plain typing, so there short text
     * (a menu number, a y or n) goes as key presses.
     */
    suspend fun sendText(paneId: String, text: String, expectedConnection: Any? = null): String {
        if (!core) return runNow(cmd("pane", "send-text", paneId, text), expectedConnection = expectedConnection)
        val keys = text.map { c -> when (c) { ' ' -> "space"; else -> c.toString() } }
        return runNow(hcCmd("keys", paneId, *keys.toTypedArray()), expectedConnection = expectedConnection)
    }

    /** Types [text] and presses Enter: one paste-and-submit on herdr-core. */
    suspend fun typeAndEnter(paneId: String, text: String, expectedConnection: Any? = null) {
        if (core) { runNow(hcCmd("send", "--force", paneId, text), expectedConnection = expectedConnection); return }
        runNow(cmd("pane", "send-text", paneId, text), expectedConnection = expectedConnection)
        runNow(cmd("pane", "send-keys", paneId, "enter"), expectedConnection = expectedConnection)
    }

    suspend fun sendKeys(paneId: String, vararg keys: String, expectedConnection: Any? = null) =
        if (core) runNow(hcCmd("keys", paneId, *keys), expectedConnection = expectedConnection)
        else runNow(cmd("pane", "send-keys", paneId, *keys), expectedConnection = expectedConnection)

    /** Keys that go only while [permit] is open; on herdr-core as `hc keys` (2.2.0: the mode menu works there too). */
    internal suspend fun sendPermittedKeys(paneId: String, keys: List<String>, connection: Any, permit: WritePermit) =
        runNow(if (core) hcCmd("keys", paneId, *keys.toTypedArray()) else cmd("pane", "send-keys", paneId, *keys.toTypedArray()),
            expectedConnection = connection, permit = permit)

    private fun usageSnapshotCommand(paneId: String, agent: String) =
        "$PY -c ${shq(USAGE_OVERLAY_SNAPSHOT)} $herdr ${shq(paneId)} ${shq(agent)}"

    internal suspend fun readUsageOverlay(paneId: String, agent: String, anyAgent: Boolean = false): UsageOverlay? =
        (readUsageState(paneId, agent, anyAgent) as? UsageState.Open)?.overlay

    /** The guarded live snapshot (occupant digest and visible screen), or null when refused or unreadable. */
    private suspend fun panelSnapshot(paneId: String, agent: String): Pair<String, String>? {
        if (core) return null
        val json = runNow(usageSnapshotCommand(paneId, agent), timeoutSeconds = 5).trim()
        if (json.isEmpty()) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val visible = o.optString("visible")
        if (visible.length > 20_000) return null
        return (o.optString("occupant").ifEmpty { return null }) to visible
    }

    /**
     * The guarded live read for closing an informational panel (2.2.1, [closePanelAndVerify]): a
     * positively identified panel, the composer back, something that must not get an Esc, or can't tell.
     */
    internal suspend fun readPanelState(paneId: String, agent: String): PanelState {
        val (occupant, visible) = panelSnapshot(paneId, agent) ?: return PanelState.Unknown
        return panelStateOf(agent, occupant, visible)
    }

    /**
     * The guarded live read of [paneId] (2.1.4): its usage dialog in a known layout, no dialog, or
     * can't tell (the read was refused: the pane is working, scrolled back, or changed occupant;
     * or the dialog's hint is drawn in a layout the app doesn't know).
     */
    internal suspend fun readUsageState(paneId: String, agent: String, anyAgent: Boolean = false): UsageState {
        // 2.2.1: [anyAgent] when a panel shows on a recent read of an agent outside the usage set.
        if (core || (agent !in USAGE_DIALOG_AGENTS && !anyAgent)) return UsageState.Unknown
        val json = runNow(usageSnapshotCommand(paneId, agent), timeoutSeconds = 5).trim()
        if (json.isEmpty()) return UsageState.Unknown
        val objectJson = runCatching { JSONObject(json) }.getOrNull() ?: return UsageState.Unknown
        val visible = objectJson.optString("visible")
        if (visible.length > 20_000) return UsageState.Unknown
        if (dismissibleUsageFrame(agent, visible) != null) return UsageState.Open(UsageOverlay(objectJson.getString("occupant"), visible))
        return if (usageDialogHint(agent, visible) || visible.isBlank()) UsageState.Unknown else UsageState.Closed
    }

    internal suspend fun closeUsageOverlay(paneId: String, agent: String, overlay: UsageOverlay): Boolean {
        if (core || dismissibleUsageFrame(agent, overlay.visible) == null) return false
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(overlay.visible.toByteArray()).joinToString("") { "%02x".format(it) }
        val expected = "${shq(overlay.occupant)} ${shq(digest)}"
        // Repeat the exact compare on the server, rather than sending an unguarded Escape over SSH.
        // Herdr does not expose an atomic compare-and-send; the final local CLI call is still separate.
        return runNow("${usageSnapshotCommand(paneId, agent)} $expected", timeoutSeconds = 5).trim() == "sent"
    }

    /**
     * Scrolls Claude's /btw panel one step (2.1.4): Down, under the same server-side exact-snapshot
     * check as [closeUsageOverlay], so it goes only to the panel just read. Only for a panel the app opened.
     */
    internal suspend fun scrollBtwOverlay(paneId: String, overlay: UsageOverlay): Boolean {
        if (core || claudeBtwOverlay(overlay.visible) == null) return false
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(overlay.visible.toByteArray()).joinToString("") { "%02x".format(it) }
        return runNow("${usageSnapshotCommand(paneId, "claude")} ${shq(overlay.occupant)} ${shq(digest)} down", timeoutSeconds = 5).trim() == "sent"
    }

    /**
     * Reads what the agent in [pane] has added to its own session log since [cursor], by sending
     * the transcript reader on standard input to the computer's python3. Nothing is installed or
     * written there. Null when the pane has no agent.
     */
    suspend fun transcript(pane: Pane, cursor: String, script: ByteArray): TranscriptRead? {
        val agent = pane.agent ?: return null
        val r = ssh.execInput("$PYZ ${shq(agent)} ${shq(pane.cwd)} ${shq(Transcript.withLive(cursor, pane.id, pane.sessionId))}", z(script))
        if (r.exit != 0 || r.out.isBlank()) {
            val why = r.err.trim().lines().lastOrNull { it.isNotBlank() } ?: "the reader exited ${r.exit}"
            return TranscriptRead(false, why, "", false, false, null, emptyList())
        }
        return Transcript.parseRead(r.out)
    }

    /** One image inside an agent's log, fetched with the reader's image mode. */
    suspend fun logImage(file: String, offset: Long, index: String, script: ByteArray): ByteArray {
        val r = ssh.execInput("$PYZ __image__ ${shq(file)} $offset ${shq(index)}", z(script))
        val o = runCatching { JSONObject(r.out) }.getOrNull() ?: throw HerdrError("The image couldn't be read.", r.exit)
        if (!o.optBoolean("ok")) throw HerdrError(o.optString("why").ifBlank { "The image couldn't be read." }, r.exit)
        return java.util.Base64.getMimeDecoder().decode(o.getString("data"))
    }

    /** One OpenCode blob (an attachment it stored in its database). */
    suspend fun blob(db: String, hash: String, script: ByteArray): ByteArray {
        val r = ssh.execInput("$PYZ __blob__ ${shq(db)} ${shq(hash)}", z(script))
        val o = runCatching { JSONObject(r.out) }.getOrNull() ?: throw HerdrError("The attachment couldn't be read.", r.exit)
        if (!o.optBoolean("ok")) throw HerdrError(o.optString("why").ifBlank { "The attachment couldn't be read." }, r.exit)
        return java.util.Base64.getMimeDecoder().decode(o.getString("data"))
    }

    /** A file on the computer, up to [maxBytes]; "~/" paths are taken from the home folder. */
    suspend fun fetchFile(path: String, maxBytes: Long = 50L * 1024 * 1024): ByteArray {
        val p = if (path.startsWith("~/")) "\"\$HOME\"/" + shq(path.removePrefix("~/")) else shq(path)
        // The type too (1.3.0, from a code review): /dev/zero, /dev/urandom or a pipe has size 0 and
        // would be read forever, so only a regular file is fetched, and never more than [maxBytes].
        // The mode bits (%f, hex), not %F, whose words are translated on a host in another language
        // (1.3.2, from a code review): a regular file has 0x8000 in S_IFMT (0xF000).
        val (mode, size) = ssh.exec("stat -L -c '%f|%s' -- $p").let { r ->
            if (r.exit != 0) throw HerdrError("That file isn't there (${path.substringAfterLast('/')}).", r.exit)
            val f = r.out.trim().split('|')
            (f.getOrNull(0)?.toLongOrNull(16) ?: -1L) to (f.getOrNull(1)?.toLongOrNull() ?: 0L)
        }
        if (mode < 0 || (mode and 0xF000L) != 0x8000L) throw HerdrError("That isn't a regular file, so it isn't fetched.", 0)
        if (size > maxBytes) throw HerdrError("That file is ${size / (1024 * 1024)} MB, over the ${maxBytes / (1024 * 1024)} MB limit.", 0)
        val (exit, bytes) = ssh.execBytes("head -c ${maxBytes} -- $p", maxBytes = (maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (exit != 0) throw HerdrError("That file couldn't be read.", exit)
        return bytes
    }

    /**
     * Copies a file from the phone to ~/.herdrdroid/inbox on the computer and returns its full
     * path there, so a message can point the agent at it.
     */
    suspend fun upload(name: String, bytes: ByteArray): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').take(80).ifBlank { "file" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val rel = ".herdrdroid/inbox/$stamp-$safe"
        val r = ssh.execInput(
            "mkdir -p \"\$HOME/.herdrdroid/inbox\" && cat > \"\$HOME\"/${shq(rel)} && printf %s \"\$HOME\"/${shq(rel)}",
            bytes, timeoutSeconds = 120,
        )
        if (r.exit != 0) throw HerdrError(r.err.trim().ifBlank { "The upload failed (exit ${r.exit})." }, r.exit)
        return r.out.trim()
    }

    /** Automatic usage collection must never write to a pane. No inline-command fallback. */
    internal suspend fun readQuota(paneIds: List<String>, profile: QuotaProfile): List<QuotaItem>? {
        if (profile.cliCommand != null) return cliQuota(profile)
        val parse = profile.screenUsage ?: return null
        if (core) return null
        return paneIds.firstNotNullOfOrNull { pane ->
            try {
                parse(readVisible(pane)).takeIf { it.isNotEmpty() }
            } catch (_: HerdrError) {
                null
            }
        }
    }

    private suspend fun cliQuota(profile: QuotaProfile): List<QuotaItem>? {
        val cmd = profile.cliCommand ?: return null
        val parse = profile.parseCli ?: return null
        val r = ssh.exec("PATH=\"\$HOME/.local/bin:\$PATH\" $cmd", timeoutSeconds = 60)
        if (r.exit != 0) return null
        return runCatching { parse(r.out) }.getOrNull()
    }

    /**
     * Starts an agent in a new tab: makes the tab in [workspace] at [cwd] (created if missing),
     * then `herdr agent start` in its pane, which returns once the agent is ready for input.
     * Returns the new pane's id. Agents started this way are not in the boot launcher.
     */
    suspend fun startAgent(kind: String, cwd: String, label: String, name: String, workspace: String?): String {
        ssh.exec("mkdir -p " + (if (cwd.startsWith("~/")) "\"\$HOME\"/" + shq(cwd.removePrefix("~/")) else shq(cwd)))
        val dir = if (cwd.startsWith("~/")) ssh.exec("printf %s \"\$HOME\"").out.trim() + "/" + cwd.removePrefix("~/") else cwd
        if (core) {
            // A new terminal named for the helper, running the agent; hc returns its address.
            val o = JSONObject(run(hcCmd("run", "--bg", "--json", "--name", label, "--cwd", dir, agentExecutable(kind))).trim().lines().last { it.startsWith("{") })
            return o.getString("address")
        }
        // A herdr server with no workspace yet (nobody has opened the TUI there) can't make a tab:
        // make the first workspace instead and start the agent in its pane.
        var ws = workspace
        if (ws.isNullOrBlank()) {
            val list = runCatching { JSONObject(run(cmd("workspace", "list"))).getJSONObject("result").optJSONArray("workspaces") }.getOrNull()
            if (list == null || list.length() == 0) {
                val made = JSONObject(run(cmd("workspace", "create", "--cwd", dir, "--label", label)))
                val pane = made.getJSONObject("result").getJSONObject("root_pane").getString("pane_id")
                delay(800)
                launchIn(pane, kind, name)
                return pane
            }
            ws = list.getJSONObject(0).optString("workspace_id").ifBlank { null }
        }
        val args = mutableListOf("tab", "create", "--cwd", dir, "--label", label, "--no-focus")
        if (!ws.isNullOrBlank()) args.addAll(listOf("--workspace", ws))
        val created = JSONObject(run(cmd(*args.toTypedArray())))
        val pane = created.getJSONObject("result").getJSONObject("root_pane").getString("pane_id")
        delay(800)
        launchIn(pane, kind, name)
        return pane
    }

    /**
     * Starts an agent in a fresh pane.  herdr starts the kinds it knows (`agent start` waits until
     * the agent is ready); Vibe isn't one of them, so its command is typed into the pane's shell.
     */
    private suspend fun launchIn(pane: String, kind: String, name: String) {
        if (kind in HERDR_KINDS) run(cmd("agent", "start", name, "--kind", kind, "--pane", pane, "--timeout", "90000"))
        else run(cmd("pane", "run", pane, agentExecutable(kind)))
    }

    /** Closes a tab and everything in it (a helper made with [startAgent]). */
    /**
     * Closes [tabId] only over [connection], the one the user confirmed on: a reconnect in between
     * refuses with ConnectionChanged rather than closing on a different link (2.0.1 finding 2).
     */
    internal suspend fun closeTabOn(tabId: String, connection: Any) {
        if (core) {
            runCatching { runNow(hcCmd("kill", tabId), expectedConnection = connection) }
            runNow(hcCmd("rm", tabId), expectedConnection = connection)
            return
        }
        runNow(cmd("tab", "close", tabId), expectedConnection = connection)
    }

    /** The live panes over [connection] only. */
    internal suspend fun panesOn(connection: Any): List<Pane> =
        if (core) corePanes(runNow(hcCmd("ls", "--json"), 5, connection))
        else JSONObject(runNow(cmd("pane", "list"), 10, connection)).getJSONObject("result").getJSONArray("panes")
            .let { a -> (0 until a.length()).map { tuiPane(a.getJSONObject(it)) } }

    suspend fun closeTab(tabId: String) {
        if (core) {
            runCatching { run(hcCmd("kill", tabId)) }
            run(hcCmd("rm", tabId))
            return
        }
        run(cmd("tab", "close", tabId))
    }

    /** Renames a tab: in the app it is the session's name. */
    suspend fun renameTab(tabId: String, label: String) {
        // herdr-core keeps a terminal's name from `hc run --name`; `hc label name=` only adds a label.
        if (core) throw HerdrError("herdr-core can't rename a session.", 0)
        run(cmd("tab", "rename", tabId, label))
    }

    /**
     * Which agent programs this computer has, by `command -v` in a login shell: what the New
     * session sheet offers. Nothing is installed or changed.
     */
    suspend fun installedAgents(): List<String> {
        val names = listOf("claude", "codex", "opencode", "omp", "pi", "hermes", "grok", "copilot", "agy", "gemini", "kiro", "vibe", "muse")
        // A login shell finds most; the usual per-user install folders catch the rest.  The check
        // is by program name (Kiro's is kiro-cli); what comes back is the agent's name.
        val r = ssh.exec("bash -lc " + shq(names.joinToString("; ") {
            val exe = agentExecutable(it)
            "{ command -v $exe || test -x \"\$HOME/.local/bin/$exe\" || test -x \"\$HOME/.$it/bin/$exe\"; } >/dev/null 2>&1 && echo $it"
        }))
        return r.out.lines().map { it.trim() }.filter { it in names }
    }

    /** A folder's entries, folders first, through the computer's python3 (names, kinds, sizes, times). */
    suspend fun listDir(path: String): List<DirEntry> {
        val target = if (path.startsWith("~")) "\"\$HOME\"" + shq(path.removePrefix("~")) else shq(path)
        val py = "import os,sys,json\np=os.path.realpath(sys.argv[1])\nout=[]\n" +
            "for e in sorted(os.scandir(p),key=lambda e:(not e.is_dir(),e.name.lower())):\n" +
            "  try:\n    st=e.stat()\n    out.append([e.name,e.is_dir(),st.st_size,int(st.st_mtime)])\n" +
            "  except Exception:\n    out.append([e.name,e.is_dir(),0,0])\n" +
            "print(json.dumps({'path':p,'entries':out[:2000]}))"
        val r = ssh.execInput("$PY - $target", py.toByteArray())
        if (r.exit != 0) throw HerdrError(r.err.trim().lines().lastOrNull() ?: "That folder couldn't be read.", r.exit)
        return parseDir(r.out)
    }

    /** Past sessions of [agent] in [cwd], newest first, from the reader's session-list mode. */
    suspend fun sessions(agent: String, cwd: String, script: ByteArray): List<PastSession> {
        val r = ssh.execInput("$PYZ __sessions__ ${shq(agent)} ${shq(cwd)}", z(script), timeoutSeconds = 60)
        val o = runCatching { JSONObject(r.out) }.getOrNull() ?: throw HerdrError("The session list couldn't be read.", r.exit)
        if (!o.optBoolean("ok")) throw HerdrError(o.optString("why").ifBlank { "The session list couldn't be read." }, r.exit)
        return parseSessions(o.optJSONArray("sessions") ?: org.json.JSONArray())
    }

    /** The shell form of a path, with "~" taken from the home folder. */
    private fun shellPath(path: String) =
        if (path == "~") "\"\$HOME\"" else if (path.startsWith("~/")) "\"\$HOME\"/" + shq(path.removePrefix("~/")) else shq(path)

    /** Copies a file from the phone into [dir] (never over an existing file) and returns its path. */
    suspend fun uploadTo(dir: String, name: String, bytes: ByteArray): String {
        val safe = safeName(name) ?: throw HerdrError("That name can't be used for a file.", 0)
        val target = shellPath(dir.trimEnd('/') + "/" + safe)
        val r = ssh.execInput(
            "set -C; [ -e $target ] && { echo 'A file with that name is already there.' >&2; exit 3; }; cat > $target && printf %s $target",
            bytes, timeoutSeconds = 120,
        )
        if (r.exit != 0) throw HerdrError(r.err.trim().lines().lastOrNull { it.isNotBlank() } ?: "The upload failed (exit ${r.exit}).", r.exit)
        return r.out.trim()
    }

    /** Makes a folder inside [dir]. */
    suspend fun makeFolder(dir: String, name: String) {
        val safe = safeName(name) ?: throw HerdrError("That name can't be used for a folder.", 0)
        val r = ssh.exec("mkdir -- " + shellPath(dir.trimEnd('/') + "/" + safe))
        if (r.exit != 0) throw HerdrError(r.err.trim().ifBlank { "The folder couldn't be made." }, r.exit)
    }

    /** Renames [path] within its folder, refusing to replace anything already there. */
    suspend fun rename(path: String, newName: String) {
        val safe = safeName(newName) ?: throw HerdrError("That name can't be used.", 0)
        val to = path.substringBeforeLast('/') + "/" + safe
        val r = ssh.exec("mv -n -- ${shellPath(path)} ${shellPath(to)} && [ -e ${shellPath(to)} ] && [ ! -e ${shellPath(path)} ]")
        if (r.exit != 0) throw HerdrError(r.err.trim().ifBlank { "It couldn't be renamed (is that name taken?)." }, r.exit)
    }

    /**
     * Moves [path] into ~/scratch/herdrdroid-recycled-<date>, the recycle bin, instead of
     * deleting it. Nothing is ever deleted from the phone. Returns where it went.
     */
    suspend fun recycle(path: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val bin = "~/scratch/herdrdroid-recycled-$stamp"
        val name = path.trimEnd('/').substringAfterLast('/')
        val clock = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val dest = "$bin/$clock-$name"
        val r = ssh.exec("mkdir -p ${shellPath(bin)} && mv -n -- ${shellPath(path)} ${shellPath(dest)} && [ ! -e ${shellPath(path)} ]")
        if (r.exit != 0) throw HerdrError(r.err.trim().ifBlank { "It couldn't be moved to ~/scratch." }, r.exit)
        return dest
    }

    /**
     * The model each Claude pane last answered with, read from the end of its newest session log
     * ("message":{"model":"claude-…"}), since Claude's footer doesn't show it. One short python3
     * run for all of them; nothing is written. Panes without a log are left out.
     */
    suspend fun logModels(panes: List<Pane>): Map<String, String> {
        val want = panes.filter { it.agent?.lowercase() == "claude" && it.cwd.isNotBlank() }
        if (want.isEmpty()) return emptyMap()
        val py = """
import glob, json, os, re, sys
out = {}
for arg in sys.argv[1:]:
    pid, cwd = arg.split("\t", 1)
    d = os.path.join(os.path.expanduser("~"), ".claude", "projects", re.sub(r"[^A-Za-z0-9]", "-", cwd))
    fs = glob.glob(os.path.join(d, "*.jsonl"))
    if not fs:
        continue
    f = max(fs, key=os.path.getmtime)
    with open(f, "rb") as h:
        h.seek(max(0, os.path.getsize(f) - 262144))
        tail = h.read().decode("utf-8", "replace")
    ms = [m for m in re.findall(r'"model"\s*:\s*"([^"]+)"', tail) if m.startswith("claude")]
    if ms:
        out[pid] = ms[-1]
print(json.dumps(out))
""".trimIndent()
        val args = want.joinToString(" ") { shq(it.id + "\t" + it.cwd) }
        val r = ssh.execInput("$PY - $args", py.toByteArray())
        if (r.exit != 0) return emptyMap()
        val o = runCatching { JSONObject(r.out.trim()) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { prettyClaudeModel(o.getString(it)) }
    }

    // ---- Work under a pane (the reader's __work__, __workcounts__, __tail__ and __stop__ modes) ----

    /** The processes, subagents and background tasks under [pane]. Read-only. */
    suspend fun work(pane: Pane, script: ByteArray): WorkInfo {
        val r = ssh.execInput("$PYZ __work__ ${shq(pane.id)} ${shq(pane.agent.orEmpty())} ${shq(pane.cwd)} ${shq(pane.sessionId.orEmpty())}", z(script), timeoutSeconds = 30)
        if (r.out.isBlank()) throw HerdrError(r.err.trim().lines().lastOrNull { it.isNotBlank() } ?: "The work list couldn't be read.", r.exit)
        return parseWork(r.out)
    }

    /** For the cards: active subagents plus running background tasks, per pane. */
    /** Running subagents per pane, and subagents plus running background tasks ("busy"). */
    suspend fun workCounts(panes: List<Pane>, script: ByteArray): Pair<Map<String, Int>, Map<String, Int>> {
        // Every agent: its running commands count as work too (1.2.5), not only Claude's subagents.
        val list = panes.filter { !it.agent.isNullOrBlank() }
        if (list.isEmpty()) return emptyMap<String, Int>() to emptyMap()
        val arg = org.json.JSONArray(list.map { org.json.JSONArray(listOf(it.id, it.agent.orEmpty(), it.cwd, it.sessionId.orEmpty())) }).toString()
        val r = ssh.execInput("$PYZ __workcounts__ ${shq(arg)}", z(script), timeoutSeconds = 30)
        return runCatching { parseWorkCounts(r.out) to parseBusyCounts(r.out) }.getOrDefault(emptyMap<String, Int>() to emptyMap())
    }

    /** A background task's output since [offset]; -1 starts from its last 64 KB. */
    suspend fun tail(file: String, offset: Long, script: ByteArray): TailRead {
        val r = ssh.execInput("$PYZ __tail__ ${shq(file)} $offset", z(script))
        if (r.out.isBlank()) throw HerdrError(r.err.trim().lines().lastOrNull { it.isNotBlank() } ?: "The output couldn't be read.", r.exit)
        return parseTail(r.out)
    }

    /**
     * Stops process [pid] in [paneId] and everything under it (TERM, or KILL when [force]). The
     * computer checks that the pid is still in that pane first, so a reused pid is never hit.
     */
    suspend fun stopProcess(paneId: String, pid: Int, force: Boolean, script: ByteArray): Int {
        val r = ssh.execInput("$PYZ __stop__ ${shq(paneId)} $pid ${if (force) "KILL" else "TERM"}", z(script))
        val o = runCatching { JSONObject(r.out.trim()) }.getOrNull() ?: throw HerdrError("Stop didn't answer.", r.exit)
        if (!o.optBoolean("ok")) throw HerdrError(o.optString("why").ifBlank { "Couldn't stop it." }, r.exit)
        return o.optInt("sent")
    }

    /** Git branch and changes for each folder that is a repository (one short python3 run). */
    suspend fun gitStatus(cwds: List<String>, script: ByteArray): Map<String, GitInfo> {
        if (cwds.isEmpty()) return emptyMap()
        val arg = org.json.JSONArray(cwds).toString()
        val r = ssh.execInput("$PYZ __git__ ${shq(arg)}", z(script), timeoutSeconds = 40)
        return runCatching { parseGit(r.out) }.getOrDefault(emptyMap())
    }

    /** Messages matching [query] in every agent's logs on the computer, newest first. */
    suspend fun search(query: String, script: ByteArray): List<SearchHit> {
        val r = ssh.execInput("$PYZ __search__ ${shq(query)}", z(script), timeoutSeconds = 60)
        if (r.out.isBlank()) throw HerdrError(r.err.trim().lines().lastOrNull { it.isNotBlank() } ?: "The search didn't finish.", r.exit)
        return parseSearch(r.out)
    }

    /** Confirms the command exists before the app shows any tabs. */
    suspend fun check() {
        if (core) {
            val c = ssh.exec("$hc --version")
            if (c.exit != 0) throw HerdrError("Couldn't run herdr-core's hc on this computer.", c.exit)
            return
        }
        val r = ssh.exec("$herdr --version")
        if (r.exit != 0) {
            throw HerdrError(
                "Couldn't run \"$herdr\" on this computer. Check the herdr path in the server's settings " +
                    "(for example ~/.local/bin/herdr).",
                r.exit,
            )
        }
    }
}

/** Retry a screen read using a smaller recent capture after a failed visible fallback. */
internal suspend fun recoverPaneRead(
    recent: suspend () -> String,
    visible: suspend () -> String,
    shortRecent: suspend () -> String,
    pause: suspend () -> Unit = { kotlinx.coroutines.delay(180) },
): String {
    var problem = HerdrError("the screen came back empty", -2)
    for ((index, read) in listOf(recent, visible, shortRecent).withIndex()) {
        if (index == 2) pause()
        try {
            val text = read()
            if (text.isNotBlank()) return text
        } catch (e: HerdrError) {
            problem = e
        }
    }
    throw problem
}

class HerdrError(message: String, val exit: Int) : Exception(message)

/** A file or folder name typed on the phone, or null when it has a slash, is empty, or is "." or "..". */
fun safeName(name: String): String? {
    val n = name.trim()
    return n.takeIf { it.isNotEmpty() && it.length <= 200 && '/' !in it && '\u0000' !in it && it != "." && it != ".." }
}

/**
 * One past session of an agent, for the history list. [ref] is the session's log file, or its id
 * in [db] for the agents that keep sessions in SQLite (OpenCode, Hermes).
 */
data class PastSession(val ref: String, val db: String?, val whenSec: Long, val title: String?, val kb: Int) {
    /** The cursor that makes the reader show this session instead of the newest one. */
    fun pinCursor(): String = JSONObject().apply {
        if (db != null) { put("pin_sid", ref); put("db", db) } else put("pin", ref)
    }.toString()
}

fun parseSessions(arr: org.json.JSONArray): List<PastSession> = (0 until arr.length()).mapNotNull { i ->
    val s = arr.optJSONObject(i) ?: return@mapNotNull null
    PastSession(
        ref = s.optString("ref").ifBlank { return@mapNotNull null },
        db = s.optString("db").takeIf { !s.isNull("db") && it.isNotBlank() },
        whenSec = s.optLong("when"),
        title = s.optString("title").takeIf { !s.isNull("title") && it.isNotBlank() },
        kb = s.optInt("kb"),
    )
}

/** One entry in a folder listing. [dir] is the folder's resolved path. */
data class DirEntry(val name: String, val isDir: Boolean, val size: Long, val mtime: Long, val dir: String) {
    val path get() = if (dir == "/") "/$name" else "$dir/$name"
}

fun parseDir(json: String): List<DirEntry> {
    val o = JSONObject(json.trim())
    val dir = o.getString("path")
    val arr = o.getJSONArray("entries")
    return (0 until arr.length()).map { i ->
        val e = arr.getJSONArray(i)
        DirEntry(e.getString(0), e.getBoolean(1), e.optLong(2), e.optLong(3), dir)
    }
}

/** The last few lines of a pane: the one-line preview, and the raw lines the footer model comes from. */
data class Peek(val line: String, val tail: String)

/** Box-drawing and status-bar lines that make a poor one-line preview. */
private fun isChrome(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return true
    val boxy = t.count { it in "─│┌┐└┘├┤┬┴┼╭╮╰╯═║╔╗╚╝━┃" }
    return boxy * 2 >= t.length
}

data class Choice(val label: String, val keys: String, val pressEnter: Boolean)
/**
 * A question on screen. [selected] is the number of the highlighted menu option (the ❯ line),
 * when there is one: answers then move the highlight with the arrow keys and press Enter instead
 * of typing a digit, which could land in an option's own text box.
 */
data class Prompt(
    val question: String,
    val choices: List<Choice>,
    val selected: String? = null,
    val escRejects: Boolean = false,
    /** The question laid out for the app: progress steps, heading, command and context (1.2.9). */
    val parts: QuestionParts? = null,
    /**
     * When the screen looks like a question but no parser understood it (1.4.0): the bottom of the
     * screen as it shows, for the fallback card, which offers generic keys instead of choices.
     */
    val region: List<String>? = null,
    /** A list of checkboxes (several can be picked): a choice toggles its row, Enter submits (1.4.0). */
    val multi: Boolean = false,
    /** Codex's menu, whose options name their own keys ("Yes, proceed (y)"): those keys answer it (1.4.2). */
    val shortcuts: Boolean = false,
    /** Buttons side by side (OpenCode's and Vibe's): left and right move between them (1.4.9). */
    val horizontal: Boolean = false,
    /** OpenCode binds number keys to select and submit an option. */
    val numberKeys: Boolean = false,
    /** The prompt can only be reviewed or answered in Terminal. */
    val terminalReview: Boolean = false,
    /** Exact pre-layout approval material. Never rendered or used as history text. */
    internal val approvalIdentity: String? = null,
    /** OpenCode's permission row needs a styled, live focus read before confirming. */
    internal val openCodePermission: Boolean = false,
    /** Exact text of the native single-select question frame, excluding ANSI and row-end padding. */
    internal val openCodeQuestionFrame: String? = null,
    /**
     * The digit of a native OpenCode question's "Type your own answer" row (2.1.2): the question
     * takes free text, typed after that digit opens its editor. Not one of [choices].
     */
    internal val openCodeCustom: String? = null,
    /**
     * An OpenCode question the app reads but only Terminal can answer (2.1.2): several questions
     * (tabs), several answers (checkboxes), or a frame the parser could not prove complete.
     */
    internal val openCodeForm: Boolean = false,
    /** Claude renders this unnumbered button between the checkboxes and "Chat about this". */
    internal val claudeCheckboxAction: Choice? = null,
    /** Native Hermes clarify choices are available only from a guarded Chat card. */
    internal val hermesClarify: Boolean = false,
    /** Codex request_user_input overlay, not a numbered approval menu. */
    internal val codexQuestion: Boolean = false,
    /** Muse Code's quota warning: its highlight shows only in colour, so it is answered from a styled live read (2.1.0). */
    internal val museQuota: Boolean = false,
    /** Claude's session moved to the background behind its sessions list (2.1.1): Esc returns to it. */
    internal val claudeBackground: Boolean = false,
    /**
     * 2.1.4: the pane shows as needing you, but no parser read a question from its screen. The card
     * says so and offers Show terminal; it sends nothing (see [withNeedsYouCard]).
     */
    internal val unreadQuestion: Boolean = false,
    /**
     * 2.2.2: the question is on screen but not yet confirmed (a complete dialog, read the same twice):
     * a neutral card with no options and no keys, only Show terminal (see [readingQuestionCard]).
     */
    internal val readingQuestion: Boolean = false,
) {
    /**
     * Never an empty card (1.4.2): a prompt with no choices always has something to show, the
     * screen region or, when the screen couldn't be read, a note saying so, with the generic keys.
     */
    fun forCard(): Prompt = if (choices.isNotEmpty() || region.orEmpty().any { it.isNotBlank() }) this else copy(region = listOf(UNREAD_SCREEN))

    /** [parts], or the same layout read from the question's text. */
    fun shownParts(): QuestionParts = parts ?: questionParts(listOf(QLine(question)))

    /** Include the native navigation button in Chat only, not in notifications or answer history. */
    internal fun answerRows(): List<Choice> = claudeCheckboxAction?.let { action ->
        val (checkboxes, rest) = choices.partition { isCheckbox(it.label) }
        checkboxes + action + rest
    } ?: choices
}

/** What a card with no options shows when the app couldn't read the screen (1.4.2). */
const val UNREAD_SCREEN = "(The app couldn't read this pane's screen yet. Tap Show terminal, or answer with the keys below.)"

/**
 * The card for a pane the app shows as needing you (2.1.4, Bill: "it showed the orange you know
 * waiting on you icon but didn't actually render the question in chat"): [prompt] when a parser
 * read one; otherwise, while [status] is NEEDS_YOU, a card that says the app couldn't read the
 * question and offers Show terminal, so the orange state never comes without a card. [suppressed]:
 * something else holds the card's place (an answer being typed, a usage dialog, OpenCode's
 * background drawer, an agent error banner).
 */
internal fun withNeedsYouCard(status: Status, prompt: Prompt?, suppressed: Boolean, agent: String): Prompt? =
    prompt ?: if (status == Status.NEEDS_YOU && !suppressed) unreadQuestionCard(agent) else null

/** [withNeedsYouCard]'s card: no options and no keys, only the way to the terminal. */
internal fun unreadQuestionCard(agent: String): Prompt = Prompt(
    "$agent is waiting for you, but the app couldn't read the question from its screen.",
    emptyList(), terminalReview = true, unreadQuestion = true,
)

/** Dispatch identity, unlike history deduplication, must not discard punctuation or command text. */
internal fun cardAnswerStillFits(shown: Prompt, fresh: Prompt?): Boolean =
    !shown.terminalReview && !shown.readingQuestion && fresh != null && !fresh.terminalReview && !fresh.readingQuestion &&
        shown.forCard().region != listOf(UNREAD_SCREEN) &&
        shown.copy(selected = null).forCard() == fresh.copy(selected = null).forCard()

/** One step of Claude's multi-question dialog ("☐ Close 537", "☒ Stop hook", "✔ Submit"). */
data class QTab(val title: String, val done: Boolean, val current: Boolean, val submit: Boolean = false)
enum class QKind { HEADING, TEXT, COMMAND }
data class QBlock(val kind: QKind, val text: String)
/** A question as the app shows it: [tabs] for Claude's multi-question dialog, then [blocks] in screen order. */
data class QuestionParts(val tabs: List<QTab>, val blocks: List<QBlock>) {
    val heading: String? get() = blocks.firstOrNull { it.kind == QKind.HEADING }?.text
    val command: String? get() = blocks.firstOrNull { it.kind == QKind.COMMAND }?.text
    val details: List<String> get() = blocks.filter { it.kind == QKind.TEXT }.map { it.text }
    /** "Question 2 of 4", or null when there is one question. */
    val step: String? get() {
        val qs = tabs.filter { !it.submit }
        if (qs.size < 2) return null
        val at = tabs.indexOfFirst { it.current }
        return if (at in qs.indices) "Question ${at + 1} of ${qs.size}" else "Review and submit (${qs.size} questions)"
    }
}

/** A line of a question as read off the screen: [cmd] when it sat in a box of its own (Grok, Copilot). */
data class QLine(val text: String, val cmd: Boolean = false, val gapBefore: Boolean = false,
                 /** The line began with a single "│" (1.4.8): under a "$ " line, the rest of that command. */
                 val bar: Boolean = false,
                 /**
                  * One line of a command Grok drew row by row (2.1.4): it stays its own line in the
                  * command block, so a multi-line command keeps its lines. [wraps]: Grok wrapped it
                  * ("·" at the end of the row); [cont]: it carries on the row above ("┆" gutter).
                  */
                 val row: Boolean = false, val cont: Boolean = false, val wraps: Boolean = false)

private val TAB_ITEM = Regex("""([☐☒✔✓☑])\s*([^☐☒✔✓☑←→]+)""")
/** "Reason: …", "Environment: local", "Read: /path": a labelled line of context, never the heading. */
private val LABEL_LINE = Regex("""^[A-Z][A-Za-z ]{0,24}:\s""")
/** A piece of a path that ends in a file extension: "culus.json", "8.json". */
private val FILE_END = Regex("""\.[A-Za-z0-9]{1,8}$""")
/** The rest of a wrapped path: no spaces, and path characters in it (1.4.2, from review: not a status word). */
private val PATH_PIECE = Regex("""^[^\s]*[/._\-~][^\s]*$""")
/** A label whose value is a path, address or tool name, shown in a monospace block (1.4.2). */
private val PATH_LABEL = Regex("""^(Destination|Path|File|Files|Directory|Target|URL|Host|Server|Tool):\s+(\S.*)$""")
/** A line that only introduces the command under it. */
private val COMMAND_INTRO = Regex("""^(Requesting permission for:?|Bash command|Command|Run command|Shell command)$""", RegexOption.IGNORE_CASE)
/**
 * Who asks, after a Claude Code 2.1.329 permission dialog's title (2.1.4, from the program's own
 * strings): "· from the general-purpose agent", "· from a subagent", "· from the "x" workflow",
 * "· from a workflow", "· from a remote cloud agent", "· from the x plugin", "· from a plugin".
 */
internal val CLAUDE_REQUEST_SOURCE = Regex("""\s+·\s+(from (?:the \S.{0,60} agent|a subagent|the ".{1,60}" workflow|a workflow|a remote cloud agent|the \S.{0,60} plugin|a plugin))\s*$""")
/** A note in a Claude command title's parentheses: "Bash command (unsandboxed)", "(runs on devbox)". */
private val PERMISSION_TITLE_NOTE = Regex("""\s*\([^)]{1,60}\)\s*$""")
/** Claude Code 2.1.329's command dialog titles, with or without a note or who asks (2.1.4). */
private val CLAUDE_COMMAND_TITLE = Regex("""^(Bash command|Run command|PowerShell command|Run PowerShell command)(\s*\([^)]{1,60}\))?(\s+·\s+.{1,80})?$""")
/** Shell signs that make a line read as a command rather than a description. */
private val SHELL_SIGN = Regex("""[/|&><$='"`\\;*~]|\s-{1,2}[A-Za-z]""")

/**
 * Whether [t], the line after a command title, is Claude Code 2.1.329's description of the command
 * that [next] holds (2.1.4): a capitalised sentence with no shell signs, over a line (after Claude's
 * dashed rule, captured) that has them or starts in lower case. The older layout (the command first)
 * reads as before.
 */
private fun claudeDescriptionAbove(t: String, next: QLine?): Boolean {
    if (next == null || t.isEmpty() || !t.first().isUpperCase() || !t.contains(' ')) return false
    if (SHELL_SIGN.containsMatchIn(t)) return false
    val n = next.text.trim()
    return n.isNotEmpty() && (SHELL_SIGN.containsMatchIn(n) || n.first().isLowerCase())
}

/** Vibe's "Permission for the file_system.bash tool (…)": the command is the next line. */
private val TOOL_PERMISSION = Regex("""^Permission for the \S+ tool(\s*\(.*\))?$""")
private const val MARKS = "›❯◆◈●○•▸➤▶↓→│┃╭╰╮╯─ "

/**
 * Claude's multi-question tab bar, "←  ☐ Close 537  ☒ Stop hook  ✔ Submit  →", as steps. The
 * current one is the tab a styled read marked ([TAB_CURRENT_MARK], 2.1.1: Left and Right move
 * between tabs without answering, and only the colour shows where); on a plain read it is the first
 * unanswered question (Submit once all are answered). The second value is any question text that
 * followed the bar on the same line.
 */
fun parseTabBar(line: String): Pair<List<QTab>, String>? {
    val unmarked = line.replace(TAB_CURRENT_MARK.toString(), "")
    val t = unmarked.trim()
    // Where the mark sat, as an index into [t]: the mark goes just before the current tab's box.
    val markAt = line.indexOf(TAB_CURRENT_MARK).takeIf { it >= 0 && line.indexOf(TAB_CURRENT_MARK, it + 1) < 0 }
        ?.let { it - (unmarked.length - unmarked.trimStart().length) }
    val items = TAB_ITEM.findAll(t).toList()
    if (items.size < 2) return null
    val titles = items.map { it.groupValues[2].trim() }
    if (!t.startsWith("←") && titles.none { it.equals("Submit", true) }) return null
    val rest = if (t.contains("→")) t.substringAfterLast("→").trim() else ""
    val marked = items.indexOfFirst { it.range.first == markAt }
    val all = items.mapIndexed { i, m ->
        val sub = titles[i].equals("Submit", true)
        QTab(titles[i], done = !sub && m.groupValues[1] != "☐", current = i == marked, submit = sub)
    }
    var tabs = all.filter { it.title.isNotEmpty() }
    if (tabs.none { it.current }) {
        val cur = tabs.indexOfFirst { !it.done && !it.submit }.takeIf { it >= 0 } ?: tabs.indexOfFirst { it.submit }
        if (cur >= 0) tabs = tabs.mapIndexed { i, q -> if (i == cur) q.copy(current = true) else q }
    }
    return tabs to rest
}

/**
 * A styled row of Claude's question bar as plain text with [TAB_CURRENT_MARK] before the current
 * tab's box, or null when the row is not such a bar or its styles don't single out one tab (2.1.1).
 * Claude Code 2.1.329 draws the current tab on a background colour ("ESC[38;5;16mESC[48;5;153m ☐
 * Restart ESC[39mESC[49m"); the other tabs and the arrows have none.
 */
internal fun markCurrentTab(row: String): String? {
    val chars = styledChars(row)
    val plain = chars.joinToString("") { it.ch.toString() }
    if (plain != row.replace(ANSI_SEQ, "") || plain.indexOf(TAB_CURRENT_MARK) >= 0) return null
    if (parseTabBar(plain) == null) return null
    val items = TAB_ITEM.findAll(plain).toList()
    val lit = items.filter { chars[it.range.first].background.isNotEmpty() }
    if (lit.size != 1) return null
    // A bar drawn wholly on a background (a theme, a selection) singles nothing out.
    val left = plain.indexOf('←')
    if (left >= 0 && chars[left].background.isNotEmpty()) return null
    val at = lit.single().range.first
    return plain.substring(0, at) + TAB_CURRENT_MARK + plain.substring(at)
}

/** The index just past the question's first "?" outside parentheses, or -1. */
private fun firstQuestionEnd(s: String): Int {
    var depth = 0
    s.forEachIndexed { i, c ->
        when (c) {
            '(' -> depth++
            ')' -> depth = maxOf(0, depth - 1)
            '?' -> if (depth == 0 && (i == s.lastIndex || s[i + 1] == ' ')) return i + 1
        }
    }
    return -1
}

/**
 * Lays a question out for the app (1.2.9, Bill: "improve the formatting ... Format questions from
 * other agents nicely too"): Claude's tab bar becomes steps; the question is the heading; a command
 * (Codex's "$ …", Grok's and Copilot's boxed line, the line under Antigravity's "Requesting
 * permission for:" or Vibe's "Permission for the … tool") is its own block; the rest is context.
 * Terminal-wrapped lines join into one paragraph; a blank line, a "Label: " line or a line ending
 * in "?" starts a new one. No marker or box characters are kept.
 */
fun questionParts(lines: List<QLine>): QuestionParts {
    var tabs = emptyList<QTab>()
    val body = mutableListOf<QLine>()
    for (l in lines) {
        val bar = if (tabs.isEmpty()) parseTabBar(l.text) else null
        if (bar != null) {
            tabs = bar.first
            if (bar.second.isNotEmpty()) body += QLine(bar.second, gapBefore = true)
        } else body += l
    }
    /** [inPlace]: a path or address block that stays with its label among the context (1.4.2). */
    class Para(var text: String, val cmd: Boolean, val inPlace: Boolean = false, val rows: Boolean = false)
    val paras = mutableListOf<Para>()
    var cmdNext = false
    // The current command block began with "$ ": its "│ ..." lines are the rest of it (1.4.8, from Grok's
    // review: Muse draws a script's lines under "$ python3 - <<'PY'" that way).
    var dollarBlock = false
    for ((idx, l) in body.withIndex()) {
        // A Grok command row (2.1.4, Bill: "parse them nicely maybe as newlines"): a line of its own
        // in the command block, its indentation and every character kept, a ")" or "}" row too.
        if (l.row) {
            val t = l.text.trimEnd()
            if (t.isBlank()) continue
            val last = paras.lastOrNull()
            if (last != null && last.rows && !l.gapBefore) last.text += "\n" + t else paras += Para(t, true, rows = true)
            cmdNext = false; dollarBlock = false
            continue
        }
        val answerRow = l.text.trim().startsWith("→")
        var t = l.text.trim().trimStart { it in MARKS }.trimEnd { it in "│┃╮╯─ " }.trim()
        if (t.isEmpty() || !t.any(Char::isLetterOrDigit)) continue
        // Claude's review step: "● Close 537" over "→ Close it" reads "Close 537: Close it".
        if (answerRow && paras.lastOrNull()?.cmd == false) { paras.last().text += ": " + t; continue }
        if (COMMAND_INTRO.matches(t) || CLAUDE_COMMAND_TITLE.matches(t) && CLAUDE_REQUEST_SOURCE.find(t) == null) { cmdNext = true; continue }
        // 2.1.4: Claude Code 2.1.329 names who asks after the title ("Bash command · from the
        // general-purpose agent"): the title still introduces the command, and the asker is context.
        val source = CLAUDE_REQUEST_SOURCE.find(t)
        if (source != null) {
            val title = t.substring(0, source.range.first).trim()
            if (COMMAND_INTRO.matches(title.replace(PERMISSION_TITLE_NOTE, ""))) cmdNext = true
            paras += Para(if (title.isEmpty()) source.groupValues[1].replaceFirstChar { it.uppercase() }
                else "$title, ${source.groupValues[1]}", false)
            continue
        }
        // Claude Code 2.1.329 draws the command's description above the command (2.1.4, from the
        // program): a sentence right over a line that reads as a command is text; the command follows.
        if (cmdNext && claudeDescriptionAbove(t, body.getOrNull(idx + 1))) { paras += Para(t, false); continue }
        // The dialog's own question is never the command, and starts a paragraph of its own (2.1.4).
        val dialogQuestion = t.startsWith("Do you want to ")
        if (dialogQuestion) cmdNext = false
        // "Destination: /path/to/file" (Codex's edit card), "Path:", "File:", "URL:": the label as
        // text and the value in a monospace block of its own (1.4.2).
        val pathLabel = PATH_LABEL.find(t)
        if (pathLabel != null) {
            paras += Para(pathLabel.groupValues[1] + ":", false)
            paras += Para(pathLabel.groupValues[2].trim(), true, inPlace = true)
            continue
        }
        val dollar = Regex("""^\$\s+(.+)$""").find(t)
        // Every line after it up to a blank line, the "│" lines and the wrapped ones alike (from Grok's
        // review), except Muse's own "Stage 1/2" and "Current argv: [...]" under the command.
        val goesOn = dollarBlock && !l.gapBefore && paras.lastOrNull()?.cmd == true && !CMD_TRAILER.containsMatchIn(t)
        val isCmd = l.cmd || dollar != null || cmdNext || goesOn
        cmdNext = false
        dollarBlock = dollar != null || goesOn
        if (dollar != null) t = dollar.groupValues[1].trim()
        val last = paras.lastOrNull()
        if (isCmd) {
            if (last != null && last.cmd && !l.gapBefore && dollar == null) last.text += " " + t else paras += Para(t, true)
            continue
        }
        // A long path wrapped onto the next line goes on in its block (1.4.2).
        // The rest of a path wrapped at the terminal's width: a piece with a slash or a file extension,
        // not a new path of its own (starting with / or ~) after a path that already ended (1.4.2, from review).
        if (last != null && last.inPlace && !l.gapBefore && PATH_PIECE.matches(t) && !LABEL_LINE.containsMatchIn(t) &&
            t.first() !in "/~" && (
                (t.contains('/') && last.text.last() in "-_/") ||
                (!t.contains('/') && FILE_END.containsMatchIn(t) && (last.text.last() in "-_." ||
                    (last.text.last().isLetterOrDigit() && !last.text.substringAfterLast('/').contains('.') && (t.first().isLowerCase() || t.first().isDigit())))))) { last.text += t; continue }
        // Grok's title ("Check SSH to devbox: grok usage ...") came joined to its command before.
        // A capitalised line after a "Label: …" line is a new sentence ("Reason: outside workspace"
        // over "Allow access to this file?"); a wrapped label line goes on in lower case.
        val afterLabel = last != null && LABEL_LINE.containsMatchIn(last.text) && t.first().isUpperCase()
        val bullet = l.text.trim().first() in "●•◆"
        if (last == null || last.cmd || l.gapBefore || afterLabel || bullet || dialogQuestion || LABEL_LINE.containsMatchIn(t) || last.text.endsWith("?") || last.text.endsWith(":"))
            paras += Para(t, false)
        else last.text += " " + t
        if (TOOL_PERMISSION.matches(paras.last().text)) cmdNext = true
    }
    val texts = paras.withIndex().filter { !it.value.cmd }
    val pick = texts.firstOrNull { !LABEL_LINE.containsMatchIn(it.value.text) && firstQuestionEnd(it.value.text) > 0 }
        ?: texts.firstOrNull { !LABEL_LINE.containsMatchIn(it.value.text) } ?: texts.firstOrNull()
    // The heading first (with the explanation that followed it on the same paragraph), then the
    // command, then the rest of the context in screen order.
    val head = mutableListOf<QBlock>()
    val cmds = mutableListOf<QBlock>()
    val rest = mutableListOf<QBlock>()
    paras.forEachIndexed { i, p ->
        when {
            p.cmd && p.inPlace -> rest += QBlock(QKind.COMMAND, p.text)
            p.cmd -> cmds += QBlock(QKind.COMMAND, p.text)
            i == pick?.index -> {
                val end = firstQuestionEnd(p.text)
                if (end in 1 until p.text.length) {
                    head += QBlock(QKind.HEADING, p.text.substring(0, end).trim())
                    head += QBlock(QKind.TEXT, p.text.substring(end).trim())
                } else head += QBlock(QKind.HEADING, p.text.trimEnd(':'))
            }
            else -> rest += QBlock(QKind.TEXT, p.text)
        }
    }
    return QuestionParts(tabs, (head + cmds + rest).filter { it.text.isNotBlank() })
}

/**
 * The hints agents draw in an empty input line (1.4.4, Bill: the warning said "press up to edit
 * queued messages"). Each is the agent's own text, confirmed from its program or a live pane:
 * Claude Code's queued-message hints (2.1.325), Codex's empty composer (0.169, and the screen
 * library) and Kiro's (a live pane, with its "↵"). Keyed by herdr's agent name. Hints that read
 * like a real request (Hermes's example prompts, "Find and fix a failing test") are left out, so
 * the same words typed at the computer are never taken for an empty line (from Grok's review).
 * 2.1.1 (Bill, 2026-11-20: "don't see the grey suggestion template text as active text"): Claude's
 * background-session prompt, Codex's follow-up hint and Hermes's two hints that are not requests
 * (its locales/en.yaml placeholder p01 and p11), from the program and a live pane.
 */
private val COMPOSER_HINTS: Map<String, Set<String>> = mapOf(
    "claude" to setOf(
        "Press up to edit queued messages",
        "Press up to edit queued messages, Enter to send them immediately",
        "Press up to select a queued message to edit, or Enter to send them now",
        "Press up to select a queued message, then Enter to edit it",
        "describe a task for a new session",
    ),
    "codex" to setOf("Ask Codex to do anything", "Ask a follow-up question"),
    "kiro" to setOf("ask a question or describe a task ↵"),
    "hermes" to setOf("Ask anything, or type / for commands…", "Type / to browse commands, or Ctrl+P for the palette"),
)

/**
 * Hermes's example prompts (locales/en.yaml placeholder p02 to p10), which read like requests and
 * so never count on a plain read. On a styled read they count as a placeholder only when the whole
 * row is one of them and every character is drawn in a colour, faint or italic: Hermes draws typed
 * text in the terminal's default colour ("input-area": "") and these in its dim skin colour, italic
 * (2.1.1; a skin without the italic still reads as empty, the same words in normal type as typing).
 */
private val STYLED_HINTS: Map<String, Set<String>> = mapOf(
    "hermes" to setOf(
        "Summarize what's in this folder", "Draft a reply to the last email in my inbox",
        "Plan a feature, then build it step by step", "Find and fix a failing test",
        "Research this topic and write me a brief", "What changed in this repo recently?",
        "Turn these notes into a to-do list", "Explain this error and how to fix it",
        "Set a reminder or schedule a recurring task",
    ),
)

private fun isStyledHint(agent: String?, text: String): Boolean {
    val key = agent?.lowercase() ?: return false
    val norm = text.trim().replace(Regex("""\s+"""), " ")
    return norm in COMPOSER_HINTS[key].orEmpty() || norm in STYLED_HINTS[key].orEmpty()
}

/**
 * True when [text], read from [agent]'s input line, is exactly that agent's own hint for an empty
 * line (1.4.4). Only the pane's known agent counts, and only the whole hint: a cut or longer line,
 * or a pane with no known agent, is typing (from Grok's review: never type over real text).
 */
fun isComposerHint(agent: String?, text: String): Boolean {
    val hints = agent?.lowercase()?.let { COMPOSER_HINTS[it] } ?: return false
    return text.trim().replace(Regex("""\s+"""), " ") in hints
}

/**
 * What is typed in the agent's input line now, read off its screen (1.4.0): the lowest line that
 * starts with a prompt glyph (❯ › > »), without the glyph and box edges; "" when it is empty; null
 * when no input line shows (a question card or a full-screen view), so nothing can be judged.
 * An agent's own hint in an empty line reads as "": on a screen read with its styles (1.4.6), any
 * text drawn entirely as a placeholder ([isPlaceholderText]: faint, or a grey colour); on a plain
 * screen, the known hints ([isComposerHint], 1.4.4).
 */
fun inputLineOf(screen: String, agent: String? = null, menuWrite: Boolean = false): String? {
    // OpenCode draws no prompt glyph (2.1.5): its box, positively found empty, is an empty line;
    // otherwise nothing is judged, as before.
    if (isOpenCode(agent) && openCodeComposerEmpty(screen)) return ""
    // 2.2.2: pi, Oh My Pi and Copilot draw no prompt glyph either; their boxes are found by shape, and
    // a layout their reader doesn't know is no input line (never the generic glyph search below).
    // A blank Oh My Pi or Copilot box can hold typed spaces the screen can't show (no cursor cell), so
    // for a normal send it is typing (held); only a menu write, which clears the line with the agent's
    // own Ctrl+U before its command, takes it as empty (2.2.2, from Grok's review).
    if (AgentComposers.handles(agent)) return AgentComposers.line(screen, agent)?.let {
        if (it.isEmpty() && !menuWrite && AgentComposers.clearKey(agent) != null) AgentComposers.UNSURE_BLANK else it
    }
    val (raw, typed) = inputRow(screen) ?: return null
    if (typed.isEmpty()) return ""
    // A row read with its styles is judged only by how it is drawn: the same words as a known hint
    // in normal type are typing (from Grok's review). The hint list is for plain reads.
    if (raw.contains('\u001B')) return if (isPlaceholderText(raw, typed, agent)) "" else typed
    return if (isComposerHint(agent, typed)) "" else typed
}

private val INPUT_LEAD = Regex("""^[\s\u00A0│┃|]*[>❯›»][\s\u00A0]*""")
private val ANSI_SEQ = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]|\u001B\][^\u0007\u001B]*(?:\u0007|\u001B\\)""")

/** The composer's row as read (styles kept) and the text typed in it, or null with no input line (see [inputLineOf]). */
private fun inputRow(screen: String): Pair<String, String>? {
    val lead = INPUT_LEAD
    // Not an input line (from a review): a menu row ("❯ 1. Yes"), a ">>" or ">>>" prompt or nested quote.
    val notInput = Regex("""^[\s\u00A0│┃|]*(?:[>❯›»]\s*\d{1,2}[.)]\s|>>)""")
    // 1.4.1 (from a code review): colour codes taken out first, and only the composer's own row
    // counts: one between two rule lines (the box agents draw around it), else one inside a
    // "│ ... │" frame, else one with at most a three-line footer under it. A quoted or diff "> " row in the output above is not typing.
    val rawRows = withoutClaudeAgentList(screen.lines().map { it.replace("\r", "") }).takeLast(14)
    val rows = rawRows.map { it.replace(ANSI_SEQ, "") }
    val rule = Regex("""^[\s\u00A0╭╰├└┌]*[─━═╌┄-]{4,}""")
    fun isRule(i: Int) = i in rows.indices && rule.containsMatchIn(rows[i])
    val candidates = rows.indices.filter { lead.containsMatchIn(rows[it]) && !notInput.containsMatchIn(rows[it]) }
    val framed = Regex("""^[\s\u00A0]*([│┃|]).*\1[\s\u00A0]*$""")
    val i = candidates.lastOrNull { isRule(it - 1) && isRule(it + 1) }
        ?: candidates.lastOrNull { framed.matches(rows[it]) }
        ?: candidates.lastOrNull { c -> rows.drop(c + 1).count { it.isNotBlank() && !rule.containsMatchIn(it) } <= 3 }
        ?: return null
    val row = rows[i]
    val content = if (framed.matches(row)) row.trimEnd().dropLast(1) else row
    return rawRows[i] to content.replace(lead, "").trim()
}

/**
 * 2.2.1 (Bill: "try to be robust to harness version changes"): the chips of the status row under
 * an agent's boxed input line, found by structure, not by wording or version: the lowest prompt
 * row with a rule right above it, the next rule under it (the box's bottom edge), then up to three
 * non-blank rows under that, each split at "·". Claude Code 2.1.329 and 2.1.330 draw "⏵⏵ auto mode
 * on · 3 shells · ↓ to manage" there, with "esc to interrupt" among the chips while a turn runs.
 * Empty when the screen shows no such box.
 */
internal fun composerFooterChips(screen: String): List<String> {
    val rows = withoutClaudeAgentList(screen.lines().map { it.replace("\r", "") }).takeLast(14).map { it.replace(ANSI_SEQ, "") }
    val rule = Regex("""^[\s\u00A0╭╰├└┌]*[─━═]{4,}""")
    val prompt = rows.indices.lastOrNull { i ->
        i > 0 && INPUT_LEAD.containsMatchIn(rows[i]) && rule.containsMatchIn(rows[i - 1])
    } ?: return emptyList()
    val close = (prompt + 1 until rows.size).firstOrNull { rule.containsMatchIn(rows[it]) } ?: return emptyList()
    return rows.drop(close + 1).filter { it.isNotBlank() }.take(3)
        .flatMap { row -> row.split('·', '•').map { it.trim() }.filter { it.isNotEmpty() } }
}

private val INTERRUPT_CHIP = Regex("""\besc\b.{0,12}\binterrupt""", RegexOption.IGNORE_CASE)

/**
 * 2.2.1: the status row under the input box offers to interrupt a running turn ("esc to
 * interrupt", in any chip and any order), so the agent is working whatever its status says.
 * Background work chips ("3 shells", "1 subagent", "2 background tasks", "↓ to manage") are not
 * a running turn.
 */
internal fun composerFooterSaysWorking(screen: String): Boolean = composerFooterChips(screen).any { INTERRUPT_CHIP.containsMatchIn(it) }

/**
 * What is typed in an agent's input box, all of its lines (2.1.0, Bill: "when a draft is in the pane
 * text box, maybe show it in chat view and give me the option to send or clear it there"). The first
 * row is found and judged the way [inputLineOf] judges it (prompt glyph, box edges, placeholders);
 * the rows under it count as the draft only inside a box whose end the screen shows:
 * - between box rules (Claude Code, Hermes): down to the next rule of the same span as the one
 *   above the prompt, with only a short footer under it. A typed "----" or a shorter rule inside
 *   the draft is text, not the box's edge. Claude indents its continuation rows under the text;
 *   Hermes's prompt_toolkit box starts them at column 0.
 * - inside a "│ ... │" frame (Grok, older Claude): framed rows down to the bottom edge.
 * - Codex, unboxed, its "›" at column 0: indented rows down to the blank row above its footer.
 * Text drawn as a placeholder or a ghost suggestion (faint, grey, or Hermes's italic) is left out.
 * [ComposerDraft.bounded] is false when rows under the first could be more of the draft or could be
 * the agent's footer; the app then shows what it read but never sends keys for it.
 * [ComposerDraft.menu] is true when a list of commands or suggestions shows under the box, where
 * Enter would pick the highlighted one. Null when the input box is empty, shows only a placeholder,
 * or can't be found.
 */
internal data class ComposerDraft(val text: String, val bounded: Boolean, val menu: Boolean = false)

private const val MAX_DRAFT_ROWS = 30
/** A box rule: only box-drawing lines (no ASCII "-"), at least 20 wide; a corner may start or end it. */
private val DRAFT_BOX_RULE = Regex("""^([\s ]*)([╭╰├└┌]?[─━═]{20,}[╮╯┤┘┐]?)[\s ]*$""")
/**
 * The rule above Claude Code's input box with the session's title in it (2.1.1, Claude Code
 * 2.1.329: "──── … ──── MiniPC2 ─" after /rename or a named session). Only the rule that opens the
 * box may carry a title; the rule that closes it is a plain [DRAFT_BOX_RULE].
 */
private val DRAFT_TITLED_RULE = Regex("""^([\s\u00A0]*)([╭├┌]?[─━═]{20,} [^─━═\s][^─━═]{0,60}? [─━═]{1,4}[╮┤┐]?)[\s\u00A0]*$""")
/** A row of the list Claude Code 2.1.329 draws under its box while helpers run ("  ● main", "  ◯ general-purpose  …  7s"). */
private val CLAUDE_AGENT_LIST_ROW = Regex("""^ {2}[●◯○◉✓✔✗✘] \S.*$""")
/** Claude Code 2.1.329's mode row under its input area: "  ⏵⏵ auto mode on · 4 shells · ↓ to manage", "  ⏸ plan mode on". */
private val CLAUDE_MODE_ROW = Regex("""^ {1,4}(⏵⏵|⏸|⏵)\s+\S.*$""")
/** The last rows of a Claude dialog: its key hints, or one of its option rows. */
private val CLAUDE_DIALOG_END = Regex("""\bEsc to (cancel|reject)\b|^Esc cancel\b|Enter to select|↑/↓ to navigate|^([❯›]\s*)?\d{1,2}[.)]\s+\S""", RegexOption.IGNORE_CASE)

/**
 * [text] without the rows Claude Code 2.1.329 can keep under a dialog (2.1.4, Bill: "a claude pane
 * ... showed the orange you know waiting on you icon but didn't actually render the question in
 * chat"): its mode row ("⏵⏵ auto mode on · 4 shells") and the list of the session and its helpers
 * ("● main", "◯ general-purpose  …"). Those rows have words and Claude's own bullets, so the parser
 * took them for the agent going on after the dialog and dropped the dialog as an old one. Only a
 * trailing block of such rows and blank rows goes, and only right under a dialog's hints or options.
 */
/**
 * The hint row under Claude Code 2.1.329's permission dialogs: "Esc to cancel", "Esc to cancel · Tab
 * to amend" (2.1.4, captured against a mock API; "ctrl+e to explain" from the program). Claude draws
 * nothing under an open dialog, so this row last on screen means the dialog is open now.
 */
private val CLAUDE_PERMISSION_FOOTER_LEAD = Regex("""^Esc to cancel$""", RegexOption.IGNORE_CASE)

/**
 * 2.2.3 (Bill: "Claude tab herdrdroid failed to parse that yes/no question"): the hint row read by
 * its structure, so later chips keep working: "Esc to cancel" first, then any number of " · " chips
 * that are each a key hint ([HINT_SEGMENT]: "Tab to amend", "ctrl+e to explain", Claude Code
 * 2.1.330's "ctrl+x ctrl+k twice to stop background agents"). 2.1.4 took only the first two chips.
 */
internal fun isClaudePermissionFooter(row: String): Boolean {
    val chips = row.trim().trim('│', '┃', '|').trim().split(Regex("""\s+·\s+"""))
    return CLAUDE_PERMISSION_FOOTER_LEAD.matches(chips.first()) && chips.drop(1).all { HINT_SEGMENT.containsMatchIn(it.trim()) }
}

/**
 * A rule across the screen, plain or with the session's title in it ("──── … ──── Session ─"). 2.2.3:
 * Claude Code 2.1.330 draws its input area's closing rule, with the title, under an open dialog's
 * hint row, so the hint row is no longer the last row on screen.
 */
private val CLAUDE_CLOSING_RULE = Regex("""^[\s\u00A0]*[╭╰├└┌]?[─━═]{20,}( [^─━═\s][^─━═]{0,60}? [─━═]{1,4})?[╮╯┤┘┐]?[\s\u00A0]*$""")

/**
 * Whether [text] ends in Claude's permission-dialog hint row ([isClaudePermissionFooter]), 2.1.4;
 * 2.2.3: with the closing rule Claude Code 2.1.330 draws under it.
 */
internal fun claudePermissionFooterAtBottom(text: String): Boolean {
    val rows = withoutClaudeChromeUnderDialog(stripAnsi(text)).lines().filter { it.isNotBlank() }
    val end = rows.indexOfLast { !CLAUDE_CLOSING_RULE.matches(it) }
    // At most two rules under the hint row: the closing rule (and a frame's own bottom edge).
    return end >= 0 && rows.size - 1 - end <= 2 && isClaudePermissionFooter(rows[end])
}

internal fun withoutClaudeChromeUnderDialog(text: String): String {
    val rows = text.lines()
    var k = rows.size
    var chrome = false
    while (k > 0) {
        val r = rows[k - 1].trimEnd()
        if (r.isBlank()) { k--; continue }
        if (CLAUDE_AGENT_LIST_ROW.matches(r) || CLAUDE_MODE_ROW.matches(r)) { chrome = true; k--; continue }
        break
    }
    if (!chrome || k == 0) return text
    val end = rows.take(k).filter { !isChrome(it) }.takeLast(3).map { it.trim().trim('│', '┃', '|').trim() }
    return if (end.any { CLAUDE_DIALOG_END.containsMatchIn(it) }) rows.take(k).joinToString("\n") else text
}
private val CLAUDE_LIST_RULE = Regex("""^[\s\u00A0╭╰├└┌]*[─━═]{20,}""")

/**
 * [rows] without the list of the session and its background helpers that Claude Code 2.1.329
 * draws under its input box and status row (2.1.1: with it, the box looked too far from the
 * bottom to be the composer, and a typed draft showed no card). Only a trailing block is taken
 * off: list rows and blank rows, the first of them "  ● ", no prompt glyph, right under a short
 * footer (at most three rows) that sits under a box rule. Anything else is left as it is.
 */
private fun withoutClaudeAgentList(rows: List<String>): List<String> {
    val plain = rows.map { it.replace(ANSI_SEQ, "") }
    var start = plain.size
    while (start > 0 && (plain[start - 1].isBlank() || CLAUDE_AGENT_LIST_ROW.matches(plain[start - 1]))) start--
    val block = plain.subList(start, plain.size)
    val listed = block.filter { it.isNotBlank() }
    if (listed.isEmpty() || !listed.first().startsWith("  ● ")) return rows
    if (listed.any { INPUT_LEAD.containsMatchIn(it) }) return rows
    // The footer between the box's closing rule and the list: one to three rows.
    val footerRows = (start - 1 downTo maxOf(0, start - 4)).takeWhile { !CLAUDE_LIST_RULE.containsMatchIn(plain[it]) }
    val ruleAt = (footerRows.lastOrNull() ?: start) - 1
    if (footerRows.isEmpty() || footerRows.size > 3 || ruleAt < 0 || !CLAUDE_LIST_RULE.containsMatchIn(plain[ruleAt])) return rows
    return rows.subList(0, start)
}

private val DRAFT_FRAMED = Regex("""^[\s ]*([│┃|]).*\1[\s ]*$""")
private val DRAFT_FRAME_EDGE = Regex("""^[\s ]*[│┃|]""")
private val DRAFT_BOTTOM_EDGE = Regex("""^[\s ]*[╰└]""")
private val DRAFT_TOP_EDGE = Regex("""^[\s ]*[╭┌]""")
private val DRAFT_NOT_INPUT = Regex("""^[\s │┃|]*(?:[>❯›»]\s*\d{1,2}[.)]\s|>>)""")
/** The prompt glyph at column 0, as Codex and other unboxed agents draw it. */
private val DRAFT_LEAD_AT_ZERO = Regex("""^[>❯›»][\s ]*""")
/** A row of a command or suggestion list ("/clear  Clear the conversation", "@src/main.kt"). */
private val DRAFT_MENU_ROW = Regex("""^[\s │┃|❯›>▸▶]*[/@][\w.:/-]+(\s{2,}\S.*)?[\s │┃|]*$""")

/** [text], a piece of the stripped [raw] row, without a ghost suggestion at its end (styled reads only). */
private fun withoutGhost(raw: String, text: String, from: Int, agent: String?): String {
    if (!raw.contains('\u001B') || text.isEmpty()) return text
    val chars = styledChars(raw)
    val plain = chars.joinToString("") { it.ch.toString() }
    if (plain != raw.replace(ANSI_SEQ, "")) return text
    val start = plain.indexOf(text, from).takeIf { it >= 0 } ?: return text
    val hermes = agent?.lowercase() == "hermes"
    var keep = text.length
    while (keep > 0) {
        val c = chars[start + keep - 1]
        if (c.ch.isWhitespace() || c.faint || c.grey || (hermes && c.italic)) keep-- else break
    }
    return text.substring(0, keep).trimEnd()
}

internal fun composerDraftOf(screen: String, agent: String? = null): ComposerDraft? {
    val rawRows = withoutClaudeAgentList(screen.lines().map { it.replace("\r", "") }).takeLast(48)
    val rows = rawRows.map { it.replace(ANSI_SEQ, "") }
    /** Where a box rule starts and how wide it is, or null for any other row. */
    fun span(i: Int): IntRange? = rows.getOrNull(i)?.let(DRAFT_BOX_RULE::matchEntire)?.let {
        val at = it.groupValues[1].length
        at until at + it.groupValues[2].length
    }
    /** [span] for the rule that opens a box, which may carry the session's title (Claude Code 2.1.329). */
    fun openSpan(i: Int): IntRange? = span(i) ?: rows.getOrNull(i)?.let(DRAFT_TITLED_RULE::matchEntire)?.let {
        val at = it.groupValues[1].length
        at until at + it.groupValues[2].length
    }
    fun sameSpan(a: IntRange, b: IntRange) = kotlin.math.abs(a.first - b.first) <= 2 && kotlin.math.abs(a.last - b.last) <= 3
    fun isEdge(i: Int) = span(i) != null || rows.getOrNull(i)?.let { DRAFT_TOP_EDGE.containsMatchIn(it) || DRAFT_BOTTOM_EDGE.containsMatchIn(it) } == true
    /** The rows under the box: a short footer, and no other prompt row or box. */
    fun footer(i: Int): List<String>? {
        val under = rows.drop(i + 1)
        if (under.count { it.isNotBlank() } > 5) return null
        if (under.any { INPUT_LEAD.containsMatchIn(it) && !DRAFT_MENU_ROW.matches(it) }) return null
        return under
    }
    fun menuIn(under: List<String>) = under.any { DRAFT_MENU_ROW.matches(it) }
    val candidates = rows.indices.filter { INPUT_LEAD.containsMatchIn(rows[it]) && !DRAFT_NOT_INPUT.containsMatchIn(rows[it]) }
    if (candidates.isEmpty()) return null

    fun build(first: Int, rest: List<Int>, bounded: Boolean, menu: Boolean): ComposerDraft? {
        val row = rows[first]
        val frame = DRAFT_FRAMED.matches(row)
        val content = if (frame) row.trimEnd().dropLast(1) else row
        val leadLen = INPUT_LEAD.find(content)?.range?.last?.plus(1) ?: return null
        val raw = rawRows[first]
        val rawTyped = content.substring(leadLen).trim()
        // The box's own indent before continuation rows: the width of what comes before the text
        // on the first row ("❯ ", or "│ ❯ " less the frame edge).
        val barAt = if (frame) content.indexOfFirst { it == '│' || it == '┃' || it == '|' } else -1
        val indent = leadLen - (barAt + 1)
        // Suggestion or command rows (a popup under the text) are not the draft, nor counted for Backspace.
        val more = rest.filterNot { DRAFT_MENU_ROW.matches(rows[it]) }.map { i ->
            var t = rows[i].trimEnd()
            var at = 0
            if (frame) {
                val edge = DRAFT_FRAME_EDGE.find(t)
                if (edge != null) { at = edge.range.last + 1; t = t.substring(at) }
                if (t.isNotEmpty() && t.last() in "│┃|") t = t.dropLast(1)
            }
            val pad = minOf(t.takeWhile { it == ' ' || it == ' ' }.length, indent)
            withoutGhost(rawRows[i], t.drop(pad).trimEnd(), at + pad, agent)
        }.dropLastWhile { it.isBlank() }
        if (more.all { it.isBlank() }) {
            // A placeholder shows only in an empty box, so it is judged on a one-row draft only.
            if (rawTyped.isEmpty()) return null
            if (raw.contains('\u001B')) { if (isPlaceholderText(raw, rawTyped, agent)) return null }
            else if (isComposerHint(agent, rawTyped)) return null
        }
        val typed = withoutGhost(raw, rawTyped, leadLen, agent)
        val text = (listOf(typed) + more).joinToString("\n").trim('\n')
        if (text.isBlank()) return null
        return ComposerDraft(text, bounded, menu)
    }

    // Between box rules: the prompt row right under one, closed by the last rule of the same span
    // that has only a footer under it. A typed line of dashes as wide as the box looks just like
    // that edge, so with more than one such rule the end can't be told: shown, not acted on.
    for (c in candidates.asReversed()) {
        val open = openSpan(c - 1) ?: continue
        val closes = (c + 1 until minOf(rows.size, c + 1 + MAX_DRAFT_ROWS)).filter { i -> span(i)?.let { sameSpan(it, open) } == true }
        val close = closes.lastOrNull { footer(it) != null } ?: continue
        val under = footer(close) ?: continue
        val rest = (c + 1 until close).toList()
        return build(c, rest, bounded = closes.first() == close, menu = menuIn(under) || rest.any { DRAFT_MENU_ROW.matches(rows[it]) })
    }
    // Inside a frame: framed rows down to its bottom edge.
    for (c in candidates.asReversed()) {
        if (!DRAFT_FRAMED.matches(rows[c])) continue
        var end = c + 1
        while (end < rows.size && end - c <= MAX_DRAFT_ROWS && DRAFT_FRAMED.matches(rows[end]) && span(end) == null) end++
        val closed = end < rows.size && (DRAFT_BOTTOM_EDGE.containsMatchIn(rows[end]) || span(end) != null)
        val opened = c > 0 && (span(c - 1) != null || DRAFT_TOP_EDGE.containsMatchIn(rows[c - 1]))
        val under = footer(end) ?: continue
        // More of a frame, or another edge, under the one it stopped at: that was an inner rule
        // (a divider, or typed dashes), and the end can't be told.
        val more = rows.drop(end + 1).any { DRAFT_FRAMED.matches(it) || DRAFT_BOTTOM_EDGE.containsMatchIn(it) || DRAFT_BOX_RULE.matches(it) }
        val rest = (c + 1 until end).toList()
        return build(c, rest, bounded = closed && opened && !more, menu = menuIn(under) || rest.any { DRAFT_MENU_ROW.matches(rows[it]) })
    }
    // No box: the lowest prompt row, its glyph at column 0, with only a short footer under it.
    // A "  > quoted" row of the draft is not a prompt row.
    val c = candidates.lastOrNull { DRAFT_LEAD_AT_ZERO.containsMatchIn(rows[it]) } ?: return null
    // Text right above it may be the start of what it belongs to (a quote in the output).
    val clearAbove = c == 0 || rows[c - 1].isBlank() || isEdge(c - 1)
    val below = rows.drop(c + 1)
    if (below.count { it.isNotBlank() } > 3 + MAX_DRAFT_ROWS) return null
    if (agent?.lowercase() == "codex") {
        // Codex: the draft's rows are indented under it, then a blank row, then its footer block.
        val lastBlank = below.indexOfLast { it.isBlank() }
        val tail = below.drop(lastBlank + 1)
        if (lastBlank in 0..MAX_DRAFT_ROWS && tail.count { it.isNotBlank() } <= 3 &&
            below.take(lastBlank).all { it.isBlank() || it.startsWith("  ") }) {
            val rest = (c + 1 until c + 1 + lastBlank).toList()
            // A popup list under the text (slash commands, files) is not the draft.
            val menu = menuIn(tail) || rest.any { DRAFT_MENU_ROW.matches(rows[it]) }
            return build(c, rest, bounded = clearAbove, menu = menu)
        }
    }
    if (below.count { it.isNotBlank() } > 3) return null
    // Rows right under it may be more of the draft or the footer: shown, but not acted on.
    val next = below.firstOrNull()
    val single = next == null || next.isBlank() || isEdge(c + 1)
    return build(c, emptyList(), bounded = single && clearAbove, menu = menuIn(below))
}

/**
 * Where Claude Code's or Hermes's input box starts on [screen] (2.1.2, 2.1.1 acceptance finding 5):
 * the index in `screen.lines()` of the rule that opens the box, found the way [composerDraftOf]
 * finds a boxed draft (a prompt row right under an opening rule, closed by a rule of the same span
 * with only a short footer under it, Claude's helper list set aside), whether the box is empty,
 * shows a placeholder or holds a draft. Null for other agents or when no such box shows. The chat
 * built from the screen ends its prose there, so the box's text is never taken for a sent message.
 */
internal fun composerBoxStart(screen: String, agent: String?): Int? {
    if (agent?.lowercase() !in setOf("claude", "hermes")) return null
    val all = withoutClaudeAgentList(screen.lines().map { it.replace("\r", "") })
    val offset = maxOf(0, all.size - 48)
    val rows = all.takeLast(48).map { it.replace(ANSI_SEQ, "") }
    fun span(i: Int): IntRange? = rows.getOrNull(i)?.let(DRAFT_BOX_RULE::matchEntire)?.let {
        val at = it.groupValues[1].length
        at until at + it.groupValues[2].length
    }
    fun openSpan(i: Int): IntRange? = span(i) ?: rows.getOrNull(i)?.let(DRAFT_TITLED_RULE::matchEntire)?.let {
        val at = it.groupValues[1].length
        at until at + it.groupValues[2].length
    }
    fun sameSpan(a: IntRange, b: IntRange) = kotlin.math.abs(a.first - b.first) <= 2 && kotlin.math.abs(a.last - b.last) <= 3
    fun footer(i: Int): Boolean {
        val under = rows.drop(i + 1)
        return under.count { it.isNotBlank() } <= 5 && under.none { INPUT_LEAD.containsMatchIn(it) && !DRAFT_MENU_ROW.matches(it) }
    }
    val candidates = rows.indices.filter { INPUT_LEAD.containsMatchIn(rows[it]) && !DRAFT_NOT_INPUT.containsMatchIn(rows[it]) }
    for (c in candidates.asReversed()) {
        val open = openSpan(c - 1) ?: continue
        val closes = (c + 1 until minOf(rows.size, c + 1 + MAX_DRAFT_ROWS)).filter { i -> span(i)?.let { sameSpan(it, open) } == true }
        if (closes.none { footer(it) }) continue
        return offset + c - 1
    }
    return null
}

/** One character of a styled row and how it is drawn (1.4.6). */
private data class Styled(val ch: Char, val faint: Boolean, val italic: Boolean, val grey: Boolean, val background: List<Int>, val colored: Boolean = false, val reverse: Boolean = false)

/** A colour that reads as grey: the three channels within 28 of each other, of middle brightness. */
private fun greyRgb(r: Int, g: Int, b: Int): Boolean =
    maxOf(r, g, b) - minOf(r, g, b) <= 28 && (r + g + b) / 3 in 90..180

/** The 256-colour palette entry [n] as grey or not (8 is bright black; 16 to 231 the colour cube; 232 to 255 the greys). */
private fun grey256(n: Int): Boolean = when (n) {
    8 -> true
    in 16..231 -> { val lv = intArrayOf(0, 95, 135, 175, 215, 255); val c = n - 16; greyRgb(lv[c / 36], lv[c / 6 % 6], lv[c % 6]) }
    in 232..255 -> (8 + 10 * (n - 232)) in 90..180
    else -> false
}

/** The characters of a row with the SGR state each is drawn in; other escape sequences are dropped. */
private fun styledChars(row: String): List<Styled> {
    val out = ArrayList<Styled>(row.length)
    var faint = false; var italic = false; var grey = false
    // A foreground colour other than the terminal's default is set (2.1.1).
    var colored = false
    // Reverse video (SGR 7): how Kiro draws its cursor block (2.1.2).
    var reverse = false
    var background = emptyList<Int>()
    var i = 0
    while (i < row.length) {
        val m = if (row[i] == '\u001B') ANSI_SEQ.find(row, i)?.takeIf { it.range.first == i } else null
        if (m != null) {
            val seq = m.value
            if (seq.startsWith("\u001B[") && seq.endsWith("m")) {
                val ps = seq.substring(2, seq.length - 1).split(';').map { it.toIntOrNull() ?: 0 }
                var k = 0
                while (k < ps.size) {
                    when (val c = ps[k]) {
                        0 -> { faint = false; italic = false; grey = false; colored = false; reverse = false; background = emptyList() }
                        2 -> faint = true
                        22 -> faint = false
                        3 -> italic = true
                        23 -> italic = false
                        7 -> reverse = true
                        27 -> reverse = false
                        39 -> { grey = false; colored = false }
                        in 30..37 -> { grey = false; colored = true }
                        90 -> { grey = true; colored = true }
                        in 91..97 -> { grey = false; colored = true }
                        38 -> {
                            if (ps.getOrNull(k + 1) == 2 && k + 4 < ps.size) { grey = greyRgb(ps[k + 2], ps[k + 3], ps[k + 4]); colored = true; k += 4 }
                            else if (ps.getOrNull(k + 1) == 5 && k + 2 < ps.size) { grey = grey256(ps[k + 2]); colored = true; k += 2 }
                        }
                        in 40..47, in 100..107 -> background = listOf(c)
                        49 -> background = emptyList()
                        48 -> {
                            val n = if (ps.getOrNull(k + 1) == 2) 4 else if (ps.getOrNull(k + 1) == 5) 2 else 0
                            if (n > 0 && k + n < ps.size) { background = ps.subList(k, k + n + 1); k += n }
                        }
                        else -> {}
                    }
                    k++
                }
            }
            i = m.range.last + 1
            continue
        }
        out += Styled(row[i], faint, italic, grey, background, colored, reverse)
        i++
    }
    return out
}

/**
 * True when [typed], the text of the composer row [raw] (as read with its styles), is drawn the
 * way agents draw a placeholder (1.4.6, Bill: "ask codex to do anything" is "the greyed out text in
 * the input pane that just appears for you to type over"): every visible character of it faint
 * (Claude Code and Codex draw theirs with SGR 2) or in a grey colour (Kiro's 158,158,158), or, for
 * Hermes, italic (its gold italic examples). Text with any character in normal type is typed,
 * so typing followed by a dim suggestion (Claude's ghost text) still holds. A row read without
 * styles is never a placeholder by this test. 2.1.1: an agent's own known hint ([isStyledHint]) that
 * is the whole row, every character in a colour, faint or italic, is one too (Hermes in a skin
 * whose example prompts are not italic); the same words in the default colour are typing.
 */
fun isPlaceholderText(raw: String, typed: String, agent: String?): Boolean {
    if (!raw.contains('\u001B') || typed.isEmpty()) return false
    val chars = styledChars(raw)
    val plain = chars.joinToString("") { it.ch.toString() }
    // The styled reading must give the same text as the plain one, or nothing is assumed.
    if (plain != raw.replace(ANSI_SEQ, "")) return false
    val leadEnd = INPUT_LEAD.find(plain)?.range?.last?.plus(1) ?: return false
    val start = plain.indexOf(typed, leadEnd)
    if (start < 0) return false
    val hermes = agent?.lowercase() == "hermes"
    val seen = chars.subList(start, start + typed.length).filter { !it.ch.isWhitespace() }
    if (seen.isEmpty()) return false
    if (seen.all { it.faint || it.grey || (hermes && it.italic) }) return true
    if (cursorBeforeHint(chars, leadEnd, start, typed, agent)) return true
    return isStyledHint(agent, typed) && seen.all { it.colored || it.faint || it.italic }
}

/**
 * 2.1.2 (Bill, 2026-11-21: "Kiro just has the 'ask a question or describe a task' question in its
 * box"): Kiro can draw its hint in the default colour, after the cursor block (one reverse-video
 * space right after the marker). Typed text puts the cursor after the text, so the row is empty
 * only when the first cell after the marker is that cursor cell and the rest is exactly the
 * agent's known hint ([isComposerHint]). The same words with the cursor after them, or any other
 * text behind the cursor, are typing.
 */
private fun cursorBeforeHint(chars: List<Styled>, leadEnd: Int, start: Int, typed: String, agent: String?): Boolean {
    if (!isComposerHint(agent, typed)) return false
    // The marker's own run of spaces, which INPUT_LEAD took in: the cursor cell must be the first
    // reverse cell in it and the cell right before the hint; no other cell before it is reverse.
    val lead = chars.subList(0, leadEnd)
    val marker = lead.indexOfLast { it.ch in ">❯›»" }
    if (marker < 0 || start != leadEnd || start - 1 <= marker) return false
    val cursor = chars[start - 1]
    if (!cursor.reverse || cursor.ch != ' ') return false
    if (chars.subList(marker + 1, start - 1).any { it.reverse || !it.ch.isWhitespace() }) return false
    // Nothing after the hint is drawn as the cursor (that would be the cursor after typed words).
    return chars.subList(start, chars.size).none { it.reverse }
}

/** A choice that grants standing permission ("always allow", "don't ask again"); the card adds a caution (1.4.0). */
// 1.4.9 (the card audit): "in the future", "permanent allowlist", "allow rule ... future", "for the rest of",
// "approve all tools", "disable the sandbox", "for this repo" grant standing permission too.
private val ALWAYS_LABEL = Regex("""\balways\b|don'?t ask again|\btrust\b|remainder of this session|always-approve|for this (session|conversation)|don'?t ask me again|(never|don'?t) show again|in the future|permanent|allowlist|allow rule|for the rest of|approve all|disable the sandbox|future similar|during this session|(from|for|in) this (project|repo)|auto-accept""", RegexOption.IGNORE_CASE)
// 1.4.1 (from a code review): a refusal ("No, don't trust this", "Never allow") grants nothing.
private val REFUSAL_LABEL = Regex("""^\W*(no|deny|reject|cancel|never|quit|don'?t (trust|allow))\b|\b(don'?t|do not|not|never) (trust|always)\b|\bdeny rule\b|\bblock\b""", RegexOption.IGNORE_CASE)
// A curly apostrophe ("don’t ask again", Claude's MCP and skill rows) reads as a straight one (1.4.9).
fun grantsStandingPermission(label: String): Boolean = label.replace('\u2019', '\'').let { ALWAYS_LABEL.containsMatchIn(it) && !REFUSAL_LABEL.containsMatchIn(it) }

/** A choice that means "I'll type my own answer" ("Type something.", "Other", "Something else"), 1.4.0. */
private val OTHER_LABEL = Regex("""^(type something\.?|other\b.*|something else.*|chat about this|type your own.*|enter (a |your )?custom.*)$""", RegexOption.IGNORE_CASE)
// A checkbox before it ("[ ] Type something.", 1.4.9 from Grok's review) is not part of the words.
fun isTypedAnswerChoice(label: String): Boolean = OTHER_LABEL.matches(label.trim().replace(Regex("""^(\[[ xX✓✔]\]|[☐☑☒◻◼□■])\s+"""), ""))

/**
 * 2.1.4, Bill: "some question cards show type something and also chat about this as separate
 * options they seem redundant ... I'd like to merge them anytime that they would appear". Claude's
 * AskUserQuestion ends with "Type something." (a free-text answer) and "Chat about this" (leave
 * the question and talk instead); Oh My Pi's ask dialog ends with "Other (type your own)" and
 * "Chat about this". When both are offered, the card and the alert show one option,
 * [TYPED_ANSWER_LABEL], that answers through the typed row (move the highlight to that row, from
 * the live screen, then type and Enter). The "Chat about this" row is never sent from it; it stays
 * reachable through Terminal and the card's keys. [Prompt.choices] keep both rows, so keys, alert
 * identity and option checks are unchanged. With only one of the two, nothing changes.
 */
const val TYPED_ANSWER_LABEL = "Type your answer"
private val CHAT_INSTEAD_LABEL = Regex("""^chat about this\.?$""", RegexOption.IGNORE_CASE)
private val CHOICE_BOX = Regex("""^(\[[ xX✓✔]\]|[☐☑☒◻◼□■])\s+""")

/** The typed-answer row and the "Chat about this" row, when [choices] offer exactly one of each (2.1.4). */
fun typedChatPair(choices: List<Choice>): Pair<Choice, Choice>? {
    val chat = choices.filter { CHAT_INSTEAD_LABEL.matches(it.label.trim().replace(CHOICE_BOX, "")) }.singleOrNull() ?: return null
    val typed = choices.filter { it != chat && isTypedAnswerChoice(it.label) }.singleOrNull() ?: return null
    return typed to chat
}

/** The rows a card or alert shows (2.1.4): [rows] without "Chat about this" when it pairs with a typed row. */
fun shownChoices(rows: List<Choice>): List<Choice> = typedChatPair(rows)?.let { (_, chat) -> rows.filter { it != chat } } ?: rows

/** What [c] is called on a card or alert (2.1.4): the merged typed row is [TYPED_ANSWER_LABEL], a checkbox kept. */
fun shownChoiceLabel(rows: List<Choice>, c: Choice): String {
    if (typedChatPair(rows)?.first != c) return c.label
    val box = CHOICE_BOX.find(c.label.trim())?.value.orEmpty()
    return box + TYPED_ANSWER_LABEL
}

/** A checkbox row ("[ ] Tests", "[x] Docs", "☐ Lint", "◼ Build"), 1.4.0. */
private val CHECKBOX = Regex("""^(\[[ xX✓✔]\]|[☐☑☒◻◼□■])\s+""")
fun isCheckbox(label: String): Boolean = CHECKBOX.containsMatchIn(label.trim())

private const val CLAUDE_CHECKBOX_ACTION = "claude:checkbox-action"
private val CLAUDE_CHECKBOX_BUTTON = Regex("""^([❯›])?\s+(Next|Submit)\s*$""")

/** An option whose own label says Esc answers it: "No, and tell Codex what to do differently (esc)". */
private val ESC_LABEL = Regex("""\((?:esc)\b[^)]*\)""", RegexOption.IGNORE_CASE)
private val REJECT_LABEL = Regex("""^(deny|reject|no)\b""", RegexOption.IGNORE_CASE)
/** A sentence that asks: starts with a word, three words or more, ends in "?", and no code signs in it (1.4.2). */
private val ASKING_SENTENCE = Regex("""^[\p{L}"'(][^{}=;<>`]*\s\S+\s[^{}=;<>`]*\?$""")
/** A line of code that ends in "?": a declaration keyword, or a nullable type ("name: String?"). */
private val CODE_LINE = Regex("""^(val|var|let|const|def|fun|function|func|fn|return|import|public|private|class|type)\b|:\s*[A-Z]\w*(<[^>]*>)?\?$""")
/** The line under Muse Code's approval options (1.4.7). */
private val MUSE_CARD_FOOTER = Regex("""^\s*Change approval defaults in /permissions\s*$""", RegexOption.IGNORE_CASE)
/** What Muse prints under a command it asks about: "Stage 1/2", "Current argv: [...]" (1.4.8). */
private val CMD_TRAILER = Regex("""^(Stage\s+\d+\s*/\s*\d+|Current argv:)""")
/** A working agent's own status line (Grok's "… 14m29s ⇣84.1k [↓][stop]"); not a card footer's "esc to interrupt" (1.4.9). */
private val WORKING_LINE = Regex("""\[stop]|⇣\s*[\d.]+k?""")
/** An agent's input row ("│ ❯ ", "› ", "> "), not a numbered option row (1.4.9). */
private val INPUT_ROW_UNDER = Regex("""^[\s\u00A0│┃|]*[>❯›»](?![\s\u00A0]*\d{1,2}[.)\s])""")
/**
 * Buttons on one row, with a "⇆ select" or "←→ select" hint on that row or the next (1.4.9): OpenCode's
 * permission prompt ("Allow once   Always allow   Reject") and Vibe's folder trust ("Yes   No"). The
 * first button has the focus when the prompt opens (the focus is drawn only in colour), and left and
 * right move it; Esc answers a refusal.
 */
fun detectButtonRow(text: String): Prompt? {
    val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.takeLast(12)
    val hint = Regex("""(⇆|←→)\s*select""")
    val at = lines.indexOfLast { hint.containsMatchIn(it) }
    if (at < 0 || at < lines.size - 4) return null
    fun buttons(l: String): List<String> = l.trim().trim { it in "│┃|" }.substringBefore("⇆").substringBefore("←→")
        .trim().split(Regex("""\s{3,}""")).map { it.trim() }.filter { it.isNotEmpty() }
    val here = buttons(lines[at])
    val (row, labels) = if (here.size >= 2) at to here else (at - 1) to (if (at >= 1) buttons(lines[at - 1]) else emptyList())
    if (labels.size !in 2..5 || labels.any { it.length > 40 || !it.first().isLetter() }) return null
    // Not when the agent went on under it (from Grok's review): an old prompt in the scrollback.
    if (agentWentOn(lines.drop(at + 1))) return null
    val clean = { l: String -> l.trim().trim { it in "│┃|△ " }.trim() }
    val above = (row - 1 downTo maxOf(0, row - 10)).map { clean(lines[it]) }.filter { it.any(Char::isLetterOrDigit) }.reversed()
    val question = above.lastOrNull { it.endsWith("?") } ?: above.firstOrNull { it.contains("required", true) || it.endsWith("?") } ?: above.firstOrNull() ?: "Choose"
    val parts = questionParts(above.map { QLine(it) })
    return Prompt(question, labels.mapIndexed { i, l -> Choice(l, (i + 1).toString(), false) }, "1", escRejects = true,
        parts = parts.takeIf { above.isNotEmpty() }, horizontal = true)
}

/**
 * Options answered by a letter (1.4.9): Grok's folder trust ("y  Yes, proceed" / "n  No, quit") and its plan
 * review footer ("a:approve  │  s:request changes  │  c:comment  │  …  │  q:quit plan").
 */
fun detectLetterMenu(text: String, blocked: Boolean): Prompt? {
    val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.takeLast(14)
    if (lines.isEmpty()) return null
    val foot = lines.last()
    if (Regex("""\ba:approve\b""").containsMatchIn(foot)) {
        val pairs = Regex("""\b([a-z]):([a-z][a-z ]*[a-z])""").findAll(foot).map { it.groupValues[1] to it.groupValues[2] }
            .filter { (k, _) -> k in setOf("a", "s", "c", "q") }.toList()
        if (pairs.size < 2) return null
        // The plan review's key line is the last line of the screen; nothing of the agent's own comes after it.
        val heading = lines.firstOrNull { it.trimStart().startsWith("#") }?.trim()?.trimStart('#')?.trim()
        return Prompt(heading?.let { "Approve the plan: $it?" } ?: "Approve the plan?",
            pairs.map { (k, l) -> Choice(l.replaceFirstChar { it.uppercase() }, k, false) })
    }
    val row = Regex("""^\s*([a-z])\s{2,}(\S.{0,80})$""")
    val rows = lines.withIndex().filter { row.matches(it.value) }
    if (rows.size !in 2..6) return null
    // One run, near the bottom, with the letters named in the hint under it ("Enter or y to trust · n or Esc to quit").
    if (rows.zipWithNext().any { (a, b) -> b.index != a.index + 1 } || rows.last().index < lines.size - 3) return null
    val letters = rows.map { row.find(it.value)!!.groupValues[1] }
    if (agentWentOn(lines.drop(rows.last().index + 1))) return null
    val hint = lines.drop(rows.last().index + 1).joinToString(" ")
    if (!blocked && letters.none { Regex("""\b$it\b""").containsMatchIn(hint) }) return null
    val question = lines.take(rows.first().index).lastOrNull { it.trim().endsWith("?") }?.trim() ?: "Choose"
    return Prompt(question, rows.map { r -> row.find(r.value)!!.let { m -> Choice(m.groupValues[2].trim(), m.groupValues[1], false) } })
}

/**
 * Whether the agent went on after a card (1.4.9): its activity line, its working line or its input row
 * among [below], the lines under the card's options and hint.
 */
internal fun agentWentOn(below: List<String>): Boolean = below.any { l ->
    val c = l.trim().trim { it in "│┃|╭╰╮╯─ " }.trim()
    (c.any(Char::isLetterOrDigit) && (ACTIVITY.containsMatchIn(c) || WORKING_LINE.containsMatchIn(c))) ||
        (INPUT_ROW_UNDER.containsMatchIn(l) && !TYPED_ANSWER_HINT.containsMatchIn(c))
}

/** A key hint line ("ctrl+g to edit", "esc to cancel", "enter to submit"), 1.4.9. */
private val KEY_HINT_LINE = Regex("""^(ctrl|shift|alt|cmd|esc|enter|tab|space|↑|↓|←|→)\b|\b(to|·)\s*(cancel|select|navigate|submit|confirm|edit)\b""", RegexOption.IGNORE_CASE)
/** A whole keyboard hint, not a sentence that happens to mention an action. */
private const val CARD_HINT = """(?:(?:(?:ctrl|shift|alt|cmd)[+-][a-z0-9]+|esc|enter|tab(?:/arrow keys)?|space|arrow keys|[↑↓←→/]+)\s+(?:to\s+)?(?:cancel|reject|select|navigate|submit|confirm|toggle|edit(?: in editor)?)|ctrl\+g to edit in [a-z0-9_][a-z0-9_.+-]*)"""
private val STANDALONE_CARD_HINT = Regex(
    """^$CARD_HINT(?:\s*[·|]\s*$CARD_HINT)*$""",
    RegexOption.IGNORE_CASE,
)
private val TYPED_ANSWER_HINT = Regex("""type your|other""", RegexOption.IGNORE_CASE)
/** At most this many lines with text under a live card's last option (1.4.9). */
private const val MAX_UNDER_CARD = 8
/** Codex's approval footer (1.4.2). */
private val CODEX_FOOTER = Regex("""Press enter to confirm or esc to cancel""", RegexOption.IGNORE_CASE)
/** An option's own one-letter key at the end of its label, "(y)" (1.4.2). */
private val SHORTCUT_LABEL = Regex("""\(([a-z])\)\s*$""")

/** The keys that pick [choice]: arrows from the highlighted option, then Enter; null to type it. */
fun menuKeys(prompt: Prompt, choice: Choice): List<String>? {
    if (prompt.terminalReview) return null
    if (prompt.claudeCheckboxAction != null) {
        val rows = prompt.answerRows()
        val from = rows.indexOfFirst { it.keys == prompt.selected }
        val to = rows.indexOf(choice)
        if (from < 0 || to < 0) return null
        val key = if (to > from) "down" else "up"
        return List(kotlin.math.abs(to - from)) { key } + if (isCheckbox(choice.label)) "space" else "enter"
    }
    if (prompt.numberKeys && choice in prompt.choices) return listOf(choice.keys)
    // "No, and tell Copilot what to do differently (Esc to stop)": Enter on that row does nothing in
    // Copilot, and in Codex and Claude it opens a feedback box; Esc answers it in all three. Vibe's
    // card says "Esc reject", and Esc rejects wherever its (invisible) highlight is.
    if (ESC_LABEL.containsMatchIn(choice.label) || (prompt.escRejects && REJECT_LABEL.containsMatchIn(choice.label))) return listOf("esc")
    // Codex names each option's own key at the end of its label: "Yes, proceed (y)", "Yes, and don't
    // ask again for these files (a)". That key answers it directly (1.4.2).
    if (prompt.shortcuts && prompt.choices.any { it.keys == choice.keys }) SHORTCUT_LABEL.find(choice.label)?.let { return listOf(it.groupValues[1]) }
    val from = prompt.selected?.toIntOrNull() ?: return null
    val to = choice.keys.toIntOrNull() ?: return null
    if (prompt.choices.none { it.keys == choice.keys }) return null
    val step = if (prompt.horizontal) (if (to > from) "right" else "left") else if (to > from) "down" else "up"
    // A checkbox row is toggled with Space, not picked with Enter (1.4.0); "Submit" is Enter.
    val last = if (prompt.multi && isCheckbox(choice.label)) "space" else "enter"
    return List(kotlin.math.abs(to - from)) { step } + last
}

/** The arrows that move the highlight to [choice], without the Enter (a typed answer goes there next), 1.4.0. */
fun moveKeys(prompt: Prompt, choice: Choice): List<String>? = menuKeys(prompt, choice)?.dropLast(1)

/**
 * Looks at the bottom of a pane for a question the agent is waiting on:
 * a numbered menu ("1. Yes"), or a "(y/n)" prompt. herdr's own "blocked" status
 * means the agent is waiting even when no choices can be read from the screen.
 */
/** The footer of Claude's question dialog ("Enter to select · ↑/↓ to navigate · Esc to cancel"). */
private val QUESTION_FOOTER = Regex("""Enter to select.*(navigate|Esc to cancel)|Tab/Arrow keys to navigate|↑/↓ to navigate|\b\d+/\d+:select\b|↑/?↓(?:/jk)?\s+(?:to\s+)?navigate|press enter to confirm or esc|^\s*Change approval defaults in /permissions\s*$|⇆ select|←→ select|↑↓ move|↑↓ navigate\s+enter select|^\s*enter to submit\s*$|enter confirm\s+esc cancel""", RegexOption.IGNORE_CASE)

/** A line of the agent's own activity log above a card: "● Ran …", "• Running …", "◆ …", spinners. */
private val ACTIVITY = Regex("""^[●○•◆◈⏵✓✗└▸☐☒⠀-⣿]""")  // ☐ ☒: Claude's question-dialog header chips
/** A running status line: Grok's "… 7.5s 26s ⇣87.0k [↓][stop]", or "esc to interrupt". */
private val STATUS_LINE = Regex("""\[stop]|⇣\s*[\d.]+k?|\besc to interrupt\b""", RegexOption.IGNORE_CASE)

/** Grok's data-sharing banner ("Help improve Grok ... [Opt out] [Opt in]"): never part of a card's question. */
private val GROK_BANNER = Regex("""Help improve Grok|\[Opt out]\s*\[Opt in]|Opt-in to allow SpaceXAI|Read Terms and Privacy Policy|Change anytime via settings""")

/** An echoed prompt above a card ("› card", "> run it", "❯ go"); only after a blank line, since
 *  Grok's card title itself starts with "›" right above its command. */
private val PROMPT_ECHO = Regex("""^[›>❯]\s""")

/** Vibe's approval card: numbered rows, the highlight shown only by colour, "Esc reject" in its footer. */
private val VIBE_CARD_FOOTER = Regex("""↑↓/jk navigate.*(Esc reject|Enter select)""", RegexOption.IGNORE_CASE)

fun showsQuestionDialog(text: String): Boolean =
    text.lines().filter { it.isNotBlank() }.takeLast(6).any { QUESTION_FOOTER.containsMatchIn(it) }

/** One hint of a dialog's footer: a key, then what it does ("Esc to cancel", "Tab:next option", "1/3:select"). */
private val HINT_SEGMENT = Regex("""^(?:esc|enter|tab|space|return|↵|(?:shift|ctrl|alt|cmd)[+-]\S+|[↑↓←→/]+(?:/?jk)?|\d+/\d+)(?:\s+|:)\S""", RegexOption.IGNORE_CASE)

/**
 * 2.2.2: a question dialog's own footer or hint row, read from its structure rather than one
 * harness's words: Claude's and Kiro's hint rows, Codex's "Press enter to confirm or esc to cancel",
 * Grok's "1/3:select │ Tab:next option", Vibe's and Muse's footers, a button row's "⇆ select", any
 * row of two or more key hints, or the bottom edge of the dialog's box.
 */
internal fun isDialogFooterRow(row: String): Boolean {
    val t = row.trim()
    if (t.isEmpty()) return false
    val c = t.trim('│', '┃', '|', '║', ' ').trim()
    if (QUESTION_FOOTER.containsMatchIn(t) || CODEX_FOOTER.containsMatchIn(t) || VIBE_CARD_FOOTER.containsMatchIn(t) ||
        MUSE_CARD_FOOTER.matches(c) || STANDALONE_CARD_HINT.matches(c) || Regex("""(⇆|←→)\s*select""").containsMatchIn(t)) return true
    if (t.first() in "╰└┗╚" && t.last() in "╯┘┛╝") return true
    // Claude's tab bar ("←  ☐ Backup  ✔ Submit  →") is the dialog's top, not its footer.
    if (parseTabBar(c) != null) return false
    return c.split(Regex("""\s*[·│┃|]\s*|\s{2,}""")).count { HINT_SEGMENT.containsMatchIn(it.trim()) } >= 2
}

/** 2.2.2: a header row that is one agent's exact dialog heading (Codex's "Would you like to run the following command?"), not a sentence that starts like one. */
internal fun isNamedDialogHeading(row: String): Boolean {
    val c = row.trim().trim('│', '┃', '|', '║', ' ').trim()
    return CODEX_COMMAND_HEADING.matches(c) || CODEX_OTHER_APPROVAL.matches(c) || COMMAND_INTRO.matches(c) ||
        CLAUDE_COMMAND_TITLE.matches(c) || TOOL_PERMISSION.matches(c) || HERMES_COMMAND_HEADING.matches(c) || CODEX_QUESTION_PROGRESS.containsMatchIn(c)
}

/** 2.2.2: the agent's own running status row ("… 0.1s 16m4s ⇣90.2k [↓][stop]", "esc to interrupt"), drawn above its prompt. */
internal fun isAgentStatusRow(row: String): Boolean = STATUS_LINE.containsMatchIn(row.trim())

/**
 * 2.2.2: the header row a dialog opens with when it draws no edge above its question: Claude's and
 * Codex's "Do you want to …" and "Would you like to …", a command title, Vibe's "Permission for the
 * … tool", Hermes's "Dangerous Command", Codex's other approvals.
 */
internal fun isDialogHeaderRow(row: String): Boolean {
    val c = row.trim().trim('│', '┃', '|', '║', ' ').trim()
    return c.startsWith("Do you want to ") || c.startsWith("Would you like to ") || COMMAND_INTRO.matches(c) ||
        CODEX_QUESTION_PROGRESS.matches(c) || c.startsWith("Question ") && CODEX_QUESTION_PROGRESS.containsMatchIn(c) ||
        CLAUDE_COMMAND_TITLE.matches(c) || TOOL_PERMISSION.matches(c) || HERMES_COMMAND_HEADING.matches(c) ||
        CODEX_OTHER_APPROVAL.matches(c) || c.endsWith(" requires approval", ignoreCase = true)
}

/**
 * Rows read from each Claude pane for its status (2.1.1). A short conversation in Claude Code's
 * fullscreen view leaves its question dialog high on the screen with blank rows under it; a
 * 12-row read saw only those blank rows, and the pane stayed "working". 80 covers a tall pane.
 */
internal const val CLAUDE_STATUS_READ_LINES = 80

/**
 * Why a Claude pane that herdr calls working or idle needs you, from the bottom of its screen
 * ([CLAUDE_STATUS_READ_LINES] rows), or null: its question dialog is open, or its sessions list
 * shows a session awaiting input (2.1.1).
 */
/**
 * The herdr states [Herdr.waitForChange] waits for on [p]: every state but the one herdr reports.
 * herdr's own status (2.1.2): waiting until "done" on a pane herdr already calls done, but the app
 * shows as needing you, would end at once and poll without a pause.
 */
internal fun waitUntilStates(p: Pane, herdrStatus: Status? = null): List<String> {
    val names = mapOf(Status.IDLE to "idle", Status.WORKING to "working", Status.NEEDS_YOU to "blocked", Status.DONE to "done")
    // 2.1.4: herdr's own last status when it is known ([Herdr.herdrStates]). A Claude pane promoted to
    // needs you, or a pane shown working for its background shells, would otherwise wait for the very
    // state herdr already reports, and the wait would end at once, round after round.
    return names.filterKeys { it != (herdrStatus ?: p.screenNeedsYouFrom ?: p.status) }.values.toList()
}

/** Splits a [Herdr.waitForWake] output into the pane that woke it, or null (2.1.4). */
internal fun parseWake(out: String): Herdr.Wake? {
    val line = out.lines().firstOrNull { it.startsWith("@@chg ") || it.startsWith("@@scr ") } ?: return null
    return Herdr.Wake(line.substring(6).trim(), line.startsWith("@@scr "))
}

/**
 * The footer a Claude or OpenCode dialog shows, for herdr's `pane wait-output` (Rust regex syntax),
 * or null for other agents (2.1.4). Loose on purpose: a match only reads the list again, where
 * [claudeNeedsYouReason] and [openCodeNeedsYouReason] decide, and a match that shows nothing is
 * left alone for a while ([StatusPush]).
 */
internal fun screenWakeRegex(agent: String?): String? = when {
    agent == "claude" -> """(?im)Enter to select.*(?:navigate|Esc to cancel)|↑/↓ to navigate|Tab/Arrow keys to navigate|\d{1,3} awaiting input\s*$"""
    isOpenCode(agent) -> """(?m)(?:⇆|←→)\s+select\s+enter\s+confirm|enter\s+(?:submit|confirm|toggle)\s+esc\s+dismiss|Permission required"""
    else -> null
}

/**
 * The panes whose screens a [Herdr.waitForWake] watches (2.1.4): the ones [screenAskable] reads,
 * by herdr's own status in [herdrStates], that the app doesn't already show as needing you.
 */
internal fun screenWatchPanes(list: List<Pane>, herdrStates: Map<String, Status>): List<Pane> =
    screenAskable(list.map { p -> herdrStates[p.id]?.let { p.copy(status = it) } ?: p })
        .filter { s -> list.firstOrNull { it.id == s.id }?.status != Status.NEEDS_YOU }

/**
 * The panes whose screen [Herdr.panes] reads for a dialog herdr didn't report: Claude panes not
 * already waiting, and OpenCode panes herdr calls done or idle (2.1.2).
 */
internal fun screenAskable(list: List<Pane>): List<Pane> = list.filter {
    (it.agent == "claude" && it.status != Status.NEEDS_YOU) ||
        (isOpenCode(it.agent) && it.status in setOf(Status.DONE, Status.IDLE))
}

/**
 * [list] with each [screenAskable] pane whose screen tail in [tails] shows it needs you marked
 * NEEDS_YOU. A Claude pane is promoted as before; an OpenCode pane only for display and alerts,
 * keeping herdr's status in [Pane.screenNeedsYouFrom] for the send route (2.1.2).
 */
internal fun markScreenNeedsYou(list: List<Pane>, tails: Map<String, String>): List<Pane> {
    val askable = screenAskable(list).map { it.id }.toSet()
    return list.map { p ->
        // 2.2.0: herdr's "blocked" on OpenCode with its normal input box on screen and no dialog
        // (after an error, or after /new) is not a question: shown idle, sends unchanged.
        if (isOpenCode(p.agent) && p.status == Status.NEEDS_YOU) {
            val t = tails[p.id]
            if (t != null && openCodeStaleBlocked(t)) {
                com.billmongan.herdrdroid.diag.DiagLog.log("prompt", "${p.id}: herdr says blocked, but OpenCode shows its input box and no dialog; showing it as idle, sends unchanged")
                return@map p.copy(openCodeStaleBlock = true)
            }
            return@map p
        }
        val tail = tails[p.id]?.takeIf { p.id in askable }
        val why = when {
            tail == null -> null
            p.agent == "claude" -> claudeNeedsYouReason(tail)
            isOpenCode(p.agent) -> openCodeNeedsYouReason(tail)
            else -> null
        }
        when {
            why == null -> p
            isOpenCode(p.agent) -> {
                com.billmongan.herdrdroid.diag.DiagLog.log("prompt", "${p.id}: $why while herdr says ${p.status.name.lowercase()}; showing it as needs you, sends unchanged")
                p.copy(status = Status.NEEDS_YOU, screenNeedsYouFrom = p.status)
            }
            else -> {
                com.billmongan.herdrdroid.diag.DiagLog.log("prompt", "${p.id}: $why while herdr says ${p.status.name.lowercase()}; treating it as needs you")
                p.copy(status = Status.NEEDS_YOU)
            }
        }
    }
}

/**
 * Why an OpenCode pane herdr calls done or idle needs you, from the bottom of its screen, or null
 * (2.1.2): its permission dialog is open, as the chat card would read it (the same checks as
 * [detectPrompt]: no background drawer, no composer under it, no question dialog first).
 */
internal fun openCodeNeedsYouReason(tail: String): String? {
    val plain = stripAnsi(tail)
    if (openCodeBackgroundDrawer("opencode", plain) || openCodeComposerMode(plain.lines().takeLast(20)) >= 0) return null
    // 2.1.2: OpenCode's own question dialog (the "question" tool) needs you as much as a permission.
    if (detectOpenCodeQuestion(plain) != null) return "a question dialog is open"
    return detectOpenCodePermission(tail, plain)?.let { "a permission dialog is open" }
}

/**
 * Whether an OpenCode screen herdr calls blocked shows nothing that waits for you (2.2.0): OpenCode's
 * normal input box with its model row and commands footer at the bottom, no background drawer, and
 * no question, permission or web-search dialog as the chat card would read it.
 */
internal fun openCodeStaleBlocked(tail: String): Boolean {
    val plain = stripAnsi(tail)
    if (openCodeBackgroundDrawer("opencode", plain) || openCodeComposerMode(plain.lines().takeLast(20)) < 0) return false
    return detectOpenCodeQuestion(plain) == null && detectOpenCodePermission(tail, plain) == null &&
        detectPrompt(tail, blocked = false, agent = "opencode") == null
}

internal fun claudeNeedsYouReason(tail: String): String? = when {
    showsQuestionDialog(withoutClaudeChromeUnderDialog(stripAnsi(tail))) -> "a question dialog is open"
    claudePermissionFooterAtBottom(tail) -> "a permission dialog is open"
    claudeSessionsAwaiting(tail) > 0 -> "Claude's sessions list shows a session awaiting input"
    else -> null
}

private val PI_SELECT_FOOTER = Regex("""↑↓\s+navigate\s+enter select\s+escape/ctrl\+c cancel""", RegexOption.IGNORE_CASE)

/** Pi's native select footer must still be at the bottom; a footer in old output is not a card. */
internal fun activePiQuestion(text: String, version: String?): Prompt? {
    val footer = text.lines().lastOrNull { !isChrome(it) }?.trim() ?: return null
    if (!PI_SELECT_FOOTER.matches(footer)) return null
    return detectPrompt(text, blocked = false, agent = "pi", version = version)?.takeIf { it.choices.isNotEmpty() }
}

/** OpenCode's input box bottom row, "┃  Build · Hosted Coding Model  Hosted AI" (2.2.0, from 2.3.2's screen). */
private val OPENCODE_AGENT_ROW = Regex("""^\s*[┃│]\s+(Build|Plan)\s+·\s+\S""")

/**
 * The permission mode an agent shows in its footer: "plan", "accept edits", "auto" or "bypass".
 * Claude in its default mode shows none, which reads as "default"; other agents return null.
 */
fun agentMode(agent: String?, screen: String): String? {
    val tail = screen.lines().filter { it.isNotBlank() }.takeLast(8).joinToString("\n")
    // 2.2.0: OpenCode's agent ("Build" or "Plan"), on its input box's bottom row: "┃  Build · <model>".
    if (isOpenCode(agent)) return stripAnsi(screen).lines().takeLast(12)
        .asReversed().firstNotNullOfOrNull { OPENCODE_AGENT_ROW.find(it)?.groupValues?.get(1)?.lowercase() }
    when (agent?.lowercase()) {
        // Vibe's footer names its mode: "Mistral Medium 3.5[high] · auto approve ─" (Shift+Tab
        // cycles ask, accept edits, smart approve, auto approve). Auto approve runs every tool.
        "vibe" -> return Regex("""·\s+(ask|accept edits|smart approve|auto approve)\b""", RegexOption.IGNORE_CASE)
            .findAll(tail).lastOrNull()?.groupValues?.get(1)?.lowercase()?.let {
                when (it) { "ask" -> "default"; "accept edits" -> "accept edits"; "smart approve" -> "auto"; else -> "bypass" }
            }
        // Kiro: "Plan · auto · ◔ 2%" in plan mode (Shift+Tab toggles it), "Default · …" otherwise;
        // "Trust All Tools active" means every tool runs without asking.
        "kiro" -> {
            val foot = tail.lines().lastOrNull { Regex("""^\s*\w+\s+·\s+.*◔""").containsMatchIn(it) } ?: return null
            return when {
                foot.trimStart().startsWith("Plan") -> "plan"
                screen.lines().takeLast(12).any { it.contains("Trust All Tools active") } -> "bypass"
                else -> "default"
            }
        }
    }
    if (agent?.lowercase() != "claude" && footerBypass(screen)) return "bypass"
    val m = Regex("""(plan mode|accept edits|auto mode|auto-accept edits|bypass permissions) on""", RegexOption.IGNORE_CASE).findAll(tail).lastOrNull()
    val mode = m?.groupValues?.get(1)?.lowercase()?.let {
        when {
            it.startsWith("plan") -> "plan"
            it.startsWith("bypass") -> "bypass"
            it.contains("edits") -> "accept edits"
            else -> "auto"
        }
    }
    return mode ?: if (agent?.lowercase() == "claude" && tail.isNotBlank()) "default" else null
}

/**
 * Whether an agent's footer says every tool runs without asking: Grok's always-approve, Hermes's
 * --yolo, Codex's full access, Copilot's allow-all, Antigravity's skipped permissions. Display only:
 * the app never turns such a mode on (Bill, 2026-11-13). Only the bottom three lines are read, key
 * hints such as "Ctrl+o:always-approve" are removed first, and an approval card's own options
 * ("1 (○) Yes, and don't ask again ... (always-approve mode)") are skipped, so neither counts.
 */
private val BYPASS_WORDS = Regex(
    """\b(always[- ]approve|yolo|full access|allow[- ]all|bypass(ing)? permissions|dangerously|skip(ping)? permissions|auto[- ]approve|approve all)\b""",
    RegexOption.IGNORE_CASE,
)
private val KEY_HINT = Regex("""\b(ctrl|shift|alt|meta|cmd)\+[\w.]+\s*:\s*[\w-]+(\s+mode)?""", RegexOption.IGNORE_CASE)
/** The gutters down the rows of a command Grok wrapped in its approval box (2.1.3). */
private const val GROK_GUTTER = GrokMeta.GUTTER

private val CARD_ROW = Regex("""^[\s│┃|]*([❯›>▶➤⟩]\s*)?\d(?:[.)]|\s+\([○●◯◉]\))\s""")

fun footerBypass(screen: String): Boolean =
    screen.lines().filter { it.isNotBlank() }.takeLast(3)
        .filterNot { CARD_ROW.containsMatchIn(it) }
        .any { BYPASS_WORDS.containsMatchIn(KEY_HINT.replace(it, " ")) }

/**
 * A menu without numbers, as Kiro's and Vibe's model pickers draw it: the current row marked with
 * ❯ or ›, the other rows indented to the same column ("❯ auto  1.00x credits" over
 * "  claude-sonnet-4.5  1.30x credits"; "│  › Mistral Medium 3.5" under "│    Default"). Each row
 * gets its position as its number, so [menuKeys] reaches it with the arrows and Enter.
 */
fun detectUnnumberedMenu(text: String): Prompt? {
    val raw = text.lines().map { it.trimEnd() }.takeLast(60)
    val border = "│┃|"
    // "⟩" marks the current row in Muse Code's lists (its /help command list, 1.4.5).
    val marker = Regex("""^([\s$border]*)([❯›▶➤⟩→])\s+(\S.*)$""")
    val at = raw.indexOfLast { marker.matches(it) }
    if (at < 0) return null
    val m = marker.find(raw[at])!!
    val col = raw[at].length - m.groupValues[3].length
    fun row(l: String): String? {
        // The marked row's text starts after its marker; the others are indented to the same column.
        if (l == raw[at]) return m.groupValues[3].trimEnd { it.isWhitespace() || it in border }
        if (l.length <= col) return null
        val head = l.substring(0, col)
        if (head.any { !it.isWhitespace() && it !in border }) return null
        if (l[col].isWhitespace() || l[col] in "─━═") return null
        if (marker.matches(l) && l != raw[at]) return null
        return l.substring(col).trimEnd { it.isWhitespace() || it in border }
    }
    // A description under a row ("  ❯ ◉ SQLite" over "      Single file, no server"), indented past the
    // rows' column, is part of that row, not the end of the menu (1.4.9: omp's ask dialog, Kiro's scope page).
    fun described(l: String): Boolean = l.length > col && l.substring(0, col).all { it.isWhitespace() || it in border } &&
        l[col].isWhitespace() && l.substring(col).trim().trim { it in border }.isNotEmpty()
    var top = at
    while (top - 1 >= 0) {
        var k = top - 1
        while (k >= 0 && described(raw[k])) k--
        if (k >= 0 && row(raw[k]) != null) top = k else break
    }
    var bottom = at
    while (bottom + 1 < raw.size) {
        var k = bottom + 1
        while (k < raw.size && described(raw[k])) k++
        if (k < raw.size && row(raw[k]) != null) bottom = k else break
    }
    var rows = (top..bottom).filter { !described(raw[it]) || it == at }.map { it to row(raw[it])!! }
    // A table's header ("Model   Credit usage   Description") is not a choice.
    if (rows.size > 1 && rows.first().first != at && Regex("""^(Model|Name)(\s{2,}|$)""").containsMatchIn(rows.first().second)) rows = rows.drop(1)
    if (rows.size !in 2..30) return null
    // omp draws a radio before each label ("◉ SQLite", "○ Postgres"): the label is the words (1.4.9).
    val labels = rows.map { (_, r) -> r.split(Regex("""\s{2,}""")).filter { it.isNotBlank() }.joinToString(" · ").take(90).replace(Regex("""^[◉○●◯]\s+"""), "") }
    var question = (top - 1 downTo maxOf(0, top - 4)).map { raw[it].trim().trim { c -> c in border || c.isWhitespace() } }
        .firstOrNull { it.any(Char::isLetter) && !it.all { c -> c in "─━═ " } }
        ?.substringBefore("  ")?.trimEnd(':')?.ifBlank { null } ?: "Choose"
    // Kiro: "shell requires approval" under a rule, with the tool and command just above the rule
    // ("↓ Shell mkdir -p sub && date > sub/now.txt"). The question carries the command too.
    var kiroCmd: String? = null
    if (question.endsWith("requires approval")) {
        (top - 1 downTo maxOf(0, top - 8)).map { raw[it].trim() }
            .firstNotNullOfOrNull { Regex("""^[↓•●▸]?\s*(Shell|Write|Edit|Read|Fetch|Web\w*|\w+Tool)\s+(.+)$""").find(it) }
            ?.let { m -> kiroCmd = m.groupValues[2].trim(); question = "$question: $kiroCmd" }
    }
    val selected = rows.indexOfFirst { it.first == at } + 1
    val parts = kiroCmd?.let { cmd ->
        val head = question.substringBefore(": ").replaceFirstChar { it.uppercase() }
        QuestionParts(emptyList(), listOf(QBlock(QKind.HEADING, head), QBlock(QKind.COMMAND, cmd)))
    }
    return Prompt(question, labels.mapIndexed { i, l -> Choice(l, (i + 1).toString(), false) }, selected.toString(), parts = parts)
}

/**
 * [agent] and [version] (1.4.0) let a version-specific rule from [ParserRules] apply; with neither,
 * the defaults are used, as before.
 */
/**
 * An agent's error on screen (1.4.2, Bill's opencode and omp panes: herdr said "blocked", the screen
 * showed a failed compaction or a rate limit, and the app showed an empty question card): a short
 * summary such as "compaction failed; model rate-limited", or null.
 *
 * Only the bottom 12 lines count, and only a line that reads as an error: one that starts with an
 * error mark (✗ ⚠ × ■ ⎿ !) or word (Error, Fatal, Failed), says "error:" or "(HTTP 5xx)", or opens
 * with one of the agents' own unmistakable messages ("You are not logged in", "Your credit balance
 * is too low"); the line after such a line counts too, for an error wrapped over two lines. Your
 * own lines (">", "❯", "›") never count, so a conversation about rate limits shows no banner. A
 * crash (a panic, a traceback, a fatal error) counts wherever it is in those lines.
 */
fun detectAgentError(text: String): String? = agentErrorLines(text)?.first

/** [detectAgentError]'s summary with the lines that named the error, or null. */
private fun agentErrorLines(text: String): Pair<String, List<String>>? {
    val all = text.lines().filter { it.isNotBlank() }.takeLast(12)
    val found = linkedSetOf<String>()
    val lines = mutableListOf<String>()
    var carry = false
    for (raw in all) {
        // A row inside a box (a code view, a table) is the agent's content, never its error (1.4.2, from review).
        if (raw.trimStart().firstOrNull()?.let { it in "│┃|╭╰┌└├" } == true) { carry = false; continue }
        val l = raw.trim().trim('│', '┃', '|', ' ').trim()
        val mine = YOUR_LINE.containsMatchIn(l)
        val marked = !mine && (ERROR_MARK.containsMatchIn(l) || AGENT_OWN_ERROR.containsMatchIn(l) || ERROR_LEAD.containsMatchIn(l))
        var added = false
        if (marked || (carry && !mine)) for ((pattern, summary) in AGENT_ERRORS) if (pattern.containsMatchIn(l)) {
            // 2.2.0: llmproxy's own wording names it, so the notice can too.
            found += if (summary == NO_MODEL && l.contains("llmproxy", ignoreCase = true)) LLMPROXY_NO_MODEL else summary
            lines += l; added = true
        }
        // The next line continues an error only after a line that named one (1.4.2, from review).
        carry = marked && added
    }
    // A crash counts only when the shell's prompt is back at the bottom: the agent itself is gone
    // (1.4.2, from review: a traceback the agent shows from your program is not its crash).
    // The shell is back only when the screen's own last line is its prompt (1.4.2, from review);
    // the crash itself is looked for outside boxes.
    val rows = all.filter { r -> r.trimStart().firstOrNull()?.let { it in "│┃|╭╰┌└├" } != true }.map { it.trim() }
    if (all.lastOrNull()?.trim()?.let { SHELL_PROMPT.containsMatchIn(it) } == true &&
        rows.dropLast(1).any { r -> !YOUR_LINE.containsMatchIn(r) && AGENT_CRASHES.any { (p, _) -> p.containsMatchIn(r) } }) {
        found += "the agent crashed"; found += "the agent exited"
    }
    return found.takeIf { it.isNotEmpty() }?.let { it.joinToString("; ") to lines }
}

/** 2.2.0: llmproxy's "No 'flagship' models are currently available" and "No model currently qualifies". */
private val MODEL_UNAVAILABLE = Regex("""\bNo '[^']{1,80}' models? (?:is|are) currently available\b|\bNo model currently qualifies\b""", RegexOption.IGNORE_CASE)
internal const val NO_MODEL = "no model is available for this tier right now"
internal const val LLMPROXY_NO_MODEL = "llmproxy has no available model for this tier right now"

/**
 * An agent's error as its own card in the chat (2.2.0, Bill: "a different kind of card not a
 * question card in the pane chat view that I can acknowledge and clear"). Never a question: no
 * choices and no keys. [identity] is what acknowledging it keys on, with the server, pane and
 * session, so the same error stays cleared and a new one shows again. [retryText] is the user's
 * message right above OpenCode's failed turn, read from the screen; the app resends it only when
 * it is the message the phone itself sent there. [message] is the agent's own text, when the app
 * read it whole.
 */
data class AgentErrorNotice(
    val title: String,
    val summary: String,
    val message: String?,
    val identity: String,
    val retryText: String? = null,
    val modelUnavailable: Boolean = false,
    /** Whether the agent's own model picker is a safe next step (OpenCode's /models, through the model dialog). */
    val switchModel: Boolean = false,
)

/** OpenCode's turn footer under each reply: "Build · <model> · 1m 25s", "Plan · <model> · 5.4s · 37.7 tok/s". */
private val OPENCODE_TURN_FOOTER = Regex("""^[A-Z][\w-]*\s+·\s+.+\s+·\s+(?:\d+h\s*)?(?:\d+m\s*)?\d+(?:\.\d+)?s(?:\s+·.*)?$""")

/** OpenCode's failed turn: its error text, the user's message above it (when wholly on screen) and its footer. */
internal class OpenCodeTurnError(val message: String, val userText: String?, val footer: String)

/**
 * OpenCode's error after a failed turn (2.2.0): in a session, right above the input box, the turn
 * footer ("Build · <model> · 1m 25s") under a paragraph that starts "Error: ", drawn in the
 * transcript, not as a dialog or a toast, so no key removes it. Rows are cut at the width of the
 * box's "╹▀▀▀" edge, so OpenCode's sidebar is not read into the error. Null for anything else.
 */
internal fun openCodeTurnError(screen: String): OpenCodeTurnError? {
    val rows = stripAnsi(screen).lines().map { it.replace("\r", "").trimEnd() }.takeLast(80)
    val tail = rows.takeLast(20)
    val modeInTail = openCodeComposerMode(tail)
    if (modeInTail < 1) return null
    val mode = rows.size - tail.size + modeInTail
    val bar = rows[mode].indexOfFirst { it == '┃' || it == '│' }
    if (bar < 0) return null
    val edge = rows.subList(mode + 1, rows.size).firstOrNull { it.trimStart().startsWith("╹") }
    val width = edge?.let { it.lastIndexOf('▀') + 1 }?.takeIf { it > bar + 1 } ?: Int.MAX_VALUE
    fun cut(r: String) = if (r.length > width) r.substring(0, width) else r
    fun boxRow(r: String) = r.length > bar && (r[bar] == '┃' || r[bar] == '│') && r.substring(0, bar).isBlank()
    var i = mode
    while (i > 0 && boxRow(rows[i - 1])) i--
    i--
    while (i >= 0 && cut(rows[i]).isBlank()) i--
    if (i < 0 || boxRow(rows[i])) return null
    val footer = cut(rows[i]).trim()
    if (!OPENCODE_TURN_FOOTER.matches(footer)) return null
    i--
    while (i >= 0 && cut(rows[i]).isBlank()) i--
    val end = i
    while (i >= 0 && cut(rows[i]).isNotBlank() && !boxRow(rows[i])) i--
    val start = i + 1
    // The paragraph's first row must be on screen, with something read above it.
    if (start > end || start == 0) return null
    val block = (start..end).map { cut(rows[it]).trim() }
    if (!block.first().startsWith("Error: ")) return null
    // The user's message: OpenCode's box of "┃" rows above the reply, only when its top is on screen too.
    while (i >= 0 && cut(rows[i]).isBlank()) i--
    var userText: String? = null
    if (i >= 0 && boxRow(rows[i])) {
        val bottom = i
        while (i >= 0 && boxRow(rows[i])) i--
        if (i >= 0) userText = (i + 1..bottom).map { cut(rows[it]).substring(bar + 1).trim() }
            .filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }
    return OpenCodeTurnError(block.joinToString(" "), userText, footer)
}

/**
 * The error card for [screen], or null (2.2.0). OpenCode's failed turn is read whole, with its
 * text; any other error [detectAgentError] finds gets its summary. A crash, a sign-in or a full
 * context offers no retry: only the terminal.
 */
fun agentErrorNotice(screen: String, agent: String?, agentName: String): AgentErrorNotice? {
    val plain = stripAnsi(screen)
    if (isOpenCode(agent)) openCodeTurnError(plain)?.let { e ->
        val noModel = MODEL_UNAVAILABLE.containsMatchIn(e.message)
        val llmproxy = noModel && e.message.contains("llmproxy", ignoreCase = true)
        val summary = when {
            llmproxy -> LLMPROXY_NO_MODEL
            noModel -> NO_MODEL
            else -> AGENT_ERRORS.firstOrNull { it.first.containsMatchIn(e.message) }?.second ?: "its last turn failed"
        }
        return AgentErrorNotice(
            title = when { llmproxy -> "llmproxy: no model available"; noModel -> "$agentName: no model available"; else -> "$agentName error" },
            summary = summary, message = e.message,
            identity = listOf("opencode-turn", e.footer, e.message, e.userText.orEmpty()).joinToString("\n"),
            retryText = e.userText, modelUnavailable = noModel, switchModel = true,
        )
    }
    val (why, lines) = agentErrorLines(plain) ?: return null
    val noModel = why.contains(NO_MODEL) || why.contains(LLMPROXY_NO_MODEL)
    return AgentErrorNotice(
        title = if (why.contains(LLMPROXY_NO_MODEL)) "llmproxy: no model available" else "$agentName error",
        summary = why, message = null,
        identity = (listOf("screen", why) + lines).joinToString("\n"), modelUnavailable = noModel,
    )
}

/** A line that opens with an error itself: "Rate limit exceeded", "429 Too Many Requests", "503 Service Unavailable" (1.4.2, from review). */
private val ERROR_LEAD = Regex("""^\W{0,3}(Rate limit (exceeded|reached)|429 Too Many Requests|Too Many Requests|5\d\d (Internal Server Error|Service Unavailable|Bad Gateway|Gateway Time-?out)|402 Payment Required|Usage limit (reached|exceeded)|Out of credits|Insufficient (credits|balance)|Connection (lost|reset|refused|closed))\b""", RegexOption.IGNORE_CASE)

/** Your own lines: the echo of what you typed, or the input box. */
private val YOUR_LINE = Regex("""^(>|❯|›|»)(\s|$)""")
private val ERROR_MARK = Regex("""^(✗|⚠|×|✖|❌|■|⎿|‼|!)|^\W{0,3}(Error|ERROR|Fatal|FATAL|fatal|Failed|FAILED|error)\b|\b[Ee]rror:|\((HTTP|status) [45]\d\d\)|\bstatus [45]\d\d\b""")
/** Messages the agents print on their own line (from their programs' strings, 2026-11-14). */
private val AGENT_OWN_ERROR = Regex("""^\W{0,3}(You are not logged in|You're not logged in|Not logged in|Please (log|sign) in|Your credit balance is too low|Quota exceeded|Grok is temporarily overloaded|prompt is too long:|Context window exceeded|Compaction failed|Rate limited|Too Many Requests|Service Unavailable|Internal Server Error|Bad Gateway|Token refresh failed|Invalid .{0,40}credentials|Billing error|You've hit your usage limit|Claude usage limit reached|A network error occurred|No model currently qualifies|No '[^']{1,80}' models? (?:is|are) currently available)""", RegexOption.IGNORE_CASE)

private val AGENT_ERRORS = listOf(
    MODEL_UNAVAILABLE to NO_MODEL,
    Regex("""✗\s*Compaction|[Cc]ompaction failed|failed to (start )?compact|compaction:?error|compaction_failed""") to "compaction failed",
    Regex("""\bAll \d+ .{0,80}candidate\(?s?\)? failed""") to "every model candidate failed",
    // omp and pi on the llmproxy stack: "Error: Retry budget exhausted after 10 retries: 502 ...".
    Regex("""Retry budget exhausted|Retry failed after \d+ attempts""", RegexOption.IGNORE_CASE) to "retries exhausted",
    Regex("""Rate limit(ed| exceeded| reached| error)?\b|rate[-_ ]limited|rate_limit_error|\b429\b|Too Many Requests|resource.?exhausted|MODEL_CAPACITY_(EXHAUSTED|EXCEEDED)""", RegexOption.IGNORE_CASE) to "model rate-limited",
    Regex("""usage limit|hit your (usage )?limit|quota (exceeded|exhausted)|QuotaExceeded|limit reached.{0,40}reset|insufficient_quota|exceeded your current quota|message quota exceeded""", RegexOption.IGNORE_CASE) to "usage limit reached",
    Regex("""credit balance is too low|out of credits|insufficient (credits?|balance|funds)|\b402\b|Payment Required|Billing error|billing_block""", RegexOption.IGNORE_CASE) to "out of credits",
    Regex("""API Error:?\s*(\d{3}|Overloaded|Request rejected|Connection)""") to "API error",
    Regex("""\b(Provider|Upstream) error|upstream_error|provider returned an? error|Last upstream error|\b5\d\d\b.{0,30}(error|server|gateway|unavailable)|\b(status|HTTP) 5\d\d\b|Internal Server Error|Service Unavailable|Bad Gateway|temporarily unavailable""", RegexOption.IGNORE_CASE) to "provider error",
    Regex("""\b[Oo]verloaded\b|overloaded_error|serverOverloaded""") to "model overloaded",
    Regex("""network error|connection (lost|reset|refused|closed|timed out)|ECONNRESET|ECONNREFUSED|ETIMEDOUT|ENOTFOUND|fetch failed|socket hang up|could not resolve host""", RegexOption.IGNORE_CASE) to "connection lost",
    Regex("""not logged in|please (log|sign) ?in|/login\b|re-?authenticate|log ?in again|token (has )?expired|token_expired|invalid (api[_ ]?key|credentials)|\bunauthori[sz]ed\b|\b401\b|\b403\b|\bforbidden\b|authentication failed""", RegexOption.IGNORE_CASE) to "signed out or not allowed",
    Regex("""context (window|length) (exceeded|exceeds|is full|limit)|exceeds the context window|prompt is too long|maximum context length|context_length_exceeded|too many tokens""", RegexOption.IGNORE_CASE) to "context window full",
)

/** A crash: these read as one on their own (a panic, a traceback, a fatal error). */
private val AGENT_CRASHES = listOf(
    Regex("""thread '.{1,40}' panicked at|Traceback \(most recent call last\)|Segmentation fault|core dumped|^Killed$|FATAL ERROR:|Unhandled(Promise)?Rejection|unhandled exception|uncaught exception""", RegexOption.IGNORE_CASE) to "the agent crashed",
)

/** A shell's prompt: "tester@host:~/work$", "user@devbox2:~ %" (a bare "$" is too easily something else). */
private val SHELL_PROMPT = Regex("""^[\w.-]+@[\w.-]+(:\S*)?\s?[$#%]\s*$""")

internal fun isOpenCode(agent: String?): Boolean = agent?.lowercase() in setOf("opencode", "opencode-ursinus")

private val OPENCODE_DRAWER_TABS = Regex("""\bSubagents\b.*\bShell\b.*\bTerminals\b""", RegexOption.IGNORE_CASE)
private val OPENCODE_DRAWER_FOOTER = Regex("""\besc\b.*\bshow inactive\s+ctrl\+a\s+tabs\b""", RegexOption.IGNORE_CASE)

/** This drawer owns keyboard input, but offers no approval or question choices. */
internal fun openCodeBackgroundDrawer(agent: String?, screen: String): Boolean {
    if (!isOpenCode(agent)) return false
    val rows = stripAnsi(screen).lineSequence().filter { it.any(Char::isLetterOrDigit) }.toList().takeLast(20)
    return rows.lastOrNull()?.let(OPENCODE_DRAWER_FOOTER::containsMatchIn) == true &&
        rows.dropLast(1).any(OPENCODE_DRAWER_TABS::containsMatchIn)
}

private val OPENCODE_COMMANDS = Regex("""\bctrl\+p\s+commands\b""", RegexOption.IGNORE_CASE)
private val OPENCODE_MODE = Regex("""^[┃│]\s+(Build|Plan)(?:\s+·)?\s+[^┃│]+[┃│]?.*$""")
private val OPENCODE_EMPTY_HINT = Regex("""^\s*[┃│]\s+Ask anything(?:\.\.\.|…)\s*(?:[┃│].*)?$""")

/** The normal OpenCode composer draws model/mode above its commands footer, not modal controls. */
private fun openCodeComposerMode(rows: List<String>): Int {
    val footer = rows.indexOfLast { it.isNotBlank() }
    if (footer < 1 || !OPENCODE_COMMANDS.containsMatchIn(rows[footer])) return -1
    return (footer - 1 downTo maxOf(0, footer - 3)).firstOrNull { i ->
        OPENCODE_MODE.matches(rows[i].trim()) && rows.subList(i + 1, footer).all {
            it.isBlank() || it.trimStart().startsWith("╹")
        }
    } ?: -1
}

/** A positively identified empty composer, required before typing despite stale blocked metadata. */
internal fun openCodeEmptyComposer(screen: String): Boolean {
    val rows = stripAnsi(screen).lines().takeLast(20)
    val mode = openCodeComposerMode(rows)
    if (mode < 1) return false
    return (mode - 1 downTo maxOf(0, mode - 4)).any { i ->
        OPENCODE_EMPTY_HINT.matches(rows[i]) && rows.subList(i + 1, mode).all {
            it.isBlank() || it.trim().let { row -> row == "┃" || row == "│" }
        }
    }
}

/**
 * OpenCode's box with nothing typed in it inside a session (2.1.5, Bill: "herdrdroid couldn't send
 * Ursinus OpenCode the cross server info 5 times"). There OpenCode shows no placeholder: the box is
 * its top padding row, its one text row and a gap row, all a bare "┃", over the "Build · <model>"
 * row. The rows are cut at the width of the box's "╹▀▀▀" bottom edge, so OpenCode's sidebar beside
 * it is not read as typing. Typed text fills the text row, and a draft of line feeds makes the box
 * taller, so either one is not empty. Spaces typed alone can't be seen on the screen. On the home
 * screen the text row shows OpenCode's "Ask anything… \"<example>\"" placeholder, which counts as
 * empty only when it is drawn wholly in grey or faint, on a read with styles.
 */
internal fun openCodeSessionComposerEmpty(screen: String): Boolean {
    val rawRows = screen.lines().map { it.replace("\r", "") }.takeLast(20)
    val rows = rawRows.map { it.replace(ANSI_SEQ, "") }
    val mode = openCodeComposerMode(rows)
    if (mode < 3) return false
    val bar = rows[mode].indexOfFirst { it == '┃' || it == '│' }
    val edge = rows.subList(mode + 1, rows.size).firstOrNull { it.trimStart().startsWith("╹") } ?: return false
    val width = edge.lastIndexOf('▀') + 1
    if (bar < 0 || width <= bar + 1 || edge.indexOf('╹') != bar) return false
    fun inBox(r: String) = r.length > bar && r[bar] == rows[mode][bar] && r.substring(0, bar).isBlank()
    var top = mode
    while (top > 0 && inBox(rows[top - 1])) top--
    if (mode - top != 3) return false
    // The text row may show OpenCode's own placeholder, drawn wholly in grey (home screen).
    fun empty(i: Int): Boolean {
        val text = rows[i].take(width).drop(bar + 1)
        if (text.isBlank()) return true
        if (i != mode - 2 || !OPENCODE_PLACEHOLDER.matches(text.trim())) return false
        val chars = styledChars(rawRows[i])
        if (chars.joinToString("") { it.ch.toString() } != rows[i]) return false
        return chars.subList(bar + 1, minOf(width, chars.size)).all { it.ch.isWhitespace() || it.grey || it.faint }
    }
    return (top until mode).all(::empty)
}
private val OPENCODE_PLACEHOLDER = Regex("""^Ask anything(?:\.\.\.|…)(?: "[^"]*")?$""")

/**
 * An OpenCode box with nothing typed in it, on the home screen (its grey placeholder) or in a session
 * (2.1.5). Only [openCodeSessionComposerEmpty] decides: the older [openCodeEmptyComposer] ignores
 * colour, so the words "Ask anything…" typed in a session box would read as empty (Grok's review).
 */
internal fun openCodeComposerEmpty(screen: String): Boolean = openCodeSessionComposerEmpty(screen)

private val OPENCODE_WEB_SEARCH_FOOTER =
    Regex("""^↑/?↓\s+select\s+enter\s+submit\s+esc\s+dismiss$""", RegexOption.IGNORE_CASE)

private val OPENCODE_PERMISSION_TITLE = Regex("""^(?:△\s+)?(?:Permission required|Reject permission)$""")
private val OPENCODE_PERMISSION_BUTTONS = Regex("""(Allow once)\s{3,}(?:(Always allow)\s{3,})?(Reject)$""")
private val OPENCODE_PERMISSION_HINT = Regex("""(?:⇆|←→)\s+select\s+enter\s+confirm$""")
/** OpenCode 2.3.2 puts its fullscreen toggle ("ctrl+f fullscreen", "ctrl+f minimize") before "⇆ select" (2.1.2). */
private val OPENCODE_PERMISSION_FULLSCREEN = Regex("""(?:^|\s+)(?:\S+\s+)?(?:fullscreen|minimize)$""")

private fun detectOpenCodePermission(styled: String, text: String): Prompt? {
    fun clean(row: String) = row.trim().trim('┃', '│', '|').trim()
    val rows = text.lines()
    var footer = rows.indexOfLast { clean(it).isNotEmpty() }
    if (footer < 0) return null
    // A status row under the dialog ("~/dir  12K (6%)  ctrl+p commands") is not part of it (2.1.2).
    if (footer > 0 && rows[footer].none { it in "┃│" } && OPENCODE_COMMANDS.containsMatchIn(rows[footer]) &&
        OPENCODE_PERMISSION_HINT.find(clean(rows[footer])) == null) {
        footer = (footer - 1 downTo 0).firstOrNull { clean(rows[it]).isNotEmpty() } ?: return null
    }
    val hint = OPENCODE_PERMISSION_HINT.find(clean(rows[footer]))
    val title = (footer downTo maxOf(0, footer - 60)).firstOrNull { OPENCODE_PERMISSION_TITLE.matches(clean(rows[it])) }
    if (title == null && (hint == null || !clean(rows[footer]).contains("Allow once"))) return null
    // A shell command wrapped inside the dialog can start a row with ">" (">/dev/null 2>&1"): it is
    // the command being asked about, not an input row under the dialog (2.1.2).
    val command = title?.let { t -> (t + 1..footer).firstOrNull { clean(rows[it]).startsWith("$ ") } }
    val commandEnd = command?.let { c -> (c + 1..footer).firstOrNull { clean(rows[it]).isEmpty() } ?: (footer + 1) }
    if (title != null && agentWentOn((title + 1..footer).filter { command == null || it !in command until checkNotNull(commandEnd) }.map { rows[it] })) return null
    val region = rows.subList(title ?: footer, footer + 1)
    val heading = title?.let { clean(rows[it]).removePrefix("△ ") } ?: "Permission required"
    fun review() = Prompt(heading, emptyList(), region = region.map(::clean), terminalReview = true)
    if (title == null || heading != "Permission required" || hint == null) return review()
    val onFooter = clean(rows[footer]).substring(0, hint.range.first).trimEnd().replace(OPENCODE_PERMISSION_FULLSCREEN, "").trimEnd()
    // Narrow panes stack the hints under the buttons, with a blank row between them (2.3.2).
    val buttonRow = if (onFooter.isNotBlank() && onFooter.contains("Allow once")) footer
        else (footer - 1 downTo maxOf(0, footer - 2)).firstOrNull { clean(rows[it]).isNotEmpty() } ?: return review()
    if (buttonRow <= title) return review()
    val buttons = OPENCODE_PERMISSION_BUTTONS.find(if (buttonRow == footer) onFooter else clean(rows[buttonRow])) ?: return review()
    val body = rows.subList(title + 1, buttonRow).map(::clean).filter(String::isNotEmpty)
    if (body.isEmpty() || body.any { '…' in it || "..." in it } || body.last().trim() in listOf("$", "# Shell command")) return review()
    val labels = listOfNotNull(buttons.groups[1]?.value, buttons.groups[2]?.value, buttons.groups[3]?.value)
    val styledRow = styled.lines().getOrNull(buttonRow).orEmpty()
    val chars = styledChars(styledRow)
    val plain = chars.joinToString("") { it.ch.toString() }
    val fills = labels.map { label ->
        val at = plain.indexOf(label)
        if (at < 0 || plain.lastIndexOf(label) != at) null
        else chars.subList(at, at + label.length).map { it.background }.distinct().singleOrNull()
    }
    // OpenCode highlights with background color, not a glyph. Only trust a unique fill
    // against two matching unselected buttons; plain or ambiguous frames cannot approve.
    val focused = if (fills.size == 3 && fills.none { it == null } && fills.distinct().size == 2)
        fills.indexOfFirst { fill -> fills.count { it == fill } == 1 }.takeIf { it >= 0 }?.let { (it + 1).toString() }
        else null
    // Every button the dialog shows, Always allow included (2.1.2, Bill: "The alert should offer
    // whatever options the question does").
    return Prompt(heading, labels.mapIndexed { i, label -> Choice(label, (i + 1).toString(), false) }, focused, escRejects = true, parts = questionParts((listOf(heading) + body).map { QLine(it) }),
        horizontal = true, approvalIdentity = rows.subList(title, buttonRow).joinToString("\n"), openCodePermission = true)
}
private val OPENCODE_WEB_SEARCH_OPTION = Regex("""^(?:[❯›>]\s*)?([1-3])[.]\s+(.+)$""")

private val OPENCODE_QUESTION_FOOTER = Regex("""(⇆\s+tab\s+)?(↑↓\s+select\s+)?enter\s+(submit|confirm|toggle)\s+esc\s+dismiss""")
private val OPENCODE_QUESTION_OPTION = Regex("""([1-9])\. (\S.*)""")
/** A checkbox in front of a multi-select option ("1. [ ] Docs", "1. [✓] Docs"), 2.3.2. */
private val OPENCODE_QUESTION_CHECKBOX = Regex("""^\[[ ✓]\]\s+""")
/** OpenCode 2.3.2's question form, in edit mode on its own answer row: "enter submit  esc close". */
private val OPENCODE_QUESTION_EDIT_FOOTER = Regex("""enter\s+submit\s+esc\s+close""")
private const val OPENCODE_CUSTOM = "Type your own answer"

/**
 * The rows of an OpenCode question frame with the pane's inset taken off (2.1.2): OpenCode 2.3.2
 * draws its dialogs two columns in from the pane edge ("  ┃  …"), v1.19.6 at the edge. [footer]
 * is the last row with text inside the border. A status row under the dialog
 * ("~/dir  12K (6%)  ctrl+p commands") is not part of it. Null when the bottom is no bordered frame.
 */
private class OpenCodeFrame(val rows: List<String>, val footer: Int)

private fun openCodeFrame(text: String): OpenCodeFrame? {
    var raw = text.lines().takeLast(80).map(String::trimEnd).dropLastWhile(String::isBlank)
    if (raw.isNotEmpty() && '┃' !in raw.last() && OPENCODE_COMMANDS.containsMatchIn(raw.last()))
        raw = raw.dropLast(1).dropLastWhile(String::isBlank)
    val last = raw.indexOfLast { it.trim() != "┃" }
    if (last < 0) return null
    val inset = raw[last].indexOf('┃')
    if (inset < 0 || raw[last].substring(0, inset).isNotBlank()) return null
    val pad = " ".repeat(inset)
    // Rows of another column (the transcript beside it) are not the frame's: mark them unusable.
    val rows = raw.map { if (it.startsWith("$pad┃")) it.substring(inset) else "\u0000$it" }
    return OpenCodeFrame(rows, last)
}

/** Native QuestionPrompt (OpenCode v1.19.6, packages/tui/src/routes/session/question.tsx) and
 * OpenCode 2.3.2's question form (the "question" tool, title "Questions", 2.1.2).
 * A digit selects directly, without depending on color-only focus. Only a complete single-select
 * frame (no tabs, review, or custom editor) has that digit-to-submit contract; the others are
 * shown as a card answered in Terminal.
 */
private fun detectOpenCodeQuestion(text: String): Prompt? {
    val frame = openCodeFrame(text) ?: return null
    val rows = frame.rows
    val footer = frame.footer
    if (!rows[footer].startsWith("┃  ")) return null
    val (tabs, selection, enter) = OPENCODE_QUESTION_FOOTER.matchEntire(rows[footer].removePrefix("┃  "))
        ?.destructured ?: return null
    // Keep legacy, glyph-focused confirm fixtures on their existing arrow/Enter path.
    if (tabs.isEmpty() && selection.isNotEmpty() && enter == "confirm") return null
    fun review(question: String = "OpenCode has a question") = Prompt(question, emptyList(), terminalReview = true, openCodeForm = true)

    // The renderer has a spacer above the question, between it and the options, and before the
    // footer. Require all three: otherwise the viewport may start partway through the question.
    val panel = rows.take(footer).takeLastWhile { it.startsWith("┃") }
    val end = panel.indexOfLast { it != "┃" } + 1
    if (end == 0 || end == panel.size) return review()
    val divider = (end - 1 downTo 0).firstOrNull { panel[it] == "┃" } ?: return review()
    val top = (divider - 1 downTo 0).firstOrNull { panel[it] == "┃" } ?: return review()
    val heading = panel.subList(top + 1, divider)
    if (heading.isEmpty() || heading.any { !it.startsWith("┃  ") || OPENCODE_QUESTION_OPTION.matches(it.removePrefix("┃  ")) }) return review()
    val question = heading.joinToString(" ") { it.removePrefix("┃  ").trimStart() }
    // 2.3.2 titles its form "Questions" above the question; the question tool always adds the
    // "Type your own answer" row there, last.
    val titled = panel.subList(0, top).lastOrNull { it != "┃" }?.removePrefix("┃  ")?.trim() == "Questions"
    val terminalOnly = tabs.isNotEmpty() || selection.isEmpty() || enter != "submit"
    val choices = mutableListOf<Choice>()
    var custom: String? = null
    for (row in panel.subList(divider + 1, end)) {
        // Even an empty focused custom editor displays a placeholder below the custom row.
        // Do not send digits to a textarea or accept any unrecognized continuation there.
        if (custom != null) return review(question)
        if (row.startsWith("┃     ") && choices.isNotEmpty()) continue // option description
        val option = OPENCODE_QUESTION_OPTION.matchEntire(row.removePrefix("┃  ")) ?: return review(question)
        val (digit, shown) = option.destructured
        if (digit.toInt() != choices.size + 1) return review(question)
        val label = if (terminalOnly) shown.replace(OPENCODE_QUESTION_CHECKBOX, "") else shown
        if (label == OPENCODE_CUSTOM) custom = digit
        // A ✓ after a label marks an answer already picked: the frame is not a fresh question.
        else if (label.endsWith(" ✓") && !terminalOnly) return review(question)
        else choices += Choice(label, digit, false)
    }
    if (choices.isEmpty()) return review(question)
    // Several questions or answers: the card shows the question, Terminal answers it (2.1.2).
    if (terminalOnly) return review(question)
    // The 2.3.2 form always ends in the custom row; one that ends elsewhere has typed text in it.
    if (titled && custom == null) return review(question)
    return Prompt(question, choices, numberKeys = true, openCodeCustom = custom,
        openCodeQuestionFrame = (panel.subList(top, panel.size) + rows[footer]).joinToString("\n"))
}

/**
 * The question of a native OpenCode single-select question whose own answer row is open for
 * typing (2.1.2): the frame right after its "Type your own answer" digit. Null for anything else.
 */
internal fun openCodeQuestionEditing(text: String): String? {
    val frame = openCodeFrame(stripAnsi(text)) ?: return null
    val rows = frame.rows
    if (!rows[frame.footer].startsWith("┃  ") || !OPENCODE_QUESTION_EDIT_FOOTER.matches(rows[frame.footer].removePrefix("┃  "))) return null
    val panel = rows.take(frame.footer).takeLastWhile { it.startsWith("┃") }
    val end = panel.indexOfLast { it != "┃" } + 1
    if (end == 0) return null
    val divider = (end - 1 downTo 0).firstOrNull { panel[it] == "┃" } ?: return null
    val top = (divider - 1 downTo 0).firstOrNull { panel[it] == "┃" } ?: return null
    val heading = panel.subList(top + 1, divider)
    if (heading.isEmpty() || heading.any { !it.startsWith("┃  ") }) return null
    // The open row is the last one, with the placeholder or nothing typed yet after its digit.
    val open = OPENCODE_QUESTION_OPTION.matchEntire(panel[end - 1].removePrefix("┃  "))
    val emptyOpen = open?.groupValues?.get(2) == OPENCODE_CUSTOM || Regex("""[1-9]\.""").matches(panel[end - 1].removePrefix("┃  ").trim())
    if (!emptyOpen) return null
    return heading.joinToString(" ") { it.removePrefix("┃  ").trimStart() }
}

/** OpenCode highlights this picker with color alone. Its numbered keys select and submit directly. */
private fun detectOpenCodeWebSearch(text: String): Prompt? {
    val lines = text.lineSequence().toList().takeLast(20).map { it.trim().trim('│', '┃', '|').trim() }
        .filter { it.isNotBlank() }
        .dropLastWhile { it.startsWith("~/") || it.startsWith("/") }
    val footer = lines.lastOrNull() ?: return null
    if (!OPENCODE_WEB_SEARCH_FOOTER.matches(footer)) return null

    val header = lines.indexOfLast { it.equals("Web Search", ignoreCase = true) }
    if (header < 0) return null
    val body = lines.subList(header + 1, lines.lastIndex)
    val question = body.firstOrNull() ?: return null
    if (!question.startsWith("Allow OpenCode to search the web", ignoreCase = true) || !question.endsWith("?")) return null
    val choices = body.drop(1).map { line ->
        val match = OPENCODE_WEB_SEARCH_OPTION.matchEntire(line) ?: return null
        Choice(match.groupValues[2], match.groupValues[1], pressEnter = false)
    }
    if (choices.map { it.keys } != listOf("1", "2", "3") ||
        !choices[0].label.startsWith("Allow ", ignoreCase = true) ||
        !choices[1].label.equals("Choose another provider", ignoreCase = true) ||
        !choices[2].label.equals("Disable web search", ignoreCase = true)
    ) return null
    return Prompt(question, choices, numberKeys = true)
}

private val HERMES_APPROVAL_FOOTER =
    Regex("""^↑/↓ to select, Enter to confirm(?:\s*\(\d+s\))?$""", RegexOption.IGNORE_CASE)
private val HERMES_EMPTY_INPUT = Regex("""^⚠(?:️)?\s*[❯›]$""")
private val HERMES_HIDDEN_COMMAND = Regex("""^[.…]+\s*\(choose Show full command\)$""", RegexOption.IGNORE_CASE)
private val HERMES_SHOW_COMMAND = Regex("""^(?:[❯›>]\s*)?5\. Show full command$""", RegexOption.IGNORE_CASE)
private val HERMES_COMMAND_HEADING = Regex("""^(?:⚠️?\s*)?Dangerous Command$""")
private val CODEX_COMMAND_HEADING = Regex("""^Would you like to run the following command\?$""", RegexOption.IGNORE_CASE)
private val CODEX_OTHER_APPROVAL = Regex(
    """^(?:Would you like to (?:make the following edits|run the following tool|allow network access|grant these permissions)|Do you want to approve network access to .+|Approve app tool call)\?$""",
    RegexOption.IGNORE_CASE,
)
private val HIDDEN_CODEX_COMMAND = Regex(
    """^(?:…|\.{3})(?:\s|$)|^(?:\[|\()?(?:\d+\s+(?:lines?|characters?)\s+(?:omitted|hidden)|command (?:truncated|hidden))\b|(?:…|\.{3})$""",
    RegexOption.IGNORE_CASE,
)

// https://github.com/openai/codex/blob/rust-v0.169.0/codex-rs/tui/src/bottom_pane/request_user_input/mod.rs
private val CODEX_QUESTION_PROGRESS = Regex("""Question [1-9]\d*/[1-9]\d*(?: \([1-9]\d* unanswered\))?(?: · auto-resolves in .+)?""")
private val CODEX_QUESTION_SUBMIT = Regex("""[^\s|]+(?: [^\s|]+){0,2} to submit (?:answer|all)""")
private val CODEX_QUESTION_CONTINUATION = Regex("""←/→ to navigate questions|⌃p / ⌃n change question|\S+ to interrupt""")
private val CODEX_QUESTION_OPTION = Regex("""(?:›\s+|[0-9]+\.\s).*""")
private const val CODEX_UNANSWERED_TITLE = "Submit with unanswered questions?"
private const val CODEX_UNANSWERED_FOOTER = "Press enter to confirm or esc to go back"

/** Codex's `$ ` introduces the command, not a paragraph of its explanation. */
private fun codexCommandParts(rows: List<String>): QuestionParts? {
    val start = rows.indexOfFirst { it.trimStart().startsWith("$ ") }
    if (start < 1) return null
    val first = rows[start].trimStart().removePrefix("$ ")
    if (first.isBlank()) return null
    val inset = rows[start].takeWhile { it.isWhitespace() }
    val command = listOf(first) + rows.drop(start + 1).dropLastWhile { it.isBlank() }
        .map { it.removePrefix(inset) }
    val intro = questionParts(rows.take(start).map { QLine(it) })
    return intro.copy(blocks = intro.blocks.take(1) + QBlock(QKind.COMMAND, command.joinToString("\n")) + intro.blocks.drop(1))
}

private fun terminalCommandReview(command: String?): Prompt = Prompt(
    question = "Review the full command",
    choices = emptyList(),
    parts = QuestionParts(emptyList(), listOf(QBlock(QKind.HEADING, "Review the full command")) +
        listOfNotNull(command?.takeIf { it.isNotBlank() }?.let { QBlock(QKind.COMMAND, it) })),
    region = listOf("Open the terminal to review the full command."),
    terminalReview = true,
)

private fun detectHermesCommandPreview(text: String): Prompt? {
    val lines = text.lines().takeLast(80)
    val footer = lines.indexOfLast { HERMES_APPROVAL_FOOTER.matches(it.trim()) }
    if (footer < 0 || lines.drop(footer + 1).filter { it.isNotBlank() }.let { tail ->
            tail.size > 1 || tail.any { !HERMES_EMPTY_INPUT.matches(it.trim()) }
        }) return null
    val bottom = (footer - 1 downTo 0).firstOrNull { lines[it].isNotBlank() } ?: return null
    if (!lines[bottom].trimStart().startsWith("╰")) return null
    val top = (bottom - 1 downTo 0).firstOrNull { lines[it].trimStart().startsWith("╭") } ?: return null
    val rows = lines.subList(top + 1, bottom).map { it.trim() }
    if (rows.any { !it.startsWith("│") || !it.endsWith("│") }) return null
    val body = rows.map { it.removePrefix("│").removeSuffix("│").trimEnd().removePrefix(" ") }
    if (!HERMES_COMMAND_HEADING.matches(body.firstOrNull()?.trim().orEmpty())) return null
    val hidden = body.indexOfFirst { HERMES_HIDDEN_COMMAND.matches(it.trim()) }
    if (hidden < 1 || body.drop(hidden + 1).none { HERMES_SHOW_COMMAND.matches(it.trim()) }) return null
    return terminalCommandReview(body.subList(1, hidden).dropWhile { it.isBlank() }.joinToString("\n"))
}

fun detectPrompt(text: String, blocked: Boolean, agent: String? = null, version: String? = null): Prompt? {
    val plain = stripAnsi(text)
    if (agent.equals("hermes", true)) hermesUsageEnd(plain)?.let { end ->
        // A report supersedes any old question above it, not a new card below it. A close
        // hint alone is not a question, even when herdr still reports that it is blocked.
        val after = plain.lines().drop(end + 1).joinToString("\n")
        return detectPlainPrompt(after, false, agent, version)?.takeIf { it.choices.isNotEmpty() || it.terminalReview }
    }
    // Before the styles are stripped: they are the only mark of Muse's highlighted option (2.1.0).
    if (agent.equals("muse", true)) detectMuseQuotaDialog(text)?.let { return it }
    if (isOpenCode(agent)) {
        if (openCodeBackgroundDrawer(agent, plain) || openCodeComposerMode(plain.lines().takeLast(20)) >= 0) return null
        detectOpenCodeQuestion(plain)?.let { return it }
        detectOpenCodePermission(text, plain)?.let { return it }
    }
    return detectPlainPrompt(plain, blocked, agent, version)
}

/**
 * Claude Code's sessions list (2.1.1, Bill: "Claude might have had a question in chat that was not a
 * card but I couldn't see it in terminal view"). Left on an empty composer ("← for agents") moves the
 * session to the background and fills the screen with the list, and a question the session asks
 * then waits there ("Needs input", "1 awaiting input") without its dialog on screen. Captured from
 * Claude Code 2.1.329 in a throwaway container.
 */
private val CLAUDE_BACKGROUND_HINT = Regex("""^Your session moved to the background\b.*\besc returns to it\b""", RegexOption.IGNORE_CASE)
private val CLAUDE_AWAITING = Regex("""(?:^|\s)(\d{1,3}) awaiting input$""")

/**
 * How many sessions Claude's sessions list shows as awaiting input ("1 awaiting input"), 0 when
 * the list shows none, or the screen is not that list or a question dialog has replaced it (2.1.1).
 */
internal fun claudeSessionsAwaiting(text: String): Int {
    val rows = text.lines().takeLast(80).map { it.trim() }
    val hint = rows.indexOfLast { CLAUDE_BACKGROUND_HINT.containsMatchIn(it) }
    if (hint < 0 || rows.drop(hint + 1).any { QUESTION_FOOTER.containsMatchIn(it) }) return 0
    return rows.take(hint).firstNotNullOfOrNull { CLAUDE_AWAITING.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

internal fun claudeBackgroundList(text: String): Prompt? {
    val rows = text.lines().takeLast(80).map { it.trim() }
    val hint = rows.indexOfLast { CLAUDE_BACKGROUND_HINT.containsMatchIn(it) }
    if (hint < 0) return null
    // A question dialog below the hint is the session itself, back on screen: the usual parser reads it.
    if (rows.drop(hint + 1).any { QUESTION_FOOTER.containsMatchIn(it) }) return null
    val count = rows.take(hint).firstNotNullOfOrNull { CLAUDE_AWAITING.find(it) }
    val waiting = count?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val question = if (waiting > 0) "Claude's session is in the background, and its sessions list shows " +
        (if (waiting == 1) "a session" else "$waiting sessions") + " waiting for your input. Its question shows once you return to it."
        else "Claude's session is in the background, so its sessions list fills the screen. Return to it before sending anything."
    // Only lines that stay the same between reads, so the card's Esc passes the fresh-read check.
    val region = listOfNotNull(rows[hint], count?.value?.trim())
    return Prompt(question, emptyList(), region = region, claudeBackground = true)
}

/** Claude Code's cue for a question held back while text is typed in its input box (2.1.329 bundle). */
private const val CLAUDE_DRAFT_CUE = "Claude has a question for you. It shows once you send or clear what you're typing."

/** Whether Claude shows that a question waits behind the draft in its input box. */
internal fun claudeQuestionBehindDraft(text: String): Boolean =
    stripAnsi(text).lines().takeLast(80).any { it.trim().trim('│', '┃', '|').trim() == CLAUDE_DRAFT_CUE }

private fun detectPlainPrompt(text: String, blocked: Boolean, agent: String?, version: String?): Prompt? {
    // 2.1.4: Claude's mode row and helper list under a dialog are not the agent going on after it.
    @Suppress("NAME_SHADOWING") val text = if (agent.equals("claude", true)) withoutClaudeChromeUnderDialog(text) else text
    if ((agent == null || agent.equals("claude", true)) && claudeUsageOpen(text)) return null
    // 2.1.4: Claude's /btw panel is a side answer, not a question.
    if ((agent == null || agent.equals("claude", true)) && claudeBtwOverlay(text) != null) return null
    if (agent.equals("claude", true)) claudeBackgroundList(text)?.let { return it }
    if (agent.equals("codex", true)) {
        val rows = text.lines().takeLast(80)
        fun hints(row: String) = row.split('|').map(String::trim)
        val head = rows.indexOfLast { CODEX_QUESTION_PROGRESS.matches(it.trim()) || it.trim() == CODEX_UNANSWERED_TITLE }
        val footer = (head + 1..rows.lastIndex).lastOrNull { at ->
            rows[at].trim() == CODEX_UNANSWERED_FOOTER || hints(rows[at]).any(CODEX_QUESTION_SUBMIT::matches)
        } ?: -1
        if (head >= 0 || footer >= 0) {
            val confirmation = (head >= 0 && rows[head].trim() == CODEX_UNANSWERED_TITLE) ||
                (footer >= 0 && rows[footer].trim() == CODEX_UNANSWERED_FOOTER)
            val newer = if (footer >= 0) (footer + 1..rows.lastIndex).firstOrNull { at ->
                !isChrome(rows[at]) && !hints(rows[at]).all(CODEX_QUESTION_CONTINUATION::matches)
            } else (head + 1..rows.lastIndex).firstOrNull { at ->
                CODEX_COMMAND_HEADING.matches(rows[at].trim()) || CODEX_OTHER_APPROVAL.matches(rows[at].trim()) ||
                    (INPUT_ROW_UNDER.containsMatchIn(rows[at]) && !TYPED_ANSWER_HINT.containsMatchIn(rows[at]))
            }
            // Leave newer UI to the ordinary parser. A clipped native overlay must not
            // fall through as a generic actionable menu based on its numbered rows.
            if (newer != null) return detectPlainPrompt(rows.drop(newer).joinToString("\n"), false, agent, version)
            if (footer < 0 && !confirmation) return null
            val question = when {
                confirmation -> CODEX_UNANSWERED_TITLE
                head < 0 -> "Codex has a question"
                else -> rows.subList(head + 1, footer).map(String::trim)
                    .takeWhile { it.isNotBlank() && !CODEX_QUESTION_OPTION.matches(it) }
                    .joinToString(" ").ifBlank { "Codex has a question" }
            }
            // Enter can move focus, advance a step or submit, depending on the native overlay.
            return Prompt(question, emptyList(), terminalReview = true, codexQuestion = true)
        }
    }
    val openCode = isOpenCode(agent)
    if (openCode) {
        detectOpenCodeWebSearch(text)?.let { return it }
    }
    if (agent.equals("hermes", true)) {
        hermesClarifyFrame(text)?.let { frame ->
            return frame.card ?: detectPlainPrompt(frame.remainder, false, agent, version)
        }
        detectHermesCommandPreview(text)?.let { return it }
        val rows = text.lines().takeLast(80)
        fun body(row: String) = row.trim().trim('│').trim()
        val hidden = rows.indexOfLast { HERMES_HIDDEN_COMMAND.matches(body(it)) }
        if (hidden >= 0) {
            // A clipped or stale preview must not reach the generic numbered-menu parser.
            val footer = (hidden + 1..rows.lastIndex).firstOrNull { HERMES_APPROVAL_FOOTER.matches(rows[it].trim()) }
                ?: return null
            val latest = rows.drop(footer + 1).joinToString("\n")
            if (latest.isNotBlank() && !HERMES_EMPTY_INPUT.matches(latest.trim())) {
                return detectPrompt(latest, blocked, agent, version)
            }
            // If the heading or frame was clipped, the truncation marker, reveal option and
            // live footer still identify a preview. Don't guess at the missing command text.
            return if (rows.subList(hidden + 1, footer).any { HERMES_SHOW_COMMAND.matches(body(it)) }) {
                terminalCommandReview(null)
            } else null
        }
    }
    val rules = ParserRules.forAgent(agent, version)
    // A question dialog on screen means the agent waits, whatever herdr reported.
    // 2.1.4: Claude's permission dialog, its hint row at the bottom, waits whatever herdr reported.
    @Suppress("NAME_SHADOWING") val blocked = blocked || showsQuestionDialog(text) ||
        (agent.equals("claude", true) && claudePermissionFooterAtBottom(text)) ||
        (rules.extraFooter?.let { f -> text.lines().takeLast(6).any { f.containsMatchIn(it) } } == true)
    var raw = text.lines().takeLast(80)
    var pastDialog = false
    if (agent.equals("codex", true)) {
        val footer = raw.indexOfLast { CODEX_FOOTER.matches(it.trim()) }
        if (footer >= 0 && raw.drop(footer + 1).any { !isChrome(it) }) {
            raw = raw.drop(footer + 1)
            pastDialog = true
        }
    }
    var lines = raw.filter { it.isNotBlank() }.takeLast(40)
    if (lines.isEmpty()) return if (blocked) Prompt("This agent is waiting for you.", emptyList(), region = listOf(UNREAD_SCREEN)) else null

    val clean = { s: String -> s.trim().trim('│', '┃', '|', '╭', '╰', '╮', '╯', '─', ' ').trim() }
    if (agent.equals("codex", true) && CODEX_FOOTER.matches(lines.last().trim())) {
        val heading = lines.indexOfLast { CODEX_COMMAND_HEADING.matches(clean(it)) }
        if (heading >= 0 && lines.drop(heading + 1).none { clean(it).contains("Yes, proceed", ignoreCase = true) }) {
            return terminalCommandReview(null)
        }
    }
    // End each old dialog at its footer, then inspect any newer UI below it.
    val claudeFooterPattern = Regex(QUESTION_FOOTER.pattern + "|\\bEsc to (cancel|reject)\\s*[│┃|]*\\s*$", RegexOption.IGNORE_CASE)
    while (raw.isNotEmpty()) {
        val tab = raw.indexOfLast { parseTabBar(clean(it)) != null }
        if (tab >= 0) raw = raw.drop(tab)
        if (tab < 0 && agent?.lowercase() != "claude") break
        val footer = raw.indexOfFirst { claudeFooterPattern.containsMatchIn(it) }
        if (footer < 0) break
        val tail = raw.drop(footer + 1)
        val newer = tail.any { l ->
            val input = INPUT_ROW_UNDER.containsMatchIn(l)
            val typedAnswer = input && TYPED_ANSWER_HINT.containsMatchIn(clean(l))
            !typedAnswer && (input || (clean(l).any(Char::isLetterOrDigit) &&
                !isChrome(l) && !STANDALONE_CARD_HINT.matches(clean(l))))
        }
        if (!newer) { raw = raw.take(footer + 1); break }
        raw = tail
        pastDialog = true
    }
    lines = raw.filter { it.isNotBlank() }.takeLast(40)
    val activeText = raw.joinToString("\n")
    val dialogAt = raw.indexOfLast { parseTabBar(clean(it)) != null }
    // "❯ 1. Yes", "  2. Yes, and don't ask again for …" (labels can be long; up to 200 characters).
    // Grok draws radio buttons instead: "1 (○) Yes, and don't ask again", "2 (●) Yes, proceed", with
    // (●) on the highlighted option, which ↑/↓ move and Enter picks (checked in Grok 4.7, 2026-11-13).
    // One or two digits (1.4.0, from a code review: option 10 and longer menus never became a card).
    // Muse Code marks the highlighted row with "⟩" (1.4.7: "⟩ 1. Allow this stage once (y)").
    // 1.4.9 (the card audit): Hermes's "❯ [ ] 1. pytest" (a checkbox before the number) and "❯ [1] Approve
    // Once" (a number in brackets), and pi's "→" highlight.
    val option = Regex("""^[\s│┃|]*([❯›>▶➤⟩→]\s*)?(\[[ xX✓✔]\]\s+)?(?:(\d{1,2})(?:[.)]|\s+(\([○●◯◉]\)))|\[(\d{1,2})\])\s+(.{1,200}?)[\s│┃|]*$""")
    fun key(m: MatchResult) = m.groupValues[3].ifEmpty { m.groupValues[5] }
    fun label(m: MatchResult) = (m.groupValues[2] + m.groupValues[6]).trim()
    val marked = mutableSetOf<Int>()
    val optionRows = lines.withIndex().mapNotNull { (i, l) ->
        option.find(l)?.let { m ->
            if (m.groupValues[1].isNotBlank() || m.groupValues[4] in setOf("(●)", "(◉)")) marked += i
            Triple(i, key(m), label(m))
        }
    }
    // Keep the last run of consecutive numbers (1, 2, 3 …), so a numbered list earlier on the
    // screen isn't mixed into the menu.
    val run = mutableListOf<Triple<Int, String, String>>()
    for (o in optionRows) {
        if (run.isNotEmpty() && o.second.toInt() != run.last().second.toInt() + 1) run.clear()
        run += o
    }
    // An option's label wrapped onto the next line goes on there (1.4.9, from Grok's review: Copilot's
    // "(Manual" over "Approval for this session; …)"): the next line, when it is not an option row, while a
    // "(" is still open or it starts in lower case. A description under it (capitalised, its own sentence) is not.
    val numbered = run.map { (i, k, label) ->
        var full = label
        var j = i + 1
        while (j < lines.size && option.find(lines[j]) == null) {
            val next = clean(lines[j])
            // A key hint under the last option ("ctrl+g to edit in editor") is not part of it.
            if (next.isEmpty() || KEY_HINT_LINE.containsMatchIn(next) ||
                !(full.count { it == '(' } > full.count { it == ')' } || next.first().isLowerCase())) break
            full = "$full $next"; j++
        }
        Choice(full, k, false)
    }.distinctBy { it.keys }
    // The question: the text just above the first choice (up to four lines, skipping frame
    // lines), or else the last line on screen that ends with "?".
    // The whole question: every line above the first choice up to a blank line, the top of the
    // question's box or a rule (at most 20 lines), so a long question isn't cut.
    val firstOption = run.firstOrNull()?.let { raw.lastIndexOf(lines[it.first]) } ?: -1
    // Grok's command rows ("│ git …·", "┆ …"), read whatever frame its box has (2.1.4): Grok's
    // screen, or any screen with a row in Grok's "┆" gutter.
    val grokRows = agent.equals("grok", true) || raw.any { r -> GrokMeta.commandRow(r, null)?.cont == true }
    val above = run.firstOrNull()?.let {
        val at = firstOption
        if (at <= 0) null else {
            // Each line, and whether it is a Grok command line ("┃  │ grok usage ..."), which follows
            // its title after ": " ("Print plan usage with no session id: grok usage ...").
            val got = mutableListOf<QLine>()
            // Claude's multi-question dialog: everything from its tab bar down to the options is the
            // question, including the review step's "● Close 537" / "→ Close it" rows (1.2.9).
            val barAt = (at - 1 downTo maxOf(0, at - 30)).firstOrNull { parseTabBar(clean(raw[it])) != null }
            if (barAt != null) {
                var gapNext = false
                for (k in barAt until at) {
                    val c = clean(raw[k])
                    if (raw[k].isBlank() || c.isEmpty()) { gapNext = true; continue }
                    got += QLine(c, gapBefore = gapNext); gapNext = false
                }
                return@let got.takeIf { it.isNotEmpty() }
            }
            var i = at - 1
            // Blank lines inside a card are crossed (Antigravity puts one between "Requesting
            // permission for:", the command, and "Run this command?"), up to two of them; a rule, the
            // top of a box, or the agent's own activity lines above the card end the question.
            var gaps = 0
            var gap = false
            // At most 20 lines, or 60 while none of them asks yet (1.4.8: a Muse card with a long command
            // put its "Would you like to run the following command?" more than 20 lines up).
            while (i >= 0 && (got.size < 20 || (got.size < 60 && got.none { it.text.trimEnd().endsWith("?") }))) {
                val l = raw[i]
                val c = clean(l)
                // Grok's data-sharing banner can sit right above a card; it is its own question.
                if (got.isNotEmpty() && GROK_BANNER.containsMatchIn(c)) break
                // A rule on its own line ("──────") ends the question; clean() would empty it.
                if (got.isNotEmpty() && l.trim().matches(Regex("""^[─━═]{3,}$"""))) break
                // The top of Grok's light box ("╭───╮") ends its question; clean() would empty it and
                // the walk would cross it like a blank line into the reply above (2.1.4).
                if (grokRows && got.isNotEmpty() && l.trimStart().startsWith("╭")) {
                    val edgeTitle = l.trim().trim('╭', '╮', '─', ' ')
                    if (got.any { it.row } && edgeTitle.any(Char::isLetter)) got.add(0, QLine(edgeTitle))
                    break
                }
                if (l.isBlank() || c.isEmpty()) {
                    if (got.isNotEmpty() && !gap) { gap = true; gaps++; if (gaps > 2) break; got[0] = got[0].copy(gapBefore = true) }
                    i--; continue
                }
                if (l.trimStart().startsWith("╭") || l.trimStart().startsWith("┌") || c.matches(Regex("""^[─━═\-_\s]+$"""))) break
                // The agent's activity always ends the question; an echoed prompt does after a blank
                // line (1.2.7: before, Codex's card took in the conversation above it).
                // A status line (Grok's "… 7.5s 26s ⇣87.0k [↓][stop]", "esc to interrupt") ends it too,
                // wherever its marker is (1.2.7, Bill: "make sure you parse it correctly").
                // Grok's "›" title inside its box's frame is not an echoed prompt, blank row or not (2.1.4).
                val framedTitle = grokRows && l.trimStart().firstOrNull()?.let { it == '┃' || it == '│' } == true
                if (got.isNotEmpty() && (ACTIVITY.containsMatchIn(c) || STATUS_LINE.containsMatchIn(c) ||
                        (gap && PROMPT_ECHO.containsMatchIn(c) && !framedTitle))) break
                gap = false
                // Grok's command rows (2.1.4, Bill: "Herdrdroid still shows vertical bars for grok questions
                // like this, parse then nicely maybe as newlines?"): read in any frame, each without its
                // gutter and wrap mark; a "┆" row joins the row above it only when that row wrapped.
                val cmdRow = if (grokRows) GrokMeta.commandRow(l, raw.getOrNull(i + 1)) else null
                if (cmdRow != null) {
                    if (cmdRow.text.isNotBlank()) got.add(0, QLine(cmdRow.text, cmd = true, row = true, cont = cmdRow.cont, wraps = cmdRow.wraps))
                    i--; continue
                }
                // A box inside the card's frame holds the command: Grok's "┃  │ grok usage", Copilot's
                // "│ │ mkdir -p sub" (1.2.9: Copilot's too, so it shows as a command).
                val tl = l.trimStart()
                val inner = tl.drop(1).trimStart()
                // Grok's wrapped command goes on over rows with a "┆" gutter and a "·" at the end of each
                // wrapped row (2.1.3, Bill: "Notice the vertical pipe glyphs coming through"): command rows
                // too, without the gutter or the marks, so the card shows one command under its title.
                val gutter = tl.startsWith("┃") && inner.firstOrNull()?.let { it in GROK_GUTTER } == true
                val isCmd = (tl.startsWith("┃") || tl.startsWith("│")) && inner.startsWith("│") || gutter
                // Grok's "← → narrow scope" hint sits between the command and its options.
                // A dashed line with no words (the edges of Claude's diff box) separates paragraphs (1.4.9).
                if (!c.any(Char::isLetterOrDigit) && got.isNotEmpty()) got[0] = got[0].copy(gapBefore = true)
                // Grok's question card starts its question with a "?" icon (1.4.9).
                val row = if (gutter) c.trimStart { it in GROK_GUTTER || it == ' ' }.trimEnd().removeSuffix("·").trimEnd()
                    else if (isCmd && tl.startsWith("┃")) c.trimEnd().removeSuffix("·").trimEnd() else c
                if (row.any(Char::isLetterOrDigit) && !row.contains("narrow scope")) got.add(0, QLine(row.trimStart('›', '❯', ' ').replace(Regex("""^\?\s+"""), ""), isCmd, bar = !isCmd && tl.startsWith("│")))
                i--
            }
            // A "┆" row goes on the line above only when that row ends in Grok's "·" wrap mark; else
            // it is a line of its own (2.1.4). The command reads in its true order, one line per row.
            val joined = mutableListOf<QLine>()
            for (q in got) {
                val prev = joined.lastOrNull()
                if (q.row && q.cont && prev != null && prev.row && prev.wraps && !q.gapBefore)
                    joined[joined.size - 1] = prev.copy(text = prev.text.trimEnd() + " " + q.text.trimStart(), wraps = q.wraps)
                else joined += q
            }
            joined.takeIf { it.isNotEmpty() }
        }
    }
    // The question's text (notifications, matching) leaves out Claude's tab bar; the app shows it as steps.
    val aboveText = above?.let { got ->
        val body = got.mapNotNull { l ->
            val bar = parseTabBar(l.text)
            when {
                bar == null -> l
                bar.second.isEmpty() -> null
                else -> QLine(bar.second)
            }
        }
        buildString {
            body.forEachIndexed { k, l ->
                if (k > 0) append(if (l.cmd && !body[k - 1].cmd) ": " else " ")
                append(l.text)
            }
        }.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }
    val question = aboveText ?: lines.lastOrNull { clean(it).endsWith("?") }?.let(clean)
    var parts = above?.let(::questionParts).takeIf { aboveText != null }
    val beforeOptions = lines.take(run.firstOrNull()?.first ?: lines.size)
    // 2.1.4: Claude Code 2.1.329's titles ("Bash command · from the general-purpose agent", and "Run
    // command" in its fitted dialog, which draws no "Do you want to proceed?").
    val approvalHeader = beforeOptions.any { clean(it).startsWith("Do you want to ") || COMMAND_INTRO.matches(clean(it)) ||
        (agent?.lowercase() != "codex" && (CLAUDE_COMMAND_TITLE.matches(clean(it)) || CLAUDE_REQUEST_SOURCE.containsMatchIn(clean(it)))) }
    val questionHeader = beforeOptions.any {
        !PROMPT_ECHO.containsMatchIn(it.trimStart()) && clean(it).endsWith("?") && !CODE_LINE.containsMatchIn(clean(it))
    }
    val plainEcho = raw.firstOrNull { it.isNotBlank() }?.trimStart()?.startsWith("❯ ") == true
    val rejectEcho = dialogAt < 0 && !approvalHeader && !questionHeader &&
        (agent?.lowercase() == "claude" || (agent == null && plainEcho)) &&
        raw.any { PROMPT_ECHO.containsMatchIn(it.trimStart()) }


    // A numbered list is a menu only when the screen shows a real one: a highlight on one of its
    // rows (❯ › > ▶, or a filled radio), a question dialog's footer, or Vibe's card footer. A reply
    // that lists steps and ends with "?" is prose, not a question card (1.2.7, Bill: "choices like
    // first, second, then, but they weren't mutually exclusive options").
    // Claude Code 2.1.326 renders Next/Submit separately from the numbered inputs. Down from
    // the final checkbox focuses it; Enter activates only the focused button. The label in the
    // tab bar and the generic Enter hint do not identify that button.
    val checkboxCount = numbered.takeWhile { isCheckbox(it.label) }.size
    val actionButton = if (agent.equals("claude", true) && dialogAt >= 0 && checkboxCount >= 2 &&
        numbered.take(checkboxCount).map { it.keys.toIntOrNull() } == (1..checkboxCount).toList() &&
        numbered.drop(checkboxCount).let { it.isEmpty() || it.singleOrNull()?.label == "Chat about this" }) {
        val end = run[checkboxCount - 1].first
        val footer = lines.indexOfLast { row ->
            val hints = clean(row).split('·').map(String::trim)
            STANDALONE_CARD_HINT.matches(clean(row)) && "Enter to select" in hints && "Esc to cancel" in hints &&
                hints.any { it == "Tab/Arrow keys to navigate" || it == "↑/↓ to navigate" || it == "↑↓ to navigate" }
        }
        if (footer <= end) null else {
            val buttons = (end + 1 until footer).mapNotNull { i ->
                CLAUDE_CHECKBOX_BUTTON.matchEntire(lines[i])?.let { i to it }
            }
            buttons.singleOrNull()?.takeIf { (i, button) ->
                // A number after the button may only be Claude's optional Chat row. No unknown
                // intervening controls or second focus marker can authorize an action.
                (end + 1 until footer).all { j -> j == i || isChrome(lines[j]) ||
                    j < i && lines[j].startsWith("      ") && !INPUT_ROW_UNDER.containsMatchIn(lines[j]) ||
                    j > i && checkboxCount < run.size && j == run.last().first } &&
                    run.count { it.first in marked } + (if (button.groupValues[1].isNotEmpty()) 1 else 0) == 1
            }
        }
    } else null
    if (dialogAt >= 0 && run.none { it.first in marked } && actionButton == null) {
        return Prompt(question ?: "This agent is waiting for you.", emptyList(), region = raw.takeLast(16))
    }
    val vibeCard = lines.takeLast(6).any { VIBE_CARD_FOOTER.containsMatchIn(it) }
    val realMenu = run.any { it.first in marked } || actionButton != null || showsQuestionDialog(activeText) || vibeCard
    // A card is at the bottom of the screen: under its last option only a few lines (a description,
    // a footer, the card's frame). A card with more under it is an old one left in the scrollback
    // after it was answered (1.4.9, Bill: "Grok is stuck on another fake question box"): Grok's
    // "(●)" stays drawn there, so it looked like a live question while Grok was back at work.
    val below = run.lastOrNull()?.let { (at, _, _) ->
        lines.drop(maxOf(at, actionButton?.first ?: at) + 1)
    } ?: emptyList()
    val underCard = below.count { clean(it).any(Char::isLetterOrDigit) }
    // The agent's own activity ("● …", a spinner, a status line) or its input row under the options means
    // the agent went on after the card, however few lines that took (from Grok's review).
    // A box's own corner ("└────") is not activity: only a line with words counts.
    val wentOn = agentWentOn(below)
    val codex = agent.equals("codex", true)
    val beforeMenu = raw.take(firstOption.coerceAtLeast(0))
    val approvalRows = if (codex) beforeMenu.drop(beforeMenu.indexOfLast { CODEX_FOOTER.matches(it.trim()) } + 1)
        else beforeMenu
    val approvalStart = approvalRows.indexOfLast { row ->
        val body = clean(row)
        when (agent?.lowercase()) {
            "codex" -> CODEX_COMMAND_HEADING.matches(body) || CODEX_OTHER_APPROVAL.matches(body)
            "hermes" -> HERMES_COMMAND_HEADING.matches(body)
            else -> false
        }
    }
    val approvalIdentity = if (approvalStart < 0) null else java.security.MessageDigest.getInstance("SHA-256")
        .digest(approvalRows.drop(approvalStart).joinToString("\n").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    if (codex && !wentOn) {
        val activeFooter = below.lastOrNull { !isChrome(it) }?.trim()?.let(CODEX_FOOTER::matches) == true
        val heading = approvalStart >= 0 && CODEX_COMMAND_HEADING.matches(clean(approvalRows[approvalStart]))
        val commandRows = if (heading) approvalRows.drop(approvalStart) else emptyList()
        val commandParts = codexCommandParts(commandRows)
        // Inspect the raw rows: an ellipsis can be both standalone and the only sign of clipping.
        val hidden = commandRows.drop(1).any { HIDDEN_CODEX_COMMAND.containsMatchIn(clean(it)) }
        val explicitKeys = numbered.all { SHORTCUT_LABEL.containsMatchIn(it.label) || ESC_LABEL.containsMatchIn(it.label) }
        val approvalChoices = numbered.any {
            it.label.startsWith("Yes, proceed", ignoreCase = true) ||
                it.label.startsWith("No, and tell Codex what to do differently", ignoreCase = true)
        }
        val otherApproval = approvalStart >= 0 && !heading
        if (realMenu && ((heading && (!activeFooter || numbered.size < 2 || commandParts == null || !explicitKeys || hidden)) ||
                (activeFooter && approvalChoices && !otherApproval && !heading))) {
            return if (activeFooter || blocked) terminalCommandReview(commandParts?.command) else null
        }
        if (commandParts != null) parts = commandParts
    }
    if (numbered.size in 2..20 && realMenu && underCard <= MAX_UNDER_CARD && !wentOn && !rejectEcho) {
        if (agent.equals("hermes", true) && approvalIdentity == null &&
            numbered.any { it.label.equals("Allow once", true) } &&
            numbered.any { it.label.equals("Deny", true) }) return terminalCommandReview(null)
        // Vibe draws no marker on the highlighted row; a fresh card highlights the first one.
        val selected = if (actionButton?.second?.groupValues?.get(1)?.isNotEmpty() == true) CLAUDE_CHECKBOX_ACTION
            else run.firstOrNull { it.first in marked }?.second ?: if (vibeCard) "1" else null
        val multi = numbered.count { isCheckbox(it.label) } >= 2
        val checkboxAction = actionButton?.let { Choice(it.second.groupValues[2], CLAUDE_CHECKBOX_ACTION, false) }
        // Codex (by name, or by its "Press enter to confirm or esc to cancel" footer) answers an
        // option by the key in its label; other agents keep arrows and Enter (1.4.2, from review).
        // Muse Code (1.4.7) does the same ("Allow this stage once (y)", "... (p)", "Abort the entire
        // command (esc)"), known by name or by its "Change approval defaults in /permissions" line.
        val shortcuts = agent?.lowercase() in setOf("codex", "muse") ||
            lines.takeLast(4).any { CODEX_FOOTER.containsMatchIn(it) || MUSE_CARD_FOOTER.containsMatchIn(it) }
        return Prompt(question ?: "Choose an option", numbered, selected, escRejects = vibeCard, parts = parts, multi = multi,
            shortcuts = shortcuts, approvalIdentity = approvalIdentity, claudeCheckboxAction = checkboxAction)
    }

    val yn = Regex("""[(\[]\s*[yY](?:es)?\s*/\s*[nN]o?\s*[)\]]""")
    val ynIndex = lines.indexOfLast { yn.containsMatchIn(it) }
    if (ynIndex >= 0 && ynIndex >= lines.size - 3) {
        return Prompt(clean(lines[ynIndex]), listOf(Choice("Yes", "y", true), Choice("No", "n", true)))
    }

    // Grok's privacy dialog (from /privacy): "○  Opt in" / "●  Opt out", the filled circle is the
    // current choice and the cursor; ↑/↓ and Enter answer it.
    detectGrokPrivacyDialog(text)?.let { return it }

    // A menu without numbers, as Kiro draws its approval card ("❯ Yes, single permission" over
    // "  Trust, always allow in this session" and "  No (Tab to edit)"); arrows and Enter answer it.
    if (blocked) detectUnnumberedMenu(activeText)?.takeUnless { p ->
        rejectEcho && p.choices.any { Regex("""^\d{1,2}[.)]\s""").containsMatchIn(it.label) }
    }?.let { return it }

    // Buttons side by side (OpenCode's permission prompt, Vibe's folder trust) and options answered by a
    // letter (Grok's folder trust and plan review), 1.4.9.
    detectButtonRow(activeText)?.let { return it }
    detectLetterMenu(activeText, blocked)?.let { return it }

    // Grok's data-sharing banner, when nothing else is being asked: answered through /privacy.
    if (showsGrokBanner(text)) return GROK_PRIVACY_PROMPT

    // Looks like a question (herdr says it waits, or a question footer shows) but nothing above
    // understood it (1.4.0): the fallback card shows the bottom of the screen and generic keys.
    val footer = lines.takeLast(6).any { claudeFooterPattern.containsMatchIn(it) }
    // A line near the bottom that asks: a sentence ending in "?", not code ("val name: String?",
    // "// why?") and not inside a box (omp's code view) (1.4.2, from review).
    val askingLine = raw.withIndex().filter { it.value.isNotBlank() }.takeLast(6).lastOrNull { (i, l) ->
        val t = l.trimStart()
        val echoAt = (i downTo 0).firstOrNull { PROMPT_ECHO.containsMatchIn(raw[it].trimStart()) }
        var continuationColumn = Int.MAX_VALUE
        val echoLine = rejectEcho && echoAt != null && (echoAt..i).all { at ->
            val row = raw[at]
            val match = option.matchEntire(row)
            when {
                row.isBlank() -> false
                match != null -> { continuationColumn = match.groups[6]!!.range.first; true }
                else -> row.takeWhile { it.isWhitespace() }.length >= continuationColumn
            }
        }
        val askingText = if (rejectEcho && !echoLine) option.matchEntire(l)?.let { label(it) } ?: clean(l) else clean(l)
        !echoLine && t.firstOrNull()?.let { it in "│┃|╭╰" } != true &&
            ASKING_SENTENCE.matches(askingText) && !CODE_LINE.containsMatchIn(askingText)
    }
    val asks = askingLine != null
    // An agent's error (a failed compaction, a rate limit) is not a question, whatever herdr says:
    // no card; the pane shows it as an error banner (1.4.2). A sentence that asks still gets its card.
    if (!asks && detectAgentError(text) != null) return null
    // herdr's "blocked" alone is not a question (1.4.2: opencode's plugin kept a pane blocked after
    // a failed compaction, even on a fresh session, and the app showed an empty card). The fallback
    // card needs something question-like on screen too: a question footer, or a sentence near the
    // bottom that asks. After a dialog has scrolled into history, herdr can still report it as
    // blocked; don't let that status turn subsequent prose into a new card. A current footer or
    // other recognized prompt controls still work without waiting for the status to change.
    if ((footer && !rejectEcho) || (blocked && asks && !pastDialog && !openCode)) {
        val region = raw.dropLastWhile { it.isBlank() }.takeLast(16).map { clean(it) }.dropWhile { it.isEmpty() }
        val fallbackQuestion = if (rejectEcho) askingLine?.value?.let(clean) else question
        return Prompt(fallbackQuestion ?: "This agent is waiting for you.", emptyList(), region = region.takeIf { r -> r.any { it.isNotBlank() } } ?: listOf(UNREAD_SCREEN))
    }
    return null
}

/** The choice keys of Grok's data-sharing question; [AppViewModel] answers them through /privacy. */
const val GROK_PRIVACY_OUT = "grok-privacy:out"
const val GROK_PRIVACY_IN = "grok-privacy:in"
val GROK_PRIVACY_PROMPT = Prompt(
    "Help improve Grok: share coding data with SpaceXAI?",
    listOf(Choice("Opt out (keep it off)", GROK_PRIVACY_OUT, false), Choice("Opt in", GROK_PRIVACY_IN, false)),
)

/** Grok's "Help improve Grok ... [Opt out] [Opt in]" banner is on screen (2026-11-13, Grok 4.7). */
fun showsGrokBanner(text: String): Boolean {
    val tail = text.lines().takeLast(40).joinToString("\n")
    return tail.contains("Help improve Grok") && Regex("""\[Opt out]\s*\[Opt in]""").containsMatchIn(tail)
}

/**
 * Grok's /privacy dialog: a box headed "Settings › Coding data, retention, and training" with rows
 * "○  Opt in" and "●  Opt out" (the filled circle is the current choice and the cursor) and the
 * footer "↑/↓ nav | Enter select | ... | Esc cancel".
 */
fun detectGrokPrivacyDialog(text: String): Prompt? {
    val lines = text.lines().takeLast(40)
    if (lines.none { it.contains("Coding data, retention, and training") }) return null
    val row = Regex("""^[\s│┃|]*([○●◯◉])\s+(Opt in|Opt out)\b""")
    val rows = lines.mapNotNull { row.find(it) }
    if (rows.size != 2) return null
    val choices = rows.mapIndexed { i, m -> Choice(m.groupValues[2], (i + 1).toString(), false) }
    val selected = rows.indexOfFirst { it.groupValues[1] in setOf("●", "◉") }.takeIf { it >= 0 }?.let { (it + 1).toString() }
    return Prompt("Grok: share coding data with SpaceXAI (Coding data, retention, and training)?", choices, selected)
}

/**
 * What Grok's input box holds, from its bottom box ("│ ❯ text │"); null when no input line is
 * found (a dialog is open, say). Used to make sure it is empty before typing /privacy: on
 * 2026-11-13 the app's own usage check had left "/usage" there and "/privacy" went to the model
 * as "/usage/privacy".
 */
fun grokInputText(text: String): String? {
    val m = text.lines().takeLast(12).asReversed().firstNotNullOfOrNull { Regex("""^\s*[│┃|]\s*❯ ?(.*?)\s*[│┃|]?\s*$""").find(it) }
    return m?.groupValues?.get(1)?.trim()
}

/** Links and sign-in codes on an agent's screen, for one-tap Open and Copy (a /login flow, say). */
data class ScreenLinks(val urls: List<String>, val codes: List<String>)

fun screenLinks(text: String): ScreenLinks {
    val tail = text.lines().takeLast(60).joinToString("\n")
    val urls = Regex("""https?://[A-Za-z0-9.-]+(?::\d+)?(?:/[^\s│┃|"'<>)\]]*)?""").findAll(tail)
        .map { it.value.trimEnd('.', ',', ';', ':') }.distinct().toList().takeLast(3)
    // Device codes such as "ABCD-1234" (GitHub, Microsoft) or "ABCD-EFGH-IJKL".
    val codes = Regex("""(?<![A-Za-z0-9-])[A-Z0-9]{4}(?:-[A-Z0-9]{4}){1,2}(?![A-Za-z0-9-])""").findAll(tail)
        .map { it.value }.filter { c -> c.any { it.isDigit() } || c.any { it.isLetter() } }.distinct().toList().takeLast(2)
    return ScreenLinks(urls, codes)
}

/**
 * The app's name for an agent kind. herdr versions and integrations name some agents differently
 * (Antigravity's integration is "antigravity-cli"), and every per-agent rule in the app (footer
 * models, usage checks, commands, log readers) keys on these names.
 */
fun canonicalAgent(name: String): String {
    val n = name.trim().lowercase()
    // Muse Code's launcher execs "muse-bin-<version>", the program name herdr-core reports (1.4.5,
    // from Grok's review); a path to either counts too.
    if (Regex("""(^|/)muse(-bin-[\w.+-]+)?$""").containsMatchIn(n)) return "muse"
    return canonicalKind(n)
}

private fun canonicalKind(n: String): String = when (n) {
    "agy", "antigravity", "antigravity-cli", "antigravity_cli", "gemini-antigravity" -> "agy"
    "claude-code", "claude_code", "claudecode" -> "claude"
    "codex-cli", "openai-codex" -> "codex"
    "grok-cli", "grok-build" -> "grok"
    "copilot-cli", "github-copilot", "gh-copilot" -> "copilot"
    "oh-my-pi", "ohmypi" -> "omp"
    "pi-agent", "pi-coding-agent" -> "pi"
    "kiro-cli", "kiro-cli-chat" -> "kiro"
    "vibe-rs", "mistral-vibe", "vibe-acp" -> "vibe"
    "muse-code", "meta-muse", "muse-cli" -> "muse"
    else -> n
}

/**
 * Agents whose own key moves a running command to the background, and that key. Checked in each
 * program on 2026-11-13: Claude Code (task:background, Ctrl+B), OpenCode (session.background, "Background
 * blocking session tools", Ctrl+B), Grok ("Ctrl+B sends the running foreground command to the
 * background") and Antigravity ("Press ctrl+b to send a long task to the background"). Not offered
 * elsewhere: in pi and Oh My Pi Ctrl+B moves the cursor left, in Hermes it is push-to-talk, and
 * Codex, Copilot, Kiro and Vibe have no such key. Muse Code 1.4.20 has it ("ctrl + b to send to background", 1.4.5).
 */
object Backgrounding {
    // Muse Code 1.4.20's /help: "ctrl + b to send to background" (1.4.5).
    private val agents = setOf("claude", "opencode", "grok", "agy", "muse")
    const val KEY = "ctrl+b"
    fun supported(agent: String?): Boolean = agent?.lowercase() in agents
}

/** The program that runs an agent kind, where it differs from the kind's name. */
fun agentExecutable(kind: String): String = when (kind) {
    "kiro" -> "kiro-cli"
    else -> kind
}

/** The agent kinds `herdr agent start --kind` accepts among those the app can start. */
val HERDR_KINDS = setOf("claude", "codex", "opencode", "omp", "pi", "hermes", "grok", "copilot", "agy", "gemini", "kiro", "muse")

/**
 * An agent herdr doesn't recognize, known by the title its program gives the terminal.  Mistral
 * Vibe has no herdr integration and isn't detected, but it titles its terminal "Vibe".
 */
fun titleAgent(title: String): String? =
    if (Regex("""^vibe(\b|$)""", RegexOption.IGNORE_CASE).containsMatchIn(title.trim())) "vibe" else null

/** The agents the app knows by name; any other program in a herdr-core terminal is a plain shell. */
private val KNOWN_AGENTS = setOf("claude", "codex", "pi", "omp", "grok", "agy", "copilot", "hermes", "opencode", "gemini", "cursor", "amp", "kiro", "vibe", "muse")

/**
 * herdr-core's terminal list (`hc ls --json`) as panes. Each terminal is its own tab; there are no
 * workspaces. A program that reports its state (OSC 7501) gives the status; one that doesn't shows
 * as unknown, and its "agent" is the program's name.
 */
fun corePanes(json: String): List<Pane> {
    val text = json.trim()
    val o = runCatching { JSONObject(text) }.getOrElse { JSONObject(text.lines().firstOrNull { it.trimStart().startsWith("{") } ?: "{}") }
    val arr = o.optJSONArray("terminals") ?: org.json.JSONArray()
    val list = (0 until arr.length()).mapNotNull { i ->
        val t = arr.optJSONObject(i) ?: return@mapNotNull null
        if (t.optString("state") == "finished") return@mapNotNull null
        val id = t.optString("address").ifBlank { return@mapNotNull null }
        val program = t.optString("agent")
        val agent = canonicalAgent(program).takeIf { it in KNOWN_AGENTS }
        Pane(
            id = id,
            tabId = id,
            workspaceId = "core",
            agent = agent,
            status = when (t.optString("status")) {
                "blocked" -> Status.NEEDS_YOU
                "working" -> Status.WORKING
                "done" -> Status.DONE
                "idle" -> Status.IDLE
                "error" -> Status.NEEDS_YOU
                else -> Status.UNKNOWN
            },
            title = t.optString("title").ifBlank { if (agent == null) program else "" },
            cwd = t.optString("cwd"),
            tabLabel = t.optString("name"),
        )
    }
    val order = listOf(Status.NEEDS_YOU, Status.WORKING, Status.DONE, Status.IDLE, Status.UNKNOWN)
    return list.sortedWith(compareBy({ order.indexOf(it.status) }, { it.id }))
}

/**
 * Finds which herdr a computer has, without starting anything: the TUI's `herdr status server`
 * says whether its server runs, and herdr-core counts as running when its socket and process are
 * both there (`hc server status` would start a core, so it isn't used).
 */
suspend fun detectBackend(ssh: HerdrSsh, herdrPath: String): Detected {
    val h = programPath(herdrPath.trim().ifBlank { "herdr" })
    val script = """
h=${'$'}(command -v $h 2>/dev/null || command -v herdr 2>/dev/null || { [ -x "${'$'}HOME/.local/bin/herdr" ] && echo "${'$'}HOME/.local/bin/herdr"; })
c=${'$'}(command -v hc 2>/dev/null || { [ -x "${'$'}HOME/.local/bin/hc" ] && echo "${'$'}HOME/.local/bin/hc"; })
tr=0; [ -n "${'$'}h" ] && "${'$'}h" status server 2>/dev/null | grep -q "status: running" && tr=1
cr=0; [ -S "${'$'}HOME/.local/state/herdr-core/default/default.sock" ] && pgrep -u "${'$'}(id -u)" -f herdr-core >/dev/null 2>&1 && cr=1
echo "@@detect ${'$'}{h:+1} ${'$'}tr ${'$'}{c:+1} ${'$'}cr ${'$'}c"
""".trimIndent()
    val out = ssh.exec(script).out.lines().firstOrNull { it.startsWith("@@detect") } ?: return Detected(true, false, false, false, null)
    return parseDetect(out)
}

fun parseDetect(line: String): Detected {
    // "@@detect <1 or empty> <0|1> <1 or empty> <0|1> <hc path>"; empty fields collapse, so read by position.
    val f = line.removePrefix("@@detect").split(' ')
    fun at(i: Int) = f.getOrNull(i).orEmpty()
    return Detected(at(1) == "1", at(2) == "1", at(3) == "1", at(4) == "1", at(5).ifBlank { null })
}

/**
 * The status a pane shows once what runs under it is known: an agent herdr calls idle, done or
 * unknown (Vibe) while its subagents, background tasks or shells still run is working. Used for
 * the open server and, since 1.2.8, every watched one (Bill: "showing an agent as idle when it has
 * active shells running", the Claude pane on another server).
 */
fun withBusy(list: List<Pane>, busy: Map<String, Int>): List<Pane> = list.map { p ->
    if ((p.status == Status.IDLE || p.status == Status.DONE || p.status == Status.UNKNOWN) && (busy[p.id] ?: 0) > 0) p.copy(status = Status.WORKING, backgroundBusy = true) else p
}
