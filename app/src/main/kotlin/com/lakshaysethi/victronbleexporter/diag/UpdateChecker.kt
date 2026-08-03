package com.lakshaysethi.victronbleexporter.diag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update check against the mppt-log-server:
 *   GET https://mppt.lak.nz/api/latest.json
 *     -> {"versionCode", "versionName", "apkUrl", "notes"}
 *
 * The versionCode comparison ([isNewer]) is a pure function so the decision is
 * unit-testable on the JVM.
 */
object UpdateChecker {

    const val LATEST_URL = "https://mppt.lak.nz/api/latest.json"
    const val DEFAULT_APK_URL = "https://mppt.lak.nz/apk/latest.apk"

    data class LatestRelease(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String?
    )

    /** Pure decision: is the served version newer than what we are running? */
    fun isNewer(servedVersionCode: Int, currentVersionCode: Int): Boolean =
        servedVersionCode > currentVersionCode

    /** Parse the latest.json body; null when malformed. */
    fun parseLatest(json: String): LatestRelease? {
        return try {
            val o = JSONObject(json)
            LatestRelease(
                versionCode = o.getInt("versionCode"),
                versionName = o.optString("versionName", "unknown"),
                apkUrl = o.optString("apkUrl", DEFAULT_APK_URL),
                notes = if (o.has("notes")) o.getString("notes") else null
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Fetch the latest release from the server; null when unreachable/malformed. */
    suspend fun fetchLatest(): LatestRelease? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode !in 200..299) null
                else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.let(::parseLatest)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
