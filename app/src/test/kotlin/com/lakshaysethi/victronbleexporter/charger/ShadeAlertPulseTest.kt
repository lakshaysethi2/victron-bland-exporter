package com.lakshaysethi.victronbleexporter.charger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeAlertPulseTest {
    @Test
    fun `explicit restart action`() {
        assertTrue(ShadeAlertPulse.shouldRestart("""{"action":"restart"}"""))
        assertFalse(ShadeAlertPulse.shouldRestart("""{"action":"on"}"""))
    }

    @Test
    fun `grafana firing shade-free alert`() {
        val body = """{"status":"firing","alerts":[{"status":"firing","labels":{"kind":"shade-free-expect","alertname":"Solar power below 500W (11:30-15:30 NZST)"}}]}"""
        assertTrue(ShadeAlertPulse.shouldRestart(body))
    }

    @Test
    fun `grafana resolved does not pulse`() {
        val body = """{"status":"resolved","alerts":[{"status":"resolved","labels":{"kind":"shade-free-expect"}}]}"""
        assertFalse(ShadeAlertPulse.shouldRestart(body))
    }

    @Test
    fun `cooldown blocks a second claim`() {
        ShadeAlertPulse.resetForTest()
        assertTrue(ShadeAlertPulse.tryClaim(1_000L, cooldownMs = 60_000L))
        assertFalse(ShadeAlertPulse.tryClaim(30_000L, cooldownMs = 60_000L))
        assertTrue(ShadeAlertPulse.tryClaim(61_000L, cooldownMs = 60_000L))
    }
}
