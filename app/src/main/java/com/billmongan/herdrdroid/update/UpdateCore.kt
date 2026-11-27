package com.billmongan.herdrdroid.update

import android.content.Context
import org.json.JSONObject

/**
 * 2.1.4: the in-app update check (Bill, 2026-11-21). The rules here are plain functions, so the
 * tests can check them without a network or a phone: comparing versions, reading GitHub's release
 * JSON, and the checks a downloaded APK must pass before it is
 * handed to Android's installer.
 */
const val UPDATE_PACKAGE = "com.billmongan.herdrdroid"

/** GitHub's newest published release. It never lists drafts or prereleases. */
const val LATEST_RELEASE_URL = "https://api.github.com/repos/BillJr99/HerdrDroid/releases/latest"

/** Only APKs from this repository's releases are downloaded. */
const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/BillJr99/HerdrDroid/releases/download/"

/** The release signer's certificate SHA-256, for tests. At run time the installed app's own signer is used. */
const val RELEASE_SIGNER_SHA256 = "ba497f283bc9b65cab39348459f9af16eccc86c7385d0b7db70bba2a368d6c6f"

/** A release APK is about 55 MB; anything far bigger is refused before it is downloaded. */
const val MAX_APK_BYTES = 300L * 1024 * 1024

/** The only header that says who is asking: "HerdrDroid/2.1.4". */
fun updateUserAgent(versionName: String) = "HerdrDroid/$versionName"

/** A version like 2.1.4 (or 2.0.0-rc.8 for an old test build). */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val pre: String? = null) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // 2.0.0-rc.8 comes before 2.0.0.
        return when {
            pre == other.pre -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> comparePre(pre, other.pre)
        }
    }

    override fun toString() = "$major.$minor.$patch" + (pre?.let { "-$it" } ?: "")

    companion object {
        private val pattern = Regex("""^v?(\d{1,6})\.(\d{1,6})\.(\d{1,6})(?:-([0-9A-Za-z.]+))?$""")

        fun parse(text: String?): AppVersion? {
            val m = pattern.matchEntire(text?.trim() ?: return null) ?: return null
            val (a, b, c, p) = m.destructured
            return AppVersion(a.toInt(), b.toInt(), c.toInt(), p.ifEmpty { null })
        }

        /** rc.8 before rc.10: numeric parts compare as numbers. */
        private fun comparePre(a: String, b: String): Int {
            val x = a.split('.')
            val y = b.split('.')
            for (i in 0 until minOf(x.size, y.size)) {
                val nx = x[i].toIntOrNull()
                val ny = y[i].toIntOrNull()
                val c = if (nx != null && ny != null) compareValues(nx, ny) else x[i].compareTo(y[i])
                if (c != 0) return c
            }
            return compareValues(x.size, y.size)
        }
    }
}

/** A release that has an APK to offer. */
data class ReleaseInfo(val version: AppVersion, val tag: String, val apkName: String, val apkUrl: String, val apkSize: Long)

sealed interface ReleaseRead {
    data class Found(val release: ReleaseInfo) : ReleaseRead
    /** Nothing to offer, and why (shown only after Check for updates now). */
    data class Skipped(val reason: String) : ReleaseRead
}

/**
 * Reads GitHub's release JSON: tag_name vX.Y.Z and the HerdrDroid-X.Y.Z.apk asset's
 * browser_download_url and size. A draft, a prerelease, a tag that isn't a version, or a release
 * without that APK offers nothing. Throws on JSON that can't be read at all.
 */
fun parseRelease(json: String): ReleaseRead {
    val o = JSONObject(json)
    if (o.optBoolean("draft", false)) return ReleaseRead.Skipped("The newest release is a draft.")
    if (o.optBoolean("prerelease", false)) return ReleaseRead.Skipped("The newest release is a prerelease.")
    val tag = o.optString("tag_name", "")
    val version = AppVersion.parse(tag)?.takeIf { it.pre == null }
        ?: return ReleaseRead.Skipped("The newest release has no version number.")
    val name = "HerdrDroid-$version.apk"
    val assets = o.optJSONArray("assets") ?: return ReleaseRead.Skipped("Release $version has no APK yet.")
    for (i in 0 until assets.length()) {
        val a = assets.optJSONObject(i) ?: continue
        if (a.optString("name") != name) continue
        val url = a.optString("browser_download_url", "")
        val size = a.optLong("size", 0L)
        if (!url.startsWith(RELEASE_DOWNLOAD_PREFIX) || !url.endsWith("/$name")) {
            return ReleaseRead.Skipped("Release $version's APK isn't on this repository's release page.")
        }
        if (size <= 0 || size > MAX_APK_BYTES) return ReleaseRead.Skipped("Release $version's APK has an unexpected size.")
        return ReleaseRead.Found(ReleaseInfo(version, tag, name, url, size))
    }
    return ReleaseRead.Skipped("Release $version has no $name yet.")
}

/** Rules for choosing which releases to offer. */
object UpdateSchedule {
    /** Offer a newer release, unless Not now skipped this exact version and the user didn't ask. */
    fun shouldOffer(release: AppVersion, installed: AppVersion?, skipped: String?, manual: Boolean): Boolean {
        if (installed == null || release <= installed) return false
        return manual || release.toString() != skipped
    }
}

/** What Android reads from a downloaded APK. */
data class ArchiveFacts(val packageName: String, val versionCode: Long, val signers: Set<String>)

sealed interface Verdict {
    data object Ok : Verdict
    data class Refused(val reason: String) : Verdict
}

/**
 * The checks before install: the file is the size the release lists, Android can read it, it is
 * this app, it is newer than the installed build, and it is signed by the installed app's own
 * signer (the same set of certificates).
 */
fun verifyApk(
    archive: ArchiveFacts?,
    fileSize: Long,
    expectedSize: Long,
    installedVersionCode: Long,
    installedSigners: Set<String>,
    expectedPackage: String = UPDATE_PACKAGE,
): Verdict {
    if (fileSize != expectedSize) {
        return Verdict.Refused("The download is $fileSize bytes, but the release lists $expectedSize. It wasn't installed.")
    }
    archive ?: return Verdict.Refused("Android couldn't read the downloaded APK. It wasn't installed.")
    if (archive.packageName != expectedPackage) {
        return Verdict.Refused("The downloaded APK is ${archive.packageName}, not HerdrDroid. It wasn't installed.")
    }
    if (archive.versionCode <= installedVersionCode) {
        return Verdict.Refused("The downloaded APK (build ${archive.versionCode}) isn't newer than this one (build $installedVersionCode). It wasn't installed.")
    }
    if (installedSigners.isEmpty() || archive.signers.isEmpty() ||
        archive.signers.map { it.lowercase() }.toSet() != installedSigners.map { it.lowercase() }.toSet()
    ) {
        return Verdict.Refused("The downloaded APK isn't signed with this app's key. It wasn't installed.")
    }
    return Verdict.Ok
}

/** What the update check keeps between runs. */
interface UpdatePrefs {
    var checkOnLaunch: Boolean
    /** The last check that got an answer from GitHub (ms), shown in Settings. */
    var lastCheckAt: Long
    /** A version Not now skipped; launch checks don't offer it again. */
    var skippedVersion: String?
}

class AppUpdatePrefs(context: Context) : UpdatePrefs {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    override var checkOnLaunch: Boolean
        get() = prefs.getBoolean("dailyCheck", true)
        set(v) = prefs.edit().putBoolean("dailyCheck", v).apply()
    override var lastCheckAt: Long
        get() = prefs.getLong("lastCheckAt", 0L)
        set(v) = prefs.edit().putLong("lastCheckAt", v).apply()
    override var skippedVersion: String?
        get() = prefs.getString("skippedVersion", null)
        set(v) = prefs.edit().putString("skippedVersion", v).apply()
}
