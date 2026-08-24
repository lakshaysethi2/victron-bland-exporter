package com.lakshaysethi.victronbleexporter.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lakshaysethi.victronbleexporter.charger.ChargerScheduleAlarm
import com.lakshaysethi.victronbleexporter.service.VictronBleExporterService

/** Exact-alarm callback: bring the exporter back up and apply the daily window. */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ChargerScheduleAlarm.ACTION) return
        val service = Intent(context, VictronBleExporterService::class.java)
            .setAction(ChargerScheduleAlarm.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
