package com.lakshaysethi.victronbleexporter.charger

import java.util.Calendar

/**
 * Pure charger-schedule logic: a daily enable/disable window, expressed in
 * minutes since midnight. Kept free of Android dependencies so it can be unit
 * tested on the JVM.
 *
 * Semantics:
 *  - enable 08:30 / disable 18:00 -> charger ON while the clock is inside
 *    [08:30, 18:00).
 *  - enable 18:00 / disable 08:30 -> overnight window (enable > disable),
 *    charger ON from 18:00 until 08:30 the next morning.
 *  - enable == disable -> treated as a 24 h window (charger always ON) so a
 *    degenerate config never locks the charger off.
 */
object ChargerSchedule {

    const val DEFAULT_ENABLE = "08:30"
    const val DEFAULT_DISABLE = "18:00"

    /** "HH:mm" -> minutes since midnight; null when malformed. */
    fun parseMinutes(hhmm: String?): Int? {
        if (hhmm == null) return null
        val m = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(hhmm.trim()) ?: return null
        val hours = m.groupValues[1].toInt()
        val minutes = m.groupValues[2].toInt()
        return hours * 60 + minutes
    }

    /** minutes since midnight -> "HH:mm". */
    fun formatMinutes(minutes: Int): String {
        val clamped = ((minutes % 1440) + 1440) % 1440
        return "%02d:%02d".format(clamped / 60, clamped % 60)
    }

    fun isValidTime(hhmm: String): Boolean = parseMinutes(hhmm) != null

    /** True when the charger should be ON at the given minute-of-day. */
    fun isInWindow(nowMinutes: Int, enableMinutes: Int, disableMinutes: Int): Boolean {
        if (enableMinutes == disableMinutes) return true // degenerate: 24 h window
        return if (enableMinutes < disableMinutes) {
            nowMinutes in enableMinutes until disableMinutes
        } else {
            nowMinutes >= enableMinutes || nowMinutes < disableMinutes // overnight
        }
    }

    /** The next window boundary strictly after nowMinutes (same day or wrapped). */
    fun nextTransition(nowMinutes: Int, enableMinutes: Int, disableMinutes: Int): Int {
        val candidates = listOf(enableMinutes, disableMinutes).filter { it > nowMinutes }
        if (candidates.isNotEmpty()) return candidates.min()
        // All boundaries already passed today: next one is tomorrow morning.
        return listOf(enableMinutes, disableMinutes).min()
    }

    /** The charger state the schedule wants at nowMinutes (true = ON). */
    fun scheduledOn(nowMinutes: Int, enableMinutes: Int, disableMinutes: Int): Boolean =
        isInWindow(nowMinutes, enableMinutes, disableMinutes)

    /** Phone-local HH:mm and zone id the daily window actually uses. */
    fun phoneClock(now: Calendar = Calendar.getInstance()): Pair<String, String> {
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return formatMinutes(minutes) to now.timeZone.id
    }
}
