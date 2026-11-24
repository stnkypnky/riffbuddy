package com.riffbuddy.ui.gear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffbuddy.data.Rig
import com.riffbuddy.data.SavedAmp
import com.riffbuddy.data.SavedGuitar
import com.riffbuddy.data.SavedPedal
import com.riffbuddy.domain.gear.AmpKind
import com.riffbuddy.domain.gear.EffectCategory
import com.riffbuddy.domain.gear.Reach
import com.riffbuddy.ui.Panel
import com.riffbuddy.ui.SectionHeader
import com.riffbuddy.ui.tactile.IconButton
import com.riffbuddy.ui.tactile.TextButton
import com.riffbuddy.ui.theme.RiffColors

/*
 * Read-only pages for one piece of gear: everything the app knows
 * about it, laid out to look at. Editing is one tap away on the pencil, but
 * seeing no longer means opening a form and backing out without saving.
 */

/** A menu entry in a detail page's ⋮. */
class GearAction(val label: String, val run: () -> Unit)

/** The frame every detail page shares: back, name, pencil, ⋮, then a scrolling body. */
@Composable
fun GearDetailPage(
    title: String,
    kind: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    actions: List<GearAction>,
    body: @Composable ColumnScope.() -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to gear")
            }
            Column(Modifier.weight(1f)) {
                Text(kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit $title")
            }
            if (actions.isNotEmpty()) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More for $title")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        actions.forEach { a ->
                            DropdownMenuItem(text = { Text(a.label) }, onClick = { menu = false; a.run() })
                        }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { body() }
    }
}

// ---- amp ------------------------------------------------------------------------

@Composable
fun ColumnScope.AmpDetailBody(amp: SavedAmp, inRigs: List<Rig>, onOpenRig: (Rig) -> Unit) {
    val p = amp.profile
    AmpPanel(p, knob = 46.dp)

    val hidden = p.controls.filter { it.reach == Reach.DISPLAY }
    if (hidden.isNotEmpty()) {
        // The LT25's Middle: real, adjustable, and not on the panel. Said
        // here so nobody hunts the amp for a knob that isn't there.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            hidden.forEach { c ->
                Text(
                    "${c.name} · in the amp's display" + if (c.note.isNotBlank()) " — ${c.note}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }

    Facts(
        listOfNotNull(
            p.displayName.takeIf { it.isNotBlank() },
            when (p.kind) { AmpKind.ANALOG -> "Analog"; AmpKind.MODELING -> "Modeling"; AmpKind.HYBRID -> "Hybrid" },
            p.presetSlots.takeIf { it > 0 }?.let { "$it preset slots" },
        ),
    )

    if (p.channels.isNotEmpty()) {
        DetailSection("CHANNELS") { TagRow(p.channels) }
    }

    val notes = p.controls.filter { it.note.isNotBlank() && it.reach == Reach.PANEL }
    if (notes.isNotEmpty()) {
        DetailSection("ABOUT THE KNOBS") {
            notes.forEach { c -> Line(c.name, c.note) }
        }
    }

    if (p.ampModels.isNotEmpty()) {
        var all by rememberSaveable { mutableStateOf(false) }
        DetailSection("AMP MODELS · ${p.ampModels.size}") {
            (if (all) p.ampModels else p.ampModels.take(PREVIEW)).forEach { Line(it.name, it.description) }
            if (p.ampModels.size > PREVIEW) ShowAll(all, p.ampModels.size) { all = !all }
        }
    }

    if (p.effects.isNotEmpty()) {
        DetailSection("EFFECTS · ${p.effects.size}") {
            Text(
                if (p.slotsPerCategory == 1) "One of each kind at a time in a preset." else "Up to ${p.slotsPerCategory} of each kind in a preset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EffectCategory.entries.forEach { cat ->
                val list = p.effectsIn(cat)
                if (list.isNotEmpty()) {
                    var open by rememberSaveable(cat) { mutableStateOf(false) }
                    Text(
                        "${cat.label} · ${list.size}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    (if (open) list else list.take(3)).forEach { Line(it.name, it.description) }
                    if (list.size > 3) ShowAll(open, list.size) { open = !open }
                }
            }
        }
    }

    if (p.notes.isNotBlank()) DetailSection("NOTES") { Body(p.notes) }
    InRigs(inRigs, onOpenRig)
}

private const val PREVIEW = 6

// ---- guitar ---------------------------------------------------------------------

@Composable
fun ColumnScope.GuitarDetailBody(guitar: SavedGuitar, tuningName: String?, inRigs: List<Rig>, onOpenRig: (Rig) -> Unit) {
    val p = guitar.profile
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("PICKUP SELECTOR")
            PickupSelector(p, showDescriptions = true)
        }
    }
    Facts(listOfNotNull(p.displayName.takeIf { it.isNotBlank() }, "${p.stringCount} strings", tuningName?.let { "Usually in $it" }))
    if (p.controls.isNotEmpty()) DetailSection("CONTROLS") { TagRow(p.controls) }
    if (p.notes.isNotBlank()) DetailSection("NOTES") { Body(p.notes) }
    InRigs(inRigs, onOpenRig)
}

// ---- pedal ----------------------------------------------------------------------

@Composable
fun ColumnScope.PedalDetailBody(pedal: SavedPedal, inRigs: List<Rig>, onOpenRig: (Rig) -> Unit) {
    val p = pedal.profile
    Stompbox(pedal.name, p, width = 200.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
    Facts(listOfNotNull(p.displayName.takeIf { it.isNotBlank() }, p.type.takeIf { it.isNotBlank() }))
    if (p.controls.isNotEmpty()) DetailSection("CONTROLS · ${p.controls.size}") { TagRow(p.controls) }
    if (p.notes.isNotBlank()) DetailSection("NOTES") { Body(p.notes) }
    InRigs(inRigs, onOpenRig)
}

// ---- rig ------------------------------------------------------------------------

@Composable
fun ColumnScope.RigDetailBody(rig: Rig, cards: Int, onOpen: (ChainNode) -> Unit) {
    if (rig.isDefault) DefaultBadge()
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("SIGNAL CHAIN")
            // Every pedal shown here, wrapping if it must; tap a box to open it.
            SignalChain(chainNodes(rig), wrap = true, onTap = onOpen)
            Text(
                "Tap a box to see that piece of gear.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Facts(
        listOf(
            when (cards) { 0 -> "No tone cards yet"; 1 -> "1 tone card"; else -> "$cards tone cards" },
            if (rig.isDefault) "New tone cards use this rig" else "Not the default",
        ),
    )
    if (rig.amp == null) {
        Text(
            "This rig has no amp, so the coach can't make tone cards for it yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** "● DEFAULT": the lamp says it at a glance, the word says it for sure. */
@Composable
fun DefaultBadge(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val lit = RiffColors.lampLit
        androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(lit) }
        Spacer(Modifier.size(6.dp))
        Text("DEFAULT", style = MaterialTheme.typography.labelMedium, color = RiffColors.lampLit)
    }
}

// ---- pieces ---------------------------------------------------------------------

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(title)
            content()
        }
    }
}

@Composable
private fun Facts(facts: List<String>) {
    if (facts.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        facts.forEach { GearTag(it) }
    }
}

@Composable
private fun TagRow(items: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { GearTag(it) }
    }
}

@Composable
private fun Line(name: String, description: String) {
    Column {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        if (description.isNotBlank()) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ShowAll(open: Boolean, total: Int, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (open) "Show fewer" else "Show all $total")
    }
}

@Composable
private fun InRigs(rigs: List<Rig>, onOpenRig: (Rig) -> Unit) {
    if (rigs.isEmpty()) {
        Text(
            "Not in any rig yet. Add it to one to use it for tone cards.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    DetailSection("IN RIGS") {
        rigs.forEach { r ->
            TextButton(onClick = { onOpenRig(r) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(r.name + if (r.isDefault) "  · default" else "")
            }
        }
    }
}
