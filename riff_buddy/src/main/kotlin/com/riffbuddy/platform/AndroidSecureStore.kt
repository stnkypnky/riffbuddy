package com.riffbuddy.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.riffbuddy.ai.ProviderId
import com.riffbuddy.data.SecureStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys, encrypted by a key that never leaves the device's secure hardware
 *. Ported from an earlier app — the design notes.
 *
 * This class only ever holds ciphertext, so the preferences file is useless if
 * pulled off the device. Values are stored as Base64(iv || ciphertext).
 *
 * Deliberately hand-rolled rather than `androidx.security:security-crypto`,
 * which sat in alpha for years and is now deprecated.
 *
 * Nothing here touches the database, which is what keeps keys out of the
 * export by construction rather than by remembering to strip them — and
 * `SchemaTest` fails the build if a key-shaped column ever appears.
 */
class AndroidSecureStore(context: Context) : SecureStore {

    private val prefs = context.getSharedPreferences("riffbuddy_keys", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun put(provider: ProviderId, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val blob = cipher.iv + cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(provider.name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    override fun get(provider: ProviderId): String? {
        val stored = prefs.getString(provider.name, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
            )
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // A key rotation, or a restore onto a new device, invalidates the
            // Keystore entry. Treat it as "no key" so the player is asked to paste it
            // again rather than shown a crash the player can do nothing about.
            null
        }
    }

    override fun remove(provider: ProviderId) = prefs.edit().remove(provider.name).apply()

    override fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "riffbuddy_api_keys"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
