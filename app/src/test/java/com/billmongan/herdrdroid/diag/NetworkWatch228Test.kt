package com.billmongan.herdrdroid.diag

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkWatch228Test {
    @Test fun registrationCanRetryAfterFailureOrMissingManager() {
        val started = AtomicBoolean()
        val trouble = SecurityException("fixture registration denied")
        assertSame(trouble, runCatching {
            registerNetworkWatchOnce(started) { throw trouble }
        }.exceptionOrNull())
        assertFalse(started.get())
        registerNetworkWatchOnce(started) { false }
        assertFalse(started.get())

        var registrations = 0
        registerNetworkWatchOnce(started) {
            registrations++
            true
        }
        registerNetworkWatchOnce(started) {
            registrations++
            true
        }
        assertTrue(started.get())
        assertEquals(1, registrations)
    }

    @Test fun lossOfAnOldDefaultDoesNotEraseItsReplacement() {
        val wifi = Any()
        val vpn = Any()
        val cell = Any()
        val priorListener = NetworkWatch.listener
        val notices = mutableListOf<Pair<String, Int>>()
        try {
            NetworkWatch.listener = null
            NetworkWatch.observe(wifi, "wifi")
            val before = NetworkWatch.changes
            NetworkWatch.listener = { type, changes -> notices.add(type to changes) }

            NetworkWatch.observe(vpn, NetworkWatch.typeOf(vpn = true, wifi = true, cellular = false, ethernet = false))
            NetworkWatch.lost(wifi)
            assertEquals("vpn", NetworkWatch.type)

            NetworkWatch.observe(cell, "cellular")
            NetworkWatch.lost(vpn)
            assertEquals("cellular", NetworkWatch.type)
            NetworkWatch.lost(cell)
            NetworkWatch.lost(cell)
            assertEquals("none", NetworkWatch.type)
            assertEquals(listOf("vpn" to before + 1, "cellular" to before + 2, "none" to before + 3), notices)
        } finally {
            NetworkWatch.listener = priorListener
        }
    }
}
