package com.billmongan.herdrdroid.herdr

import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2.2.7 Send a secret (Bill, 2026-11-23: "a string or file that I'd like to send to the agent but
 * not through the chat pane ... it would return just the path"). A separate path from the chat's
 * attach ([Herdr.uploadVerified] stays as it was): the secret goes over the SSH command's stdin
 * only, into ~/.herdrdroid/secrets (0700) as a 0600 file, and only its path comes back for the
 * message box. The command never carries the secret, the log carries only the byte count and the
 * result, and nothing is kept on the phone. HerdrDroid never deletes these files.
 */
object SecretDrop {
    const val DIR = ".herdrdroid/secrets"
    /** The line before the results, so text a shell prints at login is skipped. */
    const val MARKER = "@@herdrdroid-secret@@"
    /** A secret is small; a file over this is refused before anything is sent. */
    const val MAX_BYTES = 5 * 1024 * 1024

    private val random = SecureRandom()

    /** The neutral label Bill typed (or a picked file's name), as a safe file-name part; null when nothing is left. */
    fun label(raw: String?): String? =
        raw?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.trim('_', '.', '-')?.take(60)?.ifBlank { null }

    /**
     * The file's name: a time stamp and a random part, then the label. Text without a label (or
     * whose label has no extension) ends in ".txt". Never anything taken from the secret itself.
     */
    fun fileName(label: String?, text: Boolean, stamp: String = stamp(), rand: String = rand()): String {
        val l = label(label)
        val base = "$stamp-$rand" + (l?.let { "-$it" } ?: "")
        return if (text && (l == null || !l.contains('.'))) "$base.txt" else base
    }

    fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun rand(): String = ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * The remote command for one secret named [name]. umask 077 first, so the folder and the
     * temporary file are private from the start; a folder that is a link is refused; the folder
     * is set to 0700 and the file to 0600; set -C and mv -n never write over an existing file.
     * 2.2.7 (Codex's review): the temporary file gets its final name only once it holds all [size]
     * bytes, so a link cut mid-upload (cat then sees an early end and exits 0) leaves only the
     * ".part" file, never a partial secret under the name a message would point at.
     * The results (path, size, SHA-256, the file's mode, the folder's mode) follow [MARKER].
     */
    fun command(name: String, size: Int): String {
        require(size >= 0) { "negative size" }
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "unsafe secret file name" }
        val d = "\"\$HOME\"/${shq(DIR)}"
        val f = "$d/${shq(name)}"
        val t = "$d/${shq("$name.part")}"
        fun mode(p: String) = "{ stat -c %a $p 2>/dev/null || stat -f %Lp $p; }"
        return "umask 077; " +
            "if [ -L \"\$HOME\"/.herdrdroid ] || [ -L $d ]; then echo 'The secrets folder is a link, so nothing was saved.' >&2; exit 4; fi; " +
            "mkdir -p $d && chmod 700 $d && set -C && cat > $t && " +
            "{ [ \$(wc -c < $t) -eq $size ] || { echo 'Only part of it arrived, so it was not saved.' >&2; exit 5; }; } && { [ ! -e $f ] && mv -n $t $f && [ ! -e $t ]; } && chmod 600 $f && " +
            "printf '%s\\n' $MARKER $f && wc -c < $f && " +
            "{ sha256sum $f 2>/dev/null || shasum -a 256 $f 2>/dev/null || echo nosum; } | cut -d' ' -f1 && " +
            "${mode(f)} && ${mode(d)}"
    }

    /** What the computer said it has. */
    data class Reply(val path: String, val size: Long?, val sha256: String?, val fileMode: String?, val dirMode: String?)

    fun parse(out: String): Reply? {
        val lines = out.lines().map { it.trim() }
        val at = lines.lastIndexOf(MARKER)
        if (at < 0) return null
        return Reply(
            lines.getOrNull(at + 1).orEmpty(), lines.getOrNull(at + 2)?.toLongOrNull(),
            lines.getOrNull(at + 3)?.lowercase(), lines.getOrNull(at + 4), lines.getOrNull(at + 5),
        )
    }

    /** The command's runner: [stdin] is the secret for the write, null for setting a bad copy aside. */
    fun interface Run {
        suspend fun run(command: String, stdin: ByteArray?): com.billmongan.herdrdroid.ssh.ExecResult
    }

    /**
     * Saves [bytes] as [name] in the secrets folder and returns the checked receipt. A size or
     * checksum mismatch is set aside (kept 0600, never deleted) and written once more under a new
     * name; wrong permissions are refused with the path, so Bill can remove it. Logs only the byte
     * count, the try and the result.
     */
    suspend fun upload(label: String?, text: Boolean, bytes: ByteArray, run: Run, max: Int = MAX_BYTES): UploadReceipt {
        if (bytes.isEmpty()) throw UploadFailed("there is nothing to save", true)
        if (bytes.size > max) throw UploadFailed("it is over ${max / (1024 * 1024)} MB", true)
        val want = sha256Hex(bytes)
        var last: String? = null
        for (attempt in 1..2) {
            val name = fileName(label, text)
            val r = run.run(command(name, bytes.size), bytes)
            if (r.exit != 0) {
                val err = r.err.trim().lines().lastOrNull { it.isNotBlank() }.orEmpty()
                com.billmongan.herdrdroid.diag.DiagLog.log("send", "secret bytes=${bytes.size} try=$attempt result=failed exit=${r.exit}")
                throw UploadFailed(err.ifBlank { "saving it failed (exit ${r.exit})" }, true)
            }
            val reply = parse(r.out)
            val noSum = reply?.sha256 == "nosum"
            val whole = reply != null && reply.path.startsWith("/") && reply.size == bytes.size.toLong() && (noSum || reply.sha256 == want)
            val private = reply != null && reply.fileMode == "600" && reply.dirMode == "700"
            com.billmongan.herdrdroid.diag.DiagLog.log("send", "secret bytes=${bytes.size} try=$attempt verify=${
                if (!whole) "mismatch" else if (!private) "mode" else if (noSum) "size-only" else "ok"}")
            if (whole && !private) throw SecretNotPrivate(reply!!.path, reply.fileMode, reply.dirMode)
            if (whole) return UploadReceipt(reply!!.path, bytes.size.toLong(), if (noSum) "" else want)
            last = if (reply?.size != bytes.size.toLong()) "the computer has ${reply?.size ?: "unknown"} of ${bytes.size} bytes" else "its checksum didn't match"
            val f = "\"\$HOME\"/${shq("$DIR/$name")}"
            runCatching { run.run("mv -n $f \"\$HOME\"/${shq("$DIR/$name.failed")} 2>/dev/null; true", null) }
        }
        throw UploadMismatch(last ?: "it couldn't be checked")
    }

    /**
     * [path] inserted into [text] at the caret ([at], clamped), with a space on each side where
     * the text has none, and the caret after it.
     */
    fun insertAt(text: String, at: Int, path: String): Pair<String, Int> {
        val i = at.coerceIn(0, text.length)
        val before = text.substring(0, i)
        val after = text.substring(i)
        val lead = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
        val trail = if (after.firstOrNull()?.isWhitespace() == true) "" else " "
        val out = before + lead + path + trail + after
        return out to (before.length + lead.length + path.length + trail.length)
    }

    /** [path] at the end of another pane's draft. */
    fun appendTo(draft: String, path: String): String = if (draft.isBlank()) "$path " else draft.trimEnd() + " " + path + " "
}

/** 2.2.7: the secret arrived whole, but the computer didn't keep it private; the path isn't put in the chat. */
class SecretNotPrivate(val path: String, fileMode: String?, dirMode: String?) :
    Exception("it was saved as $path, but its permissions are ${fileMode ?: "unknown"} (folder ${dirMode ?: "unknown"}), not 600 (folder 700); remove it on the computer")

/**
 * One pane a secret can go to: [serverId] and [paneId], with the names shown in the dialog.
 * 2.2.7 (Codex's review): [pane] is the pane as the dialog listed it, so the save and the path go
 * only to that occupant (its agent, tab, terminal and session), not to whatever took its id since.
 */
data class SecretTarget(val serverId: String, val paneId: String, val serverLabel: String, val paneLabel: String, val pane: Pane? = null) {
    /** [p] is this target's pane with the same occupant (any pane with its id when none was listed). */
    internal fun matches(p: Pane?): Boolean = p != null && p.id == paneId && (pane == null || sameOccupant(pane, p))
}

/** How a secret ended: saved (and where its path went) or refused (nothing inserted). */
sealed class SecretOutcome {
    /** [live]: in the open message box; [draft]: in that pane's draft; neither: the pane went away after the save. */
    data class Saved(val target: SecretTarget, val path: String, val live: Boolean, val draft: Boolean) : SecretOutcome()
    data class Refused(val why: String) : SecretOutcome()
}

/**
 * 2.2.7: sends one secret to [SecretTarget]'s server and puts its path into that pane's message
 * box: the open one at the caret, any other as its draft. A target that is gone (or a server not
 * connected) before the save gets nothing saved and nothing inserted.
 *
 * 2.2.7 (Codex's review): the whole send is bound to one SSH connection ([Port.bind]): the pane
 * is read fresh on it before the save, the save runs only on it (a replacement refuses it), and
 * the pane is read on it again before the path goes in. A pane whose occupant changed, or that
 * can't be read back, gets no path. The send counts as one in flight on that connection, so the
 * small-packets fallback doesn't reset it mid-upload.
 */
class SecretSender(private val port: Port) {
    /** One connection: its runner, a fresh read of a pane on it, and its send-lane count. */
    class Bound(
        val run: SecretDrop.Run,
        val pane: suspend (String) -> Pane?,
        val begin: () -> Unit = {},
        val end: () -> Unit = {},
    )

    interface Port {
        /** The pane is still listed on its server, with the same occupant. */
        fun present(t: SecretTarget): Boolean
        /** The connected server's current connection, or null when it isn't connected now. */
        fun bind(serverId: String): Bound?
        /** The pane is the one open on screen now. */
        fun isOpen(t: SecretTarget): Boolean
        fun insertLive(t: SecretTarget, path: String)
        fun appendDraft(t: SecretTarget, path: String)
    }

    private suspend fun stillThere(b: Bound, t: SecretTarget): Boolean = try {
        t.matches(b.pane(t.paneId))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    suspend fun send(t: SecretTarget, label: String?, text: Boolean, bytes: ByteArray): SecretOutcome {
        try {
            if (!port.present(t)) return SecretOutcome.Refused("${t.paneLabel} is no longer open on ${t.serverLabel}, so nothing was saved.")
            val bound = port.bind(t.serverId) ?: return SecretOutcome.Refused("${t.serverLabel} isn't connected, so nothing was saved.")
            bound.begin()
            try {
                if (!stillThere(bound, t) || !port.present(t)) {
                    return SecretOutcome.Refused("${t.paneLabel} on ${t.serverLabel} changed or couldn't be checked, so nothing was saved.")
                }
                val receipt = try {
                    SecretDrop.upload(label, text, bytes, bound.run)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: com.billmongan.herdrdroid.ssh.ConnectionChanged) {
                    return SecretOutcome.Refused("The connection to ${t.serverLabel} changed, so nothing was saved.")
                } catch (e: Exception) {
                    return SecretOutcome.Refused("Couldn't save the secret on ${t.serverLabel}: ${e.message ?: e.javaClass.simpleName}. Nothing went into the message.")
                }
                val there = stillThere(bound, t)
                return when {
                    there && port.isOpen(t) && port.present(t) -> { port.insertLive(t, receipt.path); SecretOutcome.Saved(t, receipt.path, live = true, draft = false) }
                    there && port.present(t) -> { port.appendDraft(t, receipt.path); SecretOutcome.Saved(t, receipt.path, live = false, draft = true) }
                    else -> SecretOutcome.Saved(t, receipt.path, live = false, draft = false)
                }
            } finally {
                bound.end()
            }
        } finally {
            bytes.fill(0)
        }
    }
}

/** The one-line notice for an outcome. */
fun secretNotice(o: SecretOutcome): String = when (o) {
    is SecretOutcome.Refused -> o.why
    is SecretOutcome.Saved -> "Saved on ${o.target.serverLabel} as ${o.path}; only the path goes in the chat." + when {
        o.live -> ""
        o.draft -> " It is in ${o.target.paneLabel}'s message box, not sent."
        else -> " ${o.target.paneLabel} closed or changed, so it went into no message."
    }
}
