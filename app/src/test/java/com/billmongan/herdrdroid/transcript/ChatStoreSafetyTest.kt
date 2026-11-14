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

    @Test fun aSealThatFailsToAuthenticateIsSetAsideNotDeleted() {
        val s = store()
        val before = tmp.root.listFiles()!!.first { it.name.endsWith(".chat") }.readBytes()
        failWith = javax.crypto.AEADBadTagException("tag mismatch")
        assertNull(s.load("s", "p"))
        assertFalse(tmp.root.listFiles()!!.any { it.name.endsWith(".chat") })
        val aside = java.io.File(tmp.root, "unreadable").listFiles()!!.single { it.name.endsWith(".chat") }
        assertTrue(before.contentEquals(aside.readBytes()))
        assertTrue(s.hasQuestions("s", "p"))
        // Removing the server clears the set-aside copy with the rest.
        s.removeServer("s")
        assertEquals(0, java.io.File(tmp.root, "unreadable").listFiles()!!.size)
    }

    @Test fun aFileThatOpensButDoesntDecodeIsSetAside() {
        // Written properly, then opened by a store whose files open to something that isn't a chat.
        ChatStore(tmp.root, seal, open).save("s", "p", chat)
        val s = ChatStore(tmp.root, seal, { _: ByteArray -> "not json".toByteArray() })
        assertNull(s.load("s", "p"))
        assertEquals(0, tmp.root.listFiles()!!.count { it.name.endsWith(".chat") })
        assertEquals(1, java.io.File(tmp.root, "unreadable").listFiles()!!.count { it.name.endsWith(".chat") })
    }

    @Test fun questionsThatShareOneStepAreDifferentQuestions() {
        fun item(k: String, vararg qs: String) = QuestionItem(k, qs.map { QPart(question = it, options = listOf("Yes", "No")) })
        assertFalse(QuestionLog.sameQuestion(item("a", "Delete the branch?", "Are you sure?"), item("b", "Drop the table?", "Are you sure?")))
        assertTrue(QuestionLog.sameQuestion(item("a", "Delete the branch?", "Are you sure?"), item("b", "Are you sure?", "Delete the branch?")))
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
