package com.lakshaysethi.victronbleexporter.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class BootSafeStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(ChargerScheduleStore.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(RemoteChargerStore.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(ChargerScheduleStore.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(RemoteChargerStore.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `schedule survives a new store and is readable from device-protected prefs`() {
        ChargerScheduleStore(context).save(true, "07:15", "19:45", "aa:bb:cc:dd:ee:ff")

        val reloaded = ChargerScheduleStore(ApplicationProvider.getApplicationContext()).load()
        assertTrue(reloaded.scheduleEnabled)
        assertEquals(7 * 60 + 15, reloaded.enableMinutes)
        assertEquals(19 * 60 + 45, reloaded.disableMinutes)
        assertEquals("AA:BB:CC:DD:EE:FF", reloaded.chargerMac)

        val stored = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(ChargerScheduleStore.PREFS, Context.MODE_PRIVATE)
        assertTrue(stored.getBoolean(ChargerScheduleStore.KEY_SCHEDULE_ENABLED, false))
        assertEquals("07:15", stored.getString(ChargerScheduleStore.KEY_ENABLE_TIME, null))
    }

    @Test
    fun `remote settings survive a new store and are readable from device-protected prefs`() {
        RemoteChargerStore(context).save(true, "bridge-secret")

        val reloaded = RemoteChargerStore(ApplicationProvider.getApplicationContext()).load()
        assertTrue(reloaded.enabled)
        assertEquals("bridge-secret", reloaded.authSecret)

        val stored = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(RemoteChargerStore.PREFS, Context.MODE_PRIVATE)
        assertTrue(stored.getBoolean(RemoteChargerStore.KEY_ENABLED, false))
        assertEquals("bridge-secret", stored.getString(RemoteChargerStore.KEY_AUTH_SECRET, null))
    }

    @Test
    fun `legacy credential schedule migrates onto the device-protected store`() {
        context.getSharedPreferences(ChargerScheduleStore.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ChargerScheduleStore.KEY_SCHEDULE_ENABLED, true)
            .putString(ChargerScheduleStore.KEY_ENABLE_TIME, "09:00")
            .putString(ChargerScheduleStore.KEY_DISABLE_TIME, "17:00")
            .putString(ChargerScheduleStore.KEY_CHARGER_MAC, "11:22:33:44:55:66")
            .commit()

        val migrated = ChargerScheduleStore(context).load()
        assertTrue(migrated.scheduleEnabled)
        assertEquals(9 * 60, migrated.enableMinutes)
        assertEquals(17 * 60, migrated.disableMinutes)
        assertEquals("11:22:33:44:55:66", migrated.chargerMac)
        assertEquals(
            "09:00",
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(ChargerScheduleStore.PREFS, Context.MODE_PRIVATE)
                .getString(ChargerScheduleStore.KEY_ENABLE_TIME, null),
        )
    }

    @Test
    fun `empty store defaults to 07-45 window with schedule enabled`() {
        val loaded = ChargerScheduleStore(context).load()
        assertTrue(loaded.scheduleEnabled)
        assertEquals(7 * 60 + 45, loaded.enableMinutes)
        assertEquals(18 * 60, loaded.disableMinutes)
    }

    @Test
    fun `legacy 08-30 enable migrates to 07-45 on load and persists`() {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(ChargerScheduleStore.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ChargerScheduleStore.KEY_SCHEDULE_ENABLED, true)
            .putString(ChargerScheduleStore.KEY_ENABLE_TIME, "08:30")
            .putString(ChargerScheduleStore.KEY_DISABLE_TIME, "18:00")
            .commit()

        val loaded = ChargerScheduleStore(context).load()
        assertEquals(7 * 60 + 45, loaded.enableMinutes)
        assertEquals(
            "07:45",
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(ChargerScheduleStore.PREFS, Context.MODE_PRIVATE)
                .getString(ChargerScheduleStore.KEY_ENABLE_TIME, null),
        )
    }

    @Test
    fun `legacy 08-30 window with schedule disabled re-enables on load`() {
        // The real pre-#23 phone state: saving charger settings (to set the
        // MAC) persisted schedule_enabled=false alongside the 08:30 default.
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(ChargerScheduleStore.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ChargerScheduleStore.KEY_SCHEDULE_ENABLED, false)
            .putString(ChargerScheduleStore.KEY_ENABLE_TIME, "08:30")
            .putString(ChargerScheduleStore.KEY_DISABLE_TIME, "18:00")
            .commit()

        val loaded = ChargerScheduleStore(context).load()
        assertTrue(loaded.scheduleEnabled)
        assertEquals(7 * 60 + 45, loaded.enableMinutes)
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(ChargerScheduleStore.PREFS, Context.MODE_PRIVATE)
        assertEquals(true, prefs.getBoolean(ChargerScheduleStore.KEY_SCHEDULE_ENABLED, false))
        assertEquals("07:45", prefs.getString(ChargerScheduleStore.KEY_ENABLE_TIME, null))
    }

    @Test
    fun `legacy credential remote secret migrates onto the device-protected store`() {
        context.getSharedPreferences(RemoteChargerStore.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(RemoteChargerStore.KEY_ENABLED, true)
            .putString(RemoteChargerStore.KEY_AUTH_SECRET, "old-secret")
            .commit()

        val migrated = RemoteChargerStore(context).load()
        assertTrue(migrated.enabled)
        assertEquals("old-secret", migrated.authSecret)
        assertEquals(
            "old-secret",
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(RemoteChargerStore.PREFS, Context.MODE_PRIVATE)
                .getString(RemoteChargerStore.KEY_AUTH_SECRET, null),
        )
    }
}
