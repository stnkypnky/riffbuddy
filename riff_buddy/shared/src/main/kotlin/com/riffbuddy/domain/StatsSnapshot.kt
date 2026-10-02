package com.riffbuddy.domain

import com.riffbuddy.domain.music.Tuning
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The Stats tab's time ranges. */
enum class StatsRange(val label: String, val days: Long?) {
    WEEK("Week", 7), MONTH("Month", 30), YEAR("Year", 365), ALL("All", null);

    /** How many weeks the calendar heat map shows for this range. */
    val heatWeeks: Int get() = if (this == WEEK || this == MONTH) 12 else 52
}

/** Time spent on one thing: a song, a tuning, a key. */
data class Share(val label: String, val seconds: Long, val id: Long? = null)

/** A song's or exercise's tempo over time. */
data class TempoClimb(val label: String, val points: List<Pair<Instant, Int>>) {
    val first: Int get() = points.first().second
    val best: Int get() = points.maxOf { it.second }
    val climb: Int get() = best - first
}

/** Time of day, in the four parts a practicing day falls into. */
enum class DayPart(val label: String) { MORNING("Morning"), AFTERNOON("Afternoon"), EVENING("Evening"), NIGHT("Night") }

/**
 * Everything the Stats tab shows, worked out in one pass from the raw rows.
 * Pure Kotlin with no platform code in it, so every figure is tested on the
 * JVM and the screen only draws.
 */
data class StatsSnapshot(
    val range: StatsRange,
    // Time
    val totalSeconds: Long,
    val sessionCount: Int,
    val averageSeconds: Long,
    val longestSeconds: Long,
    val busiestDay: Pair<LocalDate, Long>?,
    val heat: Map<LocalDate, Long>,
    val heatStart: LocalDate,
    val today: LocalDate,
    val currentStreak: Int,
    val bestStreak: Int,
    // Songs and mastery
    val topSongs: List<Share>,
    val statusCounts: Map<SongStatus, Int>,
    val songsMastered: Int,
    val partsMastered: Int,
    val partsMasteredOverTime: List<Pair<LocalDate, Int>>,
    val averageDaysToMaster: Double?,
    // Tempo
    val climbs: List<TempoClimb>,
    val biggestJump: TempoClimb?,
    val highestBpm: Int?,
    // Habits and gear
    val dayParts: Map<DayPart, Long>,
    val weekdays: Map<DayOfWeek, Long>,
    val ratings: List<Int>,
    val tunings: List<Share>,
    val keys: List<Share>,
    val takes: Int,
    val toneCards: Int,
    // For milestones: counts behind the badges and their tooltips.
    /** Distinct days with a session. */
    val daysPracticed: Int = 0,
    val dayPartSessions: Map<DayPart, Int> = emptyMap(),
    val weekendSessions: Int = 0,
    /** Which days of the week have seen a session, ever (in range). */
    val weekdaysPlayed: Set<DayOfWeek> = emptySet(),
    val hourLongSessions: Int = 0,
    /** Different songs with time logged against them. */
    val songsPracticed: Int = 0,
    val songsInBook: Int = 0,
    /** Songs and exercises taken at least 10 BPM past where they started. */
    val climbsOf10: Int = 0,
    /** The song or exercise played fastest. */
    val fastest: TempoClimb? = null,
) {
    val isEmpty: Boolean get() = sessionCount == 0

    companion object {

        fun of(
            range: StatsRange,
            zone: ZoneId,
            now: Instant,
            sessions: List<Session>,
            songs: List<Song>,
            parts: List<SongPart>,
            tunings: List<Tuning>,
            takeTimes: List<Instant> = emptyList(),
            toneCardTimes: List<Instant> = emptyList(),
            streakMinutes: Int = Stats.DEFAULT_STREAK_MINUTES,
        ): StatsSnapshot {
            val today = now.atZone(zone).toLocalDate()
            val from: Instant? = range.days?.let { now.minus(Duration.ofDays(it)) }
            fun inRange(t: Instant) = from == null || !t.isBefore(from)

            val closed = sessions.filter { !it.isOpen }
            val inScope = closed.filter { inRange(it.startedAt) }
            val songById = songs.associateBy { it.id }

            // ---- Time ------------------------------------------------------------
            val durations = inScope.map { it.durationSeconds(now) }
            val total = durations.sum()
            val byDay = inScope.groupBy { it.localDate(zone) }.mapValues { (_, s) -> s.sumOf { it.durationSeconds(now) } }
            val heatStart = today.minusWeeks(range.heatWeeks.toLong() - 1).with(DayOfWeek.MONDAY)
            val heat = closed.groupBy { it.localDate(zone) }
                .filterKeys { !it.isBefore(heatStart) }
                .mapValues { (_, s) -> s.sumOf { it.durationSeconds(now) } }

            // ---- Songs -----------------------------------------------------------
            val songSeconds = mutableMapOf<Long, Long>()
            inScope.forEach { s -> attribute(s, now).forEach { (songId, secs) -> songSeconds.merge(songId, secs, Long::plus) } }
            val topSongs = songSeconds.entries
                .mapNotNull { (id, secs) -> songById[id]?.let { Share(it.title, secs, id) } }
                .sortedByDescending { it.seconds }.take(5)

            val masteredParts = parts.filter { it.masteredAt != null }
            val partsOverTime = masteredParts.mapNotNull { it.masteredAt?.atZone(zone)?.toLocalDate() }
                .sorted().runningFold(0 to (null as LocalDate?)) { acc, d -> (acc.first + 1) to d }
                .drop(1).map { (n, d) -> d!! to n }
                // One point per day, the day's closing count.
                .groupBy { it.first }.map { (d, pts) -> d to pts.maxOf { it.second } }

            val daysToMaster = songs.mapNotNull { s ->
                val done = s.masteredAt ?: return@mapNotNull null
                if (s.createdAt == Instant.EPOCH) null else Duration.between(s.createdAt, done).toDays().coerceAtLeast(0)
            }

            // ---- Tempo -----------------------------------------------------------
            val points = mutableMapOf<String, MutableList<Pair<Instant, Int>>>()
            inScope.sortedBy { it.startedAt }.forEach { s ->
                s.items.forEach { item ->
                    val bpm = item.bpmEnd ?: item.bpmStart ?: return@forEach
                    val label = item.songId?.let { songById[it]?.title } ?: item.exerciseName ?: return@forEach
                    points.getOrPut(label) { mutableListOf() } += s.startedAt to bpm
                }
            }
            val climbs = points.map { (label, pts) -> TempoClimb(label, pts) }.sortedByDescending { it.climb }

            // ---- Habits ----------------------------------------------------------
            val dayParts = DayPart.entries.associateWith { 0L }.toMutableMap()
            val weekdays = DayOfWeek.entries.associateWith { 0L }.toMutableMap()
            inScope.forEach { s ->
                val local = s.startedAt.atZone(zone)
                val part = dayPart(local.hour)
                val secs = s.durationSeconds(now)
                dayParts.merge(part, secs, Long::plus)
                weekdays.merge(local.dayOfWeek, secs, Long::plus)
            }

            // ---- Gear: time by the tuning and key of the songs played ------------
            val tuningNames = tunings.associate { it.id to it.name }
            val tuningShare = mutableMapOf<String, Long>()
            val keyShare = mutableMapOf<String, Long>()
            songSeconds.forEach { (id, secs) ->
                val song = songById[id] ?: return@forEach
                tuningShare.merge(song.tuningId?.let { tuningNames[it] } ?: "Standard", secs, Long::plus)
                song.key?.takeIf { it.isNotBlank() }?.let { keyShare.merge(it.trim(), secs, Long::plus) }
            }

            val dayPartSessions = inScope.groupingBy { dayPart(it.startedAt.atZone(zone).hour) }.eachCount()
            val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

            return StatsSnapshot(
                range = range,
                totalSeconds = total,
                sessionCount = inScope.size,
                averageSeconds = if (inScope.isEmpty()) 0 else total / inScope.size,
                longestSeconds = durations.maxOrNull() ?: 0,
                busiestDay = byDay.maxByOrNull { it.value }?.toPair(),
                heat = heat,
                heatStart = heatStart,
                today = today,
                currentStreak = Stats.currentStreak(closed, zone, today, streakMinutes, now),
                bestStreak = Stats.longestStreak(closed, zone, streakMinutes, now),
                topSongs = topSongs,
                statusCounts = SongStatus.entries.associateWith { st -> songs.count { it.status == st } },
                songsMastered = songs.count { it.status == SongStatus.MASTERED },
                partsMastered = masteredParts.size,
                partsMasteredOverTime = partsOverTime,
                averageDaysToMaster = daysToMaster.takeIf { it.isNotEmpty() }?.average(),
                climbs = climbs.take(6),
                biggestJump = climbs.firstOrNull { it.climb > 0 },
                highestBpm = climbs.maxOfOrNull { it.best },
                dayParts = dayParts,
                weekdays = weekdays,
                ratings = inScope.sortedBy { it.startedAt }.mapNotNull { it.rating },
                tunings = tuningShare.map { Share(it.key, it.value) }.sortedByDescending { it.seconds },
                keys = keyShare.map { Share(it.key, it.value) }.sortedByDescending { it.seconds },
                takes = takeTimes.count(::inRange),
                toneCards = toneCardTimes.count(::inRange),
                daysPracticed = byDay.size,
                dayPartSessions = dayPartSessions,
                weekendSessions = inScope.count { it.startedAt.atZone(zone).dayOfWeek in weekend },
                weekdaysPlayed = inScope.map { it.startedAt.atZone(zone).dayOfWeek }.toSet(),
                hourLongSessions = durations.count { it >= 3600 },
                songsPracticed = songSeconds.count { it.value > 0 && it.key in songById },
                songsInBook = songs.size,
                climbsOf10 = climbs.count { it.climb >= 10 },
                fastest = climbs.maxByOrNull { it.best },
            )
        }

        /** 5 am to noon is morning; 10 pm to 5 am is night. */
        fun dayPart(hour: Int): DayPart = when (hour) {
            in 5..11 -> DayPart.MORNING
            in 12..16 -> DayPart.AFTERNOON
            in 17..21 -> DayPart.EVENING
            else -> DayPart.NIGHT
        }

        /**
         * Seconds per song for one session.
         *
         * Items with their own seconds count as recorded. Songs in the session
         * with no seconds of their own (the Practice button, or a tag left at
         * zero) share whatever part of the session nothing else accounts for,
         * evenly. So a session is never counted twice, and a tagged one is
         * never counted as nothing.
         */
        fun attribute(session: Session, now: Instant): Map<Long, Long> {
            val out = mutableMapOf<Long, Long>()
            val songItems = session.items.filter { it.songId != null }
            val timed = songItems.filter { it.seconds > 0 }
            timed.forEach { out.merge(it.songId!!, it.seconds.toLong(), Long::plus) }
            val untimedSongs = songItems.filter { it.seconds <= 0 }.map { it.songId!! }.distinct()
            if (untimedSongs.isNotEmpty()) {
                val accounted = session.items.sumOf { it.seconds.coerceAtLeast(0).toLong() }
                val left = (session.durationSeconds(now) - accounted).coerceAtLeast(0)
                val each = left / untimedSongs.size
                untimedSongs.forEach { out.merge(it, each, Long::plus) }
            }
            return out
        }
    }
}
