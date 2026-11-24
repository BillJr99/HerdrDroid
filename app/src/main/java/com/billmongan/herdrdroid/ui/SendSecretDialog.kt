package com.billmongan.herdrdroid.ui

import android.app.Activity
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.net.Uri
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.herdr.SecretTarget

/** The open pane's "⋯" menu item that opens [SendSecretDialog]. */
const val SEND_SECRET_ITEM = "Send a secret…"

/** 2.2.7 Send a secret, wired to the view model: the open pane is the default target. */
@Composable
fun SendSecretDialog(vm: AppViewModel, paneId: String, onDismiss: () -> Unit) {
    val targets = remember { vm.secretTargets() }
    SendSecretDialog(
        targets = targets,
        initial = targets.firstOrNull { it.serverId == vm.current?.id && it.paneId == paneId } ?: targets.firstOrNull(),
        busy = vm.secretBusy,
        onSendText = { t, label, bytes -> vm.sendSecret(t, label, bytes) },
        onSendFile = { t, label, uri -> vm.sendSecretFile(t, label, uri) },
        onDismiss = onDismiss,
    )
}

/**
 * 2.2.7 Send a secret: a masked text box (no suggestions, no autocorrect, no autofill) or a
 * picked file, a neutral label, and the pane its path goes to. The dialog and the screen behind it
 * are FLAG_SECURE while it is open, so neither a screenshot nor the recents view shows it. The
 * secret is handed over as bytes once and the text box cleared; nothing else keeps it.
 */
@Composable
fun SendSecretDialog(
    targets: List<SecretTarget>,
    initial: SecretTarget?,
    /** True when the secret was taken; false keeps the dialog open with what was typed. */
    onSendText: (SecretTarget, String?, ByteArray) -> Boolean,
    onSendFile: (SecretTarget, String?, Uri) -> Boolean,
    onDismiss: () -> Unit,
    /** Another secret is still being saved: Save waits, and the dialog says why (nothing is dropped). */
    busy: Boolean = false,
) {
    var asFile by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var target by remember { mutableStateOf(initial) }
    var choosing by remember { mutableStateOf(false) }
    var noPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) file = uri to (displayName(context, uri) ?: "the chosen file")
    }
    // The screen behind is secured too (the recents thumbnail is the whole task), and set back after.
    DisposableEffect(Unit) {
        val window = context.activity()?.window
        val had = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (window != null && !had) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (window != null && !had) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            secret = ""
        }
    }
    val close = { secret = ""; onDismiss() }
    AlertDialog(
        onDismissRequest = close,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        modifier = Modifier.semantics { contentDescription = "Send a secret" },
        title = { Text("Send a secret") },
        text = {
            // No autofill service sees or offers to save anything in this dialog.
            val view = LocalView.current
            DisposableEffect(view) {
                val before = view.importantForAutofill
                view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                onDispose { view.importantForAutofill = before }
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved in ~/.herdrdroid/secrets on the computer, readable only by your account. Only its path goes into the message box.",
                    color = Palette.Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !asFile, onClick = { asFile = false }, label = { Text("Text") })
                    FilterChip(selected = asFile, onClick = { asFile = true; secret = "" }, label = { Text("File") })
                }
                if (!asFile) {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text("Secret") },
                        singleLine = false,
                        maxLines = 4,
                        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        trailingIcon = { TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") } },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Secret text" },
                    )
                } else {
                    Row {
                        TextButton(onClick = {
                            noPicker = try { picker.launch(arrayOf("*/*")); false } catch (_: ActivityNotFoundException) { true }
                        }) { Text(if (file == null) "Choose a file" else "Choose another") }
                    }
                    if (noPicker) Text(NO_FILE_PICKER_TEXT, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    file?.let { Text("Chosen: ${it.second}", color = Palette.Ink, fontSize = 13.sp) }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(60) },
                    label = { Text("Label (optional)") },
                    supportingText = { Text("Used in the file name, not the secret.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) Text(SECRET_BUSY_TEXT, color = MaterialTheme.colorScheme.error)
                // The pane list opens in place, inside this secure dialog, not as a separate popup window.
                TextButton(onClick = { choosing = !choosing }, enabled = targets.size > 1) {
                    Text("For: " + (target?.let { "${it.paneLabel} on ${it.serverLabel}" } ?: "no pane is open") + if (targets.size > 1) (if (choosing) "  ▴" else "  ▾") else "")
                }
                if (choosing) targets.filter { it != target }.forEach { t ->
                    TextButton(onClick = { target = t; choosing = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("${t.paneLabel} on ${t.serverLabel}", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            val t = target
            TextButton(
                enabled = t != null && !busy && (if (asFile) file != null else secret.isNotEmpty()),
                onClick = {
                    if (t == null) return@TextButton
                    val l = label.trim().ifBlank { null }
                    val taken = if (asFile) file?.let { onSendFile(t, l, it.first) } == true
                    else onSendText(t, l, secret.toByteArray(Charsets.UTF_8)).also { if (it) secret = "" }
                    if (taken) onDismiss()
                },
            ) { Text(if (busy) SECRET_BUSY_BUTTON else "Save on server") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

/** 2.2.7: shown while another secret is still being saved; Save waits, nothing is dropped. */
const val SECRET_BUSY_TEXT = "Another secret is still being saved. Save becomes available when it is done; what you typed stays here."
const val SECRET_BUSY_BUTTON = "Waiting for the last secret"

/** Shown when no app on the phone can pick a document (OpenDocument has no handler). */
const val NO_FILE_PICKER_TEXT = "No file picker on this phone."

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
