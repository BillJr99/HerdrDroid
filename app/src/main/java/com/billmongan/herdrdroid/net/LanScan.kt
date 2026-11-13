package com.billmongan.herdrdroid.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** A machine on the local network that answers SSH: a candidate HerdrDroid server. */
data class LanHost(val ip: String, val name: String?, val ssh: String?)

/**
 * Every address in the phone's Wi-Fi subnet, at most a /24 around the phone (254 addresses), the
 * phone's own and the network and broadcast addresses left out.
 */
fun subnetHosts(phoneIp: String, prefix: Int): List<String> {
    val parts = phoneIp.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return emptyList()
    val bits = prefix.coerceIn(24, 30)
    val ip = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
    val net = ip and mask
    val size = 1 shl (32 - bits)
    return (1 until size - 1).map { net + it }.filter { it != ip }.map { a ->
        "${(a ushr 24) and 255}.${(a ushr 16) and 255}.${(a ushr 8) and 255}.${a and 255}"
    }
}

/** "SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.5" reads as "OpenSSH 9.6p1, Ubuntu"; null when not SSH. */
fun sshVersion(banner: String?): String? {
    val b = banner?.trim()?.takeIf { it.startsWith("SSH-") } ?: return null
    val soft = b.split(" ", limit = 2)
    val product = soft[0].split("-", limit = 3).getOrNull(2)?.replace('_', ' ') ?: return "SSH"
    val os = soft.getOrNull(1)?.substringBefore('-')?.takeIf { it.isNotBlank() }
    return listOfNotNull(product, os).joinToString(", ")
}

object LanScan {
    /** The phone's IPv4 address and prefix on Wi-Fi (or Ethernet); null on cellular or offline. */
    fun localNetwork(context: Context): Pair<String, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        val la = cm.getLinkProperties(net)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return null
        return la.address.hostAddress!! to la.prefixLength
    }

    /**
     * Looks for machines that answer SSH: names from mDNS (`_ssh._tcp`) and a quick check of port
     * 22 across the subnet, 48 at a time with short timeouts. Nothing signs in anywhere; only the
     * SSH version line each machine sends first is read. [onProgress] gets (checked, total).
     */
    suspend fun scan(context: Context, onProgress: (Int, Int) -> Unit, onFound: (LanHost) -> Unit) {
        val (ip, prefix) = localNetwork(context) ?: return
        val names = mutableMapOf<String, String>()
        val nsd = runCatching { mdnsNames(context) }.getOrDefault(emptyMap())
        names.putAll(nsd)
        val hosts = subnetHosts(ip, prefix)
        var done = 0
        val gate = Semaphore(48)
        coroutineScope {
            hosts.map { h ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val banner = probe(h)
                        synchronized(this@LanScan) { done++; onProgress(done, hosts.size) }
                        if (banner != null) {
                            val name = names[h] ?: withTimeoutOrNull(1_500) { reverseName(h) }
                            onFound(LanHost(h, name, sshVersion(banner) ?: "SSH"))
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** Connects to port 22 and reads the first line the server sends; null when nothing answers. */
    private fun probe(host: String): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, 22), 350)
            s.soTimeout = 900
            val buf = ByteArray(255)
            val n = s.getInputStream().read(buf)
            if (n <= 0) "" else String(buf, 0, n).lines().first()
        }
    }.getOrNull()

    private suspend fun reverseName(ip: String): String? = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()?.takeIf { it != ip }
    }

    /** mDNS names of machines advertising `_ssh._tcp`, by IPv4 address, gathered for about 3 s. */
    @Suppress("DEPRECATION")
    private suspend fun mdnsNames(context: Context): Map<String, String> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyMap()
        val out = java.util.concurrent.ConcurrentHashMap<String, String>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val a = si.host as? Inet4Address ?: return
                            out[a.hostAddress!!] = si.serviceName
                        }
                    })
                }
            }
        }
        if (runCatching { nsd.discoverServices("_ssh._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }.isFailure) return emptyMap()
        delay(3_000)
        runCatching { nsd.stopServiceDiscovery(listener) }
        return out
    }
}
