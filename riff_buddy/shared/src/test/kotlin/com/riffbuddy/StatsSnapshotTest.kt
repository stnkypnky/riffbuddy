package com.riffbuddy

import com.riffbuddy.domain.DayPart
import com.riffbuddy.domain.Milestones
import com.riffbuddy.domain.PartStage
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.SessionItem
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.SongPart
import com.riffbuddy.domain.SongStatus
import com.riffbuddy.domain.StatsRange
import com.riffbuddy.domain.StatsSnapshot
import com.riffbuddy.domain.music.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Every figure on the Stats tab, from fixture rows. */
class StatsSnapshotTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    // Tuesday 22 Sep 2026, 20:00 in LA.
    private val now = Instant.parse("2026-09-23T03:00:00Z")

    /** A closed session starting [daysAgo] days before [now] at [hourLocal], lasting [minutes]. */
    private fun session(id: Long, daysAgo: Long, hourLocal: Int, minutes: Long, vararg items: SessionItem, rating: Int? = null): Session {
        val day = now.atZone(zone).toLocalDate().minusDays(daysAgo)
        val start = day.atTime(hourLocal, 0).atZone(zone).toInstant()
        return Session(id = id, startedAt = start, endedAt = start.plusSeconds(minutes * 60), items = items.toList(), rating = rating)
    }

    private val floods = Song(id = 1, title = "Floods", key = "C# minor", tuningId = 5, status = SongStatus.LEARNING)
    private val wing = Song(id = 2, title = "Little Wing", key = "E minor", status = SongStatus.MASTERED,
        createdAt = Instant.parse("2026-09-01T00:00:00Z"), masteredAt = Instant.parse("2026-09-11T00:00:00Z"))
    private val tunings = listOf(Tuning(id = 5, name = "Drop C#", group = Tuning.STANDARD.group, midi = Tuning.STANDARD.midi))

    private val sessions = listOf(
        session(1, 0, 19, 30, SessionItem(songId = 1, seconds = 1200, bpmEnd = 80), rating = 4),     // today, evening
        session(2, 1, 8, 20, SessionItem(songId = 2), rating = 5),                                   // yesterday morning, untimed tag
        session(3, 2, 14, 10, SessionItem(songId = 1, bpmEnd = 96), SessionItem(songId = 2)),        // afternoon, two untimed songs
        session(4, 40, 23, 60, SessionItem(exerciseName = "Chromatic", bpmStart = 60, bpmEnd = 60)), // night, outside a month
    )

    private fun snap(range: StatsRange) = StatsSnapshot.of(
        range, zone, now, sessions, listOf(floods, wing),
        parts = listOf(SongPart(songId = 1, name = "Solo", stage = PartStage.MASTERED, masteredAt = now)),
        tunings = tunings, takeTimes = listOf(now, now.minusSeconds(90L * 86_400)),
    )

    @Test
    fun `time totals follow the range`() {
        assertEquals(60L * 60, snap(StatsRange.WEEK).totalSeconds)
        assertEquals(3, snap(StatsRange.WEEK).sessionCount)
        assertEquals(120L * 60, snap(StatsRange.ALL).totalSeconds)
        assertEquals(30L * 60, snap(StatsRange.WEEK).longestSeconds)
        assertEquals(LocalDate.of(2026, 9, 22), snap(StatsRange.WEEK).busiestDay!!.first)
    }

    @Test
    fun `untimed tags share what the session leaves over`() {
        val s = snap(StatsRange.WEEK)
        val bySong = s.topSongs.associate { it.label to it.seconds }
        // Floods: 1200 timed + half of session 3 (300). Little Wing: all of session 2 (1200) + half of 3 (300).
        assertEquals(1500L, bySong["Floods"])
        assertEquals(1500L, bySong["Little Wing"])
        // Session 1 keeps its untagged 10 minutes unattributed rather than inventing a song.
        assertEquals(1800L, StatsSnapshot.attribute(sessions[0], now).values.sum() + 600L)
    }

    @Test
    fun `streaks, time of day and weekdays`() {
        val s = snap(StatsRange.WEEK)
        assertEquals(3, s.currentStreak)
        assertEquals(1800L, s.dayParts[DayPart.EVENING])
        assertEquals(1200L, s.dayParts[DayPart.MORNING])
        assertEquals(600L, s.dayParts[DayPart.AFTERNOON])
        assertEquals(1800L, s.weekdays[DayOfWeek.TUESDAY])
        assertEquals(listOf(5, 4), s.ratings)
    }

    @Test
    fun `tempo climbs and the tuning and key shares`() {
        val s = snap(StatsRange.ALL)
        val floodsClimb = s.climbs.first { it.label == "Floods" }
        assertEquals(96, floodsClimb.first)          // session 3 was first in time
        assertEquals(96, floodsClimb.best)
        assertEquals(96, s.highestBpm)
        val t = s.tunings.associate { it.label to it.seconds }
        assertEquals(1500L, t["Drop C#"])
        assertEquals(1500L, t["Standard"])
        assertTrue(s.keys.any { it.label == "E minor" })
    }

    @Test
    fun `mastery figures`() {
        val s = snap(StatsRange.ALL)
        assertEquals(1, s.songsMastered)
        assertEquals(1, s.partsMastered)
        assertEquals(10.0, s.averageDaysToMaster!!, 0.001)
        assertEquals(1, s.statusCounts[SongStatus.LEARNING])
        assertEquals(2, s.takes)
        assertEquals(1, snap(StatsRange.WEEK).takes)
    }

    @Test
    fun `the heat map starts on a Monday and covers the range`() {
        val week = snap(StatsRange.WEEK)
        assertEquals(DayOfWeek.MONDAY, week.heatStart.dayOfWeek)
        assertTrue(week.heat.keys.all { !it.isBefore(week.heatStart) })
        assertEquals(1800L, week.heat[LocalDate.of(2026, 9, 22)])
    }

    @Test
    fun `milestones are earned from the all-time snapshot and only once`() {
        val all = snap(StatsRange.ALL)
        val earned = Milestones.newlyEarned(all, emptySet()).map { it.key }
        assertTrue("first_session" in earned)
        assertTrue("hours_1" in earned)
        assertTrue("first_part" in earned)
        assertTrue("first_song" in earned)
        assertTrue("streak_3" in earned)
        assertTrue("hours_10" !in earned)
        assertTrue(Milestones.newlyEarned(all, earned.toSet()).isEmpty())
        assertEquals(0.2f, Milestones.ALL.first { it.key == "hours_10" }.progress(all), 0.001f)
    }

    // ---- the expanded milestones ----------------------------------------------

    @Test
    fun `counts behind the new milestones`() {
        val s = snap(StatsRange.ALL)
        assertEquals(4, s.daysPracticed)
        assertEquals(1, s.dayPartSessions[DayPart.MORNING])
        assertEquals(1, s.dayPartSessions[DayPart.NIGHT])
        assertEquals(1, s.weekendSessions)                       // Sunday the 20th
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.THURSDAY), s.weekdaysPlayed)
        assertEquals(1, s.hourLongSessions)
        assertEquals(2, s.songsPracticed)
        assertEquals(2, s.songsInBook)
        // Floods went 96 then 80: its best is where it started, so no climb.
        assertEquals(0, s.climbsOf10)
        assertEquals("Floods", s.fastest!!.label)
        assertEquals(96, s.fastest!!.best)
    }

    @Test
    fun `the catalog hangs together`() {
        val keys = Milestones.ALL.map { it.key }
        assertEquals("duplicate keys", keys.size, keys.toSet().size)
        assertTrue("an original key went missing", keys.containsAll(Milestones.FIRST_CATALOG))
        Milestones.ALL.groupBy { it.series }.forEach { (series, tiers) ->
            val targets = tiers.map { it.target }
            assertEquals("$series tiers out of order", targets.sorted().distinct(), targets)
            assertEquals("$series spans groups", 1, tiers.map { it.group }.toSet().size)
        }
        // Every tooltip line works on no data at all as well as on some.
        val empty = StatsSnapshot.of(StatsRange.ALL, zone, now, emptyList(), emptyList(), emptyList(), tunings)
        listOf(empty, snap(StatsRange.ALL)).forEach { s -> Milestones.ALL.forEach { it.detail(s); it.standing(s) } }
    }

    @Test
    fun `next up is what's earned plus one tier each`() {
        val all = snap(StatsRange.ALL)
        val shown = Milestones.nextUp(all).map { it.key }
        assertTrue("first_session" in shown && "sessions_10" in shown && "sessions_50" !in shown)
        assertTrue("hours_1" in shown && "hours_5" in shown && "hours_10" !in shown)
        assertTrue("bpm_100" in shown && "bpm_120" !in shown)
        assertEquals(Milestones.ALL.map { it.series }.toSet().size + Milestones.ALL.count { it.earned(all) }
            - Milestones.ALL.groupBy { it.series }.values.count { tiers -> tiers.all { it.earned(all) } }, shown.size)
    }

    @Test
    fun `where you stand, in words`() {
        val all = snap(StatsRange.ALL)
        assertEquals("Now 96 BPM · Goal 100 BPM", Milestones.ALL.first { it.key == "bpm_100" }.standing(all))
        assertEquals("Goal 1 h · reached", Milestones.ALL.first { it.key == "long_1" }.standing(all))
        val streak = Milestones.ALL.first { it.key == "streak_7" }.standing(all)
        assertEquals("Now ${all.bestStreak} days · Goal 7 days", streak)
    }

    @Test
    fun `milestones added to the catalog arrive quietly`() {
        val all = snap(StatsRange.ALL)
        val fresh = Milestones.newlyEarned(all, emptySet())
        val celebrate = Milestones.toCelebrate(fresh, Milestones.FIRST_CATALOG).map { it.key }
        assertTrue("first_session" in celebrate)          // an original: celebrated as before
        assertTrue("days_7" !in celebrate)                // not earned yet anyway
        assertTrue("long_1" in fresh.map { it.key })      // new and already earned…
        assertTrue("long_1" !in celebrate)                // …so recorded without a banner
    }

    @Test
    fun `59 minutes shows as nearly an hour, not nothing`() {
        // 59 minutes logged used to leave the First hour ring empty.
        val s = StatsSnapshot.of(StatsRange.ALL, zone, now, listOf(session(1, 0, 19, 59)), emptyList(), emptyList(), tunings)
        val firstHour = Milestones.ALL.first { it.key == "hours_1" }
        assertEquals(59f / 60, firstHour.progress(s), 0.001f)
        assertEquals("Now 59 min · Goal 1 h", firstHour.standing(s))
        assertEquals("Now 59 min · Goal 5 h", Milestones.ALL.first { it.key == "hours_5" }.standing(s))
        assertEquals(59f / 60, Milestones.ALL.first { it.key == "long_1" }.progress(s), 0.001f)
        assertTrue(!firstHour.earned(s))
    }
}
