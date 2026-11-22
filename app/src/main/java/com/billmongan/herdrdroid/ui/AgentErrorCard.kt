package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * An agent's error as its own card in the chat (2.1.6, Bill: "a different kind of card not a
 * question card in the pane chat view that I can acknowledge and clear"). Red, with a warning
 * icon, unlike the orange question card, and never answered: it has no choices and sends no keys.
 * Acknowledge clears it in the app. Retry resends the phone's own last message once, only when
 * [onRetry] is given; Switch model opens the model dialog, only when [onSwitchModel] is given.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentErrorCardView(
    title: String,
    summary: String,
    message: String?,
    onAcknowledge: () -> Unit,
    onRetry: (() -> Unit)?,
    onSwitchModel: (() -> Unit)?,
    onShowTerminal: (() -> Unit)?,
) {
    Surface(shape = RoundedCornerShape(14.dp), color = Palette.Card, border = BorderStroke(1.5.dp, Palette.Red)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = "Error", tint = Palette.Red, modifier = Modifier.size(18.dp))
                Text(title, color = Palette.Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(summary.replaceFirstChar { it.uppercase() } + ".", color = Palette.Ink, fontSize = 14.sp)
            message?.let {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(it, color = Palette.TerminalInk, fontFamily = Fonts.Mono, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().background(Palette.Terminal, RoundedCornerShape(10.dp)).padding(10.dp))
                }
            }
            Text("This is an error, not a question. Acknowledge clears it here; the agent's screen keeps its own record.",
                color = Palette.Muted, fontSize = 12.5.sp)
            Button(
                onClick = onAcknowledge,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Red, contentColor = Palette.Card),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Acknowledge", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                onRetry?.let { OutlinedButton(onClick = it) { Text("Retry") } }
                onSwitchModel?.let { OutlinedButton(onClick = it) { Text("Switch model") } }
                onShowTerminal?.let { OutlinedButton(onClick = it) { Text("Show terminal") } }
            }
        }
    }
}
