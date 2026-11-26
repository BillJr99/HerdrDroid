package com.billmongan.herdrdroid

import com.billmongan.herdrdroid.herdr.SendNowRoute
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.backgroundStep
import com.billmongan.herdrdroid.herdr.sendNowRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PiBusyActions228Test {
    @Test fun busyPiKeepsSendQueuedAndOffersNoTaskBackgroundKey() {
        for (capture in listOf("after-enter", "after-second-enter")) {
            val viewport = checkNotNull(
                javaClass.getResource("/screens/pi/captured-1.6.0-busy-steering/$capture.txt")
            ).readText()
            assertEquals(SendNowRoute.WAIT_FOR_IDLE, sendNowRoute("pi", Status.WORKING, viewport))
            assertNull(backgroundStep("pi", viewport))
        }
    }
}
