package com.riffbuddy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.Journey
import com.riffbuddy.domain.Milestone
import com.riffbuddy.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Something worth celebrating. */
sealed interface Celebration {
    data class SongMastered(val title: String, val journey: Journey) : Celebration
    /** [onShown] records it as celebrated, so it plays once. */
    data class MilestoneEarned(val milestone: Milestone, val onShown: () -> Unit) : Celebration
}

/**
 * The queue of celebrations, drawn by [CelebrationHost] above the whole app.
 * Any screen can add one; several at once play one after another.
 */
object Celebrations {
    val queue = mutableStateListOf<Celebration>()
    fun show(c: Celebration) { queue += c }
}

/** Above everything, like the intro (MainActivity). */
@Composable
fun CelebrationHost() {
    // Nothing plays under the intro; the queue waits for it to finish.
    if (IntroGate.showing) return
    val current = Celebrations.queue.firstOrNull() ?: return
    val done = { if (Celebrations.queue.isNotEmpty()) Celebrations.queue.removeAt(0) }
    when (current) {
        is Celebration.SongMastered -> key(current) { SongMasteredOverlay(current, onDone = done) }
        is Celebration.MilestoneEarned -> key(current) {
            MilestoneBanner(current.milestone, onDone = { current.onShown(); done() })
        }
    }
}

@Composable
private fun key(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

/**
 * A spray of amber sparks from the middle of whatever it covers: they fly out,
 * arc down a little under gravity and fade. Replayed whenever [trigger]
 * changes. The time is read only while drawing (like the intro), so a burst
 * redraws but never recomposes. Draws nothing with reduced motion.
 */
@Composable
fun SparkBurst(
    trigger: Any,
    modifier: Modifier = Modifier,
    count: Int = 36,
    durationMs: Int = 1300,
    reach: Float = 0.45f,
) {
    if (LocalReducedMotion.current) return
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, tween(durationMs, easing = LinearEasing)) }
    val sparks = remember(trigger, count) {
        val rnd = Random(trigger.hashCode())
        List(count) {
            Spark(
                angle = rnd.nextFloat() * 2f * Math.PI.toFloat(),
                speed = 0.35f + rnd.nextFloat() * 0.65f,
                delay = rnd.nextFloat() * 0.15f,
                width = 1.5f + rnd.nextFloat() * 2.5f,
                color = SPARK_COLORS[rnd.nextInt(SPARK_COLORS.size)],
            )
        }
    }
    Canvas(modifier) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val maxR = size.minDimension * reach
        sparks.forEach { s ->
            val p = ((t - s.delay) / (1f - s.delay)).coerceIn(0f, 1f)
            if (p <= 0f || p >= 1f) return@forEach
            val eased = FastOutSlowInEasing.transform(p)
            val dx = cos(s.angle); val dy = sin(s.angle)
            val r = maxR * s.speed * eased
            val fall = maxR * 0.25f * p * p
            val head = Offset(center.x + dx * r, center.y + dy * r + fall)
            val tail = Offset(head.x - dx * maxR * 0.06f * (1f - p), head.y - dy * maxR * 0.06f * (1f - p))
            drawLine(s.color.copy(alpha = 1f - p), tail, head, strokeWidth = s.width, cap = StrokeCap.Round)
        }
    }
}

private data class Spark(val angle: Float, val speed: Float, val delay: Float, val width: Float, val color: Color)

private val SPARK_COLORS = listOf(Color(0xFFFFC24A), Color(0xFFFFD27A), Color(0xFFF7B23C), Color(0xFFFFF1D6))

/**
 * A song mastered: the big moment. Two bursts, a trophy that springs
 * in, and the song's journey, what it took to get here. A tap closes it.
 */
@Composable
private fun SongMasteredOverlay(c: Celebration.SongMastered, onDone: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    val trophy = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(260))
        trophy.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    val amber = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = enter.value }
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .pointerInput(Unit) { detectTapGestures { onDone() } }
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = "${c.title} mastered. ${journeyLine(c.journey)}. Tap to continue."
            },
        contentAlignment = Alignment.Center,
    ) {
        SparkBurst(c, Modifier.fillMaxSize(), count = 70, durationMs = 1600, reach = 0.5f)
        SparkBurst(c.title + "2", Modifier.fillMaxSize(), count = 40, durationMs = 1900, reach = 0.35f)
        Column(
            Modifier.widthIn(max = 420.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(180.dp)) {
                    drawCircle(Brush.radialGradient(listOf(amber.copy(alpha = 0.35f * trophy.value), Color.Transparent)))
                }
                Icon(
                    Icons.Filled.EmojiEvents, contentDescription = null, tint = amber,
                    modifier = Modifier.size(110.dp).graphicsLayer { scaleX = trophy.value; scaleY = trophy.value },
                )
            }
            Text("Mastered!", style = MaterialTheme.typography.displaySmall, color = amber)
            // Colors set explicitly: this overlay isn't inside a Material surface,
            // so an unset color falls back to black on the dark scrim.
            Text(c.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Text(
                journeyLine(c.journey),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text("Tap to continue", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "21 days · 6 h 40 min over 14 sessions · 70 → 96 BPM", leaving out what isn't known. */
fun journeyLine(j: Journey): String = listOfNotNull(
    when (j.days) { 0L -> "Added today"; 1L -> "1 day"; else -> "${j.days} days" },
    if (j.sessions > 0) "${duration(j.seconds)} over ${j.sessions} ${if (j.sessions == 1) "session" else "sessions"}" else null,
    if (j.firstBpm != null && j.bestBpm != null && j.bestBpm > j.firstBpm) "${j.firstBpm} → ${j.bestBpm} BPM" else null,
).joinToString(" · ")

/** 6 h 40 min, 25 min, or under a minute. */
fun duration(seconds: Long): String {
    val m = seconds / 60
    return when {
        m < 1 -> "under a minute"
        m < 60 -> "$m min"
        m % 60 == 0L -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}

/** A milestone earned: a banner across the top with a small burst, gone after a few seconds. */
@Composable
private fun MilestoneBanner(m: Milestone, onDone: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val slide = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        slide.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        delay(4200)
        slide.animateTo(0f, tween(260))
        onDone()
    }
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .padding(12.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = slide.value
                    translationY = (1f - slide.value) * -40.dp.toPx()
                }
                .pointerInput(Unit) { detectTapGestures { onDone() } }
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Panel(Modifier.fillMaxWidth()) {
                Box {
                    // matchParentSize: the sparks take the banner's size; fillMaxSize
                    // here would stretch the banner to the whole screen.
                    SparkBurst(m.key, Modifier.matchParentSize(), count = 24, durationMs = 1200, reach = 0.6f)
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Filled.MilitaryTech, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                        Column {
                            Text("MILESTONE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(m.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

