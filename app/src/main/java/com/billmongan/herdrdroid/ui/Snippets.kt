package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel

/** A saved prompt. `{{name}}` in [text] is a blank filled in when it is used. */
data class Snippet(val name: String, val text: String) {
    val blanks: List<String> get() = Regex("""\{\{\s*([^}]+?)\s*}}""").findAll(text).map { it.groupValues[1] }.distinct().toList()
    fun fill(values: Map<String, String>): String =
        Regex("""\{\{\s*([^}]+?)\s*}}""").replace(text) { m -> values[m.groupValues[1]] ?: m.value }
}

/** Saved prompts: tap one to put it in the message box (filling its blanks first), add or remove them. */
@Composable
fun SnippetsDialog(vm: AppViewModel, onInsert: (String) -> Unit, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var filling by remember { mutableStateOf<Snippet?>(null) }
    var removing by remember { mutableStateOf<Snippet?>(null) }
    val f = filling
    if (f != null) {
        val values = remember(f) { mutableStateMapOf<String, String>() }
        AlertDialog(
            onDismissRequest = { filling = null },
            title = { Text(f.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    f.blanks.forEach { b -> OutlinedTextField(values[b] ?: "", { values[b] = it }, label = { Text(b) }, modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = { TextButton(onClick = { onInsert(f.fill(values)); filling = null; onDismiss() }) { Text("Insert") } },
            dismissButton = { TextButton(onClick = { filling = null }) { Text("Cancel") } },
        )
        return
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New snippet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(text, { text = it }, label = { Text("Prompt") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Text("Write {{file}} or any {{word}} for a blank you fill in each time.", color = Palette.Muted, fontSize = 12.5.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && text.isNotBlank(), onClick = { vm.addSnippet(Snippet(name.trim(), text.trim())); adding = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
        return
    }
    removing?.let { r ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove \"${r.name}\"?") },
            confirmButton = { TextButton(onClick = { vm.removeSnippet(r); removing = null }) { Text("Remove", color = Palette.OrangeInk) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Snippets") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (vm.snippets.isEmpty()) Text("No snippets yet. Save prompts you send often, with blanks to fill in.", color = Palette.Muted, fontSize = 14.sp)
                vm.snippets.forEach { s ->
                    Surface(
                        shape = RoundedCornerShape(12.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line),
                        modifier = Modifier.fillMaxWidth().clickable { if (s.blanks.isEmpty()) { onInsert(s.text); onDismiss() } else filling = s },
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.text, color = Palette.Muted, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text("✕", color = Palette.Muted, fontSize = 16.sp, modifier = Modifier.clickable { removing = s }.padding(start = 10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { adding = true }) { Text("New snippet") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
