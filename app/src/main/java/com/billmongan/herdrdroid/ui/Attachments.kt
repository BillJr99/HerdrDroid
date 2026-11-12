package com.billmongan.herdrdroid.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.billmongan.herdrdroid.transcript.ImageItem
import com.billmongan.herdrdroid.transcript.mimeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Fetches an attachment's bytes from the computer (cached by the view model). */
typealias AttachmentLoader = suspend (ImageItem) -> Result<ByteArray>

/** What the chat needs to show and keep attachments: fetching, saving and sharing. */
class AttachmentHost(
    val load: AttachmentLoader,
    val save: (ByteArray, String, String) -> Unit,
    val share: (ByteArray, String, String, Boolean) -> Unit,
)

val LocalAttachments = staticCompositionLocalOf<AttachmentHost?> { null }

/**
 * Sets up "Download" (Android's own Save as picker, so it needs no storage permission and the
 * file goes wherever you choose) and Share/Open (through a FileProvider copy in the app's cache).
 */
@Composable
fun ProvideAttachments(load: AttachmentLoader, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val bytes = pending
        pending = null
        if (uri != null && bytes != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
        }
    }
    val host = remember(load) {
        AttachmentHost(
            load = load,
            save = { bytes, name, _ -> pending = bytes; saver.launch(name) },
            share = { bytes, name, mime, open -> shareBytes(context, bytes, name, mime, open) },
        )
    }
    CompositionLocalProvider(LocalAttachments provides host) { content() }
}

private fun shareBytes(context: Context, bytes: ByteArray, name: String, mime: String, open: Boolean) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    // Old shared copies are cleared each time, so the cache never grows.
    dir.listFiles()?.forEach { it.delete() }
    val f = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment" })
    f.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    val intent = if (open) {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    } else {
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

/** Decodes an image no larger than [maxSide] on its long side, so a big screenshot can't use much memory. */
private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun sizeText(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes bytes"
}

/**
 * One attachment in the chat. An image is fetched when it first appears and drawn (tap for the
 * full-screen viewer with Download and Share); any other file or blob is a card with Download,
 * Share and Open, fetched only when one of those is tapped.
 */
@Composable
fun AttachmentView(item: ImageItem) {
    val host = LocalAttachments.current ?: return
    if (!item.isImage) {
        FileCard(item)
        return
    }
    var bitmap by remember(item.key) { mutableStateOf<Bitmap?>(null) }
    var bytes by remember(item.key) { mutableStateOf<ByteArray?>(null) }
    var problem by remember(item.key) { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf(false) }
    LaunchedEffect(item.key) {
        host.load(item)
            .onSuccess { b ->
                bytes = b
                bitmap = withContext(Dispatchers.Default) { decode(b, 1600) }
                if (bitmap == null) problem = "This image couldn't be decoded."
            }
            .onFailure { problem = it.message ?: "The image couldn't be fetched." }
    }
    Box(
        Modifier.fillMaxWidth().padding(start = if (item.user) 48.dp else 0.dp),
        contentAlignment = if (item.user) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Palette.Code,
            border = BorderStroke(1.dp, Palette.Line),
            modifier = Modifier.widthIn(max = 340.dp).clickable(enabled = bitmap != null) { viewer = true },
        ) {
            val b = bitmap
            when {
                b != null -> Image(
                    b.asImageBitmap(), contentDescription = item.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.heightIn(max = 300.dp).clip(RoundedCornerShape(14.dp)),
                )
                problem != null -> Text(problem!!, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                else -> Box(Modifier.size(160.dp, 110.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = Palette.Blue, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
    val b = bitmap
    val raw = bytes
    if (viewer && b != null && raw != null) {
        ImageViewer(b, onClose = { viewer = false }, onSave = { host.save(raw, fileName(item), item.mime) }, onShare = { host.share(raw, fileName(item), item.mime, false) })
    }
}

private fun fileName(item: ImageItem): String {
    val base = item.name ?: item.path?.substringAfterLast('/')
    if (base != null) return base
    val ext = when (item.mime) {
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "application/pdf" -> "pdf"
        else -> if (item.mime.startsWith("image/")) "png" else "bin"
    }
    return "herdrdroid-${item.key.takeLast(8).replace(Regex("[^A-Za-z0-9]"), "")}.$ext"
}

/** A file or blob: its name, type and size, with Download, Share and Open. */
@Composable
fun FileCard(item: ImageItem) {
    val host = LocalAttachments.current ?: return
    val scope = rememberCoroutineScope()
    var busy by remember(item.key) { mutableStateOf(false) }
    var problem by remember(item.key) { mutableStateOf<String?>(null) }
    var size by remember(item.key) { mutableStateOf(if (item.kb > 0) item.kb * 1024 else 0) }
    fun run(action: (ByteArray) -> Unit) {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            host.load(item).onSuccess { b -> size = b.size; action(b) }.onFailure { problem = it.message }
            busy = false
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = if (item.user) 48.dp else 0.dp), contentAlignment = if (item.user) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(shape = RoundedCornerShape(14.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line), modifier = Modifier.widthIn(max = 360.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(Palette.BlueSoft, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                        Text(item.label.substringAfterLast('.', "").take(4).uppercase().ifBlank { "FILE" }, color = Palette.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.padding(start = 10.dp).weight(1f, fill = false)) {
                        Text(item.label, color = Palette.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(item.mime, if (size > 0) sizeText(size) else null).joinToString(" · "), color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    if (busy) CircularProgressIndicator(strokeWidth = 2.dp, color = Palette.Blue, modifier = Modifier.padding(start = 8.dp).size(16.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    ActionPill("Download") { run { host.save(it, fileName(item), item.mime) } }
                    ActionPill("Share") { run { host.share(it, fileName(item), item.mime, false) } }
                    ActionPill("Open") { run { host.share(it, fileName(item), item.mime, true) } }
                }
                problem?.let { Text(it, color = Palette.OrangeInk, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun ActionPill(label: String, onClick: () -> Unit) {
    Text(
        label, color = Palette.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.BlueSoft).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Full screen, pinch to zoom and drag to pan, with Download and Share. */
@Composable
private fun ImageViewer(bitmap: Bitmap, onClose: () -> Unit, onSave: () -> Unit, onShare: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().transformable(state).graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
            Row(
                Modifier.align(Alignment.BottomCenter).padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ViewerButton("Download", onSave)
                ViewerButton("Share", onShare)
                ViewerButton("Close", onClose)
            }
        }
    }
}

@Composable
private fun ViewerButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x66FFFFFF)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

private val PATH = Regex(
    """(?<![\w/~.])((?:~/|/)(?:[\w.@+-]+/)*[\w.@+-]*[\w@+-]\.(?:png|jpe?g|gif|webp|bmp|svg|pdf|csv|md|txt|json|html?|zip|apk|docx|xlsx|pptx|log|py|kt|ts|js|sh|ya?ml|toml|mp3|ogg|oga|opus|wav|m4a|flac|mp4|mov|webm|mkv|tar|gz|tgz|7z|rtf|odt|ods|epub|ics|xml|heic|tiff?|ipynb|tex|doc|xls|ppt))(?![\w/])""",
    RegexOption.IGNORE_CASE,
)

/** Hermes's file delivery tag: MEDIA:/absolute/path, any file type. */
private val MEDIA = Regex("""(?<![\w"'`])MEDIA:((?:~/|/)[^\s"'`<>]+)""")

/**
 * Files a reply mentions, at most six, so they can be opened from the chat: MEDIA:/path tags
 * (Hermes's way of sending a file, any type) and plain paths with a known file extension.
 */
fun mentionedFiles(text: String): List<String> {
    val media = MEDIA.findAll(text).map { it.groupValues[1].trimEnd('.', ',', ';', ':', ')', ']', '}', '"', '\'') }
    return (media + PATH.findAll(text).map { it.groupValues[1] }).distinct().take(6).toList()
}

/** Chips for the files a reply mentions; tapping one fetches it from the computer. */
@Composable
fun MentionedFiles(paths: List<String>) {
    if (paths.isEmpty()) return
    var open by remember { mutableStateOf<String?>(null) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        paths.forEach { p ->
            Surface(
                shape = RoundedCornerShape(12.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line),
                modifier = Modifier.clickable { open = if (open == p) null else p },
            ) {
                Text("📎 " + p.substringAfterLast('/'), color = Palette.Ink, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 1)
            }
        }
    }
    open?.let { p ->
        Spacer(Modifier.height(6.dp))
        AttachmentView(ImageItem("file:$p", path = p, mime = mimeFor(p), name = p.substringAfterLast('/')))
    }
}
