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
    /** The label of the tab the pane is in ("opencode-scoped", "helper-codex-2", …). */
    val tabLabel: String = "",
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
            else -> agent.replaceFirstChar { it.uppercase() }
        }

    val initial: String get() = agentName.take(1).uppercase()
    val folder: String get() = cwd.trimEnd('/').substringAfterLast('/')
}

/** Single-quotes a value for the remote shell. */
fun shq(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

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

class Herdr(private val ssh: HerdrSsh, herdrPath: String, val backend: Backend = Backend.TUI, hcPath: String? = null) {
    // The path is the user's setting; "~" stays unquoted so the remote shell expands it.
    private val herdr = herdrPath.trim().ifBlank { "herdr" }
    private val hc = hcPath?.takeIf { it.isNotBlank() }?.let(::shq) ?: "\"\$(command -v hc || echo \"\$HOME/.local/bin/hc\")\""
    private val core get() = backend == Backend.CORE

    private fun cmd(vararg args: String) = herdr + " " + args.joinToString(" ") { shq(it) }
    private fun hcCmd(vararg args: String) = hc + " " + args.joinToString(" ") { shq(it) }

    /** herdr-core's usage checks can't type a command without pressing Enter, so they are skipped. */
    val canProbeScreens: Boolean get() = !core

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

    /** A command on the send lane: it doesn't wait behind reads. */
    private suspend fun runNow(command: String): String {
        val r = ssh.execNow(command)
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
        val r = ssh.exec(command)
        if (r.exit != 0) {
            val msg = (r.err.ifBlank { r.out }).trim().lines().lastOrNull { it.isNotBlank() } ?: "exit ${r.exit}"
            throw HerdrError(msg, r.exit)
        }
        return r.out
    }

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
            Pane(
                id = p.getString("pane_id"),
                tabId = p.optString("tab_id"),
                workspaceId = p.optString("workspace_id"),
                agent = p.optString("agent").takeIf { !p.isNull("agent") && it.isNotBlank() }?.let(::canonicalAgent),
                status = when (p.optString("agent_status")) {
                    "blocked" -> Status.NEEDS_YOU
                    "working" -> Status.WORKING
                    "done" -> Status.DONE
                    "idle" -> Status.IDLE
                    else -> Status.UNKNOWN
                },
                title = p.optString("terminal_title_stripped"),
                cwd = p.optString("foreground_cwd", p.optString("cwd")),
                focused = p.optBoolean("focused", false),
                tabLabel = labels[p.optString("tab_id")].orEmpty(),
            )
        }
        val order = listOf(Status.NEEDS_YOU, Status.WORKING, Status.DONE, Status.IDLE, Status.UNKNOWN)
        return list.sortedWith(compareBy({ order.indexOf(it.status) }, { it.id }))
    }

    suspend fun read(paneId: String, lines: Int = 200): String =
        if (core) forPhone(run(hcCmd("read", paneId, "--lines", lines.toString())))
        else forPhone(run(cmd("pane", "read", paneId, "--source", "recent", "--lines", lines.toString())))

    /** The last few lines of every pane, fetched in one round trip. */
    suspend fun previews(ids: List<String>): Map<String, Peek> {
        if (ids.isEmpty()) return emptyMap()
        val script = ids.joinToString("; ") { id ->
            "echo ${shq("@@herdrdroid@@$id")}; ${if (core) hcCmd("read", id, "--lines", "12") else cmd("pane", "read", id, "--source", "recent", "--lines", "12")} 2>/dev/null"
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

    suspend fun sendMessage(pane: Pane, text: String) {
        lastSendRoute = when {
            core -> "hc send"
            pane.agent != null && pane.status != Status.NEEDS_YOU -> "herdr agent prompt"
            else -> "herdr pane send-text + enter"
        }
        if (core) {
            // hc send pastes and presses Enter; it refuses a terminal waiting on a question
            // unless forced, and a message sent then is meant as the answer.
            if (pane.status == Status.NEEDS_YOU) runNow(hcCmd("send", "--force", pane.id, text)) else runNow(hcCmd("send", pane.id, text))
            return
        }
        if (pane.agent != null && pane.status != Status.NEEDS_YOU) {
            try {
                runNow(cmd("agent", "prompt", pane.id, text))
                return
            } catch (_: HerdrError) {
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
    suspend fun sendText(paneId: String, text: String): String {
        if (!core) return runNow(cmd("pane", "send-text", paneId, text))
        val keys = text.map { c -> when (c) { ' ' -> "space"; else -> c.toString() } }
        return runNow(hcCmd("keys", paneId, *keys.toTypedArray()))
    }

    /** Types [text] and presses Enter: one paste-and-submit on herdr-core. */
    suspend fun typeAndEnter(paneId: String, text: String) {
        if (core) { runNow(hcCmd("send", "--force", paneId, text)); return }
        runNow(cmd("pane", "send-text", paneId, text))
        runNow(cmd("pane", "send-keys", paneId, "enter"))
    }

    suspend fun sendKeys(paneId: String, vararg keys: String) =
        if (core) runNow(hcCmd("keys", paneId, *keys)) else runNow(cmd("pane", "send-keys", paneId, *keys))

    /**
     * Types a command the agent's own program answers (no model call) and returns the screen it
     * shows. Enter is pressed only if the input line then holds exactly that command, so a
     * half-typed message is never sent; otherwise the typed characters are erased and this returns
     * null. Close keys go out only while [openMarker] is on screen, so an Esc can never reach an
     * agent that is working.
     */
    private suspend fun guardedCommand(
        paneId: String, command: String, waitMillis: Long, closeKeys: List<String>, openMarker: Regex?,
        working: Boolean, ready: (String) -> Boolean,
    ): String? {
        sendText(paneId, command)
        delay(600)
        // \u00A0 is listed because some prompts put a no-break space after the ❯ glyph.
        val echo = Regex("""^[\s\u00A0│┃|>❯›»$]*""" + Regex.escape(command) + """[\s\u00A0│┃|]*$""")
        if (read(paneId, 24).lines().none { echo.matches(it) }) {
            sendKeys(paneId, *Array(command.length) { "backspace" })
            return null
        }
        sendKeys(paneId, "enter")
        delay(waitMillis)
        // Some screens draw their frame first and their numbers a moment later (Antigravity), so
        // keep reading for up to 6 more seconds until the screen is open and has what we need.
        var shown = read(paneId, 120)
        val deadline = System.currentTimeMillis() + 6_000
        fun done(s: String) = ready(s) && (openMarker == null || openMarker.containsMatchIn(s))
        while (!done(shown) && System.currentTimeMillis() < deadline) {
            delay(700)
            shown = read(paneId, 120)
        }
        // Close keys go out when the screen shows it is open. Without that sign, only an idle agent
        // gets them: there an Esc can't interrupt anything, while a working agent could be stopped.
        val open = openMarker?.containsMatchIn(shown) ?: true
        if (closeKeys.isNotEmpty() && (open || !working)) {
            sendKeys(paneId, *closeKeys.toTypedArray())
            delay(400)
        }
        // A screen that finished drawing after the close keys went out (Antigravity, when slow) is
        // still open. Check a few more times and close it, but only while its own marker shows, so
        // these extra keys can never reach the agent itself.
        if (closeKeys.isNotEmpty() && openMarker != null) {
            repeat(4) {
                delay(700)
                if (!openMarker.containsMatchIn(read(paneId, 40))) return shown
                sendKeys(paneId, *closeKeys.toTypedArray())
            }
        }
        return shown
    }

    /** One usage check: the limits, and where the agent needs it, the model and effort. Null when skipped. */
    suspend fun probeQuota(paneId: String, profile: QuotaProfile, working: Boolean): ProbeResult? {
        if (core) return null
        val shown = guardedCommand(paneId, profile.command, profile.waitMillis, profile.closeKeys, profile.openMarker, working) {
            parseQuota(it, profile.bareMeansLeft).isNotEmpty()
        } ?: return null
        val items = parseQuota(shown, profile.bareMeansLeft)
        var info: PaneInfo? = null
        val cmd = profile.infoCommand
        val parse = profile.parseInfo
        if (cmd != null && parse != null) {
            val screen = guardedCommand(paneId, cmd, 1_200, profile.closeKeys, profile.openMarker, working) {
                parse(it).let { i -> i.model != null || i.effort != null }
            }
            info = screen?.let(parse)
        }
        return ProbeResult(items, info?.model, info?.effort)
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
        val size = ssh.exec("stat -L -c %s -- $p").let { r ->
            if (r.exit != 0) throw HerdrError("That file isn't there (${path.substringAfterLast('/')}).", r.exit)
            r.out.trim().toLongOrNull() ?: 0L
        }
        if (size > maxBytes) throw HerdrError("That file is ${size / (1024 * 1024)} MB, over the ${maxBytes / (1024 * 1024)} MB limit.", 0)
        val (exit, bytes) = ssh.execBytes("cat -- $p")
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

    /**
     * A usage check that asks the agent's own command line instead of its pane (Oh My Pi's
     * `omp usage --json`), so nothing is typed into the pane and it can run while the agent works.
     * Null when the command isn't there or failed.
     */
    suspend fun cliQuota(profile: QuotaProfile): List<QuotaItem>? {
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
            val o = JSONObject(run(hcCmd("run", "--bg", "--json", "--name", label, "--cwd", dir, kind)).trim().lines().last { it.startsWith("{") })
            return o.getString("address")
        }
        val args = mutableListOf("tab", "create", "--cwd", dir, "--label", label, "--no-focus")
        if (!workspace.isNullOrBlank()) args.addAll(listOf("--workspace", workspace))
        val created = JSONObject(run(cmd(*args.toTypedArray())))
        val pane = created.getJSONObject("result").getJSONObject("root_pane").getString("pane_id")
        delay(800)
        run(cmd("agent", "start", name, "--kind", kind, "--pane", pane, "--timeout", "90000"))
        return pane
    }

    /** Closes a tab and everything in it (a helper made with [startAgent]). */
    suspend fun closeTab(tabId: String) {
        if (core) {
            runCatching { run(hcCmd("kill", tabId)) }
            run(hcCmd("rm", tabId))
            return
        }
        run(cmd("tab", "close", tabId))
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
        val r = ssh.execInput("$PYZ __work__ ${shq(pane.id)} ${shq(pane.agent.orEmpty())} ${shq(pane.cwd)}", z(script), timeoutSeconds = 30)
        if (r.out.isBlank()) throw HerdrError(r.err.trim().lines().lastOrNull { it.isNotBlank() } ?: "The work list couldn't be read.", r.exit)
        return parseWork(r.out)
    }

    /** For the cards: active subagents plus running background tasks, per pane. */
    suspend fun workCounts(panes: List<Pane>, script: ByteArray): Map<String, Int> {
        val list = panes.filter { it.agent == "claude" }
        if (list.isEmpty()) return emptyMap()
        val arg = org.json.JSONArray(list.map { org.json.JSONArray(listOf(it.id, it.agent.orEmpty(), it.cwd)) }).toString()
        val r = ssh.execInput("$PYZ __workcounts__ ${shq(arg)}", z(script), timeoutSeconds = 30)
        return runCatching { parseWorkCounts(r.out) }.getOrDefault(emptyMap())
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

data class ProbeResult(val items: List<QuotaItem>, val model: String?, val effort: String?)

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
data class Prompt(val question: String, val choices: List<Choice>, val selected: String? = null)

/** The keys that pick [choice]: arrows from the highlighted option, then Enter; null to type it. */
fun menuKeys(prompt: Prompt, choice: Choice): List<String>? {
    val from = prompt.selected?.toIntOrNull() ?: return null
    val to = choice.keys.toIntOrNull() ?: return null
    if (prompt.choices.none { it.keys == choice.keys }) return null
    val step = if (to > from) "down" else "up"
    return List(kotlin.math.abs(to - from)) { step } + "enter"
}

/**
 * Looks at the bottom of a pane for a question the agent is waiting on:
 * a numbered menu ("1. Yes"), or a "(y/n)" prompt. herdr's own "blocked" status
 * means the agent is waiting even when no choices can be read from the screen.
 */
fun detectPrompt(text: String, blocked: Boolean): Prompt? {
    val raw = text.lines().map { it.trimEnd() }.takeLast(80)
    val lines = raw.filter { it.isNotBlank() }.takeLast(40)
    if (lines.isEmpty()) return if (blocked) Prompt("This agent is waiting for you.", emptyList()) else null

    val clean = { s: String -> s.trim().trim('│', '┃', '|', '╭', '╰', '╮', '╯', '─', ' ').trim() }
    // "❯ 1. Yes", "  2. Yes, and don't ask again for …" (labels can be long; up to 200 characters).
    val option = Regex("""^[\s│┃|]*([❯›>▶➤]\s*)?(\d)[.)]\s+(.{1,200}?)[\s│┃|]*$""")
    val marked = mutableSetOf<Int>()
    val optionRows = lines.withIndex().mapNotNull { (i, l) ->
        option.find(l)?.let { m -> if (m.groupValues[1].isNotBlank()) marked += i; Triple(i, m.groupValues[2], m.groupValues[3].trim()) }
    }
    // Keep the last run of consecutive numbers (1, 2, 3 …), so a numbered list earlier on the
    // screen isn't mixed into the menu.
    val run = mutableListOf<Triple<Int, String, String>>()
    for (o in optionRows) {
        if (run.isNotEmpty() && o.second.toInt() != run.last().second.toInt() + 1) run.clear()
        run += o
    }
    val numbered = run.map { Choice(it.third, it.second, false) }.distinctBy { it.keys }
    // The question: the text just above the first choice (up to four lines, skipping frame
    // lines), or else the last line on screen that ends with "?".
    // The whole question: every line above the first choice up to a blank line, the top of the
    // question's box or a rule (at most 20 lines), so a long question isn't cut.
    val above = run.firstOrNull()?.let { (_, firstKey, firstLabel) ->
        val at = raw.indexOfLast { option.find(it)?.let { m -> m.groupValues[2] == firstKey && m.groupValues[3].trim() == firstLabel } == true }
        if (at <= 0) null else {
            val got = mutableListOf<String>()
            var i = at - 1
            while (i >= 0 && got.size < 20) {
                val l = raw[i]
                val c = clean(l)
                if (l.isBlank() || c.isEmpty()) { if (got.isNotEmpty()) break else { i--; continue } }
                if (l.trimStart().startsWith("╭") || l.trimStart().startsWith("┌") || c.matches(Regex("""^[─━═\-_\s]+$"""))) break
                if (c.any(Char::isLetterOrDigit)) got.add(0, c)
                i--
            }
            got.joinToString(" ").replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
        }
    }
    val question = above ?: lines.lastOrNull { clean(it).endsWith("?") }?.let(clean)

    if (numbered.size in 2..9 && (blocked || question?.endsWith("?") == true)) {
        val selected = run.firstOrNull { it.first in marked }?.second
        return Prompt(question ?: "Choose an option", numbered, selected)
    }

    val yn = Regex("""[(\[]\s*[yY](?:es)?\s*/\s*[nN]o?\s*[)\]]""")
    val ynIndex = lines.indexOfLast { yn.containsMatchIn(it) }
    if (ynIndex >= 0 && ynIndex >= lines.size - 3) {
        return Prompt(clean(lines[ynIndex]), listOf(Choice("Yes", "y", true), Choice("No", "n", true)))
    }

    return if (blocked) Prompt(question ?: "This agent is waiting for you.", emptyList()) else null
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
fun canonicalAgent(name: String): String = when (val n = name.trim().lowercase()) {
    "agy", "antigravity", "antigravity-cli", "antigravity_cli", "gemini-antigravity" -> "agy"
    "claude-code", "claude_code", "claudecode" -> "claude"
    "codex-cli", "openai-codex" -> "codex"
    "grok-cli", "grok-build" -> "grok"
    "copilot-cli", "github-copilot", "gh-copilot" -> "copilot"
    "oh-my-pi", "ohmypi" -> "omp"
    "pi-agent", "pi-coding-agent" -> "pi"
    else -> n
}

/** The agents the app knows by name; any other program in a herdr-core terminal is a plain shell. */
private val KNOWN_AGENTS = setOf("claude", "codex", "pi", "omp", "grok", "agy", "copilot", "hermes", "opencode", "gemini", "cursor", "amp")

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
    val h = herdrPath.trim().ifBlank { "herdr" }
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
