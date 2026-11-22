package com.billmongan.herdrdroid.herdr

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** 2.1.5's frame: HDMSG2 <code>, HDTO <destination>, HD> chunks, HDEND <code>. */
internal fun fixtureMessageFrame(code: String, text: String, to: String = "Hermes on minipc2"): String =
    "HDMSG2 $code\nHDTO $to\n" + Base64.getEncoder().encodeToString(text.toByteArray()).chunked(40)
        .joinToString("\n") { "HD> $it" } + "\nHDEND $code"

class AgentMessageFrameTest {
    private val code = "K7P3QX"
    private val text = "Check the build λ.\nPreserve  spaces.  \n"

    @Test fun validFrameGivesCodeDestinationAndExactText() {
        val frame = fixtureMessageFrame(code, text).prependIndent("  ")
        assertEquals(listOf(CrossServerFrame(code, "Hermes on minipc2", text)),
            crossServerFrames("Normal agent output\n$frame\n❯"))
        // Claude Code marks the first line of a reply; a boxed code block draws a gutter.
        val marked = "⏺ " + fixtureMessageFrame(code, text).lines().joinToString("\n") { "│ $it │" }
        assertEquals(text, crossServerFrames(marked).single().text)
    }

    @Test fun malformedCodesShowNoFrame() {
        for (bad in listOf("K7P3Q", "K7P3QXY", "k7p3qx", "K7P3Q0", "K7P3QO", "K7P3Q1", "K7P3QI", "K7P3QL", "<code>",
            "1234567890abcdef1234567890abcdef")) {
            assertTrue(bad, crossServerFrames(fixtureMessageFrame(bad, text)).isEmpty())
        }
    }

    @Test fun malformedFramesAreRejected() {
        val frame = fixtureMessageFrame(code, text)
        for (output in listOf(frame.replace("HDEND $code", "HDEND QQQQQQ"), frame.substringAfter('\n'),
            frame.substringBeforeLast('\n'), frame.replace("HD> ", "HD> !"),
            frame.replace("HDTO Hermes on minipc2", "HDTO "), frame.lines().filterNot { it.startsWith("HDTO") }.joinToString("\n"),
            frame.replace("HD> ", "Unexpected line\nHD> "), frame.replace("HDMSG2", "HDMSG1"),
            "HDMSG2 $code\nHDTO x\nHD> /w==\nHDEND $code")) {
            assertTrue(output, crossServerFrames(output).isEmpty())
        }
    }

    @Test fun aBadFrameOrTheNoticeTemplateDoesNotHideAGoodOne() {
        // 2.1.4's parser gave up at the first bad line: the copied instructions' own template,
        // echoed above the agent's reply, hid every real frame.
        val template = checkNotNull(CrossServerNotice.text(listOf("Hermes on minipc2")))
        val broken = fixtureMessageFrame("QQQQQQ", text).replace("HD> ", "HD> !")
        val good = fixtureMessageFrame(code, "Hello")
        assertEquals(listOf(code), crossServerFrames("$template\n$broken\n$good").map { it.code })
    }

    @Test fun aRepeatedFrameIsOneAndAConflictingCodeIsNone() {
        val a = fixtureMessageFrame(code, "one")
        assertEquals(1, crossServerFrames("$a\n$a").size)
        assertTrue(crossServerFrames("$a\n" + fixtureMessageFrame(code, "two")).isEmpty())
        assertEquals(2, crossServerFrames("$a\n" + fixtureMessageFrame("M4N5P6", "two")).size)
    }

    @Test fun refusesHiddenControlsBlankAndUnboundedContent() {
        for (invalid in listOf("x\u001by", "x‮y", "x​y", "x󠁡y", "x\u0085y", "x\r\ny", "x\ty", "  \n", "x".repeat(1025))) {
            assertTrue(crossServerFrames(fixtureMessageFrame(code, invalid)).isEmpty())
        }
        // Only the last 64 KiB is read: a frame at the end of long output still counts.
        assertEquals(1, crossServerFrames("x".repeat(70_000) + "\n" + fixtureMessageFrame(code, "ok")).size)
    }

    /** 2.2.0: nobody types a code back any more, so there is no parser for a typed reply; codes are exact. */
    @Test fun codesAreExactAndThereIsNoTypedReplyParser() {
        assertTrue(CrossServerCode.valid(code))
        for (bad in listOf("k7p3qx", " K7P3QX", "K7P3Q", "K7P3QO", "K7P3QX1")) assertFalse(bad, CrossServerCode.valid(bad))
        assertFalse(CrossServerCode::class.java.declaredMethods.any { it.name == "reply" })
    }

    @Test fun ledgerAdmitsOnceUntilSpentOrExpiredAndPersists() {
        var stored = "{}"
        val ledger = CrossServerCodeLedger({ stored }, { stored = it })
        val t0 = 1_000_000L
        assertEquals(t0 + CrossServerCode.LIFETIME_MS, ledger.admit("s|p|$code", t0))
        // Seen again later: the deadline still counts from the first sighting.
        assertEquals(t0 + CrossServerCode.LIFETIME_MS, ledger.admit("s|p|$code", t0 + 60_000))
        assertNull(ledger.admit("s|p|$code", t0 + CrossServerCode.LIFETIME_MS))
        ledger.spend("s|p|M4N5P6", t0)
        val reloaded = CrossServerCodeLedger({ stored }, { stored = it })
        assertNull(reloaded.admit("s|p|M4N5P6", t0 + 1))
        assertTrue(reloaded.spent("s|p|M4N5P6"))
        assertNull(reloaded.admit("s|p|$code", t0 + CrossServerCode.LIFETIME_MS + 1))
    }
}
