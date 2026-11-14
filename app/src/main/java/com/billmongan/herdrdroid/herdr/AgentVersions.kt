package com.billmongan.herdrdroid.herdr

/**
 * Each agent's program version on a computer (1.4.0), so the screen parsers can tell which version
 * drew a card and branch on it when an update changes a card's shape.
 *
 * The versions come from each program's own `--version`, run once per connection on the computer
 * with a 5-second limit and nothing typed into any pane; an agent whose program isn't on the PATH
 * is left out. [ParserRules] is where a version-specific rule goes: it is consulted with the
 * pane's agent and version, and returns the defaults until an agent's update needs otherwise.
 */
object AgentVersions {
    /** The program each agent kind runs as, for `--version`. */
    val programs = mapOf(
        "claude" to "claude", "codex" to "codex", "grok" to "grok", "copilot" to "copilot", "agy" to "agy",
        "kiro" to "kiro-cli", "vibe" to "vibe", "opencode" to "opencode", "pi" to "pi", "omp" to "omp", "hermes" to "hermes",
    )

    /** One shell script that prints "@@ver <agent> <first line of --version>" for every program found. */
    fun script(): String = programs.entries.joinToString(" ", postfix = " wait") { (agent, prog) ->
        "( p=\$(command -v $prog 2>/dev/null || { [ -x \"\$HOME/.local/bin/$prog\" ] && echo \"\$HOME/.local/bin/$prog\"; }); " +
            "[ -n \"\$p\" ] && printf '@@ver $agent %s\\n' \"\$(timeout 5 \"\$p\" --version </dev/null 2>/dev/null | head -1)\" ) &"
    }

    /** The versions from [script]'s output: agent to the first version number found in its line. */
    fun parse(out: String): Map<String, String> = out.lines().mapNotNull { l ->
        if (!l.startsWith("@@ver ")) return@mapNotNull null
        val rest = l.removePrefix("@@ver ")
        val agent = rest.substringBefore(' ')
        val v = Regex("""\d+(?:\.\d+){1,3}(?:[-+][0-9A-Za-z.]+)?""").find(rest.substringAfter(' ', ""))?.value ?: return@mapNotNull null
        agent to v
    }.toMap()

    /**
     * Compares dotted versions numerically ("1.10.0" > "1.9.3"); a missing part counts as 0. With
     * equal numbers, a prerelease ("2.0.0-rc1") comes before the release (1.4.1, from a code review),
     * prereleases compare part by part as semantic versioning does, and build data ("+abc") is ignored.
     */
    fun compare(a: String, b: String): Int {
        fun core(v: String) = v.substringBefore('+').substringBefore('-')
        fun pre(v: String) = v.substringBefore('+').substringAfter('-', "")
        val pa = core(a).split('.').map { it.toIntOrNull() ?: 0 }
        val pb = core(b).split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        val ra = pre(a); val rb = pre(b)
        if (ra.isEmpty() || rb.isEmpty()) return rb.length.coerceAtMost(1) - ra.length.coerceAtMost(1)
        val xa = ra.split('.'); val xb = rb.split('.')
        for (i in 0 until maxOf(xa.size, xb.size)) {
            val p = xa.getOrNull(i) ?: return -1
            val q = xb.getOrNull(i) ?: return 1
            val np = p.toIntOrNull(); val nq = q.toIntOrNull()
            val d = when {
                np != null && nq != null -> np.compareTo(nq)
                np != null -> -1
                nq != null -> 1
                else -> p.compareTo(q)
            }
            if (d != 0) return d
        }
        return 0
    }

    /** True when [version] is known and at least [min]. */
    fun atLeast(version: String?, min: String): Boolean = version != null && compare(version, min) >= 0
}

/**
 * Version-specific parsing rules (1.4.0). Empty for now: every agent's current cards parse with the
 * defaults. When an agent update changes a card, add its rule here keyed on [AgentVersions.atLeast],
 * with a captured screen of each version in the parser tests.
 */
object ParserRules {
    data class Rules(val extraFooter: Regex? = null)
    fun forAgent(agent: String?, version: String?): Rules = Rules()
}
