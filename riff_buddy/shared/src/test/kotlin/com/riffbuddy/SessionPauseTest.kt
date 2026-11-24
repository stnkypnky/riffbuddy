package com.riffbuddy

import com.riffbuddy.data.PracticeController
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.Stats
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pausing stops the clock, and an edited session moves the stats. */
class SessionPauseTest {

    private val t0 = Instant.parse("2026-09-20T01:00:00Z")   // 18:00 PDT, the 19th
    private val zone = TimeZone.of("America/Los_Angeles")

    /** A font-size change once asked about the live session as if it were left over. */
    @Test
    fun `the running session is never offered for recovery`() {
        val open = Session(id = 7, startedAt = t0)
        assertFalse(PracticeController.needsAsking(open, running = open))
        assertTrue("a cold start asks", PracticeController.needsAsking(open, running = null))
        assertTrue(PracticeController.needsAsking(open, running = open.copy(id = 6)))
        assertFalse(PracticeController.needsAsking(null, running = null))
    }

    @Test
    fun `a pause in progress freezes the clock at the pause`() {
        val s = Session(startedAt = t0, pausedAt = (t0 + (600).seconds))
        assertTrue(s.isPaused)
        // An hour later the timer still reads ten minutes.
        assertEquals(600, s.durationSeconds(now = (t0 + (4200).seconds)))
    }

    @Test
    fun `paused time already taken is subtracted once resumed`() {
        // 30 minutes elapsed, 8 of them paused.
        val s = Session(startedAt = t0, pausedSeconds = 480)
        assertFalse(s.isPaused)
        assertEquals(22 * 60L, s.durationSeconds(now = (t0 + (1800).seconds)))
    }

    @Test
    fun `a finished session subtracts its pauses too`() {
        val s = Session(startedAt = t0, endedAt = (t0 + (1800).seconds), pausedSeconds = 300)
        assertEquals(25 * 60L, s.durationSeconds())
        assertFalse("a finished session is never paused", s.copy(pausedAt = t0).isPaused)
    }

    @Test
    fun `the stats follow an edit that moves a session to another day`() {
        val now = (t0 + (86_400 * 2).seconds)
        val original = Session(id = 1, startedAt = t0, endedAt = (t0 + (20 * 60).seconds))
        // What SessionRepo.update writes: a new start, end = start + length,
        // and the pause cleared.
        val newStart = (t0 - (86_400).seconds)
        val edited = original.copy(startedAt = newStart, endedAt = (newStart + (45 * 60).seconds), pausedSeconds = 0)

        val before = Stats.dayTotals(listOf(original), zone, now).associate { it.date to it.seconds }
        val after = Stats.dayTotals(listOf(edited), zone, now).associate { it.date to it.seconds }
        assertEquals(mapOf(LocalDate(2026, 9, 19) to 1200L), before)
        assertEquals(mapOf(LocalDate(2026, 9, 18) to 2700L), after)
        assertEquals(2700L, Stats.totalSeconds(listOf(edited), now))
    }

    @Test
    fun `a deleted session leaves the totals`() {
        val now = (t0 + (86_400).seconds)
        val a = Session(id = 1, startedAt = t0, endedAt = (t0 + (600).seconds))
        val b = Session(id = 2, startedAt = (t0 + (3600).seconds), endedAt = (t0 + (4800).seconds))
        assertEquals(1800L, Stats.totalSeconds(listOf(a, b), now))
        assertEquals(1200L, Stats.totalSeconds(listOf(a, b).filter { it.id != 1L }, now))
    }
}
