package com.riffbuddy.ui.theory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffbuddy.domain.music.Voicing

/**
 * A chord shape, drawn the way tab reads: strings run across, the
 * thickest at the bottom, the nut on the left and frets counting to the right.
 *
 * Chord books usually draw it upright, low string on the left. Drawn like tab,
 * with the thickest string at the bottom, a shape
 * looks exactly like the same shape on the Scales fretboard and in the tab
 * player, instead of the same thing turned ninety degrees.
 *
 * Finger numbers are inside the dots rather than beside the diagram. A number
 * next to it is a second thing to read and map across; a number in the dot is
 * the answer where the question is.
 */
@Composable
fun ChordDiagram(voicing: Voicing, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = MaterialTheme.colorScheme.primary
    val onDot = MaterialTheme.colorScheme.onPrimary
    val strings = voicing.frets.size

    // Shapes high up the neck are drawn from their own position rather than
    // with a row of empty frets before them.
    val fretted = voicing.frets.filter { it > 0 }
    val lowest = fretted.minOrNull() ?: 1
    val offset = if (lowest > 4) lowest - 1 else 0
    val shownFrets = 5

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(
            Modifier
                .width(140.dp)
                .height((strings * 15 + 28).dp)
                .semantics { contentDescription = describe(voicing) }
        ) {
            val left = 16.dp.toPx()                     // room for × and ○
            val top = 6.dp.toPx()
            val bottom = 22.dp.toPx()                   // room for "5fr" under the lowest dot
            val stepX = (size.width - left - 2.dp.toPx()) / shownFrets
            val stepY = (size.height - top - bottom) / (strings - 1).coerceAtLeast(1)
            // String 0 is the thickest, drawn at the bottom, as in tab.
            fun yOf(string: Int) = top + (strings - 1 - string) * stepY
            val stringsBottom = yOf(0)
            val dotRadius = (stepY * 0.42f).coerceAtMost(stepX * 0.36f)

            // The nut, only when the shape is actually at the nut.
            drawLine(
                color = if (offset == 0) onSurface else muted,
                start = Offset(left, top),
                end = Offset(left, stringsBottom),
                strokeWidth = if (offset == 0) 6f else 2f,
            )
            for (fret in 1..shownFrets) {
                val x = left + fret * stepX
                drawLine(muted.copy(alpha = 0.5f), Offset(x, top), Offset(x, stringsBottom), 1.5f)
            }
            for (string in 0 until strings) {
                // Thicker strings drawn thicker, so which way up it is never in doubt.
                val width = if (string < 3) 2.2f else 1.2f
                drawLine(muted, Offset(left, yOf(string)), Offset(left + shownFrets * stepX, yOf(string)), width)
            }

            voicing.frets.forEachIndexed { string, fret ->
                val y = yOf(string)
                when {
                    // An × or ○ before the nut, as every chord chart prints.
                    fret < 0 -> {
                        val layout = measurer.measure("×", TextStyle(fontSize = 11.sp))
                        drawText(layout, muted, Offset(left - 12.dp.toPx() - layout.size.width / 2f + 4f, y - layout.size.height / 2f))
                    }
                    fret == 0 -> {
                        drawCircle(muted, 5f, Offset(left - 9.dp.toPx() + 3f, y), style = Stroke(width = 1.5f))
                    }
                    else -> {
                        val x = left + (fret - offset - 0.5f) * stepX
                        drawCircle(dot, dotRadius, Offset(x, y))
                        voicing.fingers.getOrNull(string)?.takeIf { it > 0 }?.let { finger ->
                            val layout = measurer.measure("$finger", TextStyle(fontSize = 9.sp))
                            drawText(
                                layout, onDot,
                                Offset(x - layout.size.width / 2f, y - layout.size.height / 2f),
                            )
                        }
                    }
                }
            }

            if (offset > 0) {
                val layout = measurer.measure("${offset + 1}fr", TextStyle(fontSize = 9.sp, color = muted))
                // Below the lowest string's dot, not on it.
                drawText(layout, muted, Offset(left + stepX / 2f - layout.size.width / 2f, stringsBottom + dotRadius + 2.dp.toPx()))
            }
        }
        Text(
            voicing.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Spoken as a chord book would be read aloud, string by string. */
private fun describe(voicing: Voicing): String =
    voicing.name + ": " + voicing.frets.joinToString(", ") { fret ->
        when {
            fret < 0 -> "muted"
            fret == 0 -> "open"
            else -> "fret $fret"
        }
    }
