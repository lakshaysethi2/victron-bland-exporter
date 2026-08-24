package com.lakshaysethi.victronbleexporter.diag

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.lakshaysethi.victronbleexporter.AppState
import com.lakshaysethi.victronbleexporter.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Remote diagnostics: collects the app log buffer + device info and POSTs it to
 * the mppt-log-server. Never blocks the UI thread (all work on an IO scope).
 *
 * Server contract (optional private log host from BuildConfig.LOG_SERVER_BASE):
 *   POST /api/logs with {"device_id", "app_version", "entries": [{"ts", "level", "msg"]}
 *   where ts is a STRING (ISO-8601 works) and level is one of "info" | "warn" | "error"
 *   (uppercase/other levels are rejected with 422) -> 201 {"ok": true}.
 *
 * Failures degrade gracefully: the log buffer stays local (persisted) and the
 * next auto-send / manual tap retries. Auto-sends are rate-limited to once an
 * hour; the manual "Send diagnostics" button always sends.
 */
object Diagnostics {

    val LOGS_URL: String
        get() {
            val base = BuildConfig.LOG_SERVER_BASE.trim().trimEnd('/')
            return if (base.isBlank()) "" else "$base/api/logs"
        }
    const val AUTO_SEND_INTERVAL_MS = 60 * 60 * 1000L // once per hour

    private const val PREFS_NAME = "victron_diagnostics"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST_AUTO_SEND = "last_auto_send_ms"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val autoSendLock = Any()

    // ------------------------------------------------------------------ device

    /** Stable per-installation id (generated once, persisted). */
    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    data class DeviceInfo(
        val deviceId: String,
        val deviceModel: String,
        val androidVersion: String,
        val appVersion: String,
        val appVersionCode: Int,
        val uptimeMillis: Long
    )

    fun deviceInfo(context: Context): DeviceInfo = DeviceInfo(
        deviceId = deviceId(context),
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        appVersion = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE,
        uptimeMillis = SystemClock.elapsedRealtime()
    )

    // ------------------------------------------------------------ payload (pure)

    /**
     * Map our free-form log levels onto the server's allowed literal set
     * (info | warn | error); anything unknown falls back to "info".
     */
    fun serverLevel(level: String): String = when (level.uppercase()) {
        "ERROR" -> "error"
        "WARN" -> "warn"
        else -> "info"
    }

    /**
     * Format epoch millis as an ISO-8601 UTC string. The server requires ts to
     * be a string; ISO-8601 keeps the captain's log viewer sortable/parseable.
     */
    fun isoTime(ts: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts
        return String.format(
            Locale.US,
            "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
            cal.get(Calendar.MILLISECOND)
        )
    }

    /**
     * Build the POST body. Pure function (no Android types) so it is unit-testable
     * on the JVM. Extra device fields beyond the contract are additive and harmless.
     */
    fun buildPayload(
        deviceId: String,
        appVersion: String,
        deviceModel: String,
        androidVersion: String,
        uptimeMillis: Long,
        entries: List<LogEntry>,
        tunnelStatus: String = "",
        chargerState: String = ""
    ): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"device_id\":").append(Json.str(deviceId)).append(',')
        sb.append("\"app_version\":").append(Json.str(appVersion)).append(',')
        sb.append("\"device_model\":").append(Json.str(deviceModel)).append(',')
        sb.append("\"android_version\":").append(Json.str(androidVersion)).append(',')
        sb.append("\"uptime_ms\":").append(uptimeMillis).append(',')
        sb.append("\"tunnel_status\":").append(Json.str(tunnelStatus)).append(',')
        sb.append("\"charger_state\":").append(Json.str(chargerState)).append(',')
        sb.append("\"entries\":[")
        for ((i, e) in entries.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"ts\":").append(Json.str(isoTime(e.ts)))
                .append(",\"level\":").append(Json.str(serverLevel(e.level)))
                .append(",\"msg\":").append(Json.str(e.msg)).append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Current sendable entries: persisted app log (includes mirrored charger BLE lines). */
    fun currentEntries(): List<LogEntry> = AppLog.snapshot()

    // ------------------------------------------------------------------ sending

    /** Send the buffered logs now (no rate limit). Never throws. */
    suspend fun sendLogs(context: Context, url: String = LOGS_URL): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (url.isBlank()) {
                return@withContext Result.failure(IllegalStateException("log server not configured"))
            }
            val info = deviceInfo(context)
            val payload = buildPayload(
                deviceId = info.deviceId,
                appVersion = info.appVersion,
                deviceModel = info.deviceModel,
                androidVersion = info.androidVersion,
                uptimeMillis = info.uptimeMillis,
                entries = currentEntries(),
                tunnelStatus = AppState.tunnelStatus,
                chargerState = AppState.chargerModeText
            )
            AppLog.flush()
            val body = httpPost(url, payload)
            Result.success(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fire-and-forget rate-limited auto-send. Call on app start and after
     * significant errors (BLE / charger / tunnel failures). No-op if the last
     * auto-send was under [AUTO_SEND_INTERVAL_MS] ago.
     */
    fun autoSend(context: Context) {
        scope.launch { tryAutoSend(context) }
    }

    /** @return true when a send was attempted (rate limit not hit). */
    suspend fun tryAutoSend(context: Context): Boolean {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        // Check + stamp under a lock: app start fires autoSend from both
        // MainActivity.onCreate and the service onCreate, and without this an
        // interleaving would let both pass the rate limit and double-POST.
        val allowed = synchronized(autoSendLock) {
            val last = p.getLong(KEY_LAST_AUTO_SEND, 0L)
            if (now - last < AUTO_SEND_INTERVAL_MS) {
                false
            } else {
                // Stamp BEFORE sending so a failed send still rate-limits hammering.
                p.edit().putLong(KEY_LAST_AUTO_SEND, now).apply()
                true
            }
        }
        if (!allowed) return false
        val result = sendLogs(context)
        if (result.isSuccess) {
            AppLog.i("Auto diagnostics sent (${currentEntries().size} entries)")
        } else {
            AppLog.w("Auto diagnostics send failed: ${result.exceptionOrNull()?.message}")
        }
        return true
    }

    // ------------------------------------------------------------------- http

    internal fun httpPost(url: String, json: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("Server returned HTTP $code: ${body.take(200)}")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
