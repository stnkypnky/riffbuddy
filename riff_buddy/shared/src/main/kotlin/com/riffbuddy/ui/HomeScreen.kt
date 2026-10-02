package com.riffbuddy.ui

import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.ui.help.HelpButton
import com.riffbuddy.ui.theme.tactileClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import com.riffbuddy.ui.tactile.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.Stats
import com.riffbuddy.ui.theme.RiffColors
import java.time.LocalDate
import java.time.ZoneId

/**
 * Home: what happened today, and one button to start practicing.
 */
@Composable
fun HomeScreen(
    sessions: List<Session>,
    songs: List<Song>,
    running: Session?,
    weeklyGoalMinutes: Int? = null,
    streakMinutes: Int = Stats.DEFAULT_STREAK_MINUTES,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onOpenSong: (Song) -> Unit,
    /** Today's plan, drawn under the day's summary. */
    todayPlan: @Composable () -> Unit = {},
    /** The "This week" card opens Me › Stats. */
    onOpenStats: () -> Unit = {},
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val finished = sessions.filter { !it.isOpen }
    val todayTotals = Stats.dayTotals(finished, zone).firstOrNull { it.date == today }
    val streak = Stats.currentStreak(finished, zone, today, streakMinutes)
    val weekMinutes = Stats.secondsInWeekEnding(finished, zone, today) / 60

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Riff Buddy",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            HelpButton(HelpTopic.HOME)
        }

        com.riffbuddy.ui.Panel {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Lit only once the streak is real. Never the sole signal of
                    // state — the words beside it say the same thing.
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = if (streak > 0) RiffColors.lampLit else RiffColors.lampDark,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        when (streak) {
                            0 -> "No streak yet"
                            1 -> "1 day streak"
                            else -> "$streak day streak"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (weeklyGoalMinutes != null) GoalRing(weekMinutes, weeklyGoalMinutes)

                if (running != null) {
                    Text("Practicing now", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Started ${running.durationSeconds() / 60} minutes ago. The timer runs " +
                            "off the clock, so it keeps counting with the screen off.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onEnd, modifier = Modifier.heightIn(min = 52.dp)) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("End session")
                    }
                } else {
                    Text(
                        if (todayTotals == null) "Ready when you are" else "Good session today",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        todayTotals?.let {
                            "${it.seconds / 60} minutes across ${it.sessions} " +
                                if (it.sessions == 1) "session." else "sessions."
                        } ?: "Five minutes counts. Start whenever you pick the guitar up.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onStart, modifier = Modifier.heightIn(min = 52.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Start session")
                    }
                }
            }
        }

        todayPlan()

        // A glance at the week, and the way into Stats.
        val weekSessions = finished.count { !it.localDate(zone).isBefore(today.minusDays(6)) }
        com.riffbuddy.ui.Panel(
            Modifier.fillMaxWidth().then(Modifier.tactileClickable(onClickLabel = "Open stats", onClick = onOpenStats))
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    com.riffbuddy.ui.SectionHeader("THIS WEEK")
                    Text(
                        "${com.riffbuddy.ui.stats.minutes(weekMinutes * 60)} · $weekSessions ${if (weekSessions == 1) "session" else "sessions"} · streak $streak",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text("Stats ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }

        val recent = songs.sortedByDescending { it.updatedAt }.take(4)
        if (recent.isNotEmpty()) {
            com.riffbuddy.ui.Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.riffbuddy.ui.SectionHeader("RECENT SONGS")
                    recent.forEach { song ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                .tactileClickable { onOpenSong(song) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(song.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(
                                song.status.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The weekly-goal ring: the last seven days against the goal. The numbers
 * sit beside it, so the ring is never the only way to read progress.
 */
@Composable
private fun GoalRing(minutes: Long, goal: Int) {
    val fraction = (minutes.toFloat() / goal).coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.outlineVariant
    val fill = if (fraction >= 1f) RiffColors.lampLit else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(48.dp)) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            if (fraction > 0f) {
                drawArc(fill, -90f, 360f * fraction, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            }
        }
        Spacer(Modifier.size(12.dp))
        Column {
            Text(
                if (fraction >= 1f) "Weekly goal met" else "$minutes of $goal minutes this week",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (fraction >= 1f) "$minutes minutes in the last seven days"
                else "${goal - minutes} to go, over the last seven days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

