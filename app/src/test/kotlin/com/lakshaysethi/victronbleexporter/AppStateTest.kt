package com.lakshaysethi.victronbleexporter

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateTest {

    @Test
    fun `panel voltage backoff doubles per failure and caps at 15 minutes`() {
        assertEquals(1 * 60_000L, AppState.panelVoltageBackoffMs(0))
        assertEquals(2 * 60_000L, AppState.panelVoltageBackoffMs(1))
        assertEquals(4 * 60_000L, AppState.panelVoltageBackoffMs(2))
        assertEquals(8 * 60_000L, AppState.panelVoltageBackoffMs(3))
        assertEquals(15 * 60_000L, AppState.panelVoltageBackoffMs(4))
        assertEquals(15 * 60_000L, AppState.panelVoltageBackoffMs(99))
    }
}
