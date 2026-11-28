package com.billmongan.herdrdroid.board

import com.billmongan.herdrdroid.store.Server
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardSyncTargets2211Test {
    private fun server(id: String, configured: Boolean) = Server(
        id = id, name = id, host = "example.invalid", port = 22, user = "example",
        boardAgentPane = "selected-pane".takeIf { configured },
    )

    @Test fun onlyRequestedAndConfiguredBoardsGetOneRoundEach() {
        val servers = listOf(server("a", true), server("b", true), server("disabled", false))
        assertEquals(listOf("b", "a"), boardSyncTargets(listOf("b", "a", "disabled", "missing", "a"), servers))
        assertEquals(listOf("a"), boardSyncTargets(listOf("a"), servers))
        assertEquals(emptyList<String>(), boardSyncTargets(listOf("disabled"), servers))
    }
}
