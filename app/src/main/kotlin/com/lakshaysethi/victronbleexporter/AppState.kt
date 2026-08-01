package com.lakshaysethi.victronbleexporter

import com.lakshaysethi.victronbleexporter.charger.ChargerDebugLog
import com.lakshaysethi.victronbleexporter.charger.ChargerProtocol
import com.lakshaysethi.victronbleexporter.tunnel.CloudflaredManager

object AppState {
    @Volatile var tunnelStatus: String = "Stopped"
    @Volatile var tunnelUrl: String? = null

    /** Last DNS/network self-test report text (also embedded in shareable debug logs). */
    @Volatile var dnsSelfTestResult: String? = null

    /**
     * Live reference to the active tunnel manager so the UI can build and share
     * a debug log. Set by CloudflaredManager itself when the service instantiates it.
     */
    @Volatile var cloudflaredManager: CloudflaredManager? = null

    // ---- charger control state (written by VictronBleExporterService) ----

    /** Last known device-mode value (1 = charger on, 0/4 = off); null = unknown. */
    @Volatile var chargerMode: Int? = null

    /** MAC the charger state belongs to. */
    @Volatile var chargerMac: String? = null

    /** True while a BLE read/write session is running. */
    @Volatile var chargerBusy: Boolean = false

    /** Human-readable outcome of the last charger action. */
    @Volatile var chargerLastAction: String = "No charger action yet"

    /** Non-null when the last charger action failed. */
    @Volatile var chargerLastError: String? = null

    /** Epoch millis of the last successful charger state update. */
    @Volatile var chargerStateUpdatedAt: Long = 0L

    /** Epoch millis until which a manual override pauses the schedule; 0 = none. */
    @Volatile var chargerOverrideUntil: Long = 0L

    val chargerModeText: String get() = ChargerProtocol.chargerModeText(chargerMode)

    /** Section appended to the shared debug log so the captain can diagnose BLE writes. */
    fun chargerDebugSection(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Charger control ===")
        sb.appendLine("Target MAC: ${chargerMac ?: "not configured"}")
        sb.appendLine("Charger state: $chargerModeText (mode=${chargerMode ?: "n/a"})")
        sb.appendLine("Last action: $chargerLastAction")
        sb.appendLine("Last error: ${chargerLastError ?: "none"}")
        sb.appendLine("Manual override until: ${chargerOverrideUntil.takeIf { it > 0 }?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it)) } ?: "none"}")
        sb.appendLine("--- last ${ChargerDebugLog.snapshot().size} charger log lines ---")
        val lines = ChargerDebugLog.snapshot()
        if (lines.isEmpty()) {
            sb.appendLine("(no charger BLE activity yet — use Enable/Disable in the app)")
        } else {
            lines.forEach { sb.appendLine(it) }
        }
        return sb.toString()
    }
}
