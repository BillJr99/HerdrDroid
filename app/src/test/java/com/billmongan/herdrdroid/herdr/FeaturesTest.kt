package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.hermes.hermesMessageText
import com.billmongan.herdrdroid.ui.ModelPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {
    @Test fun usageAlertsFireOnceAtEachLineAndOnReset() {
        assertEquals(UsageChange.Crossed(80), usageChange(70.0, 82.0))
        assertEquals(UsageChange.Crossed(95), usageChange(70.0, 96.0))
        assertEquals(UsageChange.Crossed(95), usageChange(90.0, 95.0))
        assertNull("already past 80", usageChange(82.0, 85.0))
        assertNull("small drop", usageChange(60.0, 45.0))
        assertEquals(UsageChange.Reset, usageChange(97.0, 3.0))
        assertNull("low and lower", usageChange(30.0, 0.0))
    }

    @Test fun modelMenuOffersNamesAndEffortsOnlyWhereTheyWork() {
        assertTrue(ModelPresets.takesName("claude"))
        assertTrue(ModelPresets.takesName("Hermes"))
        assertFalse(ModelPresets.takesName("codex"))
        assertEquals("/reasoning high", ModelPresets.efforts("hermes").first { it.first == "high" }.second)
        assertTrue(ModelPresets.efforts("claude").isEmpty())
        assertTrue(ModelPresets.forAgent("claude").any { it.second == "/model fable" })
    }

    @Test fun hermesKnowsWhenItHasNoSession() {
        val p = QuotaProfiles.forAgent("hermes")!!
        assertTrue(p.noSession!!.containsMatchIn("(._.) No active agent -- send a message first."))
        assertTrue(p.startSession!!.isNotBlank())
        assertNull(QuotaProfiles.forAgent("claude")!!.startSession)
    }

    @Test fun hermesMessagesReadTheirWordsFromContentToo() {
        val f: (org.json.JSONObject) -> String = { hermesMessageText(it) }
        assertEquals("hi", f(org.json.JSONObject().put("role", "user").put("text", "hi")))
        assertEquals("from content", f(org.json.JSONObject().put("role", "user").put("content", "from content")))
        assertEquals("a\nb", f(org.json.JSONObject().put("role", "assistant")
            .put("content", org.json.JSONArray().put(org.json.JSONObject().put("type", "text").put("text", "a")).put("b"))))
        assertEquals("from content", f(org.json.JSONObject().put("text", org.json.JSONObject.NULL).put("content", "from content")))
    }
}
