package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenLinksTest {
    @Test fun findsTheSignInLinkAndCode() {
        val screen = """
 ● To authenticate, visit https://github.com/login/device and enter code 3F2A-9C1D.
   Waiting for authorization…
""".trimIndent()
        val f = screenLinks(screen)
        assertEquals(listOf("https://github.com/login/device"), f.urls)
        assertEquals(listOf("3F2A-9C1D"), f.codes)
    }

    @Test fun boxedLinesAndNothingSpurious() {
        val f = screenLinks("│ Open https://example.com/auth?x=1 │\n│ model GPT-5 high │\n 2026-11-12 ")
        assertEquals(listOf("https://example.com/auth?x=1"), f.urls)
        assertEquals(emptyList<String>(), f.codes)
    }
}
