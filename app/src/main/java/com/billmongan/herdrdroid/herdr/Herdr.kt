package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.HerdrSsh
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
        val root = JSONObject(run(cmd("pane", "list")))
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
            )
        }
        val order = listOf(Status.NEEDS_YOU, Status.WORKING, Status.DONE, Status.IDLE, Status.UNKNOWN)
        return list.sortedWith(compareBy({ order.indexOf(it.status) }, { it.id }))
    }

    suspend fun read(paneId: String, lines: Int = 200): String =
        stripAnsi(run(cmd("pane", "read", paneId, "--source", "recent", "--lines", lines.toString())))

    /** The last few lines of every pane, fetched in one round trip. */
    suspend fun previews(ids: List<String>): Map<String, Peek> {
        if (ids.isEmpty()) return emptyMap()
        val script = ids.joinToString("; ") { id ->
            "echo ${shq("@@herdrdroid@@$id")}; ${cmd("pane", "read", id, "--source", "recent", "--lines", "12")} 2>/dev/null"
        }
        val out = stripAnsi(ssh.exec(script).out)
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
    ): String? {
        sendText(paneId, command)
        delay(600)
        val echo = Regex("""^[\s│┃|>❯›»$]*""" + Regex.escape(command) + """[\s│┃|]*$""")
        if (read(paneId, 24).lines().none { echo.matches(it) }) {
            sendKeys(paneId, *Array(command.length) { "backspace" })
            return null
        }
        sendKeys(paneId, "enter")
        delay(waitMillis)
        val shown = read(paneId, 120)
        if (closeKeys.isNotEmpty() && (openMarker == null || openMarker.containsMatchIn(shown))) {
            sendKeys(paneId, *closeKeys.toTypedArray())
            delay(300)
        }
        return shown
    }

    /** One usage check: the limits, and for agents that need it, the active model. Null when skipped. */
    suspend fun probeQuota(paneId: String, profile: QuotaProfile): ProbeResult? {
        val shown = guardedCommand(paneId, profile.command, profile.waitMillis, profile.closeKeys, profile.openMarker)
            ?: return null
        val items = parseQuota(shown, profile.bareMeansLeft)
        var model: String? = null
        if (profile.modelCommand != null && profile.modelPattern != null) {
            val status = guardedCommand(paneId, profile.modelCommand, 1_500, profile.closeKeys, profile.openMarker)
            model = status?.lines()?.firstNotNullOfOrNull { profile.modelPattern.find(it)?.groupValues?.get(1) }
        }
        return ProbeResult(items, model)
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

data class ProbeResult(val items: List<QuotaItem>, val model: String?)

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
