package com.lakshaysethi.victronbleexporter.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lakshaysethi.victronbleexporter.charger.ChargerScheduleAlarm
import com.lakshaysethi.victronbleexporter.charger.ExporterKeepAliveAlarm
import com.lakshaysethi.victronbleexporter.service.VictronBleExporterService

/** Exact-alarm callback: bring the exporter back up (schedule window or keep-alive). */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ChargerScheduleAlarm.ACTION && action != ExporterKeepAliveAlarm.ACTION) return
        val service = Intent(context, VictronBleExporterService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
