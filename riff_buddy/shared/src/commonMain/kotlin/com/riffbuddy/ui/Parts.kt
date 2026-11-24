package com.riffbuddy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffbuddy.ui.help.HelpButton
import com.riffbuddy.ui.help.helpTopicFor
import com.riffbuddy.ui.tactile.FilterChip
import com.riffbuddy.ui.theme.Elevation
import com.riffbuddy.ui.theme.RiffColors
import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.tuner.Tool

/*
 * The pieces every screen is built from, so the look lives in one
 * place instead of in fifty-seven copies of the same Card call.
 */

/**
 * A panel: the card every screen groups its controls in.
 *
 * A raised fill off the background, a hairline that is lighter along the top
 * and fades down the sides (the way light catches the top edge of an amp's
 * control panel), and a soft shadow. On a dark finish the shadow hardly shows
 * and the fill and edge do the work; on a light one the shadow is what lifts
 * the card. Each is a single draw, so it costs nothing on a slow tablet.
 *
 * [accent] draws a short colored edge down the left side, for the one panel on
 * a screen that matters most. [contentPadding] pads the content; most callers
 * pad their own and leave it null.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    contentPadding: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    val edge = scheme.outline
    val light = RiffColors.isLight
    val accentColor = scheme.primary
    Card(
        modifier
            .shadow(if (light) Elevation.card else Elevation.low, shape, clip = false,
                ambientColor = Color.Black.copy(alpha = if (light) 0.10f else 0.4f),
                spotColor = Color.Black.copy(alpha = if (light) 0.14f else 0.5f))
            .then(
                // Drawn over the card's left edge and clipped to its corners, so
                // the bar follows the rounding instead of poking out past it.
                if (accent) Modifier.clip(shape).drawWithContent {
                    drawContent()
                    drawRect(accentColor, topLeft = Offset.Zero, size = size.copy(width = 4.dp.toPx()))
                } else Modifier
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        border = BorderStroke(
            1.dp,
            Brush.verticalGradient(listOf(edge.copy(alpha = if (light) 0.35f else 0.55f), edge.copy(alpha = if (light) 0.12f else 0f))),
        ),
    ) {
        if (contentPadding != null) Column(Modifier.padding(contentPadding), content = content)
        else content()
    }
}

/**
 * A section's title, silkscreened in the accent like GAIN or MASTER on a panel,
 * with a short bar in front of it. Marked as a heading, so a screen reader can
 * jump between sections. [action] sits at the far end, for a "See all" or an
 * add button that belongs to the section.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        action?.invoke(this)
    }
}

/**
 * The title at the top of a screen: one style everywhere, so every page starts
 * the same way. [eyebrow] is a small line above the title (where you are, or
 * the date); [subtitle] one line under it; [actions] sit at the end, level with
 * the title.
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            if (eyebrow != null) {
                Text(
                    eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(Space.xxs))
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actions != null) Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * The one big card at the top of a screen: today's session on Home, the coach's
 * welcome, a tool's main readout. A wash of the finish's own color glows from
 * the top corner, so the hero reads as the brightest thing on the screen in any
 * finish. The glow is a single gradient fill and never animates.
 */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(Space.xl),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val glow = scheme.primary.copy(alpha = if (RiffColors.isLight) 0.16f else 0.22f)
    val shape = MaterialTheme.shapes.extraLarge
    val light = RiffColors.isLight
    Card(
        modifier.shadow(if (light) Elevation.raised else Elevation.card, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = if (light) 0.12f else 0.5f),
            spotColor = Color.Black.copy(alpha = if (light) 0.16f else 0.6f)),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, Brush.verticalGradient(listOf(scheme.primary.copy(alpha = 0.45f), scheme.outline.copy(alpha = 0.08f)))),
    ) {
        Box(
            Modifier.drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(glow, Color.Transparent),
                        center = Offset(size.width * 0.15f, 0f),
                        radius = maxOf(size.width, size.height) * 0.9f,
                    )
                )
            }
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/**
 * What an empty list shows instead of nothing: an icon in a soft disc, a line
 * saying what goes here, and the one action that fills it.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Space.xl, horizontal = Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(
            Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.size(Space.xs))
            action()
        }
    }
}

/** The seven tools, each chip with its icon: drawn once by the shell, above whichever tool is showing. */
@Composable
fun ToolChips(current: Tool, onPick: (Tool) -> Unit) {
    // Centered, and wrapping onto a second line only where the screen is too
    // narrow for one (a phone held upright). The shell gives this row a width
    // set by the window alone — the reading width, or the wide width on a
    // desktop-sized window, where all seven fit on one line — whatever tool is
    // below, so the chips stay put as the tools change. Its edge
    // padding is the screens' own, so the chips line up with the content under
    // them. The "?" sits at the end and follows the tool that's showing.
    val edge = screenPadding()
    Row(
        Modifier.fillMaxWidth().padding(start = edge, end = edge - Space.sm, top = Space.md),
        verticalAlignment = Alignment.Top,
    ) {
        FlowRow(
            Modifier.weight(1f).padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Tool.entries.forEach { tool ->
                FilterChip(
                    selected = tool == current,
                    onClick = { onPick(tool) },
                    label = { Text(tool.label, maxLines = 1) },
                    leadingIcon = { Icon(tool.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }
        HelpButton(helpTopicFor(current))
    }
}
