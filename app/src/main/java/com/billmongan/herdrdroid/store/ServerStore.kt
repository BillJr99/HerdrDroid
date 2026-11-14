package com.billmongan.herdrdroid.store

import android.content.Context
import android.os.Build
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

/**
 * The phone's saved secrets behind a fingerprint (1.3.1, Bill: "Fingerprint: yes, with a 4-hour
 * window"). Saved passwords, imported key files with their passphrases, and the chats and question
 * history kept on the phone are sealed with AES-GCM keys in the Android Keystore that can only be
 * used for [WINDOW_SECONDS] after the user proved who they are, with a strong biometric or the
 * screen lock (an app unlock through BiometricPrompt, or unlocking the phone itself).
 *
 * Migration from 1.3.0 and earlier, whose keys needed no authentication: data sealed with an old
 * key (no "v2:" prefix, no [CHAT_MAGIC] header) is still opened with it. After the first
 * authenticated unlock, [migrate] re-seals everything under the new keys, and only when every item
 * has been re-sealed are the old keys deleted, so nothing is lost if it stops half way. Until then
 * new data is sealed with the old key, so nothing needs a prompt before the user has unlocked once.
 * A phone with no secure screen lock can't hold such keys: it stays on the old ones.
 *
 * Used outside the window, the new keys refuse with [VaultLocked]: callers treat that as "locked"
 * (alerts say "Unlock HerdrDroid to read", chats are neither read nor deleted, and the app asks for
 * the fingerprint when it next opens), never as damaged data.
 */
object Vaults {
    /** 4 hours in the release build; 3 minutes in the debug build, for the emulator test (build.gradle.kts). */
    val WINDOW_SECONDS: Int = com.billmongan.herdrdroid.BuildConfig.VAULT_WINDOW_SECONDS
    const val PW_OLD = "herdrdroid_passwords"
    const val PW_NEW = "herdrdroid_passwords_v2"
    const val CH_OLD = "herdrdroid_chats"
    const val CH_NEW = "herdrdroid_chats_v2"
    /** The header of a chat file sealed with the new key. */
    val CHAT_MAGIC = byteArrayOf('H'.code.toByte(), 'D'.code.toByte(), 'v'.code.toByte(), '2'.code.toByte())
    const val PW_PREFIX = "v2:"

    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun has(alias: String) = runCatching { ks().containsAlias(alias) }.getOrDefault(false)
    internal fun existing(alias: String): SecretKey? = runCatching { ks().getKey(alias, null) as? SecretKey }.getOrNull()

    /** The 1.3.0 key (no authentication), made only if no data of any kind exists yet under either key. */
    internal fun oldKey(alias: String): SecretKey = existing(alias) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generateKey()
    }

    /** A key that needs the user: strong biometric or screen lock, valid [WINDOW_SECONDS] after it. */
    internal fun newKey(alias: String): SecretKey {
        existing(alias)?.let { return it }
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            // A new fingerprint enrolled later must not make the saved passwords and chats unreadable.
            .setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(WINDOW_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(WINDOW_SECONDS)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(b.build()); generateKey() }
    }

    /** True once [migrate] finished: the new keys exist and the old ones are gone. */
    fun migrated(): Boolean = has(PW_NEW) && has(CH_NEW) && !has(PW_OLD) && !has(CH_OLD)

    /** True when data still needs re-sealing under the new keys (and the phone can hold them). */
    fun migrationPending(ctx: Context): Boolean = deviceSecure(ctx) && !migrated()

    fun deviceSecure(ctx: Context): Boolean =
        runCatching { (ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure }.getOrDefault(false)

    @Volatile private var lockedAt = 0L
    @Volatile private var checkedAt = 0L
    @Volatile private var lastLocked = false

    /**
     * True when the new keys can't be used now (the window has passed). False before migration
     * (the old keys need nobody). Checked by starting an encryption, which reveals nothing; kept
     * for 15 seconds.
     */
    fun locked(): Boolean {
        if (!has(PW_NEW)) return false
        val now = System.currentTimeMillis()
        if (now - checkedAt < 15_000) return lastLocked
        val r = runCatching {
            aesGcm().init(Cipher.ENCRYPT_MODE, existing(PW_NEW) ?: return@runCatching false)
            false
        }
        // Fail closed: anything but a key that can never be used again counts as locked, so an
        // unexpected refusal asks for the fingerprint instead of letting the app run unlocked.
        lastLocked = r.getOrElse { it !is android.security.keystore.KeyPermanentlyInvalidatedException }
        // The lock state and, on a refusal, its kind only (no key material, no data).
        android.util.Log.i("HerdrDroidVault", "lock check: ${if (lastLocked) "locked" else "open"}" + (r.exceptionOrNull()?.let { " (${it.javaClass.simpleName})" } ?: ""))
        checkedAt = now
        if (lastLocked) lockedAt = now
        return lastLocked
    }

    /** After a successful unlock: the window starts again, so the next check asks the Keystore afresh. */
    fun justUnlocked() { checkedAt = 0L; lastLocked = false }

    /**
     * AES-GCM from the Android Keystore's own provider. Asked by name because the SSH library
     * registers BouncyCastle ahead of the platform's providers: with a plain getInstance, a Keystore
     * refusal (not authenticated) fell through to BouncyCastle, which can't use a Keystore key and
     * failed with a NullPointerException instead, hiding that the key was locked (found in the
     * 1.3.1 emulator test).
     */
    private val keystoreProvider: java.security.Provider? by lazy {
        java.security.Security.getProviders().firstOrNull { it.name.startsWith("AndroidKeyStore") && it.getService("Cipher", "AES/GCM/NoPadding") != null }
    }
    internal fun aesGcm(): Cipher = keystoreProvider?.let { Cipher.getInstance("AES/GCM/NoPadding", it) } ?: Cipher.getInstance("AES/GCM/NoPadding")

    internal fun cipher(mode: Int, key: SecretKey, iv: ByteArray? = null): Cipher = try {
        aesGcm().apply { if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(128, iv)) }
    } catch (e: android.security.keystore.UserNotAuthenticatedException) {
        checkedAt = System.currentTimeMillis(); lastLocked = true
        throw VaultLocked(e)
    }

    /**
     * Re-seals every saved secret and kept chat under the new keys; deletes the old keys only when
     * all of them were re-sealed. Run right after an authenticated unlock. Returns what happened.
     */
    fun migrate(ctx: Context, store: ServerStore, chatDir: java.io.File): String {
        if (!deviceSecure(ctx)) return "no secure screen lock; the old keys stay"
        if (migrated()) return "already done"
        return runCatching {
            newKey(PW_NEW); newKey(CH_NEW)
            var secrets = 0
            for (srv in store.all()) {
                fun re(c: String?): String? = c?.let { if (it.startsWith(PW_PREFIX)) it else PasswordVault.sealNew(PasswordVault.decrypt(it)).also { secrets++ } }
                val upd = srv.copy(passwordCipher = re(srv.passwordCipher), keyCipher = re(srv.keyCipher), keyPassCipher = re(srv.keyPassCipher))
                if (upd != srv) store.save(upd)
            }
            var files = 0
            chatDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".chat") || it.name.endsWith(".q")) }?.forEach { f ->
                val bytes = f.readBytes()
                if (!ChatVault.isNew(bytes)) {
                    val plain = ChatVault.open(bytes)
                    val tmp = java.io.File(chatDir, f.name + ".mig")
                    tmp.writeBytes(ChatVault.sealNew(plain))
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                    files++
                }
            }
            // Everything is under the new keys: only now do the old ones go.
            ks().deleteEntry(PW_OLD)
            ks().deleteEntry(CH_OLD)
            justUnlocked()
            "re-sealed $secrets secret(s) and $files chat file(s); old keys removed"
        }.getOrElse { "not finished (${it.javaClass.simpleName}: ${it.message}); the old keys stay and it runs again at the next unlock" }
    }
}

/** A Keystore key that needs the fingerprint, used outside its window: locked, not damaged (1.3.1). */
class VaultLocked(cause: Throwable) : Exception("HerdrDroid is locked: unlock it to read", cause)

/** Encrypts saved passwords and key files with an AES key that never leaves the Android Keystore (see [Vaults]). */
object PasswordVault {
    /** New data: the new key once migration is done, else the old one (no prompt before the first unlock). */
    fun encrypt(password: String): String =
        if (Vaults.migrated()) sealNew(password) else sealOld(password)

    internal fun sealNew(password: String): String {
        val c = Vaults.cipher(Cipher.ENCRYPT_MODE, Vaults.newKey(Vaults.PW_NEW))
        return Vaults.PW_PREFIX + Base64.encodeToString(c.iv + c.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun sealOld(password: String): String {
        val c = Vaults.cipher(Cipher.ENCRYPT_MODE, Vaults.oldKey(Vaults.PW_OLD))
        return Base64.encodeToString(c.iv + c.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Throws [VaultLocked] outside the window for data under the new key. */
    fun decrypt(blob: String): String {
        val isNew = blob.startsWith(Vaults.PW_PREFIX)
        val bytes = Base64.decode(blob.removePrefix(Vaults.PW_PREFIX), Base64.NO_WRAP)
        val key = if (isNew) Vaults.existing(Vaults.PW_NEW) else Vaults.existing(Vaults.PW_OLD)
        key ?: throw IllegalStateException("the key for this saved secret is gone")
        val c = Vaults.cipher(Cipher.DECRYPT_MODE, key, bytes.copyOfRange(0, 12))
        return String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }
}

/** Seals the chats kept on the phone with an AES key that never leaves the Android Keystore (1.2.9; see [Vaults]). */
object ChatVault {
    fun isNew(sealed: ByteArray): Boolean = sealed.size > 4 && sealed.copyOfRange(0, 4).contentEquals(Vaults.CHAT_MAGIC)

    fun seal(plain: ByteArray): ByteArray = if (Vaults.migrated()) sealNew(plain) else {
        val c = Vaults.cipher(Cipher.ENCRYPT_MODE, Vaults.oldKey(Vaults.CH_OLD))
        c.iv + c.doFinal(plain)
    }

    internal fun sealNew(plain: ByteArray): ByteArray {
        val c = Vaults.cipher(Cipher.ENCRYPT_MODE, Vaults.newKey(Vaults.CH_NEW))
        return Vaults.CHAT_MAGIC + c.iv + c.doFinal(plain)
    }

    /** Throws [VaultLocked] outside the window for a file under the new key. */
    fun open(sealed: ByteArray): ByteArray {
        val isNew = isNew(sealed)
        val body = if (isNew) sealed.copyOfRange(4, sealed.size) else sealed
        val key = (if (isNew) Vaults.existing(Vaults.CH_NEW) else Vaults.existing(Vaults.CH_OLD))
            ?: throw IllegalStateException("the key for this chat file is gone")
        val c = Vaults.cipher(Cipher.DECRYPT_MODE, key, body.copyOfRange(0, 12))
        return c.doFinal(body, 12, body.size - 12)
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
