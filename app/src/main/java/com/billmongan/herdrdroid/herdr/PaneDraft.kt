package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.ConnectionChanged
import kotlinx.coroutines.CancellationException

/**
 * Agents whose input box the app may empty with Backspace (2.1.0). Backspace only deletes the
 * character before the cursor in each of these, so pressing it never submits, never quits and never
 * answers anything: Claude Code and Codex (their own line editors), Hermes (prompt_toolkit), and
 * Grok (the app already clears Grok's box this way before /privacy). Other agents show the draft
 * and Send draft, but no Clear draft, until their box is checked the same way.
 */
internal val DRAFT_CLEARABLE = setOf("claude", "codex", "hermes", "grok")

internal fun canClearDraft(agent: String?): Boolean = agent?.lowercase() in DRAFT_CLEARABLE

/**
 * A draft typed in a pane's input box at the computer, as the chat shows it, and the two things
 * the app can do with it on an explicit tap (2.1.0): press Enter once to send it as it is, or press
 * Backspace to empty the box. Each re-reads the pane first and acts only on the same occupant, at
 * the bottom of its scrollback, with no question open and the very text that was shown. Each holds
 * the pane's dispatch lane and a revocable permit per write, like every other prompt write.
 */
internal class PaneDraftAction(private val dispatch: PaneDispatch, private val gate: WriteGate) {
    interface Port {
        /** A fresh read of the pane, or null when it can't be read. */
        suspend fun pane(id: String): Pane?
        /** The pane's live screen with its styles, or null when it can't be read. */
        suspend fun viewport(id: String): String?
        /** Keys that the transport starts only while [permit] is open. */
        suspend fun keys(id: String, keys: List<String>, permit: WritePermit)
        /** Time for the agent to redraw before the next read. */
        suspend fun pause(ms: Long)
    }

    enum class Outcome {
        /** Enter went and a fresh read shows the input box empty. */
        SENT,
        /** A fresh read shows the input box empty. */
        CLEARED,
        /** The draft differs from the one shown: nothing was typed. */
        CHANGED,
        /** Not the same occupant, a question is open, scrolled back, another write, or the screen can't be read: nothing was typed. */
        REFUSED,
        /** Clear: Backspace went, but some text is still there. */
        PARTIAL,
        /** A key went, but the app could not see the result. Never retried. */
        UNCERTAIN,
        /** Send only: Enter could pick a highlighted command or suggestion, or the agent isn't idle. Nothing was typed. */
        NOT_SENDABLE,
    }

    companion object {
        /** Backspace rounds for Clear, each re-reading the box first. */
        const val MAX_CLEAR_ROUNDS = 6
        /** Backspaces in Clear's first round, a probe read back before any larger batch. */
        const val CLEAR_PROBE = 8
        /** Backspaces in one write. */
        const val MAX_KEYS_PER_ROUND = 2_000
        private const val SETTLE_MS = 600L
        private const val CONFIRM_READS = 4
    }

    /** A read after a key went: a failure is "can't see", never a reason to try again. */
    private suspend fun <T> readOrNull(read: suspend () -> T?): T? = try {
        read()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        null
    }

    private fun ready(shown: Pane, fresh: Pane?) = fresh != null && sameOccupant(shown, fresh) &&
        fresh.status != Status.NEEDS_YOU && fresh.status != Status.UNKNOWN && (fresh.scrollOffset ?: 0) == 0

    /** The draft on [visible], or null when nothing may be done with what it shows. */
    private fun draftOn(pane: Pane, visible: String?): ComposerDraft? {
        if (visible.isNullOrBlank()) return null
        if (dismissibleUsageFrame(pane.agent, visible) != null) return null
        if (detectPrompt(forPhone(visible), pane.status == Status.NEEDS_YOU, pane.agent) != null) return null
        return composerDraftOf(visible, pane.agent)
    }

    private fun emptyBox(pane: Pane, visible: String?): Boolean =
        !visible.isNullOrBlank() && inputLineOf(visible, pane.agent) == "" && composerDraftOf(visible, pane.agent) == null

    /** Presses Enter once, only if the box still holds [shownText]. */
    suspend fun send(server: String, shown: Pane, shownText: String, allowed: () -> Boolean, port: Port): Outcome =
        guarded(server, shown, shownText, allowed, port) { fresh, draft ->
            if (draftSendBlock(draft, fresh.status, fresh.agentName) != null) return@guarded Outcome.NOT_SENDABLE
            // The permit re-checks [allowed] at the transport, right before the key goes.
            val permit = gate.open(server to shown.id, WritePermit(allowed)) ?: return@guarded Outcome.REFUSED
            try {
                if (!allowed()) return@guarded Outcome.REFUSED
                // 2.1.1 (2.1.0 acceptance, D5c trial 6): an edit at the computer landed 179 ms before
                // Enter, after the guarded check, and the changed text went. So the box is read once
                // more here, inside the pane's dispatch lane and with the permit open, as the very
                // last step before Enter, and compared with the exact text that was shown.
                // Residual window, which no remote check can close: from this read leaving herdr to
                // Enter reaching the agent (one SSH round trip plus the key write, typically well
                // under a second). Text typed at the computer inside that window is submitted with
                // the draft; the result is then UNCERTAIN unless the box is seen empty afterwards.
                val last = port.viewport(shown.id) ?: return@guarded Outcome.REFUSED
                if (!allowed()) return@guarded Outcome.REFUSED
                val still = draftOn(fresh, last)
                    ?: return@guarded if (emptyBox(fresh, last)) Outcome.CHANGED else Outcome.REFUSED
                if (!still.bounded) return@guarded Outcome.REFUSED
                if (still.text != shownText) return@guarded Outcome.CHANGED
                if (draftSendBlock(still, fresh.status, fresh.agentName) != null) return@guarded Outcome.NOT_SENDABLE
                port.keys(shown.id, listOf("enter"), permit)
            } catch (e: WriteRevoked) {
                return@guarded Outcome.REFUSED
            } catch (c: CancellationException) {
                if (permit.submitted) return@guarded Outcome.UNCERTAIN
                throw c
            } catch (e: Exception) {
                return@guarded if (permit.submitted) Outcome.UNCERTAIN else Outcome.REFUSED
            } finally {
                gate.finish(server to shown.id, permit)
            }
            // Sent only when the box is seen empty; never a second Enter.
            repeat(CONFIRM_READS) {
                port.pause(SETTLE_MS)
                val after = readOrNull { port.viewport(shown.id) }
                if (emptyBox(fresh, after)) return@guarded Outcome.SENT
            }
            Outcome.UNCERTAIN
        }

    /**
     * Presses a few Backspaces ([CLEAR_PROBE]) and reads the box again, then as many as are left; up to
     * [MAX_CLEAR_ROUNDS] rounds, each only while the box still shows a strict start of the text the
     * last round saw (some Backspaces were dropped), so no text that was never shown is deleted.
     * Never presses Enter.
     */
    suspend fun clear(server: String, shown: Pane, shownText: String, allowed: () -> Boolean, port: Port): Outcome {
        if (!canClearDraft(shown.agent)) return Outcome.REFUSED
        return guarded(server, shown, shownText, allowed, port) { _, draft ->
            // A suggestion list may be part of the box's buffer: nothing is typed then.
            if (draft.menu) return@guarded Outcome.NOT_SENDABLE
            var left = shownText
            var pressed = false
            for (round in 1..MAX_CLEAR_ROUNDS) {
                // First a few, read back; then the rest, once they removed what was expected.
                val count = (if (round == 1) minOf(left.length, CLEAR_PROBE) else left.length).coerceIn(1, MAX_KEYS_PER_ROUND)
                val permit = gate.open(server to shown.id, WritePermit(allowed))
                    ?: return@guarded if (pressed) Outcome.UNCERTAIN else Outcome.REFUSED
                try {
                    if (!allowed()) return@guarded if (pressed) Outcome.UNCERTAIN else Outcome.REFUSED
                    port.keys(shown.id, List(count) { "backspace" }, permit)
                    pressed = true
                } catch (e: WriteRevoked) {
                    return@guarded if (pressed) Outcome.UNCERTAIN else Outcome.REFUSED
                } catch (c: CancellationException) {
                    if (pressed || permit.submitted) return@guarded Outcome.UNCERTAIN
                    throw c
                } catch (e: Exception) {
                    return@guarded if (pressed || permit.submitted) Outcome.UNCERTAIN else Outcome.REFUSED
                } finally {
                    gate.finish(server to shown.id, permit)
                }
                port.pause(SETTLE_MS)
                val fresh = readOrNull { port.pane(shown.id) }
                val after = readOrNull { port.viewport(shown.id) }
                if (!ready(shown, fresh)) return@guarded Outcome.UNCERTAIN
                if (emptyBox(fresh!!, after)) return@guarded Outcome.CLEARED
                val still = draftOn(fresh, after) ?: return@guarded Outcome.UNCERTAIN
                // Backspace from the end leaves the start of what was there, shorter by at most the
                // keys sent (fewer if some were dropped). Anything else (the cursor mid-text, text
                // that was never shown, more deleted than sent, nothing deleted) stops it.
                // 2.1.2 (2.1.1 acceptance, D4): a round that ends at a space leaves a trailing space
                // the agent's screen doesn't show ("HD211 draft clear rc" less 8 is "HD211 draft ",
                // read back as "HD211 draft"), so the shown text alone looks one longer cut than was
                // sent. What is left is taken as [left] less k keys for the largest k in 1..count
                // whose text matches the screen once trailing whitespace is set aside on both sides:
                // the largest k assumes the fewest characters remain, so the next round never sends
                // more Backspaces than the box can hold.
                if (!still.bounded || still.menu) return@guarded Outcome.PARTIAL
                val seen = still.text.trimEnd()
                val removed = (count downTo 1).firstOrNull { it <= left.length && left.dropLast(it).trimEnd() == seen }
                    ?: return@guarded Outcome.PARTIAL
                left = left.dropLast(removed)
            }
            Outcome.PARTIAL
        }
    }

    private suspend fun guarded(
        server: String, shown: Pane, shownText: String, allowed: () -> Boolean, port: Port,
        act: suspend (Pane, ComposerDraft) -> Outcome,
    ): Outcome {
        val key = server to shown.id
        if (!hasStableOccupant(shown) || shownText.isBlank()) return Outcome.REFUSED
        fun valid() = allowed() && !gate.isClosing(key)
        val release = dispatch.tryAcquire(key) ?: return Outcome.REFUSED
        try {
            if (!valid()) return Outcome.REFUSED
            val fresh = port.pane(shown.id)
            if (!valid() || !ready(shown, fresh)) return Outcome.REFUSED
            val visible = port.viewport(shown.id)
            if (!valid()) return Outcome.REFUSED
            val now = draftOn(fresh!!, visible) ?: return if (emptyBox(fresh, visible)) Outcome.CHANGED else Outcome.REFUSED
            if (!now.bounded) return Outcome.REFUSED
            if (now.text != shownText) return Outcome.CHANGED
            // The occupant may have been replaced while the screen was read.
            val last = port.pane(shown.id)
            if (!valid() || !ready(shown, last)) return Outcome.REFUSED
            return act(last!!, now)
        } catch (c: CancellationException) {
            throw c
        } catch (_: ConnectionChanged) {
            return Outcome.REFUSED
        } catch (_: Exception) {
            return Outcome.REFUSED
        } finally {
            release()
        }
    }
}

/** What the chat says after Send draft or Clear draft (2.1.0). */
internal fun draftOutcomeText(outcome: PaneDraftAction.Outcome, agent: String, clear: Boolean): String = when (outcome) {
    PaneDraftAction.Outcome.SENT -> "Draft sent to $agent."
    PaneDraftAction.Outcome.CLEARED -> "Draft cleared from $agent's input box."
    PaneDraftAction.Outcome.CHANGED ->
        "${if (clear) "Not cleared" else "Not sent"}: the draft changed at the computer. Nothing was typed. Check the new text and try again."
    PaneDraftAction.Outcome.REFUSED ->
        "${if (clear) "Not cleared" else "Not sent"}: $agent isn't ready (a question, a scrolled-back screen, another send, or a screen that couldn't be read). Nothing was typed."
    PaneDraftAction.Outcome.PARTIAL ->
        "Some of the draft is still in $agent's input box (the cursor may not have been at the end). Enter was not pressed. Clear the rest in Terminal."
    PaneDraftAction.Outcome.NOT_SENDABLE ->
        if (clear) "Not cleared: a command or suggestion list shows in $agent's input box. Nothing was typed. Close it at the computer first."
        else "Not sent: Enter could pick a highlighted command or suggestion instead, or $agent isn't idle. Nothing was typed."
    PaneDraftAction.Outcome.UNCERTAIN ->
        if (clear) "Backspace was pressed, but the app couldn't confirm that $agent's input box is empty. Enter was not pressed. Check Terminal."
        else "Enter was pressed once, but the app couldn't confirm the draft went. Check the chat before trying again."
}

/**
 * Why Send draft is off for [draft] now, or null (2.1.0). Enter in a box that shows a command or
 * suggestion list, or whose draft starts a command ("/", "!") or ends in a "/" or "@" word, may
 * pick the highlighted entry (a /clear, say) rather than send the text. And only an idle agent
 * gets it, as with a relayed prompt.
 */
internal fun draftSendBlock(draft: ComposerDraft, status: Status?, agent: String): String? {
    val text = draft.text.trim()
    val last = text.split(Regex("""\s+""")).lastOrNull().orEmpty()
    return when {
        draft.menu || text.startsWith("/") || text.startsWith("!") || last.startsWith("/") || last.startsWith("@") ->
            "Send draft is off: Enter could pick a highlighted command or suggestion instead. Finish it or clear it at the computer."
        status != Status.IDLE && status != Status.DONE -> "Send draft is available when $agent is idle."
        else -> null
    }
}
