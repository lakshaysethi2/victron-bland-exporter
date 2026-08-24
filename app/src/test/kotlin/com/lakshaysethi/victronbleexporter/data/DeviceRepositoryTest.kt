package com.lakshaysethi.victronbleexporter.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DeviceRepositoryTest {

    private lateinit var repo: DeviceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repo = DeviceRepository(context)
        repo.clear()
    }

    @Test
    fun `named tunnel token survives a new repository instance`() {
        repo.saveTunnelToken("  eyJhbGciOi-named-tunnel-token  ")
        assertEquals("eyJhbGciOi-named-tunnel-token", repo.getTunnelToken())

        val reloaded = DeviceRepository(ApplicationProvider.getApplicationContext())
        assertEquals("eyJhbGciOi-named-tunnel-token", reloaded.getTunnelToken())
    }

    @Test
    fun `named tunnel token is written to device-protected prefs`() {
        repo.saveTunnelToken("boot-safe-token")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stored = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DeviceRepository.TOKEN_PREFS, Context.MODE_PRIVATE)
            .getString(DeviceRepository.KEY_TUNNEL_TOKEN, null)
        assertEquals("boot-safe-token", stored)
    }

    @Test
    fun `legacy credential token migrates onto the device-protected store`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repo.saveTunnelToken("legacy-token")
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DeviceRepository.TOKEN_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()

        val migrated = DeviceRepository(context).getTunnelToken()
        assertEquals("legacy-token", migrated)
        assertEquals(
            "legacy-token",
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(DeviceRepository.TOKEN_PREFS, Context.MODE_PRIVATE)
                .getString(DeviceRepository.KEY_TUNNEL_TOKEN, null),
        )
    }

    @Test
    fun `blank token clears the saved value and is not a device key`() {
        repo.saveDevice("AA:BB:CC:DD:EE:FF", "0123456789abcdef0123456789abcdef")
        repo.saveTunnelToken("keep-me")
        repo.saveTunnelToken("   ")
        assertNull(repo.getTunnelToken())
        assertFalse(repo.getAllDevices().containsKey("__TUNNEL_TOKEN__"))
        assertEquals(1, repo.getAllDevices().size)
    }

    @Test
    fun `instant readout key is written to device-protected prefs`() {
        repo.saveDevice("AA:BB:CC:DD:EE:FF", "0123456789abcdef0123456789abcdef")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stored = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DeviceRepository.KEY_PREFS, Context.MODE_PRIVATE)
            .getString("AA:BB:CC:DD:EE:FF", null)
        assertEquals("0123456789abcdef0123456789abcdef", stored)
    }

    @Test
    fun `device-protected keys survive empty credential store`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DeviceRepository.KEY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("AA:BB:CC:DD:EE:FF", "0123456789abcdef0123456789abcdef")
            .commit()

        val loaded = DeviceRepository(context)
        assertEquals("0123456789abcdef0123456789abcdef", loaded.getKey("AA:BB:CC:DD:EE:FF"))
        assertEquals(1, loaded.getAllDevices().size)
    }

    @Test
    fun `legacy credential keys migrate onto the device-protected store`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repo.saveDevice("AA:BB:CC:DD:EE:FF", "0123456789abcdef0123456789abcdef")
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DeviceRepository.KEY_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()

        val migrated = DeviceRepository(context)
        assertEquals("0123456789abcdef0123456789abcdef", migrated.getKey("AA:BB:CC:DD:EE:FF"))
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(DeviceRepository.KEY_PREFS, Context.MODE_PRIVATE)
                .getString("AA:BB:CC:DD:EE:FF", null),
        )
    }
}
