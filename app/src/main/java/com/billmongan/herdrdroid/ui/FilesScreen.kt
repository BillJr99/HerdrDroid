package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.DirEntry
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.transcript.ImageItem
import com.billmongan.herdrdroid.transcript.mimeFor
import org.json.JSONArray
import org.json.JSONObject

private val TEXT_EXT = setOf(
    "txt", "md", "markdown", "json", "py", "kt", "kts", "java", "js", "ts", "tsx", "jsx", "sh", "bash", "zsh", "yaml", "yml",
    "toml", "ini", "cfg", "conf", "xml", "html", "htm", "css", "scss", "c", "h", "cpp", "hpp", "rs", "go", "rb", "php", "sql",
    "csv", "tsv", "log", "gradle", "properties", "tex", "bib", "r", "swift", "lua", "env.example", "gitignore", "dockerfile", "mk",
)

private fun isTextName(name: String): Boolean {
    val n = name.lowercase()
    val ext = n.substringAfterLast('.', "")
    return ext in TEXT_EXT || n in setOf("makefile", "dockerfile", "readme", "license", "todo", ".gitignore", ".bashrc", ".profile")
}

private fun size(b: Long) = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

/** Browses the computer's folders over SSH; files open as code, Markdown, JSON, images or a download card. */
@Composable
fun FilesScreen(vm: AppViewModel, path: String, back: Screen) {
    var entries by remember(path) { mutableStateOf<List<DirEntry>?>(null) }
    var problem by remember(path) { mutableStateOf<String?>(null) }
    var resolved by remember(path) { mutableStateOf(path) }
    var opened by remember(path) { mutableStateOf<DirEntry?>(null) }
    LaunchedEffect(path) {
        vm.listDir(path).onSuccess { e -> entries = e; resolved = e.firstOrNull()?.dir ?: path }.onFailure { problem = it.message }
    }
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (opened != null) opened = null else vm.screen = back }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Ink) }
            Column(Modifier.weight(1f)) {
                Text(opened?.name ?: resolved.substringAfterLast('/').ifBlank { "/" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(opened?.path ?: resolved, fontSize = 12.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (opened == null && resolved != "/") {
                TextButton(onClick = { vm.screen = Screen.Files(resolved.substringBeforeLast('/').ifBlank { "/" }, back) }) { Text("Up") }
            }
        }
        HorizontalDivider(color = Palette.Line)
        val o = opened
        if (o != null) {
            FileView(vm, o)
            return@Column
        }
        when {
            problem != null -> Text(problem!!, color = Palette.OrangeInk, modifier = Modifier.padding(16.dp))
            entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Blue) }
            entries!!.isEmpty() -> Text("This folder is empty.", color = Palette.Muted, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(entries!!, key = { it.name }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (e.isDir) vm.screen = Screen.Files(e.path, back) else opened = e
                        }.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (e.isDir) "▸" else "·", color = if (e.isDir) Palette.Blue else Palette.Muted, fontSize = 16.sp, modifier = Modifier.width(22.dp))
                        Text(e.name, color = Palette.Ink, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!e.isDir) Text(size(e.size), color = Palette.Muted, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = Palette.Line)
                }
            }
        }
    }
}

/** One file: text as code with line numbers (Markdown rendered, JSON pretty), images drawn, the rest a download card. */
@Composable
private fun FileView(vm: AppViewModel, e: DirEntry) {
    val mime = mimeFor(e.name)
    val item = ImageItem("file:${e.path}", path = e.path, mime = mime, name = e.name, kb = (e.size / 1024).toInt())
    ProvideAttachments(vm::attachmentBytes) {
        if (!isTextName(e.name) || e.size > 1_000_000) {
            Column(Modifier.fillMaxSize().padding(16.dp)) { AttachmentView(item) }
            return@ProvideAttachments
        }
        var text by remember(e.path) { mutableStateOf<String?>(null) }
        var problem by remember(e.path) { mutableStateOf<String?>(null) }
        LaunchedEffect(e.path) {
            vm.attachmentBytes(item).onSuccess { text = it.toString(Charsets.UTF_8) }.onFailure { problem = it.message }
        }
        val t = text
        when {
            problem != null -> Text(problem!!, color = Palette.OrangeInk, modifier = Modifier.padding(16.dp))
            t == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Blue) }
            e.name.lowercase().endsWith(".md") || e.name.lowercase().endsWith(".markdown") ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    SelectionContainer { MarkdownText(t, 15f, vm.readingFont.family) }
                    FileCard(item)
                }
            else -> {
                val shown = if (e.name.lowercase().endsWith(".json")) pretty(t) else t
                val lang = e.name.substringAfterLast('.', "")
                val lines = shown.lines()
                val gutter = lines.size.toString().length
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Box(Modifier.padding(12.dp)) { FileCard(item) }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                        Text(
                            lines.indices.joinToString("\n") { (it + 1).toString().padStart(gutter) },
                            fontFamily = Fonts.Mono, fontSize = 12.sp, lineHeight = 17.sp, color = Palette.Muted,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        SelectionContainer {
                            Text(
                                if (shown.length < 200_000) Highlight.code(shown, lang) else AnnotatedString(shown),
                                fontFamily = Fonts.Mono, fontSize = 12.sp, lineHeight = 17.sp, color = Palette.Ink, softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun pretty(s: String): String = runCatching {
    val t = s.trim()
    if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
}.getOrDefault(s)

/** Chooses which agent a reply goes to. */
@Composable
fun ForwardPicker(vm: AppViewModel, exclude: String?, onPick: (Pane) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send this to…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.panes.filter { it.agent != null && it.id != exclude }.forEach { p ->
                    Surface(
                        shape = RoundedCornerShape(12.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line),
                        modifier = Modifier.fillMaxWidth().clickable { onPick(p) },
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AgentTile(p.initial, p.status, size = 30)
                            Text("  ${p.agentName} · ${p.folder}", color = Palette.Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
