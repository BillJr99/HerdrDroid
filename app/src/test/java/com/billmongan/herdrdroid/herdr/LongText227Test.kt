package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

/** 2.2.7: long text is never cut: one message, numbered parts, or a checked file. */
class LongText227Test {
    private fun lines(chars: Int) = buildString {
        var i = 0
        while (length < chars) append("17:49:%02d.000 [link] row ${i++} ".format(i % 60)).append("x".repeat(60)).append('\n')
    }.take(chars)

    @Test fun shortTextIsOneMessage() {
        assertEquals(LongText.Plan.Single("hello"), LongText.plan("hello"))
        val edge = "a".repeat(LongText.PART_MAX_BYTES)
        assertEquals(LongText.Plan.Single(edge), LongText.plan(edge))
    }

    @Test fun mediumTextGoesAsNumberedPartsOnLineBoundariesWithNothingLost() {
        val text = lines(8_795) // the paste that arrived cut
        val plan = LongText.plan(text) as LongText.Plan.Parts
        assertTrue(plan.parts.size in 2..LongText.MAX_PARTS)
        plan.parts.forEachIndexed { i, p ->
            assertTrue(p.startsWith("(${i + 1}/${plan.parts.size}) "))
            assertTrue("part ${i + 1} fits", p.toByteArray().size <= LongText.PART_MAX_BYTES)
            if (i < plan.parts.size - 1) assertTrue("split at a line end", p.endsWith("\n"))
        }
        assertEquals(text, LongText.join(plan.parts))
    }

    @Test fun oneVeryLongLineIsCutBetweenCharactersNotLost() {
        val text = "é".repeat(4_000) + "😀".repeat(300)
        val plan = LongText.plan(text) as LongText.Plan.Parts
        assertEquals(text, LongText.join(plan.parts))
        plan.parts.forEach { assertTrue(it.toByteArray().size <= LongText.PART_MAX_BYTES) }
    }

    @Test fun largeTextGoesAsAFile() {
        for (size in listOf(20_000, 55_710, 500_000)) {
            val text = lines(size)
            assertEquals("$size", LongText.Plan.Attachment(text), LongText.plan(text))
        }
        assertEquals("55.7 KB", LongText.size(57_037))
        assertEquals("488.3 KB", LongText.size(500_000))
    }

    @Test fun anAttachmentPathIsNotASlashCommand() {
        assertTrue(isSlashCommand("/model"))
        assertTrue(isSlashCommand("/usage now"))
        assertTrue(isSlashCommand("  /btw what?"))
        assertFalse(isSlashCommand("/home/bill/.herdrdroid/inbox/20261123-175448-log.txt"))
        assertFalse(isSlashCommand("/ "))
        assertFalse(isSlashCommand("look at /tmp/x"))
        val p = "/home/fixture/.herdrdroid/inbox/20261123-175448-log.txt"
        assertEquals("Attached file: $p ", withAttachment("", p))
        assertFalse(isSlashCommand(withAttachment("", p)))
        assertEquals("see this $p ", withAttachment("see this  ", p))
    }

    @Test fun theChecksumIsSha256Hex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()))
    }
}
