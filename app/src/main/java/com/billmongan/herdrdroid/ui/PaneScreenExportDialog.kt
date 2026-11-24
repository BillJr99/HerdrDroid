package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.diag.PaneScreenExport
import com.billmongan.herdrdroid.diag.PaneScreenExports

/**
 * 2.2.7: Export pane screen, opened from the pane's menu. It reads the visible screen once, then
 * shows the file's size and a one-line warning; only Share hands the file to the share sheet.
 */
@Composable
internal fun PaneScreenExportHost(vm: AppViewModel, paneId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var export by remember(paneId) { mutableStateOf<PaneScreenExport?>(null) }
    var problem by remember(paneId) { mutableStateOf<String?>(null) }
    LaunchedEffect(paneId) {
        try {
            export = vm.paneScreenExport(paneId) ?: run { problem = "Not connected, so the screen couldn't be read."; null }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            problem = "The screen couldn't be read: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    PaneScreenExportDialog(
        export = export, problem = problem,
        onShare = { e -> shareBytes(context, e.bytes, e.fileName, "text/plain", open = false); onDone() },
        onCancel = onDone,
    )
}

/** The dialog itself: nothing leaves the phone unless Share is tapped. */
@Composable
internal fun PaneScreenExportDialog(
    export: PaneScreenExport?,
    problem: String?,
    onShare: (PaneScreenExport) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.semantics { contentDescription = "Export pane screen" },
        title = { Text("Export pane screen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    problem != null -> Text(problem, color = Palette.Ink)
                    export == null -> Text("Reading the screen…", color = Palette.Muted)
                    else -> {
                        Text(export.fileName, color = Palette.Ink, fontSize = 13.sp)
                        Text("Size: ${PaneScreenExports.sizeLabel(export.size)}", color = Palette.Ink)
                        Text(PaneScreenExports.WARNING, color = Palette.OrangeInk, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { export?.let(onShare) }, enabled = export != null && problem == null) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
