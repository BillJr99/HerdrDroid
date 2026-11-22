package com.billmongan.herdrdroid.update

import org.junit.Rule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.IdleMainLooperRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 2.1.4: the daily check is on by default, and the switch and Not now are kept. */
@RunWith(AndroidJUnit4::class)
class AppUpdatePrefsTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun theDailyCheckIsOnByDefaultAndTheChoicesAreKept() {
        val p = AppUpdatePrefs(context)
        assertTrue(p.dailyCheck)
        assertEquals(0L, p.lastCheckAt)
        assertNull(p.skippedVersion)
        p.dailyCheck = false
        p.lastCheckAt = 42
        p.skippedVersion = "2.1.5"
        val again = AppUpdatePrefs(context)
        assertFalse(again.dailyCheck)
        assertEquals(42L, again.lastCheckAt)
        assertEquals("2.1.5", again.skippedVersion)
    }

    @Test fun theRealPlatformReportsThisBuildAndHashesCertificates() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AndroidUpdatePlatform.sha256Hex("abc".toByteArray()),
        )
        val platform = AndroidUpdatePlatform(context)
        assertEquals(com.billmongan.herdrdroid.BuildConfig.VERSION_NAME, platform.installedVersionName)
        assertEquals(com.billmongan.herdrdroid.BuildConfig.VERSION_CODE.toLong(), platform.installedVersionCode)
    }
}
