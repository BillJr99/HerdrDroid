package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.ConnectionChanged
import kotlinx.coroutines.CancellationException

/**
 * A confirmed voice message to a pane on the open server. It goes only to the occupant that was
 * read back to the user: the same pane, tab, agent, folder, terminal and agent session, checked
 * against a fresh snapshot immediately before dispatch (2.0.1 finding 1). The write holds the
 * pane's dispatch lane and a revocable permit (findings 2 and 4).
 */
internal class LocalPromptSend(private val dispatch: PaneDispatch, private val gate: WriteGate) {
    interface Port {
        /** A fresh read of the pane, or null when it can't be read. */
        suspend fun pane(id: String): Pane?
        /** The pane's visible screen, or null when it can't be read. */
        suspend fun viewport(id: String): String?
        /** A draft, question, queued message or other reason not to type into [fresh]. */
        fun blocked(fresh: Pane, viewport: String): Boolean
        /** One agent prompt that the transport starts only while [permit] is open. */
        suspend fun send(fresh: Pane, text: String, permit: WritePermit)
        /** 2.2.7: a message too long to type, put in the inbox as a checked file; throws when it can't. */
        suspend fun upload(name: String, bytes: ByteArray): UploadReceipt =
            throw UnsupportedOperationException("this server can't take a file")
    }

    enum class Outcome { SENT, REFUSED, NO_IDENTITY, CONNECTION_CHANGED, UNCERTAIN }

    class Result(val outcome: Outcome, val failure: Exception? = null)

    suspend fun send(server: String, expected: Occupant, text: String, allowed: () -> Boolean, port: Port): Result {
        val refused = Result(Outcome.REFUSED)
        val key = server to expected.pane
        if (!expected.stable) return Result(Outcome.NO_IDENTITY)
        fun valid() = allowed() && !gate.isClosing(key)
        fun ready(p: Pane?) = p != null && Occupant.of(p) == expected &&
            p.status in setOf(Status.IDLE, Status.DONE) && (p.scrollOffset ?: 0) == 0
        val release = dispatch.tryAcquire(key) ?: return refused
        val permit = gate.open(key) ?: run { release(); return refused }
        try {
            if (!valid()) return refused
            // 2.2.7: too long to type: a checked file in the inbox first, then every check below on a
            // fresh read, and one line pointing at it (pre-review L2: checked after the upload, not before).
            val outgoing = when (LongText.plan(text)) {
                is LongText.Plan.Single -> text
                is LongText.Plan.Attachment -> port.upload("agent-message.txt", text.toByteArray(Charsets.UTF_8)).let { r ->
                    SentFiles.remember(r.path, text); LongText.fileLine(r)
                }
            }
            if (!valid()) return refused
            val fresh = port.pane(expected.pane)
            if (!valid() || fresh == null || !ready(fresh)) return refused
            val visible = port.viewport(expected.pane)
            if (!valid() || visible.isNullOrBlank() || port.blocked(fresh, visible)) return refused
            // The occupant may have been replaced while the screen was read.
            val last = port.pane(expected.pane)
            if (!valid() || last == null || !ready(last)) return refused
            port.send(last, outgoing, permit)
            return Result(Outcome.SENT)
        } catch (c: CancellationException) {
            if (permit.submitted) return Result(Outcome.UNCERTAIN)
            throw c
        } catch (_: WriteRevoked) {
            return refused
        } catch (e: ConnectionChanged) {
            return Result(if (permit.submitted) Outcome.UNCERTAIN else Outcome.CONNECTION_CHANGED, e)
        } catch (e: Exception) {
            return Result(if (permit.submitted) Outcome.UNCERTAIN else Outcome.REFUSED, e)
        } finally {
            gate.finish(key, permit)
            release()
        }
    }
}
