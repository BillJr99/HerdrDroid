package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.detectPrompt
import kotlinx.coroutines.delay

private data class QuickKey(val label: String, val key: String, val description: String)

private val QUICK_KEYS = listOf(
    QuickKey("Esc", "esc", "Escape"),
    QuickKey("Tab", "tab", "Tab"),
    QuickKey("↑", "up", "Up arrow"),
    QuickKey("↓", "down", "Down arrow"),
    QuickKey("Ctrl+C", "ctrl+c", "Control C"),
    QuickKey("Enter", "enter", "Enter"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(vm: AppViewModel, paneId: String) {
    val pane = vm.pane(paneId)
    var message by rememberSaveable(paneId) { mutableStateOf("") }
    var fullTerminal by rememberSaveable { mutableStateOf(false) }
    var confirmCtrlC by remember { mutableStateOf(false) }
    var typingAnswer by remember { mutableStateOf(false) }

    // Re-read the pane every 1.5 seconds while this screen is open.
    LaunchedEffect(paneId) {
        while (true) {
            vm.readPane(paneId)
            delay(1_500)
        }
    }
    LaunchedEffect(paneId) {
        while (true) {
            delay(4_000)
            vm.refreshPanes()
        }
    }

    val status = pane?.status ?: Status.UNKNOWN
    val prompt: Prompt? = if (typingAnswer) null else detectPrompt(vm.paneText, status == Status.NEEDS_YOU)
    val scroll = rememberScrollState()
    LaunchedEffect(vm.paneText) { scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding().imePadding()) {
        Surface(color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.screen = com.billmongan.herdrdroid.Screen.Tabs }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to all tabs")
                }
                AgentTile(pane?.initial ?: "?", status, size = 36)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(pane?.agentName ?: paneId, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(look(status).label + " · " + paneId, color = look(status).ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { fullTerminal = !fullTerminal }) {
                    Text(if (fullTerminal) "Chat view" else "Terminal")
                }
            }
        }
        vm.error?.let { ErrorBanner(it) { vm.error = null } }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.lastSent?.takeIf { it.first == paneId }?.let { (_, text) ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text,
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .background(Palette.Blue, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            if (fullTerminal) {
                SelectionContainer {
                    Text(
                        vm.paneText.ifBlank { "Loading…" },
                        color = Palette.TerminalInk,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Palette.Terminal, RoundedCornerShape(10.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Palette.Card,
                    border = BorderStroke(1.dp, Palette.Line),
                ) {
                    SelectionContainer {
                        Text(
                            tidy(vm.paneText).ifBlank { "Loading…" },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = Palette.Ink,
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                        )
                    }
                }
            }
            prompt?.let { p ->
                PromptCard(
                    p,
                    agent = pane?.agentName ?: "The agent",
                    onChoice = { c -> vm.answer(paneId, c.keys, c.pressEnter) },
                    onTypeAnswer = { typingAnswer = true },
                )
            }
        }

        Surface(color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_KEYS.forEach { k ->
                        val danger = k.key == "ctrl+c"
                        OutlinedButton(
                            onClick = { if (danger) confirmCtrlC = true else vm.sendKeys(paneId, k.key) },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (danger) Color(0xFFF2C9A8) else Color(0xFFD5DAE2)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (danger) Palette.OrangeSoft else Color(0xFFF7F8FA),
                                contentColor = if (danger) Palette.OrangeInk else Palette.Ink,
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp),
                        ) { Text(k.label, fontWeight = FontWeight.SemiBold) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        placeholder = { Text("Message ${pane?.agentName ?: "this tab"}…") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (message.isNotBlank() && pane != null) {
                                vm.sendMessage(pane, message); message = ""; typingAnswer = false
                            }
                        }),
                        modifier = Modifier.weight(1f),
                    )
                    FilledIconButton(
                        onClick = {
                            if (message.isNotBlank() && pane != null) {
                                vm.sendMessage(pane, message); message = ""; typingAnswer = false
                            }
                        },
                        enabled = message.isNotBlank() && pane != null,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Blue),
                        modifier = Modifier.size(52.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White) }
                }
            }
        }
    }

    if (confirmCtrlC) {
        AlertDialog(
            onDismissRequest = { confirmCtrlC = false },
            title = { Text("Send Ctrl+C?") },
            text = { Text("This interrupts whatever ${pane?.agentName ?: "the tab"} is doing right now.") },
            confirmButton = {
                Button(onClick = { confirmCtrlC = false; vm.sendKeys(paneId, "ctrl+c") }) { Text("Interrupt") }
            },
            dismissButton = { TextButton(onClick = { confirmCtrlC = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PromptCard(
    p: Prompt,
    agent: String,
    onChoice: (com.billmongan.herdrdroid.herdr.Choice) -> Unit,
    onTypeAnswer: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Palette.Card,
        border = BorderStroke(2.dp, Palette.Orange),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.OrangeInk, modifier = Modifier.size(18.dp))
                Text("$agent is asking".uppercase(), color = Palette.OrangeInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(p.question, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
            p.choices.forEachIndexed { i, c ->
                val first = i == 0
                val what = if (c.pressEnter) "sends ${c.keys} + Enter" else "sends ${c.keys}"
                if (first) {
                    Button(
                        onClick = { onChoice(c) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("${c.label}  ·  $what") }
                } else {
                    OutlinedButton(
                        onClick = { onChoice(c) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("${c.label}  ·  $what", color = Palette.Ink) }
                }
            }
            TextButton(onClick = onTypeAnswer, modifier = Modifier.fillMaxWidth()) { Text("Type a different answer") }
            Text(
                "Each button types its key into the tab, exactly as if you typed it there.",
                color = Palette.Muted,
                fontSize = 12.sp,
            )
        }
    }
}

/** Drops the blank tail and long runs of blank lines so the chat view reads cleanly. */
private fun tidy(text: String): String =
    text.lines().map { it.trimEnd() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
