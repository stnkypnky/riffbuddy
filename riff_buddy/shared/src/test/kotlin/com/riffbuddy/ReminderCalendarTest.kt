package com.riffbuddy

import com.riffbuddy.domain.ReminderCalendar
import com.riffbuddy.domain.ReminderSettings
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderCalendarTest {

    private val zone = TimeZone.of("America/New_York")
    // Tuesday 22 Sep 2026, 09:00.
    private val now = LocalDateTime.parse("2026-09-22T09:00").toInstant(zone)

    @Test
    fun `weekdays at 18 30, starting at the next one`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val ics = ReminderCalendar.ics(ReminderSettings(enabled = true, time = LocalTime(18, 30), days = days), now, zone)!!
        assertTrue(ics, ics.contains("DTSTART:20260923T183000\r\n"))
        assertTrue(ics, ics.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR\r\n"))
        assertTrue(ics, ics.contains("DTSTAMP:20260922T130000Z\r\n"))
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n") && ics.endsWith("END:VCALENDAR\r\n"))
    }

    @Test
    fun `nothing to export when the reminder is off`() {
        assertNull(ReminderCalendar.ics(ReminderSettings(enabled = false), now, zone))
        assertNull(ReminderCalendar.ics(ReminderSettings(enabled = true, days = emptySet()), now, zone))
    }
}
