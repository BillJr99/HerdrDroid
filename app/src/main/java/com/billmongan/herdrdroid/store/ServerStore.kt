package com.billmongan.herdrdroid.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
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
) {
    val label: String get() = name.ifBlank { "$user@$host" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port)
        .put("user", user).put("herdrPath", herdrPath)
        .put("fingerprint", fingerprint ?: JSONObject.NULL)
        .put("passwordCipher", passwordCipher ?: JSONObject.NULL)
        .put("biometric", biometric)

    companion object {
        fun fromJson(o: JSONObject) = Server(
            id = o.getString("id"),
            name = o.optString("name"),
            host = o.getString("host"),
            port = o.getInt("port"),
            user = o.getString("user"),
            herdrPath = o.optString("herdrPath", "herdr"),
            fingerprint = o.optString("fingerprint").takeIf { !o.isNull("fingerprint") && it.isNotBlank() },
            passwordCipher = o.optString("passwordCipher").takeIf { !o.isNull("passwordCipher") && it.isNotBlank() },
            biometric = o.optBoolean("biometric", false),
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

/** Encrypts saved passwords with an AES key that never leaves the Android Keystore. */
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
