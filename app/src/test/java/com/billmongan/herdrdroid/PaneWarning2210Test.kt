package com.billmongan.herdrdroid

import com.billmongan.herdrdroid.herdr.paneWarningCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneWarning2210Test {
    private val capture = checkNotNull(javaClass.getResource(
        "/screens/codex/derived-2.2.8-queued-inputs/queued-inputs.txt"
    )).readText()

    @Test fun readsTheCountInTheCapturedLiveCodexFooter() {
        assertEquals(1, paneWarningCount("codex", capture))
        assertEquals(12, paneWarningCount("codex", capture.replace(
            "⚠ 1 warning", "\u001B[33m⚠ 12 warnings\u001B[0m"
        ) + "\n\n"))
    }

    @Test fun requiresTheCompleteKnownFooterAtTheBottomOfACodexViewport() {
        assertNull(paneWarningCount("claude", capture))
        assertNull(paneWarningCount("codex", capture + "\n› New prompt"))
        assertNull(paneWarningCount("codex", capture.replace("← for agents · ? for shortcuts", "Example:")))
        assertNull(paneWarningCount("codex", capture.replace("1 warning", "2 warning")))
        assertNull(paneWarningCount("codex", capture.replace("1 warning", "0 warnings")))
        assertNull(paneWarningCount("codex", capture.replace("1 warning", "999999999999 warnings")))
    }
}
