package com.lakshaysethi.victronbleexporter.charger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lakshaysethi.victronbleexporter.receiver.ScheduleAlarmReceiver

/**
 * One exact RTC alarm at the next daily window boundary. Wakes the exporter
 * even if the process died, so 08:30/18:00 still fire after an OEM kill.
 */
object ChargerScheduleAlarm {
    const val ACTION = "com.lakshaysethi.victronbleexporter.CHARGER_SCHEDULE_ALARM"
    private const val REQ = 4308
    private const val TAG = "ChargerScheduleAlarm"

    fun arm(context: Context, enableMinutes: Int, disableMinutes: Int, now: Long = System.currentTimeMillis()): Long {
        val at = ChargerSchedule.nextTransitionEpoch(now, enableMinutes, disableMinutes)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarm = pending(context)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent(context)), alarm)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
            }
        } catch (e: Exception) {
            Log.w(TAG, "arm failed, falling back to inexact", e)
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
            } catch (e2: Exception) {
                Log.w(TAG, "inexact arm failed", e2)
            }
        }
        return at
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(context))
        } catch (e: Exception) {
            Log.w(TAG, "cancel failed", e)
        }
    }

    private fun pending(context: Context): PendingIntent {
        val i = Intent(context, ScheduleAlarmReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context, REQ, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun showIntent(context: Context): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        return PendingIntent.getActivity(
            context, REQ + 1, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
