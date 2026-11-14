package com.billmongan.herdrdroid.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 1.4.1 (from a code review): each kept chat file is tied to its own pane by associated data, has
 * a collision-resistant name, and a file under the 1.4.0 name moves to the new name and seal.
 */
class ChatStoreBindingTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A stand-in for AES-GCM with associated data: the data is written in front and must match to open. */
    private val seal: (ByteArray, ByteArray?) -> ByteArray = { b, ad ->
        val a = ad ?: ByteArray(0)
        byteArrayOf(a.size.toByte()) + a + b.reversedArray()
    }
    private val open: (ByteArray, ByteArray?) -> ByteArray = { s, ad ->
        val n = s[0].toInt()
        val stored = s.copyOfRange(1, 1 + n)
        if (!stored.contentEquals(ad ?: ByteArray(0))) throw javax.crypto.AEADBadTagException("associated data differs")
        s.copyOfRange(1 + n, s.size).reversedArray()
    }

    private val chat = CachedChat(listOf(TextItem("t1", "hello")), "{}", false, "Title")

    @Test fun aFileMovedOntoAnotherPaneDoesNotOpen() {
        val store = ChatStore(tmp.root, seal, open)
        store.save("s1", "w1:p1", chat)
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        // Rename pane p1's file to pane p2's name: it must not open as p2's chat.
        val f1 = tmp.root.listFiles()!!.single()
        store.save("s1", "w1:p2", chat.copy(items = listOf(TextItem("t2", "other"))))
        val f2 = tmp.root.listFiles()!!.first { it != f1 }
        f1.copyTo(f2, overwrite = true)
        assertNull(store.load("s1", "w1:p2"))
        assertFalse(f2.exists())   // set aside as damaged (1.3.2: never deleted), read afresh next time
        assertTrue(File(tmp.root, "unreadable/" + f2.name).isFile)
    }

    @Test fun namesAreCollisionResistant() {
        val store = ChatStore(tmp.root, seal, open)
        // "Aa" and "BB" share a String.hashCode, which the old names used.
        assertEquals("Aa".hashCode(), "BB".hashCode())
        store.save("s1", "Aa", chat)
        store.save("s1", "BB", chat.copy(items = listOf(TextItem("t2", "bb"))))
        assertEquals(2, tmp.root.listFiles()!!.count { it.isFile })
        assertEquals("hello", (store.load("s1", "Aa")!!.items.single() as TextItem).text)
        assertEquals("bb", (store.load("s1", "BB")!!.items.single() as TextItem).text)
        assertTrue(tmp.root.listFiles()!!.all { Regex("-[0-9a-f]{16}\\.chat$").containsMatchIn(it.name) })
    }

    @Test fun aFileUnderTheOldNameMovesToTheNewOne() {
        // Written the 1.4.0 way: old name, no associated data.
        fun oldPart(s: String) = s.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("").take(40) + "-" + Integer.toHexString(s.hashCode())
        val old = File(tmp.root, oldPart("s1") + "__" + oldPart("w1:p1") + ".chat")
        old.writeBytes(seal(ChatCodec.encode(chat).toByteArray(Charsets.UTF_8), null))
        val store = ChatStore(tmp.root, seal, open)
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        assertFalse(old.exists())
        val now = tmp.root.listFiles()!!.single()
        assertTrue(now.name != old.name)
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        // And the server's files go with it, under either name.
        store.removeServer("s1")
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun questionsAreBoundSeparatelyFromTheChat() {
        val store = ChatStore(tmp.root, seal, open)
        val q = QuestionLog.seen(emptyList(), com.billmongan.herdrdroid.herdr.Prompt("Go?", listOf(com.billmongan.herdrdroid.herdr.Choice("Yes", "1", false))), "Codex", null, 1)
        store.saveQuestions("s1", "w1:p1", q)
        store.save("s1", "w1:p1", chat)
        val qf = tmp.root.listFiles()!!.first { it.name.endsWith(".q") }
        val cf = tmp.root.listFiles()!!.first { it.name.endsWith(".chat") }
        // A question file put in place of the chat file doesn't open as the chat.
        qf.copyTo(cf, overwrite = true)
        assertNull(store.load("s1", "w1:p1"))
        assertEquals(null, store.load("s1", "w1:p1"))
    }
    // ---- moving a file from the 1.4.0 name, interrupted at each step: nothing is lost ----

    private fun clean(s: String) = s.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("")
    private fun oldName(sv: String, p: String, ext: String) = clean(sv).take(40) + "-" + Integer.toHexString(sv.hashCode()) + "__" + clean(p).take(40) + "-" + Integer.toHexString(p.hashCode()) + ext
    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    private fun newName(sv: String, p: String, ext: String) = clean(sv).take(24) + "-" + sha(sv) + "__" + clean(p).take(24) + "-" + sha(p) + ext
    private fun legacy(): File = File(tmp.root, oldName("s1", "w1:p1", ".chat")).apply { writeBytes(seal(ChatCodec.encode(chat).toByteArray(Charsets.UTF_8), null)) }

    @Test fun aRenameThatFailsKeepsTheOldFile() {
        val old = legacy()
        // Something in the way of the new name (a non-empty folder): the atomic move fails.
        File(tmp.root, newName("s1", "w1:p1", ".chat")).apply { mkdirs(); File(this, "x").writeText("x") }
        val store = ChatStore(tmp.root, seal, open)
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        assertTrue(old.isFile)
        assertEquals(chat.items, ChatCodec.decode(String(open(old.readBytes(), null)))!!.items)
    }

    @Test fun aSealThatFailsWhileMovingKeepsTheOldFile() {
        val old = legacy()
        var locked = true
        val store = ChatStore(tmp.root, { b, ad -> if (locked) throw com.billmongan.herdrdroid.store.VaultLocked(Exception("window")) else seal(b, ad) }, open)
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        assertTrue(old.isFile)
        assertFalse(File(tmp.root, newName("s1", "w1:p1", ".chat")).exists())
        // Unlocked, the next load moves it.
        locked = false
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        assertFalse(old.exists())
        assertTrue(File(tmp.root, newName("s1", "w1:p1", ".chat")).isFile)
    }

    @Test fun aCrashAfterTheNewCopyBeforeRemovingTheOldOneIsFinishedNextTime() {
        val old = legacy()
        val store = ChatStore(tmp.root, seal, open)
        // As if the app stopped right after writing the new copy.
        File(tmp.root, newName("s1", "w1:p1", ".chat")).writeBytes(seal(ChatCodec.encode(chat).toByteArray(Charsets.UTF_8), "herdrdroid|chat|s1|w1:p1".toByteArray()))
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        assertFalse(old.exists())
    }

    @Test fun aReadBackThatDiffersKeepsTheOldFile() {
        val old = legacy()
        var reads = 0
        // The new copy reads back wrong (the second open of the move).
        val store = ChatStore(tmp.root, seal, { b, ad -> reads++; if (reads == 2) "{}".toByteArray() else open(b, ad) })
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        assertTrue(old.isFile)
        // The copy that read back wrong never took the new name, so the next load reads the old
        // file again instead of trusting it (1.4.1, from code review).
        assertFalse(File(tmp.root, newName("s1", "w1:p1", ".chat")).exists())
        assertFalse(tmp.root.listFiles()!!.any { it.name.endsWith(".tmp") })
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
    }

    @Test fun aNewFileThatCantBeOpenedNowFallsBackToTheOldOneAndKeepsBoth() {
        val old = legacy()
        val f = File(tmp.root, newName("s1", "w1:p1", ".chat")).apply { writeBytes(seal("x".toByteArray(), "herdrdroid|chat|s1|w1:p1".toByteArray())) }
        // Not a bad seal: a Keystore or read error, which keeps the file where it is.
        val store = ChatStore(tmp.root, seal, { b, ad -> if (ad != null) throw java.security.ProviderException("keystore busy") else open(b, ad) })
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        assertTrue(old.isFile); assertTrue(f.isFile)
    }

    @Test fun aSaveRemovesTheOldFileOnlyWhenTheNewOneReadsBack() {
        val old = legacy()
        val newer = chat.copy(items = listOf(TextItem("t9", "newer")))
        // The new file can't be read back (say, the window closed): the old one stays.
        ChatStore(tmp.root, seal, { b, ad -> if (ad != null) throw com.billmongan.herdrdroid.store.VaultLocked(Exception("window")) else open(b, ad) })
            .save("s1", "w1:p1", newer)
        assertTrue(old.isFile)
        // It reads back, but the old file holds something else the app never loaded: it stays
        // (1.4.1, third review). The next load finds it older than the new file and removes it.
        val store = ChatStore(tmp.root, seal, open)
        store.save("s1", "w1:p1", newer)
        assertTrue(old.isFile)
        old.setLastModified(System.currentTimeMillis() - 60_000)
        assertEquals(newer.items, store.load("s1", "w1:p1")!!.items)
        assertFalse(old.exists())
    }

    @Test fun aSaveAfterLoadingTheOldFileReplacesIt() {
        val old = legacy()
        // The move can't happen yet (sealing fails), so the load returns the old file's chat.
        var sealing = false
        val store = ChatStore(tmp.root, { b, ad -> if (!sealing) throw com.billmongan.herdrdroid.store.VaultLocked(Exception("window")) else seal(b, ad) }, open)
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        sealing = true
        // That chat, carried on, is saved: it was built from the old file, so the old file goes.
        store.save("s1", "w1:p1", chat.copy(items = chat.items + TextItem("t7", "more")))
        assertFalse(old.exists())
    }

    @Test fun aReadBackThatFailsLeavesTheKeptFileAsItWas() {
        val store = ChatStore(tmp.root, seal, open)
        store.save("s1", "w1:p1", chat)
        val f = File(tmp.root, newName("s1", "w1:p1", ".chat"))
        val before = f.readBytes()
        val flaky = ChatStore(tmp.root, seal, { b, ad -> if (b.contentEquals(before)) open(b, ad) else "{}".toByteArray() })
        flaky.load("s1", "w1:p1")
        flaky.save("s1", "w1:p1", chat.copy(items = listOf(TextItem("t8", "won't read back"))))
        assertTrue(before.contentEquals(f.readBytes()))
        assertFalse(tmp.root.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun anOldCopyThatDiffersAndIsNewerStays() {
        val store = ChatStore(tmp.root, seal, open)
        store.save("s1", "w1:p1", chat.copy(items = listOf(TextItem("t2", "from the new file"))))
        val f = File(tmp.root, newName("s1", "w1:p1", ".chat"))
        f.setLastModified(System.currentTimeMillis() - 60_000)
        val old = legacy()
        assertEquals("from the new file", (store.load("s1", "w1:p1")!!.items.single() as TextItem).text)
        assertTrue(old.isFile)
    }

    @Test fun aDamagedNewFileFallsBackToTheOldOne() {
        val old = legacy()
        val f = File(tmp.root, newName("s1", "w1:p1", ".chat")).apply { writeBytes(seal("x".toByteArray(), "wrong pane".toByteArray())) }
        val store = ChatStore(tmp.root, seal, open)
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        assertTrue(File(tmp.root, "unreadable/" + f.name).isFile)
        assertFalse(old.exists())
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
    }

    @Test fun aLockedVaultKeepsBothNames() {
        val old = legacy()
        val store = ChatStore(tmp.root, seal, { _, _ -> throw com.billmongan.herdrdroid.store.VaultLocked(Exception("window")) })
        assertNull(store.load("s1", "w1:p1"))
        assertTrue(old.isFile)
        assertTrue(store.hasQuestions("s1", "w1:p1").not())
    }

    @Test fun aSaveWhileAnOldFileExistsReplacesIt() {
        val old = legacy()
        val store = ChatStore(tmp.root, seal, open)
        val newer = chat.copy(items = listOf(TextItem("t9", "newer")))
        // Loaded first, as the app does: the chat saved is built on the old file's, which then goes.
        store.load("s1", "w1:p1")
        store.save("s1", "w1:p1", newer)
        assertFalse(old.exists())
        assertEquals(newer.items, store.load("s1", "w1:p1")!!.items)
    }

    @Test fun anUnboundFileIsBoundAtItsNextLoad() {
        val f = File(tmp.root, newName("s1", "w1:p1", ".chat"))
        // Sealed before the key migration: no associated data.
        val unbound: (ByteArray, ByteArray?) -> ByteArray = { b, _ -> seal(b, null) }
        val opener: (ByteArray, ByteArray?) -> ByteArray = { b, ad -> if (b[0].toInt() == 0) open(b, null) else open(b, ad) }
        ChatStore(tmp.root, unbound, opener).save("s1", "w1:p1", chat)
        assertEquals(0, f.readBytes()[0].toInt())
        val store = ChatStore(tmp.root, seal, opener, { it[0].toInt() != 0 })
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        assertTrue(f.readBytes()[0].toInt() != 0)
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
    }
    @Test fun aSaveNeverWritesOverANewFileItCantOpen() {
        // A newer chat under the new name that can't be opened now, and an older one under the old name.
        val old = legacy()
        val f = File(tmp.root, newName("s1", "w1:p1", ".chat"))
        val newest = chat.copy(items = listOf(TextItem("t5", "the newest chat")))
        f.writeBytes(seal(ChatCodec.encode(newest).toByteArray(Charsets.UTF_8), "herdrdroid|chat|s1|w1:p1".toByteArray()))
        val newBytes = f.readBytes()
        var busy = true
        val store = ChatStore(tmp.root, seal, { b, ad -> if (busy && ad != null) throw java.security.ProviderException("keystore busy") else open(b, ad) })
        // The load falls back to the old chat; the save that follows must not replace the newer file.
        assertEquals(chat.items, store.load("s1", "w1:p1")!!.items)
        store.save("s1", "w1:p1", chat)
        assertTrue(newBytes.contentEquals(f.readBytes()))
        assertTrue(old.isFile)
        // Once it opens, the newer chat is what loads.
        busy = false
        assertEquals(newest.items, store.load("s1", "w1:p1")!!.items)
    }

    @Test fun aSaveKeepsANewerDifferentOldCopy() {
        val store = ChatStore(tmp.root, seal, open)
        store.save("s1", "w1:p1", chat.copy(items = listOf(TextItem("t2", "older, under the new name"))))
        File(tmp.root, newName("s1", "w1:p1", ".chat")).setLastModified(System.currentTimeMillis() - 60_000)
        val old = legacy()   // newer, and different
        store.save("s1", "w1:p1", chat.copy(items = listOf(TextItem("t3", "saved again"))))
        assertTrue(old.isFile)
    }
    @Test fun theSizeCapTrimsOnlyChatsThatCanBeReadAgain() {
        val one = ChatCodec.encode(chat).length.toLong()
        val store = ChatStore(tmp.root, seal, open, { true }, one / 2)   // a cap smaller than one chat
        val old = legacy()
        val q = QuestionLog.seen(emptyList(), com.billmongan.herdrdroid.herdr.Prompt("Go?", listOf(com.billmongan.herdrdroid.herdr.Choice("Yes", "1", false))), "Codex", null, 1)
        store.saveQuestions("s1", "w1:p9", q)
        store.save("s1", "w1:p2", chat)
        // The chat just saved stays even though it alone is over the cap; the question history and
        // the 1.4.0 file are never trimmed.
        assertTrue(File(tmp.root, newName("s1", "w1:p2", ".chat")).isFile)
        assertTrue(File(tmp.root, newName("s1", "w1:p9", ".q")).isFile)
        assertTrue(old.isFile)
        // Saving another pane trims the older chat, which can be read again from the agent's log.
        store.save("s1", "w1:p3", chat)
        assertFalse(File(tmp.root, newName("s1", "w1:p2", ".chat")).exists())
        assertTrue(File(tmp.root, newName("s1", "w1:p3", ".chat")).isFile)
    }
}
