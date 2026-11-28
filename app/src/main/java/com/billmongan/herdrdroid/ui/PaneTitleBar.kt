package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The pane screen's title (2.1.4, Bill 2026-11-21): the pane's name, the server under it in a
 * smaller style, and the model in use under that in the smallest style. Each line is one line,
 * cut with an ellipsis on a narrow phone; the model line is left off when the model is unknown.
 * [compact] (keyboard up) keeps the name only. [status] goes after the server, with its dot.
 * [nameAnchor] draws over the name (the Hermes menu hangs from it). [statusLine] replaces the
 * server's status part, for a link that is down.
 */
@Composable
internal fun PaneTitleLines(
    name: String,
    server: String?,
    model: String?,
    modifier: Modifier = Modifier,
    status: String? = null,
    statusInk: Color? = null,
    compact: Boolean = false,
    onName: () -> Unit = {},
    onModel: (() -> Unit)? = null,
    nameAnchor: @Composable () -> Unit = {},
    statusLine: (@Composable () -> Unit)? = null,
    quota: com.billmongan.herdrdroid.herdr.PaneQuotaReading? = null,
) {
    Column(modifier) {
        Box {
            Text(
                name,
                fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 21.sp, color = Palette.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onName).semantics { contentDescription = "Pane $name" },
            )
            nameAnchor()
        }
        if (!compact) {
            val serverText = listOfNotNull(server?.trim()?.ifBlank { null }, status).joinToString(" · ")
            if (statusLine != null) {
                if (!server.isNullOrBlank()) ServerLine(server.trim(), null)
                statusLine()
            } else if (serverText.isNotEmpty()) {
                ServerLine(serverText, statusInk, server?.trim()?.ifBlank { null }, status)
            }
            if (statusLine == null && !model.isNullOrBlank()) {
                Text(
                    model.trim(),
                    style = MaterialTheme.typography.labelSmall, lineHeight = 14.sp, color = Palette.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .then(if (onModel != null) Modifier.clickable(onClick = onModel) else Modifier)
                        .semantics { contentDescription = "Model ${model.trim()}" + if (onModel != null) ", tap to change" else "" },
                )
            }
        }
        quota?.let { PaneQuotaText(it) }
    }
}

@Composable
private fun ServerLine(text: String, dot: Color?, server: String? = text, status: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) Box(Modifier.padding(end = 5.dp).size(7.dp).background(dot, CircleShape))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp, color = Palette.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics {
                contentDescription = listOfNotNull(server?.let { "Server $it" }, status).joinToString(", ")
            },
        )
    }
}


@Composable
internal fun PaneWarningBadge(count: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val label = if (count == 1) "1 warning" else "$count warnings"
    val action = if (onClick == null) Modifier else Modifier.clickable(
        role = Role.Button, onClickLabel = "Show warning details", onClick = onClick,
    )
    Text(
        "⚠ $count",
        color = Palette.Orange,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = modifier.then(action).semantics { contentDescription = label },
    )
}
