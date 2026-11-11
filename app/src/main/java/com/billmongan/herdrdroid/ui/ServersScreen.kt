package com.billmongan.herdrdroid.ui

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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.store.Server

/** The saved herdr servers. Tap one to connect. */
@Composable
fun ServersScreen(vm: AppViewModel, unlock: (Server, (Boolean) -> Unit) -> Unit) {
    var askPasswordFor by remember { mutableStateOf<Server?>(null) }

    fun start(server: Server) {
        val saved = server.passwordCipher
        if (saved == null) {
            askPasswordFor = server
            return
        }
        val go = {
            val pw = vm.savedPassword(server)
            if (pw == null) askPasswordFor = server else vm.connect(server, pw, remember = true)
        }
        if (server.biometric) unlock(server) { ok -> if (ok) go() } else go()
    }

    Box(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp)) {
                Text("HerdrDroid", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Pick the computer running your agents.",
                    color = Palette.Muted,
                    fontSize = 15.sp,
                )
            }
            vm.error?.let { ErrorBanner(it) { vm.error = null } }
            if (vm.servers.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("No servers yet", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
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
                                Text(s.label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text("${s.user}@${s.host}:${s.port}", color = Palette.Muted, fontSize = 13.sp)
                            }
                            IconButton(onClick = { vm.screen = Screen.EditServer(s) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit ${s.label}")
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        if (vm.connecting) {
            Box(Modifier.fillMaxSize().background(Palette.Ground.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        ExtendedFloatingActionButton(
            onClick = { vm.screen = Screen.EditServer(null) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add server") },
            containerColor = Palette.Blue,
            contentColor = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    askPasswordFor?.let { s ->
        PasswordDialog(
            server = s,
            onDismiss = { askPasswordFor = null },
            onConnect = { pw, rememberIt ->
                askPasswordFor = null
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
                    Text(
                        p.fingerprint,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Palette.Gray, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    )
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

/** Add or edit a server. Every field is the user's; nothing is filled in for them. */
@Composable
fun EditServerScreen(vm: AppViewModel, existing: Server?) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf(existing?.port?.toString() ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var herdrPath by remember { mutableStateOf(existing?.herdrPath ?: "") }
    var biometric by remember { mutableStateOf(existing?.biometric ?: false) }
    var forgetPassword by remember { mutableStateOf(false) }
    var forgetFingerprint by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val portNum = port.toIntOrNull()
    val valid = host.isNotBlank() && user.isNotBlank() && portNum != null && portNum in 1..65535 && herdrPath.isNotBlank()

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
        Text(if (existing == null) "Add a server" else "Edit server", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = biometric, onCheckedChange = { biometric = it })
            Text("Ask for my fingerprint before using the saved password")
        }
        if (existing?.passwordCipher != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = forgetPassword, onCheckedChange = { forgetPassword = it })
                Text("Forget the saved password")
            }
        }
        if (existing?.fingerprint != null) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = forgetFingerprint, onCheckedChange = { forgetFingerprint = it })
                    Text("Forget the saved host fingerprint")
                }
                Text(existing.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Palette.Muted,
                    modifier = Modifier.padding(start = 48.dp))
            }
        }
        Button(
            onClick = {
                val base = existing ?: Server(name = "", host = "", port = 0, user = "", herdrPath = "")
                val changedTarget = existing != null && (existing.host != host || existing.port != portNum)
                vm.saveServer(
                    base.copy(
                        name = name.trim(), host = host, port = portNum!!, user = user, herdrPath = herdrPath.trim(),
                        biometric = biometric,
                        passwordCipher = if (forgetPassword || (existing != null && existing.user != user)) null else base.passwordCipher,
                        fingerprint = if (forgetFingerprint || changedTarget) null else base.fingerprint,
                    )
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Save") }
        OutlinedButton(onClick = { vm.screen = Screen.Servers }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Cancel") }
        if (existing != null) {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Remove this server", color = Palette.OrangeInk)
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${existing.label}?") },
            text = { Text("Its saved password and fingerprint are deleted from this phone. Nothing changes on the computer.") },
            confirmButton = { Button(onClick = { vm.deleteServer(existing) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = Palette.OrangeSoft,
        border = BorderStroke(1.dp, Palette.Orange),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = Palette.OrangeInk, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss", color = Palette.OrangeInk) }
        }
    }
}
