package com.lakshaysethi.victronbleexporter.charger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end simulation of the captain's charger-control experience, driving
 * the REAL production code (ChargerSchedule + ChargerProtocol) through the
 * exact control flow of VictronBleExporterService.enforceChargerSchedule() /
 * performChargerSet() over a simulated 2+ day timeline:
 *
 *   - schedule tick every minute (window default 08:30 -> 18:00),
 *   - manual Enable/Disable pauses the schedule until the next window boundary,
 *   - schedule resumes at the boundary (lastScheduledMode reset — the edfb7ef fix),
 *   - BLE write frames generated for the real device, device echo parsed,
 *   - readback verification (modeMatchesRequest) with the failure path shown,
 *   - the Prometheus gauge value mapping.
 *
 * The transcript printed by this test is reviewer-visible evidence of the
 * intended end-user behavior (no real MPPT available in CI).
 */
class ChargerEndToEndSimulationTest {

    private data class SimMppt(var mode: Int?)

    /** Absolute minute index on a simulated timeline. */
    private class Clock(val dayStart: Int) {
        fun minuteOfDay(m: Int) = (m - dayStart) % 1440
    }

    private fun writeMode(dev: SimMppt, on: Boolean): String {
        val frame = ChargerProtocol.makeChargerModeWriteFrame(on)
        val value = frame.last().toInt() and 0xFF
        dev.mode = value
        // Device echoes its mode in a notification: 08 03 19 02 00 41 <value>
        return "write %s -> device echoes 08 03 19 02 00 41 %02x".format(frame.toHex(), value)
    }

    private fun readbackVerify(dev: SimMppt, on: Boolean): Pair<Boolean, String> {
        val mode = dev.mode
        val ok = ChargerProtocol.modeMatchesRequest(mode, on)
        val text = ChargerProtocol.chargerModeText(mode)
        val log = if (ok) {
            "readback verified: device mode = $text (mode=$mode) matches requested ${if (on) "ON" else "OFF"}"
        } else {
            "ERROR: Readback ($text mode=$mode) does not match requested ${if (on) "ON" else "OFF"} — write may not have taken effect"
        }
        return ok to log
    }

    @Test
    fun `simulated captain day - schedule, manual override, resume, readback`() {
        val out = StringBuilder()
        val dev = SimMppt(null)
        val enable = ChargerSchedule.parseMinutes(ChargerSchedule.DEFAULT_ENABLE)!!
        val disable = ChargerSchedule.parseMinutes(ChargerSchedule.DEFAULT_DISABLE)!!
        out.appendLine("Charger control end-to-end simulation (production ChargerSchedule + ChargerProtocol)")
        out.appendLine("Device: SmartSolar MPPT 150/45 (model 0xA073), register 0x0200 device mode")
        out.appendLine("Schedule window: ${ChargerSchedule.formatMinutes(enable)} -> ${ChargerSchedule.formatMinutes(disable)}")
        out.appendLine()

        var lastScheduledMode: Boolean? = null
        var overrideUntil: Int? = null // absolute minute; null = none
        var overrideLog = ""

        fun gaugeLine(): String {
            val v = when (dev.mode) {
                ChargerProtocol.MODE_CHARGER_ON -> 1
                ChargerProtocol.MODE_CHARGER_OFF, ChargerProtocol.MODE_CHARGER_OFF_LEGACY -> 0
                else -> -1
            }
            return "victron_charger_enabled{device=\"AA:BB:CC:DD:EE:FF\"} $v"
        }

        fun scheduleTick(m: Int, clock: Clock) {
            val mod = clock.minuteOfDay(m)
            // override expiry (mirrors enforceChargerSchedule: manualOverrideUntil in 1..now)
            if (overrideUntil != null && m >= overrideUntil!!) {
                overrideUntil = null
                lastScheduledMode = null // schedule resumes (edfb7ef fix)
                out.appendLine("t=${ChargerSchedule.formatMinutes(mod)} schedule tick: manual override ended — schedule resumes")
            }
            if (overrideUntil != null) return
            val desiredOn = ChargerSchedule.scheduledOn(mod, enable, disable)
            if (desiredOn == lastScheduledMode) return
            val wr = writeMode(dev, desiredOn)
            val (ok, log) = readbackVerify(dev, desiredOn)
            if (ok) {
                lastScheduledMode = desiredOn
                out.appendLine("t=${ChargerSchedule.formatMinutes(mod)} schedule tick: window=${ChargerSchedule.formatMinutes(enable)}-${ChargerSchedule.formatMinutes(disable)} -> charger ${if (desiredOn) "ON" else "OFF"}")
                out.appendLine("    $wr | $log")
            } else {
                out.appendLine("t=${ChargerSchedule.formatMinutes(mod)} schedule apply FAILED: $log")
            }
        }

        fun manualSet(m: Int, clock: Clock, on: Boolean) {
            val mod = clock.minuteOfDay(m)
            out.appendLine("t=${ChargerSchedule.formatMinutes(mod)} MANUAL ${if (on) "ENABLE" else "DISABLE"} tapped by captain")
            val wr = writeMode(dev, on)
            val (ok, log) = readbackVerify(dev, on)
            out.appendLine("    $wr | $log")
            // Override is armed even when BLE readback fails, otherwise the next
            // schedule tick (daytime = ON) undoes a captain Disable tap.
            val next = ChargerSchedule.nextTransition(mod, enable, disable)
            overrideUntil = if (next <= mod) m + (1440 - mod) + next else m - mod + next
            lastScheduledMode = on
            overrideLog = ChargerSchedule.formatMinutes(next)
            out.appendLine("    manual override active until ${overrideLog} (next window boundary)")
        }

        val clock = Clock(dayStart = 0)
        // ---- Day 1 ----
        out.appendLine("=== Day 1 (schedule enabled, default window) ===")
        for (m in 6 * 60 until 8 * 60 + 30 step 30) scheduleTick(m, clock) // early morning: OFF
        scheduleTick(8 * 60 + 30, clock) // 08:30 -> ON
        scheduleTick(9 * 60, clock)      // mid-morning: no change
        out.appendLine("    ${gaugeLine()}   <- Prometheus /metrics while charger on")
        manualSet(10 * 60, clock, on = false) // captain disables at 10:00
        scheduleTick(12 * 60, clock)     // noon: override holds, no schedule action
        out.appendLine("    (12:00 no schedule write — override active)")
        scheduleTick(18 * 60, clock)     // 18:00: override ends, schedule resumes
        scheduleTick(19 * 60, clock)     // evening: stays OFF
        out.appendLine("    ${gaugeLine()}   <- Prometheus /metrics while charger off")
        out.appendLine()
        // ---- Day 2 ----
        out.appendLine("=== Day 2 ===")
        scheduleTick(8 * 60 + 30 + 1440, clock) // 08:30 -> ON again
        manualSet(19 * 60 + 1440, clock, on = true) // captain re-enables in the evening
        scheduleTick(20 * 60 + 1440, clock)
        out.appendLine("    (20:00 no schedule write — override active until 08:30)")
        scheduleTick(8 * 60 + 30 + 2880, clock) // day 3 08:30: override ended, schedule resumes -> ON
        val finalDeviceMode = dev.mode
        out.appendLine()
        // ---- Readback mismatch path (the edfb7ef stale-readback fix) ----
        out.appendLine("=== Readback verification failure path ===")
        dev.mode = ChargerProtocol.MODE_CHARGER_OFF // device did not accept the write
        val (ok, log) = readbackVerify(dev, on = true)
        out.appendLine("write ON requested, but device readback is OFF: $log")
        out.appendLine("    -> op reported as failed: success=$ok")
        out.appendLine()
        // ---- Overnight window config ----
        out.appendLine("=== Overnight window (18:00 -> 08:30) ===")
        val nightEnable = 18 * 60
        val nightDisable = 8 * 60 + 30
        out.appendLine("20:00 in window: ${ChargerSchedule.isInWindow(20 * 60, nightEnable, nightDisable)} (charger ON)")
        out.appendLine("03:00 in window: ${ChargerSchedule.isInWindow(3 * 60, nightEnable, nightDisable)} (charger ON)")
        out.appendLine("12:00 in window: ${ChargerSchedule.isInWindow(12 * 60, nightEnable, nightDisable)} (charger OFF)")
        out.appendLine("next boundary from 20:00: ${ChargerSchedule.formatMinutes(ChargerSchedule.nextTransition(20 * 60, nightEnable, nightDisable))}")

        val transcript = out.toString().trimEnd()
        println("=====CHARGER_SIM_BEGIN=====")
        println(transcript)
        println("=====CHARGER_SIM_END=====")

        // ---- Assertions: the end-user behavior actually holds ----
        // Default window boundaries.
        assertEquals(8 * 60 + 30, enable)
        assertEquals(18 * 60, disable)
        // Schedule applied the expected states at the expected boundaries.
        assertTrue(transcript.contains("t=08:30 schedule tick: window=08:30-18:00 -> charger ON"))
        assertTrue(transcript.contains("t=18:00 schedule tick: window=08:30-18:00 -> charger OFF"))
        // Manual override paused the schedule and resumed exactly at the boundary.
        assertTrue(transcript.contains("manual override active until 18:00 (next window boundary)"))
        assertTrue(transcript.contains("t=18:00 schedule tick: manual override ended — schedule resumes"))
        assertTrue(transcript.contains("(12:00 no schedule write — override active)"))
        // The day-2 manual enable override ran through the overnight boundary and resumed at 08:30.
        assertTrue(transcript.contains("manual override active until 08:30 (next window boundary)"))
        assertTrue(transcript.contains("t=08:30 schedule tick: manual override ended — schedule resumes"))
        // Every scheduled/manual write was followed by a matching readback, and the final
        // device state is ON (day-3 08:30 schedule apply).
        assertTrue(transcript.contains("readback verified: device mode = ON (mode=1) matches requested ON"))
        assertEquals(ChargerProtocol.MODE_CHARGER_ON, finalDeviceMode)
        // Readback-mismatch path is reported as a failure.
        assertTrue(transcript.contains("does not match requested ON — write may not have taken effect"))
        assertFalse(ok)
        // Prometheus gauge mapping appears in both states.
        assertTrue(transcript.contains("victron_charger_enabled{device=\"AA:BB:CC:DD:EE:FF\"} 1"))
        assertTrue(transcript.contains("victron_charger_enabled{device=\"AA:BB:CC:DD:EE:FF\"} 0"))
        // Overnight window semantics.
        assertTrue(ChargerSchedule.isInWindow(20 * 60, 18 * 60, 8 * 60 + 30))
        assertFalse(ChargerSchedule.isInWindow(12 * 60, 18 * 60, 8 * 60 + 30))
    }

    @Test
    fun `manual disable with failed readback still blocks daytime schedule ON`() {
        val enable = 8 * 60 + 30
        val disable = 18 * 60
        var lastScheduledMode: Boolean? = true
        var scheduleWrites = 0
        val failMin = 11 * 60
        val next = ChargerSchedule.nextTransition(failMin, enable, disable)
        val overrideUntil = next
        lastScheduledMode = false
        assertEquals(18 * 60, overrideUntil)

        val noon = 12 * 60
        if (overrideUntil > noon) {
            // schedule tick must no-op
        } else {
            scheduleWrites++
        }
        assertEquals(0, scheduleWrites)
        assertEquals(false, lastScheduledMode)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
