package com.riffbuddy.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffbuddy.ui.theme.tactileClickable
import com.riffbuddy.ui.theme.LocalReducedMotion
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.min

// ---- Morphing values -------------------------------------------------------------

internal class MorphState(
    val from: List<Float>,
    val to: List<Float>,
    val progress: Animatable<Float, AnimationVector1D>,
)

/**
 * A list of values that animates to [target] whenever it changes.
 * Changing the range *morphs* the charts: each value goes from what's on
 * screen now to the new figure, rather than dropping to zero and regrowing.
 *
 * The new Animatable is made during composition, starting at 0, so the first
 * frame after a change is already the start of the move (as in the intro and
 * screen transitions). Read [Morph.get] inside draw blocks and nothing
 * recomposes while it moves.
 */
class Morph internal constructor(private val s: MorphState) {
    operator fun get(i: Int): Float {
        val a = s.from.getOrElse(i) { 0f }
        val b = s.to.getOrElse(i) { 0f }
        return a + (b - a) * s.progress.value
    }
    val size: Int get() = s.to.size
    val progress: Float get() = s.progress.value
}

@Composable
fun rememberMorph(target: List<Float>, durationMs: Int = 750): Morph {
    val reduced = LocalReducedMotion.current
    val holder = remember { arrayOfNulls<MorphState>(1) }
    val state = remember(target) {
        val prev = holder[0]
        val from = if (prev == null) target.map { 0f }
        else target.indices.map { i ->
            val a = prev.from.getOrElse(i) { 0f }; val b = prev.to.getOrElse(i) { 0f }
            a + (b - a) * prev.progress.value
        }
        MorphState(from, target, Animatable(if (reduced) 1f else 0f)).also { holder[0] = it }
    }
    LaunchedEffect(state) { state.progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) }
    return Morph(state)
}

// ---- Numbers ---------------------------------------------------------------------

/** A figure that counts up to its value, and from one value to the next. */
@Composable
fun CountUpText(value: Float, format: (Float) -> String, style: TextStyle, color: Color = Color.Unspecified) {
    val m = rememberMorph(listOf(value))
    // Text has to recompose to change, so this one reads in composition; it's a
    // few characters, for under a second.
    Text(format(m[0]), style = style, color = color, maxLines = 1)
}

// ---- Bars ------------------------------------------------------------------------

/** Rows of label, value and a bar that grows: top songs, the status funnel. */
@Composable
fun GrowBars(labels: List<String>, values: List<Float>, valueText: (Int) -> String, max: Float? = null) {
    val m = rememberMorph(values)
    val top = max ?: (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val amber = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(valueText(i), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(track, cornerRadius = r)
                    drawRoundRect(amber, size = Size(size.width * (m[i] / top).coerceIn(0f, 1f), size.height), cornerRadius = r)
                }
            }
        }
    }
}

/** Upright bars with a label under each: time of day, day of the week. */
@Composable
fun ColumnBars(labels: List<String>, values: List<Float>, description: String) {
    val m = rememberMorph(values)
    val top = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val amber = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val best = values.indices.maxByOrNull { values[it] }?.takeIf { values[it] > 0f }
    Column(Modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val n = labels.size.coerceAtLeast(1)
            val gap = 8.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            for (i in 0 until n) {
                val x = i * (w + gap)
                drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(6.dp.toPx()))
                val h = size.height * (m[i] / top).coerceIn(0f, 1f)
                if (h > 0f) {
                    drawRoundRect(
                        if (i == best) amber else amber.copy(alpha = 0.6f),
                        Offset(x, size.height - h), Size(w, h), CornerRadius(6.dp.toPx()),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---- Rings -----------------------------------------------------------------------

/** The colors a ring's slices take, in order. Labels always say which is which too. */
@Composable
fun ringColors(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    Color(0xFFE08A00),
    MaterialTheme.colorScheme.onSurface,
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.outline,
)

/** A ring that fills slice by slice, with a legend naming each slice and its share. */
@Composable
fun Ring(labels: List<String>, values: List<Float>, center: String) {
    val m = rememberMorph(values)
    val colors = ringColors()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val total = values.sum().coerceAtLeast(1f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(112.dp)) {
                val stroke = 14.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                var start = -90f
                // Slices grow from what was shown to the new shares; the ring fills
                // as they do, since the first showing starts from zero.
                for (i in 0 until m.size) {
                    val sweep = 360f * (m[i] / total)
                    if (sweep > 0.5f) {
                        drawArc(colors[i % colors.size], start, (sweep - 2f).coerceAtLeast(0.5f), false,
                            Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                    }
                    start += sweep
                }
            }
            Text(center, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Canvas(Modifier.size(10.dp)) { drawCircle(colors[i % colors.size]) }
                    Text(
                        "$label · ${(values[i] / total * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---- Calendar heat map -----------------------------------------------------------

private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d")

/**
 * The practice calendar: one square a day, Monday at the top, a week to a
 * column, brighter for more practice. Fades in left to right. Tap a day for
 * its minutes. Brightness steps are also in the legend, never color alone.
 */
@Composable
fun HeatMap(heat: Map<LocalDate, Long>, start: LocalDate, today: LocalDate, weeks: Int) {
    val reduced = LocalReducedMotion.current
    val reveal = remember(start, weeks) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reveal) { reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    var picked by remember(start) { mutableStateOf<LocalDate?>(null) }
    val amber = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val ring = MaterialTheme.colorScheme.onSurface
    fun level(seconds: Long): Int = when {
        seconds <= 0 -> 0
        seconds < 15 * 60 -> 1
        seconds < 30 * 60 -> 2
        seconds < 60 * 60 -> 3
        else -> 4
    }
    fun color(l: Int) = if (l == 0) empty else amber.copy(alpha = 0.25f + 0.1875f * l)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            picked?.let { "${DAY.format(it)}: ${minutes(heat[it] ?: 0)}" } ?: "Tap a day to see it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Big enough to tap on a tablet, and 52 weeks still fit across a phone.
            val cell = min(maxWidth.value / weeks, 28f).dp
            Canvas(
                Modifier
                    .size(width = cell * weeks, height = cell * 7)
                    .pointerInput(start, weeks) {
                        detectTapGestures { p ->
                            val c = (p.x / (size.width / weeks)).toInt()
                            val r = (p.y / (size.height / 7f)).toInt()
                            val day = start.plusDays(c * 7L + r)
                            picked = day.takeIf { !it.isAfter(today) }
                        }
                    }
                    .semantics { contentDescription = "Practice calendar. ${heat.count { it.value > 0 }} days practiced in the last $weeks weeks." },
            ) {
                val step = size.width / weeks
                val gap = step * 0.18f
                for (c in 0 until weeks) {
                    val shown = ((reveal.value * (weeks + 4) - c) / 4f).coerceIn(0f, 1f)
                    if (shown <= 0f) continue
                    for (r in 0 until 7) {
                        val day = start.plusDays(c * 7L + r)
                        if (day.isAfter(today)) continue
                        val base = color(level(heat[day] ?: 0))
                        val o = Offset(c * step, r * step)
                        drawRoundRect(base.copy(alpha = base.alpha * shown), o, Size(step - gap, step - gap), CornerRadius(step * 0.2f))
                        if (day == picked || day == today) {
                            drawRoundRect(ring.copy(alpha = if (day == picked) 0.9f else 0.35f), o, Size(step - gap, step - gap),
                                CornerRadius(step * 0.2f), style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("None", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (0..4).forEach { l -> Canvas(Modifier.size(10.dp)) { drawRoundRect(color(l), cornerRadius = CornerRadius(2.dp.toPx())) } }
            Text("an hour+", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Lines -----------------------------------------------------------------------

/** A line drawn left to right as it appears: ratings, a song's tempo. */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier.fillMaxWidth().height(48.dp), minValue: Float? = null, maxValue: Float? = null) {
    if (values.size < 2) return
    val reduced = LocalReducedMotion.current
    val draw = remember(values) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(draw) { draw.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    val amber = MaterialTheme.colorScheme.primary
    val lo = minValue ?: values.min()
    val hi = (maxValue ?: values.max()).let { if (it - lo < 1e-3f) lo + 1f else it }
    Canvas(modifier) {
        val pad = 4.dp.toPx()
        fun pt(i: Int) = Offset(
            pad + (size.width - 2 * pad) * i / (values.size - 1),
            pad + (size.height - 2 * pad) * (1f - (values[i] - lo) / (hi - lo)),
        )
        val upTo = draw.value * (values.size - 1)
        val path = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until values.size) {
                if (i <= upTo) lineTo(pt(i).x, pt(i).y)
                else {
                    val f = upTo - (i - 1)
                    if (f > 0f) { val a = pt(i - 1); val b = pt(i); lineTo(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f) }
                    break
                }
            }
        }
        drawPath(path, amber, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        for (i in values.indices) if (i <= upTo) drawCircle(amber, 3.dp.toPx(), pt(i))
    }
}

/** Parts mastered over time: a step up for each one. */
@Composable
fun StepLine(points: List<Pair<LocalDate, Int>>, today: LocalDate) {
    if (points.isEmpty()) return
    val reduced = LocalReducedMotion.current
    val draw = remember(points) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(draw) { draw.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val amber = MaterialTheme.colorScheme.primary
    val first = points.first().first.minusDays(1)
    val span = (today.toEpochDay() - first.toEpochDay()).coerceAtLeast(1).toFloat()
    val top = points.maxOf { it.second }.toFloat()
    Canvas(Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = "${points.last().second} parts mastered" }) {
        fun x(d: LocalDate) = size.width * (d.toEpochDay() - first.toEpochDay()) / span
        fun y(n: Int) = size.height - 4.dp.toPx() - (size.height - 8.dp.toPx()) * n / top
        val path = Path().apply {
            moveTo(0f, y(0))
            var last = 0
            points.forEach { (d, n) -> lineTo(x(d), y(last)); lineTo(x(d), y(n)); last = n }
            lineTo(size.width, y(last))
        }
        clipRectFraction(draw.value) { drawPath(path, amber, style = Stroke(2.dp.toPx())) }
        val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        clipRectFraction(draw.value) { drawPath(fill, Brush.verticalGradient(listOf(amber.copy(alpha = 0.25f), Color.Transparent))) }
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.clipRectFraction(
    f: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) = drawContext.canvas.let {
    it.save(); it.clipRect(0f, 0f, size.width * f, size.height); block(); it.restore()
}

// ---- Milestone badges ------------------------------------------------------------

/**
 * A milestone: lit and glowing once earned, dimmed with a progress ring until
 * then. A tap opens its details ([MilestoneTooltip], drawn by the caller).
 */
@Composable
fun Badge(title: String, earned: Boolean, progress: Float, detail: String, selected: Boolean = false, onClick: () -> Unit = {}) {
    val m = rememberMorph(listOf(if (earned) 1f else progress))
    val amber = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        Modifier
            .width(92.dp)
            .tactileClickable(onClickLabel = "Show details", pressedScale = 0.94f, onClick = onClick)
            .semantics { contentDescription = "$title. " + if (earned) "Earned. $detail" else "${(progress * 100).toInt()} percent. $detail" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(64.dp)) {
                if (earned) drawCircle(Brush.radialGradient(listOf(amber.copy(alpha = 0.35f), Color.Transparent)))
                // The chosen badge gets a ring of its own, so it's clear which one the tooltip is about.
                if (selected) drawCircle(amber.copy(alpha = 0.18f), radius = size.minDimension / 2)
                val stroke = 4.dp.toPx()
                val inset = stroke / 2 + 6.dp.toPx()
                val arc = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                drawArc(if (earned) amber else amber.copy(alpha = 0.6f), -90f, 360f * m[0], false, Offset(inset, inset), arc,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Icon(
                if (earned) Icons.Filled.MilitaryTech else Icons.Filled.Lock,
                contentDescription = null,
                tint = if (earned) amber else dim,
                modifier = Modifier.size(if (earned) 28.dp else 20.dp),
            )
        }
        Text(title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2,
            color = if (earned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 92.dp))
    }
}

/** "1 h 20 min", "25 min", "none". */
fun minutes(seconds: Long): String = if (seconds <= 0) "none" else com.riffbuddy.ui.duration(seconds)
