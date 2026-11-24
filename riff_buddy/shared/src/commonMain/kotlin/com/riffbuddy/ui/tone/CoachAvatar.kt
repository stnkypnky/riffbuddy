package com.riffbuddy.ui.tone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffbuddy.ui.theme.RiffColors

/**
 * The coach's face: a guitar pick in a disc of the finish's accent.
 *
 * Drawn rather than loaded, so it takes the color of whichever finish is on
 * and stays sharp at any size. A pick rather than a face because the coach is
 * a tool, not a person pretending to be one. Decorative: whoever is speaking
 * is always also said in words next to it.
 */
@Composable
fun CoachAvatar(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val scheme = MaterialTheme.colorScheme
    val disc = scheme.primary
    val discLow = scheme.primaryContainer
    val pick = scheme.onPrimary
    val ring = RiffColors.finishEdge
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = Offset(r, r)
        // Lit from the top left, like the panels: the accent fades toward its container.
        drawCircle(
            Brush.linearGradient(listOf(disc, discLow), start = Offset.Zero, end = Offset(r * 2f, r * 2f)),
            radius = r,
            center = c,
        )
        drawCircle(ring.copy(alpha = 0.35f), radius = r - 0.5.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))

        // A rounded triangle, wide shoulder up, point down: the classic pick.
        val w = r * 1.02f
        val top = c.y - r * 0.50f
        val bottom = c.y + r * 0.60f
        val left = c.x - w / 2f
        val right = c.x + w / 2f
        val path = Path().apply {
            moveTo(c.x, top - r * 0.04f)
            cubicTo(right - w * 0.05f, top - r * 0.05f, right + w * 0.08f, top + r * 0.18f, right - w * 0.06f, top + r * 0.42f)
            cubicTo(right - w * 0.22f, top + r * 0.72f, c.x + w * 0.08f, bottom, c.x, bottom)
            cubicTo(c.x - w * 0.08f, bottom, left + w * 0.22f, top + r * 0.72f, left + w * 0.06f, top + r * 0.42f)
            cubicTo(left - w * 0.08f, top + r * 0.18f, left + w * 0.05f, top - r * 0.05f, c.x, top - r * 0.04f)
            close()
        }
        drawPath(path, pick)
        // A short highlight across the pick's shoulder gives it a little depth.
        drawLine(
            disc.copy(alpha = 0.35f),
            start = Offset(c.x - w * 0.22f, top + r * 0.16f),
            end = Offset(c.x + w * 0.10f, top + r * 0.10f),
            strokeWidth = r * 0.07f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}
