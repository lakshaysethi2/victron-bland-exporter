package com.lakshaysethi.victronbleexporter.charger

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargerScheduleTest {

    @Test
    fun `defaults are 08-30 and 18-00`() {
        assertEquals(8 * 60 + 30, ChargerSchedule.parseMinutes(ChargerSchedule.DEFAULT_ENABLE))
        assertEquals(18 * 60, ChargerSchedule.parseMinutes(ChargerSchedule.DEFAULT_DISABLE))
    }

    @Test
    fun `parses valid times`() {
        assertEquals(0, ChargerSchedule.parseMinutes("00:00"))
        assertEquals(23 * 60 + 59, ChargerSchedule.parseMinutes("23:59"))
        assertEquals(9 * 60 + 5, ChargerSchedule.parseMinutes("09:05"))
    }

    @Test
    fun `rejects malformed times`() {
        assertNull(ChargerSchedule.parseMinutes("25:00"))
        assertNull(ChargerSchedule.parseMinutes("08:60"))
        assertNull(ChargerSchedule.parseMinutes("830"))
        assertNull(ChargerSchedule.parseMinutes(null))
        assertFalse(ChargerSchedule.isValidTime("24:00"))
        assertTrue(ChargerSchedule.isValidTime("08:30"))
    }

    @Test
    fun `daytime window 08-30 to 18-00`() {
        // 08:30 exactly -> in window (charger ON)
        assertTrue(ChargerSchedule.isInWindow(8 * 60 + 30, 8 * 60 + 30, 18 * 60))
        // 17:59 -> in window
        assertTrue(ChargerSchedule.isInWindow(17 * 60 + 59, 8 * 60 + 30, 18 * 60))
        // 18:00 -> out (disable boundary exclusive)
        assertFalse(ChargerSchedule.isInWindow(18 * 60, 8 * 60 + 30, 18 * 60))
        // 08:29 -> out
        assertFalse(ChargerSchedule.isInWindow(8 * 60 + 29, 8 * 60 + 30, 18 * 60))
        // midnight -> out
        assertFalse(ChargerSchedule.isInWindow(0, 8 * 60 + 30, 18 * 60))
    }

    @Test
    fun `overnight window 18-00 to 08-30`() {
        assertTrue(ChargerSchedule.isInWindow(20 * 60, 18 * 60, 8 * 60 + 30))
        assertTrue(ChargerSchedule.isInWindow(3 * 60, 18 * 60, 8 * 60 + 30))
        assertTrue(ChargerSchedule.isInWindow(8 * 60 + 29, 18 * 60, 8 * 60 + 30))
        assertFalse(ChargerSchedule.isInWindow(12 * 60, 18 * 60, 8 * 60 + 30))
    }

    @Test
    fun `degenerate equal times means always on`() {
        assertTrue(ChargerSchedule.isInWindow(12 * 60, 8 * 60, 8 * 60))
        assertTrue(ChargerSchedule.isInWindow(0, 8 * 60, 8 * 60))
        assertTrue(ChargerSchedule.scheduledOn(23 * 60 + 59, 8 * 60, 8 * 60))
    }

    @Test
    fun `next transition is the nearest future boundary`() {
        // 08:30/18:00 window, now 10:00 -> next is 18:00
        assertEquals(18 * 60, ChargerSchedule.nextTransition(10 * 60, 8 * 60 + 30, 18 * 60))
        // now 19:00 -> next is 08:30 (tomorrow morning)
        assertEquals(8 * 60 + 30, ChargerSchedule.nextTransition(19 * 60, 8 * 60 + 30, 18 * 60))
        // now 08:00 -> next is 08:30
        assertEquals(8 * 60 + 30, ChargerSchedule.nextTransition(8 * 60, 8 * 60 + 30, 18 * 60))
        // exactly at boundary 08:30 -> strictly after, so 18:00
        assertEquals(18 * 60, ChargerSchedule.nextTransition(8 * 60 + 30, 8 * 60 + 30, 18 * 60))
        // overnight window, now 20:00 -> next is 08:30
        assertEquals(8 * 60 + 30, ChargerSchedule.nextTransition(20 * 60, 18 * 60, 8 * 60 + 30))
        // overnight window, now 06:00 -> next is 08:30 (disable boundary)
        assertEquals(8 * 60 + 30, ChargerSchedule.nextTransition(6 * 60, 18 * 60, 8 * 60 + 30))
    }

    @Test
    fun `scheduledOn follows the window`() {
        assertTrue(ChargerSchedule.scheduledOn(9 * 60, 8 * 60 + 30, 18 * 60))
        assertFalse(ChargerSchedule.scheduledOn(19 * 60, 8 * 60 + 30, 18 * 60))
        assertTrue(ChargerSchedule.scheduledOn(19 * 60, 18 * 60, 8 * 60 + 30))
    }

    @Test
    fun `phoneClock uses the calendar hour and zone id`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Pacific/Auckland"))
        cal.set(2026, Calendar.AUGUST, 24, 15, 42, 0)
        val (time, zone) = ChargerSchedule.phoneClock(cal)
        assertEquals("15:42", time)
        assertEquals("Pacific/Auckland", zone)
    }

    @Test
    fun `formatMinutes wraps and zero-pads`() {
        assertEquals("08:30", ChargerSchedule.formatMinutes(8 * 60 + 30))
        assertEquals("00:00", ChargerSchedule.formatMinutes(0))
        assertEquals("00:00", ChargerSchedule.formatMinutes(1440)) // wraps
        assertEquals("23:59", ChargerSchedule.formatMinutes(-1))   // negative wraps backwards
        assertEquals("09:05", ChargerSchedule.formatMinutes(9 * 60 + 5))
    }
}
