package com.lakshaysethi.victronbleexporter.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lakshaysethi.victronbleexporter.service.VictronBleExporterService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Start the exporter service on boot
            val serviceIntent = Intent(context, VictronBleExporterService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}