package com.riffbuddy.ui.recorder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riffbuddy.audio.Pcm
import com.riffbuddy.audio.TakePlayer
import com.riffbuddy.audio.Wav
import com.riffbuddy.audio.Waveform
import com.riffbuddy.data.RecordingRepo
import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.domain.Recording
import com.riffbuddy.domain.pad
import com.riffbuddy.domain.toFixed
import com.riffbuddy.platform.Blocking
import com.riffbuddy.ui.KeyShortcuts
import com.riffbuddy.ui.Shortcut
import com.riffbuddy.ui.help.HelpButton
import com.riffbuddy.ui.tactile.Button
import com.riffbuddy.ui.tactile.FilledIconButton
import com.riffbuddy.ui.tactile.IconButton
import com.riffbuddy.ui.tactile.OutlinedButton
import com.riffbuddy.ui.tactile.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One take, or two side by side for A/B compare.
 *
 * Each take starts where its music starts: a play-along take is lined up on
 * where its source began, corrected by the latency calibrated when it was made
 * (in the takes player and in A/B compare alike), so two
 * takes of the same loop switch at the same beat rather than at the same
 * second of recording.
 */
@Composable
fun TakesPlayerScreen(
    takes: List<Recording>,
    repo: RecordingRepo,
    player: TakePlayer,
    onBack: () -> Unit,
    onClipSaved: () -> Unit = {},
) {
    val state by player.state.collectAsStateWithLifecycle()
    var peaks by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    // Trimming (single take only): a selection on the player's timeline, in ms.
    var trimStart by remember { mutableStateOf<Long?>(null) }
    var trimEnd by remember { mutableStateOf<Long?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var trimNote by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selection: LongRange? = run {
        val a = trimStart; val b = trimEnd
        if (a == null || b == null || a == b) null else minOf(a, b)..maxOf(a, b)
    }
    // "Play selection" stops at the end of it, as a trim preview should.
    LaunchedEffect(state.positionMs, state.playing) {
        if (!previewing) return@LaunchedEffect
        if (!state.playing) previewing = false
        else if (selection != null && state.positionMs >= selection.last) { player.stop(); previewing = false }
    }

    LaunchedEffect(takes.map { it.id }) {
        val loaded = withContext(Dispatchers.Blocking) {
            runCatching { takes.map { Wav.read(repo.fileFor(it)) } }
        }
        loaded.onSuccess { pcms ->
            val starts = takes.mapIndexed { i, t -> (t.startMs * pcms[i].sampleRate / 1000).toInt() }
            runCatching { player.load(pcms, starts) }
                .onSuccess { peaks = pcms.mapIndexed { i, p -> trimmedPeaks(p, starts[i]) } }
                .onFailure { error = it.message }
        }.onFailure { error = "That take could not be read: ${it.message}" }
    }
    DisposableEffect(Unit) { onDispose { player.unload() } }

    // Comparing is switching back and forth while listening, which is
    // quicker by key than by moving the pointer between two buttons.
    val loaded = state.loaded
    val comparing = loaded && takes.size == 2
    KeyShortcuts(
        if (takes.size == 2) "Compare A/B" else "Take",
        if (!loaded) null else Shortcut("Space", "Play or pause", listOf(Key.Spacebar)) { player.togglePlay() },
        if (!loaded) null else Shortcut("← →", "Back or ahead 1 second (Shift: 5)", listOf(Key.DirectionLeft, Key.DirectionRight)) {
            val step = if (shift) 5000L else 1000L
            player.nudge(if (key == Key.DirectionLeft) -step else step)
        },
        if (!loaded) null else Shortcut("Home", "Back to the start", listOf(Key.MoveHome)) { player.seekMs(0) },
        if (!comparing) null else Shortcut("A B", "Listen to take A or take B", listOf(Key.A, Key.B)) {
            player.select(if (key == Key.A) 0 else 1)
        },
    )

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to takes")
            }
            Text(
                if (takes.size == 2) "Compare A/B" else takes.first().name,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            HelpButton(HelpTopic.RECORDER)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!state.loaded && error == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }

            takes.forEachIndexed { i, take ->
                val heard = state.selected == i
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        (if (takes.size == 2) "${"AB"[i]} · " else "") + take.name +
                            (if (takes.size == 2 && heard) " — playing" else ""),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (heard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val length = state.lengthMs.coerceAtLeast(1)
                    TakeWave(
                        peaks = peaks.getOrNull(i) ?: FloatArray(0),
                        progress = if (state.lengthMs > 0) state.positionMs.toFloat() / state.lengthMs else 0f,
                        active = heard,
                        onSeek = { f -> player.seekMs((f * state.lengthMs).toLong()) },
                        selection = if (takes.size == 1) selection?.let { it.first.toFloat() / length to it.last.toFloat() / length } else null,
                        onSelect = if (takes.size == 1) { { a, b ->
                            trimStart = (a * length).toLong(); trimEnd = (b * length).toLong(); trimNote = null
                        } } else null,
                    )
                    if (take.alignMs != null) {
                        Text(
                            "Starts where the ${take.source.label.removePrefix("With the ").lowercase()} began" +
                                (if (take.latencyOffsetMs != 0) ", corrected by ${take.latencyOffsetMs} ms of latency" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (takes.size == 2) {
                // Big targets: this is tapped back and forth while listening.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(0, 1).forEach { i ->
                        val on = state.selected == i
                        if (on) {
                            Button(onClick = {}, modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                                .semantics { selected = true; contentDescription = "Take ${"AB"[i]}, playing" }) {
                                Text("${"AB"[i]}", style = MaterialTheme.typography.headlineSmall)
                            }
                        } else {
                            OutlinedButton(onClick = { player.select(i) }, modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                                .semantics { selected = false; contentDescription = "Switch to take ${"AB"[i]}" }) {
                                Text("${"AB"[i]}", style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { player.nudge(-5000) }) { Text("−5s") }
                FilledIconButton(onClick = player::togglePlay, modifier = Modifier.size(60.dp)) {
                    Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play")
                }
                TextButton(onClick = { player.nudge(5000) }) { Text("+5s") }
                Text("${clock(state.positionMs)} / ${clock(state.lengthMs)}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (takes.size == 1) {
                val take = takes.first()
                com.riffbuddy.ui.SectionHeader("TRIM")
                Text(
                    selection?.let { "${clockTenths(it.first)} – ${clockTenths(it.last)}  ·  ${((it.last - it.first) / 1000.0).toFixed(1)} s" }
                        ?: "Drag across the waveform, or set a start and an end while it plays.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                com.riffbuddy.ui.ChipFlow {
                    OutlinedButton(onClick = { trimStart = state.positionMs; trimNote = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start here") }
                    OutlinedButton(onClick = { trimEnd = state.positionMs; trimNote = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("End here") }
                    if (selection != null) {
                        TextButton(onClick = {
                            player.stop(); player.seekMs(selection.first); previewing = true; player.play()
                        }) { Text("Play selection") }
                        TextButton(onClick = { trimStart = null; trimEnd = null; trimNote = null }) { Text("Clear") }
                    }
                }
                if (selection != null) {
                    Button(
                        onClick = {
                            saving = true
                            // The player's timeline starts at the take's aligned start, so
                            // add that back to reach frames in the file.
                            val sr = take.sampleRate
                            val base = take.startMs
                            val from = ((base + selection.first) * sr / 1000).toInt()
                            val to = ((base + selection.last) * sr / 1000).toInt()
                            scope.launch {
                                trimNote = runCatching { repo.saveClip(take, from, to, "${take.name} (clip)") }
                                    .fold({ "Saved as \"${it.name}\" — it's in your takes, ready to share or save." },
                                        { "Couldn't save the clip: ${it.message}" })
                                saving = false
                                onClipSaved()
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (saving) "Saving…" else "Save as new clip") }
                    Text("The original take is kept as it is.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                trimNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

/** m:ss.t — trim points need tenths, not just seconds. */
private fun clockTenths(ms: Long): String = "${ms / 60_000}:${pad(ms / 1000 % 60)}.${ms / 100 % 10}"

/** Peaks from the aligned start onward, so both waveforms line up on screen too. */
private fun trimmedPeaks(pcm: Pcm, start: Int): FloatArray =
    Waveform.peaks(pcm.samples.copyOfRange(start.coerceIn(0, pcm.frames), pcm.frames), 600)

@Composable
private fun TakeWave(
    peaks: FloatArray,
    progress: Float,
    active: Boolean,
    onSeek: (Float) -> Unit,
    selection: Pair<Float, Float>? = null,
    onSelect: ((Float, Float) -> Unit)? = null,
) {
    val wave = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val head = MaterialTheme.colorScheme.onSurface
    // Dims what will be cut rather than tinting what is kept: a loud take fills
    // the waveform to the edges, and a tint underneath it was invisible.
    val cut = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f)
    val edge = MaterialTheme.colorScheme.onSurface
    val select by rememberUpdatedState(onSelect)
    Canvas(
        Modifier.fillMaxWidth().height(if (onSelect != null) 96.dp else 72.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .pointerInput(Unit) { detectTapGestures { p -> onSeek((p.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                // Drag across to select the part to keep (trimming).
                var from = 0f
                detectHorizontalDragGestures(
                    onDragStart = { p -> from = (p.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ ->
                        val to = (change.position.x / size.width).coerceIn(0f, 1f)
                        select?.invoke(minOf(from, to), maxOf(from, to))
                    },
                )
            }
            .semantics {
                contentDescription = if (onSelect != null) "Waveform. Tap to jump; drag to select the part to keep." else "Waveform. Tap to jump."
            },
    ) {

        if (peaks.isNotEmpty()) {
            val bar = size.width / peaks.size
            for (i in peaks.indices) {
                val h = (peaks[i] * size.height * 0.9f).coerceAtLeast(1f)
                drawRect(wave, Offset(i * bar, (size.height - h) / 2), Size(bar.coerceAtLeast(1f), h))
            }
        }
        selection?.let { (a, b) ->
            drawRect(cut, Offset.Zero, Size(a * size.width, size.height))
            drawRect(cut, Offset(b * size.width, 0f), Size((1 - b) * size.width, size.height))
            drawRect(edge, Offset(a * size.width - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
            drawRect(edge, Offset(b * size.width - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        }
        drawRect(head, Offset(progress * size.width - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
    }
}
