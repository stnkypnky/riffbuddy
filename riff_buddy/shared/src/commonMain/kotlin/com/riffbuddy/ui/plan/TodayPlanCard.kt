package com.riffbuddy.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.Homework
import com.riffbuddy.domain.Routine
import com.riffbuddy.domain.RoutineRun
import com.riffbuddy.domain.RoutineStep
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.StepOutcome
import com.riffbuddy.domain.TodayItem
import com.riffbuddy.domain.TodayPlan
import com.riffbuddy.domain.format
import com.riffbuddy.domain.minutesAndSeconds
import com.riffbuddy.ui.tactile.Button
import com.riffbuddy.ui.tactile.OutlinedButton
import com.riffbuddy.ui.tactile.TextButton
import kotlin.time.Clock

/**
 * Today's plan on Home: open homework, then the chosen routine — or
 * the routine being run, step by step.
 */
@Composable
fun TodayPlanCard(
    homework: List<Homework>,
    songs: List<Song>,
    routine: Routine?,
    run: RoutineRun?,
    remainingSeconds: Long,
    onToggleDone: (Homework, Boolean) -> Unit,
    onStartRoutine: (Routine) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onOpenStep: (RoutineStep) -> Unit,
    onOpenLessons: () -> Unit,
    onOpenRoutines: () -> Unit,
) {
    val plan = TodayPlan.of(homework, routine)
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.riffbuddy.ui.SectionHeader("TODAY'S PLAN", modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenLessons) { Text("Lessons") }
                TextButton(onClick = onOpenRoutines) { Text("Routines") }
            }

            val tasks = plan.filterIsInstance<TodayItem.Task>()
            if (tasks.isNotEmpty()) {
                Text("Homework", style = MaterialTheme.typography.titleSmall)
                tasks.forEach { t -> HomeworkLine(t.homework, songs, onToggleDone) }
            }

            when {
                run != null -> RunnerPanel(run, remainingSeconds, onNext, onSkip, onPause, onResume, onStop, onDismiss, onOpenStep)
                routine != null -> {
                    Text("${routine.name} · ${routine.minutes} min", style = MaterialTheme.typography.titleSmall)
                    routine.steps.forEach { step ->
                        Text("${step.minutes} min · ${step.kind.label} — ${step.title}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { onStartRoutine(routine) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start routine") }
                }
                tasks.isEmpty() -> Text(
                    "Nothing planned. Pick a routine, or log a lesson and its homework will land here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A homework line with its checkbox — ticking it drops it off the plan. */
@Composable
fun HomeworkLine(item: Homework, songs: List<Song>, onToggleDone: (Homework, Boolean) -> Unit) {
    val now = Clock.System.now()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = !item.open, role = Role.Checkbox) { onToggleDone(item, it) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = !item.open, onCheckedChange = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(item.text, style = MaterialTheme.typography.bodyMedium)
            val detail = listOfNotNull(
                songs.firstOrNull { it.id == item.songId }?.title,
                item.dueAt?.let { (if (item.overdue(now)) "overdue, was due " else "due ") + it.format(DUE) },
            ).joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.labelSmall,
                    color = if (item.overdue(now)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RunnerPanel(
    run: RoutineRun,
    remaining: Long,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onOpenStep: (RoutineStep) -> Unit,
) {
    val step = run.current
    if (run.finished || step == null) {
        val skipped = run.outcomes.count { it == StepOutcome.SKIPPED }
        Text("${run.routine.name} — done. Logged ${run.routine.steps.size} steps" +
            (if (skipped > 0) ", $skipped skipped." else "."), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = onDismiss) { Text("OK") }
        return
    }
    Text("Step ${run.index + 1} of ${run.routine.steps.size} · ${step.kind.label}", style = MaterialTheme.typography.labelLarge)
    Text(step.title, style = MaterialTheme.typography.titleMedium)
    Text(
        minutesAndSeconds(remaining) + if (run.paused) "  (paused)" else "",
        style = MaterialTheme.typography.displaySmall,
    )
    LinearProgressIndicator(
        progress = { 1f - remaining.toFloat() / (step.minutes * 60f) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onNext) { Text("Next") }
        OutlinedButton(onClick = onSkip) { Text("Skip") }
        TextButton(onClick = if (run.paused) onResume else onPause) { Text(if (run.paused) "Resume" else "Pause") }
        TextButton(onClick = onStop) { Text("Stop") }
    }
    if (step.link.isNotBlank()) {
        TextButton(onClick = { onOpenStep(step) }) { Text(linkLabel(step)) }
    }
}

private fun linkLabel(step: RoutineStep): String = when {
    step.link.startsWith("song:") -> "Open the song"
    step.link.startsWith("scale:") -> "Show the scale"
    step.link.startsWith("preset:") -> "Open the metronome preset"
    else -> "Open"
}

private const val DUE = "EEE, MMM d"
