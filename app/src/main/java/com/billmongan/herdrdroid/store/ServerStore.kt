package com.billmongan.herdrdroid.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.billmongan.herdrdroid.herdr.QuotaItem
import com.billmongan.herdrdroid.herdr.QuotaReading
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One herdr server the user has added. Nothing here has a built-in default. */
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val herdrPath: String,
    /** "SHA256:..." once the user has confirmed the host key; null before the first connection. */
    val fingerprint: String? = null,
    /** The password, encrypted with a key held in the Android Keystore; null if not saved. */
    val passwordCipher: String? = null,
    val biometric: Boolean = false,
    /** Sign in with a key first; when off, the key is never offered to this server. */
    val useKey: Boolean = false,
    /** "keystore" for a key made on this phone, "imported" for a key file, or null for none. */
    val keyKind: String? = null,
    /** The imported key file and its passphrase, encrypted the same way as the password. */
    val keyCipher: String? = null,
    val keyPassCipher: String? = null,
    /** Text size for the agent screen, set by pinching. */
    val textSize: Float = 12.5f,
    /**
     * "wss://..." when SSH goes over a WebSocket instead of a TCP connection to [host]:[port].
     * Such a server signs in with its key only and is never held open in the background.
     */
    val wsUrl: String? = null,
    /** "tui" (herdr), "core" (herdr-core), or null to use whichever is running. */
    val backend: String? = null,
) {
    val overWebSocket: Boolean get() = !wsUrl.isNullOrBlank()
    val label: String get() = name.ifBlank { "$user@$host" }
    val keystoreAlias: String get() = "herdrdroid_ssh_$id"
    val hasKey: Boolean get() = keyKind == "keystore" || (keyKind == "imported" && keyCipher != null)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port)
        .put("user", user).put("herdrPath", herdrPath)
        .put("fingerprint", fingerprint ?: JSONObject.NULL)
        .put("passwordCipher", passwordCipher ?: JSONObject.NULL)
        .put("biometric", biometric)
        .put("useKey", useKey)
        .put("keyKind", keyKind ?: JSONObject.NULL)
        .put("keyCipher", keyCipher ?: JSONObject.NULL)
        .put("keyPassCipher", keyPassCipher ?: JSONObject.NULL)
        .put("textSize", textSize.toDouble())
        .put("wsUrl", wsUrl ?: JSONObject.NULL)
        .put("backend", backend ?: JSONObject.NULL)

    companion object {
        private fun JSONObject.str(k: String) = optString(k).takeIf { !isNull(k) && it.isNotBlank() }

        fun fromJson(o: JSONObject) = Server(
            id = o.getString("id"),
            name = o.optString("name"),
            host = o.getString("host"),
            port = o.getInt("port"),
            user = o.getString("user"),
            herdrPath = o.optString("herdrPath", "herdr"),
            fingerprint = o.str("fingerprint"),
            passwordCipher = o.str("passwordCipher"),
            biometric = o.optBoolean("biometric", false),
            useKey = o.optBoolean("useKey", false),
            keyKind = o.str("keyKind"),
            keyCipher = o.str("keyCipher"),
            keyPassCipher = o.str("keyPassCipher"),
            textSize = o.optDouble("textSize", 12.5).toFloat(),
            wsUrl = o.str("wsUrl"),
            backend = o.str("backend"),
        )
    }
}

class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)

    fun all(): List<Server> {
        val raw = prefs.getString("list", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { Server.fromJson(arr.getJSONObject(it)) }
    }

    fun get(id: String): Server? = all().firstOrNull { it.id == id }

    fun save(server: Server) {
        val list = all().filterNot { it.id == server.id } + server
        write(list)
    }

    fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(list: List<Server>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}

/** App-wide choices, and the last usage reading for each agent on each server. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var theme: String
        get() = prefs.getString("theme", "SYSTEM") ?: "SYSTEM"
        set(v) = prefs.edit().putString("theme", v).apply()

    var readingFont: String
        get() = prefs.getString("readingFont", "SANS") ?: "SANS"
        set(v) = prefs.edit().putString("readingFont", v).apply()

    var showKeys: Boolean
        get() = prefs.getBoolean("showKeys", false)
        set(v) = prefs.edit().putBoolean("showKeys", v).apply()

    var showQuotas: Boolean
        get() = prefs.getBoolean("showQuotas", true)
        set(v) = prefs.edit().putBoolean("showQuotas", v).apply()

    /** Alerts when an agent needs you or finishes, with a background "Watching" service. */
    var watching: Boolean
        get() = prefs.getBoolean("watching", false)
        set(v) = prefs.edit().putBoolean("watching", v).apply()

    /** Also watch the other saved servers that can sign in without a prompt. */
    var watchOthers: Boolean
        get() = prefs.getBoolean("watchOthers", true)
        set(v) = prefs.edit().putBoolean("watchOthers", v).apply()

    /** Models seen in each agent's panes, newest first: {"claude": ["claude-opus-5-5", ...]}. */
    var seenModels: String
        get() = prefs.getString("seenModels", "{}") ?: "{}"
        set(v) = prefs.edit().putString("seenModels", v).apply()

    /** The last agent opened, "serverId|paneId", which the app opens again at startup. */
    var lastAgent: String
        get() = prefs.getString("lastAgent", "") ?: ""
        set(v) = prefs.edit().putString("lastAgent", v).apply()

    /** Pinned sessions, one "serverId|tab label" per line, kept at the top of their group. */
    var pinnedSessions: String
        get() = prefs.getString("pinnedSessions", "") ?: ""
        set(v) = prefs.edit().putString("pinnedSessions", v).apply()

    /** When each pane turned done and when it was last open in the app: {"done": {...}, "seen": {...}}. */
    var seenPanes: String
        get() = prefs.getString("seenPanes", "{}") ?: "{}"
        set(v) = prefs.edit().putString("seenPanes", v).apply()

    /** Alerts when a usage limit passes 80% or 95%, and when it resets. */
    var alertUsage: Boolean
        get() = prefs.getBoolean("alertUsage", true)
        set(v) = prefs.edit().putBoolean("alertUsage", v).apply()

    /** "Done" alerts too, not only "needs you". */
    var alertDone: Boolean
        get() = prefs.getBoolean("alertDone", true)
        set(v) = prefs.edit().putBoolean("alertDone", v).apply()


    /**
     * Ask for the fingerprint (or screen lock) when the app opens. Unset at first: then it is on
     * when any server used the old per-server fingerprint setting.
     */
    var appLock: Boolean?
        get() = if (prefs.contains("appLock")) prefs.getBoolean("appLock", false) else null
        set(v) { if (v == null) prefs.edit().remove("appLock").apply() else prefs.edit().putBoolean("appLock", v).apply() }

    var lightPalette: String
        get() = prefs.getString("lightPalette", "STANDARD") ?: "STANDARD"
        set(v) = prefs.edit().putString("lightPalette", v).apply()

    /** Saved prompts for the message box, as a JSON array of {name, text}. */
    var snippets: String
        get() = prefs.getString("snippets", "[]") ?: "[]"
        set(v) = prefs.edit().putString("snippets", v).apply()

    /** Usage readings over time, per server and agent, as JSON (kept for the charts). */
    var quotaHistory: String
        get() = prefs.getString("quotaHistory", "{}") ?: "{}"
        set(v) = prefs.edit().putString("quotaHistory", v).apply()

    /** What the home-screen widget shows, written whenever the app reads its agents. */
    var widgetState: String
        get() = prefs.getString("widgetState", "{}") ?: "{}"
        set(v) = prefs.edit().putString("widgetState", v).apply()

    var darkPalette: String
        get() = prefs.getString("darkPalette", "STANDARD") ?: "STANDARD"
        set(v) = prefs.edit().putString("darkPalette", v).apply()

    fun quotas(serverId: String): Map<String, QuotaReading> {
        val raw = prefs.getString("quota_$serverId", null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { agent ->
                val r = o.getJSONObject(agent)
                val items = r.getJSONArray("items")
                QuotaReading(
                    at = r.getLong("at"),
                    items = (0 until items.length()).map { i ->
                        val it = items.getJSONObject(i)
                        QuotaItem(it.getString("label"), it.getDouble("used"), it.optString("reset").ifBlank { null })
                    },
                )
            }
        }.getOrDefault(emptyMap())
    }

    fun saveQuotas(serverId: String, readings: Map<String, QuotaReading>) {
        val o = JSONObject()
        readings.forEach { (agent, r) ->
            val items = JSONArray()
            r.items.forEach {
                items.put(JSONObject().put("label", it.label).put("used", it.usedPercent).put("reset", it.resets ?: ""))
            }
            o.put(agent, JSONObject().put("at", r.at).put("items", items))
        }
        prefs.edit().putString("quota_$serverId", o.toString()).apply()
    }

    fun forgetQuotas(serverId: String) = prefs.edit().remove("quota_$serverId").apply()
}

/** Encrypts saved passwords and key files with an AES key that never leaves the Android Keystore. */
object PasswordVault {
    private const val ALIAS = "herdrdroid_passwords"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(password: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }
}

/**
 * SSH keys made on this phone. Each is an ECDSA P-256 key pair inside the Android Keystore, so the
 * private key can sign but can never be read or copied out. (The Keystore has no Ed25519 before
 * Android 13, so ECDSA works on every phone the app supports.)
 */
object SshKeyVault {
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(alias: String): Boolean = runCatching { ks().containsAlias(alias) }.getOrDefault(false)

    fun generate(alias: String) {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        gen.generateKeyPair()
    }

    fun publicKey(alias: String): PublicKey? = runCatching { ks().getCertificate(alias)?.publicKey }.getOrNull()

    fun provider(alias: String): KeyProvider? {
        val store = ks()
        val priv = store.getKey(alias, null) as? PrivateKey ?: return null
        val pub = store.getCertificate(alias)?.publicKey ?: return null
        return object : KeyProvider {
            override fun getPrivate(): PrivateKey = priv
            override fun getPublic(): PublicKey = pub
            override fun getType(): KeyType = KeyType.ECDSA256
        }
    }

    fun delete(alias: String) {
        runCatching { ks().deleteEntry(alias) }
    }
}
