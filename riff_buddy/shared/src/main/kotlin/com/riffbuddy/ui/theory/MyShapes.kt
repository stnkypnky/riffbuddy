package com.riffbuddy.ui.theory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.music.CustomShape
import com.riffbuddy.domain.music.CustomShapes
import com.riffbuddy.domain.music.Fretboard
import com.riffbuddy.domain.music.Notes
import com.riffbuddy.domain.music.Phrases
import com.riffbuddy.domain.music.Position
import com.riffbuddy.domain.music.Tuning
import com.riffbuddy.ui.ChipFlow
import com.riffbuddy.ui.KeyShortcuts
import com.riffbuddy.ui.Shortcut
import androidx.compose.ui.input.key.Key
import com.riffbuddy.ui.MenuChip
import com.riffbuddy.ui.Panel
import com.riffbuddy.ui.SectionHeader
import com.riffbuddy.ui.tactile.FilterChip
import com.riffbuddy.ui.tactile.IconButton
import com.riffbuddy.ui.tactile.TextButton
import com.riffbuddy.ui.theme.tactileClickable
import kotlinx.coroutines.launch

/**
 * What the player is building on the Custom page. Held by the theory screen,
 * so leaving for Scales and coming back keeps it, and so a change to it can
 * stop whatever was playing.
 */
internal class CustomState {
    /** Building a scale (notes everywhere on the neck) rather than placing dots. */
    var scaleMode by mutableStateOf(false)
    var dots by mutableStateOf(emptySet<Pair<Int, Int>>())
    /** The pitch class held to be home for the dots; null means the lowest dot's. */
    var dotsRoot by mutableStateOf<Int?>(null)
    /** The scale's notes as pitch classes. A minor pentatonic to start: something to hear straight away. */
    var classes by mutableStateOf(setOf(9, 0, 2, 4, 7))
    /** The position chosen for a built scale, an index into its list; 0 is the whole neck. */
    var position by mutableIntStateOf(0)
    /** The saved shape on screen, so saving again overwrites it rather than making a copy. */
    var editing by mutableStateOf<CustomShape?>(null)
}

/** The Custom page: dots placed anywhere, scales built note by note, and the ones saved. */
@Composable
internal fun MyShapes(
    state: CustomState,
    tuning: Tuning,
    tunings: List<Tuning>,
    onPickTuning: (Tuning) -> Unit,
    root: Int,
    onRoot: (Int) -> Unit,
    rootNames: List<String>,
    flats: Boolean,
    label: DotLabel,
    onLabel: (DotLabel) -> Unit,
    onFlats: () -> Unit,
    onPlayNote: (Int) -> Unit,
    sounding: Set<Pair<Int, Int>>,
    /** Whether Play is offered at all, and what is playing now, by key. */
    canPlay: Boolean,
    playingKey: String?,
    onToggle: (String, () -> com.riffbuddy.domain.music.Phrase) -> Unit,
    speed: Phrases.Speed,
    onSpeed: (Phrases.Speed) -> Unit,
    saved: List<CustomShape>,
    onSave: suspend (CustomShape) -> Long,
    onDelete: (CustomShape) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomShape?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val openNotes = tuning.midi.map { Notes.name(it, flats).dropLast(1) }
    fun noteName(pitchClass: Int) = rootNames[pitchClass.mod(12)]

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ChipFlow {
            FilterChip(!state.scaleMode, { state.scaleMode = false; note = null }, { Text("Place dots") }, Modifier.heightIn(min = 40.dp))
            FilterChip(state.scaleMode, { state.scaleMode = true; note = null }, { Text("Build a scale") }, Modifier.heightIn(min = 40.dp))
        }

        Panel {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.scaleMode) {
                    val classes = state.classes + root.mod(12)
                    // Boxes for five notes, three-per-string for seven: the
                    // shapes that exist for any scale of that size.
                    val shapes = Fretboard.withOctaveUp(
                        Fretboard.pentatonicBoxes(tuning, root, classes) + Fretboard.threeNotesPerString(tuning, classes),
                    )
                    val options: List<Pair<Position?, String>> = listOf<Pair<Position?, String>>(null to "Whole neck") +
                        shapes.map { it to "${it.name} · frets ${it.frets.first}–${it.frets.last}" }
                    val at = state.position.coerceIn(0, options.lastIndex)
                    val position = options[at].first

                    Text("Notes · tap to add or remove", style = MaterialTheme.typography.labelLarge)
                    ChipFlow {
                        // In order from the root, as a scale is spelled.
                        (0..11).map { (root + it).mod(12) }.forEach { pc ->
                            val isRoot = pc == root.mod(12)
                            val on = pc in classes
                            FilterChip(
                                selected = on,
                                onClick = {
                                    // The root is always in: it's what the scale is of.
                                    if (!isRoot) {
                                        state.classes = if (on) classes - pc else classes + pc
                                        state.position = 0
                                    }
                                },
                                label = { Text(if (isRoot) "${noteName(pc)} · root" else noteName(pc)) },
                                modifier = Modifier.heightIn(min = 40.dp).semantics {
                                    stateDescription = if (on) "In the scale" else "Not in the scale"
                                },
                            )
                        }
                    }
                    val same = CustomShapes.sameNotesAs(root, classes).take(2)
                    Text(
                        when {
                            same.isEmpty() -> "${classes.size} notes. No built-in scale has exactly these."
                            else -> "${classes.size} notes. Same notes as " +
                                same.joinToString(" and ") { (r, s) -> "${noteName(r)} ${s.label.lowercase()}" } + "."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FretboardDiagram(
                        positions = Fretboard.positions(tuning, root, classes),
                        stringCount = tuning.stringCount,
                        openNotes = openNotes,
                        label = label,
                        highlight = position?.frets,
                        cells = position?.cells,
                        preferFlats = flats,
                        onTapNote = { onPlayNote(it.midi) },
                        sounding = sounding,
                    )
                    if (options.size > 1) {
                        MenuChip(
                            text = options[at].second, options = options.indices.toList(), selected = at,
                            label = { options[it].second }, onPick = { state.position = it },
                            description = "Position on the neck", active = position != null,
                        )
                    }
                    ChipFlow {
                        if (canPlay) {
                            val run = position ?: Fretboard.oneOctave(tuning, root, classes)
                            val key = "custom:scale:$root:${classes.sorted()}:${tuning.name}:$at:${speed.name}"
                            KeyShortcuts(
                                "Theory: my scale",
                                Shortcut("Space", "Play or stop the scale", listOf(Key.Spacebar)) {
                                    onToggle(key) { Phrases.scaleRun(tuning, run.ascending(tuning), speed) }
                                },
                            )
                            PlayButton(playingKey == key, if (position == null) "Play one octave" else "Play ${run.name}") {
                                onToggle(key) { Phrases.scaleRun(tuning, run.ascending(tuning), speed) }
                            }
                            SpeedChip(speed, onSpeed)
                        }
                        TextButton(onClick = { naming = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (state.editing?.kind == CustomShape.Kind.SCALE) "Save" else "Save scale")
                        }
                    }
                } else {
                    val dotRoot = state.dotsRoot ?: CustomShapes.lowestRoot(tuning, state.dots) ?: root
                    val positions = CustomShapes.dotPositions(tuning, state.dots, dotRoot)
                    val described = positions.sortedWith(compareBy({ -it.string }, { it.fret })).joinToString(", ") {
                        // Strings counted as players count them: 1 is the thinnest.
                        "string ${tuning.stringCount - it.string} fret ${it.fret}"
                    }
                    Text(
                        if (state.dots.isEmpty()) "Tap the neck to place a dot; tap it again to take it off. Hold a dot to make it the root."
                        else "${state.dots.size} dots, root ${noteName(dotRoot)} · hold a dot to make it the root",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FretboardDiagram(
                        positions = positions,
                        stringCount = tuning.stringCount,
                        openNotes = openNotes,
                        label = label,
                        preferFlats = flats,
                        sounding = sounding,
                        onTapCell = { s, f ->
                            val cell = s to f
                            if (cell in state.dots) state.dots = state.dots - cell
                            else {
                                state.dots = state.dots + cell
                                // Placing a dot sounds it, so a shape is heard as it's built.
                                onPlayNote(tuning.midi[s] + f)
                            }
                        },
                        onHoldCell = { s, f ->
                            if ((s to f) in state.dots) state.dotsRoot = (tuning.midi[s] + f).mod(12)
                        },
                        description = if (state.dots.isEmpty()) "Neck with no dots placed" else "Your dots: $described",
                    )
                    ChipFlow {
                        if (canPlay && state.dots.isNotEmpty()) {
                            val cells = state.dots.filter { it.first < tuning.stringCount }.toSet()
                            val base = "custom:dots:${cells.sortedWith(compareBy({ it.first }, { it.second }))}:${tuning.name}:${speed.name}"
                            KeyShortcuts(
                                "Theory: my dots",
                                Shortcut("Space", "Play or stop the dots, low to high", listOf(Key.Spacebar)) {
                                    onToggle("$base:run") { Phrases.scaleRun(tuning, cells.toList(), speed) }
                                },
                                Shortcut("Enter", "Play or stop the dots together", listOf(Key.Enter, Key.NumPadEnter)) {
                                    onToggle("$base:together") { Phrases.together(tuning, cells, speed) }
                                },
                                Shortcut("Delete", "Clear the dots", listOf(Key.Delete, Key.Backspace)) {
                                    state.dots = emptySet(); state.dotsRoot = null
                                },
                            )
                            PlayButton(playingKey == "$base:run", "Play low to high") {
                                onToggle("$base:run") { Phrases.scaleRun(tuning, cells.toList(), speed) }
                            }
                            PlayButton(playingKey == "$base:together", "Play together") {
                                onToggle("$base:together") { Phrases.together(tuning, cells, speed) }
                            }
                            SpeedChip(speed, onSpeed)
                        }
                        if (state.dots.isNotEmpty()) {
                            TextButton(onClick = { naming = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (state.editing?.kind == CustomShape.Kind.DOTS) "Save" else "Save shape")
                            }
                            TextButton(onClick = { state.dots = emptySet(); state.dotsRoot = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Clear")
                            }
                        }
                    }
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                ChipFlow {
                    MenuChip(
                        text = tuning.name, options = tunings, selected = tuning,
                        label = { it.name }, onPick = { onPickTuning(it); state.position = 0 }, description = "Tuning",
                        active = tuning.name != Tuning.STANDARD.name,
                    )
                    MenuChip(
                        text = "Dots: ${label.label}", options = DotLabel.entries.toList(), selected = label,
                        label = { it.label }, onPick = onLabel, description = "What each dot shows",
                    )
                    FilterChip(flats, onFlats, { Text(if (flats) "Flats ♭" else "Sharps ♯") }, Modifier.heightIn(min = 40.dp))
                }
            }
        }

        if (state.editing != null) {
            TextButton(onClick = {
                state.editing = null
                if (state.scaleMode) state.classes = setOf(root.mod(12)) else { state.dots = emptySet(); state.dotsRoot = null }
                note = null
            }) { Text("Start a new one") }
        }

        SectionHeader("MY SHAPES")
        if (saved.isEmpty()) {
            Text(
                "Nothing saved yet. Place dots or build a scale, then Save to keep it here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        saved.forEach { shape ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).tactileClickable(onClickLabel = "Open ${shape.name}") {
                    state.editing = shape
                    note = null
                    when (shape.kind) {
                        CustomShape.Kind.SCALE -> {
                            state.scaleMode = true
                            onRoot(shape.root)
                            state.classes = shape.pitchClasses
                            state.position = 0
                        }
                        CustomShape.Kind.DOTS -> {
                            state.scaleMode = false
                            // Frets are only the same notes in the tuning they were placed in.
                            val placedIn = tunings.firstOrNull { it.name == shape.tuningName }
                            if (placedIn != null) onPickTuning(placedIn)
                            else if (shape.tuningName != null && shape.tuningName != tuning.name) {
                                note = "Placed in ${shape.tuningName}, which isn't on this device: shown in ${tuning.name}."
                            }
                            state.dots = shape.cells
                            state.dotsRoot = shape.root
                        }
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(shape.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (shape.kind) {
                            CustomShape.Kind.SCALE -> "Scale · " +
                                shape.pitchClasses.sortedBy { (it - shape.root).mod(12) }.joinToString(" ") { noteName(it) }
                            CustomShape.Kind.DOTS -> "Dots · ${shape.cells.size} notes · ${shape.tuningName ?: "any tuning"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { deleting = shape }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${shape.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (naming) {
        val editing = state.editing
        val kind = if (state.scaleMode) CustomShape.Kind.SCALE else CustomShape.Kind.DOTS
        // Editing a saved shape of this kind keeps its name and overwrites it.
        val same = editing?.takeIf { it.kind == kind }
        var name by remember {
            mutableStateOf(
                same?.name ?: if (state.scaleMode) "${noteName(root)} scale of my own" else "My shape"
            )
        }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(if (state.scaleMode) "Save scale" else "Save shape") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    naming = false
                    val shape = if (state.scaleMode) {
                        CustomShape(same?.id ?: 0, name, kind, root.mod(12), pitchClasses = state.classes + root.mod(12))
                    } else {
                        val dotRoot = state.dotsRoot ?: CustomShapes.lowestRoot(tuning, state.dots) ?: root
                        CustomShape(same?.id ?: 0, name, kind, dotRoot, cells = state.dots, tuningName = tuning.name)
                    }
                    scope.launch {
                        val id = onSave(shape)
                        state.editing = shape.copy(id = id)
                        note = "Saved as \"${shape.name.trim()}\"."
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }

    deleting?.let { shape ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${shape.name}?") },
            text = { Text("It goes from My shapes. What's on the neck now stays until you clear it.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    if (state.editing?.id == shape.id) state.editing = null
                    onDelete(shape)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
