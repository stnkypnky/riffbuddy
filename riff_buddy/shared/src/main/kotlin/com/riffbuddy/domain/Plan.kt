package com.riffbuddy.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ---- lessons and homework ---------------------------------------------------

data class Lesson(
    val id: Long = 0,
    val date: Instant,
    val teacher: String = "",
    val notes: String = "",
)

data class Homework(
    val id: Long = 0,
    val lessonId: Long? = null,
    val songId: Long? = null,
    val text: String,
    val dueAt: Instant? = null,
    val doneAt: Instant? = null,
) {
    val open: Boolean get() = doneAt == null

    fun overdue(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val due = dueAt ?: return false
        // atZone().toLocalDate(), not LocalDate.ofInstant: that is Java 9, and
        // Android has it only from API 34, and the app supports API 26.
        return open && due.atZone(zone).toLocalDate().isBefore(now.atZone(zone).toLocalDate())
    }
}

// ---- routines ---------------------------------------------------------------

/** What a routine step is for. */
enum class StepKind(val label: String) {
    WARM_UP("Warm-up"), SCALES("Scales"), TECHNIQUE("Technique drill"),
    SONG("Song work"), IMPROV("Improvisation"), COOL_DOWN("Cool-down"),
}

/**
 * One step. [link] is the tool it opens, if any: "song:<id>",
 * "scale:<root>:<SCALE>" or "preset:<id>" — a string, so a link to something
 * since deleted just does not resolve.
 */
data class RoutineStep(
    val kind: StepKind,
    val title: String,
    val minutes: Int,
    val link: String = "",
) {
    val songId: Long? get() = link.removePrefix("song:").takeIf { link.startsWith("song:") }?.toLongOrNull()
}

data class Routine(val id: Long = 0, val name: String, val steps: List<RoutineStep>) {
    val minutes: Int get() = steps.sumOf { it.minutes }
}

/**
 * The three starter templates. They live here, not in the database: using
 * one copies it into the player's routines, so editing never changes the
 * template — templates are editable copies.
 */
object RoutineTemplates {
    val DAILY_20 = Routine(
        name = "20-minute daily",
        steps = listOf(
            RoutineStep(StepKind.WARM_UP, "Chromatic 1-2-3-4 up and down the neck", 3),
            RoutineStep(StepKind.SCALES, "Minor pentatonic, all five boxes", 4, "scale:9:MINOR_PENTATONIC"),
            RoutineStep(StepKind.TECHNIQUE, "Alternate picking on one string, with the metronome", 4),
            RoutineStep(StepKind.SONG, "The song you are learning", 7),
            RoutineStep(StepKind.COOL_DOWN, "Play something you already love", 2),
        ),
    )
    val SPEED_BUILDER = Routine(
        name = "Speed builder",
        steps = listOf(
            RoutineStep(StepKind.WARM_UP, "Slow legato and slides", 3),
            RoutineStep(StepKind.TECHNIQUE, "The fast lick, at 60 % — use the speed trainer", 6),
            RoutineStep(StepKind.TECHNIQUE, "Same lick, stepping up 5 BPM when clean", 6),
            RoutineStep(StepKind.SONG, "Put it back into the song", 5),
            RoutineStep(StepKind.COOL_DOWN, "Stretch hands and forearms", 2),
        ),
    )
    val WEEKEND = Routine(
        name = "Weekend deep-dive",
        steps = listOf(
            RoutineStep(StepKind.WARM_UP, "Chromatic and spider exercises", 5),
            RoutineStep(StepKind.SCALES, "A new scale shape, slowly", 10, "scale:9:DORIAN"),
            RoutineStep(StepKind.TECHNIQUE, "Bends and vibrato, checked with the tuner", 8),
            RoutineStep(StepKind.SONG, "Learn a new section of a song", 20),
            RoutineStep(StepKind.IMPROV, "Jam over a backing track", 12),
            RoutineStep(StepKind.COOL_DOWN, "Record a take of what you learned", 5),
        ),
    )
    val ALL = listOf(DAILY_20, SPEED_BUILDER, WEEKEND)
}

enum class StepOutcome { DONE, SKIPPED }

/**
 * A routine being played through, as immutable state.
 *
 * Time is taken from timestamps, never counted by a ticking timer, for the
 * reason the session timer is: the screen locks mid-routine and a
 * counter that pauses with it would log a fifteen-minute step as three.
 */
data class RoutineRun(
    val routine: Routine,
    val index: Int = 0,
    val stepStartedAt: Instant,
    /** Seconds actually spent on each step. */
    val spent: List<Long> = List(routine.steps.size) { 0L },
    val outcomes: List<StepOutcome?> = List(routine.steps.size) { null },
    val pausedAt: Instant? = null,
    val startedAt: Instant = stepStartedAt,
) {
    val finished: Boolean get() = index >= routine.steps.size
    val current: RoutineStep? get() = routine.steps.getOrNull(index)
    val paused: Boolean get() = pausedAt != null

    fun elapsedOnStep(now: Instant): Long {
        val until = pausedAt ?: now
        return Duration.between(stepStartedAt, until).seconds.coerceAtLeast(0)
    }

    fun remaining(now: Instant): Long {
        val step = current ?: return 0
        return (step.minutes * 60L - elapsedOnStep(now)).coerceAtLeast(0)
    }

    /**
     * Moves on when the step's time is up. Returns the new state and whether
     * a step just ended — the moment for the audible cue.
     */
    fun tick(now: Instant): Pair<RoutineRun, Boolean> {
        if (finished || paused) return this to false
        val step = current!!
        if (elapsedOnStep(now) < step.minutes * 60L) return this to false
        // Credited with its full length, not with however late the tick came.
        val endedAt = stepStartedAt.plusSeconds(step.minutes * 60L)
        return close(StepOutcome.DONE, step.minutes * 60L, endedAt) to true
    }

    /** "Next": this step is done, early or not. */
    fun next(now: Instant): RoutineRun = if (finished) this else close(StepOutcome.DONE, elapsedOnStep(now), pausedAt ?: now)

    /** "Skip": recorded as skipped, with whatever time went in. */
    fun skip(now: Instant): RoutineRun = if (finished) this else close(StepOutcome.SKIPPED, elapsedOnStep(now), pausedAt ?: now)

    fun pause(now: Instant): RoutineRun = if (paused || finished) this else copy(pausedAt = now)

    /** Resuming shifts the step's start by the pause, so paused time is not counted. */
    fun resume(now: Instant): RoutineRun {
        val at = pausedAt ?: return this
        return copy(stepStartedAt = stepStartedAt.plus(Duration.between(at, now)), pausedAt = null)
    }

    /** Stopping early: the step in progress counts as done for its time; anything unreached is skipped. */
    fun finish(now: Instant): RoutineRun {
        var run = this
        if (!run.finished && run.elapsedOnStep(now) > 0) run = run.next(now)
        while (!run.finished) run = run.close(StepOutcome.SKIPPED, 0, now)
        return run
    }

    private fun close(outcome: StepOutcome, seconds: Long, endedAt: Instant) = copy(
        index = index + 1,
        spent = spent.toMutableList().also { it[index] = seconds },
        outcomes = outcomes.toMutableList().also { it[index] = outcome },
        stepStartedAt = endedAt,
        pausedAt = null,
    )

    /**
     * Logging creates one session with one item per step; skipped steps are
     * recorded as skipped.
     */
    fun logItems(): List<SessionItem> = routine.steps.mapIndexed { i, step ->
        SessionItem(
            songId = step.songId,
            exerciseName = "${step.kind.label}: ${step.title}",
            seconds = spent[i].toInt(),
            notes = if (outcomes[i] == StepOutcome.SKIPPED) "Skipped" else "",
        )
    }
}

// ---- Today's plan -----------------------------------------------------------

/** One line of Today's plan. */
sealed interface TodayItem {
    data class Task(val homework: Homework) : TodayItem
    data class Step(val index: Int, val step: RoutineStep) : TodayItem
}

/**
 * Home's Today's plan: open homework first — it came from a teacher and has a
 * date on it — overdue before due before undated; then the chosen routine's
 * steps. Done homework drops out, so marking one done updates Today's plan:
 * the plan is recomputed from the rows, so there is nothing to keep in step.
 */
object TodayPlan {
    fun of(homework: List<Homework>, routine: Routine?, now: Instant = Instant.now()): List<TodayItem> {
        val tasks = homework.filter { it.open }.sortedWith(
            compareByDescending<Homework> { it.overdue(now) }
                .thenBy { it.dueAt == null }
                .thenBy { it.dueAt }
                .thenBy { it.id }
        ).map { TodayItem.Task(it) }
        val steps = routine?.steps?.mapIndexed { i, s -> TodayItem.Step(i, s) }.orEmpty()
        return tasks + steps
    }
}
