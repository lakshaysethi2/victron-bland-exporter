package com.lakshaysethi.victronbleexporter.diag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Persistence of the diagnostics log buffer across process restarts
 * (SharedPreferences backed), verified via Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
// ConscryptMode OFF: Robolectric's bundled Conscrypt has no glibc-2.31-compatible aarch64 native
// on this arm64 Linux host (same pattern as TunnelUrlCopyShareTest).
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppLogPersistenceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `entries survive a simulated restart`() {
        AppLog.init(context)
        AppLog.clear()
        AppLog.flush()

        AppLog.i("app booted")
        AppLog.e("ble scan failed")
        AppLog.flush()

        // Simulate process restart: re-read everything from disk.
        AppLog.reload()

        val snap = AppLog.snapshot()
        assertEquals(2, snap.size)
        assertEquals("INFO", snap[0].level)
        assertEquals("app booted", snap[0].msg)
        assertEquals("ERROR", snap[1].level)
        assertEquals("ble scan failed", snap[1].msg)

        AppLog.clear()
        AppLog.flush()
    }

    @Test
    fun `restored buffer still respects the 500-entry bound`() {
        AppLog.init(context)
        AppLog.clear()
        AppLog.flush()

        for (i in 1..600) {
            AppLog.i("line $i")
        }
        AppLog.flush()

        AppLog.reload()

        val snap = AppLog.snapshot()
        assertEquals(AppLog.MAX_ENTRIES, snap.size)
        assertEquals("line 101", snap.first().msg)
        assertEquals("line 600", snap.last().msg)

        AppLog.clear()
        AppLog.flush()
    }

    @Test
    fun `clear persists an empty buffer`() {
        AppLog.init(context)
        AppLog.i("will be cleared")
        AppLog.flush()

        AppLog.clear()
        AppLog.flush()
        AppLog.reload()

        assertEquals(0, AppLog.snapshot().size)
    }
}
