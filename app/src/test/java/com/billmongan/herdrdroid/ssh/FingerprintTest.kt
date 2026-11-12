package com.billmongan.herdrdroid.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerprintTest {
    private val b = "uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s"

    @Test fun acceptsTheUsualForms() {
        assertEquals("SHA256:$b", normalizeFingerprint("SHA256:$b"))
        assertEquals("SHA256:$b", normalizeFingerprint("  $b  "))
        assertEquals("SHA256:$b", normalizeFingerprint("256 SHA256:$b root@host (ED25519)"))
    }

    @Test fun rejectsOthers() {
        assertNull(normalizeFingerprint("SHA256:short"))
        assertNull(normalizeFingerprint("MD5:12:34"))
        assertNull(normalizeFingerprint(b + "x"))
    }
}
