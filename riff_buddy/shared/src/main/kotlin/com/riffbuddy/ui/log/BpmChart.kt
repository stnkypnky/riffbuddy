package com.riffbuddy.ui.log

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.Stats
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The BPM-over-time line, one song at a time.
 *
 * Only songs with two or more tempo points are offered — a single point is a
 * number, not a trend, and the tempo list is already on the song page.
 * Points are spaced by date, not evenly, so a month's gap looks like one.
 */
@Composable
fun BpmChart(sessions: List<Session>, songs: List<Song>, zone: ZoneId) {
    val candidates = Stats.songsWithBpmHistory(sessions)
    val byId = songs.associateBy { it.id }
    val choices = candidates.filter { it in byId }
    if (choices.isEmpty()) return
    var chosen by remember { mutableStateOf(choices.first()) }
    val songId = chosen.takeIf { it in choices } ?: choices.first()
    val points = Stats.bpmHistory(sessions, songId)
    val song = byId.getValue(songId)

    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val low = points.minOf { it.bpm }
    val high = points.maxOf { it.bpm }
    val first = points.first()
    val last = points.last()

    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.riffbuddy.ui.SectionHeader("TEMPO OVER TIME")
            if (choices.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { id ->
                        FilterChip(selected = id == songId, onClick = { chosen = id }, label = { Text(byId.getValue(id).title) })
                    }
                }
            }
            Text(
                "${song.title}: ${first.bpm} → ${last.bpm} BPM" +
                    if (high > last.bpm) " (best $high)" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth()) {
                // The axis labels are text beside the canvas, not drawn in it,
                // so they scale with the font setting and never collide with the line.
                Column(Modifier.height(120.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("$high", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$low", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Canvas(
                    Modifier.weight(1f).height(120.dp).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
                        .semantics {
                            contentDescription = "${song.title} tempo, ${points.size} sessions, from ${first.bpm} " +
                                "to ${last.bpm} BPM, fastest $high"
                        },
                ) {
                    // Differences taken in Long before any Float: epoch millis
                    // are ~1.8e12, where a Float's steps are over two minutes
                    // wide, and two sessions an evening apart collapsed or
                    // overshot the edge.
                    val t0 = first.at.toEpochMilli()
                    val span = (last.at.toEpochMilli() - t0).coerceAtLeast(1)
                    val range = (high - low).coerceAtLeast(1).toFloat()
                    fun at(i: Int): Offset {
                        val p = points[i]
                        val x = ((p.at.toEpochMilli() - t0).toDouble() / span).toFloat() * size.width
                        // A flat line (every point the same) sits mid-height.
                        val y = if (high == low) size.height / 2 else size.height - (p.bpm - low) / range * size.height
                        return Offset(x, y)
                    }
                    drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                    drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    val path = Path().apply {
                        moveTo(at(0).x, at(0).y)
                        for (i in 1 until points.size) lineTo(at(i).x, at(i).y)
                    }
                    drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))
                    points.indices.forEach { drawCircle(line, 3.5.dp.toPx(), at(it)) }
                }
            }
            // Two points on one day would both read "21 Sep"; the time says more.
            val sameDay = first.at.atZone(zone).toLocalDate() == last.at.atZone(zone).toLocalDate()
            val axis = if (sameDay) TIME else DAY
            Row(Modifier.fillMaxWidth().padding(start = 32.dp)) {
                Text(axis.format(first.at.atZone(zone)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(axis.format(last.at.atZone(zone)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
