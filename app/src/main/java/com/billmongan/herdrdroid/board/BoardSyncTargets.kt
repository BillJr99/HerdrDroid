package com.billmongan.herdrdroid.board

import com.billmongan.herdrdroid.store.Server

internal fun boardSyncTargets(requested: List<String>, servers: List<Server>): List<String> {
    val configured = servers.filter { it.boardAgentPane != null }.mapTo(hashSetOf()) { it.id }
    return requested.distinct().filter { it in configured }
}
