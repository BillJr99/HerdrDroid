package com.billmongan.herdrdroid.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.ui.Palette
import java.text.DateFormat
import java.util.Date

/** "Never", or the date and time of the last check that got an answer. */
fun lastCheckLabel(at: Long): String =
    if (at <= 0) "Never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))

/** The Updates part of Settings: the launch switch, Check for updates now, the version and last check. */
@Composable
fun UpdateSettings(
    installedVersion: String,
    checkOnLaunch: Boolean,
    lastCheckAt: Long,
    checking: Boolean,
    note: String?,
    onCheckOnLaunch: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checkOnLaunch, role = Role.Switch, onValueChange = onCheckOnLaunch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Check for updates on launch", color = Palette.Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checkOnLaunch, onCheckedChange = null)
    }
    Text(
        "Once each launch when online, the app asks GitHub whether a newer " +
            "HerdrDroid release is out. It sends nothing about you or your servers.",
        color = Palette.Muted, fontSize = 13.sp,
    )
    OutlinedButton(enabled = !checking, onClick = onCheckNow) {
        Text(if (checking) "Checking…" else "Check for updates now")
    }
    note?.let { Text(it, color = Palette.Blue, fontSize = 13.sp) }
    Text("Installed: HerdrDroid $installedVersion", color = Palette.Muted, fontSize = 13.sp)
    Text("Last checked: ${lastCheckLabel(lastCheckAt)}", color = Palette.Muted, fontSize = 13.sp)
}

private fun megabytes(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

/** The update prompt and its download, permission and error steps. */
@Composable
fun UpdateDialog(
    state: UpdateDialogState,
    onUpdate: () -> Unit,
    onNotNow: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    when (state) {
        is UpdateDialogState.Available -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("HerdrDroid ${state.release.version} is available") },
            text = { Text("Update downloads ${megabytes(state.release.apkSize)} from GitHub, checks it, and opens Android's installer. Not now skips this version until the next one.") },
            confirmButton = { TextButton(onClick = onUpdate) { Text("Update") } },
            dismissButton = { TextButton(onClick = onNotNow) { Text("Not now") } },
        )
        is UpdateDialogState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading HerdrDroid ${state.release.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val frac = if (state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    Text("${megabytes(state.done)} of ${megabytes(state.total)}", fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        is UpdateDialogState.NeedsPermission -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Allow HerdrDroid to install updates") },
            text = { Text("Turn on Allow from this source for HerdrDroid in Android's settings, then come back to finish the update.") },
            confirmButton = { TextButton(onClick = onOpenSettings) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        is UpdateDialogState.Installing -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Installing HerdrDroid ${state.release.version}") },
            text = { Text("The update passed its checks. Confirm it in Android's installer.") },
            confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        )
        is UpdateDialogState.Failed -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Update not installed") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        )
    }
}

/** The dialog for the app's updater, shown over any screen. */
@Composable
fun UpdatePrompt(updater: Updater) {
    val state = updater.dialog ?: return
    UpdateDialog(
        state,
        onUpdate = updater::update,
        onNotNow = updater::notNow,
        onCancel = updater::cancel,
        onOpenSettings = { updater.openInstallSettings() },
        onClose = updater::dismiss,
    )
}
