package com.lakshaysethi.victronbleexporter.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Prefs readable at LOCKED_BOOT_COMPLETED. Existing credential-storage copies
 * (the pre-0.2.4 default-context files) are copied once into device-protected
 * storage so a downstairs reboot does not drop the schedule or remote secret.
 */
internal fun bootSafePrefs(context: Context, name: String): SharedPreferences {
    val de = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(name, Context.MODE_PRIVATE)
    if (de.all.isNotEmpty()) return de
    val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    if (legacy === de || legacy.all.isEmpty()) return de
    val editor = de.edit()
    for ((key, value) in legacy.all) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
        }
    }
    editor.apply()
    return de
}
