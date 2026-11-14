package com.billmongan.herdrdroid.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 1.3.2: the key migration loses nothing, however it is interrupted (Grok's review of 1.3.1). The
 * seals here are stand-ins: a secret "old:<x>" re-seals to "v2:<x>", a file "OLD:<x>" to "HDv2<x>",
 * so every test can check that each chat, question history and password still holds its contents.
 */
class MigratorTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Bad : Exception("tag mismatch", javax.crypto.AEADBadTagException("tag mismatch"))
    private class Invalidated : Exception("key permanently invalidated")

    private var servers = mutableListOf<Server>()
    private val dir get() = tmp.root

    private fun server(id: String, pw: String?, key: String? = null) =
        Server(id = id, name = id, host = "h", port = 22, user = "u", herdrPath = "herdr", passwordCipher = pw, keyCipher = key)

    private fun file(name: String, body: String) = File(dir, name).apply { writeText("OLD:$body") }

    /** What a file or secret says, whichever way it is sealed; null if it's gone. */
    private fun plain(f: File): String? = if (!f.isFile) null else f.readText().let { t -> when {
        t.startsWith("HDv2") -> t.removePrefix("HDv2")
        t.startsWith("OLD:") -> t.removePrefix("OLD:")
        else -> "?$t"
    } }
    private fun plainSecret(s: String?) = s?.removePrefix("v2:")?.removePrefix("old:")

    private fun migrator(
        resealFile: (ByteArray) -> ByteArray = { b -> ("HDv2" + String(b).removePrefix("OLD:")).toByteArray() },
        resealSecret: (String) -> String = { "v2:" + it.removePrefix("old:") },
        replace: ((File, File) -> Unit)? = null,
        saveServer: (Server) -> Unit = { s -> servers.replaceAll { if (it.id == s.id) s else it } },
    ) = Migrator(
        servers = { servers.toList() }, saveServer = saveServer,
        isNewSecret = { it.startsWith("v2:") }, resealSecret = resealSecret,
        dir = dir, isNewFile = { String(it).startsWith("HDv2") }, resealFile = resealFile,
        unreadable = { e -> generateSequence(e) { it.cause }.any { it is javax.crypto.AEADBadTagException } },
        replace = replace ?: { t: File, f: File -> java.nio.file.Files.move(t.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); Unit },
    )

    private val chats = listOf("a.chat" to "chat A", "a.q" to "questions A", "b.chat" to "chat B", "c.q" to "questions C")

    private fun setUp() {
        servers = mutableListOf(server("s1", "old:pw1", "old:key1"), server("s2", "old:pw2"), server("s3", null))
        chats.forEach { (n, b) -> file(n, b) }
    }

    /** Every chat, question history and password is still there with its contents. */
    private fun assertNothingLost() {
        chats.forEach { (n, b) -> assertEquals(n, b, plain(File(dir, n))) }
        assertEquals("pw1", plainSecret(servers.first { it.id == "s1" }.passwordCipher))
        assertEquals("key1", plainSecret(servers.first { it.id == "s1" }.keyCipher))
        assertEquals("pw2", plainSecret(servers.first { it.id == "s2" }.passwordCipher))
        assertNull(servers.first { it.id == "s3" }.passwordCipher)
    }

    @Test fun aCleanRunReSealsEverythingAndIsComplete() {
        setUp()
        val r = migrator().run()
        assertTrue(r.stoppedAt, r.complete)
        assertEquals(3, r.secrets); assertEquals(4, r.files)
        assertNothingLost()
        assertTrue(chats.all { (n, _) -> File(dir, n).readText().startsWith("HDv2") })
        assertTrue(servers.all { s -> listOfNotNull(s.passwordCipher, s.keyCipher).all { it.startsWith("v2:") } })
    }

    @Test fun aRenameThatFailsAtAnyFileKeepsEveryFileAndStops() {
        for (failAt in chats.indices) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            setUp()
            var n = 0
            val r = migrator(replace = { t, f ->
                if (n++ == failAt) throw java.io.IOException("rename failed")
                java.nio.file.Files.move(t.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                Unit
            }).run()
            assertFalse("stopped at file $failAt", r.complete)
            assertNothingLost()
            // The copy that didn't replace its file is set beside it, never over it, and never a .chat or .q.
            assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".mig") })
            // The next unlock finishes it.
            val again = migrator().run()
            assertTrue(again.stoppedAt, again.complete)
            assertNothingLost()
        }
    }

    @Test fun aCrashAfterEachWriteLosesNothing() {
        // The process dies after the k-th file was replaced: the next one never starts.
        for (k in 0..chats.size) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            setUp()
            var done = 0
            val r = migrator(resealFile = { b ->
                if (done++ == k) throw IllegalStateException("the app was killed")
                ("HDv2" + String(b).removePrefix("OLD:")).toByteArray()
            }).run()
            if (k < chats.size) assertFalse(r.complete)
            assertNothingLost()
            assertTrue(migrator().run().complete)
            assertNothingLost()
        }
    }

    @Test fun aServerSaveThatFailsStopsWithEveryPasswordKept() {
        setUp()
        val r = migrator(saveServer = { throw java.io.IOException("disk full") }).run()
        assertFalse(r.complete)
        assertNothingLost()
        assertTrue(servers.all { s -> listOfNotNull(s.passwordCipher).all { it.startsWith("old:") } })
    }

    @Test fun aKeyInvalidatedForGoodStopsAndKeepsEverything() {
        setUp()
        val r = migrator(resealFile = { throw Invalidated() }).run()
        assertFalse(r.complete)
        assertNothingLost()
        // From the start again, the secrets' key invalidated this time.
        tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
        setUp()
        val r2 = migrator(resealSecret = { throw Invalidated() }).run()
        assertFalse(r2.complete)
        assertNothingLost()
    }

    @Test fun anItemTheOldKeyCantOpenIsSetAsideNotDeleted() {
        setUp()
        File(dir, "broken.chat").writeText("OLD:garbage")
        val r = migrator(resealFile = { b ->
            if (String(b) == "OLD:garbage") throw Bad()
            ("HDv2" + String(b).removePrefix("OLD:")).toByteArray()
        }).run()
        assertTrue(r.stoppedAt, r.complete)
        assertEquals(1, r.setAside)
        assertNothingLost()
        assertEquals("OLD:garbage", File(dir, "unreadable/broken.chat").readText())
    }

    @Test fun aStaleServerCopySavedDuringTheMigrationKeepsTheReSealedSecret() {
        val stored = server("s1", "v2:pw1", "v2:key1")
        // The app's copy from before the migration, saved again (a fingerprint update, say).
        val stale = server("s1", "old:pw1", "old:key1").copy(fingerprint = "SHA256:new")
        val merged = keepResealed(stale, stored)
        assertEquals("v2:pw1", merged.passwordCipher)
        assertEquals("v2:key1", merged.keyCipher)
        assertEquals("SHA256:new", merged.fingerprint)
        // A password typed after the migration, and a cleared one, go through.
        assertEquals("v2:new", keepResealed(stale.copy(passwordCipher = "v2:new"), stored).passwordCipher)
        assertNull(keepResealed(stale.copy(passwordCipher = null), stored).passwordCipher)
    }

    @Test fun theSecondPassRefusesWhenSomethingIsStillOld() {
        setUp()
        // A secret that the reseal "forgets" to change: the second pass must not call it complete.
        val r = migrator(resealSecret = { it }).run()
        assertFalse(r.complete)
        assertNothingLost()
    }
    @Test fun aCopyLeftByA131CrashIsTheFileAgainNotSetAside() {
        setUp()
        // 1.3.1 could stop after deleting a.chat and before renaming its new copy over it.
        File(dir, "a.chat").delete()
        File(dir, "a.chat.mig").writeText("HDv2chat A")
        // A leftover beside a file that is still there goes out of the way.
        File(dir, "b.chat.mig").writeText("HDv2half")
        val r = migrator().run()
        assertTrue(r.stoppedAt, r.complete)
        assertNothingLost()
        assertFalse(File(dir, "a.chat.mig").exists())
        assertTrue(File(dir, "b.chat.mig.stale").exists())
    }

    @Test fun aFolderThatCantBeListedStopsWithTheKeysKept() {
        setUp()
        val unlisted = object : File(dir.path) { override fun listFiles(): Array<File>? = null }
        val r = Migrator(
            servers = { servers.toList() }, saveServer = { s -> servers.replaceAll { if (it.id == s.id) s else it } },
            isNewSecret = { it.startsWith("v2:") }, resealSecret = { "v2:" + it.removePrefix("old:") },
            dir = unlisted, isNewFile = { String(it).startsWith("HDv2") }, resealFile = { it }, unreadable = { false },
        ).run()
        assertFalse(r.complete)
        assertNothingLost()
        // No folder at all is no chats: complete.
        val none = Migrator({ emptyList() }, {}, { true }, { it }, File(dir, "missing"), { true }, { it }, { false }).run()
        assertTrue(none.complete)
    }

    @Test fun aDifferentSecretTypedBeforeTheMigrationFinishesIsKept() {
        val stored = server("s1", "v2:old password")
        val typed = server("s1", "old:new password")
        val same = { a: String, b: String -> plainSecret(a) == plainSecret(b) }
        assertEquals("old:new password", keepResealed(typed, stored, oldKeysGone = false, same = same).passwordCipher)
        // The same secret, from a stale copy, keeps the re-sealed one.
        assertEquals("v2:old password", keepResealed(server("s1", "old:old password"), stored, oldKeysGone = false, same = same).passwordCipher)
        // With the old keys gone, an old-way secret can only be stale.
        assertEquals("v2:old password", keepResealed(typed, stored, oldKeysGone = true, same = same).passwordCipher)
    }
}
