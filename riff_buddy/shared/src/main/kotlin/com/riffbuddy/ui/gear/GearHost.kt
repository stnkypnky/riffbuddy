package com.riffbuddy.ui.gear

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.riffbuddy.ui.theme.Entrance
import com.riffbuddy.ui.theme.screenEntrance
import com.riffbuddy.ui.theme.tactileClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.MaterialTheme
import com.riffbuddy.ui.tactile.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffbuddy.ai.AiCoach
import com.riffbuddy.ai.AiResult
import com.riffbuddy.ai.GearSuggest
import com.riffbuddy.ai.GearSuggest.isEmpty
import com.riffbuddy.domain.gear.AmpModel
import com.riffbuddy.domain.gear.ControlRole
import com.riffbuddy.domain.gear.EffectCategory
import com.riffbuddy.domain.gear.EffectModel
import com.riffbuddy.domain.gear.KnownGear
import com.riffbuddy.domain.gear.Reach
import com.riffbuddy.data.GearRepo
import com.riffbuddy.data.Rig
import com.riffbuddy.data.SavedAmp
import com.riffbuddy.data.SavedGuitar
import com.riffbuddy.data.SavedPedal
import com.riffbuddy.domain.gear.AmpControl
import com.riffbuddy.domain.gear.AmpKind
import com.riffbuddy.domain.gear.AmpProfile
import com.riffbuddy.domain.gear.ControlKind
import com.riffbuddy.domain.gear.GuitarProfile
import com.riffbuddy.domain.gear.PedalProfile
import com.riffbuddy.domain.gear.PickupPosition
import com.riffbuddy.domain.music.Tuning
import com.riffbuddy.ui.ConfirmDelete
import kotlinx.coroutines.launch

/**
 * Me › Gear: rigs, and the guitars, amps and pedals they are made of.
 *
 * Owns its own lists and reloads them after every change; [onChanged] tells
 * the rest of the app, whose default rig (and so every new tone card) may
 * have just changed.
 *
 * The list draws each piece of gear as what it is, and a tap opens a
 * read-only page for it. Editing lives on that page's pencil, so looking at
 * the amp no longer means opening a form and backing out.
 */
@Composable
fun GearHost(repo: GearRepo, coach: AiCoach, tunings: List<Tuning>, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rigs by remember { mutableStateOf(emptyList<Rig>()) }
    var guitars by remember { mutableStateOf(emptyList<SavedGuitar>()) }
    var amps by remember { mutableStateOf(emptyList<SavedAmp>()) }
    var pedals by remember { mutableStateOf(emptyList<SavedPedal>()) }
    var cardCounts by remember { mutableStateOf(emptyMap<Long, Int>()) }
    var loaded by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }

    var editingRig by remember { mutableStateOf<Rig?>(null) }
    var editingGuitar by remember { mutableStateOf<SavedGuitar?>(null) }
    var editingAmp by remember { mutableStateOf<SavedAmp?>(null) }
    var editingPedal by remember { mutableStateOf<SavedPedal?>(null) }
    var deleting by remember { mutableStateOf<Any?>(null) }
    var rigCards by remember { mutableIntStateOf(0) }

    // Which pages are open, as "kind:id" keys joined by "|": a trail, so rig →
    // amp → Back lands on the rig again. Keys rather than objects, so after an
    // edit the page shows the reloaded gear, and a rotation keeps the page.
    var trail by rememberSaveable { mutableStateOf("") }
    val keys = trail.split('|').filter { it.isNotEmpty() }
    fun open(key: String) { trail = (keys + key).joinToString("|") }
    fun back() { trail = keys.dropLast(1).joinToString("|") }

    LaunchedEffect(version) {
        rigs = repo.rigs(); guitars = repo.guitars(); amps = repo.amps(); pedals = repo.pedals()
        cardCounts = rigs.associate { it.id to repo.cardCount(it.id) }
        loaded = true
    }
    fun changed(action: suspend () -> Unit) = scope.launch { action(); version++; onChanged() }

    val current: Any? = keys.lastOrNull()?.let { key ->
        val id = key.substringAfter(':').toLongOrNull()
        when (key.substringBefore(':')) {
            "rig" -> rigs.firstOrNull { it.id == id }
            "guitar" -> guitars.firstOrNull { it.id == id }
            "amp" -> amps.firstOrNull { it.id == id }
            "pedal" -> pedals.firstOrNull { it.id == id }
            else -> null
        }
    }
    // A page whose gear is gone — just deleted from its own ⋮ — closes itself.
    LaunchedEffect(current, loaded, trail) { if (loaded && keys.isNotEmpty() && current == null) back() }

    // Composed inside the Me tab, after the Shell's own BackHandler, so this
    // one answers first: Back closes the gear page instead of leaving for Home.
    com.riffbuddy.platform.LocalPlatform.current.BackHandler(enabled = keys.isNotEmpty()) { back() }

    // Entrances: deeper slides in from the right, back from the left.
    val depth = keys.size
    val last = remember { object { var depth = depth } }
    val entrance = remember(trail) {
        val e = when {
            depth > last.depth -> Entrance.FORWARD
            depth < last.depth -> Entrance.BACK
            else -> Entrance.SOFT
        }
        last.depth = depth
        e
    }

    Box(Modifier.fillMaxSize().screenEntrance(trail, entrance)) {
        when (val g = current) {
            is Rig -> GearDetailPage(
                g.name, "Rig", onBack = ::back, onEdit = { editingRig = g },
                actions = listOfNotNull(
                    if (!g.isDefault) GearAction("Make default") { changed { repo.makeDefault(g.id) } } else null,
                    GearAction("Delete rig") { scope.launch { rigCards = repo.cardCount(g.id); deleting = g } },
                ),
            ) {
                RigDetailBody(g, cardCounts[g.id] ?: 0) { node ->
                    when (node.kind) {
                        ChainKind.GUITAR -> open("guitar:${node.id}")
                        ChainKind.AMP -> open("amp:${node.id}")
                        ChainKind.PEDAL -> open("pedal:${node.id}")
                        ChainKind.MORE -> Unit
                    }
                }
            }
            is SavedGuitar -> GearDetailPage(
                g.name, "Guitar", onBack = ::back, onEdit = { editingGuitar = g },
                actions = listOf(GearAction("Delete guitar") { deleting = g }),
            ) {
                GuitarDetailBody(g, tunings.firstOrNull { it.id == g.defaultTuningId }?.name,
                    rigs.filter { it.guitar?.id == g.id }) { open("rig:${it.id}") }
            }
            is SavedAmp -> GearDetailPage(
                g.name, "Amp", onBack = ::back, onEdit = { editingAmp = g },
                actions = listOf(GearAction("Delete amp") { deleting = g }),
            ) {
                AmpDetailBody(g, rigs.filter { it.amp?.id == g.id }) { open("rig:${it.id}") }
            }
            is SavedPedal -> GearDetailPage(
                g.name, "Pedal", onBack = ::back, onEdit = { editingPedal = g },
                actions = listOf(GearAction("Delete pedal") { deleting = g }),
            ) {
                PedalDetailBody(g, rigs.filter { r -> r.pedals.any { it.id == g.id } }) { open("rig:${it.id}") }
            }
            else -> GearList(
                rigs, guitars, amps, pedals, cardCounts, tunings, loaded,
                onOpen = ::open,
                onAddRig = { editingRig = NEW_RIG },
                onAddGuitar = { editingGuitar = NEW_GUITAR },
                onAddAmp = { editingAmp = NEW_AMP },
                onAddPedal = { editingPedal = NEW_PEDAL },
            )
        }
    }

    editingRig?.let { rig ->
        RigEditor(rig.takeIf { it !== NEW_RIG }, guitars, amps, pedals, isOnlyRig = rigs.isEmpty(),
            onDismiss = { editingRig = null },
            onSave = { name, g, a, ps, def -> changed { repo.saveRig(rig.id, name, g, a, ps, def) }; editingRig = null })
    }
    editingGuitar?.let { g ->
        GuitarEditor(g.takeIf { it !== NEW_GUITAR }, tunings, coach,
            onDismiss = { editingGuitar = null },
            onSave = { name, profile, tuningId -> changed { repo.saveGuitar(g.id, name, profile, tuningId) }; editingGuitar = null })
    }
    editingAmp?.let { a ->
        AmpEditor(a.takeIf { it !== NEW_AMP }, coach,
            onDismiss = { editingAmp = null },
            onSave = { name, profile -> changed { repo.saveAmp(a.id, name, profile) }; editingAmp = null })
    }
    editingPedal?.let { p ->
        PedalEditor(p.takeIf { it !== NEW_PEDAL }, coach,
            onDismiss = { editingPedal = null },
            onSave = { name, profile -> changed { repo.savePedal(p.id, name, profile) }; editingPedal = null })
    }

    when (val d = deleting) {
        is Rig -> if (rigCards > 0) {
            // Asks what to do with its tone cards; keeping them is the default.
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete \"${d.name}\"?") },
                text = {
                    Text(
                        "$rigCards tone card${if (rigCards == 1) " was" else "s were"} made for this rig. Keep them — " +
                            "archived, still on their songs — or delete them with it?",
                    )
                },
                confirmButton = { TextButton(onClick = { changed { repo.deleteRig(d.id, keepCards = true) }; deleting = null }) { Text("Keep the cards") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { deleting = null }) { Text("Cancel") }
                        TextButton(onClick = { changed { repo.deleteRig(d.id, keepCards = false) }; deleting = null }) { Text("Delete them too") }
                    }
                },
            )
        } else ConfirmDelete("\"${d.name}\"", "The guitar, amp and pedals in it stay.",
            onConfirm = { changed { repo.deleteRig(d.id, keepCards = true) } }, onDismiss = { deleting = null })
        is SavedGuitar -> ConfirmDelete("\"${d.name}\"", "Rigs using it keep their amp and pedals.",
            onConfirm = { changed { repo.deleteGuitar(d.id) } }, onDismiss = { deleting = null })
        is SavedAmp -> ConfirmDelete("\"${d.name}\"", "Rigs using it will need another amp before they can make tone cards.",
            onConfirm = { changed { repo.deleteAmp(d.id) } }, onDismiss = { deleting = null })
        is SavedPedal -> ConfirmDelete("\"${d.name}\"", "It is taken out of every rig it is in.",
            onConfirm = { changed { repo.deletePedal(d.id) } }, onDismiss = { deleting = null })
        else -> Unit
    }
}

// Stand-ins for "a new one", distinct from "no editor open".
private val NEW_RIG = Rig(0, "", null, null, false)
private val NEW_GUITAR = SavedGuitar(0, "", GuitarProfile("", ""))
private val NEW_AMP = SavedAmp(0, "", AmpProfile("", "", AmpKind.ANALOG, emptyList()))
private val NEW_PEDAL = SavedPedal(0, "", PedalProfile("", ""))

// ---- the list -------------------------------------------------------------------

@Composable
private fun GearList(
    rigs: List<Rig>, guitars: List<SavedGuitar>, amps: List<SavedAmp>, pedals: List<SavedPedal>,
    cardCounts: Map<Long, Int>, tunings: List<Tuning>, loaded: Boolean,
    onOpen: (String) -> Unit,
    onAddRig: () -> Unit, onAddGuitar: () -> Unit, onAddAmp: () -> Unit, onAddPedal: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Gear", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Tone cards are set up for your default rig. Tap anything to see it in full.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val default = rigs.firstOrNull { it.isDefault }
        GearSection(if (rigs.size > 1) "RIGS" else "YOUR RIG", "Add a rig", onAddRig)
        if (loaded && rigs.isEmpty()) Hint("A rig is the guitar, pedals and amp you play together. Tone cards are made for your default rig.")
        default?.let { RigCard(it, cardCounts[it.id] ?: 0, hero = true) { onOpen("rig:${it.id}") } }
        rigs.filter { it !== default }.forEach { r -> RigCard(r, cardCounts[r.id] ?: 0, hero = false) { onOpen("rig:${r.id}") } }

        GearSection("GUITARS", "Add a guitar", onAddGuitar)
        if (loaded && guitars.isEmpty()) Hint("Pickups matter most: a bridge humbucker and a neck single-coil want different amp settings.")
        guitars.forEach { g -> GuitarCard(g, tunings.firstOrNull { it.id == g.defaultTuningId }?.name) { onOpen("guitar:${g.id}") } }

        GearSection("AMPS", "Add an amp", onAddAmp)
        if (loaded && amps.isEmpty()) Hint("The coach sets tone for your amp's actual knobs, so add the one you play through.")
        amps.forEach { a -> AmpCard(a) { onOpen("amp:${a.id}") } }

        GearSection("PEDALS AND MULTI-FX", "Add a pedal", onAddPedal)
        if (loaded && pedals.isEmpty()) Hint("Optional. Add pedals you use, and put them in a rig in the order the signal runs.")
        if (pedals.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                pedals.forEach { p ->
                    Stompbox(
                        p.name, p.profile,
                        Modifier.clip(RoundedCornerShape(14.dp)).tactileClickable(onClickLabel = "Open ${p.name}") { onOpen("pedal:${p.id}") },
                    )
                }
            }
        }
    }
}

/** A section title with its Add on the same line, so the list isn't broken up by buttons. */
@Composable
private fun GearSection(title: String, addLabel: String, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        com.riffbuddy.ui.SectionHeader(title, Modifier.weight(1f))
        TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = addLabel }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text("Add")
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RigCard(rig: Rig, cards: Int, hero: Boolean, onOpen: () -> Unit) {
    com.riffbuddy.ui.Panel(Modifier.fillMaxWidth().tactileClickable(onClickLabel = "Open ${rig.name}", onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rig.name,
                    style = if (hero) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (rig.isDefault) DefaultBadge()
            }
            RigChain(rig)
            Text(
                when (cards) { 0 -> "No tone cards yet"; 1 -> "1 tone card"; else -> "$cards tone cards" } +
                    if (rig.amp == null) " · needs an amp for tone cards" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GuitarCard(guitar: SavedGuitar, tuningName: String?, onOpen: () -> Unit) {
    com.riffbuddy.ui.Panel(Modifier.fillMaxWidth().tactileClickable(onClickLabel = "Open ${guitar.name}", onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(guitar.name, style = MaterialTheme.typography.titleMedium)
                if (guitar.profile.displayName.isNotBlank() && guitar.profile.displayName != guitar.name) {
                    Text(guitar.profile.displayName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PickupSelector(guitar.profile)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GearTag("${guitar.profile.stringCount} strings")
                tuningName?.let { GearTag(it) }
            }
        }
    }
}

@Composable
private fun AmpCard(amp: SavedAmp, onOpen: () -> Unit) {
    com.riffbuddy.ui.Panel(Modifier.fillMaxWidth().tactileClickable(onClickLabel = "Open ${amp.name}", onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(amp.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                GearTag(amp.profile.kind.name.lowercase().replaceFirstChar { it.uppercase() })
            }
            AmpPanel(amp.profile, knob = 34.dp, showName = amp.profile.displayName != amp.name)
            val extras = listOfNotNull(
                amp.profile.ampModels.size.takeIf { it > 0 }?.let { "$it amp models" },
                amp.profile.effects.size.takeIf { it > 0 }?.let { "$it effects" },
                amp.profile.channels.size.takeIf { it > 0 }?.let { "$it channels" },
            )
            if (extras.isNotEmpty()) {
                Text(extras.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- editors ------------------------------------------------------------------

@Composable
private fun RigEditor(
    rig: Rig?, guitars: List<SavedGuitar>, amps: List<SavedAmp>, pedals: List<SavedPedal>, isOnlyRig: Boolean,
    onDismiss: () -> Unit, onSave: (String, Long?, Long?, List<Long>, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(rig?.name ?: "") }
    var guitarId by remember { mutableStateOf(rig?.guitar?.id ?: guitars.firstOrNull()?.id) }
    var ampId by remember { mutableStateOf(rig?.amp?.id ?: amps.firstOrNull()?.id) }
    var pedalIds by remember { mutableStateOf(rig?.pedals?.map { it.id } ?: emptyList()) }
    var isDefault by remember { mutableStateOf(rig?.isDefault ?: isOnlyRig) }

    EditorSheet(if (rig == null) "New rig" else "Edit rig", onDismiss,
        onSave = { onSave(name.trim(), guitarId, ampId, pedalIds, isDefault) }, saveEnabled = name.isNotBlank()) {
        Field("Name", name) { name = it }
        SectionLabel("GUITAR")
        Choice("None", guitarId == null) { guitarId = null }
        guitars.forEach { g -> Choice(g.name, guitarId == g.id) { guitarId = g.id } }
        SectionLabel("AMP")
        Choice("None", ampId == null) { ampId = null }
        amps.forEach { a -> Choice(a.name, ampId == a.id) { ampId = a.id } }
        if (pedals.isNotEmpty()) {
            SectionLabel("PEDALS — in the order you tick them, first to last")
            pedals.forEach { p ->
                val at = pedalIds.indexOf(p.id)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = at >= 0, onCheckedChange = { on -> pedalIds = if (on) pedalIds + p.id else pedalIds - p.id })
                    Text(p.name + if (at >= 0) "  (${at + 1})" else "", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isDefault, onCheckedChange = { isDefault = it }, enabled = !(rig?.isDefault ?: false))
            Text("Default rig — tone cards are made for this one", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GuitarEditor(
    saved: SavedGuitar?, tunings: List<Tuning>, coach: AiCoach,
    onDismiss: () -> Unit, onSave: (String, GuitarProfile, Long?) -> Unit,
) {
    val start = saved?.profile ?: GuitarProfile("", "", pickups = listOf(PickupPosition("Bridge"), PickupPosition("Neck")), controls = listOf("Volume", "Tone"))
    var name by remember { mutableStateOf(saved?.name ?: "") }
    var make by remember { mutableStateOf(start.make) }
    var model by remember { mutableStateOf(start.model) }
    var strings by remember { mutableStateOf(start.stringCount) }
    var pickups by remember { mutableStateOf(start.pickups.map { it.label }) }
    // Descriptions ride along by label: the list editor edits names, and a
    // pickup renamed by hand simply loses its description.
    var pickupNotes by remember { mutableStateOf(start.pickups.associate { it.label to it.description }) }
    var controls by remember { mutableStateOf(start.controls) }
    var notes by remember { mutableStateOf(start.notes) }
    var tuningId by remember { mutableStateOf(saved?.defaultTuningId) }

    EditorSheet(if (saved == null) "New guitar" else "Edit guitar", onDismiss, saveEnabled = name.isNotBlank(), onSave = {
        onSave(name.trim(), start.copy(
            make = make.trim(), model = model.trim(), stringCount = strings,
            pickups = pickups.map { PickupPosition(it, pickupNotes[it] ?: "") }, controls = controls, notes = notes.trim(),
        ), tuningId)
    }) {
        if (saved == null) KnownPicks(KnownGear.guitars.map { it.displayName }) { i ->
            val g = KnownGear.guitars[i]
            make = g.make; model = g.model; strings = g.stringCount; notes = g.notes
            pickups = g.pickups.map { it.label }; pickupNotes = g.pickups.associate { it.label to it.description }
            controls = g.controls
            if (name.isBlank()) name = g.model
        }
        Field("Name (what you call it)", name) { name = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Make", make, Modifier.weight(1f)) { make = it }
            Field("Model", model, Modifier.weight(1f)) { model = it }
        }
        SuggestButton(coach, GearSuggest.Kind.GUITAR, make, model) { s ->
            if (s.pickups.isNotEmpty()) {
                pickups = s.pickups.map { it.label }
                pickupNotes = s.pickups.associate { it.label to it.description }
            }
            if (s.controls.isNotEmpty()) controls = s.controls.map { it.name }
            if (name.isBlank()) name = model.trim()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(6, 7, 8).forEach { n -> FilterChip(strings == n, { strings = n }, { Text("$n strings") }) }
        }
        TuningPicker(tunings, tuningId) { tuningId = it }
        NameListEditor("PICKUP POSITIONS", pickups, "e.g. Middle") { pickups = it }
        pickups.mapNotNull { p -> pickupNotes[p]?.takeIf { it.isNotBlank() }?.let { "$p: $it" } }.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NameListEditor("CONTROLS", controls, "e.g. Coil split") { controls = it }
        Text(
            "Add a control with \"coil\" or \"split\" in its name and tone cards may suggest splitting.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TuningPicker(tunings: List<Tuning>, selectedId: Long?, onPick: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Usual tuning: ", style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { open = true }) { Text(tunings.firstOrNull { it.id == selectedId }?.name ?: "Not set") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Not set") }, onClick = { onPick(null); open = false })
                tunings.forEach { t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { onPick(t.id); open = false }) }
            }
        }
    }
}

@Composable
private fun AmpEditor(saved: SavedAmp?, coach: AiCoach, onDismiss: () -> Unit, onSave: (String, AmpProfile) -> Unit) {
    val start = saved?.profile ?: AmpProfile("", "", AmpKind.ANALOG,
        listOf("Gain", "Bass", "Middle", "Treble", "Volume").map { AmpControl(it) })
    var name by remember { mutableStateOf(saved?.name ?: "") }
    var make by remember { mutableStateOf(start.make) }
    var model by remember { mutableStateOf(start.model) }
    var kind by remember { mutableStateOf(start.kind) }
    var channels by remember { mutableStateOf(start.channels) }
    var controls by remember { mutableStateOf(start.controls) }
    var notes by remember { mutableStateOf(start.notes) }
    var ampModels by remember { mutableStateOf(start.ampModels) }
    var effects by remember { mutableStateOf(start.effects) }
    var editorApp by remember { mutableStateOf(start.editorApp) }
    var slots by remember { mutableIntStateOf(start.slotsPerCategory) }
    val valid = name.isNotBlank() && controls.all { it.name.isNotBlank() && (it.kind != ControlKind.KNOB || it.min < it.max) }

    fun fill(p: AmpProfile) {
        make = p.make; model = p.model; kind = p.kind; channels = p.channels; controls = p.controls
        ampModels = p.ampModels; effects = p.effects; editorApp = p.editorApp; slots = p.slotsPerCategory; notes = p.notes
        if (name.isBlank()) name = p.model
    }

    EditorSheet(if (saved == null) "New amp" else "Edit amp", onDismiss, saveEnabled = valid, onSave = {
        onSave(name.trim(), start.copy(make = make.trim(), model = model.trim(), kind = kind,
            channels = channels, controls = controls.map { it.copy(name = it.name.trim()) }, notes = notes.trim(),
            ampModels = ampModels, effects = effects, editorApp = editorApp.trim(), slotsPerCategory = slots))
    }) {
        if (saved == null) KnownPicks(KnownGear.amps.map { it.displayName }) { fill(KnownGear.amps[it]) }
        Field("Name (what you call it)", name) { name = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Make", make, Modifier.weight(1f)) { make = it }
            Field("Model", model, Modifier.weight(1f)) { model = it }
        }
        SuggestButton(coach, GearSuggest.Kind.AMP, make, model) { s ->
            if (s.controls.isNotEmpty()) controls = s.controls
            if (s.channels.isNotEmpty()) channels = s.channels
            if (s.ampModels.isNotEmpty()) { ampModels = s.ampModels; kind = AmpKind.MODELING }
            if (s.effects.isNotEmpty()) effects = s.effects
            if (s.editorApp.isNotBlank()) editorApp = s.editorApp
            slots = s.effectsPerCategory
            if (name.isBlank()) name = model.trim()
        }
        SectionLabel("TYPE")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmpKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
        }
        NameListEditor("CHANNELS OR MODES", channels, "e.g. Lead") { channels = it }
        ControlListEditor(controls) { controls = it }
        ModelsAndEffects(ampModels, effects, { ampModels = it }, { effects = it })
        Field("Preset editor app, if any — e.g. BOSS Tone Studio", editorApp) { editorApp = it }
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes — effects loop, built-in FX") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PedalEditor(saved: SavedPedal?, coach: AiCoach, onDismiss: () -> Unit, onSave: (String, PedalProfile) -> Unit) {
    val start = saved?.profile ?: PedalProfile("", "")
    var name by remember { mutableStateOf(saved?.name ?: "") }
    var make by remember { mutableStateOf(start.make) }
    var model by remember { mutableStateOf(start.model) }
    var type by remember { mutableStateOf(start.type) }
    var controls by remember { mutableStateOf(start.controls) }
    var notes by remember { mutableStateOf(start.notes) }

    EditorSheet(if (saved == null) "New pedal" else "Edit pedal", onDismiss, saveEnabled = name.isNotBlank(), onSave = {
        onSave(name.trim(), PedalProfile(make.trim(), model.trim(), type.trim(), controls, notes.trim()))
    }) {
        Field("Name (what you call it)", name) { name = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Make", make, Modifier.weight(1f)) { make = it }
            Field("Model", model, Modifier.weight(1f)) { model = it }
        }
        Field("Type — overdrive, delay, multi-FX…", type) { type = it }
        SuggestButton(coach, GearSuggest.Kind.PEDAL, make, model) { s ->
            if (s.controls.isNotEmpty()) controls = s.controls.map { it.name }
            if (type.isBlank()) type = s.pedalType
            if (name.isBlank()) name = model.trim()
        }
        NameListEditor("CONTROLS", controls, "e.g. Drive") { controls = it }
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * "Suggest controls from model name". The answer is shown for review and, if
 * taken, only fills the editor — it is saved when Save is pressed, never
 * before.
 */
@Composable
private fun SuggestButton(coach: AiCoach, kind: GearSuggest.Kind, make: String, model: String, onUse: (GearSuggest.Suggestion) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<GearSuggest.Suggestion?>(null) }

    Column {
        OutlinedButton(
            // One request at a time: disabled while one is out.
            enabled = !busy && model.isNotBlank(),
            onClick = {
                busy = true; problem = null
                scope.launch {
                    val result = coach.ask(GearSuggest.request(kind, make, model), GearSuggest.cacheKey(kind, make, model))
                    busy = false
                    when (result) {
                        is AiResult.Success -> {
                            val s = result.json?.let(GearSuggest::parse)
                            if (s == null || s.isEmpty()) problem = "The coach had nothing reliable for that model."
                            else review = s
                        }
                        AiResult.NoKey -> problem = "Add an AI key in Me › Settings to use suggestions."
                        AiResult.Offline -> problem = "Offline — fill the list in by hand, or try again when connected."
                        is AiResult.RateLimited -> problem = "The AI is busy. Try again in a minute."
                        is AiResult.Malformed -> problem = "The answer could not be read. Try again."
                        is AiResult.Failed -> problem = result.message
                    }
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(if (busy) "Asking the coach…" else "Fill in with the coach") }
        if (model.isBlank()) Text(
            "Type the make and model, and the coach drafts the rest for you to check.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }

    review?.let { s ->
        AlertDialog(
            onDismissRequest = { review = null },
            title = { Text("Suggested for ${listOf(make, model).filter { it.isNotBlank() }.joinToString(" ")}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!s.confident) Text("The coach is not sure of this exact model. Check against the real thing.",
                        color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
                    if (s.note.isNotBlank()) Text(s.note, style = MaterialTheme.typography.bodySmall)
                    // Only what this kind of gear has: models answer the whole
                    // schema, and a pedal came back with "Channels: mono".
                    if (kind == GearSuggest.Kind.PEDAL && s.pedalType.isNotBlank()) Text("Type: ${s.pedalType}")
                    if (kind == GearSuggest.Kind.GUITAR && s.pickups.isNotEmpty()) {
                        SectionLabel("PICKUPS")
                        s.pickups.forEach { p -> Text("• ${p.label}" + if (p.description.isNotBlank()) " — ${p.description}" else "") }
                    }
                    if (kind == GearSuggest.Kind.AMP && s.channels.isNotEmpty()) Text("Channels: " + s.channels.joinToString(", "))
                    if (s.controls.isNotEmpty()) SectionLabel("CONTROLS")
                    s.controls.forEach { c ->
                        val flags = listOfNotNull(
                            "in the display".takeIf { c.reach == Reach.DISPLAY },
                            "loudness only".takeIf { c.role == ControlRole.LEVEL },
                        )
                        Text("• ${c.name} (${c.describe})" + if (flags.isNotEmpty()) " · ${flags.joinToString(" · ")}" else "")
                    }
                    if (s.ampModels.isNotEmpty()) {
                        SectionLabel("AMP MODELS · ${s.ampModels.size}")
                        s.ampModels.forEach { m -> Text("• ${m.name}" + if (m.description.isNotBlank()) " — ${m.description}" else "",
                            style = MaterialTheme.typography.bodySmall) }
                    }
                    if (s.effects.isNotEmpty()) {
                        SectionLabel("EFFECTS · ${s.effects.size}")
                        EffectCategory.entries.forEach { cat ->
                            val inCat = s.effects.filter { it.category == cat }
                            if (inCat.isNotEmpty()) Text("${cat.label}: ${inCat.joinToString(", ") { it.name }}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (s.editorApp.isNotBlank()) Text("Presets edited in: ${s.editorApp}")
                    Text("Nothing is saved until you press Save.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { onUse(s); review = null }) { Text("Use these") } },
            dismissButton = { TextButton(onClick = { review = null }) { Text("Discard") } },
        )
    }
}

/**
 * Gear transcribed from its maker's manual, offered as a one-tap start when
 * adding gear. Everything else is typed, or drafted by the coach.
 */
@Composable
private fun KnownPicks(names: List<String>, onPick: (Int) -> Unit) {
    if (names.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Start from a model checked against its manual, or type your own below:",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEachIndexed { i, n -> FilterChip(false, { onPick(i) }, { Text(n) }) }
        }
    }
}

/**
 * A modeling amp's amp models and effects, which a tone card chooses from.
 * Folded away behind a count: twenty models and twenty-six effects are a
 * lot of rows to scroll past on the way to Save.
 */
@Composable
private fun ModelsAndEffects(
    models: List<AmpModel>,
    effects: List<EffectModel>,
    onModels: (List<AmpModel>) -> Unit,
    onEffects: (List<EffectModel>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("AMP MODELS AND EFFECTS")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (models.isEmpty() && effects.isEmpty()) "None — fine for an amp without built-in models or effects."
                else "${models.size} amp models · ${effects.size} effects",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Edit") }
        }
        if (open) {
            val modelNotes = models.associate { it.name to it.description }
            NameListEditor("AMP MODELS", models.map { it.name }, "e.g. Plexi") { names ->
                onModels(names.map { AmpModel(it, modelNotes[it] ?: "") })
            }
            EffectCategory.entries.forEach { cat ->
                val inCat = effects.filter { it.category == cat }
                val notes = inCat.associate { it.name to it.description }
                NameListEditor(cat.label.uppercase(), inCat.map { it.name }, "Add a ${cat.label.lowercase()}") { names ->
                    onEffects(effects.filter { it.category != cat } + names.map { EffectModel(it, cat, notes[it] ?: "") })
                }
            }
        }
    }
}
