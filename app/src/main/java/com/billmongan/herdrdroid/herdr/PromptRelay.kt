package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.ConnectionChanged
import kotlinx.coroutines.CancellationException

internal interface PromptRelayPort {
    val owner: Any get() = this
    val connectionIdentity: Any?
    suspend fun pane(id: String, connection: Any): Pane?
    suspend fun hasWork(pane: Pane, connection: Any): Boolean
    suspend fun viewport(id: String, connection: Any): String
    suspend fun send(pane: Pane, text: String, connection: Any)
    suspend fun noticeHistory(pane: Pane, cursor: String, connection: Any):
        com.billmongan.herdrdroid.transcript.TranscriptRead? = null

    /**
     * Sends only if [permit] is still open when the write starts. A real transport claims it after
     * it holds the connection; this default claims it immediately before [send].
     */
    suspend fun send(pane: Pane, text: String, connection: Any, permit: WritePermit) {
        permit.admit()
        send(pane, text, connection)
    }

    suspend fun sendBoardRequest(pane: Pane, text: String, viewport: String, connection: Any, permit: WritePermit) {
        throw CardCheckFailed()
    }

    /**
     * 2.2.2: the forced relay's write, which may go to a working agent. The same permitted send as
     * above, by the same route (herdr's agent prompt, never --force); only the transport's own
     * idle requirement is relaxed to allow herdr's working status.
     */
    suspend fun sendForced(pane: Pane, text: String, connection: Any, permit: WritePermit) = send(pane, text, connection, permit)

    /**
     * 2.2.7: puts [bytes] in that computer's inbox as [name], checked by size and SHA-256, for a
     * message too long to type ([LongText]). A port that can't throws, and the message is refused.
     */
    suspend fun upload(name: String, bytes: ByteArray, connection: Any): UploadReceipt =
        throw UnsupportedOperationException("this server can't take a file")
}

/** Who is in a pane: same pane, tab, workspace, agent, folder, terminal and agent session. */
internal data class Occupant(
    val pane: String, val tab: String, val workspace: String, val agent: String?, val cwd: String,
    val terminalId: String?, val sessionId: String?,
) {
    /** Can be told apart from a replacement in the same place (2.0.1 finding 1). */
    val stable: Boolean get() = !agent.isNullOrBlank() && !(sessionId.isNullOrBlank() && terminalId.isNullOrBlank())

    companion object {
        fun of(p: Pane) = Occupant(p.id, p.tabId, p.workspaceId, p.agent, p.cwd, p.terminalId, p.sessionId)
    }
}

internal fun sameOccupant(a: Pane, b: Pane) = Occupant.of(a) == Occupant.of(b)

internal fun hasStableOccupant(p: Pane) = Occupant.of(p).stable

internal enum class RelayResult(val feedback: String) {
    SENT("Message sent."),
    REFUSED("Not sent. Check that the destination is still connected and idle, with no question, draft, or queued message."),
    UNCERTAIN("Could not confirm delivery. Check the destination chat before trying again."),
    /** 2.1.0: text is typed in the destination's input box; its chat shows it with Send draft and Clear draft. */
    DRAFT("Not sent. Text is already typed in the destination's input box at the computer. Open that agent's chat to send or clear the draft, then try again."),
}

/**
 * 2.2.1: why a relayed message was not sent, so the card can say which check failed (Bill: "it pops
 * up a card saying that the message was not sent and nothing happens"). Nothing was typed in any of
 * these cases. [transient] refusals can pass on a later try (the destination busy, a link down);
 * the others cannot pass for this proposal (a different session in a pane, a bad message).
 */
internal enum class RelayRefusal(val why: String, val transient: Boolean) {
    TEXT("the message is empty or holds control characters", false),
    STOPPED("the send was stopped before it went (the chat, the app or the approval changed)", true),
    LINK("the connection to that server changed or is not ready", true),
    CLOSING("that pane is being closed", true),
    LANE("HerdrDroid sees it busy, or has a queued message, an answer or a draft of its own waiting for it", true),
    IN_FLIGHT("another message to that pane is being sent", true),
    UNREACHABLE("that agent is not reachable now (its server is not connected, or the pane is gone)", true),
    REPLACED("a different session is now in that pane", false),
    NOT_IDLE("it is not idle (herdr can't say it is idle or done)", true),
    /** 2.2.2: herdr says working, or the footer shows "esc to interrupt". The only reason Force Send may pass, and only for Claude Code. */
    WORKING("it is working on a turn", true),
    /** 2.2.2: the pane is scrolled back at the computer, so its input box can't be checked. */
    SCROLLED("it is scrolled back at the computer, so its input box can't be checked", true),
    BACKGROUND("it is running background work that typing could interrupt, or that work could not be checked", true),
    USAGE("a usage panel is open in it", true),
    QUESTION("a question or dialog is open in it", true),
    NO_INPUT("its input box is not on screen, so it can't be checked for a draft", true),
    DRAFT("text is already typed in its input box at the computer", true),
    FAILED("its pane or screen couldn't be read", true),
    AGENT_NOT_FOUND("Herdr did not recognize that pane as an agent prompt target", true),
    SOURCE_CHANGED("this chat's pane is no longer open as it was when the proposal came", true),
    SOURCE_REPLACED("a different session is now in this chat's pane", false),
    /** 2.2.7: a message too long to type couldn't be put on that computer whole as a file. */
    UPLOAD("the long message couldn't be put on that computer whole as a file", true),
}

/**
 * 2.2.1: agents whose input box takes typed text while their own background work runs. Claude Code
 * keeps its composer live while background shells, tasks or helpers run ("3 shells" in its footer,
 * "Waiting for 2 background agents to finish"), and a message typed then is an ordinary prompt. For
 * these, background work alone does not hold a relayed message: herdr's own status must still be
 * idle or done, the box empty and no question open. Every other agent still waits for its
 * background work, where typing could interrupt it.
 */
internal fun takesInputDuringBackgroundWork(agent: String?): Boolean = agent.equals("claude", true)

/**
 * 2.2.2: agents a cross-server message may be forced to while they work: those that queue text
 * typed during a turn and take it after the current step. Claude Code only, the same agents as
 * [takesInputDuringBackgroundWork]. For any other agent a forced send is an ordinary one.
 */
internal fun takesForcedInput(agent: String?): Boolean = takesInputDuringBackgroundWork(agent)

/** 2.2.2: herdr's agent prompt refuses an agent it calls blocked "with agent_blocked before any input is sent" (its --help). */
internal fun refusedBeforeInput(e: Exception): Boolean = e is HerdrError && "agent_blocked" in e.message.orEmpty()

/** Only a structured reply to the prompt command proves a missing target before input. */
internal fun promptTargetNotFound(e: Exception): Boolean = e is HerdrError &&
    runCatching {
        org.json.JSONObject(e.message.orEmpty()).let {
            it.optString("id") == "cli:agent:prompt" && it.optJSONObject("error")?.optString("code") == "agent_not_found"
        }
    }.getOrDefault(false)


/**
 * A watched pane, after the app's work sweep ([withBusy]), that a relay may write to: idle or done
 * with no background work, or with only background work in an agent that takes input meanwhile.
 * The relay rechecks herdr's own status on a fresh read before anything is typed.
 */
internal fun idleForRelay(p: Pane): Boolean =
    if (p.backgroundBusy) takesInputDuringBackgroundWork(p.agent)
    else p.status == Status.IDLE || p.status == Status.DONE

internal class PromptRelay(
    private val dispatch: PaneDispatch,
    private val portFor: (String) -> PromptRelayPort?,
    private val gate: WriteGate = WriteGate(),
    /**
     * 2.2.2: the lane alone (HerdrDroid's own queued message, answer or draft for that pane),
     * without the watched pane's idle state. A forced send checks this one instead of
     * [clearToSend]; when it is not given, a forced send checks [clearToSend].
     */
    private val laneClear: ((String, String) -> Boolean)? = null,
    private val clearToSend: (String, String) -> Boolean,
) {
    data class Target(val server: String, val pane: Pane, val port: PromptRelayPort, val connection: Any)

    fun isCurrent(target: Target) = portFor(target.server)?.owner === target.port.owner &&
        target.port.connectionIdentity === target.connection

    suspend fun bind(server: String, selected: Pane): Target? {
        if (!hasStableOccupant(selected)) return null
        val port = portFor(server) ?: return null
        val connection = port.connectionIdentity ?: return null
        return try {
            val fresh = port.pane(selected.id, connection) ?: return null
            Target(server, fresh, port, connection).takeIf { sameOccupant(selected, fresh) && isCurrent(it) }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [force] (2.2.2, Force Send on a cross-server card) skips only the checks that the destination
     * is idle and has no background work: herdr's working status, background busy, the work count
     * and the footer's "esc to interrupt". Every other check still runs on the fresh read right
     * before typing: the same occupant, the link, the lane and write gate, the message text, no
     * panel, no question or dialog, the input line on screen and empty. herdr's own --force is
     * never used; the text is typed through the same guarded send as any other.
     */
    suspend fun send(
        target: Target, text: String, authorizedWrite: WritePermit = WritePermit(),
        refused: (RelayRefusal) -> Unit = {}, force: Boolean = false, boardRequest: Boolean = false, beforeBoardWrite: () -> Boolean = { true },
        allowed: () -> Boolean,
    ): RelayResult {
        val key = target.server to target.pane.id
        // Force only ever applies to an agent that queues typed input (Claude Code); for any other it is an ordinary send.
        val force = force && !boardRequest && takesForcedInput(target.pane.agent)
        val lane = if (force) laneClear ?: clearToSend else clearToSend
        fun invalid(): RelayRefusal? = when {
            !allowed() -> RelayRefusal.STOPPED
            !isCurrent(target) -> RelayRefusal.LINK
            gate.isClosing(key) -> RelayRefusal.CLOSING
            !lane(target.server, target.pane.id) -> RelayRefusal.LANE
            else -> null
        }
        fun refuse(why: RelayRefusal): RelayResult {
            refused(why)
            return if (why == RelayRefusal.DRAFT) RelayResult.DRAFT else RelayResult.REFUSED
        }
        val clean = text.isNotBlank() && text.none { it != '\n' && it != '\t' && it.isISOControl() }
        if (!clean) return refuse(RelayRefusal.TEXT)
        invalid()?.let { return refuse(it) }
        val release = dispatch.tryAcquire(key) ?: return refuse(RelayRefusal.IN_FLIGHT)
        val permit = gate.open(key, authorizedWrite) ?: run { release(); return refuse(RelayRefusal.CLOSING) }
        try {
            val port = target.port
            // 2.2.7: a message too long to type goes as a checked file in that computer's inbox, and
            // the agent is sent one line pointing at it, under the same checks and approval.
            val outgoing = when (LongText.plan(text)) {
                is LongText.Plan.Single -> text
                is LongText.Plan.Attachment -> {
                    if (boardRequest) return refuse(RelayRefusal.TEXT)
                    invalid()?.let { return refuse(it) }
                    val r = try { port.upload("agent-message.txt", text.toByteArray(Charsets.UTF_8), target.connection) }
                        catch (c: CancellationException) { throw c } catch (_: Exception) { return refuse(RelayRefusal.UPLOAD) }
                    SentFiles.remember(r.path, text)
                    LongText.fileLine(r)
                }
            }
            // 2.2.1: background work alone does not hold an agent that takes input meanwhile.
            val waitsForBackground = boardRequest || !takesInputDuringBackgroundWork(target.pane.agent)
            var notIdle: RelayRefusal = RelayRefusal.UNREACHABLE
            suspend fun idlePane(): Pane? {
                val p = port.pane(target.pane.id, target.connection)
                // 2.2.2: each reason on its own, so only a working agent is ever offered Force Send.
                notIdle = when {
                    p == null -> RelayRefusal.UNREACHABLE
                    !sameOccupant(target.pane, p) -> RelayRefusal.REPLACED
                    // Scrolled back hides the input line, so even a forced send waits for the bottom.
                    p.scrollOffset != 0 -> RelayRefusal.SCROLLED
                    // Never to an agent waiting on you, forced or not.
                    p.status == Status.NEEDS_YOU -> RelayRefusal.QUESTION
                    p.status == Status.WORKING -> if (force) null else RelayRefusal.WORKING
                    p.status !in setOf(Status.IDLE, Status.DONE) -> RelayRefusal.NOT_IDLE
                    else -> null
                } ?: if (p!!.backgroundBusy && waitsForBackground) RelayRefusal.BACKGROUND else return p
                return null
            }
            /** Every screen check; a forced send runs them twice, the second time right before typing. */
            fun screenRefusal(viewport: String, agent: String?): RelayRefusal? {
                if (dismissibleUsageFrame(agent, viewport) != null ||
                    (agent in setOf("grok", "agy") && usageDialogHint(agent, viewport)) ||
                    // 2.2.1: any informational panel, even one only partly identified, is never typed into.
                    infoPanelOn(agent, viewport) != null)
                    return RelayRefusal.USAGE
                if (detectPrompt(forPhone(viewport), false, agent) != null) return RelayRefusal.QUESTION
                // 2.2.2: a working agent can raise a dialog mid-turn; a forced send also checks the dialog footers.
                if ((force || boardRequest) && (showsQuestionDialog(stripAnsi(viewport)) || claudePermissionFooterAtBottom(viewport))) return RelayRefusal.QUESTION
                // 2.2.1: the screen says a turn is running ("esc to interrupt" under the box), whatever herdr says.
                if (!force && composerFooterSaysWorking(viewport)) return RelayRefusal.WORKING
                // 2.2.7: a blank Oh My Pi or Copilot box counts as empty: the prompt clears it first ([Herdr.sendMessage]).
                val line = inputLineOf(viewport, agent, menuWrite = !boardRequest)
                // A draft of several rows whose first row is empty is a draft too.
                val draft = composerDraftOf(viewport, agent) != null
                if (!line.isNullOrEmpty() || draft) return RelayRefusal.DRAFT
                if (line == null) return RelayRefusal.NO_INPUT
                return null
            }
            invalid()?.let { return refuse(it) }
            val fresh = idlePane() ?: return refuse(notIdle)
            invalid()?.let { return refuse(it) }
            if (waitsForBackground && port.hasWork(fresh, target.connection)) return refuse(RelayRefusal.BACKGROUND)
            invalid()?.let { return refuse(it) }
            val viewport = port.viewport(fresh.id, target.connection)
            invalid()?.let { return refuse(it) }
            screenRefusal(viewport, fresh.agent)?.let { return refuse(it) }
            // This recheck narrows, but does not eliminate, the server-side read/write race.
            val ready = idlePane() ?: return refuse(notIdle)
            invalid()?.let { return refuse(it) }
            if (force) {
                // 2.2.2: a working agent can open a question or approval after the first read, so a
                // forced send reads the screen again right before typing and repeats every check.
                val again = port.viewport(ready.id, target.connection)
                invalid()?.let { return refuse(it) }
                screenRefusal(again, ready.agent)?.let { return refuse(it) }
                port.sendForced(ready, outgoing, target.connection, permit)
            } else if (boardRequest) {
                val again = port.viewport(ready.id, target.connection)
                invalid()?.let { return refuse(it) }
                screenRefusal(again, ready.agent)?.let { return refuse(it) }
                if (!beforeBoardWrite()) return refuse(RelayRefusal.STOPPED)
                port.sendBoardRequest(ready, outgoing, again, target.connection, permit)
            } else port.send(ready, outgoing, target.connection, permit)
            return RelayResult.SENT
        } catch (c: CancellationException) {
            // Cancelled after the write started: it may have arrived, so it is reported and never
            // retried. Cancelled before: nothing was sent and the cancellation goes on.
            if (permit.submitted) return RelayResult.UNCERTAIN
            throw c
        } catch (_: WriteRevoked) {
            return refuse(RelayRefusal.STOPPED)
        } catch (_: ConnectionChanged) {
            return if (permit.submitted) RelayResult.UNCERTAIN else refuse(RelayRefusal.LINK)
        } catch (e: Exception) {
            if (boardRequest && (e is CardChanged || e is CardCheckFailed || e is OccupantChanged))
                return refuse(RelayRefusal.FAILED)
            // 2.2.2: herdr's agent_blocked is decided before any input is sent, so nothing was typed.
            if (refusedBeforeInput(e)) return refuse(RelayRefusal.QUESTION)
            if (promptTargetNotFound(e)) return refuse(RelayRefusal.AGENT_NOT_FOUND)
            return if (permit.submitted) RelayResult.UNCERTAIN else refuse(RelayRefusal.FAILED)
        } finally {
            gate.finish(key, permit)
            release()
        }
    }
}
