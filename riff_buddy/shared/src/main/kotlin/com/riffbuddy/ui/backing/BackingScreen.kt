package com.riffbuddy.ui.backing

import com.riffbuddy.ui.Shortcut
import com.riffbuddy.ui.KeyShortcuts
import androidx.compose.ui.input.key.Key
import com.riffbuddy.ui.tabs.NotesChip

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import com.riffbuddy.ui.tactile.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.FilledIconButton
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.riffbuddy.ui.tactile.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffbuddy.ui.ConfirmDelete
import com.riffbuddy.data.SavedBacking
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.backing.BackingGenerator
import com.riffbuddy.domain.backing.BackingScore
import com.riffbuddy.domain.backing.BackingSpec
import com.riffbuddy.domain.backing.BackingStyle
import com.riffbuddy.domain.backing.Chart
import com.riffbuddy.domain.music.Fretboard
import com.riffbuddy.domain.music.Notes
import com.riffbuddy.domain.music.Tuning
import com.riffbuddy.tabs.PlaybackMemory
import com.riffbuddy.tabs.TabPlayer
import com.riffbuddy.tabs.TabSource
import com.riffbuddy.ui.theory.DotLabel
import com.riffbuddy.ui.theory.FretboardDiagram
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ROOTS = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

/** The three tracks the generator writes, in order. */
private val PARTS = listOf("Drums", "Bass", "Rhythm")

/**
 * The backing-track builder and player.
 *
 * Changes regenerate live: every edit to key, chart, style or tempo produces a
 * new alphaTex score a moment later, which the same alphaTab view renders and
 * plays — so looping, speed, count-in and per-part mute are the tab player's,
 * and survive the regeneration because they are re-applied on every load.
 */
@Composable
fun BackingScreen(
    header: @Composable () -> Unit,
    initial: BackingSpec,
    savedId: Long?,
    saved: List<SavedBacking>,
    songs: List<Song>,
    tuning: Tuning,
    onSave: (spec: BackingSpec, songId: Long?, id: Long?) -> Unit,
    onOpenSaved: (SavedBacking) -> Unit,
    onDelete: (SavedBacking) -> Unit,
    tabOnly: Boolean = true,
    onTabOnly: (Boolean) -> Unit = {},
) {
    var spec by remember(initial) { mutableStateOf(initial) }
    var chartText by remember(initial) { mutableStateOf(initial.chart) }
    var chartError by remember { mutableStateOf<String?>(null) }
    var score by remember { mutableStateOf<BackingScore?>(null) }
    var source by remember { mutableStateOf<TabSource?>(null) }
    val platform = com.riffbuddy.platform.LocalPlatform.current
    val player = remember { platform.tabPlayer(PlaybackMemory()).also { it.showTabOnly(tabOnly) } }
    LaunchedEffect(tabOnly) { player.showTabOnly(tabOnly) }
    val muted = remember { mutableStateListOf<Int>() }
    var looping by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedBacking?>(null) }

    // Changing tempo or style regenerates within a second. The short pause
    // is only so a run of taps on the tempo button makes one score, not ten.
    LaunchedEffect(spec) {
        delay(100)
        BackingGenerator.generate(spec).onSuccess {
            score = it
            source = TabSource(it.tex.toByteArray(), "backing.alphatex", generated = true)
            chartError = null
        }.onFailure { chartError = it.message }
    }

    // Loop, mutes and count-in belong to the screen, not the score, so each
    // freshly generated score is given them again.
    DisposableEffect(player) {
        player.onReady = {
            player.loopWhole(looping)
            muted.forEach { player.setMute(it, true) }
        }
        onDispose { player.onReady = null }
    }

    val ready = player.ready
    KeyShortcuts(
        "Backing track",
        if (!ready) null else Shortcut("Space", "Play or pause", listOf(Key.Spacebar)) { player.playPause() },
        if (!ready) null else Shortcut("Home", "Stop, back to the start", listOf(Key.MoveHome)) { player.stop() },
    )

    Column(Modifier.fillMaxSize()) {
        header()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- the score and its transport --------------------------------
            Box(Modifier.fillMaxWidth().height(200.dp)) {
                source?.let { platform.TabScoreView(player, it, Modifier.fillMaxSize()) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledIconButton(onClick = player::playPause, enabled = player.ready, modifier = Modifier.size(56.dp)) {
                    Icon(if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (player.playing) "Pause" else "Play")
                }
                OutlinedIconButton(onClick = player::stop, enabled = player.ready, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
                Column {
                    SwitchLine("Loop", looping) { looping = it; player.loopWhole(it) }
                }
                NotesChip(tabOnly, onTabOnly)
                Text(
                    if (player.ready) "${clock(player.positionMs)} / ${clock(player.lengthMs)}" else "Preparing…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            com.riffbuddy.ui.ChipFlow {
                PARTS.forEachIndexed { i, part ->
                    val on = i !in muted
                    FilterChip(
                        selected = on,
                        onClick = {
                            if (on) muted.add(i) else muted.remove(i)
                            player.setMute(i, on)
                        },
                        label = { Text(if (on) part else "$part (muted)") },
                    )
                }
                FilterChip(selected = player.countIn, onClick = { player.useCountIn(!player.countIn) }, label = { Text("Count-in") })
                listOf(50, 75, 100).forEach { pct ->
                    FilterChip(selected = player.speedPercent == pct, onClick = { player.setSpeed(pct) }, label = { Text("$pct%") })
                }
            }

            // ---- what to play -----------------------------------------------
            score?.let { s -> WhatToPlay(spec, s, player, tuning) }

            // ---- the recipe -------------------------------------------------
            com.riffbuddy.ui.Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.riffbuddy.ui.SectionHeader("BUILD")
                    com.riffbuddy.ui.ChipFlow {
                        ROOTS.forEachIndexed { pc, name ->
                            FilterChip(selected = spec.keyTonic == pc, onClick = { spec = spec.copy(keyTonic = pc) }, label = { Text(name) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!spec.minor, { spec = spec.copy(minor = false) }, { Text("Major") })
                        FilterChip(spec.minor, { spec = spec.copy(minor = true) }, { Text("Minor") })
                    }
                    Text("Progression", style = MaterialTheme.typography.labelLarge)
                    com.riffbuddy.ui.Choices(
                        options = Chart.PRESETS,
                        selected = Chart.PRESETS.firstOrNull { it.chart == spec.chart },
                        label = { it.name },
                        onPick = { p ->
                            chartText = p.chart
                            // A blues preset brings the shuffle with it; anything
                            // else keeps the style already chosen.
                            val style = if (p.name.contains("blues", true)) BackingStyle.SHUFFLE else spec.style
                            spec = spec.copy(chart = p.chart, minor = p.minor, barsPerChord = p.barsPerChord, style = style,
                                tempo = if (style != spec.style) style.defaultTempo else spec.tempo)
                        },
                        title = "Preset",
                    )
                    OutlinedTextField(
                        value = chartText,
                        onValueChange = { chartText = it; spec = spec.copy(chart = it) },
                        label = { Text("Chords — numerals (I IV V) or names (A7 D7 E7)") },
                        isError = chartError != null,
                        supportingText = chartError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Stepper("Bars per chord", "${spec.barsPerChord}",
                        { spec = spec.copy(barsPerChord = (spec.barsPerChord - 1).coerceAtLeast(1)) },
                        { spec = spec.copy(barsPerChord = (spec.barsPerChord + 1).coerceAtMost(8)) })
                    Text("Style", style = MaterialTheme.typography.labelLarge)
                    com.riffbuddy.ui.ChipFlow {
                        BackingStyle.entries.forEach { st ->
                            FilterChip(selected = spec.style == st, onClick = { spec = spec.copy(style = st) }, label = { Text(st.label) })
                        }
                    }
                    Stepper("Tempo", "${spec.tempo} BPM",
                        { spec = spec.copy(tempo = (spec.tempo - 5).coerceAtLeast(30)) },
                        { spec = spec.copy(tempo = (spec.tempo + 5).coerceAtMost(300)) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(spec.beatsPerBar == 4, { spec = spec.copy(beatsPerBar = 4) }, { Text("4/4") })
                        FilterChip(spec.beatsPerBar == 3, { spec = spec.copy(beatsPerBar = 3) }, { Text("3/4") })
                    }
                    if (spec.style != BackingStyle.SHUFFLE && spec.style != BackingStyle.FUNK) {
                        SwitchLine("Swing the eighths", spec.swing) { spec = spec.copy(swing = it) }
                    }
                    Button(onClick = { saving = true }, enabled = score != null) {
                        Text(if (savedId == null) "Save this track" else "Save changes")
                    }
                }
            }

            if (saved.isNotEmpty()) {
                com.riffbuddy.ui.SectionHeader("SAVED TRACKS")
                deleting?.let { track ->
                    ConfirmDelete(
                        "\"${track.name}\"",
                        "The chart and settings go. Nothing else uses them.",
                        onConfirm = { onDelete(track) },
                        onDismiss = { deleting = null },
                    )
                }
                saved.forEach { track ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(track.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(
                                    Notes.name(track.spec.keyTonic + 60).dropLast(1) + if (track.spec.minor) " minor" else "",
                                    track.spec.style.label,
                                    "${track.spec.tempo} BPM",
                                    songs.firstOrNull { it.id == track.songId }?.title,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onOpenSaved(track) }) { Text("Open") }
                        TextButton(onClick = { deleting = track }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (saving) {
        SaveDialog(
            spec = spec,
            songs = songs,
            currentSong = saved.firstOrNull { it.id == savedId }?.songId,
            onDismiss = { saving = false },
            onSave = { name, songId ->
                saving = false
                spec = spec.copy(name = name)
                onSave(spec.copy(name = name), songId, savedId)
            },
        )
    }
}

/**
 * "What to play": the scale for the track on the neck, with the
 * playing chord's tones brought forward. Follows the chord from the player's
 * own tick count — the same clock the notes are played from — so it changes
 * on the bar line, not a beat after it.
 */
@Composable
private fun WhatToPlay(spec: BackingSpec, score: BackingScore, player: TabPlayer, tuning: Tuning) {
    val ticksPerBar = 960L * score.beatsPerBar
    val bar = (player.tick / ticksPerBar).toInt()
    val chord = score.chordAt(bar)
    val scale = BackingGenerator.suggestedScale(spec)
    val keyName = Notes.name(spec.keyTonic + 60).dropLast(1)
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.riffbuddy.ui.SectionHeader("WHAT TO PLAY")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chord?.name() ?: "—", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.width(96.dp)
                    .semantics { contentDescription = "Chord now: ${chord?.name() ?: "none"}" })
                Column {
                    Text("$keyName ${scale.label.lowercase()} over the whole track", style = MaterialTheme.typography.bodyMedium)
                    chord?.let { c ->
                        Text(
                            "Land on ${c.quality.pitchClasses(c.root).sortedBy { (it - c.root).mod(12) }.joinToString(" ") { Notes.name(it + 60).dropLast(1) }} — the big dots",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            FretboardDiagram(
                positions = Fretboard.positions(tuning, spec.keyTonic, scale),
                stringCount = tuning.stringCount,
                openNotes = tuning.noteNames().map { it.dropLast(1) },
                label = DotLabel.FRET,
                emphasis = chord?.let { it.quality.pitchClasses(it.root) },
            )
        }
    }
}

@Composable
private fun SaveDialog(
    spec: BackingSpec,
    songs: List<Song>,
    currentSong: Long?,
    onDismiss: () -> Unit,
    onSave: (String, Long?) -> Unit,
) {
    var name by remember { mutableStateOf(spec.name.ifBlank { "${Notes.name(spec.keyTonic + 60).dropLast(1)} ${spec.style.label.lowercase()}" }) }
    var songId by remember { mutableStateOf(currentSong) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save backing track") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                Text("For a song (it then shows on the song's page)", style = MaterialTheme.typography.labelLarge)
                (listOf<Pair<Long?, String>>(null to "No song") + songs.sortedBy { it.title.lowercase() }.map { it.id to it.title })
                    .forEach { (id, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = songId == id, role = Role.RadioButton) { songId = id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = songId == id, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, songId) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Stepper(label: String, value: String, onDown: () -> Unit, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDown, modifier = Modifier.semantics { contentDescription = "$label lower" }) { Text("−") }
        Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(84.dp))
        TextButton(onClick = onUp, modifier = Modifier.semantics { contentDescription = "$label higher" }) { Text("+") }
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
