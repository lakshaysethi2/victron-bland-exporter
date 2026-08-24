package com.lakshaysethi.victronbleexporter.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Parsing of the server's latest.json body (org.json is Android-only, so this
 * runs under Robolectric).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
// ConscryptMode OFF: Robolectric's bundled Conscrypt has no glibc-2.31-compatible aarch64 native
// on this arm64 Linux host (same pattern as TunnelUrlCopyShareTest).
@ConscryptMode(ConscryptMode.Mode.OFF)
class UpdateCheckerParseTest {

    @Test
    fun `parses a valid latest json`() {
        val release = UpdateChecker.parseLatest(
            """{"versionCode":2,"versionName":"0.2.0","apkUrl":"https://logs.example.com/apk/latest.apk","notes":"fixed charger enable"}"""
        )
        assertNotNull(release)
        assertEquals(2, release!!.versionCode)
        assertEquals("0.2.0", release.versionName)
        assertEquals("https://logs.example.com/apk/latest.apk", release.apkUrl)
        assertEquals("fixed charger enable", release.notes)
    }

    @Test
    fun `missing notes and apkUrl are tolerated`() {
        val release = UpdateChecker.parseLatest("""{"versionCode":3,"versionName":"0.3.0"}""")
        assertNotNull(release)
        assertNull(release!!.notes)
        assertEquals(UpdateChecker.DEFAULT_APK_URL, release.apkUrl)
    }

    @Test
    fun `relative apk path is resolved against the log server base`() {
        // The live server serves apkUrl as a relative path (e.g. "/apk/latest.apk").
        val release = UpdateChecker.parseLatest(
            """{"versionCode":2,"versionName":"0.2.0","apkUrl":"/apk/latest.apk"}"""
        )
        assertNotNull(release)
        assertEquals(UpdateChecker.resolveApkUrl("/apk/latest.apk"), release!!.apkUrl)
    }

    @Test
    fun `absolute apk url passes through unchanged`() {
        val release = UpdateChecker.parseLatest(
            """{"versionCode":2,"versionName":"0.2.0","apkUrl":"https://cdn.example.com/app.apk"}"""
        )
        assertNotNull(release)
        assertEquals("https://cdn.example.com/app.apk", release!!.apkUrl)
    }

    @Test
    fun `github release latest json is accepted`() {
        val release = UpdateChecker.parseLatest(
            """{"versionCode":2,"versionName":"0.2.0","apkUrl":"https://github.com/lakshaysethi2/victron-bland-exporter/releases/latest/download/victron-ble-exporter.apk","notes":"CI debug APK"}"""
        )
        assertNotNull(release)
        assertEquals(2, release!!.versionCode)
        assertEquals(
            "https://github.com/lakshaysethi2/victron-bland-exporter/releases/latest/download/victron-ble-exporter.apk",
            release.apkUrl
        )
    }

    @Test
    fun `dead log host still falls through to github`() {
        val github = """{"versionCode":2,"versionName":"0.2.0","apkUrl":"https://github.com/lakshaysethi2/victron-bland-exporter/releases/latest/download/victron-ble-exporter.apk"}"""
        val release = UpdateChecker.newestRelease(listOf(null, "not json", github))
        assertNotNull(release)
        assertEquals(2, release!!.versionCode)
        assertEquals(
            "https://github.com/lakshaysethi2/victron-bland-exporter/releases/latest/download/victron-ble-exporter.apk",
            release.apkUrl
        )
    }

    @Test
    fun `stale log host loses to a newer github release`() {
        val staleLog = """{"versionCode":1,"versionName":"0.1.0","apkUrl":"https://logs.example.com/apk/latest.apk"}"""
        val github = """{"versionCode":2,"versionName":"0.2.0","apkUrl":"https://github.com/lakshaysethi2/victron-bland-exporter/releases/latest/download/victron-ble-exporter.apk"}"""
        val release = UpdateChecker.newestRelease(listOf(staleLog, github))
        assertNotNull(release)
        assertEquals(2, release!!.versionCode)
        assertEquals(
            "https://github.com/lakshaysethi2/victron-bland-exporter/releases/latest/download/victron-ble-exporter.apk",
            release.apkUrl
        )
    }

    @Test
    fun `newer log host wins over an older github release`() {
        val log = """{"versionCode":3,"versionName":"0.3.0","apkUrl":"https://logs.example.com/apk/latest.apk"}"""
        val github = """{"versionCode":2,"versionName":"0.2.0","apkUrl":"https://github.com/lakshaysethi2/victron-bland-exporter/releases/latest/download/victron-ble-exporter.apk"}"""
        val release = UpdateChecker.newestRelease(listOf(log, github))
        assertNotNull(release)
        assertEquals(3, release!!.versionCode)
        assertEquals("https://logs.example.com/apk/latest.apk", release.apkUrl)
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(UpdateChecker.parseLatest("not json"))
        assertNull(UpdateChecker.parseLatest("""{"versionName":"0.2.0"}""")) // versionCode required
        assertNull(UpdateChecker.parseLatest(""))
    }
}
