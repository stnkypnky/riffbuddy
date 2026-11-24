package com.riffbuddy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.Stats
import com.riffbuddy.domain.format
import com.riffbuddy.domain.local
import com.riffbuddy.domain.localDate
import com.riffbuddy.domain.localZone
import com.riffbuddy.domain.minusDays
import com.riffbuddy.domain.today
import com.riffbuddy.ui.help.HelpButton
import com.riffbuddy.ui.songs.StatusPill
import com.riffbuddy.ui.tactile.Button
import com.riffbuddy.ui.tactile.TextButton
import com.riffbuddy.ui.theme.LocalCondensedFont
import com.riffbuddy.ui.theme.RiffColors
import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.theme.tactileClickable
import com.riffbuddy.ui.tuner.Tool
import kotlin.time.Clock

/**
 * Home: the streak, the week's goal and one button to start practicing, then
 * a way into each tool and what's planned for today.
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
    /** Opens a tool from the quick-start tiles; without it those tiles are left out. */
    onOpenTool: ((Tool) -> Unit)? = null,
    /** Opens the Songs tab from its quick-start tile. */
    onOpenSongs: (() -> Unit)? = null,
    /** Opens the Coach tab from its quick-start tile. */
    onOpenCoach: (() -> Unit)? = null,
) {
    val zone = localZone()
    val today = today(zone)
    val finished = sessions.filter { !it.isOpen }
    val todayTotals = Stats.dayTotals(finished, zone).firstOrNull { it.date == today }
    val streak = Stats.currentStreak(finished, zone, today, streakMinutes)
    val weekMinutes = Stats.secondsInWeekEnding(finished, zone, today) / 60
    val weekSessions = finished.count { it.localDate(zone) >= today.minusDays(6) }
    val hour = Clock.System.now().local(zone).hour

    val tiles = buildList {
        if (onOpenTool != null) {
            add(QuickTile("Metronome", "Click and speed trainer", Tool.METRONOME.icon) { onOpenTool(Tool.METRONOME) })
            add(QuickTile("Tuner", "Get in tune first", Tool.TUNER.icon) { onOpenTool(Tool.TUNER) })
        }
        if (onOpenSongs != null) add(QuickTile("Songs", "Your songbook", Icons.AutoMirrored.Filled.LibraryBooks, onOpenSongs))
        if (onOpenCoach != null) add(QuickTile("Coach", "Ask about tone or technique", Icons.Filled.AutoAwesome, onOpenCoach))
        if (onOpenTool != null) {
            add(QuickTile("Looper", "Slow a riff down", Tool.LOOPER.icon) { onOpenTool(Tool.LOOPER) })
            add(QuickTile("Tabs", "Play along to a tab", Tool.TABS.icon) { onOpenTool(Tool.TABS) })
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Two columns once the content area is wide enough that each card
        // still holds a full line of text; a phone and a narrow window keep one.
        val width = maxWidth
        val twoColumns = width >= 760.dp
        val gutter = if (twoColumns) Space.screenWide else Space.screen
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(gutter),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            PageHeader(
                title = greeting(hour),
                eyebrow = today.format("EEEE, MMMM d"),
                actions = { HelpButton(HelpTopic.HOME) },
            )

            HomeHero(
                streak = streak,
                running = running,
                todaySeconds = todayTotals?.seconds,
                todaySessions = todayTotals?.sessions ?: 0,
                weekMinutes = weekMinutes,
                weeklyGoalMinutes = weeklyGoalMinutes,
                wide = width >= 560.dp,
                onStart = onStart,
                onEnd = onEnd,
            )

            if (tiles.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    SectionHeader("Quick start")
                    val perRow = when {
                        width >= 900.dp -> 6
                        width >= 560.dp -> 3
                        else -> 2
                    }
                    Grid(tiles, perRow, Space.md, equalHeight = true) { QuickStartTile(it) }
                }
            }

            val week: @Composable () -> Unit = {
                WeekCard(weekMinutes, weekSessions, streak, onOpenStats)
            }
            val recent = songs.sortedByDescending { it.updatedAt }.take(4)
            val recentCard: (@Composable () -> Unit)? = if (recent.isEmpty()) null else {
                { RecentSongsCard(recent, onOpenSong) }
            }

            if (twoColumns) {
                // Today's plan is the longest card, so it takes the left column
                // on its own and the shorter cards stack beside it.
                // The two columns end level: the row is as tall as the taller
                // side, the plan stretches to it, and on the right the last card
                // takes up whatever the column has to spare.
                Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                    Box(Modifier.weight(1f).fillMaxHeight(), propagateMinConstraints = true) { todayPlan() }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                        if (recentCard == null) {
                            Box(Modifier.weight(1f), propagateMinConstraints = true) { week() }
                        } else {
                            week()
                            Box(Modifier.weight(1f), propagateMinConstraints = true) { recentCard() }
                        }
                    }
                }
            } else {
                todayPlan()
                week()
                recentCard?.invoke()
            }
            Spacer(Modifier.size(Space.sm))
        }
    }
}

private fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late-night practice"
}

@Composable
private fun HomeHero(
    streak: Int,
    running: Session?,
    todaySeconds: Long?,
    todaySessions: Int,
    weekMinutes: Long,
    weeklyGoalMinutes: Int?,
    wide: Boolean,
    onStart: () -> Unit,
    onEnd: () -> Unit,
) {
    HeroCard(Modifier.fillMaxWidth()) {
        StreakLamp(streak)
        Spacer(Modifier.size(Space.lg))
        val main: @Composable (Modifier) -> Unit = { mod ->
            Column(mod, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (running != null) {
                    Text("Practicing now", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Started ${running.durationSeconds() / 60} minutes ago. The timer runs " +
                            "off the clock, so it keeps counting with the screen off.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        if (todaySeconds == null) "Ready when you are" else "Good session today",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        todaySeconds?.let {
                            "${it / 60} minutes across $todaySessions " +
                                if (todaySessions == 1) "session." else "sessions."
                        } ?: "Five minutes counts. Start whenever you pick the guitar up.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(Space.xs))
                if (running != null) {
                    Button(onClick = onEnd, modifier = Modifier.heightIn(min = 52.dp)) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.size(Space.sm))
                        Text("End session")
                    }
                } else {
                    Button(onClick = onStart, modifier = Modifier.heightIn(min = 52.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(Space.sm))
                        Text("Start session")
                    }
                }
            }
        }
        if (weeklyGoalMinutes == null) {
            // No goal set: the week's minutes still sit beside the button on a
            // wide card, so the hero shows progress either way.
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    main(Modifier.weight(1f))
                    Spacer(Modifier.size(Space.xl))
                    Column(
                        Modifier.semantics(mergeDescendants = true) {},
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            "$weekMinutes",
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "minutes this week",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                main(Modifier.fillMaxWidth())
            }
        } else if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                main(Modifier.weight(1f))
                Spacer(Modifier.size(Space.xl))
                GoalRing(weekMinutes, weeklyGoalMinutes, 132.dp)
            }
        } else {
            main(Modifier.fillMaxWidth())
            Spacer(Modifier.size(Space.xl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoalRing(weekMinutes, weeklyGoalMinutes, 96.dp)
            }
        }
    }
}

/**
 * The streak lamp: lit once the streak is real. The words beside it say the
 * same thing, so the lamp is never the only signal.
 */
@Composable
private fun StreakLamp(streak: Int) {
    val lit = streak > 0
    val scheme = MaterialTheme.colorScheme
    val words = when (streak) {
        0 -> "No streak yet"
        1 -> "1-day streak"
        else -> "$streak-day streak"
    }
    Row(
        Modifier
            .background(if (lit) scheme.primaryContainer else scheme.surfaceContainerHighest, CircleShape)
            .padding(horizontal = Space.md, vertical = Space.xs + Space.xxs)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp)
                .background(if (lit) RiffColors.lampLit else RiffColors.lampDark, CircleShape)
                .border(1.dp, scheme.outline.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = if (lit) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(Modifier.size(Space.sm))
        Text(
            words,
            style = MaterialTheme.typography.labelLarge,
            color = if (lit) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
    }
}

/**
 * The weekly-goal ring: the last seven days against the goal. The minutes
 * are written in the middle and the words underneath, so the ring is never
 * the only way to read progress.
 */
@Composable
private fun GoalRing(minutes: Long, goal: Int, diameter: Dp) {
    val fraction = (minutes.toFloat() / goal).coerceIn(0f, 1f)
    val met = fraction >= 1f
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = if (met) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val words = if (met) "Weekly goal met: $minutes minutes in the last seven days"
    else "$minutes of $goal minutes this week, ${goal - minutes} to go"
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = words },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(diameter)) {
                val stroke = Stroke(width = diameter.toPx() * 0.09f, cap = StrokeCap.Round)
                val inset = stroke.width / 2
                val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
                val topLeft = Offset(inset, inset)
                drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
                if (fraction > 0f) {
                    drawArc(fill, -90f, 360f * fraction, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$minutes",
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = LocalCondensedFont.current),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "of $goal min",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (diameter < 120.dp) {
            Spacer(Modifier.size(Space.lg))
            Column {
                Text(if (met) "Weekly goal met" else "Weekly goal", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (met) "$minutes minutes in the last seven days"
                    else "${goal - minutes} minutes to go, over the last seven days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private class QuickTile(val label: String, val hint: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun QuickStartTile(tile: QuickTile) {
    Panel(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight()
                .tactileClickable(onClickLabel = "Open ${tile.label}", onClick = tile.onClick)
                .semantics { role = Role.Button }
                .heightIn(min = 96.dp)
                .padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tile.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
            Column {
                Text(tile.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    tile.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A glance at the week, and the way into Stats. */
@Composable
private fun WeekCard(weekMinutes: Long, weekSessions: Int, streak: Int, onOpenStats: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight()
                .tactileClickable(onClickLabel = "Open stats", onClick = onOpenStats)
                .padding(Space.panel),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            // A text button, the same as the Lessons and Routines buttons on
            // today's plan beside it, so the two headers share a height and
            // their titles sit on one baseline.
            SectionHeader("This week") {
                TextButton(onClick = onOpenStats) {
                    Text("Stats")
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                Figure(if (weekMinutes <= 0) "0 min" else com.riffbuddy.ui.stats.minutes(weekMinutes * 60), "practiced", Modifier.weight(1f))
                Figure("$weekSessions", if (weekSessions == 1) "session" else "sessions", Modifier.weight(1f))
                Figure("$streak", "day streak", Modifier.weight(1f))
            }
        }
    }
}

/** A number in the condensed face over its label, read at a glance. */
@Composable
private fun Figure(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
            .padding(horizontal = Space.md, vertical = Space.sm)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun RecentSongsCard(recent: List<Song>, onOpenSong: (Song) -> Unit) {
    Panel(Modifier.fillMaxWidth(), contentPadding = Space.panel) {
        SectionHeader("Recent songs")
        Spacer(Modifier.size(Space.xs))
        recent.forEach { song ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .tactileClickable(onClickLabel = "Open ${song.title}") { onOpenSong(song) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (song.artist.isNotBlank()) {
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.size(Space.sm))
                StatusPill(song.status)
            }
        }
    }
}

/**
 * Lays [items] out [perRow] to a row, each cell the same width. With
 * [equalHeight] every cell in a row is also stretched to the tallest one, so a
 * card whose text wraps doesn't stand taller than its neighbors.
 */
@Composable
internal fun <T> Grid(items: List<T>, perRow: Int, gap: Dp, equalHeight: Boolean = false, cell: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        items.chunked(perRow).forEach { row ->
            Row(
                if (equalHeight) Modifier.height(IntrinsicSize.Max) else Modifier,
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                row.forEach {
                    // propagateMinConstraints hands the row's height to the card
                    // itself, which has no modifier of its own to fill with.
                    Box(
                        if (equalHeight) Modifier.weight(1f).fillMaxHeight() else Modifier.weight(1f),
                        propagateMinConstraints = equalHeight,
                    ) { cell(it) }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
