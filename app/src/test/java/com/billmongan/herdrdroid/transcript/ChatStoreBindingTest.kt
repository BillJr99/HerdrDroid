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
}
