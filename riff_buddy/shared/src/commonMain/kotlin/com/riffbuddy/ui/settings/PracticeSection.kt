package com.riffbuddy.ui.settings

import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.ValueStepper
import com.riffbuddy.ui.IntStepper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.PracticeSettings

/**
 * The Audio and Practice settings. Click sound and volume live on the
 * metronome itself, where they are heard as they change.
 *
 * Steppers you can type into, not sliders: each of these matters exactly (440,
 * not "about 440"), and a slider on a phone lands near a value, rarely on it.
 */
@Composable
fun PracticeSection(settings: PracticeSettings, onChange: (PracticeSettings) -> Unit) {
    com.riffbuddy.ui.Panel(contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text("Practice and audio", style = MaterialTheme.typography.titleMedium)

            // Half-hertz steps on the buttons; typing takes a tenth, for a
            // record that sits at 441.3.
            ValueStepper(
                label = "Tuner reference A4",
                value = settings.a4Hz,
                range = PracticeSettings.A4_RANGE,
                onValue = { onChange(settings.copy(a4Hz = it)) },
                step = 0.5,
                unit = "Hz",
                decimals = PracticeSettings.A4_DECIMALS,
                quickPicks = listOf(432.0, 440.0, 442.0),
            )

            val goal = settings.weeklyGoalMinutes
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Weekly goal", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                // Turning it on starts at an hour, which is a goal worth having.
                Switch(
                    checked = goal != null,
                    onCheckedChange = { on -> onChange(settings.copy(weeklyGoalMinutes = if (on) 60 else null)) },
                    modifier = Modifier.semantics { contentDescription = "Weekly goal" },
                )
            }
            if (goal != null) {
                IntStepper(
                    label = "Minutes a week",
                    value = goal,
                    range = PracticeSettings.GOAL_RANGE,
                    onValue = { onChange(settings.copy(weeklyGoalMinutes = it)) },
                    step = GOAL_STEP,
                    unit = "min",
                    quickPicks = listOf(60, 150, 300),
                )
            }

            IntStepper(
                label = "A streak day needs",
                value = settings.streakMinutes,
                range = PracticeSettings.STREAK_RANGE,
                onValue = { onChange(settings.copy(streakMinutes = it)) },
                unit = "min",
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

private const val GOAL_STEP = 15
