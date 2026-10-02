package com.riffbuddy

import com.riffbuddy.domain.Session
import com.riffbuddy.domain.SessionItem
import com.riffbuddy.domain.SessionMode
import com.riffbuddy.domain.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The stats match a hand-calculated fixture of 10 sessions exactly.
 *
 * The fixture below is written out in full, with the arithmetic in comments, so
 * the expected numbers are checkable by reading rather than by trusting the code
 * that produces them.
 */
class StatsTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    private val today: LocalDate = LocalDate.of(2026, 9, 20)   // a Sunday

    /** A session on [day], [minutes] long, starting at 18:00 local. */
    private fun session(
        id: Long,
        day: LocalDate,
        minutes: Long,
        items: List<SessionItem> = emptyList(),
    ): Session {
        val start = day.atTime(LocalTime.of(18, 0)).atZone(zone).toInstant()
        return Session(
            id = id,
            startedAt = start,
            endedAt = start.plusSeconds(minutes * 60),
            mode = if (items.isEmpty()) SessionMode.FREE else SessionMode.SONG,
            items = items.map { it.copy(sessionId = id) },
        )
    }

    private fun item(songId: Long, minutes: Int) =
        SessionItem(songId = songId, seconds = minutes * 60)

    /*
     * Ten sessions across twelve days. Song 1 is "the song being learned",
     * song 2 an occasional one.
     *
     *   Sep 9   20 min   (song 1: 20)
     *   Sep 10  30 min   (song 1: 20, song 2: 10)
     *   Sep 11   4 min   free            <- under the 5-minute streak floor
     *   Sep 14  45 min   (song 1: 30, song 2: 15)
     *   Sep 15  25 min   (song 2: 25)
     *   Sep 16  10 min   free
     *   Sep 17  60 min   (song 1: 45)    <- 15 min unassigned
     *   Sep 18  15 min   (song 1: 15)
     *   Sep 19  35 min   (song 1: 35)
     *   Sep 20  20 min   (song 2: 20)
     *
     * Totals by hand:
     *   all sessions      20+30+4+45+25+10+60+15+35+20 = 264 min
     *   song 1            20+20+30+45+15+35            = 165 min
     *   song 2            10+15+25+20                  =  70 min
     *   current streak    Sep 14..20, seven days        =   7
     *   longest streak    the same seven                =   7
     *   week to Sep 20    Sep 14..20 = 45+25+10+60+15+35+20 = 210 min
     */
    private val fixture: List<Session> = listOf(
        session(1, LocalDate.of(2026, 9, 9), 20, listOf(item(1, 20))),
        session(2, LocalDate.of(2026, 9, 10), 30, listOf(item(1, 20), item(2, 10))),
        session(3, LocalDate.of(2026, 9, 11), 4),
        session(4, LocalDate.of(2026, 9, 14), 45, listOf(item(1, 30), item(2, 15))),
        session(5, LocalDate.of(2026, 9, 15), 25, listOf(item(2, 25))),
        session(6, LocalDate.of(2026, 9, 16), 10),
        session(7, LocalDate.of(2026, 9, 17), 60, listOf(item(1, 45))),
        session(8, LocalDate.of(2026, 9, 18), 15, listOf(item(1, 15))),
        session(9, LocalDate.of(2026, 9, 19), 35, listOf(item(1, 35))),
        session(10, LocalDate.of(2026, 9, 20), 20, listOf(item(2, 20))),
    )

    private val now: Instant = today.atTime(LocalTime.of(23, 0)).atZone(zone).toInstant()

    @Test
    fun `total time matches the hand-calculated 264 minutes`() {
        assertEquals(264L * 60, Stats.totalSeconds(fixture, now))
    }

    @Test
    fun `each day is totalled separately`() {
        val days = Stats.dayTotals(fixture, zone, now)
        assertEquals(10, days.size)
        assertEquals(LocalDate.of(2026, 9, 9), days.first().date)
        assertEquals(20L * 60, days.first().seconds)
        assertEquals(4L * 60, days.first { it.date == LocalDate.of(2026, 9, 11) }.seconds)
    }

    @Test
    fun `time per song comes from items, not whole sessions`() {
        val totals = Stats.songTotals(fixture).associateBy { it.songId }
        assertEquals(165L * 60, totals.getValue(1).seconds)
        assertEquals(70L * 60, totals.getValue(2).seconds)
        // Sep 17 logged 60 minutes but assigned only 45 — the rest is nobody's.
        assertEquals(15L * 60, fixture.first { it.id == 7L }.unassignedSeconds(now))
    }

    @Test
    fun `last practiced is the most recent session for that song`() {
        val totals = Stats.songTotals(fixture).associateBy { it.songId }
        assertEquals(
            LocalDate.of(2026, 9, 19),
            totals.getValue(1).lastPracticed!!.atZone(zone).toLocalDate(),
        )
        assertEquals(
            LocalDate.of(2026, 9, 20),
            totals.getValue(2).lastPracticed!!.atZone(zone).toLocalDate(),
        )
    }

    /** Sep 14 to Sep 20 inclusive. Sep 11's four minutes do not qualify. */
    @Test
    fun `streaks match the fixture`() {
        assertEquals(7, Stats.currentStreak(fixture, zone, today, now = now))
        assertEquals(7, Stats.longestStreak(fixture, zone, now = now))
    }

    /**
     * A streak must not be declared broken at one minute past midnight, before
     * the new day has had any chance to happen.
     */
    @Test
    fun `yesterday still counts as an unbroken streak`() {
        val tomorrow = today.plusDays(1)
        assertEquals(7, Stats.currentStreak(fixture, zone, tomorrow, now = now))
        // But skipping a whole day does break it.
        assertEquals(0, Stats.currentStreak(fixture, zone, today.plusDays(2), now = now))
    }

    /**
     * A day under the floor does not *extend* a streak, but nor does it end one
     * while the day is still going.
     *
     * On Sep 11, having played four minutes, the Sep 9-10 run still stands at
     * two: yesterday qualified, and today has not failed — it has merely not
     * finished. Reporting zero there would tell the player their streak was dead at
     * teatime, when ten more minutes would have saved it. Once Sep 11 is over
     * and Sep 12 arrives with nothing, it is genuinely broken.
     */
    @Test
    fun `a short day neither extends nor immediately ends a streak`() {
        val early = fixture.filter { it.startedAt.atZone(zone).toLocalDate() <= LocalDate.of(2026, 9, 11) }

        assertEquals(
            "four minutes today should not kill yesterday's streak",
            2, Stats.currentStreak(early, zone, LocalDate.of(2026, 9, 11), now = now),
        )
        assertEquals(
            "by the next day the short one has failed",
            0, Stats.currentStreak(early, zone, LocalDate.of(2026, 9, 12), now = now),
        )
        assertEquals("the run itself was Sep 9-10", 2, Stats.longestStreak(early, zone, now = now))
    }

    @Test
    fun `the weekly ring covers the seven days ending today`() {
        assertEquals(210L * 60, Stats.secondsInWeekEnding(fixture, zone, today, now))
    }

    /** An unfinished session is live, not history: it must not be counted yet. */
    @Test
    fun `an open session is excluded from totals`() {
        val open = Session(id = 99, startedAt = now.minusSeconds(600))
        val withOpen = fixture + open
        assertEquals(264L * 60, Stats.totalSeconds(withOpen, now))
        assertTrue(open.isOpen)
        assertEquals(600L, open.durationSeconds(now))
    }

    @Test
    fun `no sessions means no streak and no crash`() {
        assertEquals(0, Stats.currentStreak(emptyList(), zone, today, now = now))
        assertEquals(0, Stats.longestStreak(emptyList(), zone, now = now))
        assertEquals(0L, Stats.totalSeconds(emptyList(), now))
        assertTrue(Stats.songTotals(emptyList()).isEmpty())
    }
}
