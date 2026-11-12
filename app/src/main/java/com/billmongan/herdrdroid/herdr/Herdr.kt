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
class Herdr(private val ssh: HerdrSsh, herdrPath: String) {
    // The path is the user's setting; "~" stays unquoted so the remote shell expands it.
    private val herdr = herdrPath.trim().ifBlank { "herdr" }

    private fun cmd(vararg args: String) = herdr + " " + args.joinToString(" ") { shq(it) }

    private suspend fun run(command: String): String {
        val r = ssh.exec(command)
        if (r.exit != 0) {
            val msg = (r.err.ifBlank { r.out }).trim().lines().lastOrNull { it.isNotBlank() } ?: "exit ${r.exit}"
            throw HerdrError(msg, r.exit)
        }
        return r.out
    }

    suspend fun panes(): List<Pane> {
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
                agent = p.optString("agent").takeIf { !p.isNull("agent") && it.isNotBlank() },
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
        forPhone(run(cmd("pane", "read", paneId, "--source", "recent", "--lines", lines.toString())))

    /** The last few lines of every pane, fetched in one round trip. */
    suspend fun previews(ids: List<String>): Map<String, Peek> {
        if (ids.isEmpty()) return emptyMap()
        val script = ids.joinToString("; ") { id ->
            "echo ${shq("@@herdrdroid@@$id")}; ${cmd("pane", "read", id, "--source", "recent", "--lines", "12")} 2>/dev/null"
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
    suspend fun sendMessage(pane: Pane, text: String) {
        if (pane.agent != null && pane.status != Status.NEEDS_YOU) {
            try {
                run(cmd("agent", "prompt", pane.id, text))
                return
            } catch (_: HerdrError) {
                // Fall through to typing it in, which works in any pane.
            }
        }
        run(cmd("pane", "send-text", pane.id, text))
        run(cmd("pane", "send-keys", pane.id, "enter"))
    }

    suspend fun sendText(paneId: String, text: String) = run(cmd("pane", "send-text", paneId, text))

    suspend fun sendKeys(paneId: String, vararg keys: String) = run(cmd("pane", "send-keys", paneId, *keys))

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
        val r = ssh.execInput("python3 - ${shq(agent)} ${shq(pane.cwd)} ${shq(cursor)}", script)
        if (r.exit != 0 || r.out.isBlank()) {
            val why = r.err.trim().lines().lastOrNull { it.isNotBlank() } ?: "the reader exited ${r.exit}"
            return TranscriptRead(false, why, "", false, false, null, emptyList())
        }
        return Transcript.parseRead(r.out)
    }

    /** One image inside an agent's log, fetched with the reader's image mode. */
    suspend fun logImage(file: String, offset: Long, index: String, script: ByteArray): ByteArray {
        val r = ssh.execInput("python3 - __image__ ${shq(file)} $offset ${shq(index)}", script)
        val o = runCatching { JSONObject(r.out) }.getOrNull() ?: throw HerdrError("The image couldn't be read.", r.exit)
        if (!o.optBoolean("ok")) throw HerdrError(o.optString("why").ifBlank { "The image couldn't be read." }, r.exit)
        return java.util.Base64.getMimeDecoder().decode(o.getString("data"))
    }

    /** One OpenCode blob (an attachment it stored in its database). */
    suspend fun blob(db: String, hash: String, script: ByteArray): ByteArray {
        val r = ssh.execInput("python3 - __blob__ ${shq(db)} ${shq(hash)}", script)
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
        val args = mutableListOf("tab", "create", "--cwd", dir, "--label", label, "--no-focus")
        if (!workspace.isNullOrBlank()) args.addAll(listOf("--workspace", workspace))
        val created = JSONObject(run(cmd(*args.toTypedArray())))
        val pane = created.getJSONObject("result").getJSONObject("root_pane").getString("pane_id")
        delay(800)
        run(cmd("agent", "start", name, "--kind", kind, "--pane", pane, "--timeout", "90000"))
        return pane
    }

    /** Closes a tab and everything in it (a helper made with [startAgent]). */
    suspend fun closeTab(tabId: String) = run(cmd("tab", "close", tabId))

    /** A folder's entries, folders first, through the computer's python3 (names, kinds, sizes, times). */
    suspend fun listDir(path: String): List<DirEntry> {
        val target = if (path.startsWith("~")) "\"\$HOME\"" + shq(path.removePrefix("~")) else shq(path)
        val py = "import os,sys,json\np=os.path.realpath(sys.argv[1])\nout=[]\n" +
            "for e in sorted(os.scandir(p),key=lambda e:(not e.is_dir(),e.name.lower())):\n" +
            "  try:\n    st=e.stat()\n    out.append([e.name,e.is_dir(),st.st_size,int(st.st_mtime)])\n" +
            "  except Exception:\n    out.append([e.name,e.is_dir(),0,0])\n" +
            "print(json.dumps({'path':p,'entries':out[:2000]}))"
        val r = ssh.execInput("python3 - $target", py.toByteArray())
        if (r.exit != 0) throw HerdrError(r.err.trim().lines().lastOrNull() ?: "That folder couldn't be read.", r.exit)
        return parseDir(r.out)
    }

    /** Past sessions of [agent] in [cwd], newest first, from the reader's session-list mode. */
    suspend fun sessions(agent: String, cwd: String, script: ByteArray): List<PastSession> {
        val r = ssh.execInput("python3 - __sessions__ ${shq(agent)} ${shq(cwd)}", script, timeoutSeconds = 60)
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

    /** Confirms the command exists before the app shows any tabs. */
    suspend fun check() {
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
data class Prompt(val question: String, val choices: List<Choice>)

/**
 * Looks at the bottom of a pane for a question the agent is waiting on:
 * a numbered menu ("1. Yes"), or a "(y/n)" prompt. herdr's own "blocked" status
 * means the agent is waiting even when no choices can be read from the screen.
 */
fun detectPrompt(text: String, blocked: Boolean): Prompt? {
    val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.takeLast(16)
    if (lines.isEmpty()) return if (blocked) Prompt("This agent is waiting for you.", emptyList()) else null

    val clean = { s: String -> s.trim().trim('│', '┃', '|', ' ').trim() }
    val option = Regex("""^[\s│┃|]*(?:[❯›>▶➤]\s*)?(\d)[.)]\s+(.{1,70}?)[\s│┃|]*$""")
    val numbered = lines.mapNotNull { l ->
        option.find(l)?.let { m -> Choice(m.groupValues[2].trim(), m.groupValues[1], false) }
    }.distinctBy { it.keys }
    val question = lines.lastOrNull { clean(it).endsWith("?") }?.let(clean)

    if (numbered.size in 2..6 && (blocked || question != null)) {
        return Prompt(question ?: "Choose an option", numbered)
    }

    val yn = Regex("""[(\[]\s*[yY](?:es)?\s*/\s*[nN]o?\s*[)\]]""")
    val ynIndex = lines.indexOfLast { yn.containsMatchIn(it) }
    if (ynIndex >= 0 && ynIndex >= lines.size - 3) {
        return Prompt(clean(lines[ynIndex]), listOf(Choice("Yes", "y", true), Choice("No", "n", true)))
    }

    return if (blocked) Prompt(question ?: "This agent is waiting for you.", emptyList()) else null
}
