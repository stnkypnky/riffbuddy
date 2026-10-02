package com.riffbuddy.ui

import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.horizontalScroll

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffbuddy.ui.tactile.FilterChip
import com.riffbuddy.ui.help.HelpButton
import com.riffbuddy.ui.help.helpTopicFor
import com.riffbuddy.ui.tuner.Tool

/*
 * The pieces every screen is built from, so the look lives in one
 * place instead of in fifty-seven copies of the same Card call.
 */

/**
 * A panel: the card every screen groups its controls in.
 *
 * Same fill as before. The addition is a hairline that is lighter along the
 * top and fades out down the sides, the way light catches the top edge of an
 * amp's control panel. A 1 dp gradient border is a single draw call, so it
 * costs nothing even on a slow tablet.
 */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val edge = MaterialTheme.colorScheme.outline
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, Brush.verticalGradient(listOf(edge.copy(alpha = 0.55f), Color.Transparent))),
        content = content,
    )
}

/**
 * A section's title, silkscreened in amber like GAIN or MASTER on a panel,
 * with a short bar in front of it. Marked as a heading, so TalkBack can jump
 * between sections.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Row(modifier.semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 12.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * The seven tools, each chip with its icon. Used by the Tools tab and the tuner,
 * which draws its own. Wraps onto a second line on a phone rather than
 * scrolling, so every tool can be seen.
 */
@Composable
fun ToolChips(current: Tool, onPick: (Tool) -> Unit) {
    // One centered row that never wraps. Wrapping made the chips jump as the
    // tools changed, because each tool's screen is a different width; centered,
    // they sit in the same place on every tool. Where the row is wider than the
    // screen (a phone held upright) it scrolls sideways instead. The "?" sits at
    // the end, outside the row, and follows the tool that's showing.
    Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            Modifier.align(Alignment.Center)
                .padding(start = 8.dp, end = 52.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.width(8.dp))
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
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { HelpButton(helpTopicFor(current)) }
    }
}
