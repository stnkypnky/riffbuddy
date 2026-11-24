package com.riffbuddy.ui.gear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffbuddy.data.Rig
import com.riffbuddy.domain.gear.AmpControl
import com.riffbuddy.domain.gear.AmpProfile
import com.riffbuddy.domain.gear.ControlKind
import com.riffbuddy.domain.gear.GuitarProfile
import com.riffbuddy.domain.gear.PedalProfile
import com.riffbuddy.ui.theme.RiffColors
import com.riffbuddy.ui.theme.tactileClickable
import com.riffbuddy.ui.tone.KnobFace
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The gear, drawn. Every picture here is made from the stored
 * profile — the amp's own control list, the pedal's own knob names, the
 * guitar's own pickup positions — never from stock art, so the picture can't
 * disagree with what the tone card will use. All Canvas and plain shapes:
 * a few circles and lines per item, which costs even a slow tablet nothing.
 */

/** Twelve o'clock, in [KnobFace]'s clockwise-from-three-o'clock degrees. */
private const val NOON = 270.0

// ---- signal chain ---------------------------------------------------------------

enum class ChainKind(val label: String) { GUITAR("Guitar"), PEDAL("Pedal"), AMP("Amp"), MORE("Pedals") }

/** One box in a rig's signal chain. [id] is the gear's id; null for a gap or "+N". */
data class ChainNode(val kind: ChainKind, val name: String, val id: Long?, val missing: Boolean = false)

/**
 * A rig as the signal runs through it: guitar, pedals in order, amp.
 *
 * Pure, so the rules are tested on the JVM. A missing guitar or amp is still
 * a node — a dashed "No amp" says why the rig can't make tone cards, where a
 * gap would just look short. Past [maxPedals] the rest fold into one "+N"
 * node, so the phone's chain stays on one line.
 */
fun chainNodes(rig: Rig, maxPedals: Int = Int.MAX_VALUE): List<ChainNode> {
    val guitar = rig.guitar?.let { ChainNode(ChainKind.GUITAR, it.name, it.id) }
        ?: ChainNode(ChainKind.GUITAR, "No guitar", null, missing = true)
    val amp = rig.amp?.let { ChainNode(ChainKind.AMP, it.name, it.id) }
        ?: ChainNode(ChainKind.AMP, "No amp", null, missing = true)
    val shown = rig.pedals.take(maxPedals.coerceAtLeast(0)).map { ChainNode(ChainKind.PEDAL, it.name, it.id) }
    val hidden = rig.pedals.size - shown.size
    val more = if (hidden > 0) listOf(ChainNode(ChainKind.MORE, "+$hidden", null)) else emptyList()
    return listOf(guitar) + shown + more + amp
}

/**
 * [chainNodes], drawn: boxes joined by a patch cable. [wrap] lets a long
 * chain run onto a second line (the rig's own page); otherwise it's one row
 * and the caller has already folded pedals to fit.
 */
@Composable
fun SignalChain(
    nodes: List<ChainNode>,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
    onTap: ((ChainNode) -> Unit)? = null,
) {
    val summary = nodes.joinToString(" to ") { it.name }
    val content: @Composable () -> Unit = {
        nodes.forEachIndexed { i, node ->
            if (i > 0) Cable()
            ChainBox(node, if (onTap != null && node.id != null) ({ onTap(node) }) else null)
        }
    }
    if (wrap) {
        FlowRow(
            modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { content() }
    } else {
        Row(
            // One description for the whole chain; the boxes are read as a
            // sentence, not as eight separate things.
            modifier.fillMaxWidth().then(if (onTap == null) Modifier.clearAndSetSemantics { contentDescription = "Signal chain: $summary" } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            nodes.forEachIndexed { i, node ->
                if (i > 0) Cable()
                ChainBox(
                    node,
                    if (onTap != null && node.id != null) ({ onTap(node) }) else null,
                    // Guitar and amp names get the room; pedals and "+N" stay compact.
                    Modifier.weight(if (node.kind == ChainKind.GUITAR || node.kind == ChainKind.AMP) 1.4f else 1f, fill = true),
                )
            }
        }
    }
}

@Composable
private fun ChainBox(node: ChainNode, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val outline = MaterialTheme.colorScheme.outline
    val base = modifier
        .heightIn(min = 56.dp)
        .clip(shape)
        .then(
            if (node.missing) Modifier.dashedBorder(outline)
            else Modifier.background(RiffColors.faceplate).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        )
        .then(if (onClick != null) Modifier.tactileClickable(onClickLabel = "Open ${node.name}", onClick = onClick) else Modifier)
        .padding(horizontal = 10.dp, vertical = 8.dp)
    Column(base.widthIn(min = 56.dp), verticalArrangement = Arrangement.Center) {
        Text(
            node.kind.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Text(
            node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (node.missing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A dashed outline: a gap in the chain — "No amp" — drawn as a gap, not a box. */
private fun Modifier.dashedBorder(color: androidx.compose.ui.graphics.Color): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(10.dp.toPx()),
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
    )
}

/**
 * A rig's chain sized to where it's drawn: measured, not guessed from the
 * screen. Under 380 dp the pedals fold into one "+N" box so it stays a single
 * readable row (the Gear list, the Coach's "dialing in for" line).
 */
@Composable
fun RigChain(rig: Rig, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val narrow = maxWidth < 380.dp
        val max = if (!narrow) 3 else if (rig.pedals.size <= 1) 1 else 0
        SignalChain(chainNodes(rig, max))
    }
}

/** A short patch cable between two boxes: a lead with a jack at each end. */
@Composable
private fun Cable() {
    val lead = MaterialTheme.colorScheme.outline
    val jack = RiffColors.knobPointer
    Canvas(Modifier.size(width = 18.dp, height = 12.dp)) {
        val y = size.height / 2f
        drawLine(lead, Offset(2.dp.toPx(), y), Offset(size.width - 2.dp.toPx(), y), strokeWidth = 2.dp.toPx())
        drawCircle(jack, radius = 2.5.dp.toPx(), center = Offset(2.5.dp.toPx(), y))
        drawCircle(jack, radius = 2.5.dp.toPx(), center = Offset(size.width - 2.5.dp.toPx(), y))
    }
}

// ---- amp ------------------------------------------------------------------------

/**
 * An amp's front panel: a strip of grille cloth, then its panel controls left
 * to right as the profile lists them — which is the order on the real amp
 * (MustangLt25.kt). Knobs point at noon: this shows what the amp *has*, not
 * a setting; setting them is the tone card's job.
 */
@Composable
fun AmpPanel(profile: AmpProfile, modifier: Modifier = Modifier, knob: Dp = 40.dp, showName: Boolean = true) {
    val panel = profile.panelControls
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clearAndSetSemantics {
                contentDescription = "${profile.displayName.ifBlank { "Amp" }} panel: " +
                    panel.joinToString(", ") { it.name }.ifBlank { "no controls listed" }
            },
    ) {
        GrilleCloth(Modifier.fillMaxWidth().height(18.dp))
        if (showName && profile.displayName.isNotBlank()) {
            Text(
                profile.displayName.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 14.dp, top = 10.dp),
            )
        }
        if (panel.isEmpty()) {
            Text(
                "No panel controls listed yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        } else {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                panel.forEach { PanelControl(it, knob) }
            }
        }
    }
}

@Composable
private fun PanelControl(control: AmpControl, knob: Dp) {
    Column(Modifier.width(knob + 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (control.kind) {
            ControlKind.SWITCH -> ToggleSwitch(knob)
            else -> KnobFace(NOON, size = knob)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            control.name.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            when (control.kind) {
                ControlKind.KNOB -> control.scale.replace("-", "–").let { if (it.startsWith("–")) "-" + it.drop(1) else it }
                ControlKind.SWITCH -> "on/off"
                ControlKind.SELECTOR -> if (control.options.isEmpty()) "select" else "${control.options.size} pos."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A panel toggle: a slot with the lever up. */
@Composable
private fun ToggleSwitch(size: Dp) {
    val slot = RiffColors.faceplate
    val lever = RiffColors.knobPointer
    val rim = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(size)) {
        val w = this.size.width * 0.34f
        val h = this.size.height * 0.8f
        val left = (this.size.width - w) / 2f
        val top = (this.size.height - h) / 2f
        drawRoundRect(slot, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = CornerRadius(w / 2f))
        drawRoundRect(rim, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = CornerRadius(w / 2f), style = Stroke(1.5f))
        drawCircle(lever, radius = w * 0.42f, center = Offset(this.size.width / 2f, top + w * 0.55f))
    }
}

/** The speaker cloth along the top of an amp: a fine diagonal weave. Shared with the tone card. */
@Composable
fun GrilleCloth(modifier: Modifier) {
    val cloth = MaterialTheme.colorScheme.surfaceContainerHigh
    val weave = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    Canvas(modifier.background(cloth)) {
        val step = 6.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(weave, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1f)
            x += step
        }
    }
}

// ---- pedal ----------------------------------------------------------------------

/**
 * A stompbox: up to three knobs across the top, named from the pedal's own
 * controls, its name and type, and a footswitch with a lamp. A multi-FX with
 * a dozen controls still shows three — the pedal's page lists the rest.
 */
@Composable
fun Stompbox(name: String, profile: PedalProfile, modifier: Modifier = Modifier, width: Dp = 148.dp) {
    val shape = RoundedCornerShape(14.dp)
    val body = MaterialTheme.colorScheme.primaryContainer
    val onBody = MaterialTheme.colorScheme.onPrimaryContainer
    val knobs = profile.controls.take(3)
    Column(
        modifier
            .width(width)
            .clip(shape)
            .background(body)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .clearAndSetSemantics {
                contentDescription = "$name, ${profile.type.ifBlank { "pedal" }}" +
                    if (profile.controls.isNotEmpty()) ": ${profile.controls.joinToString(", ")}" else ""
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (knobs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                knobs.forEach { c ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        KnobFace(NOON, size = 22.dp)
                        Text(
                            c.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = onBody,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 40.dp),
                        )
                    }
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = onBody,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (profile.type.isNotBlank()) {
            Text(
                profile.type.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = onBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Footswitch()
    }
}

/** A lamp over a round metal footswitch. Shared with the tone card's effect pedals. */
@Composable
fun Footswitch() {
    val lamp = RiffColors.lampLit
    val metal = RiffColors.faceplate
    val rim = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(width = 40.dp, height = 40.dp)) {
        val cx = size.width / 2f
        drawCircle(lamp, radius = 3.dp.toPx(), center = Offset(cx, 4.dp.toPx()))
        val r = 13.dp.toPx()
        val c = Offset(cx, size.height - r - 1.dp.toPx())
        drawCircle(metal, radius = r, center = c)
        drawCircle(rim, radius = r, center = c, style = Stroke(2f))
        drawCircle(rim, radius = r * 0.55f, center = c, style = Stroke(1.5f))
    }
}

// ---- guitar ---------------------------------------------------------------------

/**
 * A guitar's pickup selector: the lever's positions on an arc, each labeled.
 * The lever is drawn at the first position — which one is *chosen* is a tone
 * card's business; this only shows what's there.
 */
@Composable
fun PickupSelector(profile: GuitarProfile, modifier: Modifier = Modifier, showDescriptions: Boolean = false) {
    val positions = profile.pickups
    val summary = if (positions.isEmpty()) "No pickup positions listed"
    else "Pickup selector: " + positions.joinToString(", ") { it.label }
    Row(
        modifier.clearAndSetSemantics { contentDescription = summary },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SelectorDial(positions.size.coerceAtLeast(1))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            if (positions.isEmpty()) {
                Text("No pickup positions listed yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            positions.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(18.dp),
                    )
                    Column {
                        Text(p.label, style = MaterialTheme.typography.bodyMedium)
                        if (showDescriptions && p.description.isNotBlank()) {
                            Text(p.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** The switch itself: [count] numbered stops on an arc, lever on the first. */
@Composable
private fun SelectorDial(count: Int) {
    val plate = RiffColors.faceplate
    val rim = MaterialTheme.colorScheme.outlineVariant
    val stop = MaterialTheme.colorScheme.onSurfaceVariant
    val lever = RiffColors.knobPointer
    Canvas(Modifier.size(64.dp)) {
        val pivot = Offset(size.width / 2f, size.height * 0.82f)
        val reach = size.height * 0.62f
        drawRoundRect(plate, topLeft = Offset(4f, 4f), size = androidx.compose.ui.geometry.Size(size.width - 8f, size.height - 8f),
            cornerRadius = CornerRadius(12.dp.toPx()))
        drawRoundRect(rim, topLeft = Offset(4f, 4f), size = androidx.compose.ui.geometry.Size(size.width - 8f, size.height - 8f),
            cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(1.5f))
        // Stops from upper left to upper right, as a toggle's throw reads.
        val spread = if (count == 1) 0.0 else 70.0
        fun at(i: Int): Offset {
            val deg = -90.0 - spread / 2 + (if (count == 1) 0.0 else spread * i / (count - 1))
            val rad = deg * PI / 180
            return Offset(pivot.x + (cos(rad) * reach).toFloat(), pivot.y + (sin(rad) * reach).toFloat())
        }
        repeat(count) { i -> drawCircle(stop, radius = 3.dp.toPx(), center = at(i)) }
        drawLine(lever, pivot, at(0), strokeWidth = 4.dp.toPx())
        drawCircle(lever, radius = 5.dp.toPx(), center = at(0))
        drawCircle(rim, radius = 5.dp.toPx(), center = pivot)
    }
}

/** A small label chip in the gear's own style: "6 strings", "E standard". */
@Composable
fun GearTag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
