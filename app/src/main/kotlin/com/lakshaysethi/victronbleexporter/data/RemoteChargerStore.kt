package com.lakshaysethi.victronbleexporter.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Plain SharedPreferences store for the remote charger-control surface
 * (the HTTP API + control page served on port 5338 and reachable through the
 * Cloudflare tunnel).
 *
 * Follows the ChargerScheduleStore pattern (context-provided prefs, defensive
 * reads). The auth secret is deliberately NOT encrypted: same trade-off as the
 * rest of the app's prefs — the app sandbox keeps prefs app-private, and the
 * secret never leaves the device except over the TLS tunnel inside an
 * `X-Remote-Secret` request header. Never log it, never put it in a URL.
 */
class RemoteChargerStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("victron_remote_settings", Context.MODE_PRIVATE)

    /** Master switch: when false every /charger* route answers 404 (feature hidden). */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Shared secret required on every /charger* API call (header `X-Remote-Secret`). */
    var authSecret: String
        get() = prefs.getString(KEY_AUTH_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_SECRET, value).apply()

    fun load(): RemoteChargerSettings = RemoteChargerSettings(enabled = enabled, authSecret = authSecret)

    fun save(enabled: Boolean, authSecret: String) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_AUTH_SECRET, authSecret)
            .apply()
    }

    data class RemoteChargerSettings(
        val enabled: Boolean,
        val authSecret: String,
    )

    private companion object {
        const val KEY_ENABLED = "remote_control_enabled"
        const val KEY_AUTH_SECRET = "remote_control_secret"
    }
}
