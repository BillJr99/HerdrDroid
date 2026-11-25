package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.notify.alertPlan
import org.junit.Assert.*
import org.junit.Test

class KiroApprovalShortcuts228Test {
    private val frame = checkNotNull(javaClass.getResource(
        "/screens/kiro/derived-1.4.9/0901-default-page-label-set.txt"
    )).readText()

    @Test fun directKeysAreLimitedToOnceOnlyChoicesInTheExactModernFrame() {
        val prompt = checkNotNull(detectPrompt(frame, false, "kiro"))
        assertTrue(prompt.kiroOnceShortcuts)
        assertEquals(listOf("y"), menuKeys(prompt, prompt.choices.first { it.label == "Allow once" }))
        assertEquals(listOf("n"), menuKeys(prompt, prompt.choices.first { it.label == "Deny once" }))
        assertTrue(prompt.choices.filter { it.label.startsWith("Create") }.all {
            menuKeys(prompt, it).orEmpty().none { key -> key in setOf("t", "d") }
        })
        assertTrue(alertPlan(prompt).buttons.isEmpty())
    }

    @Test fun changedOrStaleFooterCannotAuthorizeDirectShortcuts() {
        assertNull(kiroApprovalShortcuts(frame + "\n› new input"))
        assertNull(kiroApprovalShortcuts(frame.replace("n deny once", "n never ask")))
        assertNull(kiroApprovalShortcuts(frame.replace("shell requires approval", "shell completed")))
        assertFalse(detectPrompt(frame, true, "claude")?.kiroOnceShortcuts == true)
    }
}
