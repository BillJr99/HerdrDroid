package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.voice.VoiceCommand
import com.billmongan.herdrdroid.voice.VoiceMessageDraft
import com.billmongan.herdrdroid.voice.parseVoiceCommand
import com.billmongan.herdrdroid.voice.voiceCommandHelp
import com.billmongan.herdrdroid.voice.voiceName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PromptRelayTest {
    private val pane = Pane("pane-1", "tab-1", "ws-1", "codex", Status.IDLE, "Codex", "/project", scrollOffset = 0, terminalId = "term-1", sessionId = "session-1")
    private class Host(var pane: Pane?) : PromptRelayPort {
        var connection: Any = Any()
        var viewport = "❯ "
        var busy = false
        var beforeViewport: () -> Unit = {}
        var beforeWrite: () -> Unit = {}
        val writes = mutableListOf<Pair<String, String>>()
        override val connectionIdentity get() = connection
        override suspend fun pane(id: String, connection: Any) = pane?.takeIf { it.id == id && this.connection === connection }
        override suspend fun hasWork(pane: Pane, connection: Any) = busy
        override suspend fun viewport(id: String, connection: Any): String { beforeViewport(); return viewport }
        override suspend fun send(pane: Pane, text: String, connection: Any) {
            assertSame(this.connection, connection)
            writes += pane.id to text
            beforeWrite()
        }
    }
    private val local = Host(pane)
    private val remote = Host(pane)
    private val hosts = mutableMapOf("local" to local, "remote" to remote)
    private val dispatch = PaneDispatch()
    private var localClear = true
    private val relay = PromptRelay(dispatch, hosts::get) { _, _ -> localClear }

    @Test fun `same pane ID on two hosts routes only to the named host`() = runBlocking {
        val target = checkNotNull(relay.bind("remote", pane))
        val text = "Review the build\nLiteral 'quotes' and \$variables."
        assertEquals(RelayResult.SENT, relay.send(target, text) { true })
        assertEquals(listOf(pane.id to text), remote.writes)
        assertTrue(local.writes.isEmpty())
    }

    @Test fun `documented voice send reaches the remote fixture only after readback and confirm`() = runBlocking {
        val example = "Send a message to Codex on Laptop"
        assertTrue(example in voiceCommandHelp)
        val command = parseVoiceCommand(example) as VoiceCommand.Message
        assertEquals("codex", command.pane.name)
        assertEquals(voiceName("Laptop"), voiceName(checkNotNull(command.pane.server)))
        val target = checkNotNull(relay.bind("remote", pane))
        val draft = VoiceMessageDraft<PromptRelay.Target> { 0L }
        draft.begin(target, "Codex on Laptop")
        val dictate = parseVoiceCommand("Dictate Check the build") as VoiceCommand.Dictate
        val preview = checkNotNull(draft.dictate(draft.activeTarget(relay::isCurrent), dictate.text))
        assertEquals("Codex on Laptop", preview.label)
        assertEquals("Check the build", preview.text)
        assertTrue(remote.writes.isEmpty())
        draft.onReadBack(preview)
        assertEquals(VoiceCommand.ConfirmMessage, parseVoiceCommand("Confirm message"))
        val confirmed = checkNotNull(draft.consume(draft.activeTarget(relay::isCurrent)))
        assertEquals(RelayResult.SENT, relay.send(confirmed.target, confirmed.text) { draft.isFresh(confirmed) })
        assertNull(draft.consume(target))
        assertEquals(listOf(pane.id to "Check the build"), remote.writes)
        assertTrue(local.writes.isEmpty())
    }

    @Test fun `absent or unidentifiable targets do not bind`() = runBlocking {
        assertNull(relay.bind("missing", pane))
        remote.pane = pane.copy(terminalId = null, sessionId = null)
        assertNull(relay.bind("remote", remote.pane!!))
        remote.pane = pane.copy(sessionId = "replacement")
        assertNull(relay.bind("remote", pane))
    }

    @Test fun `a replacement pane or connection after binding never receives the text`() = runBlocking {
        val target = checkNotNull(relay.bind("remote", pane))
        remote.beforeViewport = { remote.pane = pane.copy(terminalId = "replacement") }
        assertEquals(RelayResult.REFUSED, relay.send(target, "test") { true })
        remote.pane = pane
        remote.beforeViewport = { remote.connection = Any() }
        assertEquals(RelayResult.REFUSED, relay.send(target, "test") { true })
        assertTrue(remote.writes.isEmpty())
    }

    @Test fun `unclear input busy status background work queued input and expiry refuse`() = runBlocking {
        val target = checkNotNull(relay.bind("remote", pane))
        for (status in listOf(Status.NEEDS_YOU, Status.WORKING, Status.UNKNOWN)) {
            remote.pane = pane.copy(status = status)
            assertEquals(RelayResult.REFUSED, relay.send(target, "test") { true })
        }
        remote.pane = pane
        for (viewport in listOf("", "no composer", "❯ 1. Yes\n  2. No")) {
            remote.viewport = viewport
            assertEquals(RelayResult.REFUSED, relay.send(target, "test") { true })
        }
        // 2.1.0: a draft is still not typed over, and the refusal names it.
        remote.viewport = "❯ unsent input"
        assertEquals(RelayResult.DRAFT, relay.send(target, "test") { true })
        remote.viewport = "❯ "
        remote.busy = true
        assertEquals(RelayResult.REFUSED, relay.send(target, "test") { true })
        remote.busy = false
        localClear = false
        assertEquals(RelayResult.REFUSED, relay.send(target, "test") { true })
        localClear = true
        var allowed = true
        remote.beforeViewport = { allowed = false }
        assertEquals(RelayResult.REFUSED, relay.send(target, "test") { allowed })
        assertTrue(remote.writes.isEmpty())
    }

    @Test fun `contention refuses and an uncertain send is never retried`() = runBlocking {
        val target = checkNotNull(relay.bind("remote", pane))
        val release = checkNotNull(dispatch.tryAcquire("remote" to pane.id))
        assertEquals(RelayResult.REFUSED, relay.send(target, "test") { true })
        release()
        remote.beforeWrite = { error("lost acknowledgement") }
        assertEquals(RelayResult.UNCERTAIN, relay.send(target, "test") { true })
        assertEquals(listOf(pane.id to "test"), remote.writes)
        checkNotNull(dispatch.tryAcquire("remote" to pane.id)).invoke()
    }
}
