package com.riffbuddy.ui.metronome

import com.riffbuddy.ui.theme.tactileClickable
import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.NumberEntryDialog
import com.riffbuddy.ui.IntStepper
import com.riffbuddy.ui.HeroCard
import com.riffbuddy.ui.PageHeader
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.graphics.graphicsLayer
import com.riffbuddy.audio.TrainerMode
import com.riffbuddy.audio.AtTarget
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Edit
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riffbuddy.audio.Accent
import com.riffbuddy.audio.ClickSound
import com.riffbuddy.audio.MetronomeSettings
import com.riffbuddy.audio.MetronomeState
import com.riffbuddy.audio.Subdivision
import com.riffbuddy.audio.TapTempo
import com.riffbuddy.audio.TimeSignature
import com.riffbuddy.audio.TrainerConfig
import com.riffbuddy.data.MetronomePreset
import com.riffbuddy.domain.nowMillis
import com.riffbuddy.ui.KeyShortcuts
import com.riffbuddy.ui.Shortcut
import com.riffbuddy.ui.tactile.Button
import com.riffbuddy.ui.tactile.FilledTonalButton
import com.riffbuddy.ui.tactile.FilterChip
import com.riffbuddy.ui.tactile.IconButton
import com.riffbuddy.ui.tactile.OutlinedButton
import com.riffbuddy.ui.tactile.TextButton
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
    // Up and right both mean faster, the way a volume slider reads either way.
    fun nudge(by: Int) = onSettings(s.copy(bpm = (s.bpm + by).coerceIn(MetronomeSettings.BPM_RANGE)))
    KeyShortcuts(
        "Metronome",
        Shortcut("Space", "Start or stop", listOf(Key.Spacebar)) { if (state.isRunning) onStop() else onStart() },
        Shortcut("T", "Tap the tempo", listOf(Key.T)) {
            tapper.tap(nowMillis())?.let { onSettings(s.copy(bpm = it)) }
        },
        Shortcut("↑ →", "Faster by 1 BPM (Shift: 5)", listOf(Key.DirectionUp, Key.DirectionRight)) { nudge(if (shift) 5 else 1) },
        Shortcut("↓ ←", "Slower by 1 BPM (Shift: 5)", listOf(Key.DirectionDown, Key.DirectionLeft)) { nudge(if (shift) -5 else -1) },
    )

    var typingBpm by remember { mutableStateOf(false) }
    val range = MetronomeSettings.BPM_RANGE

    // Two columns once there is room for them: the tempo and its transport
    // stay in view on the left while the trainer and the setup controls sit
    // beside them, instead of one narrow column with the trainer below the
    // fold. Decided by the width this screen is given, not the window's.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= WIDE_LAYOUT
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Space.screen),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            PageHeader(title = "Metronome", subtitle = "Tempo, tap tempo and speed trainer")
            songHeader()

            val tempo: @Composable () -> Unit = {
                TempoHero(state, onSettings, ::nudge) { typingBpm = true }
                Transport(state, tapper, onStart, onStop) {
                    // A knob-click on every tap, and the reason tap
                    // tempo feels like an instrument rather than a form field.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    tapper.tap(nowMillis())?.let { onSettings(s.copy(bpm = it)) }
                }
                state.error?.let {
                    Text(
                        "Audio stopped: $it",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            val setup: @Composable () -> Unit = {
                TrainerCard(state, onStartTrainer, onStopTrainer, onNailedIt)
                SettingsCard(s, onSettings)
                AccentRow(s, onSettings)
                PresetCard(presets, onSavePreset, onDeletePreset) { onSettings(it) }
            }

            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.lg)) { tempo() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.lg)) { setup() }
                }
            } else {
                tempo()
                setup()
            }
        }
    }

    if (typingBpm) {
        NumberEntryDialog(
            title = "Tempo",
            initial = s.bpm.toDouble(),
            range = range.first.toDouble()..range.last.toDouble(),
            onValue = { onSettings(s.copy(bpm = it.toInt())) },
            onDismiss = { typingBpm = false },
            unit = "BPM",
        )
    }
}

/** Where the metronome splits into two columns: room for two panels of a phone's width. */
private val WIDE_LAYOUT = 840.dp

/** The tempo, as large as it can reasonably be, with the beat lamps above it. */
@Composable
private fun TempoHero(
    state: MetronomeState,
    onSettings: (MetronomeSettings) -> Unit,
    nudge: (Int) -> Unit,
    onType: () -> Unit,
) {
    val s = state.settings
    val range = MetronomeSettings.BPM_RANGE
    HeroCard(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.lg),
    ) {
        BeatLamps(state)
        Spacer(Modifier.height(Space.md))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.isCountIn) "COUNT-IN" else "TEMPO",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            // The numeral is the control: tap it and type, the quickest
            // way to an exact tempo off a chart.
            Box(
                Modifier
                    .clip(MaterialTheme.shapes.large)
                    .tactileClickable(onClickLabel = "Type a tempo", onClick = onType)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${state.displayBpm} beats per minute. Double-tap to type a tempo."
                    }
                    .padding(horizontal = Space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${state.displayBpm}",
                    style = BigNumeral.copy(fontSize = 96.sp, lineHeight = 100.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(Space.xs))
                Text(
                    "BPM · TAP TO TYPE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Space.xxs))
            Text(
                "${s.timeSignature.beatUnitSymbol} = ${state.displayBpm}  ·  ${s.timeSignature.beatUnitName}s per minute",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Space.sm))

        // --- coarse and fine tempo -------------------------------------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton(Icons.Filled.Remove, "Slower") { nudge(-1) }
            PlainSlider(
                value = s.bpm.toFloat(),
                onValueChange = { onSettings(s.copy(bpm = it.toInt().coerceIn(range))) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Tempo" },
            )
            StepButton(Icons.Filled.Add, "Faster") { nudge(1) }
        }
    }
}

/** Start or stop, and tap tempo: the two buttons a thumb reaches for. */
@Composable
private fun Transport(state: MetronomeState, tapper: TapTempo, onStart: () -> Unit, onStop: () -> Unit, onTap: () -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
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
                onClick = onTap,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Text(if (tapper.tapCount in 1..3) "Tap…" else "Tap tempo")
            }
        }
}

/**
 * A slider without Material's stop dot at the end of the track. The dot marks
 * nothing here (the ends are already the ends of the range), and on its own it
 * read as a stray mark or a setting nobody could explain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlainSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        track = { SliderDefaults.Track(sliderState = it, drawStopIndicator = null) },
    )
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
            // motionSpec snaps under reduced motion, so the lit bar still
            // jumps to full height on the beat but never tweens there.
            val size by animateFloatAsState(
                if (lit) 1f else 0.62f,
                animationSpec = motionSpec(90),
                label = "lamp",
            )
            // An idle bar is drawn in the muted text color: the finish's own
            // lampDark is under 2:1 against the hero card, and a bar the eye
            // can't find gives the beat nowhere to land. Muted text clears 3:1
            // on the hero in every finish, even under the corner glow.
            val idle = MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    // Scaled in its layer, not resized: the row never
                    // re-measures on a beat, and only one layer redraws.
                    .graphicsLayer { scaleY = size }
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            lit -> RiffColors.lampLit
                            accent == Accent.MUTED -> Color.Transparent
                            else -> idle
                        }
                    )
                    .then(
                        // A muted beat is an empty outline rather than a dimmer
                        // fill, so it differs from a played beat by shape too.
                        if (accent == Accent.MUTED && !lit)
                            Modifier.border(2.dp, idle, RoundedCornerShape(6.dp))
                        else Modifier
                    ),
            )
        }
    }
}

@Composable
private fun SettingsCard(s: MetronomeSettings, onSettings: (MetronomeSettings) -> Unit) {
    var customMeter by remember { mutableStateOf(false) }
    com.riffbuddy.ui.Panel(contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Label("TIME SIGNATURE")
            val isCommon = s.timeSignature in TimeSignature.COMMON
            com.riffbuddy.ui.ChipFlow {
                TimeSignature.COMMON.forEach { sig ->
                    FilterChip(
                        selected = sig == s.timeSignature,
                        onClick = { onSettings(s.copy(timeSignature = sig, accents = emptyList())) },
                        label = { Text(sig.toString()) },
                        modifier = Modifier.heightIn(min = 40.dp),
                    )
                }
                // Any meter the click can count, not only the common ones: a
                // 9/8 jig or an 11/8 riff is typed here.
                FilterChip(
                    selected = !isCommon,
                    onClick = { customMeter = true },
                    label = { Text(if (isCommon) "Custom…" else "${s.timeSignature} · Custom") },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
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
            IntStepper(
                label = "Bars before the count",
                value = s.countInBars,
                range = MetronomeSettings.COUNT_IN_RANGE,
                onValue = { onSettings(s.copy(countInBars = it)) },
                quickPicks = listOf(0, 1, 2, 4),
                format = { if (it == 0) "Off" else "$it bar${if (it > 1) "s" else ""}" },
            )
            Label("VOLUME")
            PlainSlider(
                value = s.volume,
                onValueChange = { onSettings(s.copy(volume = it)) },
                modifier = Modifier.semantics { contentDescription = "Click volume" },
            )
        }
    }

    if (customMeter) {
        CustomMeterDialog(
            initial = s.timeSignature,
            onPick = { onSettings(s.copy(timeSignature = it, accents = emptyList())) },
            onDismiss = { customMeter = false },
        )
    }
}

/** Beats over 4 or 8: every meter the click can count. */
@Composable
private fun CustomMeterDialog(initial: TimeSignature, onPick: (TimeSignature) -> Unit, onDismiss: () -> Unit) {
    var beats by remember { mutableStateOf(initial.beats) }
    var unit by remember { mutableStateOf(initial.unit) }
    val sig = TimeSignature.parse("$beats/$unit")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time signature") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                IntStepper(label = "Beats in a bar", value = beats, range = 1..16, onValue = { beats = it })
                Text("The note that gets the beat", style = MaterialTheme.typography.bodyMedium)
                com.riffbuddy.ui.ChipFlow {
                    listOf(4 to "Quarter (/4)", 8 to "Eighth (/8)").forEach { (u, name) ->
                        FilterChip(
                            selected = unit == u,
                            onClick = { unit = u },
                            label = { Text(name) },
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
                sig?.let {
                    val n = it.tempoBeatsPerBar
                    Text(
                        "$it clicks $n time${if (n > 1) "s" else ""} a bar, on the ${it.beatUnitName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { sig?.let(onPick); onDismiss() }, enabled = sig != null) { Text("Use $beats/$unit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
) {
    val trainer = state.trainer
    com.riffbuddy.ui.Panel(accent = trainer != null, contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Label("SPEED TRAINER")
            if (trainer == null) {
                TrainerSetup(state, onStartTrainer)
            } else {
                val c = trainer.config
                val sign = if (c.targetBpm >= c.startBpm) "+" else "−"
                Text(
                    "${c.startBpm} → ${c.targetBpm} BPM, $sign${c.step} " +
                        if (c.mode == TrainerMode.AUTO_RAMP) "every ${c.everyBars} bar${if (c.everyBars > 1) "s" else ""}"
                        else "each time you nail it",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { trainer.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                )
                Text(
                    when {
                        trainer.reachedTarget && c.atTarget == AtTarget.STOP -> "Target reached — stopping."
                        trainer.reachedTarget -> "Target reached — nice. Holding at ${c.targetBpm}."
                        c.mode == TrainerMode.AUTO_RAMP ->
                            "At ${trainer.currentBpm}, bar ${trainer.barsAtThisTempo + 1} of ${c.everyBars}"
                        else -> "At ${trainer.currentBpm}. Tap Nailed it when it's clean."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (trainer.reachedTarget) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
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
 * Setting up a ramp: where it starts and ends, how big each step is, and what
 * moves it on. It opens on the last ramp used, since most players come back to
 * the same exercise; a first ramp starts at the current tempo and aims 30 higher.
 */
@Composable
private fun TrainerSetup(state: MetronomeState, onStartTrainer: (TrainerConfig) -> Unit) {
    val range = MetronomeSettings.BPM_RANGE
    // Keyed on the last ramp only: a tempo nudge while the form is open must
    // not wipe what was typed into it.
    val seed = remember(state.lastTrainer) { state.lastTrainer ?: TrainerConfig(
        startBpm = state.settings.bpm,
        targetBpm = (state.settings.bpm + 30).coerceAtMost(range.last),
        step = 5,
        everyBars = 4,
    ) }
    var mode by remember(seed) { mutableStateOf(seed.mode) }
    var start by remember(seed) { mutableStateOf(seed.startBpm) }
    var target by remember(seed) { mutableStateOf(seed.targetBpm) }
    var step by remember(seed) { mutableStateOf(seed.step) }
    var every by remember(seed) { mutableStateOf(seed.everyBars) }
    var atTarget by remember(seed) { mutableStateOf(seed.atTarget) }

    Text(
        "Ramp the tempo up as you get it clean, or down to ease a passage in.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    com.riffbuddy.ui.ChipFlow {
        listOf(TrainerMode.AUTO_RAMP to "Step every few bars", TrainerMode.MANUAL_CONFIRM to "Step when I say").forEach { (m, name) ->
            FilterChip(
                selected = mode == m,
                onClick = { mode = m },
                label = { Text(name) },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
    }
    IntStepper(label = "Start", value = start, range = range, onValue = { start = it }, step = 5, unit = "BPM")
    if (start != state.settings.bpm) {
        TextButton(onClick = { start = state.settings.bpm }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Start from the current ${state.settings.bpm}")
        }
    }
    IntStepper(label = "Target", value = target, range = range, onValue = { target = it }, step = 5, unit = "BPM")
    IntStepper(
        label = "Step",
        value = step,
        range = TRAINER_STEP_RANGE,
        onValue = { step = it },
        unit = "BPM",
        quickPicks = listOf(2, 5, 10),
    )
    if (mode == TrainerMode.AUTO_RAMP) {
        IntStepper(
            label = "Bars at each tempo",
            value = every,
            range = TRAINER_BARS_RANGE,
            onValue = { every = it },
            quickPicks = listOf(2, 4, 8),
        )
    }
    Text("At the target", style = MaterialTheme.typography.bodyMedium)
    com.riffbuddy.ui.ChipFlow {
        listOf(AtTarget.HOLD to "Keep playing", AtTarget.STOP to "Stop").forEach { (t, name) ->
            FilterChip(
                selected = atTarget == t,
                onClick = { atTarget = t },
                label = { Text(name) },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
    }
    FilledTonalButton(
        onClick = {
            onStartTrainer(
                TrainerConfig(
                    mode = mode, startBpm = start, targetBpm = target,
                    step = step, everyBars = every, atTarget = atTarget,
                )
            )
        },
        enabled = start != target,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(if (start == target) "Pick a target away from the start" else "Start ramp: $start → $target")
    }
}

/** A step of more than 20 BPM is a jump, not a ramp; the trainer refuses it too. */
private val TRAINER_STEP_RANGE = 1..20

/** Up to 64 bars at a tempo: a whole chorus or two before stepping on. */
private val TRAINER_BARS_RANGE = 1..64

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
