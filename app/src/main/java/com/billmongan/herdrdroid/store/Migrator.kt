package com.billmongan.herdrdroid.store

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Re-seals the saved secrets and the kept chat files under the new keys (1.3.2), written so that
 * nothing a user has can be lost at any step (Grok's review of 1.3.1 found four ways it could):
 *  - a file is replaced by writing the new copy beside it and making it the file with one atomic
 *    rename; if that rename fails, the original stays and the pass stops;
 *  - a server record is saved, synchronously, only after every one of its secrets was re-sealed;
 *  - an item the old key can't open either (its seal fails to authenticate) is set aside, never
 *    deleted: a file goes to the unreadable/ folder, a secret stays as it is;
 *  - anything else that goes wrong (a Keystore or disk error) stops the pass with the old keys kept;
 *  - [Result.complete] is true only after a second pass found every secret and file under the new
 *    keys, and only then may the caller delete the old keys.
 * The caller holds the vault's write lock for the whole run, so no save happens in between.
 * Pure Kotlin: everything Android-specific comes in as functions, so the unit tests can stop it at
 * any step.
 */
class Migrator(
    private val servers: () -> List<Server>,
    private val saveServer: (Server) -> Unit,
    private val isNewSecret: (String) -> Boolean,
    private val resealSecret: (String) -> String,
    private val dir: File,
    private val isNewFile: (ByteArray) -> Boolean,
    private val resealFile: (ByteArray) -> ByteArray,
    /** True for "this seal can't be opened with its key" (AES-GCM authentication failed). */
    private val unreadable: (Throwable) -> Boolean,
    /** Moves the new copy over the file in one step; throws when it can't. */
    private val replace: (File, File) -> Unit = { tmp, f -> Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) },
) {
    data class Result(
        val complete: Boolean,
        val secrets: Int = 0,
        val files: Int = 0,
        val setAside: Int = 0,
        val stoppedAt: String? = null,
    )

    private fun kept(f: File) = f.isFile && (f.name.endsWith(".chat") || f.name.endsWith(".q"))

    fun run(): Result {
        var secrets = 0
        var files = 0
        var aside = 0
        val deadSecrets = mutableSetOf<String>()
        // No folder means no kept chats; a folder that can't be listed stops the pass (keys kept).
        if (dir.exists() && dir.listFiles() == null) return Result(false, stoppedAt = "listing ${dir.name}")
        // A copy left by a pass that stopped: beside its file it is only a leftover and goes out of the
        // way; with its file gone (1.3.1 deleted the file before the rename) it is the only copy and
        // becomes the file again. A copy cut short then fails its seal and is set aside below.
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".mig") }?.forEach { mig ->
            val real = File(dir, mig.name.removeSuffix(".mig"))
            val ok = if (real.exists()) mig.renameTo(File(dir, mig.name + ".stale")) else mig.renameTo(real)
            if (!ok) return Result(false, stoppedAt = "recovering ${mig.name}")
        }
        for (srv in servers()) {
            fun re(c: String?): String? {
                if (c == null || isNewSecret(c)) return c
                return try {
                    resealSecret(c).also { secrets++ }
                } catch (e: Exception) {
                    if (!unreadable(e)) throw StopAt("${srv.label}: ${e.javaClass.simpleName}", e)
                    deadSecrets += c; aside++; c
                }
            }
            val upd = try {
                srv.copy(passwordCipher = re(srv.passwordCipher), keyCipher = re(srv.keyCipher), keyPassCipher = re(srv.keyPassCipher))
            } catch (s: StopAt) {
                return Result(false, secrets, files, aside, s.message)
            }
            if (upd != srv) runCatching { saveServer(upd) }.onFailure { return Result(false, secrets, files, aside, "saving ${srv.label}: ${it.javaClass.simpleName}") }
        }
        for (f in dir.listFiles()?.filter(::kept).orEmpty()) {
            val bytes = runCatching { f.readBytes() }.getOrElse { return Result(false, secrets, files, aside, "reading ${f.name}: ${it.javaClass.simpleName}") }
            if (isNewFile(bytes)) continue
            val sealed = try {
                resealFile(bytes)
            } catch (e: Exception) {
                if (!unreadable(e)) return Result(false, secrets, files, aside, "${f.name}: ${e.javaClass.simpleName}")
                // Kept, out of the way: never the user's file deleted.
                val bin = File(dir, "unreadable").apply { mkdirs() }
                if (!f.renameTo(File(bin, f.name))) return Result(false, secrets, files, aside, "setting aside ${f.name}")
                aside++
                continue
            }
            val tmp = File(dir, f.name + ".mig")
            try {
                tmp.writeBytes(sealed)
                replace(tmp, f)
            } catch (e: Exception) {
                // The original is still the file; the new copy is only a leftover.
                runCatching { tmp.renameTo(File(dir, tmp.name + ".stale")) }
                return Result(false, secrets, files, aside, "replacing ${f.name}: ${e.javaClass.simpleName}")
            }
            files++
        }
        // The second pass: every secret and file under the new keys, or set aside.
        val oldSecretLeft = servers().any { s ->
            listOfNotNull(s.passwordCipher, s.keyCipher, s.keyPassCipher).any { !isNewSecret(it) && it !in deadSecrets }
        }
        val listed = dir.listFiles()
        if (dir.exists() && listed == null) return Result(false, secrets, files, aside, "listing ${dir.name} again")
        val oldFileLeft = listed.orEmpty().any { f -> (kept(f) && runCatching { !isNewFile(f.readBytes()) }.getOrDefault(true)) || (f.isFile && f.name.endsWith(".mig")) }
        if (oldSecretLeft || oldFileLeft) return Result(false, secrets, files, aside, "second pass found data still under the old keys")
        return Result(true, secrets, files, aside)
    }

    private class StopAt(message: String, cause: Throwable) : Exception(message, cause)
}
