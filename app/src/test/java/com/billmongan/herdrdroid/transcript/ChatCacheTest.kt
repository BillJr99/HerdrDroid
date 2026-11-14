package com.billmongan.herdrdroid.transcript

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 1.2.9: the chats kept on the phone, and how reads fold into them (Bill: "cache better"). */
class ChatCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val items: List<Item> = listOf(
        UserItem("u1", "hello"),
        ThinkItem("th1", "thinking", 1200),
        TextItem("t1", "Hi there"),
        ToolItem("tool1", "c1", "Bash", "Run ls", "bash", "ls -la", DiffData("a.kt", "x", "y"), "@@ patch", "out", ToolState.OK,
            path = "/tmp/a", image = null, attach = listOf("/tmp/f.pdf")),
        ToolItem("tool2", "c2", "Bash", "Still going", "bash", null, null, null, null, ToolState.RUNNING),
        NoteItem("n1", "model opus"),
        ImageItem("i1", logFile = "/l.jsonl", logOffset = 42, logIndex = "[1,2]", mime = "image/png", kb = 3, user = true, name = "shot.png"),
    )
    private val chat = CachedChat(items, """{"file":"/l.jsonl","off":900}""", true, "Title", 100L, "/l.jsonl", "claude")

    private fun read(cursor: String, reset: Boolean, vararg raw: String) =
        TranscriptRead(true, null, cursor, reset, false, null, raw.map { JSONObject(it) })

    @Test fun everyItemTypeRoundTrips() {
        val back = ChatCodec.decode(ChatCodec.encode(chat))!!
        assertEquals(items, back.items)
        assertEquals(chat.cursor, back.cursor)
        assertEquals(chat.copy(items = back.items), back)
    }

    @Test fun aMessageStillBeingSentIsNotKept() {
        val back = ChatCodec.decode(ChatCodec.encode(chat.copy(items = items + UserItem("p1", "queued", sending = true))))!!
        assertEquals(items, back.items)
    }

    @Test fun onlyTheNewestItemsAreKeptAndEarlierIsNotReadAcrossTheGap() {
        val many = (1..ChatCodec.MAX_ITEMS + 50).map { TextItem("t$it", "reply $it") }
        val back = ChatCodec.decode(ChatCodec.encode(chat.copy(items = many, more = false)))!!
        assertEquals(ChatCodec.MAX_ITEMS, back.items.size)
        assertEquals("t${ChatCodec.MAX_ITEMS + 50}", back.items.last().key)
        assertTrue(back.more)
        assertEquals(-1L, back.start)
    }

    @Test fun longToolOutputIsCut() {
        val big = ToolItem("x", "c", "Bash", "t", "bash", null, null, null, "y".repeat(50_000), ToolState.OK)
        val back = ChatCodec.decode(ChatCodec.encode(chat.copy(items = listOf(big))))!!
        assertTrue((back.items[0] as ToolItem).out!!.length < 9_000)
    }

    @Test fun aDeltaReadAddsOnlyWhatIsNew() {
        val next = chat.withRead(read("""{"file":"/l.jsonl","off":1000}""", false,
            """{"t":"text","k":"t1","text":"Hi there"}""", """{"t":"text","k":"t2","text":"New reply"}""",
            """{"t":"result","ref":"c2","out":"done","err":false}"""))
        assertEquals(items.size + 1, next.items.size)
        assertEquals(1, next.items.count { it.key == "t1" })
        assertEquals("t2", next.items.last().key)
        assertEquals(ToolState.OK, (next.items.first { it.key == "tool2" } as ToolItem).state)
        assertEquals("""{"file":"/l.jsonl","off":1000}""", next.cursor)
        assertEquals(100L, next.start)
    }

    @Test fun aResetReplacesTheChat() {
        val next = chat.withRead(read("""{"file":"/other.jsonl","off":50,"start":0}""", true, """{"t":"user","k":"u9","text":"new session"}"""))
        assertEquals(listOf<Item>(UserItem("u9", "new session")), next.items)
        assertEquals("/other.jsonl", next.file)
        assertEquals("claude", next.agent)
    }

    @Test fun anEmptyReadKeepsEverything() {
        val next = chat.withRead(read("""{"file":"/l.jsonl","off":900}""", false))
        assertEquals(items, next.items)
    }

    private fun store(max: Long = 8L * 1024 * 1024) = ChatStore(tmp.root,
        { b -> ByteArray(b.size) { (b[it].toInt() xor 0x5a).toByte() } },
        { b -> ByteArray(b.size) { (b[it].toInt() xor 0x5a).toByte() } }, max)

    @Test fun storeSavesLoadsAndSealsIt() {
        val s = store()
        s.save("srv-1", "w1:p2", chat)
        assertEquals(items, s.load("srv-1", "w1:p2")!!.items)
        val f = tmp.root.listFiles()!!.single()
        assertFalse(String(f.readBytes()).contains("hello"))
        assertNull(s.load("srv-1", "w1:p3"))
        assertNull(s.load("srv-2", "w1:p2"))
    }

    @Test fun aDamagedFileIsDroppedNotShown() {
        val s = store()
        s.save("srv-1", "w1:p2", chat)
        tmp.root.listFiles()!!.single().writeBytes(byteArrayOf(1, 2, 3))
        assertNull(s.load("srv-1", "w1:p2"))
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }

    @Test fun closedPanesAndForgottenServersAreRemoved() {
        val s = store()
        s.save("srv-1", "w1:p2", chat)
        s.save("srv-1", "w1:p3", chat)
        s.save("srv-2", "w1:p2", chat)
        s.keepOnly("srv-1", listOf("w1:p3"))
        assertNull(s.load("srv-1", "w1:p2"))
        assertEquals(items, s.load("srv-1", "w1:p3")!!.items)
        s.removeServer("srv-1")
        assertNull(s.load("srv-1", "w1:p3"))
        assertEquals(items, s.load("srv-2", "w1:p2")!!.items)
    }

    @Test fun theOldestFilesGoPastTheSizeCap() {
        val one = ChatCodec.encode(chat).length.toLong()
        val s = store(max = one * 2 + one / 2)
        s.save("srv", "a", chat); tmp.root.listFiles()!!.forEach { it.setLastModified(1_000) }
        s.save("srv", "b", chat); tmp.root.listFiles()!!.first { it.lastModified() != 1_000L }.setLastModified(2_000)
        s.save("srv", "c", chat)
        assertNull(s.load("srv", "a"))
        assertEquals(items, s.load("srv", "c")!!.items)
    }
}
