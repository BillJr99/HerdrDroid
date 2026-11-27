package com.billmongan.herdrdroid.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
fun ServersScreen(vm: AppViewModel) {
    var finding by remember { mutableStateOf(false) }
    var askPasswordFor by remember { mutableStateOf<Server?>(null) }

    // The status lights: every server checked now and about once a minute while this list shows.
    // The view model checks every server in the background; this only asks for a check now.
    LaunchedEffect(Unit) { vm.startBackgroundChecks() }

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
        go()
    }

    // 2.1.3: Back (and the arrow) returns to where the list was opened from. 2.2.7: always, with
    // or without a server open: with no origin it goes to the main screen, never out of the app.
    androidx.activity.compose.BackHandler { vm.leaveServers() }

    Box(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 20.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                IconButton(onClick = { vm.leaveServers() }, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.Ink)
                }
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
            if (vm.pendingShare != null) {
                Text(
                    "Connect to a server, then pick the agent for what you shared.",
                    color = Palette.Blue, fontSize = 13.5.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
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
            PullRefresh({ vm.checkServers() }, Modifier.fillMaxSize()) {
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
                            // The server's light: yellow needs you, green up, red unreachable, grey unchecked.
                            val h = vm.healthFor(s.id)
                            val tileStatus = when (h?.health) {
                                AppViewModel.Health.NEEDS_YOU -> com.billmongan.herdrdroid.herdr.Status.NEEDS_YOU
                                AppViewModel.Health.UP -> com.billmongan.herdrdroid.herdr.Status.DONE
                                else -> com.billmongan.herdrdroid.herdr.Status.UNKNOWN
                            }
                            Box {
                                AgentTile(s.label.take(1).uppercase(), tileStatus)
                                val dot = when (h?.health) {
                                    AppViewModel.Health.NEEDS_YOU -> Palette.Orange
                                    AppViewModel.Health.UP -> Palette.Green
                                    AppViewModel.Health.DOWN -> Palette.Red
                                    else -> Palette.Muted
                                }
                                Box(Modifier.align(Alignment.BottomEnd).size(13.dp).background(Palette.Card, CircleShape).padding(2.dp).background(dot, CircleShape))
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(s.label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.Ink)
                                // The WebSocket address is not shown in the list (it's a public endpoint).
                                Text(if (s.overWebSocket) "${s.user} · SSH over WebSocket" else "${s.user}@${s.host}:${s.port}", color = Palette.Muted, fontSize = 13.sp)
                                vm.healthFor(s.id)?.let { h ->
                                    Text(h.line, color = when (h.health) {
                                        AppViewModel.Health.NEEDS_YOU -> Palette.OrangeInk
                                        AppViewModel.Health.DOWN -> Palette.Red
                                        else -> Palette.Muted
                                    }, fontSize = 12.5.sp, fontWeight = if (h.health == AppViewModel.Health.NEEDS_YOU) FontWeight.SemiBold else FontWeight.Normal)
                                }
                                // Its agents, each with its tightest usage limit when there is one.
                                vm.serverPanes[s.id]?.filter { it.agent != null }?.takeIf { it.isNotEmpty() }?.let { list ->
                                    val q = vm.serverQuotas[s.id].orEmpty()
                                    Text(
                                        list.joinToString(" · ") { p ->
                                            val t = com.billmongan.herdrdroid.herdr.QuotaProfiles.key(p.agent)?.let { q[it]?.tightest }
                                            p.agentName + (t?.let { " " + it.usedPercent.toInt() + "%" } ?: "")
                                        },
                                        // The whole list; a very long one ends with "…" after eight lines.
                                        color = Palette.Muted, fontSize = 12.sp, maxLines = 8, overflow = TextOverflow.Ellipsis,
                                    )
                                }
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
        // Find on this network: machines on the same Wi-Fi that answer SSH, to add in one tap.
        androidx.compose.material3.OutlinedButton(
            onClick = { finding = true },
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
        ) { Text("Find on this network") }
        ExtendedFloatingActionButton(
            onClick = { vm.screen = Screen.EditServer(null) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add server") },
            containerColor = Palette.Blue,
            contentColor = Palette.OnBlue,
            // Named for screen readers and UI automation, which did not see its text.
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
                .semantics { contentDescription = "Add server" },
        )
    }

    if (finding) LanFinderDialog(vm) { finding = false }

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

    vm.pendingBackendChoice?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.dismissBackendChoice() },
            title = { Text("Which herdr?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${p.server.label} runs both the herdr TUI and the Herdr App's server (herdr-core). They keep separate " +
                            "agents. Show one, the other, or both (both adds a second entry for the same computer; the drawer lists " +
                            "the agents of each). The choice is saved and can be changed in the server's settings.",
                    )
                    Button(onClick = { vm.chooseBackend(core = false) }, modifier = Modifier.fillMaxWidth()) { Text("herdr (TUI)") }
                    Button(onClick = { vm.chooseBackend(core = true) }, modifier = Modifier.fillMaxWidth()) { Text("Herdr App (herdr-core)") }
                    OutlinedButton(onClick = { vm.chooseBothBackends() }, modifier = Modifier.fillMaxWidth()) { Text("Both, as two entries") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.dismissBackendChoice() }) { Text("Cancel") } },
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
fun EditServerScreen(vm: AppViewModel, existing: Server?, prefillHost: String? = null, prefillName: String? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val draftId = remember { existing?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(existing?.name ?: prefillName.orEmpty()) }
    var host by remember { mutableStateOf(existing?.host ?: prefillHost.orEmpty()) }
    var viaWs by remember { mutableStateOf(existing?.overWebSocket ?: false) }
    // A new WebSocket address starts with "wss://" typed in, which can be edited or removed.
    var wsUrl by remember { mutableStateOf(existing?.wsUrl ?: "wss://") }
    var pinned by remember { mutableStateOf(existing?.fingerprint ?: "") }
    var backend by remember { mutableStateOf(existing?.backend) }
    // 2.2.6: small packets (a smaller TCP MSS) for a lossy Wi-Fi path; direct SSH only.
    var smallPackets by remember { mutableStateOf(existing?.smallPackets ?: false) }
    var mssText by remember { mutableStateOf((existing?.mss ?: com.billmongan.herdrdroid.ssh.SmallPackets.DEFAULT).toString()) }
    // 1.2.7: a new server starts with port 22 and the command "herdr" (both required; before, they
    // started empty and Save stayed grey with nothing saying why).
    var port by remember { mutableStateOf(existing?.port?.toString() ?: "22") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var herdrPath by remember { mutableStateOf(existing?.herdrPath ?: "herdr") }
    var paneQuotaCommand by remember { mutableStateOf(existing?.paneQuotaCommand.orEmpty()) }
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

    // Back closes the form to the server list, as Cancel does (2.1.3: it never leaves the app).
    androidx.activity.compose.BackHandler { vm.closeServerForm(if (existing == null) draftId else null) }

    val portNum = port.toIntOrNull()
    val keyReady = keyKind == "keystore" || (keyKind == "imported" && keyCipher != null)
    val wsOk = wsUrl.trim().let { (it.startsWith("wss://", true) || it.startsWith("ws://", true)) && it.substringAfter("://").isNotBlank() }
    val pinOk = pinned.isBlank() || normalizeFingerprint(pinned) != null
    val mssNum = mssText.toIntOrNull()
    val mssOk = mssNum != null && mssNum in com.billmongan.herdrdroid.ssh.SmallPackets.MIN..com.billmongan.herdrdroid.ssh.SmallPackets.MAX
    val valid = if (viaWs) {
        wsOk && user.isNotBlank() && herdrPath.isNotBlank() && useKey && keyReady && pinOk
    } else {
        host.isNotBlank() && user.isNotBlank() && portNum != null && portNum in 1..65535 &&
            herdrPath.isNotBlank() && (!useKey || keyReady) && (!smallPackets || mssOk)
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
        Text("herdr on that computer", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Palette.Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(selected = backend == null, onClick = { backend = null }, label = { Text("Automatic", softWrap = false) })
            FilterChip(selected = backend == "tui", onClick = { backend = "tui" }, label = { Text("herdr (TUI)", softWrap = false) })
            FilterChip(selected = backend == "core", onClick = { backend = "core" }, label = { Text("herdr-core (Herdr App)", softWrap = false) })
        }
        Text(
            when (backend) {
                null -> "Uses whichever is running there. If both are, the app asks once and remembers."
                "core" -> "Talks to herdr-core through its hc command (found on the computer's PATH or in ~/.local/bin)."
                else -> "Talks to the herdr TUI's server through the herdr command below."
            },
            color = Palette.Muted, fontSize = 13.sp,
        )
        OutlinedTextField(
            herdrPath, { herdrPath = it }, label = { Text("herdr command") }, singleLine = true,
            supportingText = { Text("The command that runs herdr on that computer, such as herdr or ~/.local/bin/herdr") },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            paneQuotaCommand,
            { paneQuotaCommand = it.filterNot(Char::isISOControl).take(com.billmongan.herdrdroid.herdr.QUOTA_COMMAND_LIMIT) },
            label = { Text("Pane quota command (optional)") }, singleLine = true,
            supportingText = { Text("Runs over SSH. Use a trusted, read-only command that prints quota JSON keyed by pane ID.") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (!viaWs) {
            SwitchRow(
                "Small packets (for lossy Wi-Fi)",
                "Asks this computer to send smaller packets. Try it if signing in works but lists or chats stall on Wi-Fi.",
                smallPackets,
            ) { smallPackets = it }
            if (smallPackets) OutlinedTextField(
                mssText, { mssText = it.filter(Char::isDigit).take(4) }, label = { Text("Packet size (MSS, bytes)") }, singleLine = true,
                isError = !mssOk,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text("From ${com.billmongan.herdrdroid.ssh.SmallPackets.MIN} to ${com.billmongan.herdrdroid.ssh.SmallPackets.MAX}; " +
                        "${com.billmongan.herdrdroid.ssh.SmallPackets.DEFAULT} suits most networks.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

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
        if (existing?.passwordCipher != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { forgetPassword = !forgetPassword }) {
                Checkbox(checked = forgetPassword, onCheckedChange = { forgetPassword = it })
                Text("Forget the saved password", color = Palette.Ink)
            }
        }
        if (existing?.fingerprint != null && !viaWs) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { forgetFingerprint = !forgetFingerprint }) {
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
                        paneQuotaCommand = paneQuotaCommand.trim().takeUnless(String::isBlank),
                        biometric = false,
                        passwordCipher = if (viaWs || forgetPassword || (existing != null && existing.user != user)) null else base.passwordCipher,
                        fingerprint = fp,
                        useKey = if (viaWs) true else useKey,
                        wsUrl = if (viaWs) url else null,
                        backend = backend,
                        // Kept off for a WebSocket server, where it can't apply.
                        smallPackets = smallPackets && !viaWs,
                        // Only a valid value typed with the switch on replaces the saved one (pre-review L2).
                        mss = if (smallPackets && !viaWs && mssOk) mssNum!! else base.mss,
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
            onClick = { vm.closeServerForm(if (existing == null) draftId else null) },
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



/**
 * Lists the machines on this Wi-Fi that answer SSH, as they are found. Nothing signs in anywhere:
 * each line is an address, the name it advertises if any, and the SSH version it reports. Tapping
 * one opens Add server with the address filled in; herdr itself is checked on the first connection.
 */
@Composable
private fun LanFinderDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val found = remember { androidx.compose.runtime.mutableStateListOf<com.billmongan.herdrdroid.net.LanHost>() }
    var checked by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(false) }
    val net = remember { com.billmongan.herdrdroid.net.LanScan.localNetwork(context) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (net == null) { done = true; return@LaunchedEffect }
        com.billmongan.herdrdroid.net.LanScan.scan(context,
            onProgress = { c, t -> checked = c; total = t },
            onFound = { h -> if (found.none { it.ip == h.ip }) found += h })
        done = true
    }
    val known = vm.servers.map { it.host }.toSet()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find on this network") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    net == null -> Text("Connect the phone to Wi-Fi first. The search only looks at the local network you're on.", color = Palette.Muted)
                    !done -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  Checking ${net.first.substringBeforeLast('.')}.x for SSH… $checked of $total", color = Palette.Muted, fontSize = 13.sp)
                    }
                    found.isEmpty() -> Text("No machine on this network answered SSH on port 22.", color = Palette.Muted)
                    else -> Text("Machines that answer SSH. Tap one to add it; herdr is checked when you first connect.", color = Palette.Muted, fontSize = 13.sp)
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(found.sortedBy { h -> h.ip.split('.').map { it.toIntOrNull() ?: 0 }.fold(0L) { a, b -> a * 256 + b } }, key = { it.ip }) { h ->
                        val already = h.ip in known || (h.name != null && h.name in known)
                        Column(
                            Modifier.fillMaxWidth().clickable(enabled = !already) {
                                onDismiss()
                                vm.screen = Screen.EditServer(null, prefillHost = h.ip, prefillName = h.name?.substringBefore('.') ?: h.ip)
                            }.padding(vertical = 8.dp),
                        ) {
                            Text((h.name ?: h.ip) + if (already) "  (added)" else "", color = if (already) Palette.Muted else Palette.Ink, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(h.ip.takeIf { h.name != null }, h.ssh).joinToString(" · "), color = Palette.Muted, fontSize = 12.5.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(if (done) "Close" else "Stop") } },
    )
}
