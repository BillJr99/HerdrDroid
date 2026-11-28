package com.billmongan.herdrdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.billmongan.herdrdroid.AppViewModel
import kotlinx.coroutines.delay

@Composable
internal fun ConnectedBoardScreen(vm: AppViewModel) {
    val store = vm.boardStore
    val state by store.state.collectAsState()
    val sources = vm.servers.filter { it.boardAgentPane != null }.map {
        BoardSource(it.id, state.servers[it.id]?.name ?: it.label, vm.boardAgentAvailability(it.id))
    }
    for (source in sources) {
        key(source.id) {
            LaunchedEffect(source.id) {
                while (true) {
                    vm.serviceBoard(source.id)
                    delay(2_500)
                }
            }
        }
    }
    BoardScreen(
        state = state,
        servers = sources,
        onMenu = { vm.drawerOpen = true },
        onBack = vm::leaveBoard,
        onRefresh = vm::refreshBoards,
        onSync = vm::syncBoards,
        onRetry = { store.retry(it) },
        onSave = { server, card, target -> if (!store.saveCard(server, card, target)) vm.boardEditFailed() },
        onResolveDuplicate = { server, phone, remote ->
            if (!store.resolveDuplicate(server, phone, remote)) vm.boardEditFailed()
        },
        onAddNote = { server, card, note -> if (!store.addNote(server, card, note)) vm.boardEditFailed() },
        onResolve = { server, card, field, keepPhone -> if (!store.resolve(server, card, field, keepPhone)) vm.boardEditFailed() },
        handleBack = !vm.drawerOpen,
    )
}
