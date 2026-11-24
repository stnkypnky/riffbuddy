package com.riffbuddy.domain

import kotlinx.datetime.DayOfWeek

/** Where a milestone sits on the Stats tab. */
enum class MilestoneGroup(val label: String) {
    PRACTICE("Practice time"),
    HABITS("Streaks and habits"),
    SONGS("Songs"),
    TEMPO("Tempo"),
    STUDIO("Recording and tone"),
}

/**
 * Badges earned by practicing.
 *
 * Each is a target and a measure over the all-time snapshot, so the Stats tab
 * can show locked ones with how close they are, and a newly earned one can be
 * celebrated exactly once.
 *
 * Milestones in the same [series] are tiers of one thing (1, 5, 10 hours…),
 * so the tab can show the ones earned plus the next to aim for. [unit] says
 * a measure the way a person would ("25 h", "7 days", "120 BPM"), and
 * [detail] adds the figures behind it for the tooltip.
 */
data class Milestone(
    val key: String,
    val title: String,
    val description: String,
    val target: Int,
    val group: MilestoneGroup,
    val series: String,
    val unit: (Int) -> String,
    val detail: (StatsSnapshot) -> String? = { null },
    val measure: (StatsSnapshot) -> Int,
) {
    fun progress(all: StatsSnapshot): Float = (measure(all).toFloat() / target).coerceIn(0f, 1f)
    fun earned(all: StatsSnapshot): Boolean = measure(all) >= target

    /** "Now 96 BPM · Goal 100 BPM", or once earned, just the goal. */
    fun standing(all: StatsSnapshot): String =
        if (earned(all)) "Goal ${unit(target)} · reached" else "Now ${unit(measure(all).coerceAtMost(target))} · Goal ${unit(target)}"
}

object Milestones {

    /**
     * Time milestones count *minutes*, shown as hours. Counting whole hours
     * left 59 minutes looking like nothing at all: the
     * ring sat empty an hour short of a badge.
     */
    private fun minutes(s: StatsSnapshot) = (s.totalSeconds / 60).toInt()

    private fun count(one: String, many: String = one + "s"): (Int) -> String = { n -> "$n ${if (n == 1) one else many}" }
    private val SESSIONS = count("session")
    private val DAYS = count("day")
    private val SONGS = count("song")
    private val PARTS = count("part")
    private val HOURS: (Int) -> String = { time(it * 60L) }
    private val BPM: (Int) -> String = { "$it BPM" }

    /** "12 h 30 min", "25 min", "none": the tooltip's version of a time. */
    fun time(seconds: Long): String {
        val m = seconds / 60
        return when {
            seconds <= 0 -> "none"
            m < 1 -> "under a minute"
            m < 60 -> "$m min"
            m % 60 == 0L -> "${m / 60} h"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    private val totalTime: (StatsSnapshot) -> String = {
        if (it.sessionCount == 0) "Nothing logged yet: start a session from Home."
        else "${time(it.totalSeconds)} over ${SESSIONS(it.sessionCount)}, ${time(it.averageSeconds)} on average."
    }
    private val streaks: (StatsSnapshot) -> String = { "Best streak ${DAYS(it.bestStreak)}; current ${DAYS(it.currentStreak)}." }
    private val fastest: (StatsSnapshot) -> String? = { s -> s.fastest?.let { "Fastest so far: ${it.label} at ${it.best} BPM." } }
    private val biggestClimb: (StatsSnapshot) -> String? = { s ->
        s.biggestJump?.let { "Biggest climb: ${it.label}, ${it.first} → ${it.best} BPM." }
    }
    private val mastered: (StatsSnapshot) -> String = {
        "${SONGS(it.songsMastered)} mastered of ${it.songsInBook} in the songbook; ${PARTS(it.partsMastered)} mastered."
    }

    private val WEEK = DayOfWeek.entries
    private fun short(d: DayOfWeek) = d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

    val ALL: List<Milestone> = buildList {
        val g = MilestoneGroup.PRACTICE
        // Sessions
        add(Milestone("first_session", "First session", "Logged your first practice session.", 1, g, "sessions", SESSIONS, totalTime) { it.sessionCount })
        add(Milestone("sessions_10", "Getting the hang of it", "Ten practice sessions logged.", 10, g, "sessions", SESSIONS, totalTime) { it.sessionCount })
        add(Milestone("sessions_50", "Regular", "Fifty practice sessions logged.", 50, g, "sessions", SESSIONS, totalTime) { it.sessionCount })
        add(Milestone("sessions_100", "Centurion", "A hundred practice sessions.", 100, g, "sessions", SESSIONS, totalTime) { it.sessionCount })
        add(Milestone("sessions_250", "Lifer", "Two hundred and fifty sessions. It's who you are now.", 250, g, "sessions", SESSIONS, totalTime) { it.sessionCount })
        // Hours
        add(Milestone("hours_1", "First hour", "An hour of practice logged.", 1 * 60, g, "hours", HOURS, totalTime, ::minutes))
        add(Milestone("hours_5", "Warming up", "Five hours of practice logged.", 5 * 60, g, "hours", HOURS, totalTime, ::minutes))
        add(Milestone("hours_10", "Ten hours", "Ten hours of practice logged.", 10 * 60, g, "hours", HOURS, totalTime, ::minutes))
        add(Milestone("hours_25", "Calluses", "Twenty-five hours: your fingertips have noticed.", 25 * 60, g, "hours", HOURS, totalTime, ::minutes))
        add(Milestone("hours_50", "Fifty hours", "Fifty hours of practice logged.", 50 * 60, g, "hours", HOURS, totalTime, ::minutes))
        add(Milestone("hours_100", "Hundred hours", "A hundred hours with a guitar in your hands.", 100 * 60, g, "hours", HOURS, totalTime, ::minutes))
        add(Milestone("hours_250", "Road dog", "Two hundred and fifty hours played.", 250 * 60, g, "hours", HOURS, totalTime, ::minutes))
        // Days
        val days: (StatsSnapshot) -> String = { "${time(it.totalSeconds)} across them; best streak ${DAYS(it.bestStreak)}." }
        add(Milestone("days_7", "Seven days", "Practiced on seven different days.", 7, g, "days", DAYS, days) { it.daysPracticed })
        add(Milestone("days_30", "A month of days", "Practiced on thirty different days.", 30, g, "days", DAYS, days) { it.daysPracticed })
        add(Milestone("days_100", "Hundred days", "Practiced on a hundred different days.", 100, g, "days", DAYS, days) { it.daysPracticed })
        add(Milestone("days_365", "A year of playing", "Practiced on 365 different days.", 365, g, "days", DAYS, days) { it.daysPracticed })
        // Long sessions
        val long: (StatsSnapshot) -> String = { "Longest session ${time(it.longestSeconds)}; ${SESSIONS(it.hourLongSessions)} of an hour or more." }
        // Marathon fills from the longest session so far, so a 45-minute one shows three quarters.
        add(Milestone("long_1", "Marathon", "A single session of an hour or more.", 60, g, "longest", HOURS, long) { (it.longestSeconds / 60).toInt() })
        add(Milestone("long_10", "Endurance", "Ten sessions of an hour or more.", 10, g, "long", SESSIONS, long) { it.hourLongSessions })
    } + buildList {
        val g = MilestoneGroup.HABITS
        add(Milestone("streak_3", "On a roll", "Practiced three days running.", 3, g, "streak", DAYS, streaks) { it.bestStreak })
        add(Milestone("streak_7", "Full week", "A seven-day streak.", 7, g, "streak", DAYS, streaks) { it.bestStreak })
        add(Milestone("streak_14", "Two weeks strong", "A fourteen-day streak.", 14, g, "streak", DAYS, streaks) { it.bestStreak })
        add(Milestone("streak_30", "Habit", "A thirty-day streak.", 30, g, "streak", DAYS, streaks) { it.bestStreak })
        add(Milestone("streak_60", "Unstoppable", "A sixty-day streak.", 60, g, "streak", DAYS, streaks) { it.bestStreak })
        add(Milestone("streak_100", "Hundred-day streak", "A hundred days in a row.", 100, g, "streak", DAYS, streaks) { it.bestStreak })
        add(Milestone("early_bird", "Early bird", "Five sessions started in the morning (5 am to noon).", 5, g, "early", SESSIONS,
            { "Morning sessions so far: ${it.dayPartSessions[DayPart.MORNING] ?: 0}." }) { it.dayPartSessions[DayPart.MORNING] ?: 0 })
        add(Milestone("night_owl", "Night owl", "Five sessions started late at night (10 pm to 5 am).", 5, g, "night", SESSIONS,
            { "Late-night sessions so far: ${it.dayPartSessions[DayPart.NIGHT] ?: 0}." }) { it.dayPartSessions[DayPart.NIGHT] ?: 0 })
        add(Milestone("weekend_10", "Weekend warrior", "Ten sessions on a Saturday or Sunday.", 10, g, "weekend", SESSIONS,
            { "Weekend sessions so far: ${it.weekendSessions}." }) { it.weekendSessions })
        add(Milestone("every_weekday", "Seven for seven", "Practiced on every day of the week at least once.", 7, g, "weekdays", count("day of the week", "days of the week"),
            { s ->
                val missing = WEEK.filter { it !in s.weekdaysPlayed }
                if (missing.isEmpty()) "Every day of the week covered." else "Still to go: ${missing.joinToString(", ") { short(it) }}."
            }) { it.weekdaysPlayed.size })
        add(Milestone("rated_10", "Honest ears", "Rated ten sessions at the end.", 10, g, "rated", SESSIONS,
            { s -> "Sessions rated: ${s.ratings.size}" + (s.ratings.takeIf { it.isNotEmpty() }?.let { r -> ", averaging ${r.average().toFixed(1)}." } ?: ".") }
        ) { it.ratings.size })
    } + buildList {
        val g = MilestoneGroup.SONGS
        add(Milestone("songbook_5", "Wish list", "Five songs in the songbook.", 5, g, "songbook", SONGS, mastered) { it.songsInBook })
        add(Milestone("songbook_10", "Songbook", "Ten songs in the songbook.", 10, g, "songbook", SONGS, mastered) { it.songsInBook })
        add(Milestone("songbook_25", "Deep catalog", "Twenty-five songs in the songbook.", 25, g, "songbook", SONGS, mastered) { it.songsInBook })
        val played: (StatsSnapshot) -> String = { s ->
            "${SONGS(s.songsPracticed)} with time logged" + (s.topSongs.firstOrNull()?.let { "; most played: ${it.label}." } ?: ".")
        }
        add(Milestone("variety_5", "Variety", "Logged time on five different songs.", 5, g, "variety", SONGS, played) { it.songsPracticed })
        add(Milestone("variety_15", "Wide repertoire", "Logged time on fifteen different songs.", 15, g, "variety", SONGS, played) { it.songsPracticed })
        add(Milestone("first_part", "First part down", "Mastered a part of a song.", 1, g, "parts", PARTS, mastered) { it.partsMastered })
        add(Milestone("parts_5", "Piece by piece", "Five song parts mastered.", 5, g, "parts", PARTS, mastered) { it.partsMastered })
        add(Milestone("parts_10", "Ten parts", "Ten song parts mastered.", 10, g, "parts", PARTS, mastered) { it.partsMastered })
        add(Milestone("parts_25", "Section leader", "Twenty-five song parts mastered.", 25, g, "parts", PARTS, mastered) { it.partsMastered })
        add(Milestone("first_song", "First song mastered", "Mastered a whole song.", 1, g, "songs", SONGS, mastered) { it.songsMastered })
        add(Milestone("songs_5", "Setlist", "Five songs mastered.", 5, g, "songs", SONGS, mastered) { it.songsMastered })
        add(Milestone("songs_10", "Full set", "Ten songs mastered: enough for a gig.", 10, g, "songs", SONGS, mastered) { it.songsMastered })
        add(Milestone("songs_25", "Headliner", "Twenty-five songs mastered.", 25, g, "songs", SONGS, mastered) { it.songsMastered })
    } + buildList {
        val g = MilestoneGroup.TEMPO
        val top: (StatsSnapshot) -> Int = { it.highestBpm ?: 0 }
        add(Milestone("bpm_100", "Hundred club", "Played a song or exercise at 100 BPM or faster.", 100, g, "bpm", BPM, fastest, top))
        add(Milestone("bpm_120", "Picking up speed", "Played something at 120 BPM or faster.", 120, g, "bpm", BPM, fastest, top))
        add(Milestone("bpm_140", "Shredding", "Played something at 140 BPM or faster.", 140, g, "bpm", BPM, fastest, top))
        add(Milestone("bpm_160", "Thrash tempo", "Played something at 160 BPM or faster.", 160, g, "bpm", BPM, fastest, top))
        add(Milestone("bpm_180", "Speed demon", "Played something at 180 BPM or faster.", 180, g, "bpm", BPM, fastest, top))
        val climb: (StatsSnapshot) -> Int = { it.biggestJump?.climb ?: 0 }
        add(Milestone("climb_10", "Nudging up", "Took something 10 BPM faster than you started it.", 10, g, "climb", BPM, biggestClimb, climb))
        add(Milestone("climb_20", "Speeding up", "Took something 20 BPM faster than you started it.", 20, g, "climb", BPM, biggestClimb, climb))
        add(Milestone("climb_40", "Rocket", "Took something 40 BPM faster than you started it.", 40, g, "climb", BPM, biggestClimb, climb))
        add(Milestone("climbers_5", "Tempo builder", "Five songs or exercises each taken 10 BPM faster.", 5, g, "climbers", count("song or exercise", "songs and exercises"),
            { "Taken 10 BPM or more faster: ${it.climbsOf10}." }) { it.climbsOf10 })
    } + buildList {
        val g = MilestoneGroup.STUDIO
        val takes: (StatsSnapshot) -> String = { "Takes recorded: ${it.takes}." }
        add(Milestone("takes_1", "Rolling", "Recorded your first take.", 1, g, "takes", count("take"), takes) { it.takes })
        add(Milestone("takes_10", "On tape", "Recorded ten takes.", 10, g, "takes", count("take"), takes) { it.takes })
        add(Milestone("takes_50", "Studio rat", "Recorded fifty takes.", 50, g, "takes", count("take"), takes) { it.takes })
        val tones: (StatsSnapshot) -> String = { "Tone cards made: ${it.toneCards}." }
        add(Milestone("tone_1", "Tone chaser", "Made your first tone card.", 1, g, "tone", count("tone card"), tones) { it.toneCards })
        add(Milestone("tone_10", "Tone library", "Made ten tone cards.", 10, g, "tone", count("tone card"), tones) { it.toneCards })
        add(Milestone("tunings_3", "Retuned", "Logged time on songs in three different tunings.", 3, g, "tunings", count("tuning"),
            { s -> if (s.tunings.isEmpty()) null else "Tunings played: ${s.tunings.joinToString(", ") { it.label }}." }) { it.tunings.size })
        add(Milestone("keys_5", "Key explorer", "Logged time on songs in five different keys.", 5, g, "keys", count("key"),
            { s -> if (s.keys.isEmpty()) "Set a song's key on its page to count it." else "Keys played: ${s.keys.joinToString(", ") { it.label }}." }) { it.keys.size })
    }

    /**
     * Earned now but not yet recorded. The caller records them; on the first
     * run after this feature arrived it records them *silently*, so months of
     * history don't all go off at once.
     */
    fun newlyEarned(all: StatsSnapshot, recorded: Set<String>): List<Milestone> =
        ALL.filter { it.key !in recorded && it.earned(all) }

    /** The fourteen original milestones, known to every install that seeded. */
    val FIRST_CATALOG: Set<String> = setOf(
        "first_session", "hours_1", "hours_10", "hours_50", "hours_100", "streak_3", "streak_7", "streak_30",
        "first_part", "first_song", "songs_5", "bpm_100", "climb_20", "takes_10",
    )

    /**
     * Which newly earned milestones to celebrate. One this install has never
     * seen (just added to the catalog) and that history already earned is
     * recorded quietly instead: an update shouldn't set off a dozen banners.
     * Anything in [known] that's earned from here on is celebrated as usual.
     */
    fun toCelebrate(fresh: List<Milestone>, known: Set<String>): List<Milestone> = fresh.filter { it.key in known }

    /**
     * What the Stats tab shows by default: every one earned, plus the next
     * locked tier of each series to aim for. The rest of a series stays out
     * of the way until it's next.
     */
    fun nextUp(all: StatsSnapshot): List<Milestone> =
        ALL.groupBy { it.series }.values.flatMap { tiers ->
            val sorted = tiers.sortedBy { it.target }
            sorted.filter { it.earned(all) } + listOfNotNull(sorted.firstOrNull { !it.earned(all) })
        }
}
