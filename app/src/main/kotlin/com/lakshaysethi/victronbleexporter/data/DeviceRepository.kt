package com.lakshaysethi.victronbleexporter.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Simple encrypted storage for device MAC -> encryption keys.
 */
class DeviceRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "victron_devices",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveDevice(mac: String, key: String) {
        prefs.edit().putString(mac.uppercase(), key).apply()
    }

    fun getAllDevices(): Map<String, String> {
        return prefs.all.filterKeys { it.matches(Regex("[0-9A-F:]{17}")) } as Map<String, String>
    }

    fun getKey(mac: String): String? = prefs.getString(mac.uppercase(), null)

    fun removeDevice(mac: String) {
        prefs.edit().remove(mac.uppercase()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}