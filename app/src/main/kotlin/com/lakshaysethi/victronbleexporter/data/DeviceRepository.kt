package com.lakshaysethi.victronbleexporter.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Simple encrypted storage for device MAC -> encryption keys.
 * Falls back to plain SharedPreferences if encrypted store fails (some OEMs / emulators).
 *
 * The named-tunnel token and Instant Readout keys are also written to
 * device-protected prefs so boot restore can read them at LOCKED_BOOT_COMPLETED,
 * before credential storage is unlocked.
 */
class DeviceRepository(context: Context) {

    private val tag = "DeviceRepository"
    private var prefs: SharedPreferences
    private val tokenPrefs: SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
    private val keyPrefs: SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)

    init {
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "victron_devices",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(tag, "EncryptedSharedPreferences failed, falling back to plain prefs", e)
            try {
                context.getSharedPreferences("victron_devices_fallback", Context.MODE_PRIVATE)
            } catch (e2: Exception) {
                // Locked boot: credential CE storage is not available yet.
                Log.w(tag, "Credential prefs unavailable; using device-protected fallback", e2)
                context.createDeviceProtectedStorageContext()
                    .getSharedPreferences("victron_devices_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    fun saveDevice(mac: String, key: String) {
        val cleanKey = key.trim().lowercase().replace(Regex("[^0-9a-f]"), "")
        if (cleanKey.length != 32) {
            Log.w(tag, "Attempt to save invalid key length ${cleanKey.length} for $mac")
        }
        writeKey(prefs, mac, cleanKey)
        writeKey(keyPrefs, mac, cleanKey)
        Log.i(tag, "Saved key for $mac")
    }

    fun getAllDevices(): Map<String, String> {
        val boot = macEntries(keyPrefs)
        val cred = try {
            macEntries(prefs)
        } catch (e: Exception) {
            Log.e(tag, "getAllDevices credential read failed", e)
            emptyMap()
        }
        if (cred.isNotEmpty()) {
            val editor = keyPrefs.edit()
            var wrote = false
            for ((mac, key) in cred) {
                if (keyPrefs.getString(mac, null) != key) {
                    editor.putString(mac, key)
                    wrote = true
                }
            }
            if (wrote) editor.apply()
        }
        return boot + cred
    }

    fun getKey(mac: String): String? {
        val upper = mac.uppercase()
        val cred = try {
            prefs.getString(upper, null)
        } catch (e: Exception) {
            Log.w(tag, "getKey credential read failed for $mac", e)
            null
        }
        if (cred != null) {
            if (keyPrefs.getString(upper, null) != cred) writeKey(keyPrefs, upper, cred)
            return cred
        }
        return keyPrefs.getString(upper, null)
    }

    fun saveTunnelToken(token: String) {
        val trimmed = token.trim()
        writeToken(tokenPrefs, trimmed)
        try {
            writeToken(prefs, trimmed)
        } catch (e: Exception) {
            Log.e(tag, "Failed to save tunnel token to credential store", e)
        }
        Log.i(tag, "Saved tunnel token")
    }

    fun getTunnelToken(): String? {
        readToken(tokenPrefs)?.let { return it }
        val legacy = try {
            readToken(prefs)
        } catch (e: Exception) {
            Log.w(tag, "getTunnelToken credential read failed", e)
            null
        }
        if (legacy != null) writeToken(tokenPrefs, legacy) // migrate onto the boot-safe store
        return legacy
    }

    private fun writeToken(store: SharedPreferences, trimmed: String) {
        if (trimmed.isBlank()) {
            store.edit().remove(KEY_TUNNEL_TOKEN).apply()
        } else {
            store.edit().putString(KEY_TUNNEL_TOKEN, trimmed).apply()
        }
    }

    private fun readToken(store: SharedPreferences): String? =
        store.getString(KEY_TUNNEL_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun removeDevice(mac: String) {
        val upper = mac.uppercase()
        try {
            prefs.edit().remove(upper).apply()
        } catch (e: Exception) {
            Log.e(tag, "remove failed for $mac", e)
        }
        try {
            keyPrefs.edit().remove(upper).apply()
        } catch (e: Exception) {
            Log.e(tag, "boot key remove failed for $mac", e)
        }
    }

    fun clear() {
        try {
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(tag, "clear failed", e)
        }
        try {
            tokenPrefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(tag, "tokenPrefs clear failed", e)
        }
        try {
            keyPrefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(tag, "keyPrefs clear failed", e)
        }
    }

    private fun writeKey(store: SharedPreferences, mac: String, cleanKey: String) {
        try {
            store.edit().putString(mac.uppercase(), cleanKey).apply()
        } catch (e: Exception) {
            Log.e(tag, "Failed to save $mac", e)
        }
    }

    private fun macEntries(store: SharedPreferences): Map<String, String> =
        store.all.entries
            .filter { entry ->
                entry.key.matches(Regex("(?i)^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")) ||
                    entry.key.matches(Regex("(?i)^[0-9A-F:]{17}$"))
            }
            .filter { it.value is String }
            .associate { it.key.uppercase() to it.value as String }

    fun hasKey(mac: String): Boolean = !getKey(mac).isNullOrBlank()

    companion object {
        // Reserved key for the cloudflared named-tunnel token (not a MAC, so getAllDevices skips it).
        internal const val TOKEN_PREFS = "victron_tunnel_token"
        internal const val KEY_PREFS = "victron_devices_boot"
        internal const val KEY_TUNNEL_TOKEN = "__tunnel_token__"

        fun normalizeKeyInput(input: String): String {
            return input.trim().lowercase().replace(Regex("[^0-9a-f]"), "")
        }

        fun isValidKey(input: String): Boolean {
            val clean = normalizeKeyInput(input)
            return clean.length == 32 && clean.matches(Regex("^[0-9a-f]{32}$"))
        }

        fun normalizeMacInput(input: String): String {
            val cleaned = input.trim().uppercase().replace(Regex("[^0-9A-F:]"), "")
            // Auto-insert colons if given as 12 hex chars without colons
            return if (!cleaned.contains(":") && cleaned.length == 12) {
                cleaned.chunked(2).joinToString(":")
            } else {
                cleaned.uppercase()
            }
        }

        fun isValidMac(input: String): Boolean {
            val mac = normalizeMacInput(input)
            return mac.matches(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$"))
        }
    }
}
