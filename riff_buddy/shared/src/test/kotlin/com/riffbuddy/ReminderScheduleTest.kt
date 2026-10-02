package com.riffbuddy

import com.riffbuddy.domain.ReminderSchedule
import com.riffbuddy.domain.ReminderSettings
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderScheduleTest {

    private val zone = ZoneId.of("America/New_York")
    private fun at(text: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone)
    private val daily = ReminderSettings(enabled = true, time = LocalTime.of(18, 30))

    @Test
    fun `later today when the time has not passed`() {
        assertEquals(at("2026-09-22T18:30"), ReminderSchedule.nextFire(daily, at("2026-09-22T09:00")))
    }

    @Test
    fun `tomorrow once today's time has gone`() {
        assertEquals(at("2026-09-23T18:30"), ReminderSchedule.nextFire(daily, at("2026-09-22T18:30")))
    }

    @Test
    fun `only on the chosen days`() {
        val weekends = daily.copy(days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        // 22 Sep 2026 is a Tuesday.
        assertEquals(at("2026-09-26T18:30"), ReminderSchedule.nextFire(weekends, at("2026-09-22T12:00")))
    }

    /** Turning reminders off cancels all scheduled notifications. */
    @Test
    fun `off, or no days chosen, means nothing is scheduled`() {
        assertNull(ReminderSchedule.nextFire(daily.copy(enabled = false), at("2026-09-22T12:00")))
        assertNull(ReminderSchedule.nextFire(daily.copy(days = emptySet()), at("2026-09-22T12:00")))
    }

    /** The wall-clock time holds through the autumn change (New York, 1 Nov 2026). */
    @Test
    fun `18 30 stays 18 30 across the clocks going back`() {
        val next = ReminderSchedule.nextFire(daily, at("2026-10-31T19:00"))!!
        assertEquals(LocalTime.of(18, 30), next.toLocalTime())
        assertEquals(1, next.dayOfMonth)
    }

    /** A time the spring change skips (02:30 on 8 Mar 2026) comes just after, not never. */
    @Test
    fun `a skipped time comes at the first valid moment`() {
        val early = daily.copy(time = LocalTime.of(2, 30))
        val next = ReminderSchedule.nextFire(early, at("2026-03-08T00:00"))!!
        assertEquals(8, next.dayOfMonth)
        assertEquals(LocalTime.of(3, 30), next.toLocalTime())
    }

    /** No reminder fires if a session was already logged that day (configurable). */
    @Test
    fun `a day already practiced stays quiet, unless the player asked otherwise`() {
        assertFalse(ReminderSchedule.shouldShow(daily, practicedToday = true))
        assertTrue(ReminderSchedule.shouldShow(daily, practicedToday = false))
        assertTrue(ReminderSchedule.shouldShow(daily.copy(skipIfPracticed = false), practicedToday = true))
    }

    @Test
    fun `the backup nudge waits for 30 days, then comes at most weekly`() {
        val day = 86_400_000L
        val now = 100 * day
        assertFalse(ReminderSchedule.backupNudgeDue(now - 10 * day, null, now))
        assertTrue(ReminderSchedule.backupNudgeDue(now - 31 * day, null, now))
        assertFalse(ReminderSchedule.backupNudgeDue(now - 31 * day, now - 2 * day, now))
        assertTrue(ReminderSchedule.backupNudgeDue(now - 31 * day, now - 8 * day, now))
        assertTrue("never backed up counts as due", ReminderSchedule.backupNudgeDue(null, null, now))
    }

    @Test
    fun `days survive a round trip through the settings table`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        assertEquals(days, ReminderSettings.decodeDays(daily.copy(days = days).encodeDays()))
    }
}
