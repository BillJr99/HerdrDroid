package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.ConnectionChanged
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RcHerdr228Test {
    @Test fun claudeScrollbackHintDoesNotMaskTheForegroundPermission() {
        val stale = "1 awaiting input\nYour session moved to the background (esc returns to it)"
        val permission = "$stale\n\nRun the sample?\n❯ 1. Yes\n  2. No\nEsc to cancel"
        assertNull(claudeBackgroundList(permission))
        assertEquals(0, claudeSessionsAwaiting(permission))
        assertFalse(detectPrompt(permission, true, "claude")?.claudeBackground == true)
        assertNull(claudeBackgroundList("$stale\n❯ "))
    }

    @Test fun titlelessOpenCodePermissionCannotFallThroughToGuessedFocus() {
        val screen = "┃  Allow once   Always allow   Reject\n┃  ⇆ select enter confirm"
        for (agent in listOf("opencode", "opencode-named")) {
            val card = checkNotNull(detectPrompt(screen, true, agent))
            assertTrue(card.terminalReview)
            assertTrue(card.choices.isEmpty())
        }
    }

    @Test fun capturedOpenCodeSessionSidebarsStayOutOfFallbackChat() {
        fun capture(path: String) = checkNotNull(javaClass.getResource("/screens/opencode/$path")).readText()
        val views = listOf(
            capture("live-2026-11-22-session/sidebar-empty.ansi") to "PANESEND_PROBE",
            capture("live-2026-11-25-probe/oc-running.ansi") to "sleep 120",
        )
        for ((screen, message) in views) {
            val column = openCodeChatColumn(screen)
            assertTrue(column.contains(message))
            assertFalse(column.contains("Getting started"))
            assertFalse(column.contains("38,922 tokens"))
            assertFalse(column.contains("mcpproxy"))
            val chat = Reflow.parse(screen, agent = "opencode").joinToString(" ")
            assertFalse(chat.contains("Getting started"))
            assertFalse(chat.contains("mcpproxy"))
        }
        assertEquals("Context\n4 tokens\n40% used", openCodeChatColumn("Context\n4 tokens\n40% used"))
    }

    @Test fun relayUploadCannotFollowAReplacedConnection() = runBlocking {
        val host = Host()
        val original = host.connection
        val port = host.herdr.relayPort(ByteArray(0))
        host.connection = Any()
        val result = runCatching { port.upload("sample.txt", byteArrayOf(1), original) }
        assertTrue(result.exceptionOrNull() is ConnectionChanged)
        assertEquals(0, host.uploads)
    }

    @Test fun forcedCoreWriteKeepsThePinnedConnection() = runBlocking {
        val host = Host(backend = Backend.CORE)
        val pane = Pane("p1", "t1", "w1", "claude", Status.NEEDS_YOU, "", "/fixture")
        host.herdr.sendMessage(pane, "answer", expectedConnection = host.connection)
        assertSame(host.connection, host.writes.single().second)
        assertTrue(host.writes.single().first.contains("'--force'"))
    }

    @Test fun timeoutWithoutAnyMarkerIsUncertain() = runBlocking {
        val host = Host(result = ExecResult(-2, "", "command timed out"))
        val typed = runCatching { host.herdr.typeAndEnter("p1", "sample", host.connection) }
        assertTrue(typed.exceptionOrNull() is DeliveryUncertain)
        val card = runCatching {
            host.herdr.sendCardInput("p1", CardInput.Keys(listOf("enter")), CardGuard("occupant", "digest"), host.connection)
        }
        assertTrue(card.exceptionOrNull() is DeliveryUncertain)
        assertEquals(2, host.writes.size)
    }

    private class Host(backend: Backend = Backend.TUI, private val result: ExecResult = ExecResult(0, "", "")) : Herdr.Commands {
        override var connection = Any()
        val writes = mutableListOf<Pair<String, Any?>>()
        var uploads = 0
        val herdr = Herdr(HerdrSsh("127.0.0.1", 1, "fixture", Credentials(null, null), null, { null }),
            "/fixture/herdr", backend, null, this)
        override suspend fun bulk(command: String): ExecResult = error("Unexpected bulk command")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            writes += command to expectedConnection
            return result
        }
        override suspend fun input(command: String, input: ByteArray, expectedConnection: Any): ExecResult {
            uploads++
            return result
        }
    }

    @Test fun standingPermissionTargetMayContainBlock() {
        assertTrue(grantsStandingPermission("Yes, allow all edits to block.kt during this session"))
        assertFalse(grantsStandingPermission("Block, do not allow these edits"))
    }

    @Test fun grokFolderTrustConfirmationKnowsItsNativeKey() {
        val screen = "Trust this folder?\ny  Yes, trust this folder\nn  No, quit\nEnter or y to trust · n or Esc to quit"
        val card = checkNotNull(detectLetterMenu(screen, true))
        val approval = approveAllAsk(card, card.choices.first())
        assertNotNull(approval)
        assertEquals(listOf("y"), approval?.keys)
    }
}
