package com.riffbuddy

import com.riffbuddy.domain.PracticeSettings
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.SessionItem
import com.riffbuddy.domain.Stats
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** The settings round-trip, the configurable streak, and the BPM line. */
class PracticeSettingsTest {

    @Test
    fun `settings survive a round trip through the settings table`() {
        val s = PracticeSettings(a4Hz = 432.5, weeklyGoalMinutes = 150, streakMinutes = 10, keepAwake = false)
        val stored = s.toStored()
        assertEquals(s, PracticeSettings.fromStored { stored[it] })
    }

    @Test
    fun `a whole-hertz reference stored by an older build still reads`() {
        assertEquals(442.0, PracticeSettings.fromStored { if (it == "a4_hz") "442" else null }.a4Hz, 0.0)
        // And a whole number is written back the old way, so an older build can read it.
        assertEquals("442", PracticeSettings(a4Hz = 442.0).toStored()["a4_hz"])
        assertEquals("441.5", PracticeSettings(a4Hz = 441.5).toStored()["a4_hz"])
    }

    @Test
    fun `no goal round-trips as no goal`() {
        val stored = PracticeSettings(weeklyGoalMinutes = null).toStored()
        assertEquals(null, PracticeSettings.fromStored { stored[it] }.weeklyGoalMinutes)
    }

    @Test
    fun `nothing stored, or nonsense stored, gives the defaults`() {
        assertEquals(PracticeSettings(), PracticeSettings.fromStored { null })
        val junk = mapOf("a4_hz" to "880", "streak_minutes" to "-3", "keep_awake" to "maybe", "weekly_goal_minutes" to "x")
        assertEquals(PracticeSettings(), PracticeSettings.fromStored { junk[it] })
    }

    private val zone = TimeZone.of("UTC")
    private fun day(d: Int, minutes: Long, vararg items: SessionItem): Session {
        val start = Instant.parse("2026-09-%02dT18:00:00Z".format(d))
        return Session(startedAt = start, endedAt = (start + (minutes * 60).seconds), items = items.toList())
    }

    @Test
    fun `the streak threshold is configurable`() {
        // 8 minutes each on the 19th and 20th.
        val sessions = listOf(day(19, 8), day(20, 8))
        val today = LocalDate(2026, 9, 20)
        assertEquals(2, Stats.currentStreak(sessions, zone, today, minimumMinutes = 5))
        assertEquals(0, Stats.currentStreak(sessions, zone, today, minimumMinutes = 10))
    }

    @Test
    fun `the tempo line takes each session's fastest, in date order`() {
        val sessions = listOf(
            day(20, 20, SessionItem(songId = 1, bpmStart = 80, bpmEnd = 96), SessionItem(songId = 1, bpmEnd = 88)),
            day(18, 20, SessionItem(songId = 1, bpmStart = 70, bpmEnd = 84)),
            day(19, 20, SessionItem(songId = 2, bpmEnd = 120)),       // another song
            day(17, 20, SessionItem(songId = 1)),                     // no tempo recorded
        )
        val line = Stats.bpmHistory(sessions, 1).map { it.at.toString().substring(8, 10) to it.bpm }
        assertEquals(listOf("18" to 84, "20" to 96), line)
        assertEquals(listOf(1L), Stats.songsWithBpmHistory(sessions))
    }
}
