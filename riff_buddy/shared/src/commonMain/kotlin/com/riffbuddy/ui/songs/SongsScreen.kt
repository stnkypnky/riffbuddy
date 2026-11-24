package com.riffbuddy.ui.songs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.riffbuddy.ui.EmptyState
import com.riffbuddy.ui.PageHeader
import com.riffbuddy.ui.Panel
import com.riffbuddy.ui.theme.RiffColors
import com.riffbuddy.ui.theme.Space
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.domain.music.Tuning
import com.riffbuddy.domain.toFixed
import com.riffbuddy.ui.help.HelpButton
import com.riffbuddy.ui.theme.tactileClickable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import com.riffbuddy.ui.tactile.AssistChip
import com.riffbuddy.ui.tactile.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.SongSort
import com.riffbuddy.domain.SongStatus
import com.riffbuddy.domain.Songbook
import kotlin.time.Instant
import kotlin.time.Clock

/**
 * The songbook: search, filter, sort, and a way in to each song.
 */
@Composable
fun SongsScreen(
    songs: List<Song>,
    practiceSeconds: Map<Long, Long>,
    lastPracticed: Map<Long, Instant>,
    onOpenSong: (Song) -> Unit,
    onAddSong: (title: String, artist: String) -> Unit,
    tunings: List<Tuning> = emptyList(),
    /** How far through its parts each song is, for songs that have parts. */
    progress: Map<Long, Float> = emptyMap(),
) {
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<SongStatus?>(null) }
    var tuningId by remember { mutableStateOf<Long?>(null) }
    var sort by remember { mutableStateOf(SongSort.RECENT) }
    var adding by remember { mutableStateOf(false) }

    val visible = Songbook.filterAndSort(songs, query, status, tuningId, sort = sort, lastPracticed = lastPracticed)
    val usedTunings = tunings.filter { t -> songs.any { it.tuningId == t.id } }

    Column(Modifier.fillMaxSize().padding(horizontal = Space.screen)) {
        val learning = songs.count { it.status == SongStatus.LEARNING || it.status == SongStatus.POLISHING }
        PageHeader(
            title = "Songs",
            eyebrow = "Songbook",
            subtitle = when {
                songs.isEmpty() -> null
                learning > 0 -> "${songs.size} ${if (songs.size == 1) "song" else "songs"} · $learning in progress"
                else -> "${songs.size} ${if (songs.size == 1) "song" else "songs"}"
            },
            modifier = Modifier.padding(top = Space.lg, bottom = Space.md),
            actions = {
                Button(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.size(Space.xs + Space.xxs))
                    Text("Add")
                }
                HelpButton(HelpTopic.SONGS)
            },
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Title, artist or tag") },
        )

        // One row for all three. As chips per option they wrapped to about
        // six rows on a phone and pushed the songs off the screen;
        // folded into menus, the list starts right under the search box.
        // No separate Clear: each menu's first option clears it, and a Clear
        // button wrapped onto a row of its own on the phone.
        val tuning = usedTunings.firstOrNull { it.id == tuningId }
        com.riffbuddy.ui.ChipFlow(Modifier.padding(vertical = Space.md)) {
            com.riffbuddy.ui.MenuChip(
                text = sort.short,
                options = SongSort.entries,
                selected = sort,
                label = { it.label },
                onPick = { sort = it },
                description = "Sort",
                icon = Icons.AutoMirrored.Filled.Sort,
            )
            com.riffbuddy.ui.MenuChip(
                text = status?.label ?: "Status",
                options = listOf<SongStatus?>(null) + SongStatus.entries,
                selected = status,
                label = { it?.label ?: "Any status" },
                onPick = { status = it },
                description = "Status",
                active = status != null,
            )
            // Only tunings some song is in — thirty options for tunings
            // nobody uses would bury the two that matter.
            if (usedTunings.isNotEmpty()) {
                com.riffbuddy.ui.MenuChip(
                    text = tuning?.name ?: "Tuning",
                    options = listOf<com.riffbuddy.domain.music.Tuning?>(null) + usedTunings.sortedBy { it.name.lowercase() },
                    selected = tuning,
                    label = { it?.name ?: "Any tuning" },
                    onPick = { tuningId = it?.id },
                    description = "Tuning",
                    active = tuning != null,
                )
            }
        }

        when {
            songs.isEmpty() -> Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    icon = Icons.Filled.MusicNote,
                    title = "Nothing in the songbook yet",
                    body = "Add a song you are learning. Everything else — tone, tempo, practice " +
                        "history — hangs off it.",
                    action = {
                        Button(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.size(Space.xs + Space.xxs))
                            Text("Add your first song")
                        }
                    },
                )
            }
            // The one place a Clear earns its room: the filters have hidden
            // everything, so undoing them is the only useful next step.
            visible.isEmpty() -> Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = "No songs match",
                    body = "Try a different search, or clear the filters.",
                    action = {
                        TextButton(onClick = { query = ""; status = null; tuningId = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Clear search and filters")
                        }
                    },
                )
            }
            // On a wide window the cards sit two to a row, so a long songbook
            // doesn't become one narrow strip down the middle of the screen.
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = if (maxWidth >= 760.dp) 2 else 1
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                    contentPadding = PaddingValues(top = Space.xxs, bottom = Space.xl),
                ) {
                    items(visible, key = { it.id }) { song ->
                        SongRow(
                            song,
                            practiceSeconds[song.id] ?: 0L,
                            lastPracticed[song.id],
                            progress[song.id],
                            tunings.firstOrNull { it.id == song.tuningId },
                        ) { onOpenSong(song) }
                    }
                }
            }
        }
    }

    if (adding) {
        AddSongDialog(
            existing = songs,
            onDismiss = { adding = false },
            onAdd = { title, artist -> onAddSong(title, artist); adding = false },
        )
    }
}

@Composable
private fun SongRow(
    song: Song,
    seconds: Long,
    lastPracticed: Instant?,
    progress: Float?,
    tuning: Tuning?,
    onClick: () -> Unit,
) {
    // The status's color runs down the card's left edge; its name is always
    // written in the pill beside the title, so the color is never the only cue.
    val accent = statusColor(song.status)
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).tactileClickable(onClickLabel = "Open ${song.title}", onClick = onClick)) {
            Box(Modifier.width(Space.xs).fillMaxHeight().background(accent))
            Column(
                Modifier.weight(1f).padding(start = Space.md, end = Space.panel, top = Space.panel, bottom = Space.panel),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (song.artist.isNotBlank()) {
                            Text(
                                song.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.size(Space.sm))
                    StatusPill(song.status)
                }
                val facts = buildList {
                    song.key?.takeIf { it.isNotBlank() }?.let { add("Key $it") }
                    tuning?.let { add(it.name) }
                    if (song.capo > 0) add("Capo ${song.capo}")
                    song.timeSignature?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                if (facts.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs + Space.xxs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        facts.forEach { FactChip(it) }
                    }
                }
                song.targetBpm?.let { BpmProgress(song.currentBpm, it) }
                progress?.let { p ->
                    Meter(
                        fraction = p,
                        label = "Parts",
                        value = "${(p * 100).toInt()}% mastered",
                        description = "Parts ${(p * 100).toInt()} percent mastered",
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    practiceSummary(seconds, lastPracticed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Current tempo against the goal: the bar fills as the song speeds up. */
@Composable
private fun BpmProgress(current: Int?, target: Int) {
    val fraction = ((current ?: 0).toFloat() / target).coerceIn(0f, 1f)
    Meter(
        fraction = fraction,
        label = "Tempo",
        value = if (current == null) "Goal $target BPM" else "$current of $target BPM",
        description = if (current == null) "Target tempo $target beats per minute, no current tempo yet"
        else "At $current of $target beats per minute",
        color = if (fraction >= 1f) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Meter(fraction: Float, label: String, value: String, description: String, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            if (fraction > 0f) drawRoundRect(color, size = Size(size.width * fraction, size.height), cornerRadius = r)
        }
    }
}

@Composable
private fun FactChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(horizontal = Space.sm, vertical = Space.xs),
    )
}

/**
 * A song's status as a color: the further along, the warmer, ending in the
 * "done" color. Always shown with the status's name.
 */
@Composable
internal fun statusColor(status: SongStatus): Color = when (status) {
    SongStatus.WISHLIST -> MaterialTheme.colorScheme.outline
    SongStatus.LEARNING -> MaterialTheme.colorScheme.primary
    SongStatus.POLISHING -> RiffColors.lampLit
    SongStatus.PERFORMANCE_READY -> MaterialTheme.colorScheme.tertiary
    SongStatus.MASTERED -> MaterialTheme.colorScheme.tertiary
}

/** The status in words, in a small pill tinted with its color. */
@Composable
internal fun StatusPill(status: SongStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Row(
        modifier
            .background(color.copy(alpha = 0.14f), CircleShape)
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = Space.sm + Space.xxs, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status == SongStatus.MASTERED) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(Space.xs))
        } else {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.size(Space.xs + Space.xxs))
        }
        Text(status.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** The sort chip's own words: the menu has room for the full label, the chip doesn't. */
private val SongSort.short: String
    get() = when (this) {
        SongSort.RECENT -> "Recent"
        SongSort.TITLE -> "A–Z"
        SongSort.DIFFICULTY -> "Difficulty"
        SongSort.ADDED -> "Newest"
    }

/** Each song shows total time practiced and when it was last touched. */
private fun practiceSummary(seconds: Long, lastPracticed: Instant?): String {
    if (seconds <= 0 && lastPracticed == null) return "Not practiced yet"
    val minutes = seconds / 60
    val total = when {
        minutes >= 60 -> "${(minutes / 60.0).toFixed(1)} h"
        else -> "$minutes min"
    }
    val ago = lastPracticed?.let {
        val days = (Clock.System.now() - it).inWholeDays
        when {
            days <= 0L -> "today"
            days == 1L -> "yesterday"
            days < 30L -> "$days days ago"
            else -> "${days / 30} months ago"
        }
    }
    return if (ago == null) total else "$total · $ago"
}

/**
 * Adding a song is two fields, because the rest is optional and typing with
 * a guitar on is to be avoided. Everything else is
 * edited on the song's own page, later, sitting down.
 */
@Composable
private fun AddSongDialog(
    existing: List<Song>,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    val duplicate = Songbook.findDuplicate(existing, title, artist)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a song") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                )
                // Warn, but do not block. Two songs can share a name, and
                // being told "no" by an app that is wrong is worse than a duplicate.
                if (duplicate != null) {
                    Text(
                        "\"${duplicate.title}\" by ${duplicate.artist.ifBlank { "unknown" }} is " +
                            "already in your songbook. You can add it again if you mean to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(title.trim(), artist.trim()) },
                enabled = title.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
