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
    fun `blank token clears the saved value and is not a device key`() {
        repo.saveDevice("AA:BB:CC:DD:EE:FF", "0123456789abcdef0123456789abcdef")
        repo.saveTunnelToken("keep-me")
        repo.saveTunnelToken("   ")
        assertNull(repo.getTunnelToken())
        assertFalse(repo.getAllDevices().containsKey("__TUNNEL_TOKEN__"))
        assertEquals(1, repo.getAllDevices().size)
    }
}
