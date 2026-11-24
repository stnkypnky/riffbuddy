package com.riffbuddy.ui.tone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffbuddy.ai.ControlSetting
import com.riffbuddy.domain.gear.Reach
import com.riffbuddy.domain.toFixed
import com.riffbuddy.ui.theme.RiffColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The amp faceplate, drawn from the rig's own control list.
 *
 * A number beside every knob, always — a pointer alone is a picture of a
 * setting, and it has to be reproduced on a real amp at arm's length. The
 * picture is for recognizing the shape of a tone at a glance; the number is what
 * actually gets dialed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Faceplate(controls: List<ControlSetting>, modifier: Modifier = Modifier) {
    if (controls.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        controls.forEach { Knob(it) }
    }
}

@Composable
private fun Knob(control: ControlSetting) {
    // From the control's own ends, not by splitting "min-max" on the dash,
    // which read a -5 to 5 range as "5 to 5".
    val min = control.min
    val max = control.max
    val fraction = KnobGeometry.fraction(control.value, min, max)

    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .width(76.dp)
            .semantics {
                // Reads like "Gain, 6 out of 10".
                contentDescription = "${control.name}, ${format(control.value)} out of ${format(max)}" +
                    (if (control.reach == Reach.DISPLAY) ", in the amp display" else "") +
                    (control.clampedFrom?.let { ", the coach said ${format(it)}, which is off the dial" } ?: "")
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KnobFace(KnobGeometry.angleDegrees(control.value, min, max))

        Text(
            format(control.value),
            style = MaterialTheme.typography.titleMedium,
            color = onSurface,
        )
        Text(
            control.name.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Values outside a control's range are clamped and flagged.
        // Words, not just a color, so the flag survives any eyesight.
        control.clampedFrom?.let {
            Text(
                "said ${format(it)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        if (control.reach == Reach.DISPLAY) {
            // Otherwise the panel gets hunted for a knob that is not there.
            Text(
                "in display",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One knob, drawn: a face, a rim, and an amber pointer at [angleDegrees]
 * (clockwise from three o'clock, as [KnobGeometry] measures). Shared by the
 * tone card and the gear screens, so a knob looks the same wherever it
 * appears. No semantics of its own — the caller says what it means.
 */
@Composable
fun KnobFace(angleDegrees: Double, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val pointer = RiffColors.knobPointer
    val face = RiffColors.faceplate
    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)

        drawCircle(face, radius = radius)
        drawCircle(outline, radius = radius, style = Stroke(width = 1.5f))

        val angle = angleDegrees * PI / 180
        val inner = radius * 0.30f
        val outer = radius * 0.80f
        drawLine(
            color = pointer,
            start = center + Offset((cos(angle) * inner).toFloat(), (sin(angle) * inner).toFloat()),
            end = center + Offset((cos(angle) * outer).toFloat(), (sin(angle) * outer).toFloat()),
            // 4 px at the tone card's 52 dp, as it always was; smaller knobs thinner.
            strokeWidth = (4f * size.value / 52f).coerceAtLeast(2f),
        )
    }
}

private fun format(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
    else value.toFixed(1)
