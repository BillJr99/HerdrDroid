package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kiro CLI and Mistral Vibe: named, found, started, and recognized when herdr doesn't. */
class NewAgentsTest {
    @Test fun programNamesMapToAgents() {
        assertEquals("kiro", canonicalAgent("kiro-cli"))
        assertEquals("kiro", canonicalAgent("kiro"))
        assertEquals("vibe", canonicalAgent("vibe-rs"))
        assertEquals("vibe", canonicalAgent("mistral-vibe"))
    }

    @Test fun kiroRunsAsKiroCli() {
        assertEquals("kiro-cli", agentExecutable("kiro"))
        assertEquals("vibe", agentExecutable("vibe"))
        assertEquals("claude", agentExecutable("claude"))
    }

    @Test fun herdrStartsKiroButNotVibe() {
        assert("kiro" in HERDR_KINDS)
        assert("vibe" !in HERDR_KINDS)
    }

    @Test fun vibeIsKnownByItsTitle() {
        assertEquals("vibe", titleAgent("Vibe"))
        assertEquals("vibe", titleAgent("vibe - fixing tests"))
        assertNull(titleAgent("bill@demo-box2: ~/agents/vibe"))
        assertNull(titleAgent("Vibes check"))
        assertNull(titleAgent(""))
    }

    @Test fun displayNames() {
        assertEquals("Kiro", Pane(id = "p", tabId = "t", workspaceId = "w", agent = "kiro", status = Status.IDLE, title = "", cwd = "/").agentName)
        assertEquals("Vibe", Pane(id = "p", tabId = "t", workspaceId = "w", agent = "vibe", status = Status.UNKNOWN, title = "Vibe", cwd = "/").agentName)
    }

    @Test fun coreTerminalsRunningThemAreAgents() {
        val json = """{"terminals":[{"address":"a1","agent":"kiro-cli","status":"idle","title":"","cwd":"/k"},""" +
            """{"address":"a2","agent":"vibe","status":"","title":"Vibe","cwd":"/v"}]}"""
        val panes = corePanes(json)
        assertEquals(listOf("kiro", "vibe"), panes.map { it.agent })
    }
}
