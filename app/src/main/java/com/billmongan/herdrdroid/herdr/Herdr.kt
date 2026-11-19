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
) {
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
fun stripAnsi(s: String): String = ANSI.replace(s, "")

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
        ssh.execStream("$PYZ __follow__ ${shq(agent)} ${shq(pane.cwd)} ${shq(cursor)} $seconds", z(script)) { line ->
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
        val askable = list.filter { it.agent == "claude" && it.status != Status.NEEDS_YOU }
        val marked = if (askable.isEmpty()) list else {
            val tails = runCatching { previews(askable.map { it.id }) }.getOrDefault(emptyMap())
            list.map { p ->
                if (p in askable && tails[p.id]?.let { showsQuestionDialog(it.tail) } == true) {
                    com.billmongan.herdrdroid.diag.DiagLog.log("prompt", "${p.id}: a question dialog is open while herdr says ${p.status.name.lowercase()}; treating it as needs you")
                    p.copy(status = Status.NEEDS_YOU)
                } else p
            }
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
        override suspend fun pane(id: String, connection: Any) = cardPane(id, connection)
        override suspend fun viewport(id: String, connection: Any) =
            runNow(cmd("pane", "read", id, "--source", "visible", "--format", "ansi"), 5, connection)
        override suspend fun hasWork(pane: Pane, connection: Any): Boolean {
            val arg = org.json.JSONArray(listOf(listOf(pane.id, pane.agent.orEmpty(), pane.cwd, pane.sessionId.orEmpty()))).toString()
            val r = ssh.execInput("$PYZ __workcounts__ ${shq(arg)}", z(script), 8, connection)
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
    }

    /** No tab list, status enrichment, bulk lock, or reconnect during a card tap. */
    internal suspend fun cardPane(id: String, connection: Any): Pane? {
        if (core) return corePanes(runNow(hcCmd("ls", "--json"), 5, connection)).singleOrNull { it.id == id }
        val p = JSONObject(runNow(cmd("pane", "get", id), 5, connection)).getJSONObject("result").getJSONObject("pane")
        return tuiPane(p).takeIf { it.id == id && p.optJSONObject("scroll")?.opt("offset_from_bottom") == 0 }
    }

    /** Only the live viewport, with no fallback into old scrollback. */
    internal suspend fun readCardViewport(id: String, connection: Any, timeoutSeconds: Long = 5): String = stripAnsi(
        if (core) runNow(hcCmd("read", id, "--lines", "80"), timeoutSeconds, connection)
        else runNow(cmd("pane", "read", id, "--source", "visible"), timeoutSeconds, connection)
    )

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

    suspend fun readStyled(paneId: String, lines: Int = 40): String =
        if (core) read(paneId, lines)
        else runCatching { runNow(recentSnapshot(paneId, lines), timeoutSeconds = 5) }
            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; read(paneId, lines) }

    /**
     * Only what the pane shows now (`--source visible`), plain (1.4.8): the fallback when the usual
     * read of its recent output fails or comes back empty, so a waiting agent's card can still be read.
     */
    suspend fun readVisible(paneId: String): String = readVisible(paneId, preserveGlyphs = false)

    internal suspend fun readVisible(paneId: String, preserveGlyphs: Boolean): String =
        if (core) read(paneId, 60, preserveGlyphs) else screenText(
            runNow(cmd("pane", "read", paneId, "--source", "visible"), timeoutSeconds = 5), preserveGlyphs,
        )

    private fun screenText(text: String, preserveGlyphs: Boolean) = if (preserveGlyphs) stripAnsi(text) else forPhone(text)

    suspend fun read(paneId: String, lines: Int = 200, preserveGlyphs: Boolean = false): String =
        if (core) screenText(runNow(hcCmd("read", paneId, "--lines", lines.toString()), timeoutSeconds = 5), preserveGlyphs)
        else try {
            screenText(runNow(recentSnapshot(paneId, lines), timeoutSeconds = 5), preserveGlyphs)
        } catch (e: HerdrError) {
            if (e.exit == -2) throw e
            screenText(runNow(cmd("pane", "read", paneId, "--source", "visible", "--lines", lines.toString()), timeoutSeconds = 5), preserveGlyphs)
        }

    /**
     * Waits until any agent pane in [panes] changes state, or [timeoutMs] passes (1.4.1, "fewer
     * polls"): one command that runs `herdr agent wait <pane> --until <each other state>` for every
     * agent pane at once, prints the first that changed and stops the rest. It runs on the send
     * lane, so it never holds up reads or answers. Returns the pane that changed, or null on a timeout,
     * or null when it can't wait here (herdr-core, no agent panes, or an error), so the caller polls.
     */
    suspend fun waitForChange(panes: List<Pane>, timeoutMs: Long): String? {
        if (core) return null
        val agents = panes.filter { it.agent != null }
        if (agents.isEmpty()) return null
        val names = mapOf(Status.IDLE to "idle", Status.WORKING to "working", Status.NEEDS_YOU to "blocked", Status.DONE to "done")
        val body = agents.joinToString("") { p ->
            val until = names.filterKeys { it != p.status }.values.joinToString(" ") { "--until $it" }
            "( $herdr agent wait ${shq(p.id)} $until --timeout $timeoutMs >/dev/null 2>&1 && echo ${shq("@@chg " + p.id)} ) & pids=\"\$pids \$!\"; "
        }
        // The first wait's own status is the script's (1.4.1, from a second review: a trailing
        // "true" made a failed wait look like a timeout).
        val script = "pids=; ${body}wait -n 2>/dev/null; rc=\$?; kill \$pids 2>/dev/null; exit \$rc"
        val r = runCatching { ssh.execNow("bash -c " + shq(script), timeoutSeconds = timeoutMs / 1000 + 20) }.getOrNull() ?: return null
        // A change is the pane that changed. Anything else (a timeout, a wait that failed, herdr
        // without agent wait) is null, and the caller polls with its usual gap instead of starting
        // another wait straight away (1.4.1, from two code reviews).
        return r.out.lines().firstOrNull { it.startsWith("@@chg ") }?.removePrefix("@@chg ")?.trim()
    }

    /** Each agent's program version here (1.4.0): agent kind to version, for the parsers and the debug bundle. */
    /**
     * The agents' versions (1.4.0). On the send lane, so no read waits for it, and every agent's
     * --version at once under one 10-second cap (1.4.1, from a code review: one at a time on the
     * connection's lock, pane reads waited up to a minute).
     */
    suspend fun agentVersions(): Map<String, String> = runCatching { AgentVersions.parse(ssh.execNow(AgentVersions.script(), timeoutSeconds = 10).out) }.getOrDefault(emptyMap())

    /** The last few lines of every pane, fetched in one round trip. */
    suspend fun previews(ids: List<String>): Map<String, Peek> {
        if (ids.isEmpty()) return emptyMap()
        val script = ids.joinToString("; ") { id ->
            "echo ${shq("@@herdrdroid@@$id")}; ${if (core) hcCmd("read", id, "--lines", "12") else recentSnapshot(id, 12)} 2>/dev/null"
        }
        val out = forPhone(ssh.exec(script).out)
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
        for (line in out.lines()) {
            if (line.startsWith("@@herdrdroid@@")) {
                flush()
                current = line.removePrefix("@@herdrdroid@@")
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
    ) {
        require(allowTerminalFallback || (pane.agent != null && pane.status in setOf(Status.IDLE, Status.DONE)))
        require(permit == null || (!allowTerminalFallback && expectedConnection != null))
        lastSendRoute = when {
            core -> "hc send"
            pane.agent != null && pane.status != Status.NEEDS_YOU -> "herdr agent prompt"
            else -> "herdr pane send-text + enter"
        }
        if (core) {
            // hc send pastes and presses Enter; it refuses a terminal waiting on a question
            // unless forced, and a message sent then is meant as the answer.
            if (pane.status == Status.NEEDS_YOU) runNow(hcCmd("send", "--force", pane.id, text)) else runNow(hcCmd("send", pane.id, text), expectedConnection = expectedConnection, permit = permit)
            return
        }
        if (pane.agent != null && pane.status != Status.NEEDS_YOU) {
            try {
                runNow(cmd("agent", "prompt", pane.id, text), expectedConnection = expectedConnection, permit = permit)
                return
            } catch (e: HerdrError) {
                if (!allowTerminalFallback) throw e
                // Fall through to typing it in, which works in any pane.
                lastSendRoute = "agent prompt refused; typed with send-text + enter"
            }
        }
        runNow(cmd("pane", "send-text", pane.id, text))
        runNow(cmd("pane", "send-keys", pane.id, "enter"))
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

    private fun usageSnapshotCommand(paneId: String, agent: String) =
        "$PY -c ${shq(USAGE_OVERLAY_SNAPSHOT)} $herdr ${shq(paneId)} ${shq(agent)}"

    internal suspend fun readUsageOverlay(paneId: String, agent: String): UsageOverlay? {
        if (core || (agent != "claude" && agent != "kiro")) return null
        val json = runNow(usageSnapshotCommand(paneId, agent), timeoutSeconds = 5).trim()
        if (json.isEmpty()) return null
        val objectJson = JSONObject(json)
        val visible = objectJson.getString("visible")
        if (visible.length > 20_000 || dismissibleUsageFrame(agent, visible) == null) return null
        return UsageOverlay(objectJson.getString("occupant"), visible)
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
     * Reads what the agent in [pane] has added to its own session log since [cursor], by sending
     * the transcript reader on standard input to the computer's python3. Nothing is installed or
     * written there. Null when the pane has no agent.
     */
    suspend fun transcript(pane: Pane, cursor: String, script: ByteArray): TranscriptRead? {
        val agent = pane.agent ?: return null
        val r = ssh.execInput("$PYZ ${shq(agent)} ${shq(pane.cwd)} ${shq(cursor)}", z(script))
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
    /** An incomplete approval preview is reviewable only in the terminal. */
    val terminalReview: Boolean = false,
    /** Exact pre-layout approval material. Never rendered or used as history text. */
    internal val approvalIdentity: String? = null,
) {
    /**
     * Never an empty card (1.4.2): a prompt with no choices always has something to show, the
     * screen region or, when the screen couldn't be read, a note saying so, with the generic keys.
     */
    fun forCard(): Prompt = if (choices.isNotEmpty() || region.orEmpty().any { it.isNotBlank() }) this else copy(region = listOf(UNREAD_SCREEN))

    /** [parts], or the same layout read from the question's text. */
    fun shownParts(): QuestionParts = parts ?: questionParts(listOf(QLine(question)))
}

/** What a card with no options shows when the app couldn't read the screen (1.4.2). */
const val UNREAD_SCREEN = "(The app couldn't read this pane's screen yet. Tap Show terminal, or answer with the keys below.)"

/** Dispatch identity, unlike history deduplication, must not discard punctuation or command text. */
internal fun cardAnswerStillFits(shown: Prompt, fresh: Prompt?): Boolean =
    !shown.terminalReview && fresh != null && !fresh.terminalReview &&
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
                 val bar: Boolean = false)

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
/** Vibe's "Permission for the file_system.bash tool (…)": the command is the next line. */
private val TOOL_PERMISSION = Regex("""^Permission for the \S+ tool(\s*\(.*\))?$""")
private const val MARKS = "›❯◆◈●○•▸➤▶↓→│┃╭╰╮╯─ "

/**
 * Claude's multi-question tab bar, "←  ☐ Close 537  ☒ Stop hook  ✔ Submit  →", as steps; the
 * current one is the first unanswered question (Submit once all are answered). The second value is
 * any question text that followed the bar on the same line.
 */
fun parseTabBar(line: String): Pair<List<QTab>, String>? {
    val t = line.trim()
    val items = TAB_ITEM.findAll(t).toList()
    if (items.size < 2) return null
    val titles = items.map { it.groupValues[2].trim() }
    if (!t.startsWith("←") && titles.none { it.equals("Submit", true) }) return null
    val rest = if (t.contains("→")) t.substringAfterLast("→").trim() else ""
    var tabs = items.mapIndexed { i, m ->
        val sub = titles[i].equals("Submit", true)
        QTab(titles[i], done = !sub && m.groupValues[1] != "☐", current = false, submit = sub)
    }.filter { it.title.isNotEmpty() }
    val cur = tabs.indexOfFirst { !it.done && !it.submit }.takeIf { it >= 0 } ?: tabs.indexOfFirst { it.submit }
    if (cur >= 0) tabs = tabs.mapIndexed { i, q -> if (i == cur) q.copy(current = true) else q }
    return tabs to rest
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
    class Para(var text: String, val cmd: Boolean, val inPlace: Boolean = false)
    val paras = mutableListOf<Para>()
    var cmdNext = false
    // The current command block began with "$ ": its "│ ..." lines are the rest of it (1.4.8, from Grok's
    // review: Muse draws a script's lines under "$ python3 - <<'PY'" that way).
    var dollarBlock = false
    for (l in body) {
        val answerRow = l.text.trim().startsWith("→")
        var t = l.text.trim().trimStart { it in MARKS }.trimEnd { it in "│┃╮╯─ " }.trim()
        if (t.isEmpty() || !t.any(Char::isLetterOrDigit)) continue
        // Claude's review step: "● Close 537" over "→ Close it" reads "Close 537: Close it".
        if (answerRow && paras.lastOrNull()?.cmd == false) { paras.last().text += ": " + t; continue }
        if (COMMAND_INTRO.matches(t)) { cmdNext = true; continue }
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
        if (last == null || last.cmd || l.gapBefore || afterLabel || bullet || LABEL_LINE.containsMatchIn(t) || last.text.endsWith("?") || last.text.endsWith(":"))
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
 */
private val COMPOSER_HINTS: Map<String, Set<String>> = mapOf(
    "claude" to setOf(
        "Press up to edit queued messages",
        "Press up to edit queued messages, Enter to send them immediately",
        "Press up to select a queued message to edit, or Enter to send them now",
        "Press up to select a queued message, then Enter to edit it",
    ),
    "codex" to setOf("Ask Codex to do anything"),
    "kiro" to setOf("ask a question or describe a task ↵"),
)

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
fun inputLineOf(screen: String, agent: String? = null): String? {
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
    val rawRows = screen.lines().map { it.replace("\r", "") }.takeLast(14)
    val rows = rawRows.map { it.replace(ANSI_SEQ, "") }
    val rule = Regex("""^[\s\u00A0╭╰├└┌]*[─━═╌┄-]{4,}""")
    fun isRule(i: Int) = i in rows.indices && rule.containsMatchIn(rows[i])
    val candidates = rows.indices.filter { lead.containsMatchIn(rows[it]) && !notInput.containsMatchIn(rows[it]) }
    val framed = Regex("""^[\s\u00A0]*[│┃|].*[│┃|][\s\u00A0]*$""")
    val i = candidates.lastOrNull { isRule(it - 1) && isRule(it + 1) }
        ?: candidates.lastOrNull { framed.matches(rows[it]) }
        ?: candidates.lastOrNull { c -> rows.drop(c + 1).count { it.isNotBlank() && !rule.containsMatchIn(it) } <= 3 }
        ?: return null
    return rawRows[i] to rows[i].replace(lead, "").trimEnd(' ', '\u00A0', '│', '┃', '|').trim()
}

/** One character of a styled row and how it is drawn (1.4.6). */
private data class Styled(val ch: Char, val faint: Boolean, val italic: Boolean, val grey: Boolean)

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
                        0 -> { faint = false; italic = false; grey = false }
                        2 -> faint = true
                        22 -> faint = false
                        3 -> italic = true
                        23 -> italic = false
                        39 -> grey = false
                        in 30..37 -> grey = false
                        90 -> grey = true
                        in 91..97 -> grey = false
                        38 -> {
                            if (ps.getOrNull(k + 1) == 2 && k + 4 < ps.size) { grey = greyRgb(ps[k + 2], ps[k + 3], ps[k + 4]); k += 4 }
                            else if (ps.getOrNull(k + 1) == 5 && k + 2 < ps.size) { grey = grey256(ps[k + 2]); k += 2 }
                        }
                        48 -> k += if (ps.getOrNull(k + 1) == 2) 4 else if (ps.getOrNull(k + 1) == 5) 2 else 0
                        else -> {}
                    }
                    k++
                }
            }
            i = m.range.last + 1
            continue
        }
        out += Styled(row[i], faint, italic, grey)
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
 * styles is never a placeholder by this test.
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
    return seen.isNotEmpty() && seen.all { it.faint || it.grey || (hermes && it.italic) }
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

/** A checkbox row ("[ ] Tests", "[x] Docs", "☐ Lint", "◼ Build"), 1.4.0. */
private val CHECKBOX = Regex("""^(\[[ xX✓✔]\]|[☐☑☒◻◼□■])\s+""")
fun isCheckbox(label: String): Boolean = CHECKBOX.containsMatchIn(label.trim())

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
private val STANDALONE_CARD_HINT = Regex(
    """^(?:(?:ctrl|shift|alt|cmd)[+-][a-z0-9]+|esc|enter|tab(?:/arrow keys)?|space|arrow keys|[↑↓←→/]+)\s+(?:to\s+)?(?:cancel|reject|select|navigate|submit|confirm|toggle|edit(?: in editor)?)(?:\s*[·|]\s*(?:(?:ctrl|shift|alt|cmd)[+-][a-z0-9]+|esc|enter|tab(?:/arrow keys)?|space|arrow keys|[↑↓←→/]+)\s+(?:to\s+)?(?:cancel|reject|select|navigate|submit|confirm|toggle|edit(?: in editor)?))*$""",
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

/**
 * The permission mode an agent shows in its footer: "plan", "accept edits", "auto" or "bypass".
 * Claude in its default mode shows none, which reads as "default"; other agents return null.
 */
fun agentMode(agent: String?, screen: String): String? {
    val tail = screen.lines().filter { it.isNotBlank() }.takeLast(8).joinToString("\n")
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
fun detectAgentError(text: String): String? {
    val all = text.lines().filter { it.isNotBlank() }.takeLast(12)
    val found = linkedSetOf<String>()
    var carry = false
    for (raw in all) {
        // A row inside a box (a code view, a table) is the agent's content, never its error (1.4.2, from review).
        if (raw.trimStart().firstOrNull()?.let { it in "│┃|╭╰┌└├" } == true) { carry = false; continue }
        val l = raw.trim().trim('│', '┃', '|', ' ').trim()
        val mine = YOUR_LINE.containsMatchIn(l)
        val marked = !mine && (ERROR_MARK.containsMatchIn(l) || AGENT_OWN_ERROR.containsMatchIn(l) || ERROR_LEAD.containsMatchIn(l))
        var added = false
        if (marked || (carry && !mine)) for ((pattern, summary) in AGENT_ERRORS) if (pattern.containsMatchIn(l)) { found += summary; added = true }
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
    return found.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

/** A line that opens with an error itself: "Rate limit exceeded", "429 Too Many Requests", "503 Service Unavailable" (1.4.2, from review). */
private val ERROR_LEAD = Regex("""^\W{0,3}(Rate limit (exceeded|reached)|429 Too Many Requests|Too Many Requests|5\d\d (Internal Server Error|Service Unavailable|Bad Gateway|Gateway Time-?out)|402 Payment Required|Usage limit (reached|exceeded)|Out of credits|Insufficient (credits|balance)|Connection (lost|reset|refused|closed))\b""", RegexOption.IGNORE_CASE)

/** Your own lines: the echo of what you typed, or the input box. */
private val YOUR_LINE = Regex("""^(>|❯|›|»)(\s|$)""")
private val ERROR_MARK = Regex("""^(✗|⚠|×|✖|❌|■|⎿|‼|!)|^\W{0,3}(Error|ERROR|Fatal|FATAL|fatal|Failed|FAILED|error)\b|\b[Ee]rror:|\((HTTP|status) [45]\d\d\)|\bstatus [45]\d\d\b""")
/** Messages the agents print on their own line (from their programs' strings, 2026-11-14). */
private val AGENT_OWN_ERROR = Regex("""^\W{0,3}(You are not logged in|You're not logged in|Not logged in|Please (log|sign) in|Your credit balance is too low|Quota exceeded|Grok is temporarily overloaded|prompt is too long:|Context window exceeded|Compaction failed|Rate limited|Too Many Requests|Service Unavailable|Internal Server Error|Bad Gateway|Token refresh failed|Invalid .{0,40}credentials|Billing error|You've hit your usage limit|Claude usage limit reached|A network error occurred)""", RegexOption.IGNORE_CASE)

private val AGENT_ERRORS = listOf(
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

private val OPENCODE_WEB_SEARCH_FOOTER =
    Regex("""^↑/?↓\s+select\s+enter\s+submit\s+esc\s+dismiss$""", RegexOption.IGNORE_CASE)
private val OPENCODE_WEB_SEARCH_OPTION = Regex("""^(?:[❯›>]\s*)?([1-3])[.]\s+(.+)$""")

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
    if ((agent == null || agent.equals("claude", true)) && claudeUsageOpen(text)) return null
    if (agent?.lowercase() in setOf("opencode", "opencode-college")) {
        detectOpenCodeWebSearch(text)?.let { return it }
    }
    if (agent.equals("hermes", true)) {
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
    @Suppress("NAME_SHADOWING") val blocked = blocked || showsQuestionDialog(text) ||
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
                if (l.isBlank() || c.isEmpty()) {
                    if (got.isNotEmpty() && !gap) { gap = true; gaps++; if (gaps > 2) break; got[0] = got[0].copy(gapBefore = true) }
                    i--; continue
                }
                if (l.trimStart().startsWith("╭") || l.trimStart().startsWith("┌") || c.matches(Regex("""^[─━═\-_\s]+$"""))) break
                // The agent's activity always ends the question; an echoed prompt does after a blank
                // line (1.2.7: before, Codex's card took in the conversation above it).
                // A status line (Grok's "… 7.5s 26s ⇣87.0k [↓][stop]", "esc to interrupt") ends it too,
                // wherever its marker is (1.2.7, Bill: "make sure you parse it correctly").
                if (got.isNotEmpty() && (ACTIVITY.containsMatchIn(c) || STATUS_LINE.containsMatchIn(c) ||
                        (gap && PROMPT_ECHO.containsMatchIn(c)))) break
                gap = false
                // A box inside the card's frame holds the command: Grok's "┃  │ grok usage", Copilot's
                // "│ │ mkdir -p sub" (1.2.9: Copilot's too, so it shows as a command).
                val tl = l.trimStart()
                val isCmd = (tl.startsWith("┃") || tl.startsWith("│")) && tl.drop(1).trimStart().startsWith("│")
                // Grok's "← → narrow scope" hint sits between the command and its options.
                // A dashed line with no words (the edges of Claude's diff box) separates paragraphs (1.4.9).
                if (!c.any(Char::isLetterOrDigit) && got.isNotEmpty()) got[0] = got[0].copy(gapBefore = true)
                // Grok's question card starts its question with a "?" icon (1.4.9).
                if (c.any(Char::isLetterOrDigit) && !c.contains("narrow scope")) got.add(0, QLine(c.trimStart('›', '❯', ' ').replace(Regex("""^\?\s+"""), ""), isCmd, bar = !isCmd && tl.startsWith("│")))
                i--
            }
            got.takeIf { it.isNotEmpty() }
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
    val parts = above?.let(::questionParts)
    val beforeOptions = lines.take(run.firstOrNull()?.first ?: lines.size)
    val approvalHeader = beforeOptions.any { clean(it).startsWith("Do you want to ") || COMMAND_INTRO.matches(clean(it)) }
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
    if (dialogAt >= 0 && run.none { it.first in marked }) {
        return Prompt(question ?: "This agent is waiting for you.", emptyList(), region = raw.takeLast(16))
    }
    val vibeCard = lines.takeLast(6).any { VIBE_CARD_FOOTER.containsMatchIn(it) }
    val realMenu = run.any { it.first in marked } || showsQuestionDialog(activeText) || vibeCard
    // A card is at the bottom of the screen: under its last option only a few lines (a description,
    // a footer, the card's frame). A card with more under it is an old one left in the scrollback
    // after it was answered (1.4.9, Bill: "Grok is stuck on another fake question box"): Grok's
    // "(●)" stays drawn there, so it looked like a live question while Grok was back at work.
    val below = run.lastOrNull()?.let { (at, _, _) -> lines.drop(at + 1) } ?: emptyList()
    val underCard = below.count { clean(it).any(Char::isLetterOrDigit) }
    // The agent's own activity ("● …", a spinner, a status line) or its input row under the options means
    // the agent went on after the card, however few lines that took (from Grok's review).
    // A box's own corner ("└────") is not activity: only a line with words counts.
    val wentOn = agentWentOn(below)
    val approvalRows = raw.take(firstOption.coerceAtLeast(0))
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
    if (agent.equals("codex", true) && !wentOn) {
        val preview = parts?.command
        val activeFooter = below.lastOrNull { !isChrome(it) }?.trim()?.let(CODEX_FOOTER::matches) == true
        val heading = above.orEmpty().any { CODEX_COMMAND_HEADING.matches(it.text) }
        // The layout builder drops rows with no letters or digits. Inspect the raw command
        // region so a standalone ellipsis cannot disappear before this check.

        val commandRows = raw.take(firstOption.coerceAtLeast(0)).map(clean)
        val headingAt = commandRows.indexOfLast { CODEX_COMMAND_HEADING.matches(it) }
        val hidden = headingAt >= 0 && commandRows.drop(headingAt + 1).any { HIDDEN_CODEX_COMMAND.containsMatchIn(it) }
        val explicitKeys = numbered.all { SHORTCUT_LABEL.containsMatchIn(it.label) || ESC_LABEL.containsMatchIn(it.label) }
        val approvalChoices = numbered.any { it.label.startsWith("Yes, proceed", ignoreCase = true) }
        val otherApproval = above.orEmpty().any { CODEX_OTHER_APPROVAL.matches(it.text) }
        if (realMenu && ((heading && (!activeFooter || numbered.size < 2 || preview.isNullOrBlank() || !explicitKeys || hidden)) ||
                (activeFooter && approvalChoices && !otherApproval && !heading))) {
            return if (activeFooter || blocked) terminalCommandReview(preview) else null
        }
    }
    if (numbered.size in 2..20 && realMenu && underCard <= MAX_UNDER_CARD && !wentOn && !rejectEcho) {
        if (agent.equals("hermes", true) && approvalIdentity == null &&
            numbered.any { it.label.equals("Allow once", true) } &&
            numbered.any { it.label.equals("Deny", true) }) return terminalCommandReview(null)
        // Vibe draws no marker on the highlighted row; a fresh card highlights the first one.
        val selected = run.firstOrNull { it.first in marked }?.second ?: if (vibeCard) "1" else null
        val multi = numbered.count { isCheckbox(it.label) } >= 2
        // Codex (by name, or by its "Press enter to confirm or esc to cancel" footer) answers an
        // option by the key in its label; other agents keep arrows and Enter (1.4.2, from review).
        // Muse Code (1.4.7) does the same ("Allow this stage once (y)", "... (p)", "Abort the entire
        // command (esc)"), known by name or by its "Change approval defaults in /permissions" line.
        val shortcuts = agent?.lowercase() in setOf("codex", "muse") ||
            lines.takeLast(4).any { CODEX_FOOTER.containsMatchIn(it) || MUSE_CARD_FOOTER.containsMatchIn(it) }
        return Prompt(question ?: "Choose an option", numbered, selected, escRejects = vibeCard, parts = parts.takeIf { aboveText != null }, multi = multi, shortcuts = shortcuts, approvalIdentity = approvalIdentity)
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
    if ((footer && !rejectEcho) || (blocked && asks && !pastDialog)) {
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
