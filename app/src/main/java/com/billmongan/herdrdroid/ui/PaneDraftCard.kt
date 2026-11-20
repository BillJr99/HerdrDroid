package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A draft typed in the pane's input box at the computer (2.1.0, Bill: "show it in chat view and
 * give me the option to send or clear it there in the app"). Send draft presses Enter once; Clear
 * draft asks first, then presses only Backspace. Neither runs without a tap.
 *
 * [canAct] is false when the app can't see where the draft ends or who is in the pane: the text is
 * shown with the way to the terminal and no buttons that type. [canClear] is false for agents whose
 * input box the app hasn't checked for a safe clear. [sendNote], when set, says why Send draft is off.
 *
 * The confirmation holds the [draft] as it was when Clear draft was tapped, shows its text, and is
 * dismissed if the draft on screen changes meanwhile, so it never clears something else.
 */
@Composable
internal fun <T> PaneDraftCard(
    agent: String,
    draft: T,
    text: String,
    canAct: Boolean,
    canClear: Boolean,
    busy: String?,
    sendNote: String?,
    onSend: (T) -> Unit,
    onClear: (T) -> Unit,
    onShowTerminal: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf<Pair<T, String>?>(null) }
    LaunchedEffect(text) { if (confirmClear?.second != text) confirmClear = null }
    Surface(
        shape = RoundedCornerShape(14.dp), color = Palette.BlueSoft, border = BorderStroke(1.dp, Palette.Blue),
        modifier = Modifier.fillMaxWidth().testTag("pane-draft-card"),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Draft in the pane", color = Palette.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "This is typed in $agent's input box at the computer and hasn't been sent. " +
                    "Messages from the app wait until it is sent or cleared.",
                color = Palette.Muted, fontSize = 12.5.sp,
            )
            Surface(shape = RoundedCornerShape(8.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
                SelectionContainer {
                    Text(
                        text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Palette.Ink,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                            .padding(10.dp).testTag("pane-draft-text"),
                    )
                }
            }
            if (!canAct) {
                Text(
                    "The app can't see where this draft ends, so it won't send or clear it from here.",
                    color = Palette.Muted, fontSize = 12.5.sp,
                )
                TextButton(onClick = onShowTerminal) { Text("Show terminal") }
            } else {
                if (sendNote != null) Text(sendNote, color = Palette.Muted, fontSize = 12.5.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sendNote == null) Button(onClick = { onSend(draft) }, enabled = busy == null) {
                        Text(if (busy == "send") "Sending…" else "Send draft")
                    }
                    if (canClear) OutlinedButton(onClick = { confirmClear = draft to text }, enabled = busy == null,
                        modifier = Modifier.testTag("pane-draft-clear")) {
                        Text(if (busy == "clear") "Clearing…" else "Clear draft")
                    }
                }
            }
        }
    }
    confirmClear?.let { (captured, capturedText) ->
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text("Clear the draft?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This deletes this text from $agent's input box at the computer. The app can't bring it back. Enter is not pressed.")
                    Text(
                        capturedText, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).testTag("pane-draft-confirm-text"),
                    )
                }
            },
            confirmButton = {
                Button(onClick = { confirmClear = null; onClear(captured) }, modifier = Modifier.testTag("pane-draft-confirm-clear")) { Text("Clear draft") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text("Keep it") } },
        )
    }
}
