package com.lakshaysethi.victronbleexporter.data

import android.content.Context
import android.content.SharedPreferences
import com.lakshaysethi.victronbleexporter.charger.ChargerSchedule

/**
 * Plain SharedPreferences store for the charger control settings:
 * schedule window, target device MAC, and the manual-override deadline.
 * Follows the DeviceRepository pattern (context-provided prefs, defensive
 * reads) but schedule settings are not secret so no encryption is needed.
 */
class ChargerScheduleStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("victron_charger_settings", Context.MODE_PRIVATE)

    var scheduleEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, value).apply()

    /** "HH:mm" strings; defaults 08:30 / 18:00. */
    var enableTime: String
        get() = prefs.getString(KEY_ENABLE_TIME, ChargerSchedule.DEFAULT_ENABLE) ?: ChargerSchedule.DEFAULT_ENABLE
        set(value) = prefs.edit().putString(KEY_ENABLE_TIME, value).apply()

    var disableTime: String
        get() = prefs.getString(KEY_DISABLE_TIME, ChargerSchedule.DEFAULT_DISABLE) ?: ChargerSchedule.DEFAULT_DISABLE
        set(value) = prefs.edit().putString(KEY_DISABLE_TIME, value).apply()

    /** MAC the schedule + manual buttons act on (blank = not configured). */
    var chargerMac: String
        get() = prefs.getString(KEY_CHARGER_MAC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CHARGER_MAC, value.trim().uppercase()).apply()

    /** Epoch millis until which a manual override pauses the schedule; 0 = none. */
    var manualOverrideUntil: Long
        get() = prefs.getLong(KEY_OVERRIDE_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_OVERRIDE_UNTIL, value).apply()

    fun clearOverride() {
        prefs.edit().remove(KEY_OVERRIDE_UNTIL).apply()
    }

    fun load(): ChargerSettings = ChargerSettings(
        scheduleEnabled = scheduleEnabled,
        enableMinutes = ChargerSchedule.parseMinutes(enableTime) ?: 8 * 60 + 30,
        disableMinutes = ChargerSchedule.parseMinutes(disableTime) ?: 18 * 60,
        chargerMac = chargerMac,
        manualOverrideUntil = manualOverrideUntil,
    )

    /** Persist schedule + MAC and clear any pending manual override. */
    fun save(scheduleEnabled: Boolean, enableTime: String, disableTime: String, chargerMac: String) {
        prefs.edit()
            .putBoolean(KEY_SCHEDULE_ENABLED, scheduleEnabled)
            .putString(KEY_ENABLE_TIME, enableTime)
            .putString(KEY_DISABLE_TIME, disableTime)
            .putString(KEY_CHARGER_MAC, chargerMac.trim().uppercase())
            .remove(KEY_OVERRIDE_UNTIL)
            .apply()
    }

    data class ChargerSettings(
        val scheduleEnabled: Boolean,
        val enableMinutes: Int,
        val disableMinutes: Int,
        val chargerMac: String,
        val manualOverrideUntil: Long,
    )

    private companion object {
        const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        const val KEY_ENABLE_TIME = "enable_time"
        const val KEY_DISABLE_TIME = "disable_time"
        const val KEY_CHARGER_MAC = "charger_mac"
        const val KEY_OVERRIDE_UNTIL = "manual_override_until"
    }
}
