package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.billmongan.herdrdroid.herdr.RelayResult
import kotlinx.coroutines.launch

@Composable
internal fun PromptRelayDialog(
    destination: String,
    checking: Boolean,
    available: Boolean,
    onDismiss: () -> Unit,
    onSend: suspend (String) -> RelayResult,
    onSent: () -> Unit,
) {
    var draft by remember(destination) { mutableStateOf("") }
    var sending by remember(destination) { mutableStateOf(false) }
    var result by remember(destination) { mutableStateOf<RelayResult?>(null) }
    val scope = rememberCoroutineScope()
    val enabled = available && !sending && result != RelayResult.UNCERTAIN && draft.isNotBlank()
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Send prompt to $destination") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { draft = it }, enabled = !sending, label = { Text("Prompt") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                val feedback = when {
                    sending -> "Checking the destination and sending…"
                    result == RelayResult.UNCERTAIN -> RelayResult.UNCERTAIN.feedback
                    checking -> "Checking the Fleet connection…"
                    !available -> "Destination unavailable. Check Fleet and reopen this dialog."
                    else -> result?.feedback
                }
                feedback?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(enabled = enabled, onClick = {
                if (sending || !enabled) return@Button
                sending = true
                val text = draft
                scope.launch {
                    try {
                        val outcome = onSend(text)
                        result = outcome
                        if (outcome == RelayResult.SENT) onSent()
                    } finally {
                        sending = false
                    }
                }
            }) { Text("Send prompt") }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") } },
    )
}
