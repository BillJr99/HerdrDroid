package com.billmongan.herdrdroid.herdr

/**
 * How much an agent's work costs Bill. FREE agents are used liberally (Antigravity, Grok, the
 * general OpenCode on Big Pickle, pi and Oh My Pi on llmproxy); MID ones draw on a plan with
 * limits (Copilot premium requests, Hermes); PAID ones spend Claude or Codex quota, which Bill
 * prefers to spare.
 */
enum class CostTier(val label: String) { FREE("free"), MID("plan"), PAID("paid") }

fun costTier(p: Pane): CostTier = when (p.agent?.lowercase()) {
    "agy", "antigravity", "grok", "pi", "omp" -> CostTier.FREE
    "opencode" -> if (isScoped(p)) CostTier.MID else CostTier.FREE
    "copilot", "hermes", "gemini" -> CostTier.MID
    "claude", "codex" -> CostTier.PAID
    else -> CostTier.MID
}

/** The scoped OpenCode: the only route for Canvas and student data, and nothing else goes there. */
fun isScoped(p: Pane): Boolean = p.agent?.lowercase() == "opencode" &&
    (p.tabLabel.contains("scoped", ignoreCase = true) || p.cwd.contains("scoped", ignoreCase = true))

/** One candidate for a task, with why it ranked where it did. */
data class Candidate(val pane: Pane, val tier: CostTier, val busy: Boolean, val tightest: Double?, val why: String)

/**
 * Ranks the agents that could take a task. [sensitive] work (Canvas, student data) may go only to
 * the scoped OpenCode; other work never goes there. Agents waiting on a question are skipped,
 * and so is any agent whose tightest usage limit is above [quotaCeiling] percent. Then: ready
 * before busy, free before plan before paid, more headroom first, and a pane not in front at the
 * computer before one that is.
 */
fun rankCandidates(
    panes: List<Pane>,
    quotaFor: (Pane) -> QuotaReading?,
    sensitive: Boolean,
    quotaCeiling: Double = 90.0,
): List<Candidate> = panes
    .filter { it.agent != null && it.status != Status.NEEDS_YOU }
    .filter { if (sensitive) isScoped(it) else !isScoped(it) }
    .mapNotNull { p ->
        val tight = quotaFor(p)?.tightest?.usedPercent
        if (tight != null && tight > quotaCeiling) return@mapNotNull null
        val busy = p.status == Status.WORKING
        val tier = costTier(p)
        val why = buildList {
            add(if (busy) "busy" else "ready")
            add(tier.label)
            tight?.let { add("${it.toInt()}% of its tightest limit used") }
        }.joinToString(" · ")
        Candidate(p, tier, busy, tight, why)
    }
    .sortedWith(compareBy<Candidate>({ it.busy }, { it.tier.ordinal }, { it.tightest ?: 0.0 }, { it.pane.focused }, { it.pane.id }))
