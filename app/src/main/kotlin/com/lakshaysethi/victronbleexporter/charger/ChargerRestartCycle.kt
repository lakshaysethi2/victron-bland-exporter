package com.lakshaysethi.victronbleexporter.charger

/**
 * Issue #31: periodic off→on restart of the required MPPT.
 * Same GATT path as POST /charger. Pure so it can be unit-tested.
 */
object ChargerRestartCycle {
    const val INTERVAL_MS = 30L * 60L * 1000L
    const val OFF_HOLD_MS = 4_000L

    /** First pulse waits a full interval after [startedAt] if nothing has run yet. */
    fun due(nowMs: Long, lastRestartAtMs: Long, startedAtMs: Long, intervalMs: Long = INTERVAL_MS): Boolean {
        val anchor = if (lastRestartAtMs > 0L) lastRestartAtMs else startedAtMs
        if (anchor <= 0L) return false
        return nowMs - anchor >= intervalMs
    }

    /** Daily window still wins: no pulse while the schedule wants OFF. */
    fun allowedBySchedule(
        scheduleEnabled: Boolean,
        nowMinutes: Int,
        enableMinutes: Int,
        disableMinutes: Int,
        manualOverrideUntil: Long,
        nowMs: Long,
    ): Boolean {
        if (manualOverrideUntil > nowMs) return false
        if (!scheduleEnabled) return true
        return ChargerSchedule.scheduledOn(nowMinutes, enableMinutes, disableMinutes)
    }
}
