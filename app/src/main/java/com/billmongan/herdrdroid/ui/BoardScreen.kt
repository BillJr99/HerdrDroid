package com.billmongan.herdrdroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.board.BoardCard
import com.billmongan.herdrdroid.board.BoardConflict
import com.billmongan.herdrdroid.board.BoardNote
import com.billmongan.herdrdroid.board.BoardState
import com.billmongan.herdrdroid.board.BoardStatus
import com.billmongan.herdrdroid.board.ServerBoard
import java.time.Instant
import java.util.UUID

internal data class BoardSource(val id: String, val label: String, val availability: String? = null)

private data class BoardTab(val label: String, val serverId: String? = null, val isAll: Boolean = false)
private data class BoardSelection(val serverId: String?, val cardId: String)

@Composable
internal fun BoardDrawerItem(selected: Boolean, onClick: () -> Unit) {
    Text(
        "Board",
        color = Palette.Ink,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().background(if (selected) Palette.BlueSoft else Palette.Ground)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp).testTag("drawer-board"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardScreen(
    state: BoardState,
    servers: List<BoardSource>,
    onMenu: () -> Unit,
    onBack: () -> Unit,
    onRefresh: (List<String>) -> Unit,
    onRetry: (String) -> Unit,
    onSave: (String?, BoardCard) -> Unit,
    onMovePhoneCard: (String, String) -> Unit,
    onAddNote: (String?, String, BoardNote) -> Unit,
    onResolve: (String, String, String, Boolean) -> Unit,
    handleBack: Boolean = true,
) {
    val serverIds = servers.map(BoardSource::id)
    val tabs = listOf(BoardTab("Phone")) + servers.map { BoardTab(it.label, it.id) } + BoardTab("All", isAll = true)
    var tabId by rememberSaveable { mutableStateOf<String?>(null) }
    var allTabs by rememberSaveable { mutableStateOf(false) }
    val index = tabs.indexOfFirst { it.serverId == tabId && it.isAll == allTabs }.coerceAtLeast(0)
    val tab = tabs[index]
    var mineOnly by rememberSaveable { mutableStateOf(false) }
    var waitingOnly by rememberSaveable { mutableStateOf(false) }
    var ownerName by rememberSaveable { mutableStateOf("") }
    var selectingOwner by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<BoardSelection?>(null) }
    var editor by remember { mutableStateOf<Pair<String?, BoardCard>?>(null) }
    val active = detail
    val activeCard = active?.let {
        val cards = if (it.serverId == null) state.phone else state.servers[it.serverId]?.cards.orEmpty()
        cards[it.cardId]
    }
    val activeConflicts = active?.let { selection ->
        selection.serverId?.let(state.servers::get)?.conflicts.orEmpty().filter { it.cardId == selection.cardId }
    }.orEmpty()

    LaunchedEffect(Unit) { onRefresh(serverIds) }
    BackHandler(enabled = handleBack) {
        if (detail != null) detail = null else onBack()
    }
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "All agents", tint = Palette.Ink) }
            Text("Board", Modifier.weight(1f).padding(start = 10.dp), color = Palette.Ink, fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold)
            if (activeCard == null && activeConflicts.isEmpty() && (tab.serverId != null || tab.isAll && servers.isNotEmpty())) {
                val refreshIds = if (tab.isAll) serverIds else listOfNotNull(tab.serverId)
                IconButton(onClick = { onRefresh(refreshIds) }) {
                    Icon(Icons.Filled.Refresh, "Refresh board", tint = Palette.Ink)
                }
            }
        }
        if (active != null && (activeCard != null || activeConflicts.isNotEmpty())) {
            BoardCardDetail(
                card = activeCard,
                conflicts = activeConflicts,
                destinations = if (active.serverId == null) servers else emptyList(),
                author = ownerName.ifBlank { "Phone" },
                onBack = { detail = null },
                onEdit = { card -> editor = active.serverId to card },
                onStatus = { card, status -> onSave(active.serverId, card.moveStatus(status, active.serverId == null)) },
                onMove = { id -> onMovePhoneCard(active.cardId, id); detail = null },
                onNote = { onAddNote(active.serverId, active.cardId, it) },
                onResolve = { field, keepPhone ->
                    active.serverId?.let { onResolve(it, active.cardId, field, keepPhone) }
                },
            )
        } else {
            ScrollableTabRow(selectedTabIndex = index, edgePadding = 10.dp, containerColor = Palette.Ground) {
                tabs.forEach { target ->
                    Tab(
                        selected = target == tab,
                        onClick = { tabId = target.serverId; allTabs = target.isAll; detail = null },
                        text = { Text(target.label, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(mineOnly, onClick = {
                    if (mineOnly) mineOnly = false
                    else if (ownerName.isBlank()) selectingOwner = true
                    else mineOnly = true
                }, label = { Text("Mine") })
                FilterChip(waitingOnly, onClick = { waitingOnly = !waitingOnly }, label = { Text("Waiting") },
                    modifier = Modifier.testTag("board-filter-waiting"))
                if (mineOnly) {
                    TextButton(onClick = { selectingOwner = true }) {
                        Text(ownerName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (!tab.isAll) {
                TextButton(onClick = {
                    editor = tab.serverId to BoardCard(id = UUID.randomUUID().toString(), title = "",
                        owner = ownerName.takeIf(String::isNotBlank))
                }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("New card") }
            }
            val refreshIds = if (tab.isAll) serverIds else listOfNotNull(tab.serverId)
            val body: @Composable () -> Unit = {
                BoardList(state, servers, tab, waitingOnly, ownerName.takeIf { mineOnly }, onRetry) { detail = it }
            }
            if (refreshIds.isEmpty()) body()
            else PullRefresh(onRefresh = { onRefresh(refreshIds) }, modifier = Modifier.fillMaxSize(), content = body)
        }
    }

    if (selectingOwner) {
        var input by remember { mutableStateOf(ownerName) }
        AlertDialog(
            onDismissRequest = { selectingOwner = false },
            title = { Text("Filter by owner") },
            text = {
                OutlinedTextField(input, { input = it }, label = { Text("Your board owner name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = input.isNotBlank(), onClick = {
                    ownerName = input.trim()
                    mineOnly = true
                    selectingOwner = false
                }) { Text("Show mine") }
            },
            dismissButton = { TextButton(onClick = { selectingOwner = false }) { Text("Cancel") } },
        )
    }
    editor?.let { (serverId, card) ->
        val cards = if (serverId == null) state.phone else state.servers[serverId]?.cards.orEmpty()
        val existing = cards[card.id]
        val changedWhileEditing = card.title.isNotBlank() && existing != card
        BoardEditor(card, serverId == null, changedWhileEditing, onDismiss = { editor = null }) { changed ->
            onSave(serverId, changed)
            editor = null
            detail = BoardSelection(serverId, changed.id)
        }
    }
}

private fun BoardCard.moveStatus(status: BoardStatus, phone: Boolean): BoardCard = copy(
    status = status,
    nativeStatus = if (phone && nativeStatus == this.status.wire) status.wire else nativeStatus,
)

@Composable
private fun BoardList(
    state: BoardState,
    servers: List<BoardSource>,
    tab: BoardTab,
    waitingOnly: Boolean,
    owner: String?,
    retry: (String) -> Unit,
    open: (BoardSelection) -> Unit,
) {
    val boards = when {
        tab.isAll -> listOf(null) + servers
        tab.serverId == null -> listOf(null)
        else -> servers.filter { it.id == tab.serverId }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("board-cards")) {
        boards.forEach { source ->
            val sourceId = source?.id
            val serverBoard = sourceId?.let(state.servers::get)
            val cards = if (source == null) state.phone else serverBoard?.cards.orEmpty()
            val conflicts = serverBoard?.conflicts.orEmpty().groupBy(BoardConflict::cardId)
            val openCards = cards.values.filter {
                (!waitingOnly || it.status == BoardStatus.WAITING) &&
                    (owner == null || it.owner.equals(owner, ignoreCase = true))
            }
                .sortedWith(compareBy({ it.status.ordinal }, { it.title.lowercase() }, { it.id }))
            if (tab.isAll) item {
                Text(serverBoard?.name ?: source?.label ?: "Phone", color = Palette.Ink, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            }
            source?.availability?.let { message ->
                item { Text(message, color = Palette.Muted, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            if (sourceId != null && serverBoard != null) item {
                BoardSyncStatus(serverBoard) { retry(sourceId) }
            }
            if (openCards.isEmpty() && conflicts.isEmpty()) item {
                Text("No cards", color = Palette.Muted, modifier = Modifier.padding(16.dp))
            }
            items(openCards) { card ->
                BoardListCard(card.title, card.status, card.owner, conflicts[card.id].orEmpty().isNotEmpty()) {
                    open(BoardSelection(sourceId, card.id))
                }
            }
            items(conflicts.keys.filterNot(cards::containsKey)) { id ->
                BoardListCard(id, null, null, true) { open(BoardSelection(sourceId, id)) }
            }
        }
    }
}

@Composable
private fun BoardSyncStatus(board: ServerBoard, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        board.asOf?.let { Text("Last export $it", fontSize = 12.sp, color = Palette.Muted) }
        val status = when {
            board.noAnswer -> "No answer. Showing the cached board."
            board.requestSentAt != null -> "Waiting for the board agent"
            board.request != null -> "Queued for the board agent"
            else -> null
        }
        status?.let { Text(it, color = if (board.noAnswer) Palette.OrangeInk else Palette.Muted, fontSize = 13.sp) }
        board.issue?.let { Text(it, color = Palette.OrangeInk, fontSize = 13.sp) }
        if (board.noAnswer || board.issue != null && board.request == null) TextButton(onClick = retry) { Text("Retry") }
    }
}

@Composable
private fun BoardListCard(title: String, status: BoardStatus?, owner: String?, conflict: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp), color = Palette.Card,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            val subtitle = listOfNotNull(status?.label, owner, "Conflict".takeIf { conflict }).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = if (conflict) Palette.OrangeInk else Palette.Muted, fontSize = 13.sp)
            }
        }
    }
}

private val BoardStatus.label: String get() = wire.replaceFirstChar { it.uppercase() }

@Composable
private fun BoardCardDetail(
    card: BoardCard?,
    conflicts: List<BoardConflict>,
    destinations: List<BoardSource>,
    author: String,
    onBack: () -> Unit,
    onEdit: (BoardCard) -> Unit,
    onStatus: (BoardCard, BoardStatus) -> Unit,
    onMove: (String) -> Unit,
    onNote: (BoardNote) -> Unit,
    onResolve: (String, Boolean) -> Unit,
) {
    var moving by remember { mutableStateOf(false) }
    var note by rememberSaveable(card?.id) { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back to cards") }
            if (card != null) TextButton(onClick = { onEdit(card) }) { Text("Edit card") }
        }
        Text(card?.title ?: "Card conflict", color = Palette.Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        conflicts.forEach { conflict ->
            Surface(shape = RoundedCornerShape(12.dp), color = Palette.Card) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${conflict.field} conflict", color = Palette.OrangeInk, fontWeight = FontWeight.Bold)
                    Text("Phone: ${conflict.phoneValue}", color = Palette.Ink)
                    Text("Server: ${conflict.serverValue}", color = Palette.Ink)
                    Row {
                        TextButton(onClick = { onResolve(conflict.field, true) }) { Text("Keep phone") }
                        TextButton(onClick = { onResolve(conflict.field, false) }) { Text("Keep server") }
                    }
                }
            }
        }
        if (card != null) {
            val statusConflict = conflicts.any { it.field == "status" || it.field == "deleted" }
            BoardStatusButton(card.status, enabled = !statusConflict) { onStatus(card, it) }
            if (card.nativeStatus.isNotBlank() && card.nativeStatus != card.status.wire) FieldText("Column", card.nativeStatus)
            card.owner?.let { FieldText("Owner", it) }
            card.next?.let { FieldText("Next", it) }
            if (card.waitingOn.isNotEmpty()) FieldText("Waiting on", card.waitingOn.joinToString(", "))
            card.updatedAt?.let { FieldText("Updated", it) }
            if (destinations.isNotEmpty()) {
                TextButton(onClick = { moving = true }) { Text("Move to a server board") }
            }
            HorizontalDivider()
            Text("Notes", color = Palette.Ink, fontWeight = FontWeight.Bold)
            card.notes.forEach { entry ->
                Column {
                    Text("${entry.by} · ${entry.at}", color = Palette.Muted, fontSize = 12.sp)
                    Text(entry.text, color = Palette.Ink)
                }
            }
            OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text("New note") })
            TextButton(enabled = note.isNotBlank(), onClick = {
                onNote(BoardNote(at = Instant.now().toString(), by = author, text = note))
                note = ""
            }) { Text("Add note") }
        }
    }
    if (moving) AlertDialog(
        onDismissRequest = { moving = false },
        title = { Text("Move card to a server board") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                destinations.forEach { source ->
                    TextButton(onClick = { moving = false; onMove(source.id) }) { Text(source.label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { moving = false }) { Text("Cancel") } },
    )
}

@Composable
private fun FieldText(label: String, value: String) {
    Column {
        Text(label, color = Palette.Muted, fontSize = 12.sp)
        Text(value, color = Palette.Ink)
    }
}

@Composable
private fun BoardStatusButton(status: BoardStatus, enabled: Boolean = true, set: (BoardStatus) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Column {
        TextButton(enabled = enabled, onClick = { choosing = true }) { Text("Status: ${status.label}") }
        DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
            BoardStatus.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) }, onClick = { choosing = false; set(choice) })
            }
        }
    }
}

@Composable
private fun BoardEditor(
    card: BoardCard,
    phone: Boolean,
    changedWhileEditing: Boolean,
    onDismiss: () -> Unit,
    onSave: (BoardCard) -> Unit,
) {
    var input by remember(card.id) { mutableStateOf(card) }
    var waiting by remember(card.id) { mutableStateOf(card.waitingOn.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (card.title.isBlank()) "New card" else "Edit card") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (changedWhileEditing) {
                    Text("This card updated while you were editing. Reopen it to see the latest values.",
                        color = Palette.OrangeInk)
                }
                BoardField("Title", input.title, Modifier.testTag("board-card-title")) { input = input.copy(title = it) }
                BoardStatusButton(input.status) { input = input.moveStatus(it, phone) }
                BoardField("Native status", input.nativeStatus) { input = input.copy(nativeStatus = it) }
                BoardField("Owner", input.owner.orEmpty()) { input = input.copy(owner = it.takeIf(String::isNotEmpty)) }
                BoardField("Next", input.next.orEmpty()) { input = input.copy(next = it.takeIf(String::isNotEmpty)) }
                BoardField("Waiting on (one card ID per line)", waiting) { waiting = it }
                BoardField("Updated at", input.updatedAt.orEmpty()) {
                    input = input.copy(updatedAt = it.takeIf(String::isNotEmpty))
                }
                input.notes.forEachIndexed { index, entry ->
                    HorizontalDivider()
                    Text("Note ${index + 1}")
                    fun update(note: BoardNote) {
                        input = input.copy(notes = input.notes.toMutableList().apply { this[index] = note })
                    }
                    BoardField("Author", entry.by) { update(entry.copy(by = it)) }
                    BoardField("Time", entry.at) { update(entry.copy(at = it)) }
                    BoardField("Text", entry.text) { update(entry.copy(text = it)) }
                    TextButton(onClick = {
                        input = input.copy(notes = input.notes.toMutableList().apply { removeAt(index) })
                    }) { Text("Remove note") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = input.title.isNotBlank() && !changedWhileEditing, onClick = {
                onSave(input.copy(waitingOn = waiting.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()))
            }) { Text("Save card") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BoardField(label: String, value: String, modifier: Modifier = Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, modifier = modifier.fillMaxWidth())
}
