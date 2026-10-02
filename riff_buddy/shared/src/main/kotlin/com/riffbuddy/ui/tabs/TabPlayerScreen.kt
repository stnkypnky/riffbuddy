package com.riffbuddy.ui.tabs

import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.ui.help.HelpButton
import com.riffbuddy.ui.help.HelpSheet
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import com.riffbuddy.ui.tactile.FilledIconButton
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.Icon
import com.riffbuddy.ui.tactile.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import com.riffbuddy.ui.tactile.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.riffbuddy.domain.SavedLoop
import com.riffbuddy.domain.TabFile
import com.riffbuddy.tabs.PlaybackMemory
import com.riffbuddy.tabs.TabPlayer
import com.riffbuddy.tabs.TabSource
import kotlin.math.roundToInt

/**
 * The tab player, full screen: the score on top, the controls in a
 * strip along the bottom where a thumb reaches them with a guitar on.
 *
 * Only the things used mid-practice live on the strip — play, stop, speed and
 * the loop. The mixer and the view options are one tap away in dialogs,
 * because they get set once per file and then left alone.
 */
@Composable
fun TabPlayerScreen(
    file: TabFile,
    bytes: ByteArray,
    onBack: () -> Unit,
    onLeave: (PlaybackMemory) -> Unit,
    savedLoops: List<SavedLoop> = emptyList(),
    onSaveLoop: (name: String, startBar: Int, endBar: Int, speed: Int) -> Unit = { _, _, _, _ -> },
    onDeleteLoop: (SavedLoop) -> Unit = {},
    /** Tab only, or notation above the tab — shared with the backing builder. */
    tabOnly: Boolean = true,
    onTabOnly: (Boolean) -> Unit = {},
) {
    val platform = com.riffbuddy.platform.LocalPlatform.current
    val player = remember(file.id) {
        // Set before the view exists, so the first render has the right staves.
        platform.tabPlayer(PlaybackMemory(file.positionMs, file.speedPercent, file.loopStartBar, file.loopEndBar, file.prefs))
            .also { it.showTabOnly(tabOnly) }
    }
    LaunchedEffect(tabOnly) { player.showTabOnly(tabOnly) }
    val source = remember(file.id) { TabSource(bytes, file.originalName) }
    var showingMixer by remember { mutableStateOf(false) }
    var showingView by remember { mutableStateOf(false) }
    var showingHelp by remember { mutableStateOf(false) }
    // This header is already the fullest in the app — Notes, Tracks,
    // Loops and View leave about 36 dp of title at 360 dp — so on a narrow
    // phone the "?" would push View off the edge. There it moves into the
    // View dialog instead.
    val roomForHelp = com.riffbuddy.ui.windowWidth() >= 420.dp
    var showingLoops by remember { mutableStateOf(false) }

    // Position, speed and loop are saved whenever the file is left, however
    // it is left — back, another tab, or the app going away.
    val leave by rememberUpdatedState(onLeave)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(file.id, lifecycle) {
        // ON_STOP as well as dispose: Android can kill a backgrounded app
        // without ever tearing its screens down, and then dispose never runs.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) leave(player.currentMemory())
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            leave(player.currentMemory())
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the tab library")
            }
            Text(
                file.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            NotesChip(tabOnly, onTabOnly)
            TextButton(onClick = { showingMixer = true }, enabled = player.tracks.isNotEmpty()) { Text("Tracks") }
            TextButton(onClick = { showingLoops = true }, enabled = player.ready) { Text("Loops") }
            TextButton(onClick = { showingView = true }) { Text("View") }
            if (roomForHelp) HelpButton(HelpTopic.TABS)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            platform.TabScoreView(player, source, Modifier.fillMaxSize())
            val status = when {
                player.error != null -> player.error
                player.loading -> "Loading…"
                player.pickingLoop == 1 -> "Tap the first bar of the loop"
                player.pickingLoop == 2 -> "Now tap the last bar"
                else -> null
            }
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (player.error != null) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .background(
                            if (player.error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        ControlStrip(player)
    }

    if (showingMixer) MixerDialog(player) { showingMixer = false }
    if (showingView) ViewDialog(
        player,
        onHelp = if (roomForHelp) null else { { showingView = false; showingHelp = true } },
    ) { showingView = false }
    if (showingHelp) HelpSheet(HelpTopic.TABS) { showingHelp = false }
    if (showingLoops) LoopsDialog(player, savedLoops, onSaveLoop, onDeleteLoop) { showingLoops = false }
}

/**
 * Saved loops for a tab file: the current loop saved under a name, and
 * a list to jump straight back to one — loop, speed and all.
 */
@Composable
private fun LoopsDialog(
    player: TabPlayer,
    loops: List<SavedLoop>,
    onSave: (String, Int, Int, Int) -> Unit,
    onDelete: (SavedLoop) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = player.loopStartBar
    val end = player.loopEndBar
    var name by remember { mutableStateOf("Loop ${loops.size + 1}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved loops") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (start != null && end != null) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text("Save bars $start–$end at ${player.speedPercent}% as") },
                    )
                    TextButton(onClick = { onSave(name, start, end, player.speedPercent); onDismiss() }) {
                        Text("Save this loop")
                    }
                } else {
                    Text(
                        "Set a loop with Loop bars, then come back here to save it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                loops.forEach { loop ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(loop.name, style = MaterialTheme.typography.bodyMedium)
                            Text("${loop.range} · ${loop.speedPercent}%", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            player.useLoop(loop.start.toInt(), loop.end.toInt(), loop.speedPercent)
                            onDismiss()
                        }) { Text("Go") }
                        TextButton(onClick = { onDelete(loop) }) { Text("Delete") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ControlStrip(player: TabPlayer) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledIconButton(
                onClick = player::playPause,
                enabled = player.ready,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (player.playing) "Pause" else "Play",
                )
            }
            OutlinedIconButton(onClick = player::stop, enabled = player.ready, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop and go back to the start")
            }
            Text(
                if (player.ready) "${clock(player.positionMs)} / ${clock(player.lengthMs)}" else "Preparing sound…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            LoopButton(player)
        }
        SpeedRow(player)
    }
}

@Composable
private fun LoopButton(player: TabPlayer) {
    val start = player.loopStartBar
    val end = player.loopEndBar
    when {
        player.pickingLoop != 0 -> TextButton(onClick = player::cancelPickingLoop) { Text("Cancel loop") }
        start != null && end != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Loop $start–$end",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = player::clearLoop) { Text("Clear") }
        }
        else -> TextButton(onClick = player::startPickingLoop, enabled = player.ready) { Text("Loop bars") }
    }
}

/**
 * Speed as three one-tap presets and a slider. The presets are the speeds
 * people actually practice at; the slider is for the 5 % nudges of the
 * speed-trainer habit, applied by hand.
 */
@Composable
private fun SpeedRow(player: TabPlayer) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${player.speedPercent}%",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(52.dp),
        )
        Slider(
            value = player.speedPercent.toFloat(),
            onValueChange = { player.setSpeed((it / 5).roundToInt() * 5) },
            valueRange = TabPlayer.SPEEDS.first.toFloat()..TabPlayer.SPEEDS.last.toFloat(),
            modifier = Modifier.weight(1f).semantics {
                contentDescription = "Playback speed"
                stateDescription = "${player.speedPercent} percent"
            },
        )
        com.riffbuddy.ui.ChipFlow {
            listOf(50, 75, 100).forEach { pct ->
                FilterChip(
                    selected = player.speedPercent == pct,
                    onClick = { player.setSpeed(pct) },
                    label = { Text("$pct") },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }
    }
}

/**
 * Track mute, solo and volume. Also which track is drawn, since a
 * six-track file shows only one staff system at a time on a phone.
 */
@Composable
private fun MixerDialog(player: TabPlayer, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tracks") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                player.tracks.forEach { track ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(track.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            FilterChip(
                                selected = player.shownTrack == track.index,
                                onClick = { player.showTrack(track.index) },
                                label = { Text(if (player.shownTrack == track.index) "Shown" else "Show") },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = track.muted,
                                onClick = { player.setMute(track.index, !track.muted) },
                                label = { Text("Mute") },
                            )
                            FilterChip(
                                selected = track.solo,
                                onClick = { player.setSolo(track.index, !track.solo) },
                                label = { Text("Solo") },
                            )
                            Slider(
                                value = track.volume,
                                onValueChange = { player.setVolume(track.index, it) },
                                modifier = Modifier.weight(1f).semantics {
                                    contentDescription = "${track.name} volume"
                                    stateDescription = "${(track.volume * 100).roundToInt()} percent"
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Zoom, page or horizontal layout, count-in and metronome. */
@Composable
private fun ViewDialog(player: TabPlayer, onHelp: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("View and practice") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var zoom by remember { mutableStateOf(player.zoom) }
                Text("Zoom ${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = zoom,
                    // Applied on release, not on every step: each zoom is a full
                    // re-layout, and on a slow tablet that is not free.
                    onValueChange = { zoom = (it * 10).roundToInt() / 10f },
                    onValueChangeFinished = { player.zoomTo(zoom) },
                    valueRange = TabPlayer.ZOOM_MIN..TabPlayer.ZOOM_MAX,
                    steps = ((TabPlayer.ZOOM_MAX - TabPlayer.ZOOM_MIN) * 10).roundToInt() - 1,
                    modifier = Modifier.semantics { contentDescription = "Zoom" },
                )
                SwitchRow("One long line (scrolls sideways)", player.horizontal, player::useHorizontal)
                SwitchRow("Count in before playing", player.countIn, player::useCountIn)
                SwitchRow("Metronome click", player.metronome, player::useMetronome)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { if (onHelp != null) TextButton(onClick = onHelp) { Text("Help") } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Standard notation on or off. Off by default — tab is what most guitarists read — and
 * one chip rather than a setting buried in View, since a file whose tracks
 * carry no tab (some MusicXML does not) shows blank until notation is on.
 */
@Composable
fun NotesChip(tabOnly: Boolean, onTabOnly: (Boolean) -> Unit) {
    FilterChip(
        selected = !tabOnly,
        onClick = { onTabOnly(!tabOnly) },
        label = { Text("Notes") },
        modifier = Modifier.heightIn(min = 40.dp).semantics {
            contentDescription = if (tabOnly) "Showing tab only. Show standard notation too" else "Showing notation and tab. Show tab only"
        },
    )
}

