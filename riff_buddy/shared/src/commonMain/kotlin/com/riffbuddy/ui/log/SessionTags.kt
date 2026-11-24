package com.riffbuddy.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.SessionTag
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.SongPart
import com.riffbuddy.domain.Tagging
import com.riffbuddy.ui.ChipFlow
import com.riffbuddy.ui.tactile.FilterChip
import com.riffbuddy.ui.tactile.TextButton

/**
 * "What did you work on?" Tap songs, then their parts if you like;
 * the minutes split evenly and can be moved around. All optional: nothing
 * tapped means nothing tagged.
 *
 * [initial] are tags already on the session, when editing one.
 */
@Composable
fun SessionTagPicker(
    candidates: List<Song>,
    parts: Map<Long, List<SongPart>>,
    totalMinutes: Int,
    initial: List<SessionTag> = emptyList(),
    onChange: (List<SessionTag>) -> Unit,
) {
    val songs = remember { mutableStateListOf<Long>().apply { addAll(initial.map { it.songId }.distinct()) } }
    val chosenParts = remember { mutableStateListOf<Long>().apply { addAll(initial.mapNotNull { it.partId }) } }
    // Minutes per target, keyed "song:part" (part blank for the whole song).
    val minutes = remember {
        mutableStateMapOf<String, Int>().apply { initial.forEach { put(key(it.songId, it.partId), it.seconds / 60) } }
    }

    // What the time is split across: each chosen part, or the song itself when
    // none of its parts are chosen.
    val targets = songs.flatMap { songId ->
        val mine = chosenParts.filter { id -> parts[songId].orEmpty().any { it.id == id } }
        if (mine.isEmpty()) listOf(songId to null) else mine.map { songId to it }
    }

    fun resplit() {
        val shares = Tagging.split(totalMinutes, targets.size)
        minutes.clear()
        targets.forEachIndexed { i, (s, p) -> minutes[key(s, p)] = shares[i] }
    }

    // A new selection resets to an even split; editing an existing session starts from what it had.
    val signature = targets.joinToString { key(it.first, it.second) }
    LaunchedEffect(signature) {
        if (targets.any { key(it.first, it.second) !in minutes }) resplit()
        onChange(targets.map { (s, p) -> SessionTag(s, p, (minutes[key(s, p)] ?: 0) * 60) })
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipFlow {
            candidates.forEach { song ->
                FilterChip(
                    selected = song.id in songs,
                    onClick = {
                        if (song.id in songs) {
                            songs.remove(song.id)
                            chosenParts.removeAll { id -> parts[song.id].orEmpty().any { it.id == id } }
                        } else songs.add(song.id)
                    },
                    label = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }
        songs.forEach { songId ->
            val songParts = parts[songId].orEmpty()
            if (songParts.isNotEmpty()) {
                Text(
                    "Parts of ${candidates.firstOrNull { it.id == songId }?.title ?: "this song"} (optional)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipFlow {
                    songParts.forEach { part ->
                        FilterChip(
                            selected = part.id in chosenParts,
                            onClick = { if (part.id in chosenParts) chosenParts.remove(part.id) else chosenParts.add(part.id) },
                            label = { Text(part.name) },
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
            }
        }
        if (targets.size > 1) {
            targets.forEach { (s, p) ->
                val title = candidates.firstOrNull { it.id == s }?.title ?: "Song"
                val partName = p?.let { id -> parts[s].orEmpty().firstOrNull { it.id == id }?.name }
                val k = key(s, p)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (partName != null) "$title · $partName" else title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        minutes[k] = ((minutes[k] ?: 0) - 1).coerceAtLeast(0)
                        onChange(targets.map { (ss, pp) -> SessionTag(ss, pp, (minutes[key(ss, pp)] ?: 0) * 60) })
                    }) { Text("−") }
                    Text("${minutes[k] ?: 0} min", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(52.dp))
                    TextButton(onClick = {
                        minutes[k] = (minutes[k] ?: 0) + 1
                        onChange(targets.map { (ss, pp) -> SessionTag(ss, pp, (minutes[key(ss, pp)] ?: 0) * 60) })
                    }) { Text("+") }
                }
            }
        }
    }
}

private fun key(song: Long, part: Long?) = "$song:${part ?: ""}"

/**
 * Songs to offer, likeliest first: anything already in this session, then
 * what was practiced recently, then songs being learned or polished, then the
 * rest alphabetically (a mastered song still gets played to keep it up).
 * Twelve at most, so the dialog stays short.
 */
fun tagCandidates(
    songs: List<Song>,
    recentSongIds: List<Long>,
    alreadyTagged: Collection<Long> = emptyList(),
): List<Song> {
    val active = songs.filter {
        it.status == com.riffbuddy.domain.SongStatus.LEARNING || it.status == com.riffbuddy.domain.SongStatus.POLISHING
    }.map { it.id }
    val rest = songs.sortedBy { it.title.lowercase() }.map { it.id }
    val order = (alreadyTagged + recentSongIds + active + rest).distinct()
    val byId = songs.associateBy { it.id }
    return order.mapNotNull { byId[it] }.take(12)
}
