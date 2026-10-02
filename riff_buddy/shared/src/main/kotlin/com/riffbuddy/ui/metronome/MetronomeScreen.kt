package com.riffbuddy.ui.metronome

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import com.riffbuddy.ui.tactile.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.FilledTonalButton
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.Icon
import com.riffbuddy.ui.tactile.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.riffbuddy.ui.tactile.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riffbuddy.audio.Accent
import com.riffbuddy.audio.ClickSound
import com.riffbuddy.audio.MetronomeState
import com.riffbuddy.audio.MetronomeSettings
import com.riffbuddy.audio.Subdivision
import com.riffbuddy.audio.TapTempo
import com.riffbuddy.audio.TimeSignature
import com.riffbuddy.audio.TrainerConfig
import com.riffbuddy.data.MetronomePreset
import com.riffbuddy.ui.theme.BigNumeral
import com.riffbuddy.ui.theme.RiffColors
import com.riffbuddy.ui.theme.motionSpec

/**
 * The metronome and speed trainer.
 *
 * Laid out for a phone on a music stand: the tempo is the biggest
 * thing on screen, transport and tap sit in the lower half where a thumb
 * reaches, and every target is at least 48 dp.
 */
@Composable
fun MetronomeScreen(
    state: MetronomeState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettings: (MetronomeSettings) -> Unit,
    onStartTrainer: (TrainerConfig) -> Unit,
    onStopTrainer: () -> Unit,
    onNailedIt: () -> Unit,
    presets: List<MetronomePreset> = emptyList(),
    onSavePreset: (String) -> Unit = {},
    onDeletePreset: (MetronomePreset) -> Unit = {},
    /** Which song this tempo is for, when it was opened from one. */
    songHeader: @Composable () -> Unit = {},
) {
    val s = state.settings
    val haptics = LocalHapticFeedback.current
    val tapper = remember { TapTempo() }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        songHeader()
        BeatLamps(state)

        // --- the tempo, as large as it can reasonably be -------------------
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                "${state.displayBpm}",
                style = BigNumeral,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics {
                    contentDescription = "${state.displayBpm} beats per minute"
                },
            )
            Text(
                if (state.isCountIn) "COUNT-IN"
                else "${s.timeSignature.beatUnitSymbol} = ${state.displayBpm}  ·  ${s.timeSignature.beatUnitName.uppercase()}S PER MINUTE",
                style = MaterialTheme.typography.labelMedium,
                color = if (state.isCountIn) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- coarse and fine tempo -----------------------------------------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton(Icons.Filled.Remove, "Slower") { onSettings(s.copy(bpm = (s.bpm - 1).coerceAtLeast(20))) }
            Slider(
                value = s.bpm.toFloat(),
                onValueChange = { onSettings(s.copy(bpm = it.toInt())) },
                valueRange = 20f..300f,
                modifier = Modifier.weight(1f),
            )
            StepButton(Icons.Filled.Add, "Faster") { onSettings(s.copy(bpm = (s.bpm + 1).coerceAtMost(300))) }
        }

        // --- transport and tap ----------------------------------------------
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { if (state.isRunning) onStop() else onStart() },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Icon(
                    if (state.isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (state.isRunning) "Stop" else "Start")
            }
            OutlinedButton(
                onClick = {
                    // A knob-click on every tap, and the reason tap
                    // tempo feels like an instrument rather than a form field.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    tapper.tap(System.currentTimeMillis())?.let { onSettings(s.copy(bpm = it)) }
                },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Text(if (tapper.tapCount in 1..3) "Tap…" else "Tap tempo")
            }
        }

        PresetCard(presets, onSavePreset, onDeletePreset) { onSettings(it) }
        SettingsCard(s, onSettings)
        AccentRow(s, onSettings)
        TrainerCard(state, onStartTrainer, onStopTrainer, onNailedIt)

        state.error?.let {
            Text(
                "Audio stopped: $it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * One lamp per beat of the bar.
 *
 * Color is not the only signal: the lit lamp is also larger, and the
 * whole row is labeled for a screen reader with the beat being played.
 */
@Composable
private fun BeatLamps(state: MetronomeState) {
    val beats = state.settings.beatsPerBar
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Beat ${state.beatInBar + 1} of $beats" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (beat in 0 until beats) {
            val lit = state.isRunning && beat == state.beatInBar
            val accent = state.settings.accentFor(beat)
            val size by animateFloatAsState(
                if (lit) 1f else 0.62f,
                animationSpec = motionSpec(90),
                label = "lamp",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((34 * size).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                lit -> RiffColors.lampLit
                                accent == Accent.MUTED -> Color.Transparent
                                else -> RiffColors.lampDark
                            }
                        )
                        .then(
                            if (accent == Accent.MUTED)
                                Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                            else Modifier
                        ),
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(s: MetronomeSettings, onSettings: (MetronomeSettings) -> Unit) {
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label("TIME SIGNATURE")
            ChipRow(TimeSignature.COMMON.map { it.toString() }, s.timeSignature.toString()) { picked ->
                onSettings(s.copy(timeSignature = TimeSignature.COMMON.first { it.toString() == picked }, accents = emptyList()))
            }
            Label("SUBDIVISION")
            ChipRow(
                Subdivision.entries.map { it.labelIn(s.timeSignature) },
                s.subdivision.labelIn(s.timeSignature),
            ) { picked ->
                onSettings(
                    s.copy(subdivision = Subdivision.entries.first { it.labelIn(s.timeSignature) == picked })
                )
            }
            Label("SOUND")
            ChipRow(ClickSound.entries.map { it.label }, s.sound.label) { picked ->
                onSettings(s.copy(sound = ClickSound.entries.first { it.label == picked }))
            }
            Label("COUNT-IN")
            ChipRow((0..4).map { if (it == 0) "Off" else "$it bar${if (it > 1) "s" else ""}" },
                if (s.countInBars == 0) "Off" else "${s.countInBars} bar${if (s.countInBars > 1) "s" else ""}") { picked ->
                onSettings(s.copy(countInBars = if (picked == "Off") 0 else picked.first().digitToInt()))
            }
            Label("VOLUME")
            Slider(value = s.volume, onValueChange = { onSettings(s.copy(volume = it)) })
        }
    }
}

/** Tap a beat to cycle accent → normal → muted: the per-beat accent editor. */
@Composable
private fun AccentRow(s: MetronomeSettings, onSettings: (MetronomeSettings) -> Unit) {
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("ACCENTS — tap a beat to change it")
            // Narrow gaps: 7/8 puts seven buttons here, and every dp of gap
            // comes out of a button that should be close to 48 dp wide.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (beat in 0 until s.beatsPerBar) {
                    val accent = s.accentFor(beat)
                    OutlinedButton(
                        onClick = {
                            val next = when (accent) {
                                Accent.ACCENT -> Accent.NORMAL
                                Accent.NORMAL -> Accent.MUTED
                                Accent.MUTED -> Accent.ACCENT
                            }
                            val list = (0 until s.beatsPerBar).map {
                                if (it == beat) next else s.accentFor(it)
                            }
                            onSettings(s.copy(accents = list))
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        // The letter carries the state, not the color.
                        Text(
                            when (accent) {
                                Accent.ACCENT -> ">"
                                Accent.NORMAL -> "•"
                                Accent.MUTED -> "–"
                            },
                            color = if (accent == Accent.ACCENT) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrainerCard(
    state: MetronomeState,
    onStartTrainer: (TrainerConfig) -> Unit,
    onStopTrainer: () -> Unit,
    onNailedIt: () -> Unit,
    presets: List<MetronomePreset> = emptyList(),
    onSavePreset: (String) -> Unit = {},
    onDeletePreset: (MetronomePreset) -> Unit = {},
) {
    val trainer = state.trainer
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("SPEED TRAINER")
            if (trainer == null) {
                Text(
                    "Ramp the tempo up as you get it clean.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = {
                        onStartTrainer(
                            TrainerConfig(
                                startBpm = state.settings.bpm,
                                targetBpm = (state.settings.bpm + 30).coerceAtMost(300),
                                step = 5,
                                everyBars = 4,
                            )
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Ramp +30 over 4-bar steps") }
            } else {
                val c = trainer.config
                Text(
                    "${c.startBpm} → ${c.targetBpm} BPM, +${c.step} every ${c.everyBars} bars",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { trainer.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                )
                Text(
                    if (trainer.reachedTarget) "Target reached — nice."
                    else "At ${trainer.currentBpm}, bar ${trainer.barsAtThisTempo + 1} of ${c.everyBars}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (trainer.reachedTarget) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = onNailedIt,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("Nailed it") }
                    OutlinedButton(
                        onClick = onStopTrainer,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("Stop ramp") }
                }
            }
        }
    }
}

/**
 * Saved setups: tempo, time signature, accents, subdivision and sound.
 *
 * Tap to load, long-press to delete — a delete button next to every preset
 * would crowd a row that is meant to be tapped one-handed with a guitar on.
 */
@Composable
private fun PresetCard(
    presets: List<MetronomePreset>,
    onSave: (String) -> Unit,
    onDelete: (MetronomePreset) -> Unit,
    onLoad: (MetronomeSettings) -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("PRESETS")
            if (presets.isEmpty()) {
                Text(
                    "Save a setup you come back to — a song's tempo and feel, or a warm-up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                com.riffbuddy.ui.ChipFlow {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = false,
                            onClick = { onLoad(preset.settings) },
                            label = { Text("${preset.name} · ${preset.settings.bpm}") },
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .combinedClickable(
                                    onClick = { onLoad(preset.settings) },
                                    onLongClick = { onDelete(preset) },
                                ),
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { naming = true; name = "" },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save current") }
        }
    }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name this preset") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Warm-up, Verse, Solo…") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { if (name.isNotBlank()) onSave(name.trim()); naming = false },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Label(text: String) = com.riffbuddy.ui.SectionHeader(text)

@Composable
private fun ChipRow(options: List<String>, selected: String, onPick: (String) -> Unit) {
    // Wraps rather than scrolls: on a phone at a larger font size the
    // time signatures stopped at 6/8, and nothing said 7/8 and 12/8 were
    // one swipe away.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onPick(option) },
                label = { Text(option) },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp).clip(CircleShape)) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
    }
}
