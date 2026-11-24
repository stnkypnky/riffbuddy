package com.riffbuddy.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.ui.semantics.semantics
import com.riffbuddy.ui.EmptyState
import com.riffbuddy.ui.PageHeader
import com.riffbuddy.ui.theme.Space
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffbuddy.data.SavedToneCard
import com.riffbuddy.domain.DayPart
import com.riffbuddy.domain.Milestone
import com.riffbuddy.domain.MilestoneGroup
import com.riffbuddy.domain.Milestones
import com.riffbuddy.domain.Recording
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.SongPart
import com.riffbuddy.domain.SongStatus
import com.riffbuddy.domain.StatsRange
import com.riffbuddy.domain.StatsSnapshot
import com.riffbuddy.domain.format
import com.riffbuddy.domain.localDate
import com.riffbuddy.domain.localZone
import com.riffbuddy.domain.music.Tuning
import com.riffbuddy.domain.toFixed
import com.riffbuddy.ui.ChipFlow
import com.riffbuddy.ui.Panel
import com.riffbuddy.ui.SectionHeader
import com.riffbuddy.ui.duration
import com.riffbuddy.ui.tactile.FilterChip
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.DayOfWeek

/**
 * The Stats tab: everything the practice log knows, for a range,
 * drawn so it moves. Figures come from [StatsSnapshot], worked out off the
 * main thread whenever the data or the range changes and never per frame.
 * The charts only draw.
 */
@Composable
fun StatsScreen(
    sessions: List<Session>,
    songs: List<Song>,
    parts: List<SongPart>,
    tunings: List<Tuning>,
    takes: List<Recording>,
    toneCards: List<SavedToneCard>,
    streakMinutes: Int,
    earnedAt: Map<String, Instant>,
) {
    var range by rememberSaveable { mutableStateOf(StatsRange.MONTH) }
    val zone = localZone()

    // Both the chosen range and all-time (milestones are all-time), off the main thread.
    val snaps by produceState<Pair<StatsSnapshot, StatsSnapshot>?>(null, range, sessions, songs, parts, tunings, takes, toneCards, streakMinutes) {
        value = withContext(Dispatchers.Default) {
            val now = Clock.System.now()
            fun of(r: StatsRange) = StatsSnapshot.of(
                r, zone, now, sessions, songs, parts, tunings,
                takeTimes = takes.map { it.createdAt }, toneCardTimes = toneCards.map { it.createdAt },
                streakMinutes = streakMinutes,
            )
            of(range) to if (range == StatsRange.ALL) of(range) else of(StatsRange.ALL)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Chart panels two to a row on a wide window; each chart keeps a
    // comfortable width and the page doesn't become one long scroll.
    val wide = maxWidth >= 760.dp
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = if (wide) Space.screenWide else Space.screen, vertical = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        PageHeader("Stats", eyebrow = "Your practice", subtitle = "Everything the log knows, for the range you pick.")
        ChipFlow {
            StatsRange.entries.forEach { r ->
                FilterChip(selected = r == range, onClick = { range = r }, label = { Text(r.label) }, modifier = Modifier.heightIn(min = 40.dp))
            }
        }

        val pair = snaps
        if (pair == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        val (s, all) = pair

        Headline(s, columns = if (wide) 4 else 2)
        if (all.isEmpty) {
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    icon = Icons.Filled.Insights,
                    title = "Nothing logged yet",
                    body = "Start a session from Home, and this fills up as you practice.",
                )
            }
        } else if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                    Section("Practice calendar") { HeatMap(s.heat, s.heatStart, s.today, s.range.heatWeeks) }
                    Habits(s)
                    Tempo(s)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                    SongsSection(s)
                    Gear(s)
                }
            }
        } else {
            Section("Practice calendar") { HeatMap(s.heat, s.heatStart, s.today, s.range.heatWeeks) }
            Habits(s)
            SongsSection(s)
            Tempo(s)
            Gear(s)
        }
        MilestonesSection(all, earnedAt)
    }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Panel(Modifier.fillMaxWidth(), contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            SectionHeader(title)
            content()
        }
    }
}

/** A hint where a chart would be, saying how to fill it. */
@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

// ---- Headline figures ------------------------------------------------------------

@Composable
private fun Headline(s: StatsSnapshot, columns: Int) {
    val figures: List<@Composable () -> Unit> = listOf(
        { Figure("Practiced", s.totalSeconds.toFloat(), { duration(it.toLong()).replace("under a minute", "0 min") }) },
        { Figure("Sessions", s.sessionCount.toFloat(), { it.toInt().toString() }) },
        { Figure("Streak", s.currentStreak.toFloat(), { "${it.toInt()} ${if (it.toInt() == 1) "day" else "days"}" }, "Best ${s.bestStreak}") },
        { Figure("Mastered", s.songsMastered.toFloat(), { "${it.toInt()} ${if (it.toInt() == 1) "song" else "songs"}" }, "${s.partsMastered} parts") },
    )
    com.riffbuddy.ui.Grid(figures, columns, Space.md) { it() }
}

/** A headline figure: its label, then the number big in the condensed face. */
@Composable
private fun Figure(label: String, value: Float, format: (Float) -> String, sub: String? = null) {
    Panel(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.panel, vertical = Space.md).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CountUpText(value, format, MaterialTheme.typography.headlineLarge, MaterialTheme.colorScheme.onSurface)
            Text(sub ?: " ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---- Habits ----------------------------------------------------------------------

private const val DAY = "EEE, MMM d"

@Composable
private fun Habits(s: StatsSnapshot) {
    Section("Habits") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Small("Average session", duration(s.averageSeconds))
            Small("Longest", duration(s.longestSeconds))
            s.busiestDay?.let { (d, secs) -> Small("Busiest day", "${d.format(DAY)} · ${duration(secs)}") }
        }
        if (s.sessionCount == 0) {
            Hint("No sessions in this range.")
            return@Section
        }
        Text("When you practice", style = MaterialTheme.typography.labelLarge)
        ColumnBars(
            DayPart.entries.map { it.label },
            DayPart.entries.map { (s.dayParts[it] ?: 0L).toFloat() },
            "Practice by time of day: " + DayPart.entries.joinToString { "${it.label} ${duration(s.dayParts[it] ?: 0)}" },
        )
        Text("By day of the week", style = MaterialTheme.typography.labelLarge)
        ColumnBars(
            DayOfWeek.entries.map { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) },
            DayOfWeek.entries.map { (s.weekdays[it] ?: 0L).toFloat() },
            "Practice by weekday",
        )
        if (s.ratings.size >= 2) {
            Text("How sessions felt (1–5)", style = MaterialTheme.typography.labelLarge)
            Sparkline(s.ratings.map { it.toFloat() }, minValue = 1f, maxValue = 5f)
        }
    }
}

@Composable
private fun Small(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Songs -----------------------------------------------------------------------

@Composable
private fun SongsSection(s: StatsSnapshot) {
    Section("Songs") {
        Text("Most practiced", style = MaterialTheme.typography.labelLarge)
        if (s.topSongs.isEmpty()) Hint("Tag a session with a song (when you end it, or in the Log) to see your top songs.")
        else GrowBars(s.topSongs.map { it.label }, s.topSongs.map { it.seconds.toFloat() }, { duration(s.topSongs[it].seconds) })

        Text("Songbook", style = MaterialTheme.typography.labelLarge)
        val statuses = SongStatus.entries
        GrowBars(
            statuses.map { it.label },
            statuses.map { (s.statusCounts[it] ?: 0).toFloat() },
            { i -> (s.statusCounts[statuses[i]] ?: 0).toString() },
        )

        Text(
            "Parts mastered" + if (s.partsMastered > 0) " · ${s.partsMastered} so far" else "",
            style = MaterialTheme.typography.labelLarge,
        )
        if (s.partsMasteredOverTime.isEmpty()) Hint("Break a song into parts on its page and move them to Mastered.")
        else StepLine(s.partsMasteredOverTime, s.today)
        s.averageDaysToMaster?.let {
            Hint("On average a song takes ${it.toFixed(0)} days from being added to being mastered.")
        }
    }
}

// ---- Tempo -----------------------------------------------------------------------

@Composable
private fun Tempo(s: StatsSnapshot) {
    Section("Tempo") {
        if (s.climbs.isEmpty()) {
            Hint("Tempos come from the speed trainer and the looper's trainer. Use either on a song to see it climb.")
            return@Section
        }
        s.biggestJump?.let { j ->
            Text("Biggest climb: ${j.label}, ${j.first} → ${j.best} BPM (+${j.climb})", style = MaterialTheme.typography.titleSmall)
        }
        s.climbs.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${c.first} → ${c.best} BPM" + if (c.climb > 0) "  (+${c.climb})" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Sparkline(c.points.map { it.second.toFloat() }, Modifier.widthIn(min = 96.dp, max = 160.dp).heightIn(min = 36.dp))
            }
        }
    }
}

// ---- Gear ------------------------------------------------------------------------

@Composable
private fun Gear(s: StatsSnapshot) {
    Section("Tunings, keys and takes") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (s.tunings.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tunings", style = MaterialTheme.typography.labelLarge)
                    val top = s.tunings.take(5)
                    Ring(top.map { it.label }, top.map { it.seconds.toFloat() }, "${s.tunings.size}\n${if (s.tunings.size == 1) "tuning" else "tunings"}")
                }
            }
            if (s.keys.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Keys", style = MaterialTheme.typography.labelLarge)
                    val top = s.keys.take(5)
                    Ring(top.map { it.label }, top.map { it.seconds.toFloat() }, "${s.keys.size}\n${if (s.keys.size == 1) "key" else "keys"}")
                }
            }
        }
        if (s.tunings.isEmpty()) Hint("Tag sessions with songs to see which tunings and keys you spend your time in.")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Small("Takes recorded", s.takes.toString())
            Small("Tone cards made", s.toneCards.toString())
        }
    }
}

// ---- Milestones ------------------------------------------------------------------

@Composable
private fun MilestonesSection(all: StatsSnapshot, earnedAt: Map<String, Instant>) {
    val earned = Milestones.ALL.count { it.earned(all) }
    // By default each series shows what's earned plus the next tier,
    // so 58 badges don't bury the ones worth aiming for.
    var showAll by rememberSaveable { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    val shown = if (showAll) Milestones.ALL else Milestones.nextUp(all)
    Section("Milestones · $earned of ${Milestones.ALL.size}") {
        ChipFlow {
            FilterChip(selected = !showAll, onClick = { showAll = false }, label = { Text("Next up") }, modifier = Modifier.heightIn(min = 40.dp))
            FilterChip(selected = showAll, onClick = { showAll = true }, label = { Text("All ${Milestones.ALL.size}") }, modifier = Modifier.heightIn(min = 40.dp))
        }
        Hint("Tap a badge for what it takes and where you stand.")
        // An even grid rather than a left-packed flow: as many columns of about
        // 104 dp as the card's own width holds, the same count in every group so
        // the badges line up down the card, and the spare width shared out.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + Space.sm) / (BADGE_CELL + Space.sm)).toInt().coerceAtLeast(3)
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        MilestoneGroup.entries.forEach { group ->
            val inGroup = shown.filter { it.group == group }
            if (inGroup.isEmpty()) return@forEach
            val groupAll = Milestones.ALL.filter { it.group == group }
            Text(
                "${group.label.uppercase()} · ${groupAll.count { it.earned(all) }} of ${groupAll.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Earned first, then the closest to being earned.
            val sorted = inGroup.sortedWith(compareByDescending<Milestone> { it.earned(all) }.thenByDescending { it.progress(all) }.thenBy { it.target })
            com.riffbuddy.ui.Grid(sorted, columns, Space.sm) { m ->
                        val on = m.earned(all)
                        val date = earnedAt[m.key]?.localDate()
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Badge(
                                m.title, on, m.progress(all),
                                if (on) (date?.let { "Earned ${it.format(DAY)}. " } ?: "") + m.description else m.description,
                                selected = open == m.key,
                                onClick = { open = if (open == m.key) null else m.key },
                            )
                            if (open == m.key) MilestoneTooltip(m, all, date, onDismiss = { open = null })
                        }
            }
        }
        }
        }
    }
}

/** A badge's cell: its 92 dp disc and caption, plus a little air either side. */
private val BADGE_CELL = 104.dp
