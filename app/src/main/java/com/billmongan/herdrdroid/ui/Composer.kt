package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import com.billmongan.herdrdroid.voice.LocalVoiceUi
import com.billmongan.herdrdroid.voice.HandsFreeService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A slash command an agent understands, for the picker. */
data class SlashCommand(val name: String, val about: String)

/** The commands each agent's own program answers; Hermes's chat lists its live catalog instead. */
object SlashCommands {
    fun forAgent(agent: String?): List<SlashCommand> = when (agent?.lowercase()) {
        "claude" -> listOf(
            "/compact" to "Summarize the conversation to free context", "/clear" to "Start a fresh conversation",
            "/model" to "Choose the model", "/context" to "Show context use", "/cost" to "Show this session's cost",
            "/usage" to "Show plan usage limits", "/status" to "Version, model and account",
            "/review" to "Review a pull request", "/resume" to "Resume an earlier conversation", "/memory" to "Edit memory files",
        )
        "codex" -> listOf(
            "/model" to "Choose the model and effort", "/compact" to "Summarize the conversation", "/new" to "Start a new chat",
            "/status" to "Session settings and token use", "/diff" to "Show the git diff", "/review" to "Review the changes",
            "/approvals" to "Choose what runs without asking", "/resume" to "Resume a saved chat", "/init" to "Create an AGENTS.md",
        )
        "pi" -> listOf(
            "/model" to "Choose the model", "/compact" to "Summarize the conversation", "/new" to "Start a new session",
            "/resume" to "Resume a session", "/tree" to "Browse the session tree", "/settings" to "Settings",
            "/session" to "Session info", "/hotkeys" to "Show keyboard shortcuts",
        )
        "omp" -> listOf(
            "/model" to "Choose the model", "/new" to "Start a new session", "/resume" to "Resume a session",
            "/compact" to "Summarize the conversation", "/fork" to "Fork this session", "/tree" to "Browse the session tree",
            "/plan" to "Plan mode", "/goal" to "Set a goal for the session", "/todo" to "Show the todo list",
            "/btw" to "Ask a side question without derailing the task", "/handoff" to "Hand off to a fresh session",
            "/context" to "Show context use", "/usage" to "Show provider usage limits", "/mcp" to "MCP servers",
            "/collab" to "Share this session for remote access", "/join" to "Join a shared session", "/leave" to "Leave or stop sharing",
            "/thinking" to "Set thinking effort", "/extended-context" to "Use the larger context window",
            "/diff" to "Show changes", "/commit" to "Write a commit message", "/export" to "Export the session",
            "/copy" to "Copy the last reply", "/memory" to "Memory", "/agents" to "Task agents", "/skills" to "Skills",
            "/settings" to "Settings", "/hotkeys" to "Keyboard shortcuts", "/help" to "Help",
        )
        "opencode" -> listOf(
            "/models" to "Choose the model", "/new" to "Start a new session", "/sessions" to "Switch sessions",
            "/compact" to "Summarize the session", "/undo" to "Undo the last change", "/redo" to "Redo",
            "/share" to "Share the session", "/export" to "Export the conversation",
        )
        "hermes" -> listOf(
            "/model" to "Choose the model", "/compress" to "Compress the context", "/new" to "Start a new session",
            "/usage" to "Show usage", "/resume" to "Resume a session", "/reasoning" to "Set reasoning effort",
            "/tools" to "List tools", "/skills" to "Browse skills",
        )
        "grok" -> listOf("/model" to "Choose the model", "/new" to "Start a new chat", "/compact" to "Summarize", "/usage" to "Show usage")
        "copilot" -> listOf("/model" to "Choose the model", "/clear" to "Clear the conversation", "/usage" to "Show usage", "/session" to "Session info")
        "agy", "antigravity" -> listOf("/model" to "Choose the model", "/usage" to "Show usage", "/clear" to "Clear the conversation")
        // From Kiro CLI 3.3.0's /help (2026-11-13).
        "kiro" -> listOf(
            "/model" to "Choose the model", "/usage" to "Plan usage and credits", "/plan" to "Plan mode",
            "/compact" to "Summarize the conversation", "/clear" to "Start a fresh session", "/context" to "Show context use",
            "/effort" to "Reasoning effort", "/sessions" to "Browse sessions", "/rewind" to "Fork at an earlier turn",
            "/tools" to "List tools", "/mcp" to "MCP servers", "/help" to "All commands",
        )
        // From Mistral Vibe 3.1.2's /help (2026-11-13).
        "vibe" -> listOf(
            "/model" to "Choose the model", "/thinking" to "Thinking level", "/status" to "Tokens, cost and model",
            "/whoami" to "Account and plan", "/resume" to "Resume a session", "/rename" to "Rename this session",
            "/retry" to "Retry the last response", "/rewind" to "Rewind to an earlier message", "/context" to "Show context use",
            "/copy" to "Copy the last reply", "/mcp" to "MCP servers", "/help" to "All commands",
        )
        // From Muse Code 1.4.20's /help, Commands tab (2026-11-15).
        "muse" -> listOf(
            "/model" to "Choose the model", "/effort" to "Reasoning effort", "/compact" to "Summarize the conversation to free up context",
            "/clear" to "Clear the terminal and start a fresh session", "/new" to "Start a new session", "/resume" to "Resume a session",
            "/status" to "Show current session status", "/usage" to "Show session usage", "/upgrade" to "Show your subscription plan",
            "/plan" to "Plan mode", "/permissions" to "Permissions", "/rewind" to "Rewind to an earlier turn", "/fork" to "Fork this session",
            "/goal" to "Set a goal for the session", "/tasks" to "Background tasks and terminals", "/help" to "All commands",
        )
        else -> emptyList()
    }.map { SlashCommand(it.first, it.second) }
}

/**
 * The message box for every chat: text that grows to six lines; a + button for attaching a file;
 * the mic for dictation (Android's own speech recognizer, so the text can be checked before it
 * is sent); "/" for the agent's commands; and Send, which becomes Stop while the agent is working
 * and nothing is typed, or Steer when the agent can take guidance mid-turn.
 */
@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    canSend: Boolean,
    onSend: () -> Unit,
    working: Boolean = false,
    onStop: (() -> Unit)? = null,
    steerLabel: String? = null,
    onQueueWhenIdle: (() -> Unit)? = null,
    onAttach: ((Uri) -> Unit)? = null,
    attachBusy: Boolean = false,
    commands: List<SlashCommand> = emptyList(),
    leading: (@Composable () -> Unit)? = null,
    /**
     * One row instead of three: a "⋯" menu (attach, dictate, commands and [menuItems]), the box,
     * and the send button, so the chat keeps more of the screen.
     */
    compact: Boolean = false,
    menuItems: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var showCommands by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var sendOptions by remember { mutableStateOf(false) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) onValueChange(if (value.isBlank()) said else value.trimEnd() + " " + said)
        }
    }
    val voice = LocalVoiceUi.current
    val voiceCommand = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { voice?.command?.invoke(it) }
        }
    }
    val commandMic: (() -> Unit)? = if (voice?.enabled == true && !HandsFreeService.active) {
        {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a Herdr command")
            runCatching { voiceCommand.launch(intent) }
            Unit
        }
    } else null
    val voiceMenu = listOfNotNull(commandMic?.let { "Voice command" to it })
    var voiceMenuOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { onAttach?.invoke(it) } }
    val typedSlash = value.startsWith("/") && !value.contains(' ')
    val matches = commands.filter { typedSlash && it.name.startsWith(value, ignoreCase = true) }

    val sendButton: @Composable () -> Unit = {
        val stopMode = working && onStop != null && value.isBlank()
        val label = if (working && steerLabel != null && value.isNotBlank()) steerLabel else null
        Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
        // While the agent works, a message steers it: the send button says so itself.
        if (label != null && onQueueWhenIdle != null) {
            Box {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (canSend) Palette.Blue else Palette.Gray,
                    contentColor = if (canSend) Palette.OnBlue else Palette.Muted,
                ) {
                    Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.fillMaxHeight().clickable(enabled = canSend, role = androidx.compose.ui.semantics.Role.Button,
                                onClickLabel = "Send immediately", onClick = onSend).padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                        Box(Modifier.width(1.dp).height(24.dp).background(Palette.OnBlue.copy(alpha = 0.35f)))
                        Box(
                            Modifier.size(48.dp).clickable(enabled = canSend, role = androidx.compose.ui.semantics.Role.Button,
                                onClick = { sendOptions = true }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Send options", modifier = Modifier.size(20.dp))
                        }
                    }
                }
                // Leave window focus with the composer so opening the menu does not hide the IME.
                androidx.compose.material3.DropdownMenu(
                    expanded = sendOptions,
                    onDismissRequest = { sendOptions = false },
                    properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                ) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Send when idle") },
                        onClick = { sendOptions = false; onQueueWhenIdle() })
                }
            }
        } else FilledIconButton(
            onClick = { if (stopMode) onStop?.invoke() else onSend() },
            enabled = stopMode || canSend,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (stopMode) Palette.Ink else Palette.Blue,
                disabledContainerColor = Palette.Gray,
            ),
            modifier = Modifier.size(40.dp),
        ) {
            if (stopMode) {
                Box(Modifier.size(12.dp).background(Palette.Ground, RoundedCornerShape(2.dp)))
            } else {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Send", tint = if (canSend) Palette.OnBlue else Palette.Muted)
            }
        }
    }
    val field: @Composable (Modifier, Int) -> Unit = { m, lines ->
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Palette.Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            textStyle = TextStyle(fontFamily = Fonts.Sans, fontSize = 16.sp, color = Palette.Ink, lineHeight = 22.sp),
            maxLines = lines,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = Palette.Blue,
            ),
            modifier = m,
        )
    }
    val launchDictation = {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your message")
        runCatching { speech.launch(i) }
        Unit
    }
    var dictateAfterStop by remember { mutableStateOf(false) }
    val handsFreeActive = HandsFreeService.active
    androidx.compose.runtime.LaunchedEffect(handsFreeActive, dictateAfterStop) {
        if (!handsFreeActive && dictateAfterStop) {
            dictateAfterStop = false
            launchDictation()
        }
    }
    val dictate = {
        if (handsFreeActive && voice != null) {
            dictateAfterStop = true
            voice.stopHandsFree()
        } else launchDictation()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AnimatedVisibility(showCommands || matches.isNotEmpty()) {
            val list = if (matches.isNotEmpty()) matches else commands
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Palette.Card,
                border = BorderStroke(1.dp, Palette.Line),
                shadowElevation = 4.dp,
            ) {
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(list, key = { it.name }) { c ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                onValueChange(c.name + " ")
                                showCommands = false
                            }.padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            Text(c.name, color = Palette.Ink, fontFamily = Fonts.Mono, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (c.about.isNotBlank()) Text(c.about, color = Palette.Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider(color = Palette.Line)
                    }
                }
            }
        }
        // The chips (Keys, Snippets, When idle…) get their own row above the box, so the bottom row
        // holds only the round tools and the send button and nothing can overlap.
        if (compact) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Palette.Composer,
                border = BorderStroke(1.dp, Palette.Line),
                shadowElevation = 2.dp,
            ) {
                Row(Modifier.padding(start = 2.dp, end = 6.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        RoundTool(if (attachBusy) "…" else "⋯", "More: attach, commands, keys") { showMenu = true }
                        androidx.compose.material3.DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (onAttach != null) androidx.compose.material3.DropdownMenuItem(text = { Text("Attach a file") }, enabled = !attachBusy, onClick = { showMenu = false; picker.launch("*/*") })
                            if (commands.isNotEmpty()) androidx.compose.material3.DropdownMenuItem(text = { Text("Commands") }, onClick = { showMenu = false; showCommands = !showCommands })
                            (menuItems + voiceMenu).forEach { (label, act) ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { showMenu = false; act() })
                            }
                        }
                    }
                    field(Modifier.weight(1f), 5)
                    // Dictation stays one tap away, in the box itself.
                    RoundTool("🎙", "Dictate", onLongClick = commandMic) { dictate() }
                    sendButton()
                }
            }
            return@Column
        }
        if (leading != null) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { leading() }
        }
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Palette.Composer,
            border = BorderStroke(1.dp, Palette.Line),
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(start = 4.dp, end = 8.dp, bottom = 8.dp)) {
                field(Modifier.fillMaxWidth(), 6)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onAttach != null) {
                            RoundTool(if (attachBusy) "…" else "+", "Attach a file") { if (!attachBusy) picker.launch("*/*") }
                        }
                        RoundTool("🎙", "Dictate", onLongClick = commandMic) { dictate() }
                        if (voiceMenu.isNotEmpty()) Box {
                            RoundTool("⋯", "Voice commands") { voiceMenuOpen = true }
                            androidx.compose.material3.DropdownMenu(expanded = voiceMenuOpen, onDismissRequest = { voiceMenuOpen = false }) {
                                voiceMenu.forEach { (label, action) ->
                                    androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { voiceMenuOpen = false; action() })
                                }
                            }
                        }
                        if (commands.isNotEmpty()) RoundTool("/", "Commands") { showCommands = !showCommands }
                    }
                    sendButton()
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun RoundTool(label: String, about: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .combinedClickable(onClickLabel = about, onLongClickLabel = "Voice command", onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Palette.Muted, fontSize = if (label.length == 1) 19.sp else 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** A small rounded toggle chip for the composer's tool row (the Keys switch on agent panes). */
@Composable
fun ComposerChip(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (on) Palette.BlueSoft else Color.Transparent,
        border = BorderStroke(1.dp, if (on) Palette.BlueSoft else Palette.Line),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = if (on) Palette.Blue else Palette.Muted,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * One-tap model choices for each agent, as the command its own program takes. Agents whose
 * command opens a picker instead get only [pickerCommand].
 */
object ModelPresets {
    fun forAgent(agent: String?): List<Pair<String, String>> = when (agent?.lowercase()) {
        "claude" -> listOf("Fable" to "/model fable", "Opus" to "/model opus", "Sonnet" to "/model sonnet", "Haiku" to "/model haiku", "Default" to "/model default")
        else -> emptyList()
    }

    /** Agents whose model command takes a model name, so a model seen before can be one tap. */
    fun takesName(agent: String?): Boolean = agent?.lowercase() in setOf("claude", "hermes")

    /** Reasoning effort as one-tap commands, where the agent has a command for it (label to command). */
    fun efforts(agent: String?): List<Pair<String, String>> = when (agent?.lowercase()) {
        "hermes" -> listOf("low", "medium", "high", "xhigh", "max").map { it to "/reasoning $it" }
        else -> emptyList()
    }

    /** The command that opens the agent's own model picker. */
    fun pickerCommand(agent: String?): String = when (agent?.lowercase()) {
        "opencode" -> "/models"
        else -> "/model"
    }
}
