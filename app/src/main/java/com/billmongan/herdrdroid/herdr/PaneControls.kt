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
    ) {
        fun read(screen: String): String? = agentMode(agent, screen)
    }

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
        else -> null
    }

    /** Whether the menu can switch from [current]: never away from, or round, a mode that approves everything, except Claude's. */
    fun canSwitch(spec: ModeSpec, current: String?): Boolean =
        current != null && (spec.crossesApproveAll || spec.options.none { it.approvesAll && it.name == current }) &&
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
        val avoid = spec.options.filter { it.approvesAll }.map { it.name }.toSet()
        return spec.options.filter { o ->
            when {
                o.name == current -> true
                !o.offered -> false
                o.approvesAll && !spec.crossesApproveAll -> false
                spec.cycle == null || current == null -> true
                else -> pressesOnCycle(spec.cycle, current, o.name, avoid) != null
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
            if (presses >= spec.maxPresses) return back("${labelOf(target)} wasn't reached")
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
            shown = { s -> tail(s, 4).lastOrNull()?.let { DOT_EFFORT.find(it)?.groupValues?.get(1) } })
        // Kiro CLI 3.5.0: "/effort [level]" (low to max); it replies "Effort set to <level>".
        "kiro" -> ThinkingSpec("kiro", listOf("low", "medium", "high", "xhigh", "max"), { "/effort $it" },
            replies = { screen, level -> tail(screen, 40).count { it.contains("Effort set to $level", ignoreCase = true) } })
        else -> null
    }

    /** Whether the screen after [command] shows [level] took: the footer shows it, or the agent replied once more than before. */
    fun thinkingConfirmed(spec: ThinkingSpec, level: String, before: String, after: String): Boolean =
        spec.shown(after)?.equals(level, ignoreCase = true) == true || spec.replies(after, level) > spec.replies(before, level)
}
