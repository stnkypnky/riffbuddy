package com.riffbuddy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.PracticeSettings

/**
 * The Audio and Practice settings. Click sound and volume live on the
 * metronome itself, where they are heard as they change.
 *
 * Steppers, not sliders: each of these is a small whole number that matters
 * exactly (440, not "about 440"), and a slider on a phone lands near a value,
 * rarely on it.
 */
@Composable
fun PracticeSection(settings: PracticeSettings, onChange: (PracticeSettings) -> Unit) {
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Practice and audio", style = MaterialTheme.typography.titleMedium)

            Stepper(
                label = "Tuner reference A4",
                value = "${settings.a4Hz} Hz",
                spoken = "A4 reference, ${settings.a4Hz} hertz",
                onMinus = { onChange(settings.copy(a4Hz = (settings.a4Hz - 1).coerceIn(PracticeSettings.A4_RANGE))) },
                onPlus = { onChange(settings.copy(a4Hz = (settings.a4Hz + 1).coerceIn(PracticeSettings.A4_RANGE))) },
            )
            if (settings.a4Hz != PracticeSettings.DEFAULT_A4_HZ) {
                TextButton(onClick = { onChange(settings.copy(a4Hz = PracticeSettings.DEFAULT_A4_HZ)) }) {
                    Text("Back to 440 Hz")
                }
            }

            val goal = settings.weeklyGoalMinutes
            Stepper(
                label = "Weekly goal",
                value = goal?.let { "$it min" } ?: "Off",
                spoken = goal?.let { "Weekly goal, $it minutes" } ?: "Weekly goal, off",
                // Down from the smallest goal turns it off; up from off starts
                // at an hour, which is a goal worth having.
                onMinus = {
                    val next = goal?.minus(GOAL_STEP)?.takeIf { it >= PracticeSettings.GOAL_RANGE.first }
                    onChange(settings.copy(weeklyGoalMinutes = next))
                },
                onPlus = {
                    val next = (goal?.plus(GOAL_STEP) ?: 60).coerceAtMost(PracticeSettings.GOAL_RANGE.last)
                    onChange(settings.copy(weeklyGoalMinutes = next))
                },
            )

            Stepper(
                label = "A streak day needs",
                value = "${settings.streakMinutes} min",
                spoken = "A streak day needs ${settings.streakMinutes} minutes",
                onMinus = { onChange(settings.copy(streakMinutes = (settings.streakMinutes - 1).coerceIn(PracticeSettings.STREAK_RANGE))) },
                onPlus = { onChange(settings.copy(streakMinutes = (settings.streakMinutes + 1).coerceIn(PracticeSettings.STREAK_RANGE))) },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show standard notation", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Above the tab, in the tab player and backing tracks. Also the Notes chip there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = !settings.tabOnly, onCheckedChange = { onChange(settings.copy(tabOnly = !it)) })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep the screen awake", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "While the metronome plays or a session runs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.keepAwake, onCheckedChange = { onChange(settings.copy(keepAwake = it)) })
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, spoken: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onMinus, modifier = Modifier.heightIn(min = 48.dp)) { Text("−") }
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 72.dp).semantics { contentDescription = spoken },
        )
        TextButton(onClick = onPlus, modifier = Modifier.heightIn(min = 48.dp)) { Text("+") }
    }
}

private const val GOAL_STEP = 15
