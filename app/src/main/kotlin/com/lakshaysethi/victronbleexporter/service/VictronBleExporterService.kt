package com.lakshaysethi.victronbleexporter.service

import android.os.PowerManager

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lakshaysethi.victronbleexporter.AppState
import com.lakshaysethi.victronbleexporter.BuildConfig
import com.lakshaysethi.victronbleexporter.R
import com.lakshaysethi.victronbleexporter.charger.ChargerController
import com.lakshaysethi.victronbleexporter.charger.ChargerDebugLog
import com.lakshaysethi.victronbleexporter.charger.ChargerSchedule
import com.lakshaysethi.victronbleexporter.charger.ChargerScheduleAlarm
import com.lakshaysethi.victronbleexporter.data.ChargerScheduleStore
import com.lakshaysethi.victronbleexporter.data.DeviceRepository
import com.lakshaysethi.victronbleexporter.diag.AppLog
import com.lakshaysethi.victronbleexporter.diag.Diagnostics
import com.lakshaysethi.victronbleexporter.data.RemoteChargerStore
import com.lakshaysethi.victronbleexporter.exporter.ChargerCommandSender
import com.lakshaysethi.victronbleexporter.exporter.ChargerReadSender
import com.lakshaysethi.victronbleexporter.exporter.ChargerStatusSnapshot
import com.lakshaysethi.victronbleexporter.exporter.KeyCommandSender
import com.lakshaysethi.victronbleexporter.exporter.LiveReadout
import com.lakshaysethi.victronbleexporter.exporter.ScheduleCommandSender
import com.lakshaysethi.victronbleexporter.exporter.SightedDevice
import com.lakshaysethi.victronbleexporter.exporter.DiscoveredDevicesStore
import com.lakshaysethi.victronbleexporter.exporter.VoltageCommandSender
import com.lakshaysethi.victronbleexporter.exporter.MetricsStore
import com.lakshaysethi.victronbleexporter.exporter.PrometheusExporter
import com.lakshaysethi.victronbleexporter.exporter.RemoteChargerHttp
import com.lakshaysethi.victronbleexporter.exporter.ScanCommandSender
import com.lakshaysethi.victronbleexporter.exporter.TunnelCommandSender
import com.lakshaysethi.victronbleexporter.parser.VictronParser
import com.lakshaysethi.victronbleexporter.tunnel.CloudflaredManager
import kotlinx.coroutines.*
import java.util.*

private const val TAG = "VictronBleExporterService"
private const val NOTIFICATION_ID = 1337
private const val CHANNEL_ID = "victron_exporter_channel"

class VictronBleExporterService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private lateinit var prometheusExporter: PrometheusExporter
    private lateinit var cloudflaredManager: CloudflaredManager
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var deviceRepository: DeviceRepository

    // Charger control (BLE write of register 0x0200 device mode) + daily schedule.
    private val chargerController: ChargerController by lazy { ChargerController(this) }
    private val chargerScheduleStore: ChargerScheduleStore by lazy { ChargerScheduleStore(this) }
    private var lastScheduledMode: Boolean? = null
    private var lastScheduledAppliedAt = 0L
    private var scheduleRetryAt = 0L
    private var lastVoltagePollAt = 0L
    private var lastTunnelRestoreAt = 0L

    /**
     * Remote charger-control HTTP surface. Commands are forwarded to this
     * service via the same CHARGER_SET intent the UI uses, so a remote flip
     * goes through performChargerSet() -> ChargerController.setMode() and gets
     * the same manual-override/schedule semantics as a local tap.
     */
    private val remoteChargerControl: RemoteChargerHttp by lazy {
        RemoteChargerHttp(
            settingsProvider = { RemoteChargerStore(this).load() },
            statusProvider = {
                val s = chargerScheduleStore.load()
                val minutes = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
                ChargerStatusSnapshot.fromAppState().copy(
                    scheduleEnabled = s.scheduleEnabled,
                    enableTime = ChargerSchedule.formatMinutes(s.enableMinutes),
                    disableTime = ChargerSchedule.formatMinutes(s.disableMinutes),
                    scheduleWantsOn = ChargerSchedule.scheduledOn(minutes, s.enableMinutes, s.disableMinutes),
                    nextTransition = ChargerSchedule.formatMinutes(
                        ChargerSchedule.nextTransition(minutes, s.enableMinutes, s.disableMinutes),
                    ),
                    live = LiveReadout.fromFreshMetrics(),
                    sighted = SightedDevice.fromStore(),
                    debug = ChargerDebugLog.snapshot().takeLast(ChargerStatusSnapshot.REMOTE_DEBUG_LINES),
                    tunnelHasToken = savedTunnelToken() != null,
                    appVersion = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                )
            },
            macProvider = { AppState.chargerMac ?: chargerScheduleStore.load().chargerMac.ifBlank { null } },
            commandSender = ChargerCommandSender { enable, mac ->
                try {
                    val intent = Intent(this, VictronBleExporterService::class.java).apply {
                        action = "CHARGER_SET"
                        putExtra("mac", mac)
                        putExtra("enable", enable)
                    }
                    startForegroundService(intent)
                    Log.i(TAG, "Remote charger command: ${if (enable) "ENABLE" else "DISABLE"} for $mac")
                } catch (e: Exception) {
                    Log.e(TAG, "Remote charger command could not be sent", e)
                }
            },
            scheduleSender = ScheduleCommandSender { enabled, enableTime, disableTime, mac ->
                try {
                    startForegroundService(Intent(this, VictronBleExporterService::class.java).apply {
                        action = "CHARGER_SCHEDULE_SAVE"
                        putExtra("mac", mac)
                        putExtra("schedule_enabled", enabled)
                        putExtra("enable_time", enableTime)
                        putExtra("disable_time", disableTime)
                    })
                    Log.i(TAG, "Remote schedule save: $mac ${if (enabled) "on" else "off"} $enableTime-$disableTime")
                } catch (e: Exception) {
                    Log.e(TAG, "Remote schedule save could not be sent", e)
                }
            },
            voltageCommandSender = object : VoltageCommandSender {
                override fun sendBatteryVoltageSetting(mac: String, volts: Int) {
                    try {
                        startForegroundService(Intent(this@VictronBleExporterService, VictronBleExporterService::class.java).apply {
                            action = "VOLTAGE_SET_BATTERY"
                            putExtra("mac", mac)
                            putExtra("volts", volts)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Remote battery voltage command failed", e)
                    }
                }
                override fun sendChargingVoltages(mac: String, absorptionVolts: Double?, floatVolts: Double?) {
                    try {
                        startForegroundService(Intent(this@VictronBleExporterService, VictronBleExporterService::class.java).apply {
                            action = "VOLTAGE_SET_CHARGING"
                            putExtra("mac", mac)
                            if (absorptionVolts != null) putExtra("absorption_volts", absorptionVolts)
                            if (floatVolts != null) putExtra("float_volts", floatVolts)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Remote charging voltage command failed", e)
                    }
                }
                override fun requestVoltageRead(mac: String) {
                    try {
                        startForegroundService(Intent(this@VictronBleExporterService, VictronBleExporterService::class.java).apply {
                            action = "VOLTAGE_READ"
                            putExtra("mac", mac)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Remote voltage read failed", e)
                    }
                }
            },
            keySender = KeyCommandSender { mac, key ->
                try {
                    startForegroundService(Intent(this, VictronBleExporterService::class.java).apply {
                        action = "ADD_KEY"
                        putExtra("mac", mac)
                        putExtra("key", key)
                    })
                    Log.i(TAG, "Remote Instant Readout key save for $mac")
                } catch (e: Exception) {
                    Log.e(TAG, "Remote key save could not be sent", e)
                }
            },
            readSender = ChargerReadSender { mac ->
                try {
                    startForegroundService(Intent(this, VictronBleExporterService::class.java).apply {
                        action = "CHARGER_READ"
                        putExtra("mac", mac)
                    })
                    Log.i(TAG, "Remote charger read for $mac")
                } catch (e: Exception) {
                    Log.e(TAG, "Remote charger read could not be sent", e)
                }
            },
            tunnelSender = object : TunnelCommandSender {
                override fun start(token: String?) {
                    try {
                        startForegroundService(Intent(this@VictronBleExporterService, VictronBleExporterService::class.java).apply {
                            if (token.isNullOrBlank()) {
                                action = "START_SAVED_TUNNEL"
                            } else {
                                action = "START_TUNNEL"
                                putExtra("tunnel_token", token)
                            }
                        })
                        Log.i(TAG, "Remote named-tunnel start")
                    } catch (e: Exception) {
                        Log.e(TAG, "Remote named-tunnel start could not be sent", e)
                    }
                }
                override fun stop() {
                    try {
                        startForegroundService(Intent(this@VictronBleExporterService, VictronBleExporterService::class.java).apply {
                            action = "STOP_TUNNEL"
                        })
                        Log.i(TAG, "Remote named-tunnel stop")
                    } catch (e: Exception) {
                        Log.e(TAG, "Remote named-tunnel stop could not be sent", e)
                    }
                }
            },
            scanSender = ScanCommandSender {
                try {
                    startForegroundService(Intent(this, VictronBleExporterService::class.java).apply {
                        action = "RESTART_SCAN"
                    })
                    Log.i(TAG, "Remote BLE scan restart")
                } catch (e: Exception) {
                    Log.e(TAG, "Remote BLE scan restart could not be sent", e)
                }
            },
        )
    }

    // Device encryption keys: MAC -> key (hex) - in-memory cache, persisted via DeviceRepository
    private val deviceKeys = mutableMapOf<String, String>()

    // BLE scan health: last advertisement or successful startScan. 0 = never started.
    @Volatile private var lastScanResultAt = 0L

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_UNLOCKED) return
            try {
                deviceRepository = DeviceRepository(this@VictronBleExporterService)
                loadPersistedKeys()
                Log.i(TAG, "Reloaded keys after user unlock")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reload keys after unlock", e)
            }
        }
    }

    fun addDeviceKey(mac: String, key: String) {
        val normalizedMac = DeviceRepository.normalizeMacInput(mac)
        val cleanKey = DeviceRepository.normalizeKeyInput(key)
        if (!DeviceRepository.isValidKey(cleanKey)) {
            Log.w(TAG, "Invalid key format for $mac: length ${cleanKey.length}")
            // Still allow? For UX, reject if clearly invalid
            if (cleanKey.length != 32) return
        }
        deviceKeys[normalizedMac] = cleanKey
        try {
            deviceRepository.saveDevice(normalizedMac, cleanKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist key for $normalizedMac", e)
        }
        DiscoveredDevicesStore.markHasKey(normalizedMac, true)
        Log.i(TAG, "Key added & persisted for $normalizedMac")
    }

    private fun loadPersistedKeys() {
        try {
            if (!::deviceRepository.isInitialized) {
                deviceRepository = DeviceRepository(this)
            }
            val all = deviceRepository.getAllDevices()
            deviceKeys.clear()
            deviceKeys.putAll(all.mapKeys { it.key.uppercase() })
            Log.i(TAG, "Loaded ${deviceKeys.size} persisted keys: ${deviceKeys.keys}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted keys", e)
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        AppLog.init(this)
        AppLog.i("Service starting — app v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Diagnostics.autoSend(this)

        try {
            createNotificationChannel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channel", e)
        }

        try {
            deviceRepository = DeviceRepository(this)
            loadPersistedKeys()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init DeviceRepository", e)
        }
        try {
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register unlock receiver", e)
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VictronBleExporter::WakeLock").apply {
                setReferenceCounted(false)
            }
            // Held for the life of the service so the tunnel + schedule loop survive Doze.
            @Suppress("DEPRECATION")
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wakeLock", e)
        }

        try {
            cloudflaredManager = CloudflaredManager(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init CloudflaredManager", e)
            cloudflaredManager = CloudflaredManager(this) // still init, but log
        }

        try {
            prometheusExporter = PrometheusExporter(5338, remoteChargerControl)
            prometheusExporter.startServer()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start exporter", e)
        }

        try {
            startForegroundServiceNotification("Starting...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            // Fallback without foreground type
            try {
                val notification = buildNotification("Starting...")
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback foreground failed", e2)
            }
        }

        // Start BLE scan - may fail if BT off or permission missing, handled inside
        try {
            startBleScan()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan in onCreate", e)
        }

        startChargerScheduleLoop()
        armScheduleAlarm()
        startScanWatchdog()
    }

    // ---- Charger control (enable/disable over BLE + daily schedule) ----

    private fun startChargerScheduleLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    enforceChargerSchedule()
                    maybeRefreshVoltageSettings()
                } catch (e: Exception) {
                    Log.w(TAG, "Charger schedule tick failed", e)
                }
                delay(30_000)
            }
        }
    }

    /**
     * Enforces the configured enable/disable window while the service runs.
     * Manual overrides pause the schedule until the next window boundary.
     */
    private suspend fun enforceChargerSchedule() {
        val settings = chargerScheduleStore.load()
        val now = System.currentTimeMillis()
        if (settings.manualOverrideUntil in 1..now) {
            chargerScheduleStore.clearOverride()
            AppState.chargerOverrideUntil = 0L
            lastScheduledMode = null
            lastScheduledAppliedAt = 0L
            ChargerDebugLog.append("Manual override ended — schedule resumes")
        }
        if (!settings.scheduleEnabled) return
        if (chargerScheduleStore.manualOverrideUntil > now) return // manual override active
        if (now < scheduleRetryAt) return

        val mac = ExporterKeepAlive.scheduleTargetMac(
            settings.chargerMac,
            MetricsStore.getFresh(now).keys.sorted(),
        ) ?: return
        if (settings.chargerMac.isBlank()) {
            chargerScheduleStore.chargerMac = mac
            AppState.chargerMac = mac
            ChargerDebugLog.append("Schedule target set from live Instant Readout: $mac")
        }

        val cal = Calendar.getInstance()
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val desiredOn = ChargerSchedule.scheduledOn(minutes, settings.enableMinutes, settings.disableMinutes)
        if (!ExporterKeepAlive.shouldApplySchedule(desiredOn, lastScheduledMode, lastScheduledAppliedAt, now)) return

        ChargerDebugLog.append(
            "Schedule tick: window=${ChargerSchedule.formatMinutes(settings.enableMinutes)}-${ChargerSchedule.formatMinutes(settings.disableMinutes)}" +
                " -> charger ${if (desiredOn) "ON" else "OFF"}"
        )
        val result = chargerController.setMode(mac, desiredOn)
        if (result.success) {
            lastScheduledMode = desiredOn
            lastScheduledAppliedAt = now
            AppState.chargerMode = result.mode
            AppState.chargerMac = mac
            AppState.chargerStateUpdatedAt = System.currentTimeMillis()
            AppState.chargerLastAction = "Schedule: charger ${if (desiredOn) "ENABLED" else "DISABLED"} (${result.modeText})"
            AppState.chargerLastError = null
        } else {
            AppState.chargerLastAction = "Schedule apply failed: ${result.message}"
            AppState.chargerLastError = result.message
            AppLog.e("Charger schedule apply failed: ${result.message}")
            Diagnostics.autoSend(this)
            scheduleRetryAt = now + ExporterKeepAlive.SCHEDULE_RETRY_MS
        }
    }

    private suspend fun performChargerSet(mac: String, enable: Boolean) {
        val store = chargerScheduleStore
        store.chargerMac = mac
        AppState.chargerMac = mac
        AppState.chargerBusy = true
        AppState.chargerLastAction = if (enable) "Enabling charger…" else "Disabling charger…"
        AppState.chargerLastError = null
        ChargerDebugLog.append("Manual ${if (enable) "ENABLE" else "DISABLE"} requested for $mac")
        try {
            val result = chargerController.setMode(mac, enable)
            AppState.chargerMode = result.mode
            AppState.chargerStateUpdatedAt = System.currentTimeMillis()
            if (result.success) {
                // Manual override: pause the schedule until the next window boundary.
                val cal = Calendar.getInstance()
                val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val next = ChargerSchedule.nextTransition(minutes, store.load().enableMinutes, store.load().disableMinutes)
                val until = nextTransitionEpoch(next)
                store.manualOverrideUntil = until
                AppState.chargerOverrideUntil = until
                AppState.chargerLastAction = "Charger ${if (enable) "ENABLED" else "DISABLED"} (${result.modeText})"
                ChargerDebugLog.append("Manual override active until ${ChargerSchedule.formatMinutes(next)}")
            } else {
                AppState.chargerLastAction = "Failed: ${result.message}"
                AppState.chargerLastError = result.message
                AppLog.e("Charger ${if (enable) "enable" else "disable"} failed: ${result.message}")
                Diagnostics.autoSend(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Charger set failed", e)
            AppState.chargerLastAction = "Failed: ${e.message}"
            AppState.chargerLastError = e.message
            ChargerDebugLog.append("ERROR: ${e.message}")
            AppLog.e("Charger set failed: ${e.message}")
            Diagnostics.autoSend(this)
        } finally {
            AppState.chargerBusy = false
        }
    }

    private suspend fun performChargerRead(mac: String) {
        val store = chargerScheduleStore
        store.chargerMac = mac
        AppState.chargerMac = mac
        AppState.chargerBusy = true
        AppState.chargerLastAction = "Reading charger state…"
        AppState.chargerLastError = null
        ChargerDebugLog.append("Manual state read requested for $mac")
        try {
            val result = chargerController.readMode(mac)
            AppState.chargerMode = result.mode
            AppState.chargerStateUpdatedAt = System.currentTimeMillis()
            AppState.chargerLastAction = if (result.success) "Read: ${result.modeText}" else "Read failed: ${result.message}"
            if (!result.success) {
                AppState.chargerLastError = result.message
                AppLog.e("Charger state read failed: ${result.message}")
                Diagnostics.autoSend(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Charger read failed", e)
            AppState.chargerLastAction = "Read failed: ${e.message}"
            AppState.chargerLastError = e.message
            ChargerDebugLog.append("ERROR: ${e.message}")
            AppLog.e("Charger read failed: ${e.message}")
            Diagnostics.autoSend(this)
        } finally {
            AppState.chargerBusy = false
        }
    }

    // ---- voltage settings (battery system voltage + absorption/float) ----

    /** Background GATT read of voltage settings + panel voltage; skips when a user op is in flight. */
    private suspend fun maybeRefreshVoltageSettings() {
        if (AppState.chargerBusy) return
        val mac = chargerScheduleStore.load().chargerMac.ifBlank { return }
        val now = System.currentTimeMillis()
        if (!ExporterKeepAlive.voltagePollDue(
                now,
                lastVoltagePollAt,
                AppState.voltageSettingsUpdatedAt,
                AppState.voltageSettingsLastError,
            )
        ) return
        val result = chargerController.tryReadVoltageSettings(mac) ?: return
        lastVoltagePollAt = System.currentTimeMillis()
        if (result.success) {
            AppState.voltageSettings = result.settings
            AppState.voltageSettingsUpdatedAt = lastVoltagePollAt
            AppState.voltageSettingsLastError = null
        } else {
            AppState.voltageSettingsLastError = result.message
        }
    }

    private suspend fun performVoltageRead(mac: String) {
        chargerScheduleStore.chargerMac = mac
        AppState.chargerMac = mac
        AppState.chargerBusy = true
        AppState.chargerLastAction = "Reading voltage settings…"
        AppState.voltageSettingsLastError = null
        ChargerDebugLog.append("Voltage settings read requested for $mac")
        try {
            val result = chargerController.readVoltageSettings(mac)
            if (result.success) {
                AppState.voltageSettings = result.settings
                AppState.voltageSettingsUpdatedAt = System.currentTimeMillis()
                AppState.chargerLastAction = "Voltage: $result"
            } else {
                AppState.chargerLastAction = "Voltage read failed: ${result.message}"
                AppState.voltageSettingsLastError = result.message
            }
        } catch (e: Exception) {
            Log.w(TAG, "Voltage read failed", e)
            AppState.chargerLastAction = "Voltage read failed: ${e.message}"
            AppState.voltageSettingsLastError = e.message
            ChargerDebugLog.append("ERROR: ${e.message}")
        } finally {
            AppState.chargerBusy = false
        }
    }

    private suspend fun performSetBatteryVoltage(mac: String, volts: Int) {
        chargerScheduleStore.chargerMac = mac
        AppState.chargerMac = mac
        AppState.chargerBusy = true
        AppState.chargerLastAction = "Setting battery voltage to $volts V…"
        AppState.voltageSettingsLastError = null
        ChargerDebugLog.append("Set battery voltage $volts V for $mac")
        try {
            val result = chargerController.setBatteryVoltageSetting(mac, volts)
            if (result.success) {
                AppState.voltageSettings = result.settings
                AppState.voltageSettingsUpdatedAt = System.currentTimeMillis()
                AppState.chargerLastAction = "Battery voltage set to $volts V"
            } else {
                AppState.chargerLastAction = "Battery voltage write failed: ${result.message}"
                AppState.voltageSettingsLastError = result.message
            }
        } catch (e: Exception) {
            Log.w(TAG, "Battery voltage set failed", e)
            AppState.chargerLastAction = "Failed: ${e.message}"
            AppState.voltageSettingsLastError = e.message
            ChargerDebugLog.append("ERROR: ${e.message}")
        } finally {
            AppState.chargerBusy = false
        }
    }

    private suspend fun performSetChargingVoltages(mac: String, absorptionVolts: Double?, floatVolts: Double?) {
        chargerScheduleStore.chargerMac = mac
        AppState.chargerMac = mac
        AppState.chargerBusy = true
        AppState.chargerLastAction = "Setting charge voltages…"
        AppState.voltageSettingsLastError = null
        ChargerDebugLog.append("Set charging voltages abs=$absorptionVolts float=$floatVolts for $mac")
        try {
            val result = chargerController.setChargingVoltages(mac, absorptionVolts, floatVolts)
            if (result.success) {
                AppState.voltageSettings = result.settings
                AppState.voltageSettingsUpdatedAt = System.currentTimeMillis()
                AppState.chargerLastAction = "Charge voltages updated: $result"
            } else {
                AppState.chargerLastAction = "Charge voltage write failed: ${result.message}"
                AppState.voltageSettingsLastError = result.message
            }
        } catch (e: Exception) {
            Log.w(TAG, "Charge voltage set failed", e)
            AppState.chargerLastAction = "Failed: ${e.message}"
            AppState.voltageSettingsLastError = e.message
            ChargerDebugLog.append("ERROR: ${e.message}")
        } finally {
            AppState.chargerBusy = false
        }
    }

    private fun saveChargerSchedule(mac: String, enabled: Boolean, enableTime: String, disableTime: String) {
        chargerScheduleStore.save(enabled, enableTime, disableTime, mac)
        AppState.chargerMac = mac
        AppState.chargerOverrideUntil = 0L
        lastScheduledMode = null // force a fresh apply now, not after the 30s loop delay
        lastScheduledAppliedAt = 0L
        ChargerDebugLog.append(
            "Schedule saved: $mac ${if (enabled) "enabled" else "disabled"} " +
                "($enableTime → $disableTime). Applies while the exporter notification is showing."
        )
        armScheduleAlarm()
        serviceScope.launch { enforceChargerSchedule() }
    }

    private fun armScheduleAlarm() {
        val s = chargerScheduleStore.load()
        if (!s.scheduleEnabled) {
            ChargerScheduleAlarm.cancel(this)
            return
        }
        val at = ChargerScheduleAlarm.arm(this, s.enableMinutes, s.disableMinutes)
        val next = ChargerSchedule.nextTransition(
            Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) },
            s.enableMinutes,
            s.disableMinutes,
        )
        ChargerDebugLog.append(
            "Schedule alarm armed for ${ChargerSchedule.formatMinutes(next)} (epoch $at)"
        )
    }

    private fun nextTransitionEpoch(nextMinutesOfDay: Int): Long =
        ChargerSchedule.epochAtMinutesOfDay(System.currentTimeMillis(), nextMinutesOfDay)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                "START_TUNNEL" -> {
                    val token = it.getStringExtra("tunnel_token")
                    if (!token.isNullOrBlank()) {
                        persistTunnelToken(token)
                        cloudflaredManager.startNamedTunnel(token) { status ->
                            updateNotification(status)
                        }
                    } else {
                        // Explicit quick-tunnel from the UI; do not steal a saved named token.
                        cloudflaredManager.startQuickTunnel(5338) { status ->
                            updateNotification(status)
                        }
                    }
                }
                "STOP_TUNNEL" -> cloudflaredManager.stop()
                "START_SAVED_TUNNEL" -> startSavedTunnelNow()
                "RESTART_SCAN" -> restartScan()
                "ADD_KEY" -> {
                    val mac = it.getStringExtra("mac") ?: return@let
                    val key = it.getStringExtra("key") ?: return@let
                    addDeviceKey(mac, key)
                }
                "CHARGER_SET" -> {
                    val mac = it.getStringExtra("mac") ?: return@let
                    val enable = it.getBooleanExtra("enable", true)
                    serviceScope.launch { performChargerSet(mac, enable) }
                }
                "CHARGER_READ" -> {
                    val mac = it.getStringExtra("mac") ?: return@let
                    serviceScope.launch { performChargerRead(mac) }
                }
                "CHARGER_SCHEDULE_SAVE" -> {
                    val mac = it.getStringExtra("mac") ?: return@let
                    val enabled = it.getBooleanExtra("schedule_enabled", false)
                    val enableTime = it.getStringExtra("enable_time") ?: ChargerSchedule.DEFAULT_ENABLE
                    val disableTime = it.getStringExtra("disable_time") ?: ChargerSchedule.DEFAULT_DISABLE
                    saveChargerSchedule(mac, enabled, enableTime, disableTime)
                }
                ChargerScheduleAlarm.ACTION -> {
                    lastScheduledMode = null
                    lastScheduledAppliedAt = 0L
                    restoreSavedTunnel()
                    armScheduleAlarm()
                    serviceScope.launch { enforceChargerSchedule() }
                }
                "VOLTAGE_READ" -> {
                    val mac = it.getStringExtra("mac") ?: return@let
                    serviceScope.launch { performVoltageRead(mac) }
                }
                "VOLTAGE_SET_BATTERY" -> {
                    val mac = it.getStringExtra("mac") ?: return@let
                    val volts = it.getIntExtra("volts", -1)
                    if (volts < 0) return@let
                    serviceScope.launch { performSetBatteryVoltage(mac, volts) }
                }
                "VOLTAGE_SET_CHARGING" -> {
                    val mac = it.getStringExtra("mac") ?: return@let
                    val hasAbs = it.hasExtra("absorption_volts")
                    val hasFloat = it.hasExtra("float_volts")
                    val abs = if (hasAbs) it.getDoubleExtra("absorption_volts", Double.NaN).takeIf { !it.isNaN() } else null
                    val fl = if (hasFloat) it.getDoubleExtra("float_volts", Double.NaN).takeIf { !it.isNaN() } else null
                    if (abs == null && fl == null) return@let
                    serviceScope.launch { performSetChargingVoltages(mac, abs, fl) }
                }
            }
        }
        if (intent?.action == null) {
            // Boot / sticky restart / Start Scan with no command: restore the named tunnel if it is down.
            restoreSavedTunnel()
        }
        updateNotification("Running")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed — keeping exporter running")
        try {
            startForegroundService(Intent(this, VictronBleExporterService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "restart after task-removed failed", e)
        }
    }

    private fun persistTunnelToken(token: String) {
        try {
            if (!::deviceRepository.isInitialized) {
                deviceRepository = DeviceRepository(this)
            }
            deviceRepository.saveTunnelToken(token)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist tunnel token", e)
        }
    }

    private fun savedTunnelToken(): String? = try {
        if (!::deviceRepository.isInitialized) {
            deviceRepository = DeviceRepository(this)
        }
        deviceRepository.getTunnelToken()
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read persisted tunnel token", e)
        null
    }

    /** Explicit start from the saved token — ignores a previous user Stop. */
    private fun startSavedTunnelNow() {
        val token = savedTunnelToken()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Remote named-tunnel start skipped — no token saved")
            return
        }
        lastTunnelRestoreAt = System.currentTimeMillis()
        Log.i(TAG, "Starting named tunnel from saved token")
        AppLog.i("Starting named tunnel from saved token")
        cloudflaredManager.startNamedTunnel(token) { status -> updateNotification(status) }
    }

    /** Starts the named tunnel from the persisted token; no-ops when already running, user-stopped, or none saved. */
    private fun restoreSavedTunnel(): Boolean {
        val alreadyRunning = ::cloudflaredManager.isInitialized && cloudflaredManager.isRunning()
        val userStopped = ::cloudflaredManager.isInitialized && cloudflaredManager.wasManuallyStopped()
        val token = try {
            if (!::deviceRepository.isInitialized) {
                deviceRepository = DeviceRepository(this)
            }
            deviceRepository.getTunnelToken()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read persisted tunnel token", e)
            null
        }
        val now = System.currentTimeMillis()
        if (!ExporterKeepAlive.shouldRestoreNamedTunnel(alreadyRunning, token, userStopped, lastTunnelRestoreAt, now)) {
            return alreadyRunning
        }
        lastTunnelRestoreAt = now
        Log.i(TAG, "Restoring named tunnel from saved token")
        AppLog.i("Restoring named tunnel from saved token")
        cloudflaredManager.startNamedTunnel(token!!) { status -> updateNotification(status) }
        return true
    }

    /** Restarts the BLE scan if Android dropped it without onScanFailed. */
    private fun startScanWatchdog() {
        serviceScope.launch {
            while (isActive) {
                try {
                    if (ExporterKeepAlive.shouldRestartScan(lastScanResultAt, System.currentTimeMillis())) {
                        Log.w(TAG, "No scan results for ${ExporterKeepAlive.SCAN_RESTART_AFTER_MS}ms — restarting BLE scan")
                        AppLog.w("No scan results for ${ExporterKeepAlive.SCAN_RESTART_AFTER_MS}ms — restarting BLE scan")
                        restartScan()
                    }
                    restoreSavedTunnel()
                } catch (e: Exception) {
                    Log.w(TAG, "Scan watchdog tick failed", e)
                }
                delay(60_000)
            }
        }
    }

    private fun restartScan() {
        try {
            stopBleScan()
        } catch (e: Exception) {
            Log.w(TAG, "stopBleScan failed", e)
        }
        startBleScan()
    }

    private fun startBleScan() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            AppLog.e("BLE unavailable/disabled — scan skipped")
            Diagnostics.autoSend(this)
            return
        }

        bluetoothLeScanner = adapter.bluetoothLeScanner

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val scanFilter = ScanFilter.Builder()
            .setManufacturerData(0x02E1, byteArrayOf(0x10)) // Victron + product adv
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    lastScanResultAt = System.currentTimeMillis()
                    AppState.lastBleAdAt = lastScanResultAt
                    val device = result.device
                    val scanRecord = result.scanRecord ?: return
                    val mfgData = scanRecord.getManufacturerSpecificData(0x02E1) ?: return

                    val mac = device.address.uppercase()
                    val rssi = result.rssi

                    // Extract modelId & recordType without decryption (always visible)
                    val modelId: Int? = if (mfgData.size >= 4) {
                        ((mfgData[3].toInt() and 0xFF) shl 8) or (mfgData[2].toInt() and 0xFF)
                    } else null
                    val recordType: Int? = if (mfgData.size >= 5) mfgData[4].toInt() and 0xFF else null

                    val key = deviceKeys[mac] ?: deviceKeys[mac.uppercase()]
                    val hasKey = !key.isNullOrBlank()

                    val parsed = if (hasKey) {
                        try {
                            VictronParser.parseAdvertisement(mac, mfgData, rssi, key)
                        } catch (e: Exception) {
                            Log.w(TAG, "Parse failed for $mac", e)
                            null
                        }
                    } else null

                    // Always update discovered store for easy UX
                    try {
                        DiscoveredDevicesStore.updateSeen(
                            mac = mac,
                            modelId = modelId,
                            recordType = recordType,
                            rssi = rssi,
                            hasKey = hasKey,
                            parsed = parsed,
                            decryptFailed = hasKey && parsed == null
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update discovered store", e)
                    }

                    if (parsed != null) {
                        MetricsStore.update(parsed)
                        Log.d(TAG, "Parsed ${parsed.mac} : ${parsed.data}")
                    } else {
                        if (!hasKey) {
                            Log.d(TAG, "Saw Victron $mac (model=${modelId?.toString(16)}) without key - showing in discovered list")
                        } else {
                            Log.d(TAG, "Saw Victron adv from $mac (key present but parse fail)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onScanResult exception", e)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE Scan failed: $errorCode — restarting scan")
                AppLog.e("BLE scan failed: errorCode=$errorCode")
                Diagnostics.autoSend(this@VictronBleExporterService)
                lastScanResultAt = 0L
                serviceScope.launch {
                    delay(5_000)
                    restartScan()
                }
            }
        }

        try {
            bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            lastScanResultAt = System.currentTimeMillis()
            Log.i(TAG, "BLE scan started for Victron devices")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE permission", e)
            AppLog.e("BLE scan start blocked — permission missing")
            Diagnostics.autoSend(this)
        }
    }

    @SuppressLint("MissingPermission") // SecurityException is handled by the catch below; permission is requested at startup
    private fun stopBleScan() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan", e)
        }
        bluetoothLeScanner = null
        scanCallback = null
    }

    private fun startForegroundServiceNotification(initialText: String) {
        val notification = buildNotification(initialText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: String) {
        val count = MetricsStore.count()
        val tunnel = cloudflaredManager.tunnelUrl ?: cloudflaredManager.status
        val text = getString(R.string.notification_text, count, tunnel.take(40))
        val notification = buildNotification(text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(content: String): Notification {
        val mainIntent = try {
            Intent(this, Class.forName("com.lakshaysethi.victronbleexporter.MainActivity"))
        } catch (e: Exception) {
            Log.w(TAG, "MainActivity class not found via reflection, using package launcher", e)
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        }

        val pendingIntent = try {
            PendingIntent.getActivity(
                this, 0, mainIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create PendingIntent", e)
            null
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
        AppLog.i("Service stopped")
        try { stopBleScan() } catch (e: Exception) { Log.w(TAG, "stopBleScan failed", e) }
        try {
            if (::prometheusExporter.isInitialized) prometheusExporter.stopServer()
        } catch (e: Exception) {
            Log.w(TAG, "stop exporter failed", e)
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock release failed", e)
        }
        try {
            if (::cloudflaredManager.isInitialized) cloudflaredManager.stop()
        } catch (e: Exception) {
            Log.w(TAG, "cloudflared stop failed", e)
        }
        try {
            chargerController.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "charger controller shutdown failed", e)
        }
        try {
            unregisterReceiver(unlockReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "unlock receiver unregister failed", e)
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Keep-alive / restore policy, kept Android-free so it can be unit-tested on the JVM. */
internal object ExporterKeepAlive {
    const val SCHEDULE_RETRY_MS = 60_000L
    const val SCHEDULE_REENFORCE_MS = 600_000L
    const val VOLTAGE_POLL_MS = 60_000L
    const val VOLTAGE_POLL_BACKOFF_MS = 300_000L
    const val VOLTAGE_FRESH_MS = 300_000L
    const val SCAN_RESTART_AFTER_MS = 180_000L
    const val TUNNEL_RESTART_AFTER_MS = 60_000L

    fun shouldRestoreNamedTunnel(
        alreadyRunning: Boolean,
        savedToken: String?,
        userStopped: Boolean = false,
        lastRestartAt: Long = 0L,
        now: Long = Long.MAX_VALUE,
    ): Boolean {
        if (userStopped || alreadyRunning || savedToken.isNullOrBlank()) return false
        if (lastRestartAt > 0L && now - lastRestartAt < TUNNEL_RESTART_AFTER_MS) return false
        return true
    }

    /** Stored charger MAC wins; otherwise the first live Instant Readout. */
    fun scheduleTargetMac(stored: String?, liveMacs: List<String>): String? {
        val saved = stored?.trim().orEmpty()
        if (saved.isNotEmpty()) return saved
        return liveMacs.firstOrNull { it.isNotBlank() }
    }

    /** Apply on a window change, or again every 10 minutes so a dropped write is retried. */
    fun shouldApplySchedule(desiredOn: Boolean, lastAppliedOn: Boolean?, lastAppliedAt: Long, now: Long): Boolean {
        if (lastAppliedOn != desiredOn) return true
        if (lastAppliedAt <= 0L) return true
        return now - lastAppliedAt >= SCHEDULE_REENFORCE_MS
    }

    fun voltagePollDue(now: Long, lastPollAt: Long, lastSuccessAt: Long, lastError: String?): Boolean {
        val interval = if (lastError != null) VOLTAGE_POLL_BACKOFF_MS else VOLTAGE_POLL_MS
        return now - maxOf(lastPollAt, lastSuccessAt) >= interval
    }

    fun voltageFresh(now: Long, updatedAt: Long): Boolean =
        updatedAt > 0L && now - updatedAt < VOLTAGE_FRESH_MS

    fun shouldRestartScan(lastScanResultAt: Long, now: Long): Boolean =
        lastScanResultAt != 0L && now - lastScanResultAt >= SCAN_RESTART_AFTER_MS
}