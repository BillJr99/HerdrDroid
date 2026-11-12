package com.billmongan.herdrdroid.transcript

import com.billmongan.herdrdroid.ui.MdBlock
import com.billmongan.herdrdroid.ui.Md
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    private fun j(s: String) = JSONObject(s)

    @Test
    fun readParsesItemsAndCursor() {
        val r = Transcript.parseRead("""{"ok":true,"cur":{"file":"/x","off":10},"reset":true,"more":true,"title":"T","items":[{"k":"a","t":"user","text":"hi"}]}""")
        assertTrue(r.ok)
        assertTrue(r.reset && r.more)
        assertEquals("T", r.title)
        assertEquals(1, r.items.size)
        assertEquals("/x", JSONObject(r.cursor).getString("file"))
    }

    @Test
    fun failedReadCarriesTheReason() {
        val r = Transcript.parseRead("""{"ok":false,"why":"no session log found for this folder"}""")
        assertEquals(false, r.ok)
        assertEquals("no session log found for this folder", r.why)
    }

    @Test
    fun resultFillsItsToolCallAcrossReads() {
        val first = Transcript.merge(emptyList(), listOf(j("""{"k":"t1","t":"tool","id":"c1","name":"Bash","title":"$ ls","kind":"shell","state":"run"}""")), true)
        val second = Transcript.merge(first, listOf(j("""{"k":"r1","t":"result","ref":"c1","out":"a.txt","err":false}""")), false)
        val t = second.single() as ToolItem
        assertEquals("a.txt", t.out)
        assertEquals(ToolState.OK, t.state)
    }

    @Test
    fun failedResultMarksTheCall() {
        val l = Transcript.merge(emptyList(), listOf(
            j("""{"k":"t1","t":"tool","id":"c1","name":"Bash","title":"$ false","state":"run"}"""),
            j("""{"k":"r1","t":"result","ref":"c1","out":"exit 1","err":true}"""),
        ), true)
        assertEquals(ToolState.ERROR, (l.single() as ToolItem).state)
    }

    @Test
    fun sameKeyReplacesInPlace() {
        val a = Transcript.merge(emptyList(), listOf(j("""{"k":"m","t":"text","text":"Hel"}"""), j("""{"k":"n","t":"user","text":"x"}""")), true)
        val b = Transcript.merge(a, listOf(j("""{"k":"m","t":"text","text":"Hello"}""")), false)
        assertEquals(2, b.size)
        assertEquals("Hello", (b[0] as TextItem).text)
    }

    @Test
    fun resentCallKeepsItsOutput() {
        val a = Transcript.merge(emptyList(), listOf(
            j("""{"k":"t","t":"tool","id":"c","name":"x","state":"run"}"""),
            j("""{"k":"r","t":"result","ref":"c","out":"done"}"""),
        ), true)
        val b = Transcript.merge(a, listOf(j("""{"k":"t","t":"tool","id":"c","name":"x","state":"run"}""")), false)
        assertEquals("done", (b.single() as ToolItem).out)
    }

    @Test
    fun resetStartsOver() {
        val a = Transcript.merge(emptyList(), listOf(j("""{"k":"a","t":"user","text":"one"}""")), true)
        val b = Transcript.merge(a, listOf(j("""{"k":"b","t":"user","text":"two"}""")), true)
        assertEquals(listOf("b"), b.map { it.key })
    }

    @Test
    fun workBetweenRepliesFoldsIntoOneActivityRow() {
        val items = listOf(
            UserItem("u", "do it"),
            ThinkItem("th", "hmm", 1200),
            ToolItem("t1", "1", "Read", "Read a", "read", null, null, null, "x", ToolState.OK),
            ToolItem("t2", "2", "Edit", "Edit a", "edit", null, null, null, null, ToolState.RUNNING),
            TextItem("a", "Done."),
        )
        val rows = rowsOf(items)
        assertEquals(3, rows.size)
        val act = rows[1] as ActivityRow
        assertEquals(3, act.items.size)
        assertTrue(act.running)
    }

    @Test
    fun aLoneThoughtStaysItsOwnRow() {
        val rows = rowsOf(listOf(UserItem("u", "q"), ThinkItem("t", "x", null), TextItem("a", "y")))
        assertTrue(rows[1] is OneRow)
    }

    @Test
    fun markdownBlocks() {
        val b = Md.parse(
            """
            # Title

            Some **bold** text
            that wraps.

            - one
            - two
              continued
            1. first

            ```kotlin
            val x = 1
            ```

            | a | b |
            |---|---|
            | 1 | 2 |

            > quoted
            ---
            """.trimIndent(),
        )
        assertEquals(MdBlock.Heading(1, "Title"), b[0])
        assertEquals(MdBlock.Para("Some **bold** text that wraps."), b[1])
        assertEquals("two continued", (b[3] as MdBlock.ListItem).text)
        assertTrue((b[4] as MdBlock.ListItem).ordered)
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), b[5])
        assertEquals(listOf("a", "b"), (b[6] as MdBlock.Table).header)
        assertEquals(MdBlock.Quote("quoted"), b[7])
        assertEquals(MdBlock.Rule, b[8])
    }

    @Test
    fun checkboxesAndUnclosedFence() {
        val b = Md.parse("- [x] done\n- [ ] todo\n```\nopen fence")
        assertEquals(true, (b[0] as MdBlock.ListItem).checked)
        assertEquals(false, (b[1] as MdBlock.ListItem).checked)
        assertEquals(MdBlock.Code("", "open fence"), b[2])
    }
}
