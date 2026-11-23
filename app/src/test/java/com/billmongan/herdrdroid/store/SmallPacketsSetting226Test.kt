package com.billmongan.herdrdroid.store

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 2.2.6: the per-server "Small packets" setting is saved, bounded and ignored over a WebSocket. */
class SmallPacketsSetting226Test {
    private val base = Server(id = "s1", name = "Fixture", host = "fixture.invalid", port = 22, user = "u", herdrPath = "herdr")

    @Test fun offWith1200ByDefault() {
        assertFalse(base.smallPackets)
        assertEquals(1200, base.mss)
        assertNull(base.smallPacketsMss)
    }

    @Test fun roundTripsThroughJson() {
        val on = base.copy(smallPackets = true, mss = 1300)
        val back = Server.fromJson(JSONObject(on.toJson().toString()))
        assertEquals(on, back)
        assertEquals(1300, back.smallPacketsMss)
    }

    @Test fun aServerSavedBefore226ReadsAsOff() {
        val old = base.toJson().apply { remove("smallPackets"); remove("mss") }
        val back = Server.fromJson(old)
        assertFalse(back.smallPackets)
        assertEquals(1200, back.mss)
    }

    @Test fun anOutOfRangeValueIsClampedOnRead() {
        assertEquals(536, Server.fromJson(base.toJson().put("mss", 40)).mss)
        assertEquals(1460, Server.fromJson(base.toJson().put("mss", 65000)).mss)
        assertEquals(1460, base.copy(smallPackets = true, mss = 9000).smallPacketsMss)
    }

    @Test fun aWebSocketServerNeverAsksForIt() {
        val ws = base.copy(wsUrl = "wss://bridge.invalid/ssh", smallPackets = true)
        assertNull(ws.smallPacketsMss)
    }
}
