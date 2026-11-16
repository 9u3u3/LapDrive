package dev.lapdrive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The pairing data for one laptop. */
data class Connection(
    val hosts: List<String>,
    val port: Int,
    /** SHA-256 of the laptop certificate (DER), lowercase hex. */
    val fingerprint: String,
    val token: String,
    val serverName: String,
) {
    fun toJson(): String = JSONObject()
        .put("hosts", JSONArray(hosts))
        .put("port", port)
        .put("fp", fingerprint)
        .put("token", token)
        .put("server", serverName)
        .toString()

    companion object {
        fun fromJson(s: String): Connection {
            val o = JSONObject(s)
            val h = o.getJSONArray("hosts")
            return Connection(
                hosts = List(h.length()) { h.getString(it) },
                port = o.getInt("port"),
                fingerprint = o.getString("fp"),
                token = o.getString("token"),
                serverName = o.getString("server"),
            )
        }
    }
}

/**
 * Keeps the connection (with the device token) encrypted with AES-256-GCM.
 * The key lives in the Android Keystore. The key cannot leave the secure
 * hardware, and other apps cannot read it.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("lapdrive_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun save(conn: Connection) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(conn.toJson().toByteArray())
        val blob = cipher.iv + ct
        prefs.edit().putString(PREF, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }

    fun load(): Connection? {
        val b64 = prefs.getString(PREF, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_LEN))
            Connection.fromJson(String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)))
        } catch (e: Exception) {
            // A wrong key or changed data. Forget it. The user pairs again.
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove(PREF).commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object {
        const val ALIAS = "lapdrive_conn_key"
        const val PREF = "conn"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
