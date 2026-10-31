package com.bigrocket.service

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption with a non-exportable Android Keystore key, used to keep the
 * user's proxy credentials (uuid / password / REALITY keys inside Xray configs) out of
 * plain-text SharedPreferences. The key never leaves the Keystore.
 *
 * Output format: `v1:` + base64(iv(12) || ciphertext+tag).
 */
internal object SecureTextCipher {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "bigrocket_config_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val PREFIX = "v1:"

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** @throws java.security.GeneralSecurityException when the Keystore is unusable. */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    /** Null when [encoded] is not ours, is corrupt, or its key is gone (e.g. data restored to another device). */
    fun decrypt(encoded: String): String? = try {
        if (!encoded.startsWith(PREFIX)) {
            null
        } else {
            val blob = Base64.decode(encoded.removePrefix(PREFIX), Base64.NO_WRAP)
            if (blob.size <= IV_BYTES) {
                null
            } else {
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
                )
                String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
            }
        }
    } catch (_: Exception) {
        null
    }
}
