package com.billmongan.herdrdroid.update

import android.content.pm.PackageInstaller
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The update dialog, when one shows. */
sealed interface UpdateDialogState {
    /** "HerdrDroid X.Y.Z is available", with Update and Not now. */
    data class Available(val release: ReleaseInfo) : UpdateDialogState
    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateDialogState
    /** "Install unknown apps" is off for HerdrDroid; the verified APK waits in the cache. */
    data class NeedsPermission(val release: ReleaseInfo) : UpdateDialogState
    data class Installing(val release: ReleaseInfo) : UpdateDialogState
    data class Failed(val message: String) : UpdateDialogState
}

/**
 * Checks for a newer release once per launch on an unmetered network or when requested.
 * An accepted offer downloads and verifies the APK before opening Android's installer.
 */
class Updater(
    private val prefs: UpdatePrefs,
    private val http: UpdateHttp,
    private val platform: UpdatePlatform,
    private val scope: CoroutineScope,
    cacheDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dir = File(cacheDir, "updates")
    private val userAgent = updateUserAgent(platform.installedVersionName)

    var checkOnLaunch by mutableStateOf(prefs.checkOnLaunch)
        private set
    var lastCheckAt by mutableStateOf(prefs.lastCheckAt)
        private set
    var checking by mutableStateOf(false)
        private set
    /** The result of Check for updates now, shown under the button. */
    var note by mutableStateOf<String?>(null)
        private set
    var dialog by mutableStateOf<UpdateDialogState?>(null)
        private set

    val installedVersion: String get() = platform.installedVersionName

    @Volatile private var cancelled = false
    private var job: Job? = null
    private var pending: File? = null
    private var launchChecked = false

    /** Kept so [release] can tell whether the installer's results still come here. */
    private val events: (Int, String?) -> Unit = ::installResult

    init {
        UpdateEvents.listener = events
        // An APK left from an install that replaced the app, or one that was interrupted.
        scope.launch(io) { runCatching { dir.listFiles()?.forEach { it.delete() } } }
    }

    fun changeCheckOnLaunch(on: Boolean) {
        checkOnLaunch = on
        prefs.checkOnLaunch = on
    }

    /** Recomposition, re-unlock and Activity recreation share the same launch check. */
    fun checkAtLaunch() {
        if (launchChecked) return
        launchChecked = true
        if (checking || !prefs.checkOnLaunch || !platform.hasNetwork() || platform.isMetered()) return
        scope.launch { check(manual = false) }
    }

    fun checkNow() {
        if (checking) return
        scope.launch { check(manual = true) }
    }

    /** One check. A failed launch check stays quiet; a failed manual one says why. */
    suspend fun check(manual: Boolean) {
        if (checking) return
        if (!platform.hasNetwork()) {
            if (manual) note = "No network connection. Try again when online."
            return
        }
        checking = true
        if (manual) note = null
        val now = platform.now()
        try {
            val read = withContext(io) { parseRelease(http.getText(LATEST_RELEASE_URL, userAgent)) }
            prefs.lastCheckAt = now
            lastCheckAt = now
            val installed = AppVersion.parse(platform.installedVersionName)
            when (read) {
                is ReleaseRead.Skipped -> if (manual) note = "No update to install. ${read.reason}"
                is ReleaseRead.Found -> {
                    val newer = installed != null && read.release.version > installed
                    if (UpdateSchedule.shouldOffer(read.release.version, installed, prefs.skippedVersion, manual) && !busy()) {
                        dialog = UpdateDialogState.Available(read.release)
                    }
                    if (manual) note = if (newer) "HerdrDroid ${read.release.version} is available."
                    else "HerdrDroid ${platform.installedVersionName} is up to date."
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (manual) note = "Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            checking = false
        }
    }

    private fun busy() = dialog.let { it is UpdateDialogState.Downloading || it is UpdateDialogState.NeedsPermission || it is UpdateDialogState.Installing }

    /** Not now: the launch check skips this version until a newer one is out. */
    fun notNow() {
        (dialog as? UpdateDialogState.Available)?.let { prefs.skippedVersion = it.release.version.toString() }
        dialog = null
    }

    fun update() {
        val release = (dialog as? UpdateDialogState.Available)?.release ?: return
        job = scope.launch { download(release) }
    }

    suspend fun download(release: ReleaseInfo) {
        if (!platform.hasNetwork()) {
            dialog = UpdateDialogState.Failed("No network connection. Try again when online.")
            return
        }
        cancelled = false
        val file = File(dir, release.apkName)
        dialog = UpdateDialogState.Downloading(release, 0, release.apkSize)
        try {
            withContext(io) {
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                var shown = -1L
                http.download(release.apkUrl, userAgent, file, release.apkSize, { n ->
                    // One step per percent, not one per buffer.
                    val step = n * 100 / release.apkSize
                    if (step != shown) { shown = step; dialog = UpdateDialogState.Downloading(release, n, release.apkSize) }
                }, { cancelled })
            }
            if (cancelled) { discard(file); dialog = null; return }
            pending = file
            if (!platform.canInstall()) {
                dialog = UpdateDialogState.NeedsPermission(release)
                return
            }
            install(release)
        } catch (e: CancellationException) {
            discard(file)
            dialog = null
            throw e
        } catch (e: Exception) {
            discard(file)
            dialog = if (cancelled) null else UpdateDialogState.Failed("The download failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Verifies the cached APK, then hands it to the installer. The file is deleted either way. */
    private suspend fun install(release: ReleaseInfo) {
        val file = pending ?: return
        pending = null
        try {
            val verdict = withContext(io) {
                verifyApk(platform.readArchive(file), file.length(), release.apkSize, platform.installedVersionCode, platform.installedSigners())
            }
            if (verdict is Verdict.Refused) {
                discard(file)
                dialog = UpdateDialogState.Failed(verdict.reason)
                return
            }
            dialog = UpdateDialogState.Installing(release)
            withContext(io) { platform.install(file) }
        } catch (e: CancellationException) {
            discard(file)
            throw e
        } catch (e: Exception) {
            discard(file)
            dialog = UpdateDialogState.Failed("Android's installer refused the update: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
        pending?.let { discard(it) }
        pending = null
        dialog = null
    }

    fun openInstallSettings() = runCatching { platform.openInstallPermissionSettings() }.onFailure {
        dialog = UpdateDialogState.Failed("Couldn't open Android's settings. Allow HerdrDroid under Install unknown apps, then try again.")
    }

    /** Back from Android's settings: continue the install once it's allowed. */
    fun onResume() {
        val waiting = dialog as? UpdateDialogState.NeedsPermission ?: return
        if (pending == null || !platform.canInstall()) return
        job = scope.launch { install(waiting.release) }
    }

    fun dismiss() {
        if (dialog is UpdateDialogState.Downloading || dialog is UpdateDialogState.NeedsPermission) return cancel()
        dialog = null
    }

    /** From Android's installer. A success replaces this app, so it rarely arrives. */
    fun installResult(status: Int, message: String?) {
        dialog = when (status) {
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> null
            else -> UpdateDialogState.Failed("The update wasn't installed" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."))
        }
    }

    private fun discard(file: File) { runCatching { file.delete() } }

    /** 2.2.8: the owner is gone; a later updater may already have taken the installer's results. */
    fun release() {
        job?.cancel()
        if (UpdateEvents.listener === events) UpdateEvents.listener = null
    }
}
