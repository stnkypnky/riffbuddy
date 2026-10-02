package com.riffbuddy.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.riffbuddy.domain.Milestone
import com.riffbuddy.domain.StatsSnapshot
import com.riffbuddy.ui.theme.LocalReducedMotion
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A milestone's details, popped up over its badge: what it's for,
 * where you stand ("Now 96 BPM · Goal 100 BPM"), and the figures behind it.
 *
 * Drawn in a [Popup] placed from the badge's own bounds: above it when
 * there's room, below when it's near the top, and kept inside the screen
 * sideways, so it works at phone width as well as on the tablet. A tap
 * anywhere closes it.
 */
@Composable
fun MilestoneTooltip(m: Milestone, all: StatsSnapshot, earnedOn: LocalDate?, onDismiss: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val grow = remember(m.key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(m.key) { grow.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val positioner = remember(density) { with(density) { AboveOrBelow(gapPx = 8.dp.roundToPx(), marginPx = 12.dp.roundToPx()) } }
    val earned = m.earned(all)
    val amber = MaterialTheme.colorScheme.primary

    Popup(popupPositionProvider = positioner, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .graphicsLayer {
                    alpha = grow.value.coerceIn(0f, 1f)
                    scaleX = 0.85f + 0.15f * grow.value
                    scaleY = 0.85f + 0.15f * grow.value
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp))
                .border(1.dp, if (earned) amber.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (earned) Icons.Filled.MilitaryTech else Icons.Filled.Lock, contentDescription = null,
                    tint = if (earned) amber else MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp),
                )
                Column {
                    Text(m.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        m.group.label.uppercase() + if (earned) (earnedOn?.let { " · EARNED ${DAY.format(it).uppercase()}" } ?: " · EARNED") else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (earned) amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Text(m.standing(all), style = MaterialTheme.typography.labelMedium, color = amber)
            if (!earned) ProgressBar(m.progress(all))
            m.detail(all)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    val amber = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainer
    val m = rememberMorph(listOf(progress))
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(amber, size = Size(size.width * m[0], size.height), cornerRadius = r)
    }
}

/** Centered on the anchor, above it if it fits and below if not, never off the sides. */
private class AboveOrBelow(private val gapPx: Int, private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(marginPx, (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx))
        val above = anchorBounds.top - popupContentSize.height - gapPx
        val y = if (above >= 0) above else anchorBounds.bottom + gapPx
        return IntOffset(x, y)
    }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
