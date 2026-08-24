package com.lakshaysethi.victronbleexporter.charger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.lakshaysethi.victronbleexporter.receiver.ScheduleAlarmReceiver

/**
 * One-shot RTC alarm that restarts the exporter if Android killed it between
 * schedule boundaries. Uses setExactAndAllowWhileIdle (not setAlarmClock) so
 * the status bar is not a clock every 15 minutes. Cancel only on user Stop.
 */
object ExporterKeepAliveAlarm {
    const val ACTION = "com.lakshaysethi.victronbleexporter.EXPORTER_KEEP_ALIVE"
    const val INTERVAL_MS = 15 * 60 * 1000L
    private const val REQ = 4310
    private const val TAG = "ExporterKeepAlive"

    fun nextAt(now: Long = System.currentTimeMillis()): Long = now + INTERVAL_MS

    fun ignoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun arm(context: Context, now: Long = System.currentTimeMillis()): Long {
        val at = nextAt(now)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarm = pending(context)
        try {
            if (ChargerScheduleAlarm.canExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
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
}
