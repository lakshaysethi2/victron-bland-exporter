package com.lakshaysethi.victronbleexporter.charger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargerRestartCycleTest {

    @Test
    fun `not due until 30 minutes after start`() {
        val start = 1_000_000L
        assertFalse(ChargerRestartCycle.due(start + 29 * 60_000L, 0L, start))
        assertTrue(ChargerRestartCycle.due(start + 30 * 60_000L, 0L, start))
    }

    @Test
    fun `uses last restart not process start`() {
        val start = 1_000_000L
        val last = start + 10 * 60_000L
        assertFalse(ChargerRestartCycle.due(last + 29 * 60_000L, last, start))
        assertTrue(ChargerRestartCycle.due(last + 30 * 60_000L, last, start))
    }

    @Test
    fun `schedule off window blocks pulse`() {
        val noon = 12 * 60
        assertTrue(
            ChargerRestartCycle.allowedBySchedule(
                scheduleEnabled = true,
                nowMinutes = noon,
                enableMinutes = 7 * 60 + 45,
                disableMinutes = 18 * 60,
                manualOverrideUntil = 0L,
                nowMs = 1L,
            ),
        )
        assertFalse(
            ChargerRestartCycle.allowedBySchedule(
                scheduleEnabled = true,
                nowMinutes = 20 * 60,
                enableMinutes = 7 * 60 + 45,
                disableMinutes = 18 * 60,
                manualOverrideUntil = 0L,
                nowMs = 1L,
            ),
        )
    }

    @Test
    fun `manual override blocks pulse`() {
        assertFalse(
            ChargerRestartCycle.allowedBySchedule(
                scheduleEnabled = true,
                nowMinutes = 12 * 60,
                enableMinutes = 7 * 60 + 45,
                disableMinutes = 18 * 60,
                manualOverrideUntil = 50L,
                nowMs = 10L,
            ),
        )
    }
}
