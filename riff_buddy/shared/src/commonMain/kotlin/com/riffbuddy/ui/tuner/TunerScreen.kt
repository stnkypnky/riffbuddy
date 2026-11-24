package com.riffbuddy.ui.tuner

import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.PageHeader
import com.riffbuddy.ui.IntStepper
import com.riffbuddy.ui.HeroCard
import com.riffbuddy.domain.PracticeSettings
import com.riffbuddy.domain.NumberEntry
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import com.riffbuddy.domain.Device
import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.foundation.layout.widthIn

import androidx.compose.ui.semantics.role

import androidx.compose.ui.semantics.Role

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.foundation.gestures.detectTapGestures

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import com.riffbuddy.ui.tactile.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.riffbuddy.ui.tactile.OutlinedButton
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riffbuddy.audio.TunerState
import com.riffbuddy.domain.music.NoteReading
import com.riffbuddy.domain.music.Tuning
import com.riffbuddy.domain.music.TuningGroup
import com.riffbuddy.ui.theme.BigNumeral
import com.riffbuddy.ui.theme.RiffColors
import com.riffbuddy.ui.theme.motionSpec
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The tuner.
 *
 * Built for the one moment it is used: a guitar in both hands, the phone on a
 * stand, glanced at sideways. So the note is enormous, the needle is the only
 * moving thing, and every state is legible from a meter away.
 */
@Composable
fun TunerScreen(
    state: TunerState,
    tuning: Tuning,
    capo: Int,
    hasPermission: Boolean,
    guided: Boolean,
    onRequestPermission: () -> Unit,
    onPickTuning: (Tuning) -> Unit,
    onCapo: (Int) -> Unit,
    onToggleGuided: () -> Unit,
    onPlayString: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onSelectString: (Int) -> Unit,
    a4Hz: Double = PracticeSettings.DEFAULT_A4_HZ,
    /** Built-ins and custom tunings, as stored. */
    tunings: List<Tuning> = Tuning.BUILT_IN,
    /** Hold a string's button to sustain it; release to stop. */
    onDroneString: (Int) -> Unit = {},
    onStopDrone: () -> Unit = {},
    onNewTuning: () -> Unit = {},
    onEditTuning: (Tuning) -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        // The reference is said only when it is not the standard, where it
        // would surprise: tuned to 432 and playing along with a record,
        // everything is flat.
        PageHeader(
            title = "Tuner",
            subtitle = if (a4Hz != PracticeSettings.DEFAULT_A4_HZ) {
                "Reference A4 = ${NumberEntry.format(a4Hz, PracticeSettings.A4_DECIMALS)} Hz (change in Me › Settings)"
            } else "Play one string at a time and watch the needle",
        )

        if (!hasPermission) {
            PermissionCard(onRequestPermission, onPlayAll)
        } else {
            Needle(state)
        }

        StringLadder(
            tuning = tuning,
            state = state,
            guided = guided,
            onPlay = onPlayString,
            onSelect = onSelectString,
            onDrone = onDroneString,
            onStopDrone = onStopDrone,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPlayAll, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Play all")
            }
            if (hasPermission) {
                FilterChip(
                    selected = guided,
                    onClick = onToggleGuided,
                    label = { Text("Guided") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        TuningPicker(tuning, tunings, onPickTuning, onNewTuning, onEditTuning)
        CapoPicker(capo, onCapo)

        state.error?.let {
            Text(
                "Microphone problem: $it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Permission denied is not a dead end.
 *
 * The reference tones still work, and tuning by ear against a played note is how
 * this was done for a century. An app that shuts the door because one permission
 * was refused deserves to be uninstalled.
 */
@Composable
private fun PermissionCard(onRequest: () -> Unit, onPlayAll: () -> Unit) {
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Let Riff Buddy listen", style = MaterialTheme.typography.titleLarge)
            Text(
                "The tuner needs the microphone to hear your strings. Nothing is recorded or " +
                    "sent anywhere — the audio is analyzed on this ${Device.noun} and thrown away.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRequest, modifier = Modifier.heightIn(min = 52.dp)) {
                Text("Allow microphone")
            }
            Text(
                "Or tune by ear: tap a string below to hear its pitch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onPlayAll) { Text("Play all six") }
        }
    }
}

/**
 * The needle.
 *
 * Color is never the only signal: the word IN TUNE, the arrows and the
 * cents number all say the same thing, so it reads the same to someone who
 * cannot distinguish the green from the red.
 */
@Composable
private fun Needle(state: TunerState) {
    val reading = state.reading
    val cents = reading?.cents ?: 0.0
    val position by animateFloatAsState(
        (cents / 50.0).coerceIn(-1.0, 1.0).toFloat(),
        animationSpec = motionSpec(110),
        label = "needle",
    )

    HeroCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Space.xl)) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                reading?.name ?: "—",
                style = BigNumeral.copy(fontSize = 104.sp, lineHeight = 108.sp),
                color = when {
                    reading == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    reading.inTune -> RiffColors.inTune
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.semantics {
                    contentDescription = describe(reading, state.lowSignal)
                },
            )

            Text(
                when {
                    state.lowSignal -> "Too quiet — play a little louder"
                    reading == null -> "Listening…"
                    reading.inTune -> "IN TUNE"
                    reading.sharp -> "SHARP — loosen"
                    else -> "FLAT — tighten"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    reading?.inTune == true -> RiffColors.inTune
                    reading != null -> RiffColors.outOfTune
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.Bold,
            )

            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                // The track, with a center mark for in tune.
                Box(
                    Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Box(
                    Modifier.width(3.dp).height(22.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
                if (reading != null) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.5f + position * 0.5f)
                            .height(0.dp)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Spacer(Modifier.weight(1f + position))
                        Box(
                            Modifier.width(6.dp).height(44.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (reading.inTune) RiffColors.inTune else RiffColors.outOfTune)
                        )
                        Spacer(Modifier.weight(1f - position))
                    }
                }
            }

            Text(
                reading?.let { "${if (it.cents > 0) "+" else ""}${it.cents.roundToInt()} cents" } ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun describe(reading: NoteReading?, lowSignal: Boolean): String = when {
    lowSignal -> "Signal too quiet"
    reading == null -> "Listening for a note"
    reading.inTune -> "${reading.name}, in tune"
    reading.sharp -> "${reading.name}, ${abs(reading.cents).roundToInt()} cents sharp"
    else -> "${reading.name}, ${abs(reading.cents).roundToInt()} cents flat"
}

/** One row per string, low to high, tappable to hear it. */
@Composable
private fun StringLadder(
    tuning: Tuning,
    state: TunerState,
    guided: Boolean,
    onPlay: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    onDrone: (Int) -> Unit,
    onStopDrone: () -> Unit,
) {
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                tuning.name.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Tap Hear it for a note; hold it for a drone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tuning.noteNames().forEachIndexed { index, note ->
                val isTarget = guided && state.guidedIndex == index
                val isNearest = !guided && state.nearestString == index
                val settled = index in state.settled

                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "${tuning.stringCount - index}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(16.dp),
                    )
                    Text(
                        note,
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            isTarget || isNearest -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.width(52.dp),
                    )
                    // A tick, not just a color — the state has to survive being
                    // read by someone who cannot tell the green from the amber.
                    if (settled) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "in tune",
                            tint = RiffColors.inTune,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (guided) {
                        TextButton(onClick = { onSelect(index) }) {
                            Text(if (isTarget) "Tuning this" else "Go here")
                        }
                    }
                    HearIt(
                        onTap = { onPlay(index) },
                        onHold = { onDrone(index) },
                        onRelease = onStopDrone,
                    )
                }
            }
        }
    }
}

@Composable
private fun TuningPicker(
    current: Tuning,
    tunings: List<Tuning>,
    onPick: (Tuning) -> Unit,
    onNew: () -> Unit,
    onEdit: (Tuning) -> Unit,
) {
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.riffbuddy.ui.SectionHeader("TUNING", modifier = Modifier.weight(1f))
                TextButton(onClick = onNew) { Text("New tuning") }
            }
            val currentName = current.name.substringBefore(", capo")
            // One alphabetical list rather than a row of chips per group: with
            // twenty-seven tunings the rows ran off the side of the phone.
            com.riffbuddy.ui.Choices(
                options = tunings,
                selected = tunings.firstOrNull { it.name == currentName },
                label = { it.name },
                onPick = onPick,
                title = "Tuning",
            )
            tunings.firstOrNull { it.isCustom && it.name == currentName }?.let { mine ->
                TextButton(onClick = { onEdit(mine) }) { Text("Edit \"${mine.name}\"") }
            }
        }
    }
}

@Composable
private fun CapoPicker(capo: Int, onCapo: (Int) -> Unit) {
    com.riffbuddy.ui.Panel(contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            com.riffbuddy.ui.SectionHeader("CAPO")
            IntStepper(
                label = "Capo",
                value = capo,
                range = CAPO_FRETS,
                onValue = onCapo,
                format = { if (it == 0) "None" else "Fret $it" },
            )
            if (capo > 0) {
                Text(
                    "Targets shifted up $capo ${if (capo == 1) "fret" else "frets"} — tune with the capo on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

/** Past the 12th fret a capo leaves no neck to play on. */
private val CAPO_FRETS = 0..12

/**
 * "Hear it" that also drones: a tap plays the note for two seconds;
 * holding sustains it until the finger lifts. Drawn like a text button, but a
 * Button's onClick cannot tell a tap from a hold.
 */
@Composable
private fun HearIt(onTap: () -> Unit, onHold: () -> Unit, onRelease: () -> Unit) {
    // The gesture detector outlives recompositions, so it reads the callbacks
    // through these rather than keeping the first ones: otherwise, after a
    // tuning change, a held string would drone the old tuning's note.
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val release by rememberUpdatedState(onRelease)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 72.dp)
            .semantics { role = Role.Button }
            .pointerInput(Unit) {
                var droning = false
                detectTapGestures(
                    onTap = { tap() },
                    onLongPress = { droning = true; hold() },
                    onPress = {
                        tryAwaitRelease()
                        if (droning) { droning = false; release() }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("Hear it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

