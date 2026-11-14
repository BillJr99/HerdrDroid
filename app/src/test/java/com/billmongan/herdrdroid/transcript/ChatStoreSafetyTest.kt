package com.billmongan.herdrdroid.transcript

import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 1.3.2: a kept chat or question file is deleted only when it is damaged for certain (Grok's review of 1.3.1). */
class ChatStoreSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private var failWith: Exception? = null
    private val seal: (ByteArray) -> ByteArray = { it.reversedArray() }
    private val open: (ByteArray) -> ByteArray = { b -> failWith?.let { throw it }; b.reversedArray() }
    private val chat = CachedChat(listOf(TextItem("t1", "hello")), "{}", false, null)
    private val q = QuestionLog.seen(emptyList(), Prompt("Go on?", listOf(Choice("Yes", "1", false))), "Codex", null, 1)

    private fun store() = ChatStore(tmp.root, seal, open).also { it.save("s", "p", chat); it.saveQuestions("s", "p", q) }

    @Test fun aKeystoreOrDiskErrorKeepsTheFiles() {
        val s = store()
        for (e in listOf(java.security.ProviderException("keystore busy"), java.io.IOException("read failed"),
                         IllegalStateException("the key for this chat file is gone"), ArrayIndexOutOfBoundsException("short"))) {
            failWith = e
            assertNull(s.load("s", "p"))
            assertNull(s.loadQuestions("s", "p"))
            assertEquals(e.javaClass.simpleName, 2, tmp.root.listFiles()!!.size)
        }
        failWith = null
        assertEquals(chat.items, s.load("s", "p")!!.items)
        assertEquals(q, s.loadQuestions("s", "p"))
    }

    @Test fun aSealThatFailsToAuthenticateIsDeleted() {
        val s = store()
        failWith = javax.crypto.AEADBadTagException("tag mismatch")
        assertNull(s.load("s", "p"))
        assertFalse(tmp.root.listFiles()!!.any { it.name.endsWith(".chat") })
        assertTrue(s.hasQuestions("s", "p"))
    }

    @Test fun aFileThatOpensButDoesntDecodeIsDeleted() {
        val s = ChatStore(tmp.root, seal, { _: ByteArray -> "not json".toByteArray() })
        s.save("s", "p", chat)
        assertNull(s.load("s", "p"))
        assertEquals(0, tmp.root.listFiles()!!.count { it.name.endsWith(".chat") })
    }

    @Test fun anEmptyQuestionSaveNeverClearsAHistory() {
        val s = store()
        s.saveQuestions("s", "p", emptyList())
        assertEquals(q, s.loadQuestions("s", "p"))
        // Removing a pane's history is remove()'s job.
        s.remove("s", "p")
        assertFalse(s.hasQuestions("s", "p"))
    }

    @Test fun savesLeaveNoTemporaryFiles() {
        val s = store()
        s.save("s", "p", chat.copy(title = "again"))
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertEquals("again", s.load("s", "p")!!.title)
    }
}
