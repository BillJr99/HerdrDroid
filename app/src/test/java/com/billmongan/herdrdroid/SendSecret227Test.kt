package com.billmongan.herdrdroid

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.diag.DiagLog
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.SecretDrop
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.sha256Hex
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.ui.AgentScreen
import com.billmongan.herdrdroid.ui.SEND_SECRET_ITEM
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 2.2.7 Send a secret: the "⋯" item, the masked box, and only the path in the message box. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1000dp")
class SendSecret227Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private val secret = "gho_FIXTUREnotRealSecret123"

    @Before fun plainSeal() {
        DraftSeal.seal = { it }
        DraftSeal.open = { it }
    }

    private class Computer : Herdr.Commands {
        override val connection: Any? = Any()
        val writes = mutableListOf<String>()
        val inputs = mutableListOf<Pair<String, ByteArray>>()
        private fun pane(id: String, agent: String) = """{"pane_id":"$id","workspace_id":"w1","tab_id":"tab-$id","agent":"$agent","agent_status":"idle","cwd":"/fixture","terminal_id":"t-$id","scroll":{"offset_from_bottom":0}}"""
        private val panes = pane("p1", "claude") + "," + pane("p2", "codex")
        override suspend fun bulk(command: String) = ExecResult(0, """{"result":{"panes":[$panes]}}
            |@@herdrdroid-tabs@@
            |{"result":{"tabs":[]}}
        """.trimMargin(), "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            if (command.contains("send-") || command.contains("'agent' 'prompt'")) writes += command
            if (command.endsWith("'pane' 'get' 'p1'")) return ExecResult(0, """{"result":{"pane":${pane("p1", "claude")}}}""", "")
            if (command.endsWith("'pane' 'get' 'p2'")) return ExecResult(0, """{"result":{"pane":${pane("p2", "codex")}}}""", "")
            // An idle input line, so the screen is read as ready (an empty read keeps a loading state up).
            if (command.contains("'pane' 'read'")) return ExecResult(0, "──\n❯ \n──", "")
            return ExecResult(0, "", "")
        }
        override suspend fun input(command: String, input: ByteArray, expectedConnection: Any): ExecResult {
            inputs += command to input.copyOf()
            if (".herdrdroid/secrets" !in command) return ExecResult(0, "", "")
            val name = Regex("chmod 600 [^ ]*secrets'/'([^']+)'").find(command)!!.groupValues[1]
            return ExecResult(0, "${SecretDrop.MARKER}\n/home/u/.herdrdroid/secrets/$name\n${input.size}\n${sha256Hex(input)}\n600\n700\n", "")
        }
    }

    private fun setUp(commands: Computer): AppViewModel {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val server = Server(id = "secret-fixture", name = "Fixture", host = "unused.invalid", port = 22, user = "u", herdrPath = "herdr")
        fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        fun <T> state(name: String) = field("$name\$delegate").get(vm) as MutableState<T>
        rule.runOnUiThread {
            field("herdr").set(vm, Herdr(HerdrSsh("unused.invalid", 22, "u", Credentials(null, null), null, { null }), "herdr", Backend.TUI, null, commands))
            state<Server?>("current").value = server
            state<Link>("link").value = Link.CONNECTED
            state<List<Pane>>("panes").value = listOf(
                Pane("p1", "tab-p1", "w1", "claude", Status.IDLE, "", "/fixture", tabLabel = "claude", terminalId = "t-p1"),
                Pane("p2", "tab-p2", "w1", "codex", Status.IDLE, "", "/fixture", tabLabel = "codex", terminalId = "t-p2"),
            )
            field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
            field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
            field("readers").set(vm, emptySet<String>())
            vm.openPane("p1")
        }
        return vm
    }

    private fun holdsText(t: String) = SemanticsMatcher("text contains $t") { n ->
        (n.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text?.contains(t) == true) ||
            n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().any { it.text.contains(t) }
    }

    /**
     * Some reads (agent versions, quotas) go past the fake to a connection that can't resolve and
     * mark the link lost, as in SendNowMenu227Test; the send only needs the link up when it starts.
     */
    private fun relink(vm: AppViewModel) {
        fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        (field("reconnectJob").get(vm) as? kotlinx.coroutines.Job)?.cancel()
        @Suppress("UNCHECKED_CAST")
        (field("link\$delegate").get(vm) as MutableState<Link>).value = Link.CONNECTED
    }

    private fun target(vm: AppViewModel, paneId: String): com.billmongan.herdrdroid.herdr.SecretTarget {
        relink(vm)
        return vm.secretTargets().single { it.paneId == paneId }
    }

    // Known gap (2.2.7): with a text field in the dialog, Robolectric never went idle once the dialog
    // was open (lanes hd227s2-s17, also with every guard and element varied), so these tests open the
    // menu and drive the send through the view model the dialog calls; the dialog itself is tried on the phone.

    @Test fun theMenuOffersSendASecretAndTheOpenPaneGetsOnlyThePathAtTheCaret() {
        DiagLog.clear()
        val pc = Computer()
        val vm = setUp(pc)
        try {
            rule.setContent { AgentScreen(vm, "p1", readScreen = {}) }
            rule.onNode(hasSetTextAction()).performTextInput("read this:")
            rule.onNodeWithText("⋯").performClick()
            rule.onNodeWithText(SEND_SECRET_ITEM).assertExists()
            rule.onNodeWithText("Attach a file").assertExists()
            rule.runOnUiThread {
                relink(vm)
                val targets = vm.secretTargets()
                assertEquals(listOf("p1", "p2"), targets.map { it.paneId })
                vm.sendSecret(target(vm, "p1"), "oauth", secret.toByteArray())
            }
            rule.waitUntil(5_000) { vm.composerDraft("p1").contains("/home/u/.herdrdroid/secrets/") }
            rule.runOnIdle {
                val draft = vm.composerDraft("p1")
                assertTrue(draft, draft.startsWith("read this: /home/u/.herdrdroid/secrets/") && draft.trimEnd().endsWith("-oauth.txt"))
                assertFalse(draft.contains(secret))
                val writes = pc.inputs.filter { ".herdrdroid/secrets" in it.first }
                assertEquals(1, writes.size)
                assertArrayEquals(secret.toByteArray(), writes[0].second)
                assertFalse("never in the command", writes[0].first.contains(secret))
                assertTrue("not through the attach inbox", pc.inputs.none { ".herdrdroid/inbox" in it.first })
                assertTrue("nothing typed into a pane", pc.writes.isEmpty())
                assertTrue(vm.notice.orEmpty(), vm.notice.orEmpty().startsWith("Saved on Fixture as /home/u/.herdrdroid/secrets/"))
                assertTrue(vm.notice.orEmpty().endsWith("only the path goes in the chat."))
                assertFalse(DiagLog.text().contains(secret))
                assertFalse(DiagLog.text().contains(".herdrdroid/secrets"))
                assertNull(vm.secretInsert)
            }
            rule.onAllNodes(holdsText(secret), useUnmergedTree = true).assertCountEquals(0)
        } finally {
            rule.runOnUiThread { vm.viewModelScope.cancel() }
        }
    }

    @Test fun anotherPaneGetsThePathInItsDraftWithOpenPane() {
        val pc = Computer()
        val vm = setUp(pc)
        try {
            rule.setContent { AgentScreen(vm, "p1", readScreen = {}) }
            rule.runOnUiThread { vm.sendSecret(target(vm, "p2"), null, secret.toByteArray()) }
            rule.waitUntil(5_000) { vm.secretElsewhere != null }
            rule.runOnIdle {
                assertTrue(vm.composerDraft("p2").startsWith("/home/u/.herdrdroid/secrets/"))
                assertFalse("the open pane's box is untouched", vm.composerDraft("p1").contains(".herdrdroid"))
                assertEquals("p2", vm.secretElsewhere!!.paneId)
                assertTrue(vm.secretElsewhere!!.text.contains("Codex"))
                assertTrue(pc.writes.isEmpty())
            }
            rule.onNodeWithText("Open pane").assertExists()
        } finally {
            rule.runOnUiThread { vm.viewModelScope.cancel() }
        }
    }

    @Test fun aPaneGoneBeforeTheSaveGetsNothing() {
        val pc = Computer()
        val vm = setUp(pc)
        try {
            rule.setContent { AgentScreen(vm, "p1", readScreen = {}) }
            rule.runOnUiThread {
                val codex = target(vm, "p2")
                val f = AppViewModel::class.java.getDeclaredField("panes\$delegate").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (f.get(vm) as MutableState<List<Pane>>).value = vm.panes.filter { it.id == "p1" }
                vm.sendSecret(codex, null, secret.toByteArray())
            }
            rule.waitUntil(5_000) { vm.notice != null }
            rule.runOnIdle {
                assertTrue(vm.notice!!, vm.notice!!.contains("nothing was saved"))
                assertTrue(pc.inputs.none { ".herdrdroid/secrets" in it.first })
                assertEquals("", vm.composerDraft("p2"))
                assertFalse(vm.composerDraft("p1").contains(".herdrdroid"))
            }
        } finally {
            rule.runOnUiThread { vm.viewModelScope.cancel() }
        }
    }
}
