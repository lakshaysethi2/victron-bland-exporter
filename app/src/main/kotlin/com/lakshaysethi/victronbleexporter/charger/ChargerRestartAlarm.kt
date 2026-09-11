package com.lakshaysethi.victronbleexporter.charger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lakshaysethi.victronbleexporter.receiver.ScheduleAlarmReceiver

/**
 * Issue #31: exact RTC alarms that flip the required MPPT off, then on,
 * through the existing CHARGER_SET service path (same as POST /charger).
 */
object ChargerRestartAlarm {
    const val ACTION_OFF = "com.lakshaysethi.victronbleexporter.CHARGER_RESTART_OFF"
    const val ACTION_ON = "com.lakshaysethi.victronbleexporter.CHARGER_RESTART_ON"
    private const val REQ_OFF = 4312
    private const val REQ_ON = 4313
    private const val TAG = "ChargerRestartAlarm"

    fun armOff(context: Context, atMs: Long = System.currentTimeMillis() + ChargerRestartCycle.INTERVAL_MS): Long {
        arm(context, ACTION_OFF, REQ_OFF, atMs)
        return atMs
    }

    fun armOn(context: Context, atMs: Long = System.currentTimeMillis() + ChargerRestartCycle.OFF_HOLD_MS): Long {
        arm(context, ACTION_ON, REQ_ON, atMs)
        return atMs
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(context, ACTION_OFF, REQ_OFF))
            am.cancel(pending(context, ACTION_ON, REQ_ON))
        } catch (e: Exception) {
            Log.w(TAG, "cancel failed", e)
        }
    }

    private fun arm(context: Context, action: String, req: Int, atMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarm = pending(context, action, req)
        try {
            if (ChargerScheduleAlarm.canExact(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarm)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, atMs, alarm)
                }
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarm)
            }
        } catch (e: Exception) {
            Log.w(TAG, "arm $action failed", e)
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarm)
            } catch (e2: Exception) {
                Log.w(TAG, "inexact arm $action failed", e2)
            }
        }
    }

    private fun pending(context: Context, action: String, req: Int): PendingIntent {
        val i = Intent(context, ScheduleAlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, req, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
