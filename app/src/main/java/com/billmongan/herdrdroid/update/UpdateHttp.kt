package com.billmongan.herdrdroid.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The update check's only network use: one GET of GitHub's release JSON, and the APK download
 * after the user taps Update. Tests swap in a fake, so they never reach the network.
 */
interface UpdateHttp {
    /** The body of a GET, or an IOException. */
    fun getText(url: String, userAgent: String): String

    /** Downloads [url] into [dest], refusing more than [maxBytes]; stops when [cancelled] says so. */
    fun download(url: String, userAgent: String, dest: File, maxBytes: Long, progress: (Long) -> Unit, cancelled: () -> Boolean)
}

/**
 * OkHttp with nothing that identifies the phone: no cookies, no cache, no auth, and only a generic
 * User-Agent. HTTPS only, including every redirect (GitHub sends the APK from its asset host).
 */
class OkUpdateHttp(private val requireHttps: Boolean = true) : UpdateHttp {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .followSslRedirects(false)
        .cache(null)
        .build()

    private fun request(url: String, userAgent: String, accept: String): Request {
        if (requireHttps && !url.startsWith("https://")) throw IOException("Refused a non-HTTPS address.")
        return Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", accept).build()
    }

    override fun getText(url: String, userAgent: String): String =
        client.newCall(request(url, userAgent, "application/vnd.github+json")).execute().use { r ->
            if (requireHttps && !r.request.url.isHttps) throw IOException("Refused a non-HTTPS redirect.")
            if (!r.isSuccessful) throw IOException("GitHub answered ${r.code}.")
            r.body?.string() ?: throw IOException("GitHub sent an empty answer.")
        }

    override fun download(url: String, userAgent: String, dest: File, maxBytes: Long, progress: (Long) -> Unit, cancelled: () -> Boolean) {
        val call = client.newCall(request(url, userAgent, "application/octet-stream"))
        call.execute().use { r ->
            if (requireHttps && !r.request.url.isHttps) throw IOException("Refused a non-HTTPS redirect.")
            if (!r.isSuccessful) throw IOException("The download answered ${r.code}.")
            val body = r.body ?: throw IOException("The download was empty.")
            if (body.contentLength() > maxBytes) throw IOException("The download is larger than the release lists.")
            var total = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) { call.cancel(); return }
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("The download is larger than the release lists.")
                        out.write(buf, 0, n)
                        progress(total)
                    }
                }
            }
        }
    }
}
