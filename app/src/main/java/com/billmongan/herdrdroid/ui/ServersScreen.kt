package com.billmongan.herdrdroid.ui

import androidx.compose.material3.FilterChip
import com.billmongan.herdrdroid.ssh.normalizeFingerprint
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.store.PasswordVault
import com.billmongan.herdrdroid.store.Server
import java.util.UUID

/** The saved herdr servers. Tap one to connect. */
@Composable
fun ServersScreen(vm: AppViewModel, unlock: (Server, (Boolean) -> Unit) -> Unit) {
    var askPasswordFor by remember { mutableStateOf<Server?>(null) }

    fun start(server: Server) {
        // A WebSocket server signs in with its key only; there is never a password to ask for.
        if (server.overWebSocket && !(server.useKey && server.hasKey)) {
            vm.error = "This server connects over a WebSocket and signs in with a key only. Edit it, turn on " +
                "\"Use an SSH key\" and make a key."
            return
        }
        if (!vm.canSignInSilently(server)) {
            askPasswordFor = server
            return
        }
        val go = {
            val pw = vm.savedPassword(server)
            if (pw == null && !(server.useKey && server.hasKey)) askPasswordFor = server
            else vm.connect(server, pw, remember = pw != null)
        }
        if (server.biometric) unlock(server) { ok -> if (ok) go() } else go()
    }

    Box(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("HerdrDroid", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                        Text(
                            com.billmongan.herdrdroid.BuildConfig.VERSION_NAME,
                            fontSize = 14.sp, color = Palette.Muted, modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                    Text("Pick the computer running your agents.", color = Palette.Muted, fontSize = 15.sp)
                }
                IconButton(onClick = { vm.screen = Screen.Settings(Screen.Servers) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Palette.Muted)
                }
            }
            vm.error?.let { ErrorBanner(it) { vm.error = null } }
            if (vm.servers.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("No servers yet", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Palette.Ink)
                    Text(
                        "Add the address, port and username of a computer that runs herdr. " +
                            "You can add as many as you like.",
                        color = Palette.Muted,
                    )
                }
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(vm.servers, key = { it.id }) { s ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Palette.Card,
                        border = BorderStroke(1.dp, Palette.Line),
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !vm.connecting) { start(s) },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            AgentTile(s.label.take(1).uppercase(), com.billmongan.herdrdroid.herdr.Status.WORKING)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(s.label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.Ink)
                                // The WebSocket address is not shown in the list (it's a public endpoint).
                                Text(if (s.overWebSocket) "${s.user} · SSH over WebSocket" else "${s.user}@${s.host}:${s.port}", color = Palette.Muted, fontSize = 13.sp)
                                if (s.useKey && s.hasKey) {
                                    Text(
                                        if (s.passwordCipher != null) "Key, then password" else "Key",
                                        color = Palette.Muted, fontSize = 12.sp,
                                    )
                                }
                            }
                            IconButton(onClick = { vm.screen = Screen.EditServer(s) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit ${s.label}", tint = Palette.Muted)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        if (vm.connecting) {
            Box(Modifier.fillMaxSize().background(Palette.Ground.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    vm.connectNote?.let { Text(it, color = Palette.Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 32.dp)) }
                    OutlinedButton(onClick = { vm.cancelConnect() }) { Text("Cancel") }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { vm.screen = Screen.EditServer(null) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add server") },
            containerColor = Palette.Blue,
            contentColor = Palette.OnBlue,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    (askPasswordFor ?: vm.needPasswordFor)?.let { s ->
        PasswordDialog(
            server = s,
            onDismiss = { askPasswordFor = null; vm.needPasswordFor = null },
            onConnect = { pw, rememberIt ->
                askPasswordFor = null
                vm.needPasswordFor = null
                vm.connect(s, pw, rememberIt)
            },
        )
    }

    vm.pendingHostKey?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.rejectHostKey() },
            title = { Text("First time connecting") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Check that this fingerprint belongs to ${p.server.label}. The app remembers it " +
                            "and stops if it ever changes.",
                    )
                    MonoBox(p.fingerprint)
                    Text(
                        "On that computer, ssh-keyscan localhost | ssh-keygen -lf - prints its fingerprints.",
                        color = Palette.Muted,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = { Button(onClick = { vm.acceptHostKey() }) { Text("It matches") } },
            dismissButton = { TextButton(onClick = { vm.rejectHostKey() }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MonoBox(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = Palette.Ink,
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Gray, RoundedCornerShape(8.dp))
            .padding(10.dp),
    )
}

@Composable
private fun PasswordDialog(server: Server, onDismiss: () -> Unit, onConnect: (String, Boolean) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var rememberIt by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password for ${server.user}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberIt, onCheckedChange = { rememberIt = it })
                    Text("Remember on this phone (encrypted)")
                }
            }
        },
        confirmButton = { Button(onClick = { onConnect(pw, rememberIt) }, enabled = pw.isNotEmpty()) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Palette.Ink, fontSize = 16.sp)
            detail?.let { Text(it, color = Palette.Muted, fontSize = 13.sp) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Add or edit a server. Every field is the user's; only a new WebSocket address starts with "wss://". */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EditServerScreen(vm: AppViewModel, existing: Server?) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val draftId = remember { existing?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var viaWs by remember { mutableStateOf(existing?.overWebSocket ?: false) }
    // A new WebSocket address starts with "wss://" typed in, which can be edited or removed.
    var wsUrl by remember { mutableStateOf(existing?.wsUrl ?: "wss://") }
    var pinned by remember { mutableStateOf(existing?.fingerprint ?: "") }
    var port by remember { mutableStateOf(existing?.port?.toString() ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var herdrPath by remember { mutableStateOf(existing?.herdrPath ?: "") }
    var biometric by remember { mutableStateOf(existing?.biometric ?: false) }
    var useKey by remember { mutableStateOf(existing?.useKey ?: false) }
    var keyKind by remember { mutableStateOf(existing?.keyKind) }
    var keyCipher by remember { mutableStateOf(existing?.keyCipher) }
    var keyPassCipher by remember { mutableStateOf(existing?.keyPassCipher) }
    var publicLine by remember { mutableStateOf(existing?.let { e -> vm.keyLineFor(e) }) }
    var importedPem by remember { mutableStateOf<String?>(null) }
    var replaceKeyConfirm by remember { mutableStateOf(false) }
    var forgetPassword by remember { mutableStateOf(false) }
    var forgetFingerprint by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf<String?>(null) }

    val pickKeyFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(32_768).toByteArray() }
                    ?.toString(Charsets.UTF_8)
            }.getOrNull()
            if (text.isNullOrBlank() || !text.contains("PRIVATE KEY")) keyError = "That file isn't a private key."
            else importedPem = text
        }
    }

    fun makeKey() {
        vm.generateKey(draftId)?.let {
            keyKind = "keystore"; keyCipher = null; keyPassCipher = null; publicLine = it; keyError = null
        }
    }

    val portNum = port.toIntOrNull()
    val keyReady = keyKind == "keystore" || (keyKind == "imported" && keyCipher != null)
    val wsOk = wsUrl.trim().let { (it.startsWith("wss://", true) || it.startsWith("ws://", true)) && it.substringAfter("://").isNotBlank() }
    val pinOk = pinned.isBlank() || normalizeFingerprint(pinned) != null
    val valid = if (viaWs) {
        wsOk && user.isNotBlank() && herdrPath.isNotBlank() && useKey && keyReady && pinOk
    } else {
        host.isNotBlank() && user.isNotBlank() && portNum != null && portNum in 1..65535 &&
            herdrPath.isNotBlank() && (!useKey || keyReady)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(if (existing == null) "Add a server" else "Edit server", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Connect through", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Palette.Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !viaWs, onClick = { viaWs = false }, label = { Text("SSH") })
            FilterChip(selected = viaWs, onClick = { viaWs = true; useKey = true }, label = { Text("SSH over WebSocket") })
        }
        if (viaWs) {
            OutlinedTextField(
                wsUrl, { wsUrl = it.trim() }, label = { Text("WebSocket address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("The full wss:// address of the WebSocket that carries SSH to that computer.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                pinned, { pinned = it.trim() }, label = { Text("Host key fingerprint") }, singleLine = true,
                isError = !pinOk,
                supportingText = {
                    Text(
                        if (!pinOk) "That isn't a SHA256 fingerprint (SHA256:… with 43 characters after the colon)."
                        else "SHA256:… from ssh-keygen -lf on that computer's host key. Leave it empty to confirm it on the first connection.",
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!viaWs) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                host, { host = it.trim() }, label = { Text("Address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(3f),
            )
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1.3f),
            )
        }
        OutlinedTextField(user, { user = it.trim() }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            herdrPath, { herdrPath = it }, label = { Text("herdr command") }, singleLine = true,
            supportingText = { Text("The command that runs herdr on that computer, such as herdr or ~/.local/bin/herdr") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Signing in", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Palette.Ink, modifier = Modifier.padding(top = 6.dp))
        if (viaWs) {
            Text("Over a WebSocket the app signs in with a key only; no password is ever sent.", color = Palette.Muted, fontSize = 13.sp)
        } else SwitchRow(
            "Use an SSH key",
            if (useKey) "The key is tried first. If the server turns it down, the password is used."
            else "Off: this server never sees a key from the app, only the password.",
            useKey,
        ) { useKey = it }
        if (useKey) {
            Surface(shape = RoundedCornerShape(14.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        keyKind == "keystore" && publicLine != null -> {
                            Text("A key made on this phone", fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                            Text(
                                "Its private half stays in the phone's secure storage and can't be copied out. Add this " +
                                    "line to ~/.ssh/authorized_keys on ${host.ifBlank { "the computer" }}:",
                                color = Palette.Muted, fontSize = 13.sp,
                            )
                            MonoBox(publicLine!!)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(publicLine!!)) }) { Text("Copy") }
                                OutlinedButton(onClick = {
                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, publicLine)
                                    context.startActivity(Intent.createChooser(send, "Share the public key"))
                                }) { Text("Share") }
                            }
                        }
                        keyKind == "imported" && keyCipher != null -> {
                            Text("An imported key file", fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                            Text("Stored encrypted on this phone.", color = Palette.Muted, fontSize = 13.sp)
                            publicLine?.let { MonoBox(it) }
                        }
                        else -> Text("Choose where the key comes from.", color = Palette.Muted)
                    }
                    keyError?.let { Text(it, color = Palette.OrangeInk, fontSize = 13.sp) }
                    Button(
                        onClick = { if (keyKind != null) replaceKeyConfirm = true else makeKey() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (keyKind == "keystore") "Make a new key on this phone" else "Make a key on this phone") }
                    OutlinedButton(onClick = { pickKeyFile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (keyKind == "imported") "Import a different key file" else "Import a key file")
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = biometric, onCheckedChange = { biometric = it })
            Text("Ask for my fingerprint before using the saved password or key", color = Palette.Ink)
        }
        if (existing?.passwordCipher != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = forgetPassword, onCheckedChange = { forgetPassword = it })
                Text("Forget the saved password", color = Palette.Ink)
            }
        }
        if (existing?.fingerprint != null && !viaWs) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = forgetFingerprint, onCheckedChange = { forgetFingerprint = it })
                    Text("Forget the saved host fingerprint", color = Palette.Ink)
                }
                Text(existing.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Palette.Muted,
                    modifier = Modifier.padding(start = 48.dp))
            }
        }
        Button(
            onClick = {
                val base = existing ?: Server(id = draftId, name = "", host = "", port = 0, user = "", herdrPath = "")
                val url = wsUrl.trim()
                val wsHost = url.substringAfter("://").substringBefore('/').substringBefore('?')
                val newHost = if (viaWs) wsHost else host
                val newPort = if (viaWs) 443 else portNum!!
                val changedTarget = existing != null && (existing.host != newHost || existing.port != newPort ||
                    existing.overWebSocket != viaWs || (viaWs && existing.wsUrl != url))
                val fp = when {
                    viaWs && pinned.isNotBlank() -> normalizeFingerprint(pinned)
                    viaWs -> if (changedTarget) null else base.fingerprint
                    forgetFingerprint || changedTarget -> null
                    else -> base.fingerprint
                }
                if (keyKind != "keystore") vm.forgetGeneratedKey(draftId)
                vm.saveServer(
                    base.copy(
                        name = name.trim(), host = newHost, port = newPort, user = user, herdrPath = herdrPath.trim(),
                        biometric = biometric,
                        passwordCipher = if (viaWs || forgetPassword || (existing != null && existing.user != user)) null else base.passwordCipher,
                        fingerprint = fp,
                        useKey = if (viaWs) true else useKey,
                        wsUrl = if (viaWs) url else null,
                        keyKind = keyKind,
                        keyCipher = if (keyKind == "imported") keyCipher else null,
                        keyPassCipher = if (keyKind == "imported") keyPassCipher else null,
                    )
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Save") }
        OutlinedButton(
            onClick = {
                if (existing == null) vm.forgetGeneratedKey(draftId)
                vm.screen = Screen.Servers
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Cancel") }
        if (existing != null) {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Remove this server", color = Palette.OrangeInk)
            }
        }
    }

    importedPem?.let { pem ->
        var passphrase by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importedPem = null },
            title = { Text("Import the key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("If the key file has a passphrase, enter it. It's stored encrypted with the key.")
                    OutlinedTextField(
                        passphrase, { passphrase = it }, label = { Text("Passphrase (if any)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val pass = passphrase.ifEmpty { null }
                    val result = vm.checkKeyFile(pem, pass)
                    if (result.isFailure) {
                        keyError = result.exceptionOrNull()?.message ?: "That key couldn't be read."
                    } else {
                        if (keyKind == "keystore") vm.forgetGeneratedKey(draftId)
                        keyKind = "imported"
                        keyCipher = PasswordVault.encrypt(pem)
                        keyPassCipher = pass?.let { PasswordVault.encrypt(it) }
                        publicLine = result.getOrNull()
                        keyError = null
                    }
                    importedPem = null
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importedPem = null }) { Text("Cancel") } },
        )
    }

    if (replaceKeyConfirm) {
        AlertDialog(
            onDismissRequest = { replaceKeyConfirm = false },
            title = { Text("Replace the key?") },
            text = { Text("The current key stops working for this server right away. You'll need to add the new key's line to authorized_keys.") },
            confirmButton = { Button(onClick = { replaceKeyConfirm = false; makeKey() }) { Text("Make a new key") } },
            dismissButton = { TextButton(onClick = { replaceKeyConfirm = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${existing.label}?") },
            text = { Text("Its saved password, key and fingerprint are deleted from this phone. Nothing changes on the computer.") },
            confirmButton = { Button(onClick = { vm.deleteServer(existing) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ErrorBanner(
    message: String, actionLabel: String = "Dismiss",
    secondLabel: String? = null, onSecond: (() -> Unit)? = null,
    onAction: () -> Unit,
) {
    Surface(
        color = Palette.OrangeSoft,
        border = BorderStroke(1.dp, Palette.Orange),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = Palette.OrangeInk, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (secondLabel != null && onSecond != null) TextButton(onClick = onSecond) { Text(secondLabel, color = Palette.OrangeInk) }
            TextButton(onClick = onAction) { Text(actionLabel, color = Palette.OrangeInk) }
        }
    }
}

