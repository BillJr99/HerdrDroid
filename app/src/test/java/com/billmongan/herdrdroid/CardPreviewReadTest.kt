package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.codexFreeformQuestion
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ssh.KeySource
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

/** Runs against an isolated loopback SSH server with tools/card-preview/herdr.py as its CLI. */
@RunWith(AndroidJUnit4::class)
class CardPreviewReadTest {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    @Test fun workingCodexFindsANativeQuestionMissingFromRecentOutput() = withFixture { f ->
        val recent = "Codex is preparing a response."
        f.select("codex", Status.WORKING)
        f.frames(recent, codexFreeformQuestion)
        f.read()
        assertEquals(listOf("recent", "visible"), f.trace())
        assertEquals(codexFreeformQuestion, f.vm.paneText)
        assertTrue(detectPrompt(f.vm.paneText, false, "codex")!!.terminalReview)

        val partial = codexFreeformQuestion.substringBefore("\nenter to submit")
        f.frames(partial, codexFreeformQuestion)
        f.read()
        assertEquals(listOf("recent", "visible"), f.trace())
        assertEquals(codexFreeformQuestion, f.vm.paneText)

        f.frames(codexFreeformQuestion, "› Next request")
        f.read()
        assertEquals(listOf("recent", "visible"), f.trace())
        assertEquals("› Next request", f.vm.paneText)

        val approval = resource("/screens/codex/synthetic-2.0/multiline-command-approval.txt")
        f.frames(codexFreeformQuestion, approval)
        f.read()
        assertEquals(listOf("recent", "visible"), f.trace())
        assertEquals(approval, f.vm.paneText)

        f.frames(codexFreeformQuestion, "")
        f.read()
        assertEquals(listOf("recent", "visible"), f.trace())
        assertNull(f.vm.paneTextTarget)

        f.select("codex", Status.WORKING, scrollOffset = 1)
        f.frames(recent, codexFreeformQuestion)
        f.read()
        assertEquals(listOf("recent"), f.trace())
        assertEquals(recent, f.vm.paneText)
    }

    @Test fun workingCodexUpgradesAReviewOnlyCaptureBeforeBulkCompletes() = withFixture { f ->
        val clipped = codex()
        val complete = clipped.lineSequence().filterNot { it.contains("lines omitted") }.joinToString("\n")
        assertTrue(detectPrompt(clipped, false, "codex")!!.terminalReview)
        assertTrue(detectPrompt(complete, false, "codex")!!.choices.isNotEmpty())
        f.frames(clipped, complete)
        f.select("codex", Status.WORKING)
        val bulk = async(Dispatchers.IO) { f.ssh.exec("${f.cli} bulk", timeoutSeconds = 25) }
        try {
            withTimeout(10_000) { while (!f.file("bulk-started").exists()) delay(10) }
            val elapsed = f.read()
            println("Codex preview read: $elapsed ms; actionable=${detectPrompt(f.vm.paneText, false, "codex")?.choices.orEmpty().isNotEmpty()}")
            assertEquals(complete, f.vm.paneText)
            assertEquals(listOf("recent", "visible"), f.trace())
            assertFalse(bulk.isCompleted)
        } finally {
            f.file("release").writeText("")
            assertEquals(0, bulk.await().exit)
        }
    }

    @Test fun workingCodexFindsAnApprovalMissingFromTheRecentCapture() = withFixture { f ->
        val complete = resource("/screens/codex/synthetic-2.0/multiline-command-approval.txt")
        val recent = "  + Show details\n"
        val styledRecent = "\u001B[2m  + Show details\u001B[0m\n"
        val styledVisible = complete.lineSequence().joinToString("\n") { "\u001B[1m$it\u001B[0m" }
        assertNull(detectPrompt(recent, false, "codex"))
        f.select("codex", Status.WORKING)
        f.frames(styledRecent, styledVisible)
        f.read()
        assertEquals(complete, f.vm.paneText)
        assertEquals(listOf("recent", "visible"), f.trace())
        assertEquals(2, detectPrompt(f.vm.paneText, false, "codex")!!.choices.size)

        val partial = complete.substringAfter("  $ set -e\n")
        f.frames(recent, partial)
        f.read()
        assertTrue(detectPrompt(f.vm.paneText, false, "codex")!!.terminalReview)
        assertEquals(listOf("recent", "visible"), f.trace())

        f.frames(recent, "$complete\n› Next request")
        f.read()
        assertEquals(recent, f.vm.paneText)
        assertEquals(listOf("recent", "visible"), f.trace())

        f.select("codex", Status.WORKING, scrollOffset = 1)
        f.frames(recent, complete)
        f.read()
        assertEquals(recent, f.vm.paneText)
        assertEquals(listOf("recent"), f.trace())
    }

    @Test fun workingClaudeFindsAQuestionMissingFromRecentOutput() = withFixture { f ->
        val complete = resource("/screens/claude/captured-2026-11-13/multi-question.txt")
        val recent = "Claude is preparing a response."
        assertNull(detectPrompt(recent, false, "claude"))
        assertTrue(detectPrompt(complete, false, "claude")!!.choices.isNotEmpty())
        f.select("claude", Status.WORKING)
        f.frames(recent, complete)
        f.read()
        assertEquals(listOf("recent", "visible"), f.trace())
        assertEquals(complete, f.vm.paneText)

        f.frames(recent, "❯ Next request")
        f.read()
        assertEquals(listOf("recent", "visible"), f.trace())
        assertEquals(recent, f.vm.paneText)

        f.frames(complete, "❯ Next request")
        f.read()
        assertEquals(listOf("recent"), f.trace())
        assertEquals(complete, f.vm.paneText)

        f.select("claude", Status.WORKING, scrollOffset = 1)
        f.frames(recent, complete)
        f.read()
        assertEquals(listOf("recent"), f.trace())
        assertEquals(recent, f.vm.paneText)
    }

    @Test fun blockedClaudeQuestionUpgradesARecentCaptureWithNoChoices() = withFixture { f ->
        val complete = resource("/screens/claude/captured-2026-11-13/multi-question.txt")
        val recent = "Which sample should be used?"
        assertTrue(detectPrompt(recent, true, "claude")?.choices.orEmpty().isEmpty())
        assertTrue(detectPrompt(complete, true, "claude")!!.choices.isNotEmpty())
        f.frames(recent, complete)
        f.select("claude", Status.NEEDS_YOU)
        f.read()
        assertEquals(complete, f.vm.paneText)
    }

    @Test fun probesDoNotUnlockStillHiddenBlankOrScrolledPreviews() = withFixture { f ->
        val clipped = codex()
        f.select("codex", Status.WORKING)
        for (visible in listOf(clipped, "", "› Next request")) {
            f.frames(clipped, visible)
            f.read()
            assertEquals(listOf("recent", "visible"), f.trace())
            val shown = detectPrompt(f.vm.paneText, false, "codex")!!
            assertTrue(shown.terminalReview)
            assertTrue(shown.choices.isEmpty())
        }
        val complete = clipped.lineSequence().filterNot { it.contains("lines omitted") }.joinToString("\n")
        f.select("codex", Status.WORKING, scrollOffset = 1)
        f.frames(clipped, complete)
        f.read()
        assertEquals(listOf("recent"), f.trace())
        assertEquals(clipped, f.vm.paneText)
    }

    @Test fun completeRecentApprovalNeedsNoExtraRead() = withFixture { f ->
        val complete = codex().lineSequence().filterNot { it.contains("lines omitted") }.joinToString("\n")
        f.select("codex", Status.WORKING)
        f.frames(complete, "› Next request")
        f.read()
        assertEquals(listOf("recent"), f.trace())
        assertEquals(complete, f.vm.paneText)
    }

    @Test fun workingAntigravityFindsAnApprovalMissingFromRecentOutput() = withFixture { f ->
        val complete = resource("/screens/agy/derived-1.4.9/1101-run-command.txt")
        val recent = "Antigravity is preparing a response."
        val partial = complete.substringBefore("❯ 1.")
        assertNotEquals(complete, partial)
        assertNull(detectPrompt(recent, false, "agy"))
        assertTrue(detectPrompt(partial, false, "agy")?.choices.orEmpty().isEmpty())
        assertEquals(4, detectPrompt(complete, false, "agy")!!.choices.size)
        f.select("agy", Status.WORKING)
        for (preview in listOf(recent, partial)) {
            f.frames(preview, complete)
            f.read()
            assertEquals(listOf("recent", "visible"), f.trace())
            assertEquals(complete, f.vm.paneText)
            assertNotNull(f.vm.paneTextTarget)
        }
    }

    @Test fun antigravityProbeDoesNotPromoteInactiveOrIncompleteFrames() = withFixture { f ->
        val complete = resource("/screens/agy/derived-1.4.9/1101-run-command.txt")
        val recent = "Antigravity is preparing a response."
        f.select("agy", Status.WORKING)
        for (visible in listOf("", " > Next request", complete.substringBefore("❯ 1."))) {
            f.frames(recent, visible)
            f.read()
            assertEquals(listOf("recent", "visible"), f.trace())
            assertEquals(recent, f.vm.paneText)
        }
        f.frames(complete, " > Next request")
        f.read()
        assertEquals(listOf("recent"), f.trace())
        assertEquals(complete, f.vm.paneText)

        f.select("agy", Status.WORKING, scrollOffset = 1)
        f.frames(recent, complete)
        f.read()
        assertEquals(listOf("recent"), f.trace())
        assertEquals(recent, f.vm.paneText)
    }

    private fun codex() = resource("/screens/codex/synthetic-2.0/truncated-command-approval.txt")
    private fun resource(path: String) = checkNotNull(javaClass.getResource(path)).readText()

    private fun withFixture(body: suspend kotlinx.coroutines.CoroutineScope.(Fixture) -> Unit) = runBlocking {
        val dir = System.getenv("HD_PREVIEW_FIXTURE_DIR")?.let(::File)
        assumeTrue("no isolated card preview SSH fixture", dir != null)
        val fixture = Fixture(checkNotNull(dir))
        try {
            assertEquals(0, fixture.ssh.execNow("true").exit)
            body(fixture)
        } finally {
            fixture.vm.clearForTest()
            fixture.ssh.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class Fixture(private val dir: File) {
        val cli = file("preview-cli").path
        private val server = Server(id = "test", name = "Test", host = "127.0.0.1", port = 2222,
            user = System.getenv("HD_PREVIEW_FIXTURE_USER") ?: "dev", herdrPath = cli)
        val ssh = HerdrSsh(server.host, server.port, server.user,
            Credentials(KeySource.Imported(file("client-key").readText(), null), null),
            file("pin").readText().trim(), { null })
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())

        fun file(name: String) = File(dir, name)
        fun trace() = file("preview-trace").readLines()
        fun frames(recent: String, visible: String) {
            file("recent").writeText(recent)
            file("visible").writeText(visible)
            file("preview-trace").writeText("")
            file("bulk-started").delete()
            file("release").delete()
        }
        fun select(agent: String, status: Status, scrollOffset: Int = 0) {
            field("herdr").set(vm, Herdr(ssh, cli))
            state("current", server)
            state("panes", listOf(Pane("p1", "tab", "w", agent, status, "", "/fixture", scrollOffset = scrollOffset)))
            vm.openPane("p1")
        }
        suspend fun read(): Long {
            val start = System.nanoTime()
            vm.readPane("p1")
            return (System.nanoTime() - start) / 1_000_000
        }
        private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private fun state(name: String, value: Any?) {
            (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value
        }
    }
}
