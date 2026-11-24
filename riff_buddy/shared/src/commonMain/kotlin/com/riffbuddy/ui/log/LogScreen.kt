package com.riffbuddy.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material3.Icon
import com.riffbuddy.ui.EmptyState
import com.riffbuddy.ui.PageHeader
import com.riffbuddy.ui.Panel
import com.riffbuddy.ui.theme.Radius
import com.riffbuddy.ui.theme.Space
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.EPOCH
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.Stats
import com.riffbuddy.domain.format
import com.riffbuddy.domain.fullName
import com.riffbuddy.domain.letter
import com.riffbuddy.domain.localZone
import com.riffbuddy.domain.minusDays
import com.riffbuddy.domain.today
import com.riffbuddy.ui.tactile.OutlinedButton
import com.riffbuddy.ui.tactile.TextButton
import com.riffbuddy.ui.theme.RiffColors
import com.riffbuddy.ui.theme.tactileClickable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

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
    val zone = localZone()
    val today = today(zone)
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth >= 760.dp
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = if (wide) Space.screenWide else Space.screen, vertical = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        val logPast: (@Composable RowScope.() -> Unit)? = if (edits == null) null else {
            {
                OutlinedButton(onClick = { editing = NEW_SESSION }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Space.xs + Space.xxs))
                    Text("Log past session")
                }
            }
        }
        PageHeader(
            "Practice log",
            eyebrow = "Your sessions",
            subtitle = if (finished.isEmpty()) null else "${finished.size} ${if (finished.size == 1) "session" else "sessions"} logged",
            // While the log is empty, its empty state carries the same button;
            // two of them on one screen reads as two different things to do.
            actions = if (wide && finished.isNotEmpty()) logPast else null,
        )

        if (finished.isEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.EventNote,
                    title = "Your practice will show up here",
                    body = "Start a session from Home. Every one gets logged, and the streak " +
                        "needs only five minutes a day to count.",
                    action = if (edits == null) null else {
                        { OutlinedButton(onClick = { editing = NEW_SESSION }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Log a past session") } }
                    },
                )
            }
            return@Column
        }

        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                    WeekStrip(finished, zone, today)
                    StreakCard(finished, zone, today, weeklyGoalMinutes, streakMinutes)
                    SongBreakdown(finished, songs)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                    BpmChart(finished, songs, zone)
                    RecentSessions(finished, zone, onOpen = edits?.let { { s: Session -> editing = s } })
                }
            }
        } else {
            WeekStrip(finished, zone, today)
            StreakCard(finished, zone, today, weeklyGoalMinutes, streakMinutes)
            SongBreakdown(finished, songs)
            BpmChart(finished, songs, zone)
            RecentSessions(finished, zone, onOpen = edits?.let { { s: Session -> editing = s } })
            if (logPast != null) Row { logPast() }
        }
    }
    }
}

/** A stand-in for "no session yet" that is still distinct from "no dialog". */
private val NEW_SESSION = Session(startedAt = EPOCH)

/** Seven bars, one per day. Height is relative to the best day in view. */
@Composable
private fun WeekStrip(sessions: List<Session>, zone: TimeZone, today: LocalDate) {
    val totals = Stats.dayTotals(sessions, zone).associateBy { it.date }
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val best = week.maxOf { totals[it]?.seconds ?: 0L }.coerceAtLeast(1L)

    Panel(Modifier.fillMaxWidth(), contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            com.riffbuddy.ui.SectionHeader("This week")
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
                            contentDescription = "${date.dayOfWeek.fullName}: $minutes ${if (minutes == 1L) "minute" else "minutes"}"
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
                                .clip(RoundedCornerShape(topStart = Radius.xs, topEnd = Radius.xs, bottomStart = Space.xxs, bottomEnd = Space.xxs))
                                .background(
                                    if (seconds > 0) RiffColors.lampLit
                                    else MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            date.dayOfWeek.letter,
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
private fun StreakCard(sessions: List<Session>, zone: TimeZone, today: LocalDate, goalMinutes: Int?, streakMinutes: Int) {
    val current = Stats.currentStreak(sessions, zone, today, streakMinutes)
    val longest = Stats.longestStreak(sessions, zone, streakMinutes)
    val weekSeconds = Stats.secondsInWeekEnding(sessions, zone, today)
    val total = Stats.totalSeconds(sessions)

    Panel(Modifier.fillMaxWidth(), contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            com.riffbuddy.ui.SectionHeader("Streaks and totals")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Figure("$current", "day streak", Modifier.weight(1f))
                Figure("$longest", "best streak", Modifier.weight(1f))
                Figure("${weekSeconds / 60}", "min this week", Modifier.weight(1f))
                Figure("${total / 3600}", "hours total", Modifier.weight(1f))
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
private fun Figure(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
            .padding(vertical = Space.sm, horizontal = Space.xs)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
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

    Panel(Modifier.fillMaxWidth(), contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            com.riffbuddy.ui.SectionHeader("Time per song")
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
                        Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(maxOf(0.03f, total.seconds.toFloat() / best))
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSessions(sessions: List<Session>, zone: TimeZone, onOpen: ((Session) -> Unit)?) {
    var showAll by remember { mutableStateOf(false) }
    val sorted = sessions.sortedByDescending { it.startedAt }
    Panel(Modifier.fillMaxWidth(), contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            com.riffbuddy.ui.SectionHeader("Recent sessions")
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
                        Text(session.startedAt.format(DATE, zone), style = MaterialTheme.typography.bodyMedium)
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
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                            .padding(horizontal = Space.md, vertical = Space.xs),
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

private const val DATE = "EEE, MMM d, h:mm a"
