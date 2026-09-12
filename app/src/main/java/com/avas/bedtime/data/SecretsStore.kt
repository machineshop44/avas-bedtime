package com.avas.bedtime.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Plex tokens + Discord webhook — AES-GCM via Android Keystore (not plaintext DataStore).
 */
class SecretsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var plexToken: String
        get() = read(KEY_PLEX)
        set(value) = write(KEY_PLEX, value)

    var serverAccessToken: String
        get() = read(KEY_SERVER)
        set(value) = write(KEY_SERVER, value)

    var discordWebhookUrl: String
        get() = read(KEY_DISCORD)
        set(value) = write(KEY_DISCORD, value)

    fun writeAll(plexToken: String, serverAccessToken: String, discordWebhookUrl: String) {
        prefs.edit()
            .putString(KEY_PLEX, encrypt(plexToken))
            .putString(KEY_SERVER, encrypt(serverAccessToken))
            .putString(KEY_DISCORD, encrypt(discordWebhookUrl))
            .apply()
    }

    private fun read(key: String): String {
        val stored = prefs.getString(key, null) ?: return ""
        return runCatching { decrypt(stored) }.getOrElse {
            Log.w(TAG, "Could not decrypt $key — clearing")
            prefs.edit().remove(key).apply()
            ""
        }
    }

    private fun write(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    private fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(4 + iv.size + cipherBytes.size)
            .putInt(iv.size)
            .put(iv)
            .put(cipherBytes)
            .array()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val buf = ByteBuffer.wrap(packed)
        val ivLen = buf.int
        val iv = ByteArray(ivLen)
        buf.get(iv)
        val cipherBytes = ByteArray(buf.remaining())
        buf.get(cipherBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "SecretsStore"
        private const val PREFS = "ava_secrets_enc"
        private const val KEY_PLEX = "plex_token"
        private const val KEY_SERVER = "server_access_token"
        private const val KEY_DISCORD = "discord_webhook_url"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ava_bedtime_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
