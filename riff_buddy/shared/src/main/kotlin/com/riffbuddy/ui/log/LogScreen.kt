package com.riffbuddy.ui.log

import com.riffbuddy.ui.theme.tactileClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import com.riffbuddy.ui.tactile.OutlinedButton
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.Instant
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.Stats
import com.riffbuddy.ui.theme.RiffColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The practice log: a week at a glance, streaks, and where the time went.
 */
@Composable
fun LogScreen(
    sessions: List<Session>,
    songs: List<Song>,
    weeklyGoalMinutes: Int?,
    edits: SessionEdits? = null,
    streakMinutes: Int = Stats.DEFAULT_STREAK_MINUTES,
    /** For tagging a session with songs and parts. */
    parts: List<com.riffbuddy.domain.SongPart> = emptyList(),
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val finished = sessions.filter { !it.isOpen }
    /** The session being edited; [NEW_SESSION] for "log a past session". */
    var editing by remember { mutableStateOf<Session?>(null) }

    val target = editing
    if (edits != null && target != null) {
        val existing = target.takeIf { it !== NEW_SESSION }
        SessionEditorDialog(
            session = existing,
            zone = zone,
            onDismiss = { editing = null },
            onSave = { start, seconds, rating, notes, tags ->
                edits.save(existing, start, seconds, rating, notes, tags)
                editing = null
            },
            tagSongs = tagCandidates(
                songs,
                recentSongIds = finished.sortedByDescending { it.startedAt }.flatMap { s -> s.items.mapNotNull { it.songId } },
                alreadyTagged = existing?.items?.mapNotNull { it.songId }.orEmpty(),
            ),
            tagParts = parts.groupBy { it.songId },
            onDelete = existing?.let { s -> { edits.delete(s); editing = null } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Practice log", style = MaterialTheme.typography.headlineMedium)

        if (finished.isEmpty()) {
            com.riffbuddy.ui.Panel {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your practice will show up here", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Start a session from Home. Every one gets logged, and the streak " +
                            "needs only five minutes a day to count.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (edits != null) {
                OutlinedButton(onClick = { editing = NEW_SESSION }) { Text("Log a past session") }
            }
            return@Column
        }

        WeekStrip(finished, zone, today)
        StreakCard(finished, zone, today, weeklyGoalMinutes, streakMinutes)
        SongBreakdown(finished, songs)
        BpmChart(finished, songs, zone)
        RecentSessions(finished, zone, onOpen = edits?.let { { s: Session -> editing = s } })
        if (edits != null) {
            OutlinedButton(onClick = { editing = NEW_SESSION }) { Text("Log a past session") }
        }
    }
}

/** A stand-in for "no session yet" that is still distinct from "no dialog". */
private val NEW_SESSION = Session(startedAt = Instant.EPOCH)

/** Seven bars, one per day. Height is relative to the best day in view. */
@Composable
private fun WeekStrip(sessions: List<Session>, zone: ZoneId, today: LocalDate) {
    val totals = Stats.dayTotals(sessions, zone).associateBy { it.date }
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val best = week.maxOf { totals[it]?.seconds ?: 0L }.coerceAtLeast(1L)

    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            com.riffbuddy.ui.SectionHeader("THIS WEEK")
            Row(
                Modifier.fillMaxWidth().height(110.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                week.forEach { date ->
                    val seconds = totals[date]?.seconds ?: 0L
                    val minutes = seconds / 60
                    Column(
                        Modifier.weight(1f).semantics {
                            contentDescription = "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}: $minutes ${if (minutes == 1L) "minute" else "minutes"}"
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Text(
                            if (minutes > 0) "$minutes" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(4.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                // A day with practice always shows at least a
                                // sliver, so "a little" never looks like "none".
                                .height(if (seconds > 0) (6 + 64 * seconds / best).toInt().dp else 3.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (seconds > 0) RiffColors.lampLit
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            date.dayOfWeek.getDisplayName(TextStyle.NARROW, com.riffbuddy.ui.currentLocale()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreakCard(sessions: List<Session>, zone: ZoneId, today: LocalDate, goalMinutes: Int?, streakMinutes: Int) {
    val current = Stats.currentStreak(sessions, zone, today, streakMinutes)
    val longest = Stats.longestStreak(sessions, zone, streakMinutes)
    val weekSeconds = Stats.secondsInWeekEnding(sessions, zone, today)
    val total = Stats.totalSeconds(sessions)

    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Figure("$current", if (current == 1) "day streak" else "day streak")
                Figure("$longest", "best streak")
                Figure("${weekSeconds / 60}", "min this week")
                Figure("${total / 3600}", "hours total")
            }
            if (goalMinutes != null && goalMinutes > 0) {
                val progress = (weekSeconds / 60.0 / goalMinutes).coerceIn(0.0, 1.0)
                Text(
                    "Weekly goal: ${weekSeconds / 60} of $goalMinutes minutes " +
                        "(${(progress * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SongBreakdown(sessions: List<Session>, songs: List<Song>) {
    val totals = Stats.songTotals(sessions).take(8)
    if (totals.isEmpty()) return
    val byId = songs.associateBy { it.id }
    val best = totals.maxOf { it.seconds }.coerceAtLeast(1L)

    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            com.riffbuddy.ui.SectionHeader("TIME PER SONG")
            totals.forEach { total ->
                val name = byId[total.songId]?.title ?: "Deleted song"
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            "${total.seconds / 60} min",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth(maxOf(0.03f, total.seconds.toFloat() / best))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentSessions(sessions: List<Session>, zone: ZoneId, onOpen: ((Session) -> Unit)?) {
    var showAll by remember { mutableStateOf(false) }
    val sorted = sessions.sortedByDescending { it.startedAt }
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            com.riffbuddy.ui.SectionHeader("RECENT SESSIONS")
            if (onOpen != null) {
                Text(
                    "Tap one to edit or delete it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            (if (showAll) sorted else sorted.take(12)).forEach { session ->
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (onOpen != null) Modifier.tactileClickable { onOpen(session) } else Modifier)
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(DATE.format(session.startedAt.atZone(zone)), style = MaterialTheme.typography.bodyMedium)
                        if (session.notes.isNotBlank()) {
                            Text(
                                session.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        "${session.durationSeconds() / 60} min" + (session.rating?.let { "  ·  $it/5" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (sorted.size > 12) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Show fewer" else "Show all ${sorted.size}")
                }
            }
        }
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a")
