package com.riffbuddy.domain

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The practice log as plain data. No platform, no database — so every number
 * the Home card and the Log screen show can be checked on the JVM against a
 * fixture worked out by hand.
 */

/** What a session was about. */
enum class SessionMode { FREE, SONG, ROUTINE }

/**
 * One thing worked on inside a session: a song or a named exercise.
 *
 * [bpmStart] and [bpmEnd] are what make the per-song tempo chart possible, and
 * they are the reason a speed-trainer run is worth logging at all.
 */
data class SessionItem(
    val id: Long = 0,
    val sessionId: Long = 0,
    val songId: Long? = null,
    val exerciseName: String? = null,
    val seconds: Int = 0,
    val bpmStart: Int? = null,
    val bpmEnd: Int? = null,
    val notes: String = "",
    /** The part of the song this time went on, when it was tagged. */
    val partId: Long? = null,
)

data class Session(
    val id: Long = 0,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val mode: SessionMode = SessionMode.FREE,
    val rating: Int? = null,
    val notes: String = "",
    val items: List<SessionItem> = emptyList(),
    /** Seconds paused so far, not counting a pause in progress. */
    val pausedSeconds: Long = 0,
    /** When the current pause began; null when not paused. */
    val pausedAt: Instant? = null,
) {
    /** Still running — which is how the app spots a session to offer to recover. */
    val isOpen: Boolean get() = endedAt == null

    val isPaused: Boolean get() = isOpen && pausedAt != null

    /**
     * Length from the two timestamps, never from a counter.
     *
     * The timer has to stay right with the screen locked or the app
     * backgrounded. A counter ticking in the app would simply stop; the clock
     * does not, so the difference of two instants is always the truth.
     */
    fun durationSeconds(now: Instant = Clock.System.now()): Long {
        // A paused session stops at the pause; paused time is never practice.
        val until = endedAt ?: pausedAt ?: now
        return ((until - startedAt).inWholeSeconds - pausedSeconds).coerceAtLeast(0)
    }

    /** Seconds accounted for by items; the rest is shown as unassigned. */
    val assignedSeconds: Int get() = items.sumOf { it.seconds }

    fun unassignedSeconds(now: Instant = Clock.System.now()): Long =
        (durationSeconds(now) - assignedSeconds).coerceAtLeast(0)

    fun localDate(zone: TimeZone): LocalDate = startedAt.localDate(zone)
}

/** A day's total, for the week strip and the streak. */
data class DayTotal(val date: LocalDate, val seconds: Long, val sessions: Int)

data class BpmPoint(val at: Instant, val bpm: Int)

data class SongTotal(val songId: Long, val seconds: Long, val lastPracticed: Instant?)

/**
 * Everything the Home card and the Log screen show.
 *
 * All of it is derived on demand rather than stored. A stored total is a total
 * that can disagree with the sessions it came from — and sessions can be
 * edited and deleted after the fact, so disagreement would be a matter of time.
 */
object Stats {

    /** Minimum practice for a day to count toward a streak. Configurable. */
    const val DEFAULT_STREAK_MINUTES = 5

    fun dayTotals(sessions: List<Session>, zone: TimeZone, now: Instant = Clock.System.now()): List<DayTotal> =
        sessions
            .filter { !it.isOpen }
            .groupBy { it.localDate(zone) }
            .map { (date, daily) ->
                DayTotal(date, daily.sumOf { it.durationSeconds(now) }, daily.size)
            }
            .sortedBy { it.date }

    fun totalSeconds(sessions: List<Session>, now: Instant = Clock.System.now()): Long =
        sessions.filter { !it.isOpen }.sumOf { it.durationSeconds(now) }

    /**
     * Time per song, summed from session items.
     *
     * Counted from items rather than from whole sessions: a session covering
     * three songs must not credit all of its minutes to each of them.
     */
    fun songTotals(sessions: List<Session>): List<SongTotal> {
        val seconds = mutableMapOf<Long, Long>()
        val lastPracticed = mutableMapOf<Long, Instant>()

        sessions.filter { !it.isOpen }.forEach { session ->
            session.items.forEach { item ->
                val songId = item.songId ?: return@forEach
                seconds[songId] = (seconds[songId] ?: 0L) + item.seconds
                val previous = lastPracticed[songId]
                if (previous == null || session.startedAt > previous) {
                    lastPracticed[songId] = session.startedAt
                }
            }
        }

        return seconds.map { (songId, total) ->
            SongTotal(songId, total, lastPracticed[songId])
        }.sortedByDescending { it.seconds }
    }

    /**
     * Consecutive days up to and including today that met the goal.
     *
     * Yesterday is allowed to be the most recent qualifying day: a streak should
     * not be declared broken at one minute past midnight, before the day it is
     * counting has had any chance to happen.
     */
    fun currentStreak(
        sessions: List<Session>,
        zone: TimeZone,
        today: LocalDate,
        minimumMinutes: Int = DEFAULT_STREAK_MINUTES,
        now: Instant = Clock.System.now(),
    ): Int {
        val qualifying = qualifyingDays(sessions, zone, minimumMinutes, now)
        if (qualifying.isEmpty()) return 0
        var day = when {
            today in qualifying -> today
            today.minusDays(1) in qualifying -> today.minusDays(1)
            else -> return 0
        }
        var count = 0
        while (day in qualifying) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    /** The longest run of qualifying days ever recorded. */
    fun longestStreak(
        sessions: List<Session>,
        zone: TimeZone,
        minimumMinutes: Int = DEFAULT_STREAK_MINUTES,
        now: Instant = Clock.System.now(),
    ): Int {
        val days = qualifyingDays(sessions, zone, minimumMinutes, now).sorted()
        if (days.isEmpty()) return 0
        var best = 1
        var run = 1
        for (i in 1 until days.size) {
            run = if (days[i - 1].plusDays(1) == days[i]) run + 1 else 1
            best = maxOf(best, run)
        }
        return best
    }

    private fun qualifyingDays(
        sessions: List<Session>,
        zone: TimeZone,
        minimumMinutes: Int,
        now: Instant,
    ): Set<LocalDate> =
        dayTotals(sessions, zone, now)
            .filter { it.seconds >= minimumMinutes * 60L }
            .map { it.date }
            .toSet()

    /**
     * A song's tempo over time, for the Log's line chart: one point per
     * session that recorded a tempo for it, the fastest it reached that session.
     *
     * The fastest, not the last: a speed-trainer run often ends by dropping
     * back to consolidate, and the chart is asking "how fast can I play it".
     */
    fun bpmHistory(sessions: List<Session>, songId: Long): List<BpmPoint> =
        sessions.filter { !it.isOpen }.mapNotNull { session ->
            session.items
                .filter { it.songId == songId }
                .mapNotNull { maxOfOrNull(it.bpmStart, it.bpmEnd) }
                .maxOrNull()
                ?.let { BpmPoint(session.startedAt, it) }
        }.sortedBy { it.at }

    private fun maxOfOrNull(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    /** Songs with at least two tempo points — one point is not a line. */
    fun songsWithBpmHistory(sessions: List<Session>): List<Long> =
        sessions.flatMap { s -> s.items.filter { it.bpmStart != null || it.bpmEnd != null }.mapNotNull { it.songId } }
            .distinct()
            .filter { bpmHistory(sessions, it).size >= 2 }

    /** Minutes in the seven days ending [today], for the weekly-goal ring. */
    fun secondsInWeekEnding(
        sessions: List<Session>,
        zone: TimeZone,
        today: LocalDate,
        now: Instant = Clock.System.now(),
    ): Long {
        val first = today.minusDays(6)
        return dayTotals(sessions, zone, now)
            .filter { it.date >= first && it.date <= today }
            .sumOf { it.seconds }
    }
}
