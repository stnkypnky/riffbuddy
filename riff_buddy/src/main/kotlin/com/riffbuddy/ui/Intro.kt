package com.riffbuddy.ui

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffbuddy.R
import com.riffbuddy.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** The intro's length. The player asked for three to five seconds. */
private const val TOTAL_MS = 3800

/**
 * The opening animation, drawn over the app while Home loads
 * underneath it.
 *
 * It starts where the system splash ends: the same icon, the same size, on
 * the same charcoal, so there is no jump between the two. Then:
 * - an amber outline traces the guitar while a glow warms up behind it (0–1 s);
 * - six strings draw down the neck and are plucked low to high (1–1.9 s);
 * - the name rises in underneath (1.6–2.4 s);
 * - a short hold, and it fades and eases forward into Home (3.3–3.8 s).
 *
 * **Cheap on purpose.** One clock drives everything, and it's read only inside
 * draw blocks and layer blocks. So each frame redraws; nothing recomposes,
 * measures or lays out. A few path segments a frame is nothing, even on the
 * Fire.
 *
 * A tap skips it. It never plays with "Remove animations" (reduced motion) or
 * with TalkBack on: a silent animation is only a delay to someone who can't see
 * it.
 */
@Composable
fun Intro() {
    var showing by remember { mutableStateOf(!IntroGate.played) }
    SideEffect { IntroGate.showing = showing }
    if (!showing) return

    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val talkBack = remember {
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
    }
    SideEffect { IntroGate.played = true }
    if (reduced || talkBack) {
        SideEffect { showing = false }
        return
    }

    val clock = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }            // 0 → 1 while leaving
    val scope = rememberCoroutineScope()
    var skipping by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        clock.animateTo(TOTAL_MS / 1000f, tween(TOTAL_MS, easing = LinearEasing))
        showing = false
    }
    // The last half second of the clock is the exit; a tap jumps straight to a quick one.
    fun t() = clock.value
    fun leaving() = if (skipping) exit.value else phase(t(), 3.3f, 3.8f, FastOutSlowInEasing::transform)

    val bg = MaterialTheme.colorScheme.background
    val amber = MaterialTheme.colorScheme.primary
    val stringColor = MaterialTheme.colorScheme.onSurface
    val icon = painterResource(R.drawable.ic_launcher_foreground)
    // The size the system splash drew the icon at, so the handover doesn't jump:
    // Android 12+ uses 240 dp for the animated icon; our own launch window, 220.
    val iconDp = if (Build.VERSION.SDK_INT >= 31) 240.dp else 220.dp
    val contours = remember { outlineContours() }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val l = leaving()
                alpha = 1f - l
                scaleX = 1f + 0.06f * l
                scaleY = 1f + 0.06f * l
            }
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures {
                    if (!skipping) {
                        skipping = true
                        scope.launch {
                            exit.animateTo(1f, tween(220))
                            showing = false
                        }
                    }
                }
            }
            .semantics { contentDescription = "Riff Buddy. Tap to skip." },
        contentAlignment = Alignment.Center,
    ) {
        // The icon, its outline and strings, and the glow behind: all drawn here.
        Canvas(Modifier.fillMaxSize()) {
            val side = iconDp.toPx()
            val unit = side / 108f
            val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
            val time = t()

            // Warm glow behind the guitar, and a slow breath while it holds.
            val warm = phase(time, 0f, 1.0f, FastOutSlowInEasing::transform)
            val breath = if (time > 2.4f) 0.12f * sin((time - 2.4f) * 2f * PI.toFloat() / 1.6f) else 0f
            drawCircle(
                Brush.radialGradient(
                    listOf(amber.copy(alpha = (0.30f * warm + breath * warm).coerceIn(0f, 0.45f)), Color.Transparent),
                    center = center, radius = side * 0.85f,
                ),
                radius = side * 0.85f, center = center,
            )

            translate(topLeft.x, topLeft.y) {
                with(icon) { draw(Size(side, side)) }

                scale(unit, unit, pivot = Offset.Zero) {
                    rotate(-35f, pivot = Offset(54f, 54.25f)) {
                        // The outline traces itself, every contour at once.
                        val trace = phase(time, 0f, 1.0f, FastOutSlowInEasing::transform)
                        val fade = 1f - phase(time, 1.2f, 2.0f)
                        if (trace > 0f && fade > 0f) {
                            val measure = PathMeasure()
                            contours.forEach { contour ->
                                measure.setPath(contour, false)
                                val part = Path()
                                measure.getSegment(0f, measure.length * trace, part, true)
                                drawPath(
                                    part, Color(0xFFFFD27A).copy(alpha = 0.9f * fade),
                                    style = Stroke(width = 1.1f, cap = StrokeCap.Round),
                                )
                            }
                        }

                        // Six strings: drawn down the neck, then plucked low to high.
                        val grow = phase(time, 1.0f, 1.4f, FastOutSlowInEasing::transform)
                        if (grow > 0f) {
                            val top = 27f
                            val bottom = 80f
                            val length = bottom - top
                            for (i in 0 until 6) {
                                val x = 52.2f + i * 0.72f
                                val pluckAt = 1.3f + i * 0.09f
                                val since = time - pluckAt
                                // A decaying wobble, widest at the middle of the string.
                                val amp = if (since > 0f) 0.9f * exp(-3.2f * since) * sin(2f * PI.toFloat() * 7f * since) else 0f
                                val end = top + length * grow
                                val path = Path().apply {
                                    moveTo(x, top)
                                    val steps = 16
                                    for (s in 1..steps) {
                                        val y = top + (end - top) * s / steps
                                        val shape = sin(PI.toFloat() * (y - top) / length)
                                        lineTo(x + amp * shape, y)
                                    }
                                }
                                drawPath(
                                    path, stringColor.copy(alpha = 0.85f),
                                    style = Stroke(width = if (i < 3) 0.42f else 0.28f, cap = StrokeCap.Round),
                                )
                            }
                        }
                    }
                }
            }
        }

        // The name, below the guitar.
        Column(
            Modifier.graphicsLayer {
                val show = phase(t(), 1.6f, 2.4f, FastOutSlowInEasing::transform)
                alpha = show
                translationY = iconDp.toPx() * 0.62f + (1f - show) * 16.dp.toPx()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Riff Buddy", style = MaterialTheme.typography.displaySmall, color = amber)
            Text(
                "TONE & TIME",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where [t] is between [start] and [end], 0 to 1, eased. */
private fun phase(t: Float, start: Float, end: Float, ease: (Float) -> Float = { it }): Float =
    ease(((t - start) / (end - start)).coerceIn(0f, 1f))

/**
 * What the outline traces: the guitar's silhouette (neck and both bouts
 * merged, so no construction lines show where the shapes overlap inside the
 * body) and the pickup. One closed path per contour, because a path measure
 * walks a single contour at a time.
 */
private fun outlineContours(): List<Path> {
    fun parse(d: String) = PathParser().parsePathString(d).toPath().asAndroidPath()
    val silhouette = parse(GUITAR_CONTOURS[0]).apply {
        op(parse(GUITAR_CONTOURS[1]), android.graphics.Path.Op.UNION)
        op(parse(GUITAR_CONTOURS[2]), android.graphics.Path.Op.UNION)
    }
    val contours = mutableListOf<Path>()
    val measure = android.graphics.PathMeasure(silhouette, false)
    do {
        val one = android.graphics.Path()
        measure.getSegment(0f, measure.length, one, true)
        one.close()
        contours += one.asComposePath()
    } while (measure.nextContour())
    contours += parse(GUITAR_CONTOURS[3]).asComposePath()
    return contours
}

/**
 * The icon's shapes, one closed contour each, from ic_launcher_foreground.xml
 * (the same 108-unit grid; the group's −35° turn is applied when drawing).
 * Split into contours because a path measure walks one contour at a time.
 */
private val GUITAR_CONTOURS = listOf(
    "M47.5,25 h13 v7 h-3.5 v26 h-6 v-26 h-3.5 Z",
    "M41.5,63 a12.5,9 0 1,0 25,0 a12.5,9 0 1,0 -25,0 Z",
    "M39,74 a15,9.5 0 1,0 30,0 a15,9.5 0 1,0 -30,0 Z",
    "M47,58 h14 a1.5,1.5 0 0 1 1.5,1.5 v2 a1.5,1.5 0 0 1 -1.5,1.5 h-14 a1.5,1.5 0 0 1 -1.5,-1.5 v-2 a1.5,1.5 0 0 1 1.5,-1.5 Z",
)
