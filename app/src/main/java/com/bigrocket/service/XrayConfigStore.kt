package com.bigrocket.service

import android.content.Context

/**
 * Persists the user-supplied Xray configuration exactly as pasted — either a
 * share link (e.g. vless://...) or a full client JSON config (v2rayNG/v2rayN
 * style). SharedPreferences and synchronous on purpose, for exactly the same
 * reason EmbeddedAetherRuntime.readUpstreamChoice() is: the Service must be
 * able to read it on its own, with no async window where a default is visible.
 */
object XrayConfigStore {
    private const val PREFS = "bigrocket_xray"
    private const val KEY_RAW_CONFIG = "raw_config"

    fun read(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RAW_CONFIG, null)
            ?.takeIf { it.isNotBlank() }

    fun save(context: Context, rawConfig: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RAW_CONFIG, rawConfig.trim())
            .apply()
    }
}
