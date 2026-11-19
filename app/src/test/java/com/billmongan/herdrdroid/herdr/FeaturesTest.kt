package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.hermes.hermesMessageText
import com.billmongan.herdrdroid.hermes.hermesMessageTime
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


    @Test fun hermesMessagesReadTheirWordsFromContentToo() {
        val f: (org.json.JSONObject) -> String = { hermesMessageText(it) }
        assertEquals("hi", f(org.json.JSONObject().put("role", "user").put("text", "hi")))
        assertEquals("from content", f(org.json.JSONObject().put("role", "user").put("content", "from content")))
        assertEquals("a\nb", f(org.json.JSONObject().put("role", "assistant")
            .put("content", org.json.JSONArray().put(org.json.JSONObject().put("type", "text").put("text", "a")).put("b"))))
        assertEquals("from content", f(org.json.JSONObject().put("text", org.json.JSONObject.NULL).put("content", "from content")))
    }

    @Test fun hermesHistoryParsesFractionalUnixSecondsAndLeavesUnknownTimesAbsent() {
        assertEquals(1700000000125L, hermesMessageTime(org.json.JSONObject("""{"timestamp":1700000000.125}""")))
        for (row in listOf("{}", """{"timestamp":null}""", """{"timestamp":0}""", """{"timestamp":"invalid"}""")) {
            assertNull(row, hermesMessageTime(org.json.JSONObject(row)))
        }
    }

    @Test fun theModeChipReadsTheFooter() {
        assertEquals("plan", agentMode("claude", "❯ \n  ⏸ plan mode on (shift+tab to cycle)"))
        assertEquals("accept edits", agentMode("claude", "❯ \n  ⏵⏵ accept edits on (shift+tab to cycle)"))
        assertEquals("auto", agentMode("claude", "❯ \n  » auto mode on (shift+tab to cycle) · esc to interrupt"))
        assertEquals("default", agentMode("claude", "❯ \n  ? for shortcuts"))
        assertNull(agentMode("codex", "› \n  100% context left"))
    }

    @Test fun theNetworkFinderChecksOneSubnetAndReadsSshVersions() {
        val hosts = com.billmongan.herdrdroid.net.subnetHosts("192.0.2.19", 24)
        assertEquals(253, hosts.size)
        assertTrue("192.0.2.26" in hosts)
        assertFalse("192.0.2.19" in hosts)
        assertFalse("192.0.2.0" in hosts || "192.0.2.255" in hosts)
        // A wide network is checked only around the phone, never 65,000 addresses.
        assertEquals(253, com.billmongan.herdrdroid.net.subnetHosts("198.51.100.7", 16).size)
        assertEquals("OpenSSH 9.6p1, Ubuntu", com.billmongan.herdrdroid.net.sshVersion("SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.5"))
        assertEquals("dropbear 2022.83", com.billmongan.herdrdroid.net.sshVersion("SSH-2.0-dropbear_2022.83"))
        assertNull(com.billmongan.herdrdroid.net.sshVersion("HTTP/1.1 400 Bad Request"))
    }
}
