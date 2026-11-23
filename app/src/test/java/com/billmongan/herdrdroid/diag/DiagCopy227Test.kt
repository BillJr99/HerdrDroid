package com.billmongan.herdrdroid.diag

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** 2.2.7: Bill's two copies of the log stopped at the same character; Copy keeps the newest whole lines. */
class DiagCopy227Test {
    @After fun clear() = DiagLog.clear()

    @Test fun aCopyLargerThanTheCapKeepsTheNewestWholeLinesWithANote() {
        val rows = (1..3_000).map { "17:49:%02d.000 [link] line $it ".format(it % 60) + "x".repeat(40) }
        val text = rows.joinToString("\n")
        assertTrue(text.length > DiagLog.COPY_MAX_CHARS)
        val copy = DiagLog.forClipboard(text)
        assertTrue(copy.length <= DiagLog.COPY_MAX_CHARS)
        val out = copy.lines()
        val omitted = rows.size - (out.size - 1)
        assertEquals("(trimmed: oldest $omitted lines omitted; use Save for the full log)", out.first())
        assertEquals("ends on the newest line", rows.last(), out.last())
        // Every line kept is whole and in order: exactly the newest ones.
        assertEquals(rows.takeLast(out.size - 1), out.drop(1))
    }

    @Test fun aCopyWithinTheCapIsTheWholeLog() {
        val text = (1..20).joinToString("\n") { "line $it" }
        assertEquals(text, DiagLog.forClipboard(text))
    }

    @Test fun theBufferKeepsTheNewestLines() {
        DiagLog.clear()
        for (i in 1..(DiagLog.MAX + 10)) DiagLog.log("t", "n$i")
        val lines = DiagLog.text().lines()
        assertEquals(DiagLog.MAX, lines.size)
        assertTrue(lines.first().endsWith("[t] n11"))
        assertTrue(lines.last().endsWith("[t] n${DiagLog.MAX + 10}"))
    }
}
