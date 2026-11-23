package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

/** 2.2.7: long text is never cut: one message, numbered parts, or a checked file. */
class LongText227Test {
    private fun lines(chars: Int) = buildString {
        var i = 0
        while (length < chars) append("17:49:%02d.000 [link] row ${i++} ".format(i % 60)).append("x".repeat(60)).append('\n')
    }.take(chars)

    @Test fun textUpTo20KbQuotedIsOneMessage() {
        assertEquals(LongText.Plan.Single("hello"), LongText.plan("hello"))
        // The 8,795-character paste goes whole, as one message.
        val paste = lines(8_795)
        assertEquals(LongText.Plan.Single(paste), LongText.plan(paste))
        // Right at the limit (the quotes take two bytes), and one byte over.
        val edge = "a".repeat(LongText.SINGLE_MAX_BYTES - 2)
        assertEquals(LongText.SINGLE_MAX_BYTES, LongText.quotedBytes(edge))
        assertEquals(LongText.Plan.Single(edge), LongText.plan(edge))
        assertEquals(LongText.Plan.Attachment(edge + "a"), LongText.plan(edge + "a"))
    }

    @Test fun apostrophesCountAsTheyAreQuoted() {
        // 19 KB of text, but each ' becomes four characters on the command line.
        val words = "it's Bill's log, don't cut it\n"
        val text = words.repeat(19 * 1024 / words.length)
        assertTrue(text.toByteArray().size < LongText.SINGLE_MAX_BYTES)
        assertTrue(LongText.quotedBytes(text) > LongText.SINGLE_MAX_BYTES)
        assertEquals(LongText.Plan.Attachment(text), LongText.plan(text))
        // Fewer of them and it fits.
        val few = "it's fine\n" + "a".repeat(15_000)
        assertEquals(LongText.Plan.Single(few), LongText.plan(few))
        assertTrue(LongText.COMMAND_GUARD_BYTES < 128 * 1024)
    }

    @Test fun largeTextGoesAsAFile() {
        for (size in listOf(21_000, 55_710, 500_000)) {
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
