package com.riffbuddy

import com.riffbuddy.domain.Homework
import com.riffbuddy.domain.Routine
import com.riffbuddy.domain.RoutineRun
import com.riffbuddy.domain.RoutineStep
import com.riffbuddy.domain.RoutineTemplates
import com.riffbuddy.domain.StepKind
import com.riffbuddy.domain.StepOutcome
import com.riffbuddy.domain.TodayItem
import com.riffbuddy.domain.TodayPlan
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {

    private val t0 = Instant.parse("2026-09-22T18:00:00Z")
    private fun at(s: Long) = (t0 + (s).seconds)

    private val routine = Routine(
        name = "Test", steps = listOf(
            RoutineStep(StepKind.WARM_UP, "Warm up", 2),
            RoutineStep(StepKind.SONG, "Floods", 5, "song:7"),
            RoutineStep(StepKind.COOL_DOWN, "Cool down", 1),
        ),
    )

    /** A routine advances step to step with an audible cue. */
    @Test
    fun `the step advances when its time is up, and says so for the cue`() {
        val run = RoutineRun(routine, stepStartedAt = t0)
        val (early, cueEarly) = run.tick(at(119))
        assertEquals(0, early.index); assertFalse(cueEarly)
        val (later, cue) = run.tick(at(120))
        assertEquals(1, later.index); assertTrue(cue)
        assertEquals("Floods", later.current!!.title)
    }

    /** A late tick — the screen was off — still credits the step its own length, not the delay. */
    @Test
    fun `a late tick does not steal time from the next step`() {
        val (run, _) = RoutineRun(routine, stepStartedAt = t0).tick(at(200))
        assertEquals(120L, run.spent[0])
        assertEquals(80L, run.elapsedOnStep(at(200)))
    }

    @Test
    fun `pausing does not count as practice`() {
        var run = RoutineRun(routine, stepStartedAt = t0)
        run = run.pause(at(60)).resume(at(660))     // ten minutes away
        assertEquals(60L, run.remaining(at(660)))
        assertFalse(run.tick(at(700)).second)
        assertTrue(run.tick(at(720)).second)
    }

    /** One item per step; skipped steps are recorded as skipped. */
    @Test
    fun `logging gives one item per step and records skips`() {
        var run = RoutineRun(routine, stepStartedAt = t0)
        run = run.tick(at(120)).first          // warm-up done in full
        run = run.skip(at(150))                // song skipped after 30 s
        run = run.next(at(190))                // cool-down done early
        assertTrue(run.finished)
        val items = run.logItems()
        assertEquals(3, items.size)
        assertEquals(listOf(120, 30, 40), items.map { it.seconds })
        assertEquals(listOf("", "Skipped", ""), items.map { it.notes })
        assertEquals(7L, items[1].songId)
        assertEquals("Warm-up: Warm up", items[0].exerciseName)
    }

    @Test
    fun `stopping early records the rest as skipped`() {
        val run = RoutineRun(routine, stepStartedAt = t0).finish(at(45))
        assertEquals(listOf(StepOutcome.DONE, StepOutcome.SKIPPED, StepOutcome.SKIPPED), run.outcomes)
        assertEquals(45, run.logItems()[0].seconds)
    }

    @Test
    fun `the three starter templates are there, and add up`() {
        assertEquals(listOf("20-minute daily", "Speed builder", "Weekend deep-dive"), RoutineTemplates.ALL.map { it.name })
        assertEquals(20, RoutineTemplates.DAILY_20.minutes)
        assertTrue(RoutineTemplates.ALL.all { r -> r.steps.all { it.minutes > 0 } })
    }

    // ---- Today's plan ------------------------------------------------------

    private val now = Instant.parse("2026-09-22T12:00:00Z")

    /** Open homework appears until done, and marking it done updates the plan. */
    @Test
    fun `open homework comes first, overdue first, and done work drops out`() {
        val hw = listOf(
            Homework(1, text = "Undated", dueAt = null),
            Homework(2, text = "Due Friday", dueAt = Instant.parse("2026-09-25T12:00:00Z")),
            Homework(3, text = "Overdue", dueAt = Instant.parse("2026-09-20T12:00:00Z")),
            Homework(4, text = "Finished", doneAt = now),
        )
        val plan = TodayPlan.of(hw, RoutineTemplates.DAILY_20, now)
        val tasks = plan.filterIsInstance<TodayItem.Task>().map { it.homework.text }
        assertEquals(listOf("Overdue", "Due Friday", "Undated"), tasks)
        assertEquals(5, plan.filterIsInstance<TodayItem.Step>().size)

        val afterDone = TodayPlan.of(hw.map { if (it.id == 3L) it.copy(doneAt = now) else it }, null, now)
        assertFalse(afterDone.any { it is TodayItem.Task && it.homework.id == 3L })
    }

    @Test
    fun `a routine step reads its song link`() {
        assertEquals(7L, RoutineStep(StepKind.SONG, "x", 1, "song:7").songId)
        assertNull(RoutineStep(StepKind.SCALES, "x", 1, "scale:9:DORIAN").songId)
    }
}
