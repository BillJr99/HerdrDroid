package com.billmongan.herdrdroid.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.1.4 in-app updates: version order, the release JSON, the daily schedule and the APK checks. */
class UpdateCoreTest {
    private fun v(s: String) = AppVersion.parse(s)!!

    @Test fun versionsCompareBySemanticVersionNotText() {
        assertTrue(v("2.1.5") > v("2.1.4"))
        assertTrue(v("2.1.10") > v("2.1.9"))
        assertTrue(v("2.10.0") > v("2.9.9"))
        assertTrue(v("3.0.0") > v("2.99.99"))
        assertEquals(0, v("v2.1.4").compareTo(v("2.1.4")))
        assertTrue(v("2.0.0") > v("2.0.0-rc.8"))
        assertTrue(v("2.0.0-rc.10") > v("2.0.0-rc.8"))
        assertFalse(v("2.1.3") > v("2.1.4"))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("2.1"))
        assertNull(AppVersion.parse(""))
        assertEquals("2.1.4", v("v2.1.4").toString())
    }

    private fun release(
        tag: String = "v2.1.5",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = asset("2.1.5", 57_000_000) + "," + plainAsset(57_000_000),
    ) = """{"tag_name":"$tag","name":"HerdrDroid","draft":$draft,"prerelease":$prerelease,"assets":[$assets]}"""

    private fun asset(ver: String, size: Long, url: String = "https://github.com/BillJr99/HerdrDroid/releases/download/v$ver/HerdrDroid-$ver.apk") =
        """{"name":"HerdrDroid-$ver.apk","size":$size,"browser_download_url":"$url"}"""

    private fun plainAsset(size: Long) =
        """{"name":"HerdrDroid.apk","size":$size,"browser_download_url":"https://github.com/BillJr99/HerdrDroid/releases/download/v2.1.5/HerdrDroid.apk"}"""

    @Test fun readsTheTagAndTheVersionedApkAsset() {
        val r = parseRelease(release()) as ReleaseRead.Found
        assertEquals(v("2.1.5"), r.release.version)
        assertEquals("HerdrDroid-2.1.5.apk", r.release.apkName)
        assertEquals("https://github.com/BillJr99/HerdrDroid/releases/download/v2.1.5/HerdrDroid-2.1.5.apk", r.release.apkUrl)
        assertEquals(57_000_000L, r.release.apkSize)
    }

    @Test fun aMissingVersionedAssetOffersNothing() {
        assertTrue(parseRelease(release(assets = plainAsset(57_000_000))) is ReleaseRead.Skipped)
        assertTrue(parseRelease(release(assets = "")) is ReleaseRead.Skipped)
        assertTrue(parseRelease("""{"tag_name":"v2.1.5","draft":false,"prerelease":false}""") is ReleaseRead.Skipped)
        // An asset for another version doesn't count.
        assertTrue(parseRelease(release(assets = asset("2.1.4", 57_000_000))) is ReleaseRead.Skipped)
    }

    @Test fun draftsAndPrereleasesAreSkipped() {
        assertTrue(parseRelease(release(draft = true)) is ReleaseRead.Skipped)
        assertTrue(parseRelease(release(prerelease = true)) is ReleaseRead.Skipped)
        assertTrue(parseRelease(release(tag = "v2.2.0-rc.1")) is ReleaseRead.Skipped)
        assertTrue(parseRelease(release(tag = "nightly")) is ReleaseRead.Skipped)
    }

    @Test fun anApkFromAnywhereElseOrOfAnOddSizeIsSkipped() {
        assertTrue(parseRelease(release(assets = asset("2.1.5", 57_000_000, "https://example.invalid/HerdrDroid-2.1.5.apk"))) is ReleaseRead.Skipped)
        assertTrue(parseRelease(release(assets = asset("2.1.5", 57_000_000, "http://github.com/BillJr99/HerdrDroid/releases/download/v2.1.5/HerdrDroid-2.1.5.apk"))) is ReleaseRead.Skipped)
        assertTrue(parseRelease(release(assets = asset("2.1.5", 0))) is ReleaseRead.Skipped)
        assertTrue(parseRelease(release(assets = asset("2.1.5", MAX_APK_BYTES + 1))) is ReleaseRead.Skipped)
    }

    @Test fun theDailyCheckRunsAtMostOnceADayAndOnlyWithTheSwitchOn() {
        val day = UpdateSchedule.DAY_MS
        val t = 1_800_000_000_000L
        assertTrue(UpdateSchedule.dailyDue(true, t, 0, 0))
        assertFalse(UpdateSchedule.dailyDue(false, t, 0, 0))
        assertFalse(UpdateSchedule.dailyDue(true, t + day - 1, t, t))
        assertTrue(UpdateSchedule.dailyDue(true, t + day, t, t))
        assertFalse(UpdateSchedule.dailyDue(false, t + 10 * day, t, t))
        // A failed try waits an hour before the next, even though no check has succeeded today.
        assertFalse(UpdateSchedule.dailyDue(true, t + day + 60_000, t, t + day))
        assertTrue(UpdateSchedule.dailyDue(true, t + day + UpdateSchedule.RETRY_MS, t, t + day))
        // A clock set back doesn't block the check.
        assertTrue(UpdateSchedule.dailyDue(true, t - day, t, t))
    }

    @Test fun notNowSkipsThatVersionUntilANewerOne() {
        val installed = v("2.1.4")
        assertTrue(UpdateSchedule.shouldOffer(v("2.1.5"), installed, null, manual = false))
        assertFalse(UpdateSchedule.shouldOffer(v("2.1.5"), installed, "2.1.5", manual = false))
        assertTrue(UpdateSchedule.shouldOffer(v("2.2.0"), installed, "2.1.5", manual = false))
        // Check for updates now offers it anyway.
        assertTrue(UpdateSchedule.shouldOffer(v("2.1.5"), installed, "2.1.5", manual = true))
        assertFalse(UpdateSchedule.shouldOffer(v("2.1.4"), installed, null, manual = true))
        assertFalse(UpdateSchedule.shouldOffer(v("2.1.3"), installed, null, manual = true))
    }

    private val signer = RELEASE_SIGNER_SHA256
    private val good = ArchiveFacts(UPDATE_PACKAGE, 118, setOf(signer))

    private fun verify(a: ArchiveFacts?, fileSize: Long = 100, expected: Long = 100) =
        verifyApk(a, fileSize, expected, installedVersionCode = 117, installedSigners = setOf(signer))

    @Test fun aGoodApkPasses() {
        assertEquals(Verdict.Ok, verify(good))
        assertEquals(Verdict.Ok, verify(good.copy(signers = setOf(signer.uppercase()))))
    }

    @Test fun aWrongSignerIsRefused() {
        val other = "0".repeat(64)
        assertTrue(verify(good.copy(signers = setOf(other))) is Verdict.Refused)
        assertTrue(verify(good.copy(signers = setOf(signer, other))) is Verdict.Refused)
        assertTrue(verify(good.copy(signers = emptySet())) is Verdict.Refused)
        // An installed app whose signer can't be read refuses everything.
        assertTrue(verifyApk(good, 100, 100, 117, emptySet()) is Verdict.Refused)
    }

    @Test fun aLowerOrEqualVersionCodeIsRefused() {
        assertTrue(verify(good.copy(versionCode = 116)) is Verdict.Refused)
        assertTrue(verify(good.copy(versionCode = 117)) is Verdict.Refused)
    }

    @Test fun aWrongPackageIsRefused() {
        assertTrue(verify(good.copy(packageName = "com.example.other")) is Verdict.Refused)
    }

    @Test fun aSizeMismatchOrAnUnreadableFileIsRefused() {
        assertTrue(verify(good, fileSize = 99) is Verdict.Refused)
        assertTrue(verify(good, fileSize = 101) is Verdict.Refused)
        assertTrue(verify(null) is Verdict.Refused)
    }

    @Test fun theUserAgentIsGeneric() {
        assertEquals("HerdrDroid/2.1.4", updateUserAgent("2.1.4"))
    }
}
