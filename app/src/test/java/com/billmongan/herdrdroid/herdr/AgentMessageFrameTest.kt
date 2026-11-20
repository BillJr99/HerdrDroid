package com.billmongan.herdrdroid.herdr

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

internal fun fixtureMessageFrame(code: String, text: String): String =
    "HDMSG1 $code\n" + Base64.getEncoder().encodeToString(text.toByteArray()).chunked(40)
        .joinToString("\n") { "HD> $it" } + "\nHDEND $code"

class AgentMessageFrameTest {
    private val code = "1234567890abcdef1234567890abcdef"
    private val text = "Check the build λ.\nPreserve  spaces.  \n"

    @Test fun exactLiteralTextFromOneCompleteFrame() {
        val frame = fixtureMessageFrame(code, text).prependIndent("  ")
        assertEquals(text, agentMessageFrame("Normal agent output\n$frame\n❯", code))
    }

    @Test fun wrongNonceClippedDuplicateAndMalformedFramesAreRejected() {
        val frame = fixtureMessageFrame(code, text)
        for (output in listOf(frame.replace(code, "wrong"), frame.substringAfter('\n'),
            frame.substringBeforeLast('\n'), "$frame\n$frame", frame.replace("HD> ", "HD> !"),
            frame.replace("HD> ", "Unexpected line\nHD> "))) {
            assertNull(output, agentMessageFrame(output, code))
        }
        assertNull(agentMessageFrame("HDMSG1 $code\nHD> /w==\nHDEND $code", code))
    }

    @Test fun refusesHiddenControlsBlankAndUnboundedContent() {
        for (invalid in listOf("x\u001by", "x\u202ey", "x\u200by", "x\udb40\udc61y", "x\u0085y", "x\r\ny", "x\ty", "  \n", "x".repeat(1025))) {
            assertNull(agentMessageFrame(fixtureMessageFrame(code, invalid), code))
        }
        assertNull(agentMessageFrame("x".repeat(65_537) + "\n" + fixtureMessageFrame(code, text), code))
    }
}
