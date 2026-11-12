package com.billmongan.herdrdroid.ui

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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
    onAttach: ((Uri) -> Unit)? = null,
    attachBusy: Boolean = false,
    commands: List<SlashCommand> = emptyList(),
    leading: (@Composable () -> Unit)? = null,
) {
    var showCommands by remember { mutableStateOf(false) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) onValueChange(if (value.isBlank()) said else value.trimEnd() + " " + said)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { onAttach?.invoke(it) } }
    val typedSlash = value.startsWith("/") && !value.contains(' ')
    val matches = commands.filter { typedSlash && it.name.startsWith(value, ignoreCase = true) }

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
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Palette.Composer,
            border = BorderStroke(1.dp, Palette.Line),
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(start = 4.dp, end = 8.dp, bottom = 8.dp)) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(placeholder, color = Palette.Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    textStyle = TextStyle(fontFamily = Fonts.Sans, fontSize = 16.sp, color = Palette.Ink, lineHeight = 22.sp),
                    maxLines = 6,
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
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onAttach != null) {
                            RoundTool(if (attachBusy) "…" else "+", "Attach a file") { if (!attachBusy) picker.launch("*/*") }
                        }
                        RoundTool("🎙", "Dictate") {
                            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your message")
                            runCatching { speech.launch(i) }
                        }
                        if (commands.isNotEmpty()) RoundTool("/", "Commands") { showCommands = !showCommands }
                        leading?.invoke()
                    }
                    val stopMode = working && onStop != null && value.isBlank()
                    val label = if (working && steerLabel != null && value.isNotBlank()) steerLabel else null
                    if (label != null) {
                        Text(label, color = Palette.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 6.dp))
                    }
                    FilledIconButton(
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
            }
        }
    }
}

@Composable
private fun RoundTool(label: String, about: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = about, onClick = onClick),
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
