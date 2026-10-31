package com.bigrocket.service

import android.content.Context

/**
 * Persists the user-supplied Xray configuration exactly as entered (share links,
 * subscription body or client JSON), encrypted at rest with a Keystore-held key
 * (SecureTextCipher). SharedPreferences and synchronous on purpose, for exactly the
 * same reason EmbeddedAetherRuntime.readUpstreamChoice() is: the Service must be able
 * to read it on its own, with no async window where a default is visible.
 *
 * A plain-text value written by an earlier version is migrated to the encrypted form on
 * first read. If the encrypted value cannot be decrypted (key lost, e.g. app data
 * restored to another device), [read] returns null and the user re-enters the config.
 */
object XrayConfigStore {
    private const val PREFS = "bigrocket_xray"
    private const val KEY_RAW_CONFIG = "raw_config"
    private const val KEY_ENCRYPTED_CONFIG = "raw_config_enc"

    fun read(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ENCRYPTED_CONFIG, null)?.let { encrypted ->
            return SecureTextCipher.decrypt(encrypted)?.takeIf { it.isNotBlank() }
        }
        val legacy = prefs.getString(KEY_RAW_CONFIG, null)?.takeIf { it.isNotBlank() } ?: return null
        // Migrate; if the Keystore is unusable the legacy value stays readable rather than lost.
        runCatching { save(context, legacy) }
        return legacy
    }

    /** @throws java.security.GeneralSecurityException when the Keystore cannot encrypt. */
    fun save(context: Context, rawConfig: String) {
        val encrypted = SecureTextCipher.encrypt(rawConfig.trim())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENCRYPTED_CONFIG, encrypted)
            .remove(KEY_RAW_CONFIG)
            .apply()
    }
}
