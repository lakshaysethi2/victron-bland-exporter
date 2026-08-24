package com.lakshaysethi.victronbleexporter.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lakshaysethi.victronbleexporter.service.VictronBleExporterService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldStartOn(intent.action)) return
        val serviceIntent = Intent(context, VictronBleExporterService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}

/** Boot / OEM quick-boot / APK replace should bring the exporter back up. */
internal fun shouldStartOn(action: String?): Boolean = when (action) {
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    "android.intent.action.QUICKBOOT_POWERON",
    "com.htc.intent.action.QUICKBOOT_POWERON" -> true
    else -> false
}