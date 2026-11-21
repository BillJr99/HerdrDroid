package com.billmongan.herdrdroid.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.billmongan.herdrdroid.BuildConfig
import java.io.File
import java.security.MessageDigest

/** What the updater needs from Android, behind an interface so the tests can fake it. */
interface UpdatePlatform {
    val installedVersionName: String
    val installedVersionCode: Long
    /** SHA-256 of each certificate the installed app is signed with, lowercase hex. */
    fun installedSigners(): Set<String>
    /** Package, version code and signers of an APK file, or null when Android can't read it. */
    fun readArchive(file: File): ArchiveFacts?
    fun isMetered(): Boolean
    /** Whether "Install unknown apps" is allowed for HerdrDroid. */
    fun canInstall(): Boolean
    fun openInstallPermissionSettings()
    /** Hands the APK to Android's installer, which asks the user to confirm. Deletes the file. */
    fun install(file: File)
    fun now(): Long = System.currentTimeMillis()
}

class AndroidUpdatePlatform(context: Context) : UpdatePlatform {
    private val context = context.applicationContext
    private val pm get() = context.packageManager

    override val installedVersionName: String = BuildConfig.VERSION_NAME
    override val installedVersionCode: Long = BuildConfig.VERSION_CODE.toLong()

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES

    override fun installedSigners(): Set<String> = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, signingFlags())
        }
        signersOf(info)
    }.getOrDefault(emptySet())

    override fun readArchive(file: File): ArchiveFacts? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.path, signingFlags())
        } ?: return null
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        ArchiveFacts(info.packageName, code, signersOf(info))
    }.getOrNull()

    override fun isMetered(): Boolean = runCatching {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(true)

    override fun canInstall(): Boolean = pm.canRequestPackageInstalls()

    override fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun install(file: File) {
        val installer = pm.packageInstaller
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(UPDATE_PACKAGE)
                setSize(file.length())
                // Never a silent install: Android always shows its own confirmation.
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val id = installer.createSession(params)
            try {
                installer.openSession(id).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite("HerdrDroid.apk", 0, file.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    val status = Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION)
                    // Mutable so the installer can add its status; the intent names our own receiver.
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
                }
            } catch (e: Exception) {
                runCatching { installer.abandonSession(id) }
                throw e
            }
        } finally {
            // The session holds its own copy; the cached APK goes either way.
            file.delete()
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun signersOf(info: PackageInfo): Set<String> {
            val signing: android.content.pm.SigningInfo? = if (Build.VERSION.SDK_INT >= 28) info.signingInfo else null
            val certs: Array<android.content.pm.Signature>? = signing?.apkContentsSigners ?: info.signatures
            return certs?.map { sha256Hex(it.toByteArray()) }?.toSet() ?: emptySet()
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Passes installer results from [UpdateInstallReceiver] to the updater. */
object UpdateEvents {
    @Volatile var listener: ((status: Int, message: String?) -> Unit)? = null
    fun post(status: Int, message: String?) { listener?.invoke(status, message) }
}

/**
 * Android's installer reports here. It first asks for the user's confirmation (we open its
 * screen), then reports success or failure. Not exported: only our own PendingIntent reaches it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            val opened = confirm != null && runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (!opened) UpdateEvents.post(PackageInstaller.STATUS_FAILURE, "Android's installer didn't open.")
            return
        }
        UpdateEvents.post(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    companion object {
        const val ACTION = "com.billmongan.herdrdroid.UPDATE_INSTALL_STATUS"
    }
}
