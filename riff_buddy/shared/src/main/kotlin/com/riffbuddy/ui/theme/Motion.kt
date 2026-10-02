package com.riffbuddy.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.compositionLocalOf

/**
 * Reduced motion.
 *
 * Two sources, and the system one matters more. The operating system has an
 * accessibility setting for this — Android's "Remove animations", for one —
 * and an app that ignores it in favor of its own
 * private toggle is making the person say the same thing twice. So the system
 * setting is honored automatically, and the in-app switch can only ever turn
 * motion *down*, never back on.
 *
 * What this actually protects: the metronome lamp pulsing at 200 BPM, the
 * tuner's needle chasing a string, knobs sweeping to their positions. For
 * someone with vestibular sensitivity those are not decoration — a beat lamp
 * flashing three times a second for twenty minutes is a migraine.
 */
val LocalReducedMotion = compositionLocalOf { false }

/**
 * An animation that becomes an instant change when motion is reduced.
 *
 * Deliberately still a *change* rather than nothing: the beat lamp must still
 * light on the beat, the needle must still move. Reduced motion means no
 * tweening between states, not no information.
 */
@Composable
fun <T> motionSpec(durationMillis: Int = 120): AnimationSpec<T> =
    if (LocalReducedMotion.current) snap() else tween(durationMillis)

/**
 * A control that gives a little under the finger: it shrinks while
 * pressed and springs back on release, like a real footswitch or button.
 *
 * Only `graphicsLayer` scale, and the animated value is read *inside* the
 * layer block, so a press redraws one layer on the GPU and recomposes
 * nothing. That is what makes it cheap enough to put on every button, even
 * on a slow tablet. The layout doesn't change either, so neighboring controls
 * don't move. With reduced motion it does nothing: the ripple still shows
 * the press.
 */
@Composable
fun Modifier.pressScale(source: InteractionSource, pressedScale: Float = 0.95f): Modifier {
    val reduced = LocalReducedMotion.current
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * `clickable` for a whole row or card, with the same give as a button.
 * Gentler (0.98) than a button's 0.95: a full-width card shrinking by five
 * percent would feel like the screen moving, not the card being pressed.
 */
@Composable
fun Modifier.tactileClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    pressedScale: Float = 0.98f,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .pressScale(source, pressedScale)
        .clickable(interactionSource = source, indication = ripple(), enabled = enabled, onClickLabel = onClickLabel, onClick = onClick)
}

/** How a screen arrives. */
enum class Entrance {
    /** Opening a page from a list: in from the right. */
    FORWARD,
    /** Back from a page: in from the left. */
    BACK,
    /** Another tab: fades in with a small rise. */
    SWITCH,
    /** Another tool: a quick fade and nothing else, so the tool chips at the top don't move. */
    SOFT,
}

/**
 * Plays [entrance] each time [key] changes.
 *
 * **Enter only.** The old screen is gone the moment the new one appears; only
 * the new one moves. A cross-fade would draw both screens for a quarter of a
 * second, and on a slow tablet the tab player or the fretboard twice over is
 * exactly the frame that stutters.
 *
 * A new Animatable is made *in composition* for each key, starting at 0, so
 * the new screen's first frame is already at the start of its entrance. If
 * it were started from an effect, that first frame would flash in fully
 * visible before the animation began.
 */
@Composable
fun Modifier.screenEntrance(key: Any, entrance: Entrance): Modifier {
    val reduced = LocalReducedMotion.current
    val progress = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(progress) {
        progress.animateTo(1f, tween(if (entrance == Entrance.SOFT) 150 else 220, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        val p = progress.value
        val left = 1f - p
        when (entrance) {
            Entrance.FORWARD -> { alpha = 0.3f + 0.7f * p; translationX = left * 24.dp.toPx() }
            Entrance.BACK -> { alpha = 0.3f + 0.7f * p; translationX = -left * 24.dp.toPx() }
            Entrance.SWITCH -> { alpha = p; translationY = left * 8.dp.toPx() }
            Entrance.SOFT -> { alpha = 0.4f + 0.6f * p }
        }
    }
}
