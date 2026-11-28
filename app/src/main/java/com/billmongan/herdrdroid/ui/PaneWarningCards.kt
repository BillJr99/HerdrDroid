package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel

@Composable
internal fun PaneWarningCards(
    review: AppViewModel.PaneWarningReview,
    onClose: () -> Unit,
    onTerminal: () -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onDiagnose: () -> Unit,
    onRestart: (String) -> Unit,
) {
    val height = LocalConfiguration.current.screenHeightDp.dp
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = height * 0.42f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Warnings", Modifier.weight(1f), color = Palette.Ink, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClose) { Text("Close warnings") }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (review.loading) Text("Reading warnings…", color = Palette.Muted)
            review.issue?.let { issue ->
                Text(issue, color = Palette.OrangeInk)
                TextButton(onClick = onTerminal) { Text("Show in terminal") }
            }
            if (!review.loading && review.issue == null && review.warnings.isEmpty()) {
                Text("No new warnings", color = Palette.Muted)
            }
            review.warnings.forEach { warning ->
                Surface(shape = RoundedCornerShape(12.dp), color = Palette.Card,
                    border = BorderStroke(1.dp, Palette.OrangeEdge)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(warning.text, fontSize = 14.sp, color = Palette.Ink)
                        TextButton(onClick = onTerminal) { Text("Show in terminal") }
                        Row {
                            TextButton(onClick = { onCopy(warning.text) }) { Text("Copy text") }
                            TextButton(onClick = { onDismiss(warning.text) }) { Text("Dismiss") }
                        }
                        if (warning.canDiagnose) {
                            TextButton(onClick = onDiagnose) { Text("Run codex doctor") }
                            TextButton(onClick = { onRestart(warning.text) }) { Text("Restart pane") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WarningRestartDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Restart pane?") },
        text = { Text("This opens Terminal so you can restart the session when ready.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Continue in terminal") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
