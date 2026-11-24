package com.riffbuddy.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffbuddy.ui.LocalChooseFinish
import com.riffbuddy.ui.Panel
import com.riffbuddy.ui.SectionHeader
import com.riffbuddy.ui.theme.Finish
import com.riffbuddy.ui.theme.Finishes
import com.riffbuddy.ui.theme.LocalFinish
import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.theme.tactileClickable

/**
 * The finish picker: every color scheme drawn as a little guitar body in its
 * own finish, dark ones first, then light. Picking one changes the whole app at
 * once and is remembered.
 */
@Composable
fun AppearanceSection() {
    val current = LocalFinish.current
    val choose = LocalChooseFinish.current
    Panel(contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            SectionHeader("Finish")
            Text(
                "The app's colors, after a guitar finish or a tonewood. Now: ${current.label}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FinishGroup("Dark", Finishes.ALL.filter { it.dark }, current, choose)
            FinishGroup("Light", Finishes.ALL.filter { !it.dark }, current, choose)
        }
    }
}

@Composable
private fun FinishGroup(title: String, finishes: List<Finish>, current: Finish, choose: (Finish) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            finishes.forEach { f -> FinishTile(f, f.id == current.id) { choose(f) } }
        }
    }
}

@Composable
private fun FinishTile(finish: Finish, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    // A fixed size, so every tile in the grid lines up whatever its name;
    // wide enough for "Butterscotch" on one line, and three still fit a
    // phone's row.
    Box(
        Modifier
            .size(width = 96.dp, height = 112.dp)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) scheme.primary else scheme.outlineVariant,
                shape,
            )
            .tactileClickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = "${finish.label} finish, ${if (finish.dark) "dark" else "light"}"
            },
    ) {
        Column(
            Modifier.fillMaxSize().padding(vertical = Space.sm, horizontal = Space.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            GuitarSwatch(finish, Modifier.size(width = 44.dp, height = 56.dp))
            Text(
                finish.label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The check sits on a disc of the app's own accent, in the corner, not
        // on the guitar: painted on a pale body it all but disappeared.
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(Space.xs)
                    .size(20.dp)
                    .background(scheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * A guitar body seen from the front, filled with the finish: the body color in
 * the middle burning out to the edge color, the way a sunburst is sprayed.
 * A generic double-bout outline, not any maker's shape.
 */
@Composable
private fun GuitarSwatch(finish: Finish, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.50f, h * 0.10f)
            // Upper bout, right side.
            cubicTo(w * 0.78f, h * 0.08f, w * 0.90f, h * 0.22f, w * 0.80f, h * 0.38f)
            // Waist into the lower bout.
            cubicTo(w * 0.74f, h * 0.46f, w * 1.02f, h * 0.56f, w * 0.96f, h * 0.76f)
            cubicTo(w * 0.90f, h * 0.96f, w * 0.10f, h * 0.96f, w * 0.04f, h * 0.76f)
            cubicTo(w * -0.02f, h * 0.56f, w * 0.26f, h * 0.46f, w * 0.20f, h * 0.38f)
            cubicTo(w * 0.10f, h * 0.22f, w * 0.22f, h * 0.08f, w * 0.50f, h * 0.10f)
            close()
        }
        drawPath(
            body,
            Brush.radialGradient(
                0.0f to Color(finish.swatch),
                0.55f to Color(finish.swatch),
                1.0f to Color(finish.swatchEdge),
                center = Offset(w * 0.5f, h * 0.62f),
                radius = w * 0.62f,
            ),
        )
        drawPath(body, outline.copy(alpha = 0.6f), style = Stroke(width = 1.dp.toPx()))
        // The neck stub and the sound hole's place, so it reads as a guitar at a glance.
        drawRect(Color(finish.swatchEdge), topLeft = Offset(w * 0.45f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.10f, h * 0.14f))
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = w * 0.08f, center = Offset(w * 0.5f, h * 0.52f))
    }
}
