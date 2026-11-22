package com.billmongan.herdrdroid.herdr

/**
 * 2.2.0 (Bill: "add a menu in the same menu where we can choose a model to actually change the active
 * session as well which would pull up a list of sessions for that pane and let me switch between
 * them", and "have the new session list card include a button to create a new session for that
 * agent, make sure the herdr and HerdrDroid are in sync on this at all times"): how each agent starts
 * a new session and switches to another one from inside the session, as its own program does it.
 *
 * Only commands found in each installed agent's own help, docs or source are used. [Spec.resume] types
 * the agent's resume command with the session's id where that command takes one; otherwise [picker]
 * opens the agent's own session picker, which the app reads and shows as a menu. Agents with neither
 * are left out. The app never reports a session to herdr itself: each agent's herdr integration does,
 * and where it reports only later the card says herdr is out of date until it has.
 */
object SessionControls {
    data class Spec(
        val agent: String,
        /** Starts a new session in the pane. */
        val newCommand: String,
        /** The command that resumes a session by its id ("/resume"), or null when the agent's resume takes no id. */
        val resumeById: String? = null,
        /** The id [resumeById] takes, from a session's log file or folder. */
        val idOf: (PastSession) -> String? = { null },
        /** The command that opens the agent's own session picker, or null. */
        val picker: String? = null,
        /** When herdr learns of a new session: at once, or only at the next prompt (OpenCode, Antigravity, Hermes's resume). */
        val herdrAtOnce: Boolean = true,
        /** herdr keeps no session for this agent (a third-party integration's report is dropped). */
        val herdrTracks: Boolean = true,
    ) {
        /** The command that switches to [s], or null when the agent's picker has to be used. */
        fun resume(s: PastSession): String? = resumeById?.let { cmd -> idOf(s)?.let { "$cmd $it" } }
    }

    private val ID = Regex("""^[A-Za-z0-9_.-]{1,128}$""")

    private fun safe(id: String?): String? = id?.takeIf { ID.matches(it) }

    /** The session id in a log file's name: "<id>.jsonl" (Claude), "<time>_<id>.jsonl" (pi, omp). */
    internal fun idOfLog(ref: String): String? {
        val base = ref.substringAfterLast('/').removeSuffix(".jsonl")
        return safe(base.substringAfterLast('_'))
    }

    /** The folder a session's files are in, named by its id (Grok, Copilot, Muse). */
    internal fun idOfFolder(ref: String): String? = safe(ref.substringBeforeLast('/').substringAfterLast('/'))

    fun forAgent(agent: String?): Spec? = when (agent?.lowercase()) {
        // Claude Code 2.1.329: /clear (aliases reset, new) starts a new session; /resume [session ID or
        // search term]. Its SessionStart hook reports both to herdr (sources clear and resume).
        "claude" -> Spec("claude", "/clear", resumeById = "/resume", idOf = { idOfLog(it.ref) }, picker = "/resume")
        // Codex 0.171.0: /new, and /resume opens its picker. SessionStart reports both.
        "codex" -> Spec("codex", "/new", picker = "/resume")
        // OpenCode 2.3.2: /new goes to its home page, and the new session exists (and herdr's TUI plugin
        // reports it) only when the first message is sent; /sessions opens its picker.
        "opencode", "opencode-ursinus" -> Spec("opencode", "/new", picker = "/sessions", herdrAtOnce = false)
        // pi 1.6.0: /new, /resume (picker). Its extension reports session_start (new, resume).
        "pi" -> Spec("pi", "/new", picker = "/resume")
        // Oh My Pi 19.0.1: /new, /resume [session id]. Its extension reports session_start and session_switch.
        "omp" -> Spec("omp", "/new", resumeById = "/resume", idOf = { idOfLog(it.ref) }, picker = "/resume")
        // Hermes 0.23.3: /new (its plugin reports on_session_reset). Its /resume takes a name, not an id.
        "hermes" -> Spec("hermes", "/new")
        // Grok 1.0.92: /new, /resume [id|title]; SessionStart reports both (sources new and load).
        "grok" -> Spec("grok", "/new", resumeById = "/resume", idOf = { idOfFolder(it.ref) }, picker = "/resume")
        // Copilot CLI 1.0.114: /new, /resume <id|name>; SessionStart reports a new one.
        "copilot" -> Spec("copilot", "/new", resumeById = "/resume", idOf = { idOfFolder(it.ref) })
        // Kiro CLI 3.5.0: /chat new, /chat resume (picker). herdr drops Kiro's (third-party) session reports.
        "kiro" -> Spec("kiro", "/chat new", picker = "/chat resume", herdrTracks = false)
        // Mistral Vibe 3.1.2: /new (= /clear), /resume (picker). herdr drops its session reports too.
        "vibe" -> Spec("vibe", "/new", picker = "/resume", herdrTracks = false)
        // Muse Code 1.4.24: /clear starts a fresh session, /resume [<id-or-name>]. Its herdr plugin sends no session id.
        "muse" -> Spec("muse", "/clear", resumeById = "/resume", idOf = { idOfFolder(it.ref) }, herdrTracks = false)
        else -> null
    }
}
