package com.billmongan.herdrdroid.diag

import java.security.MessageDigest

/**
 * 2.2.5 (Bill: Laptop2 signed in but showed no panes, and the log could not say which stage
 * failed or which server a line was about). One line per stage of opening a server and reading
 * its pane list, tagged with an opaque attempt number and an opaque server tag (a short hash of
 * the app's own server id, never a name, host or address).
 *
 * Only names, counts, classes, exit codes and timings go in: never credentials, keys, addresses,
 * command text, command output or pane contents.
 */
object LinkDiag {
    /** The named stages, in order. */
    enum class Stage(val label: String) {
        LOGIN("login"), CHANNEL("channel"), PROBE("probe"), LIST_READ("list-read"), PARSE("parse"), APPLY("apply"),
    }

    /** An opaque tag for a server: 6 hex digits of a hash of its app id. */
    fun serverTag(serverId: String?): String {
        if (serverId.isNullOrBlank()) return "s-none"
        val d = MessageDigest.getInstance("SHA-256").digest(("herdrdroid-diag|$serverId").toByteArray())
        return "s" + d.take(3).joinToString("") { "%02x".format(it) }
    }

    /** The error's class only (its message may carry output or an address). */
    fun errorClass(t: Throwable?): String = t?.javaClass?.simpleName?.ifBlank { "Exception" } ?: "none"

    /** The line for one stage; [fields] are already safe (names, counts, codes). */
    fun line(attempt: Long?, serverId: String?, stage: Stage, outcome: String, ms: Long?, fields: Map<String, Any?> = emptyMap()): String =
        buildString {
            append("attempt=").append(attempt?.let { "a$it" } ?: "a-")
            append(" server=").append(serverTag(serverId))
            append(" stage=").append(stage.label)
            append(" result=").append(outcome)
            ms?.let { append(" ms=").append(it) }
            fields.forEach { (k, v) -> if (v != null) append(' ').append(k).append('=').append(v) }
        }

    fun stage(attempt: Long?, serverId: String?, stage: Stage, outcome: String, ms: Long? = null, fields: Map<String, Any?> = emptyMap()) =
        DiagLog.log("link", line(attempt, serverId, stage, outcome, ms, fields))

    /** A link state change, its reason (a short fixed word) and how long the previous state lasted. */
    fun transition(serverId: String?, from: String, to: String, reason: String, afterMs: Long?) =
        DiagLog.log("link", "server=${serverTag(serverId)} link=${from.lowercase()}->${to.lowercase()} reason=$reason" + (afterMs?.let { " after_ms=$it" } ?: ""))
}
