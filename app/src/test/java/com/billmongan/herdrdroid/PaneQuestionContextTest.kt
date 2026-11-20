package com.billmongan.herdrdroid

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.hermes.Ask
import com.billmongan.herdrdroid.hermes.HermesChat
import com.billmongan.herdrdroid.hermes.HermesPhase
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.transcript.ImageItem
import com.billmongan.herdrdroid.transcript.Item
import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.ToolItem
import com.billmongan.herdrdroid.transcript.ToolState
import com.billmongan.herdrdroid.transcript.UserItem
import com.billmongan.herdrdroid.ui.AgentScreen
import com.billmongan.herdrdroid.ui.HermesLiveChat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w420dp-h1200dp")
class PaneQuestionContextTest {
    @get:Rule val rule = createComposeRule()

    @Test fun activeQuestionDoesNotRepeatTranscriptProse() = withPane(items = listOf(reply)) {
        assertClaudeQuestion()
        rule.onAllNodesWithText(reply.text).assertCountEquals(1)
    }

    @Test fun activeQuestionDoesNotRepeatToolAttachments() = withPane(
        items = listOf(tool("SendUserFile").copy(attach = listOf("/fixture/question-context.pdf"))),
    ) {
        assertClaudeQuestion()
        rule.onAllNodesWithText("question-context.pdf").assertCountEquals(1)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun activeQuestionDoesNotRepeatImages() = withPane(
        items = listOf(ImageItem("question-context-image", path = "/fixture/question-context.png")),
        seedImage = true,
    ) {
        assertClaudeQuestion()
        fun images() = rule.onAllNodesWithContentDescription("question-context.png")
        rule.waitUntil(5_000) { images().fetchSemanticsNodes().isNotEmpty() }
        images().assertCountEquals(1)
    }

    @Test fun activeQuestionDoesNotAttributeAnUnrelatedRunningEdit() = withPane(
        items = listOf(tool("Edit").copy(path = "/fixture/old.txt", patch = "+Old edit")),
    ) {
        assertClaudeQuestion()
        rule.onNodeWithText("Proposed change · old.txt").assertDoesNotExist()
    }

    @Test fun activeQuestionDoesNotAttributeAnUnrelatedRunningPlan() = withPane(
        items = listOf(tool("ExitPlanMode").copy(input = """{"plan":"Earlier plan"}""")),
    ) {
        assertClaudeQuestion()
        rule.onNodeWithText("Earlier plan").assertDoesNotExist()
    }

    @Test fun screenFallbackStillShowsAntigravityQuestion() = withPane(
        agent = "agy", frame = "agy/derived-1.4.9/1101-run-command.txt", mode = TranscriptMode.OFF,
        items = listOf(reply),
    ) {
        rule.onNodeWithText("Your answer").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Yes, run command").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Show terminal").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(reply.text).assertDoesNotExist()
    }

    @Test fun multilineCodexApprovalShowsTheScriptAndBothChoices() = withPane(
        agent = "codex", frame = "codex/synthetic-2.0/multiline-command-approval.txt",
        items = listOf(reply),
    ) {
        rule.onNodeWithText("\n  )\n", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Would you like to run the following command?").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Yes, proceed (y)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("No, and tell Codex what to do differently (esc)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Show terminal").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("+ Show details").assertDoesNotExist()
    }

    @Test fun openCodeApprovalShowsCommandAndOnlyOneTimeOrRejectControls() = withPane(
        agent = "opencode-ursinus", frame = "opencode/derived-1.4.9/0701-permission-prompt-all-tools.txt",
        items = listOf(reply), checkedTarget = true,
    ) {
        rule.onNodeWithText("npm install --prefix", substring = true).performScrollTo().assertIsDisplayed()
        for (label in listOf("Allow once", "Reject", "Show terminal")) {
            rule.onNode(hasText(label) and hasClickAction()).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("Always allow").assertDoesNotExist()
        rule.onNodeWithText("Type a different answer").assertDoesNotExist()
    }

    @Test fun openCodeQuestionChoicesAndTerminalAreReachableInChat() = withPane(
        agent = "opencode", frame = "opencode/derived-1.4.9/0704-question-tool.txt",
        items = listOf(reply), checkedTarget = true,
    ) {
        for (label in listOf("SQLite", "Postgres", "Type your own answer", "Show terminal")) {
            rule.onNode(hasText(label) and hasClickAction()).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun incompleteApprovalStillOpensTerminalWithoutOfferingAnAnswer() = withPane(
        agent = "codex", frame = "codex/synthetic-2.0/truncated-command-approval.txt",
        items = listOf(reply),
    ) {
        rule.onNodeWithText("This preview is incomplete.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Your answer").assertDoesNotExist()
        rule.onNodeWithText("Yes, proceed (y)").assertDoesNotExist()
        rule.onNodeWithText("Type answer").assertDoesNotExist()
        rule.onNodeWithText("Show terminal").performScrollTo().performClick()
        rule.onNodeWithText("Terminal").assertIsDisplayed()
        rule.onNodeWithText("Back to the question").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun optionsAndTerminalRemainReachableOnAShortScreen() = withPane(items = listOf(reply)) {
        rule.onNode(hasText("Yes") and hasClickAction()).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Show terminal").performScrollTo().assertIsDisplayed()
    }

    @Test fun hermesGatewayStillShowsItsOwnApproval() {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        // Seed conversation state without starting a gateway session or sending an answer.
        val ssh = HerdrSsh("fixture.invalid", 22, "fixture", Credentials(null, null), null, { null })
        val chat = HermesChat(ssh, vm.viewModelScope)
        try {
            rule.runOnUiThread {
                state<HermesPhase>(chat, "phase").value = HermesPhase.CHAT
                state<List<Item>>(chat, "items").value = listOf(reply)
                state<List<Ask>>(chat, "asks").value = listOf(
                    Ask.Approval("fixture", "printf fixture", "Gateway approval", listOf("once", "deny")),
                )
                field(vm, "hermes").set(vm, chat)
            }
            rule.setContent { HermesLiveChat(vm) }
            rule.onNodeWithText("HERMES WANTS TO RUN").assertIsDisplayed()
            rule.onNodeWithText("Allow once").assertIsDisplayed()
            rule.onAllNodesWithText(reply.text).assertCountEquals(1)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun assertClaudeQuestion() {
        rule.onAllNodesWithText("Do you want to proceed?").assertCountEquals(1)
        rule.onNode(hasText("Yes") and hasClickAction()).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Show terminal").performScrollTo().assertIsDisplayed()
    }

    private fun withPane(
        agent: String = "claude",
        frame: String = "claude/captured-2026-11-13/bash-permission.txt",
        mode: TranscriptMode = TranscriptMode.ON,
        items: List<Item>,
        seedImage: Boolean = false,
        checkedTarget: Boolean = false,
        verify: () -> Unit,
    ) {
        val text = checkNotNull(javaClass.getResource("/screens/$frame")).readText()
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val pane = Pane("w1:p1", "w1:t1", "w1", agent, Status.NEEDS_YOU, "", "/fixture")
        try {
            rule.runOnUiThread {
                state<List<Pane>>(vm, "panes").value = listOf(pane)
                vm.openPane(pane.id)
                state<TranscriptMode>(vm, "transcriptState").value = mode
                state<List<Item>>(vm, "transcriptItems").value = listOf(UserItem("user", "Check this")) + items
                if (seedImage) {
                    val png = ByteArrayOutputStream().also {
                        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
                    }.toByteArray()
                    @Suppress("UNCHECKED_CAST")
                    val cache = field(vm, "attachmentCache").get(vm) as LruCache<String, ByteArray>
                    cache.put("file:/fixture/question-context.png", png)
                }
                state<String>(vm, "paneText").value = text
                if (checkedTarget) {
                    // Render-only fixture. SuccessiveCardAnswerTest exercises target validation and dispatch.
                    val client = Herdr(HerdrSsh("fixture.invalid", 22, "fixture", Credentials(null, null), null, { null }), "/fixture/herdr")
                    state<AppViewModel.CardTarget?>(vm, "paneTextTarget").value =
                        AppViewModel.CardTarget(client, Any(), "fixture", 0, pane)
                }
            }
            rule.setContent { AgentScreen(vm, pane.id, readScreen = { /* no live pane I/O */ }) }
            verify()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private val reply = TextItem("reply", "Transcript context sentinel")

    private fun tool(name: String) = ToolItem(
        key = name, id = "tool-$name", name = name, title = name, kind = "tool", input = null,
        diff = null, patch = null, out = null, state = ToolState.RUNNING,
    )

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(owner: Any, property: String): MutableState<T> =
        field(owner, "$property\$delegate").get(owner) as MutableState<T>
}
