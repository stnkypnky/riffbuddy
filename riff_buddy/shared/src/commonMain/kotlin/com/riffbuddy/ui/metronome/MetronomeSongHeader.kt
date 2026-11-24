package com.riffbuddy.ui.metronome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import com.riffbuddy.ui.tactile.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.Song

/**
 * The song context: which song this tempo belongs to, and — once the tempo
 * has moved off the song's target — the offer to make the new tempo the target.
 *
 * An offer that stays put, not a dialog: tempo changes many times while
 * working a passage, and a dialog on each change would be a nag. The offer
 * simply tracks the dial until it is taken or the song is let go.
 */
@Composable
fun MetronomeSongHeader(song: Song, bpm: Int, onUpdateTarget: (Int) -> Unit, onRelease: () -> Unit) {
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("For ${song.title}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        song.targetBpm?.let { "Target $it BPM" } ?: "No target tempo yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRelease, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Not for this song")
                }
            }
            if (bpm != song.targetBpm) {
                TextButton(onClick = { onUpdateTarget(bpm) }) {
                    Text("Update this song's target BPM to $bpm?")
                }
            }
        }
    }
}
