package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.herdr.Pane

/**
 * What the pane's title bar shows while it opens (2.2.7, Bill: "during that time it puts a question
 * mark and the title bar and the w2:p5 whatever tag instead of an actual agent like Claude ... maybe
 * there's like a loading.. and then it actually shows up so that the actual render is right the
 * first time").
 *
 * - [Kind.KNOWN]: the pane is in the server's pane list, so its agent is known; the real title.
 * - [Kind.LOADING]: not yet, for up to [PANE_IDENTITY_WAIT_MS]: "Loading…" with a small spinner, no
 *   "?" tile, never the raw pane id, and no agent controls.
 * - [Kind.FALLBACK]: still unknown after the wait: the pane's label from the last tab list seen,
 *   else "Unknown agent", with Show terminal. Never a raw id.
 *
 * The title bar only; the content keeps the 2.2.0 settled-view rules ([com.billmongan.herdrdroid.herdr.SettleGate],
 * "Loading the chat…"), and while the identity is loading the content says "Loading…" too, so the
 * screen view of an agent not yet known never shows before its chat.
 */
internal data class PaneIdentity(val kind: Kind, val title: String, val initial: String?) {
    enum class Kind { KNOWN, LOADING, FALLBACK }
    val known: Boolean get() = kind == Kind.KNOWN
    val loading: Boolean get() = kind == Kind.LOADING
}

/** How long the title bar says "Loading…" before it falls back to a label (2.2.7). */
internal const val PANE_IDENTITY_WAIT_MS = 4_000L

internal const val LOADING_TITLE = "Loading…"
internal const val UNKNOWN_AGENT = "Unknown agent"

/** A herdr pane or tab id such as "w2:p5" or "w1:t3": an address, never a title. */
private val RAW_ID = Regex("""^w\d+:[a-z]?\d+$""", RegexOption.IGNORE_CASE)

internal fun looksLikeRawId(text: String, paneId: String): Boolean {
    val t = text.trim()
    return t.isEmpty() || t == paneId || RAW_ID.matches(t)
}

/**
 * [pane]: the pane from the open server's list, or null while it is not there yet. [waitedOut]:
 * [PANE_IDENTITY_WAIT_MS] passed with it still unknown. [label]: the pane's label from an earlier
 * tab list (the watcher or the last list seen), when one exists.
 */
internal fun paneIdentity(pane: Pane?, paneId: String, waitedOut: Boolean, label: String?, title: (Pane) -> String = ::sessionName): PaneIdentity = when {
    pane != null -> PaneIdentity(PaneIdentity.Kind.KNOWN, title(pane), pane.initial)
    !waitedOut -> PaneIdentity(PaneIdentity.Kind.LOADING, LOADING_TITLE, null)
    else -> PaneIdentity(PaneIdentity.Kind.FALLBACK, label?.trim()?.takeUnless { looksLikeRawId(it, paneId) } ?: UNKNOWN_AGENT, null)
}
