package com.lakshaysethi.victronbleexporter.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lakshaysethi.victronbleexporter.charger.ChargerRestartAlarm
import com.lakshaysethi.victronbleexporter.charger.ChargerRestartCycle
import com.lakshaysethi.victronbleexporter.charger.ChargerScheduleAlarm
import com.lakshaysethi.victronbleexporter.charger.ExporterKeepAliveAlarm
import com.lakshaysethi.victronbleexporter.data.ChargerScheduleStore
import com.lakshaysethi.victronbleexporter.service.VictronBleExporterService
import java.util.Calendar

/** Exact-alarm callback: bring the exporter back up (schedule window, keep-alive, or 30m restart). */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            ChargerScheduleAlarm.ACTION, ExporterKeepAliveAlarm.ACTION -> {
                ChargerRestartAlarm.armOff(context)
                startExporter(context, action)
            }
            ChargerRestartAlarm.ACTION_OFF -> handleRestartOff(context)
            ChargerRestartAlarm.ACTION_ON -> handleRestartOn(context)
        }
    }

    private fun handleRestartOff(context: Context) {
        val store = ChargerScheduleStore(context)
        ChargerRestartAlarm.armOff(context)
        if (!store.restartEvery30m) return
        val settings = store.load()
        val now = System.currentTimeMillis()
        val minutes = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        if (!ChargerRestartCycle.allowedBySchedule(
                settings.scheduleEnabled,
                minutes,
                settings.enableMinutes,
                settings.disableMinutes,
                settings.manualOverrideUntil,
                now,
            )
        ) return
        val mac = settings.chargerMac.ifBlank { return }
        startChargerSet(context, mac, enable = false)
        ChargerRestartAlarm.armOn(context)
    }

    private fun handleRestartOn(context: Context) {
        val store = ChargerScheduleStore(context)
        val mac = store.chargerMac.ifBlank { return }
        startChargerSet(context, mac, enable = true)
        store.lastRestartAt = System.currentTimeMillis()
    }

    private fun startChargerSet(context: Context, mac: String, enable: Boolean) {
        val service = Intent(context, VictronBleExporterService::class.java).apply {
            action = "CHARGER_SET"
            putExtra("mac", mac)
            putExtra("enable", enable)
        }
        startExporter(context, service)
    }

    private fun startExporter(context: Context, actionOrIntent: Any) {
        val service = if (actionOrIntent is Intent) actionOrIntent
        else Intent(context, VictronBleExporterService::class.java).setAction(actionOrIntent as String)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
