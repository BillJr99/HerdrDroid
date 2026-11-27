package com.billmongan.herdrdroid.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * 2.1.4 in-app updates, end to end with a fake network and a fake Android: when it checks, what
 * it offers, Not now, the download and every check before the installer.
 */
class UpdaterTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemPrefs : UpdatePrefs {
        override var checkOnLaunch = true
        override var lastCheckAt = 0L
        override var skippedVersion: String? = null
    }

    private class FakeHttp : UpdateHttp {
        val gets = mutableListOf<Pair<String, String>>()
        val downloads = mutableListOf<String>()
        var body: String = ""
        var fail: IOException? = null
        var apk = ByteArray(1000) { 7 }
        var onDownload: (() -> Unit)? = null

        override fun getText(url: String, userAgent: String): String {
            gets += url to userAgent
            fail?.let { throw it }
            return body
        }

        override fun download(url: String, userAgent: String, dest: File, maxBytes: Long, progress: (Long) -> Unit, cancelled: () -> Boolean) {
            downloads += url
            onDownload?.invoke()
            if (cancelled()) return
            dest.writeBytes(apk)
            progress(apk.size.toLong())
        }
    }

    private class FakePlatform : UpdatePlatform {
        override val installedVersionName = "2.1.4"
        override val installedVersionCode = 117L
        var signers = setOf(RELEASE_SIGNER_SHA256)
        var archive: ArchiveFacts? = ArchiveFacts(UPDATE_PACKAGE, 118, setOf(RELEASE_SIGNER_SHA256))
        var online = true
        var allowed = true
        var clock = 1_800_000_000_000L
        val installed = mutableListOf<String>()
        var settingsOpened = 0
        override fun installedSigners() = signers
        override fun readArchive(file: File) = archive
        override fun hasNetwork() = online
        override fun canInstall() = allowed
        override fun openInstallPermissionSettings() { settingsOpened++ }
        override fun install(file: File) { installed += file.name; file.delete() }
        override fun now() = clock
    }

    private val prefs = MemPrefs()
    private val http = FakeHttp()
    private val platform = FakePlatform()
    private val cache by lazy { tmp.newFolder("cache") }
    /** Cancelled after each test so no download or check outlives it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val updater by lazy {
        Updater(prefs, http, platform, scope, cache, io = Dispatchers.Unconfined)
    }

    @After fun cancelScope() = scope.cancel()

    private fun releaseJson(ver: String = "2.1.5", size: Long = 1000) =
        """{"tag_name":"v$ver","draft":false,"prerelease":false,"assets":[
           {"name":"HerdrDroid-$ver.apk","size":$size,"browser_download_url":"https://github.com/BillJr99/HerdrDroid/releases/download/v$ver/HerdrDroid-$ver.apk"},
           {"name":"HerdrDroid.apk","size":$size,"browser_download_url":"https://github.com/BillJr99/HerdrDroid/releases/download/v$ver/HerdrDroid.apk"}]}"""

    private fun cachedApks() = File(cache, "updates").listFiles().orEmpty().toList()

    @Test fun noNetworkCallWhenTheSwitchIsOffAndTheButtonIsNotPressed() {
        prefs.checkOnLaunch = false
        http.body = releaseJson()
        updater.checkAtLaunch()
        updater.onResume()
        updater.checkAtLaunch()
        platform.clock += 86_400_000L
        updater.checkAtLaunch()
        assertTrue(http.gets.isEmpty())
        assertTrue(http.downloads.isEmpty())
        assertNull(updater.dialog)
    }

    @Test fun theLaunchCheckAsksGitHubOnceAcrossResumes() {
        http.body = releaseJson()
        updater.checkAtLaunch()
        assertEquals(listOf(LATEST_RELEASE_URL to "HerdrDroid/2.1.4"), http.gets)
        assertEquals(platform.clock, updater.lastCheckAt)
        updater.notNow()
        platform.clock += 86_400_000L
        updater.onResume()
        updater.checkAtLaunch()
        assertEquals(1, http.gets.size)
    }

    @Test fun anOfflineLaunchAndManualCheckMakeNoRequest() {
        platform.online = false
        updater.checkAtLaunch()
        assertNull(updater.note)
        updater.checkNow()
        assertEquals("No network connection. Try again when online.", updater.note)
        assertTrue(http.gets.isEmpty())
        platform.online = true
        http.body = releaseJson()
        updater.onResume()
        updater.checkAtLaunch()
        assertTrue(http.gets.isEmpty())
        updater.checkNow()
        assertEquals(1, http.gets.size)
        assertTrue(updater.dialog is UpdateDialogState.Available)
    }

    @Test fun aFailedLaunchCheckIsSilentAndAFailedButtonSaysWhy() = runBlocking {
        http.fail = IOException("no route")
        updater.checkAtLaunch()
        assertNull(updater.note)
        assertNull(updater.dialog)
        assertEquals(0L, updater.lastCheckAt)
        updater.onResume()
        updater.checkAtLaunch()
        assertEquals(1, http.gets.size)
        updater.check(manual = true)
        assertTrue(updater.note!!.startsWith("Couldn't check for updates"))
        assertNull(updater.dialog)
    }

    @Test fun aNewerReleaseIsOfferedAndAnOlderOrEqualOneIsNot() = runBlocking {
        http.body = releaseJson("2.1.4")
        updater.check(manual = true)
        assertNull(updater.dialog)
        assertEquals("HerdrDroid 2.1.4 is up to date.", updater.note)
        http.body = releaseJson("2.1.5")
        updater.check(manual = true)
        assertEquals("2.1.5", (updater.dialog as UpdateDialogState.Available).release.version.toString())
    }

    @Test fun notNowSkipsThatVersionUntilTheNextOne() = runBlocking {
        http.body = releaseJson("2.1.5")
        updater.check(manual = false)
        updater.notNow()
        assertEquals("2.1.5", prefs.skippedVersion)
        assertNull(updater.dialog)
        updater.check(manual = false)
        assertNull(updater.dialog)
        // The button still offers it.
        updater.check(manual = true)
        assertTrue(updater.dialog is UpdateDialogState.Available)
        updater.dismiss()
        http.body = releaseJson("2.2.0")
        updater.check(manual = false)
        assertEquals("2.2.0", (updater.dialog as UpdateDialogState.Available).release.version.toString())
    }

    @Test fun aDraftOrAMissingApkOffersNothing() = runBlocking {
        http.body = releaseJson().replace("\"draft\":false", "\"draft\":true")
        updater.check(manual = true)
        assertNull(updater.dialog)
        http.body = """{"tag_name":"v2.1.5","draft":false,"prerelease":false,"assets":[]}"""
        updater.check(manual = true)
        assertNull(updater.dialog)
        assertTrue(updater.note!!.startsWith("No update to install."))
        assertTrue(http.downloads.isEmpty())
    }

    private suspend fun offerAndUpdate() {
        http.body = releaseJson()
        updater.check(manual = true)
        assertTrue(updater.dialog is UpdateDialogState.Available)
        updater.update()
    }

    @Test fun aVerifiedApkGoesToTheInstallerAndLeavesNoCachedFile() = runBlocking {
        offerAndUpdate()
        assertEquals(listOf("https://github.com/BillJr99/HerdrDroid/releases/download/v2.1.5/HerdrDroid-2.1.5.apk"), http.downloads)
        assertEquals(listOf("HerdrDroid-2.1.5.apk"), platform.installed)
        assertTrue(updater.dialog is UpdateDialogState.Installing)
        assertTrue(cachedApks().isEmpty())
    }

    private fun assertRefused() {
        assertTrue(platform.installed.isEmpty())
        assertTrue(updater.dialog is UpdateDialogState.Failed)
        assertTrue(cachedApks().isEmpty())
    }

    @Test fun aWrongSignerIsNotInstalled() = runBlocking {
        platform.archive = platform.archive!!.copy(signers = setOf("ab".repeat(32)))
        offerAndUpdate()
        assertRefused()
    }

    @Test fun theInstalledAppsOwnSignerIsWhatCounts() = runBlocking {
        // The APK carries the release signer, but this build is signed with another key.
        platform.signers = setOf("cd".repeat(32))
        offerAndUpdate()
        assertRefused()
    }

    @Test fun aLowerVersionCodeIsNotInstalled() = runBlocking {
        platform.archive = platform.archive!!.copy(versionCode = 116)
        offerAndUpdate()
        assertRefused()
    }

    @Test fun aWrongPackageIsNotInstalled() = runBlocking {
        platform.archive = platform.archive!!.copy(packageName = "com.example.other")
        offerAndUpdate()
        assertRefused()
    }

    @Test fun aSizeMismatchIsNotInstalled() = runBlocking {
        http.apk = ByteArray(999)
        offerAndUpdate()
        assertRefused()
    }

    @Test fun aFailedDownloadShowsWhyAndLeavesNoFile() = runBlocking {
        http.onDownload = { throw IOException("reset") }
        offerAndUpdate()
        assertRefused()
    }

    @Test fun cancelStopsTheDownloadAndDiscardsIt() = runBlocking {
        http.onDownload = { updater.cancel() }
        offerAndUpdate()
        assertNull(updater.dialog)
        assertTrue(platform.installed.isEmpty())
        assertTrue(cachedApks().isEmpty())
    }

    @Test fun withoutInstallPermissionItSendsTheUserToSettingsThenContinues() = runBlocking {
        platform.allowed = false
        offerAndUpdate()
        assertTrue(updater.dialog is UpdateDialogState.NeedsPermission)
        updater.openInstallSettings()
        assertEquals(1, platform.settingsOpened)
        // Back without allowing it: still waiting.
        updater.onResume()
        assertTrue(updater.dialog is UpdateDialogState.NeedsPermission)
        platform.allowed = true
        updater.onResume()
        assertEquals(listOf("HerdrDroid-2.1.5.apk"), platform.installed)
        assertTrue(cachedApks().isEmpty())
    }

    @Test fun cancellingAtThePermissionStepDiscardsTheApk() = runBlocking {
        platform.allowed = false
        offerAndUpdate()
        assertEquals(1, cachedApks().size)
        updater.dismiss()
        assertNull(updater.dialog)
        assertTrue(cachedApks().isEmpty())
    }

    @Test fun installerResultsCloseOrExplain() {
        updater.installResult(android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertNull(updater.dialog)
        updater.installResult(android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT, "conflict")
        assertEquals("The update wasn't installed: conflict", (updater.dialog as UpdateDialogState.Failed).message)
    }

    @Test fun aLeftoverApkIsClearedWhenTheAppStarts() {
        File(cache, "updates").apply { mkdirs() }.resolve("HerdrDroid-2.1.5.apk").writeBytes(ByteArray(10))
        updater.checkOnLaunch
        assertTrue(cachedApks().isEmpty())
    }
}
