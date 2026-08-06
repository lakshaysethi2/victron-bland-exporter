package com.lakshaysethi.victronbleexporter

import com.lakshaysethi.victronbleexporter.charger.ChargerDebugLog
import com.lakshaysethi.victronbleexporter.charger.ChargerProtocol
import com.lakshaysethi.victronbleexporter.tunnel.CloudflaredManager

object AppState {
    @Volatile var tunnelStatus: String = "Stopped"
    @Volatile var tunnelUrl: String? = null

    /**
     * Last tunnel URL that was auto-copied to the clipboard. Process-scoped so a
     * fresh (re)start — new process or a new URL after a stop — auto-copies again,
     * while rotation/recomposition of the same URL does not re-toast.
     */
    @Volatile var lastAutoCopiedTunnelUrl: String? = null

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

    /** Last solar panel (PV) voltage in volts read via the charger GATT service; null = unknown. */
    @Volatile var panelVoltageVolts: Double? = null

    /** Epoch millis of the last successful panel-voltage read. */
    @Volatile var panelVoltageUpdatedAt: Long = 0L

    /** Last panel-voltage read failure message; null when the last read succeeded (or none attempted). */
    @Volatile var panelVoltageLastError: String? = null

    /**
     * Panel voltage is served on /metrics only while it is fresher than this — mirrors the
     * device-expiry semantics for broadcast data (a stale value is treated as unknown).
     */
    const val PANEL_VOLTAGE_TTL_MS = 5 * 60_000L

    /**
     * Backoff (ms) after [failures] consecutive panel-voltage read failures: 1 min, doubling
     * each failure, capped at 15 min (Android 12+ throttles connectGatt after repeated failures).
     */
    fun panelVoltageBackoffMs(failures: Int): Long =
        (60_000L shl failures.coerceIn(0, 4)).coerceAtMost(15 * 60_000L)

    val chargerModeText: String get() = ChargerProtocol.chargerModeText(chargerMode)

    /** Section appended to the shared debug log so the captain can diagnose BLE writes. */
    fun chargerDebugSection(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Charger control ===")
        sb.appendLine("Target MAC: ${chargerMac ?: "not configured"}")
        sb.appendLine("Charger state: $chargerModeText (mode=${chargerMode ?: "n/a"})")
        sb.appendLine("Panel voltage: ${panelVoltageVolts?.let { String.format(java.util.Locale.US, "%.2f V", it) } ?: "not read yet"}")
        panelVoltageLastError?.let { sb.appendLine("Panel voltage error: $it") }
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
