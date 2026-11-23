package com.billmongan.herdrdroid.herdr

/**
 * 2.2.0 (Bill, 2026-11-22: "make sure all panes can change the mode like I can in Claude and also let
 * all panes change the thinking mode (omit any menus where not supported)"): the permission mode and
 * thinking level each agent can be switched to from the app, and how the app reads them back.
 *
 * An agent is listed only where its installed version has a verified, deterministic way to reach a
 * named setting and shows the setting on its screen afterwards, so the app can confirm the change by
 * reading the footer again. Everything else is left out, and the menu is not shown for that agent.
 */
object PaneControls {
    /** One mode the menu offers: [name] as [agentMode] reads it, [label] as shown. */
    data class ModeOption(
        val name: String,
        val label: String,
        /** Every tool runs without asking. Offered only where Claude's menu already reached it, behind a confirmation. */
        val approvesAll: Boolean = false,
        /**
         * Offered as a target. Vibe's Smart approve is not (2.2.0, from Grok's review): with it turned off
         * the press meant for it lands on Auto approve, and its presence can't be seen any other way.
         */
        val offered: Boolean = true,
    )

    /**
     * How an agent's mode is switched: [key] cycles through [cycle] in that order (verified against the
     * agent's own source or help), and [read] reads the current mode off the screen. A target is reached
     * by pressing [key] one step at a time and reading the footer after each press.
     */
    data class ModeSpec(
        val agent: String,
        val key: String,
        /** The cycle in order, or null when only the set of modes is known (Claude's cycle depends on its settings). */
        val cycle: List<String>?,
        val options: List<ModeOption>,
        /** Presses tried before the app gives up and goes back to the starting mode. */
        val maxPresses: Int,
        /**
         * Claude only: a press may pass over bypass on its way (its cycle puts bypass, when it is
         * there, between plan and auto). The app's menu has always cycled Claude this way.
         */
        val crossesApproveAll: Boolean = false,
        /** 2.2.2: the menu's name ("Mode", or Copilot's "Approvals"). */
        val title: String = "Mode",
        /** 2.2.2: reads the mode off the screen for agents [agentMode] doesn't know; null uses [agentMode]. */
        val reader: ((String) -> String?)? = null,
        /**
         * 2.2.2: the agent's own command that goes from one mode straight to another, or null where only
         * [key] gets there. A command is sent once and confirmed by reading the screen again.
         */
        val command: ((from: String?, to: String) -> String?)? = null,
        /**
         * 2.2.2: the mode the agent's newest reply names after a command, when its footer doesn't show
         * the mode (Codex's "Permissions updated to …"), or null when no new reply shows yet.
         */
        val replied: ((before: String, after: String) -> String?)? = null,
        /**
         * 2.2.2: every offered command sets its mode outright, whatever the mode is now, so the menu
         * works when the screen doesn't show the current one (Codex's /permissions <profile>).
         */
        val absolute: Boolean = false,
        /** 2.2.2: after a press that didn't reach the target, stop where it is instead of pressing on round the cycle. */
        val returnByKey: Boolean = true,
    ) {
        /**
         * The mode on [screen]. A custom reader still yields to [footerBypass] (2.2.2, from review): a
         * footer that says every tool runs without asking reads as "bypass", so the menu shows it and
         * never switches away from it.
         */
        fun read(screen: String): String? = when {
            reader == null -> agentMode(agent, screen)
            agent != "claude" && footerBypass(stripAnsi(screen)) -> "bypass"
            else -> reader.invoke(screen)
        }

        /** How the menu gets from [from] to [to]: the agent's command, or null for [key]. */
        fun commandFor(from: String?, to: String): String? = command?.invoke(from, to)

        /** What the dialog says about how a switch is made. */
        val how: String get() = when {
            command != null && key.isNotEmpty() -> "The app uses the agent's own command or ${keyLabel(key)}, and reads the screen until it shows the mode you picked."
            command != null -> "The app types the agent's own command and reads the screen for the change."
            else -> "The app presses ${keyLabel(key)} until the footer shows the mode you picked, and goes back to where it started if it can't get there."
        }
    }

    fun keyLabel(key: String) = when (key) { "shift+tab" -> "Shift+Tab"; else -> key }

    /** The mode menu for [agent], or null when it has none. */
    fun modes(agent: String?): ModeSpec? = when (agent?.lowercase()) {
        // Claude Code: Shift+Tab cycles default, accept edits, plan, and (when turned on) auto and
        // bypass; the footer says "accept edits on", "plan mode on", "auto mode on" or "bypass
        // permissions on", and nothing in the default mode. The app's menu has cycled it since 1.2,
        // which could land on bypass when Claude was started with it in its cycle, so bypass stays
        // (parity) behind a confirmation. Which modes the cycle holds depends on Claude's settings,
        // so the order is not assumed: the app presses and reads until it gets there.
        "claude" -> ModeSpec(
            "claude", "shift+tab", null,
            listOf(
                ModeOption("default", "Default (asks before edits and commands)"),
                ModeOption("accept edits", "Accept edits"),
                ModeOption("plan", "Plan mode"),
                ModeOption("auto", "Auto mode"),
                ModeOption("bypass", "Bypass permissions (approves every tool)", approvesAll = true),
            ),
            maxPresses = 6, crossesApproveAll = true,
        )
        // Mistral Vibe 3.1.2: Shift+Tab cycles ask, accept edits, smart approve (when turned on), auto
        // approve, then custom agents (vibe/core/agents/manager.py get_agent_order); the footer shows
        // "· ask", "· accept edits", "· smart approve" or "· auto approve". Auto approve runs every
        // tool, so it is never offered, and no switch passes over it.
        "vibe" -> ModeSpec(
            "vibe", "shift+tab", listOf("default", "accept edits", "auto", "bypass"),
            listOf(
                ModeOption("default", "Ask"),
                ModeOption("accept edits", "Accept edits"),
                ModeOption("auto", "Smart approve", offered = false),
                ModeOption("bypass", "Auto approve (approves every tool)", approvesAll = true),
            ),
            maxPresses = 4,
        )
        // Kiro CLI 3.5.0: Shift+Tab toggles its Plan agent and back ("Use Shift+Tab to switch back to
        // your previous agent"); the footer starts "Plan · " or "Default · ". Trust All Tools is a
        // /tools setting the app never turns on; while it is active the menu only shows it.
        "kiro" -> ModeSpec(
            "kiro", "shift+tab", listOf("default", "plan"),
            listOf(ModeOption("default", "Default"), ModeOption("plan", "Plan"),
                ModeOption("bypass", "Trust All Tools (approves every tool)", approvesAll = true)),
            maxPresses = 2,
        )
        // OpenCode 2.3.2: Shift+Tab is "Next agent" (agent_cycle), Build, Plan and any agents of your own;
        // the input box's bottom row names it ("┃  Build · <model>"). Neither built-in agent skips asking.
        "opencode" -> ModeSpec(
            "opencode", "shift+tab", null,
            listOf(ModeOption("build", "Build"), ModeOption("plan", "Plan")),
            maxPresses = 6,
        )
        // Codex 0.171.0: "/permissions <profile-id>" sets a permission profile outright (":read-only",
        // ":workspace"; ":danger-full-access" is never sent) and replies "Permissions updated to …". Its
        // footer shows no mode unless its status line is set up to, so the reply confirms it.
        "codex" -> ModeSpec(
            "codex", "", null,
            listOf(
                ModeOption(":read-only", "Read Only (asks before edits and internet)"),
                ModeOption(":workspace", "Default (edits the workspace, asks before going outside it)"),
                ModeOption("bypass", "Full Access (approves everything)", approvesAll = true, offered = false),
            ),
            maxPresses = 1, reader = { null }, absolute = true,
            command = { _, to -> if (to == ":read-only" || to == ":workspace") "/permissions $to" else null },
            replied = ::codexReplied,
        )
        // Grok 1.0.92: the chip after the model on the box edge ("Grok 4.7 (high) · auto-review") names
        // the mode; none is ask. "/auto-review" turns auto-review on and, run again, goes back to ask.
        // Shift+Tab's cycle puts always-approve between auto-review and ask, and plan's chip couldn't
        // be checked, so the menu uses only the command: Ask and Auto-review.
        "grok" -> ModeSpec(
            "grok", "", null,
            listOf(
                ModeOption("default", "Ask (asks before tools)"),
                ModeOption("auto", "Auto-review (a check approves safe tools)"),
                ModeOption("bypass", "Always-approve (approves every tool)", approvesAll = true, offered = false),
            ),
            maxPresses = 1, reader = ::grokMode,
            command = { from, to -> if ((from == "default" && to == "auto") || (from == "auto" && to == "default")) "/auto-review" else null },
        )
        // GitHub Copilot CLI 1.0.114: its footer reads "Interactive · Manual Approval"; "/autopilot off"
        // goes back to Interactive from Plan ("r==="off"?s="interactive""). Shift+Tab's cycle order
        // can't be proven for every build and may stop on Autopilot (which keeps going on its own and
        // counts as approving everything), so the app never presses it: Plan is shown, not offered,
        // and the menu switches from Plan back to Interactive only.
        "copilot" -> ModeSpec(
            "copilot", "", null,
            listOf(
                ModeOption("interactive", "Interactive"),
                ModeOption("plan", "Plan", offered = false),
                ModeOption("autopilot", "Autopilot (keeps going on its own)", approvesAll = true, offered = false),
            ),
            maxPresses = 1, reader = { copilotFooter(it)?.first }, returnByKey = false,
            command = { from, to -> if (to == "interactive" && from == "plan") "/autopilot off" else null },
        )
        // Antigravity 1.5.14: Shift+Tab cycles its agent modes ("Press shift+tab to cycle modes
        // (default, accept-edits, plan)", and Auto where it is offered); the status row names the
        // mode ("Plan mode: research & plan only (shift+tab to cycle)") and shows "? for shortcuts" in
        // the default mode. --dangerously-skip-permissions is a launch flag, not in the cycle. Where
        // Auto sits depends on the build, so the app presses and reads, as for Claude.
        "agy", "antigravity" -> ModeSpec(
            "agy", "shift+tab", null,
            listOf(
                ModeOption("default", "Default (asks before edits and commands)"),
                ModeOption("accept edits", "Accept edits"),
                ModeOption("plan", "Plan"),
                ModeOption("auto", "Auto (edits and safe commands approved)"),
                ModeOption("bypass", "Skipping permissions (approves every tool)", approvesAll = true, offered = false),
            ),
            maxPresses = 5, reader = ::agyMode,
        )
        // Oh My Pi 19.0.1: "/plan" toggles plan mode ("Toggle plan mode (agent plans before executing)"),
        // and its status row gains a "Plan" segment ("π > 🗺 Plan > ⬢ model > 📁 dir"). Its tool
        // approval setting is only in /settings, so the menu has Default and Plan.
        "omp" -> ModeSpec(
            "omp", "", null,
            listOf(ModeOption("default", "Default"), ModeOption("plan", "Plan"),
                ModeOption("bypass", "Yolo (approves every tool)", approvesAll = true, offered = false)),
            maxPresses = 1, reader = ::ompMode,
            command = { from, to -> if ((from == "default" && to == "plan") || (from == "plan" && to == "default")) "/plan" else null },
        )
        else -> null
    }

    private val AGY_MODE = Regex("""^\s*(Accept-edits|Plan|Auto) mode:""")
    private val AGY_DEFAULT = Regex("""^\s*\? for shortcuts\b""")

    /** Antigravity's mode from the row under its input box, or null when that row shows neither a mode nor its default hint. */
    internal fun agyMode(screen: String): String? {
        val row = stripAnsi(screen).lines().lastOrNull { it.isNotBlank() } ?: return null
        AGY_MODE.find(row)?.let { return when (it.groupValues[1]) { "Accept-edits" -> "accept edits"; "Plan" -> "plan"; else -> "auto" } }
        return if (AGY_DEFAULT.containsMatchIn(row)) "default" else null
    }

    /** Oh My Pi's status row: the row right above its "╰─" prompt, segments split at " > ". */
    private fun ompStatus(screen: String): List<String>? {
        val rows = stripAnsi(screen).lines().map { it.trimEnd() }.dropLastWhile { it.isBlank() }.takeLast(16)
        val prompt = rows.indexOfLast { it.trimStart().startsWith("╰─") }
        if (prompt < 1 || !rows[prompt - 1].contains(" > ")) return null
        return rows[prompt - 1].split(" > ").map { it.trim() }
    }

    /** Oh My Pi's mode: "plan" with a Plan segment, null with another mode segment (Vibe, Goal, Loop), else "default". */
    internal fun ompMode(screen: String): String? {
        val seg = ompStatus(screen) ?: return null
        if (seg.none { it.contains("⬢") }) return null
        if (seg.any { Regex("""^(\S+\s+)?Plan\b""").containsMatchIn(it) }) return "plan"
        if (seg.any { Regex("""^(\S+\s+)?(Vibe|Goal|Loop)\b""").containsMatchIn(it) }) return null
        return "default"
    }

    private val OMP_EFFORT = Regex("""⬢[^>]*·\s*[○◔◑◒◕◉⦸⟳]\s*(min|low|med|high|xhigh|max|off|auto)\b""")

    /** Oh My Pi's effort from its model segment ("⬢ model · ◒ high"); shown only for a thinking model. */
    internal fun ompEffort(screen: String): String? {
        val seg = ompStatus(screen) ?: return null
        val m = seg.firstNotNullOfOrNull { OMP_EFFORT.find(it) } ?: return null
        return when (val l = m.groupValues[1]) { "min" -> "minimal"; "med" -> "medium"; else -> l }
    }

    private val PI_EFFORT = Regex("""•\s+(?:thinking\s+)?(off|minimal|low|medium|high|xhigh|max)\s*$""")

    /** pi's effort from the end of its token row ("… model • high", "… model • thinking off"); shown only for a reasoning model. */
    internal fun piEffort(screen: String): String? = tail(screen, 3).asReversed().firstNotNullOfOrNull { PI_EFFORT.find(it.trimEnd())?.groupValues?.get(1) }

    /**
     * 2.2.2: a second menu for agents with two settings: Copilot's permission policy, the second chip of
     * its footer ("Manual Approval", "Assisted Permissions", "Allow All"), set with "/permissions". Allow
     * All approves every request and is never offered.
     */
    fun approvals(agent: String?): ModeSpec? = when (agent?.lowercase()) {
        "copilot" -> ModeSpec(
            "copilot", "", null,
            listOf(
                ModeOption("manual", "Manual Approval (asks for each request)"),
                ModeOption("assisted", "Assisted Permissions (a safety check approves safe requests)"),
                ModeOption("bypass", "Allow All (approves every request)", approvesAll = true, offered = false),
            ),
            maxPresses = 1, title = "Approvals", reader = { copilotFooter(it)?.second },
            command = { _, to -> when (to) { "manual" -> "/permissions default"; "assisted" -> "/permissions assisted"; else -> null } },
        )
        else -> null
    }

    /** Every mode menu [agent] has, in menu order. */
    fun modeMenus(agent: String?): List<ModeSpec> = listOfNotNull(modes(agent), approvals(agent))

    private fun codexReplies(screen: String) = tail(screen, 40).filter { it.contains("Permissions updated to", ignoreCase = true) }

    /**
     * The profile Codex's newest "Permissions updated to …" names, once one more shows than before:
     * ":read-only", ":workspace" (Default, Workspace or Agent), "bypass" (full access), or "other".
     */
    internal fun codexReplied(before: String, after: String): String? {
        val now = codexReplies(after)
        if (now.size <= codexReplies(before).size) return null
        val name = now.last().substringAfter("Permissions updated to", "").lowercase()
        return when {
            Regex("""\bfull\b|danger""").containsMatchIn(name) -> "bypass"
            Regex("""read[- ]only""").containsMatchIn(name) -> ":read-only"
            Regex("""\b(default|workspace|agent)\b""").containsMatchIn(name) -> ":workspace"
            else -> "other"
        }
    }

    private val GROK_EDGE = Regex("""Grok\b[^()\n]{0,40}\((?:low|medium|high|xhigh)\)\s*(?:·\s*([a-z][a-z-]*))?\s*─""")

    /** Grok's mode from its box edge: "default" with no chip, "auto" for auto-review, "bypass" for always-approve, null for any other chip. */
    internal fun grokMode(screen: String): String? {
        val m = tail(screen, 12).asReversed().firstNotNullOfOrNull { GROK_EDGE.find(it) } ?: return null
        return when (m.groupValues[1]) { "" -> "default"; "auto-review" -> "auto"; "always-approve" -> "bypass"; else -> null }
    }

    private val COPILOT_CHIPS = Regex("""·\s+(Interactive|Plan|Autopilot|Shell)\s+·\s+(Manual Approval|Assisted Permissions|Allow All)(?=\s+·|\s*$)""")

    /** Copilot's execution mode and permission policy from its footer ("… · Interactive · Manual Approval · …"). */
    internal fun copilotFooter(screen: String): Pair<String, String>? {
        val m = tail(screen, 4).asReversed().firstNotNullOfOrNull { COPILOT_CHIPS.find(it) } ?: return null
        val policy = when (m.groupValues[2]) { "Manual Approval" -> "manual"; "Assisted Permissions" -> "assisted"; else -> "bypass" }
        return m.groupValues[1].lowercase() to policy
    }

    /** Whether the menu can switch from [current]: never away from, or round, a mode that approves everything, except Claude's. */
    fun canSwitch(spec: ModeSpec, current: String?): Boolean =
        (current == null && spec.absolute) || current != null && (spec.crossesApproveAll || spec.options.none { it.approvesAll && it.name == current }) &&
            spec.options.any { it.name == current }

    /**
     * The presses from [from] to [to] on a known cycle, or null when the order is unknown or either
     * mode is not on it. [avoid]: none of the modes passed through on the way may be in it, so a switch
     * never stops, even for a moment, on a mode that approves everything.
     */
    fun pressesOnCycle(cycle: List<String>?, from: String, to: String, avoid: Set<String> = emptySet()): Int? {
        if (cycle == null) return null
        val a = cycle.indexOf(from); val b = cycle.indexOf(to)
        if (a < 0 || b < 0) return null
        val n = (b - a + cycle.size) % cycle.size
        for (k in 1 until n) if (cycle[(a + k) % cycle.size] in avoid) return null
        return n
    }

    /**
     * The modes of [spec] the menu offers from [current]: an approve-all mode only where Claude's menu
     * already reached it, and on a known cycle only those reached without passing over one.
     */
    fun reachable(spec: ModeSpec, current: String?): List<ModeOption> {
        // 2.2.2: a mode that isn't offered (Copilot's Autopilot, Vibe's Smart approve) is never passed over either.
        val avoid = spec.options.filter { it.approvesAll || !it.offered }.map { it.name }.toSet()
        return spec.options.filter { o ->
            when {
                o.name == current -> true
                !o.offered -> false
                o.approvesAll && !spec.crossesApproveAll -> false
                spec.absolute -> spec.commandFor(current, o.name) != null
                current == null -> spec.command == null
                spec.commandFor(current, o.name) != null -> true
                spec.command != null && spec.key.isEmpty() -> false
                spec.cycle == null -> true
                else -> pressesOnCycle(spec.cycle, current, o.name, avoid)?.let { it <= spec.maxPresses } == true
            }
        }
    }

    /** The outcome of one step of a switch, for [ModeSwitcher]. */
    sealed interface Step {
        /** Press the key once more. */
        data object Press : Step
        /** Reached: the footer shows the target. */
        data class Done(val mode: String) : Step
        /** Stopped at [mode] (the starting mode after a failed switch), with [why]. */
        data class Stop(val mode: String?, val why: String) : Step
    }

    /** Whether [mode] approves every tool and the user did not pick it (and confirm it) as [target]. */
    fun unconfirmedApproveAll(spec: ModeSpec, mode: String?, target: String): Boolean =
        mode != null && mode != target && spec.options.any { it.approvesAll && it.name == mode }

    /**
     * The switch as a state machine, so it can be tested without a pane: [start] is the mode read
     * before the first press and [target] the one asked for. [next] gets the mode read after the last
     * press (or [start] before any) and says what to do.
     *
     * The pane is never left half switched: a target that isn't in the session's cycle goes on round to
     * [start]. It never stops on a mode that approves everything that wasn't the confirmed target
     * (2.2.0, from Grok's review): from there it keeps pressing, also when a press didn't change the
     * footer, until the footer shows [start], up to a bound; a [Stop] still on such a mode is reported
     * loudly by the caller.
     */
    class ModeSwitcher(private val spec: ModeSpec, private val start: String, private val target: String) {
        var presses = 0
            private set
        private var returning = false
        private var why = ""

        /** The most presses, the way back included. */
        val limit get() = spec.maxPresses * 3

        private fun unsafe(read: String?) = unconfirmedApproveAll(spec, read, target)

        private fun back(reason: String): Step {
            why = reason
            returning = true
            return press()
        }

        private fun press(): Step {
            presses++
            return Step.Press
        }

        fun next(read: String?): Step {
            if (read == null) return Step.Stop(null, "the footer couldn't be read; check the pane")
            if (returning) {
                if (read == start) return Step.Stop(read, why)
                if (presses >= limit) return Step.Stop(read, "$why, and it didn't come back to ${labelOf(start)}; check the pane now")
                return press()
            }
            if (read == target) return Step.Done(read)
            if (presses > 0 && read == start) return Step.Stop(read, "${labelOf(target)} isn't in this session's cycle")
            if (unsafe(read) && !spec.crossesApproveAll) return back("it stopped on ${labelOf(read)}, which approves every tool, so it went back")
            if (presses >= spec.maxPresses) return if (spec.returnByKey) back("${labelOf(target)} wasn't reached")
                else Step.Stop(read, "${labelOf(target)} wasn't reached; it stopped on ${labelOf(read)}")
            return press()
        }

        /**
         * The footer didn't change after a press. On a safe mode the switch stops there; on a mode
         * that approves everything it presses again, toward [start], within [limit].
         */
        fun unchanged(read: String): Step {
            if (!unsafe(read)) return Step.Stop(read, "the mode didn't change after ${spec.key}")
            if (!returning) { why = "it stopped on ${labelOf(read)}, which approves every tool, so it went back"; returning = true }
            if (presses >= limit) return Step.Stop(read, "$why, and it didn't come back to ${labelOf(start)}; check the pane now")
            presses++
            return Step.Press
        }

        private fun labelOf(name: String) = spec.options.firstOrNull { it.name == name }?.label ?: name
    }

    // ---- Writing into the pane ----------------------------------------------------------------------

    /**
     * Why a menu action (a mode key, a thinking or session command) must not go into the pane now, from
     * a fresh read of its screen, or null (2.2.0, from Grok's review): the same checks a normal send
     * makes. A question or dialog is open; the input line can't be seen; or text is typed in it.
     */
    fun writeBlock(agent: String?, viewport: String): String? {
        if (detectPrompt(forPhone(viewport), false, agent) != null) return "a question or dialog is open in it"
        val draft = composerDraftOf(viewport, agent)
        val line = inputLineOf(viewport, agent) ?: return if (draft != null) TYPED else "its input line isn't on screen"
        if (line.isNotEmpty() || draft != null) return TYPED
        return null
    }

    const val TYPED = "text is typed in its input box at the computer"

    // ---- Thinking level ---------------------------------------------------------------------------

    /**
     * An agent's thinking (reasoning effort) command: [command] sets a named level, and the change is
     * confirmed from the screen, by [shown] (the level the footer shows) or by the agent's own reply
     * counted with [replies] (more replies after the command than before means it took).
     */
    data class ThinkingSpec(
        val agent: String,
        val levels: List<String>,
        val command: (String) -> String,
        val shown: (String) -> String? = { null },
        val replies: (String, String) -> Int = { _, _ -> 0 },
    )

    private fun tail(screen: String, n: Int) = stripAnsi(screen).lines().filter { it.isNotBlank() }.takeLast(n)

    /** Grok's box edge names the model and effort: "╰──── Grok 4.7 (high) ─╯", "Grok 4.7 (high) · auto-review". */
    private val GROK_EFFORT = Regex("""\((low|medium|high|xhigh)\)(?=\s*(?:·|─|╯|$))""")
    /** Muse's and Antigravity's footers: "muse-wonder-full-1.0 · max · ~/agents/muse", "Gemini 4 Argon · medium · ~/agents/agy". */
    /** Antigravity 1.5.14's status row ends with the level ("Orchestrator · Gemini 4 Neon · xhigh"); older ones had it mid-row. */
    private val END_EFFORT = Regex("""\s·\s(low|medium|high|xhigh|max)\s*$""")

    internal fun agyEffort(s: String): String? = tail(s, 4).lastOrNull()?.let { DOT_EFFORT.find(it)?.groupValues?.get(1) ?: END_EFFORT.find(it.trimEnd())?.groupValues?.get(1) }

    private val DOT_EFFORT = Regex("""\s·\s(none|minimal|low|medium|high|xhigh|max|ultra)\s·\s""")

    fun thinking(agent: String?): ThinkingSpec? = when (agent?.lowercase()) {
        // Claude Code 2.1.329: "/effort [low|medium|high|xhigh|max|auto]"; it replies
        // "Set effort level to <level> (this session only)". Its footer doesn't show the level.
        "claude" -> ThinkingSpec("claude", listOf("low", "medium", "high", "xhigh", "max", "auto"), { "/effort $it" },
            replies = { screen, level -> tail(screen, 40).count { it.contains("Set effort level to $level", ignoreCase = true) } })
        // Grok 1.0.92: "/effort low|medium|high|xhigh"; the box edge shows "(high)" after the model.
        "grok" -> ThinkingSpec("grok", listOf("low", "medium", "high", "xhigh"), { "/effort $it" },
            shown = { s -> tail(s, 12).firstNotNullOfOrNull { l -> GROK_EFFORT.findAll(l).lastOrNull()?.groupValues?.get(1) } })
        // Muse Code 1.4.24: "/effort [none|minimal|low|medium|high|xhigh|max|ultra]"; the footer shows it.
        "muse" -> ThinkingSpec("muse", listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"), { "/effort $it" },
            shown = { s -> tail(s, 4).lastOrNull()?.let { DOT_EFFORT.find(it)?.groupValues?.get(1) } })
        // Antigravity 1.5.14: "/effort <level>" (low to max); its footer shows "· medium ·".
        "agy", "antigravity" -> ThinkingSpec("agy", listOf("low", "medium", "high", "xhigh", "max"), { "/effort $it" },
            shown = ::agyEffort)
        // pi 1.6.0: "/thinking [level]" (off, minimal, low, medium, high, xhigh, max); the end of its token
        // row shows "• <level>" (or "• thinking off") for a reasoning model.
        "pi" -> ThinkingSpec("pi", listOf("off", "minimal", "low", "medium", "high", "xhigh", "max"), { "/thinking $it" }, shown = ::piEffort)
        // Oh My Pi 19.0.1: "/effort [level]"; its model segment shows "⬢ model · ◒ high" for a thinking model.
        "omp" -> ThinkingSpec("omp", listOf("off", "minimal", "low", "medium", "high", "xhigh", "max"), { "/effort $it" }, shown = ::ompEffort)
        // Kiro CLI 3.5.0: "/effort [level]" (low to max); it replies "Effort set to <level>".
        "kiro" -> ThinkingSpec("kiro", listOf("low", "medium", "high", "xhigh", "max"), { "/effort $it" },
            replies = { screen, level -> tail(screen, 40).count { it.contains("Effort set to $level", ignoreCase = true) } })
        // Codex 0.171.0: "/model --reasoning=<level>" ("Use '--reasoning=<level>' or '--speed=<tier>'")
        // changes the effort in the session; the footer's model-with-reasoning item reads "GPT-6.2-Sol
        // high" ("default" before one is picked), and the reply is "Model changed to …".
        "codex" -> ThinkingSpec("codex", listOf("low", "medium", "high", "xhigh", "max"), { "/model --reasoning=$it" },
            shown = ::codexEffort,
            replies = { screen, level -> tail(screen, 40).count { it.contains("Model changed to", ignoreCase = true) && Regex("""\b${Regex.escape(level)}\b""").containsMatchIn(it) } })
        // Hermes 0.23: "/reasoning <level>" replies "✓ Reasoning effort set to '<level>' …"; its status bar doesn't show it.
        "hermes" -> ThinkingSpec("hermes", listOf("none", "low", "medium", "high", "xhigh", "max"), { "/reasoning $it" },
            replies = { screen, level -> tail(screen, 40).count { it.contains("Reasoning effort set to", ignoreCase = true) &&
                (it.contains("'$level'") || it.contains("`$level`")) } })
        else -> null
    }

    private val CODEX_EFFORT = Regex("""^\s{1,4}\S[^·]*?\s(default|none|minimal|low|medium|high|xhigh|max|ultra)\s+·\s""")

    /** Codex's effort from its footer's first item ("  GPT-6.2-Sol high · ~/dir"); "default" is none picked. */
    internal fun codexEffort(screen: String): String? = tail(screen, 4).asReversed().firstNotNullOfOrNull { CODEX_EFFORT.find(it)?.groupValues?.get(1) }

    /** Whether the screen after [command] shows [level] took: the footer shows it, or the agent replied once more than before. */
    fun thinkingConfirmed(spec: ThinkingSpec, level: String, before: String, after: String): Boolean =
        spec.shown(after)?.equals(level, ignoreCase = true) == true || spec.replies(after, level) > spec.replies(before, level)
}
