package com.riffbuddy.ui.songs

import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.ui.help.HelpButton
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.EmojiEvents
import com.riffbuddy.ui.theme.tactileClickable
import com.riffbuddy.domain.music.Tuning

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.time.Duration
import java.time.Instant

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

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Songs", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Button(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Add")
            }
            HelpButton(HelpTopic.SONGS)
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Title, artist or tag") },
        )

        // One row for all three. As chips per option they wrapped to about
        // six rows on a phone and pushed the songs off the screen;
        // folded into menus, the list starts right under the search box.
        // No separate Clear: each menu's first option clears it, and a Clear
        // button wrapped onto a row of its own on the phone.
        val tuning = usedTunings.firstOrNull { it.id == tuningId }
        com.riffbuddy.ui.ChipFlow(Modifier.padding(vertical = 10.dp)) {
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
            songs.isEmpty() -> EmptyState(
                "Nothing in the songbook yet",
                "Add a song you are learning. Everything else — tone, tempo, practice " +
                    "history — hangs off it.",
            )
            // The one place a Clear earns its room: the filters have hidden
            // everything, so undoing them is the only useful next step.
            visible.isEmpty() -> EmptyState(
                "No songs match",
                "Try a different search, or clear the filters.",
                onClear = { query = ""; status = null; tuningId = null },
            )
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(visible, key = { it.id }) { song ->
                    SongRow(song, practiceSeconds[song.id] ?: 0L, lastPracticed[song.id], progress[song.id]) {
                        onOpenSong(song)
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
private fun SongRow(song: Song, seconds: Long, lastPracticed: Instant?, progress: Float?, onClick: () -> Unit) {
    com.riffbuddy.ui.Panel(Modifier.fillMaxWidth().tactileClickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (song.status == SongStatus.MASTERED) {
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.EmojiEvents,
                        contentDescription = "Mastered",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (song.artist.isNotBlank()) {
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = onClick, label = { Text(song.status.label) })
                song.targetBpm?.let {
                    Text(
                        "$it BPM",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    practiceSummary(seconds, lastPracticed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // How far through its parts, when it has any.
            progress?.let { p ->
                val amber = MaterialTheme.colorScheme.primary
                val track = MaterialTheme.colorScheme.surfaceContainerHighest
                androidx.compose.foundation.Canvas(
                    Modifier.fillMaxWidth().height(4.dp).semantics { contentDescription = "Parts ${(p * 100).toInt()} percent mastered" }
                ) {
                    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                    drawRoundRect(track, cornerRadius = r)
                    drawRoundRect(amber, size = androidx.compose.ui.geometry.Size(size.width * p, size.height), cornerRadius = r)
                }
            }
        }
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
        minutes >= 60 -> "%.1f h".format(minutes / 60.0)
        else -> "$minutes min"
    }
    val ago = lastPracticed?.let {
        val days = Duration.between(it, Instant.now()).toDays()
        when {
            days <= 0L -> "today"
            days == 1L -> "yesterday"
            days < 30L -> "$days days ago"
            else -> "${days / 30} months ago"
        }
    }
    return if (ago == null) total else "$total · $ago"
}

@Composable
private fun EmptyState(title: String, body: String, onClear: (() -> Unit)? = null) {
    com.riffbuddy.ui.Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onClear != null) {
                TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear search and filters") }
            }
        }
    }
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
