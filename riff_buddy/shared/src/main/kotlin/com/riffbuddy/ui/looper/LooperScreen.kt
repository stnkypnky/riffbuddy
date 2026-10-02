package com.riffbuddy.ui.looper

import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.ui.help.HelpButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import com.riffbuddy.ui.tactile.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.FilledIconButton
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.Icon
import com.riffbuddy.ui.tactile.IconButton
import com.riffbuddy.ui.tactile.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.riffbuddy.ui.tactile.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riffbuddy.audio.AudioFileException
import com.riffbuddy.audio.LoopTrainerConfig
import com.riffbuddy.audio.LooperEngine
import com.riffbuddy.audio.LooperState
import com.riffbuddy.audio.TapTempo
import com.riffbuddy.data.AudioRepo
import com.riffbuddy.domain.AudioFile
import com.riffbuddy.domain.SavedLoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the looper remembers when a recording is left. */
data class LooperMemory(
    val positionMs: Long,
    val speedPercent: Int,
    val loopStartMs: Long?,
    val loopEndMs: Long?,
    val volumePercent: Int,
)

private sealed interface Phase {
    data class Decoding(val progress: Float) : Phase
    data object Ready : Phase
    data object Missing : Phase
    data class Failed(val message: String) : Phase
}

/**
 * The looper: a recording's waveform, an A–B loop set by
 * dragging or by Set A / Set B, speed with the pitch held, nudges, count-in,
 * a metronome over the music, saved loops, and the loop trainer.
 */
@Composable
fun LooperScreen(
    file: AudioFile,
    engine: LooperEngine,
    repo: AudioRepo,
    onBack: () -> Unit,
    onLeave: (LooperMemory) -> Unit,
    onLogTrainer: (loopName: String, seconds: Int, startPercent: Int, endPercent: Int, loops: Int) -> Unit,
    onChanged: () -> Unit,
) {
    val platform = com.riffbuddy.platform.LocalPlatform.current
    val scope = rememberCoroutineScope()
    var uri by remember(file.id) { mutableStateOf(file.uri) }
    var phase by remember(file.id) { mutableStateOf<Phase>(Phase.Decoding(0f)) }
    val state by engine.state.collectAsStateWithLifecycle()
    var loops by remember(file.id) { mutableStateOf(emptyList<SavedLoop>()) }
    var pendingA by remember(file.id) { mutableStateOf<Long?>(null) }
    var savingLoop by remember { mutableStateOf(false) }
    var loopError by remember { mutableStateOf<String?>(null) }

    suspend fun reloadLoops() { loops = repo.loopsForAudio(file.id) }

    // Decode, unless this recording is already in memory from last time.
    LaunchedEffect(file.id, uri) {
        reloadLoops()
        if (engine.loadedId == file.id && state.loaded) {
            phase = Phase.Ready
            return@LaunchedEffect
        }
        phase = Phase.Decoding(0f)
        var shown = 0
        val decoded = withContext(Dispatchers.Default) {
            runCatching {
                platform.decodeAudio(
                    uri,
                    // The decoder reports on every packet — thousands per song.
                    // Whole percents are all a bar can show.
                    onProgress = { p ->
                        val pct = (p * 100).toInt()
                        if (pct != shown) { shown = pct; phase = Phase.Decoding(p) }
                    },
                    canceled = { !isActive },
                )
            }
        }
        decoded.onSuccess { audio ->
            engine.load(audio, file.id)
            repo.setDuration(file.id, audio.durationMs)
            engine.setBpm(file.bpm)
            engine.setSpeed(file.speedPercent)
            engine.setVolume(file.volumePercent / 100f)
            if (file.loopStartMs != null && file.loopEndMs != null) engine.setLoop(file.loopStartMs, file.loopEndMs)
            engine.seekMs(file.positionMs)
            phase = Phase.Ready
        }.onFailure { e ->
            phase = if (e is AudioFileException && e.message?.contains("moved") == true) Phase.Missing
            else Phase.Failed(e.message ?: "That recording could not be opened.")
        }
    }

    // Remembered per file, however the screen is left. Playback stops too — a
    // looper still playing on another tab is a surprise, not a feature.
    val leave by rememberUpdatedState(onLeave)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(file.id, lifecycle) {
        fun snapshot() = engine.state.value.let {
            LooperMemory(it.positionMs, it.speedPercent, it.loopStartMs, it.loopEndMs, (it.volume * 100).roundToInt())
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { engine.pause(); leave(snapshot()) }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            engine.pause()
            if (engine.loadedId == file.id) leave(snapshot())
        }
    }

    val relink = platform.rememberOpenFile(listOf("audio/*"), keepAccess = true) { picked ->
        if (picked == null) return@rememberOpenFile
        scope.launch {
            repo.relink(file.id, picked.ref, picked.size ?: 0)
            uri = picked.ref
            onChanged()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to recordings")
            }
            Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            HelpButton(HelpTopic.LOOPER)
        }

        when (val p = phase) {
            is Phase.Decoding -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Reading the recording…", style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "The whole song is loaded into memory once, so that loops can jump back with no gap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Phase.Missing -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Can't find this recording", style = MaterialTheme.typography.titleMedium)
                Text(
                    "It has been moved, renamed or deleted since it was added. Point Riff Buddy at it " +
                        "again and your saved loops and settings come back with it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { relink() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Find it again")
                }
            }
            is Phase.Failed -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { relink() }) { Text("Choose a different file") }
            }
            Phase.Ready -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                WaveformView(
                    peaks = engine.peaks,
                    state = state,
                    pendingA = pendingA,
                    onSeek = engine::seekMs,
                    onLoop = { a, b ->
                        loopError = if (engine.setLoop(a, b)) null else "A loop needs to be at least a quarter of a second."
                        pendingA = null
                    },
                )
                Transport(state, engine)
                LoopControls(
                    state = state,
                    pendingA = pendingA,
                    onSetA = {
                        val now = state.positionMs
                        val b = state.loopEndMs
                        if (b != null && now < b - 250) {
                            loopError = if (engine.setLoop(now, b)) null else "A loop needs to be at least a quarter of a second."
                        } else {
                            engine.setLoop(null, null)
                            pendingA = now
                        }
                    },
                    onSetB = {
                        val a = pendingA ?: state.loopStartMs
                        if (a == null) {
                            loopError = "Set A first — where the loop starts."
                        } else {
                            loopError = if (engine.setLoop(a, state.positionMs)) null else "A loop needs to be at least a quarter of a second."
                            pendingA = null
                        }
                    },
                    onClear = { engine.setLoop(null, null); pendingA = null },
                    onSave = { savingLoop = true },
                )
                loopError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (loops.isNotEmpty()) {
                    SavedLoops(
                        loops = loops,
                        onJump = { loop ->
                            engine.setLoop(loop.start, loop.end)
                            engine.setSpeed(loop.speedPercent)
                            engine.seekMs(loop.start)
                        },
                        onDelete = { loop -> scope.launch { repo.deleteLoop(loop.id); reloadLoops() } },
                    )
                }
                SpeedAndVolume(state, engine)
                PracticeAids(
                    state = state,
                    engine = engine,
                    onBpm = { bpm -> engine.setBpm(bpm); scope.launch { repo.setBpm(file.id, bpm); onChanged() } },
                )
                TrainerCard(
                    state = state,
                    engine = engine,
                    onLog = { startPct, endPct, count ->
                        val started = state.trainerStartedAt ?: System.currentTimeMillis()
                        val seconds = ((System.currentTimeMillis() - started) / 1000).toInt()
                        val name = loops.firstOrNull { it.start == state.loopStartMs && it.end == state.loopEndMs }?.name
                            ?: file.name
                        onLogTrainer(name, seconds, startPct, endPct, count)
                    },
                )
            }
        }
    }

    if (savingLoop) {
        var name by remember { mutableStateOf("Loop ${loops.size + 1}") }
        AlertDialog(
            onDismissRequest = { savingLoop = false },
            title = { Text("Save this loop") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name — e.g. \"solo, first run\"") })
            },
            confirmButton = {
                TextButton(onClick = {
                    savingLoop = false
                    val a = state.loopStartMs ?: return@TextButton
                    val b = state.loopEndMs ?: return@TextButton
                    scope.launch {
                        repo.saveLoop(SavedLoop(audioId = file.id, name = name, start = a, end = b, speedPercent = state.speedPercent))
                        reloadLoops()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { savingLoop = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The recording's outline, the loop, and the playhead. Tap to jump; drag from
 * an A or B handle to move it; drag anywhere else to mark a new loop.
 */
@Composable
private fun WaveformView(
    peaks: FloatArray,
    state: LooperState,
    pendingA: Long?,
    onSeek: (Long) -> Unit,
    onLoop: (Long, Long) -> Unit,
) {
    val wave = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val region = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val handle = MaterialTheme.colorScheme.tertiary
    val head = MaterialTheme.colorScheme.onSurface
    val duration = state.durationMs.coerceAtLeast(1)
    val latest by rememberUpdatedState(state)

    var dragA by remember { mutableStateOf<Long?>(null) }
    var dragB by remember { mutableStateOf<Long?>(null) }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .semantics {
                contentDescription = "Waveform. Tap to jump; drag to mark a loop."
                stateDescription = "${clock(state.positionMs)} of ${clock(state.durationMs)}"
            }
            .pointerInput(duration) {
                detectTapGestures { p -> onSeek((p.x / size.width * duration).toLong().coerceIn(0, duration)) }
            }
            .pointerInput(duration) {
                fun msAt(x: Float) = (x / size.width * duration).toLong().coerceIn(0, duration)
                var moving = 0      // 1 = A, 2 = B, 3 = a new loop
                var anchor = 0L
                detectDragGestures(
                    onDragStart = { p ->
                        val s = latest
                        val grab = 28.dp.toPx() / size.width * duration
                        val a = s.loopStartMs
                        val b = s.loopEndMs
                        val at = msAt(p.x)
                        moving = when {
                            a != null && abs(at - a) < grab -> 1
                            b != null && abs(at - b) < grab -> 2
                            else -> 3
                        }
                        anchor = at
                        dragA = if (moving == 2) a else if (moving == 1) at else at
                        dragB = if (moving == 1) b else if (moving == 2) at else at
                    },
                    onDrag = { change, _ ->
                        val at = msAt(change.position.x)
                        when (moving) {
                            1 -> dragA = at
                            2 -> dragB = at
                            else -> { dragA = minOf(anchor, at); dragB = maxOf(anchor, at) }
                        }
                    },
                    onDragEnd = {
                        val a = dragA
                        val b = dragB
                        if (a != null && b != null) onLoop(minOf(a, b), maxOf(a, b))
                        dragA = null; dragB = null
                    },
                    onDragCancel = { dragA = null; dragB = null },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        fun x(ms: Long) = ms.toFloat() / duration * w
        val a = dragA ?: state.loopStartMs
        val b = dragB ?: state.loopEndMs
        if (a != null && b != null) drawRect(region, Offset(x(minOf(a, b)), 0f), Size(abs(x(b) - x(a)), h))
        if (peaks.isNotEmpty()) {
            val bar = w / peaks.size
            for (i in peaks.indices) {
                val px = i * bar
                val hh = (peaks[i] * (h * 0.9f)).coerceAtLeast(1f)
                val inLoop = a == null || b == null || (px >= x(minOf(a, b)) && px <= x(maxOf(a, b)))
                drawRect(if (inLoop) wave else dim, Offset(px, (h - hh) / 2), Size(bar.coerceAtLeast(1f), hh))
            }
        }
        listOfNotNull(a, b, pendingA).forEach { m ->
            drawRect(handle, Offset(x(m) - 2.dp.toPx(), 0f), Size(4.dp.toPx(), h))
        }
        drawRect(head, Offset(x(state.positionMs) - 1.dp.toPx(), 0f), Size(2.dp.toPx(), h))
    }
}

@Composable
private fun Transport(state: LooperState, engine: LooperEngine) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { engine.nudge(-5000) }) { Text("−5s") }
        TextButton(onClick = { engine.nudge(-1000) }) { Text("−1s") }
        FilledIconButton(onClick = engine::togglePlay, modifier = Modifier.size(60.dp)) {
            Icon(
                if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.playing) "Pause" else "Play",
            )
        }
        TextButton(onClick = { engine.nudge(1000) }) { Text("+1s") }
        TextButton(onClick = { engine.nudge(5000) }) { Text("+5s") }
        Text(
            when {
                state.countingIn -> "Count-in…"
                else -> "${clock(state.positionMs)} / ${clock(state.durationMs)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun LoopControls(
    state: LooperState,
    pendingA: Long?,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when {
                state.loopStartMs != null && state.loopEndMs != null ->
                    "Loop ${SavedLoop.clock(state.loopStartMs)}–${SavedLoop.clock(state.loopEndMs)}" +
                        if (state.loopsCompleted > 0) " · played ${state.loopsCompleted}×" else ""
                pendingA != null -> "A at ${SavedLoop.clock(pendingA)} — now play on and tap Set B"
                else -> "No loop. Drag across the waveform, or use Set A and Set B while it plays."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        com.riffbuddy.ui.ChipFlow {
            OutlinedButton(onClick = onSetA, modifier = Modifier.heightIn(min = 48.dp)) { Text("Set A") }
            OutlinedButton(onClick = onSetB, modifier = Modifier.heightIn(min = 48.dp)) { Text("Set B") }
            if (state.loopStartMs != null) {
                TextButton(onClick = onSave) { Text("Save loop") }
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun SavedLoops(loops: List<SavedLoop>, onJump: (SavedLoop) -> Unit, onDelete: (SavedLoop) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.riffbuddy.ui.SectionHeader("SAVED LOOPS")
        com.riffbuddy.ui.ChipFlow {
            loops.forEach { loop ->
                InputChip(
                    selected = false,
                    onClick = { onJump(loop) },
                    label = { Text("${loop.name} · ${loop.speedPercent}%") },
                    trailingIcon = {
                        Text(
                            "✕",
                            modifier = Modifier.pointerInput(loop.id) { detectTapGestures { onDelete(loop) } }
                                .semantics { contentDescription = "Delete ${loop.name}" }
                                .padding(4.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SpeedAndVolume(state: LooperState, engine: LooperEngine) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speed ${state.speedPercent}%", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(120.dp))
            com.riffbuddy.ui.ChipFlow {
                listOf(50, 75, 100).forEach { pct ->
                    FilterChip(
                        selected = state.speedPercent == pct,
                        onClick = { engine.setSpeed(pct) },
                        label = { Text("$pct%") },
                        enabled = state.trainer == null,
                    )
                }
            }
        }
        Slider(
            value = state.speedPercent.toFloat(),
            onValueChange = { engine.setSpeed((it / 5).roundToInt() * 5) },
            valueRange = 25f..150f,
            enabled = state.trainer == null,
            modifier = Modifier.semantics {
                contentDescription = "Speed, pitch unchanged"
                stateDescription = "${state.speedPercent} percent"
            },
        )
        Text("Volume ${(state.volume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = state.volume,
            onValueChange = engine::setVolume,
            modifier = Modifier.semantics { contentDescription = "Volume" },
        )
    }
}

/** Count-in and the metronome over the music both need the recording's tempo. */
@Composable
private fun PracticeAids(state: LooperState, engine: LooperEngine, onBpm: (Int?) -> Unit) {
    val tap = remember { TapTempo() }
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.riffbuddy.ui.SectionHeader("TEMPO OF THE RECORDING")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onBpm(((state.bpm ?: 100) - 1).coerceAtLeast(20)) }) { Text("−") }
                Text(state.bpm?.let { "$it BPM" } ?: "Not set", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onBpm(((state.bpm ?: 100) + 1).coerceAtMost(300)) }) { Text("+") }
                OutlinedButton(onClick = { tap.tap(System.currentTimeMillis())?.let(onBpm) }) {
                    Text("Tap along")
                }
            }
            Text(
                "Tap along to the recording at full speed. Count-in and clicks then follow it, slowing when it slows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchLine("Count in before playing", state.countIn, state.bpm != null, engine::setCountIn)
            SwitchLine("Metronome over the music", state.metronome, state.bpm != null, engine::setMetronome)
        }
    }
}

/** The loop trainer. */
@Composable
private fun TrainerCard(state: LooperState, engine: LooperEngine, onLog: (Int, Int, Int) -> Unit) {
    var start by remember { mutableStateOf(60) }
    var target by remember { mutableStateOf(100) }
    var step by remember { mutableStateOf(5) }
    var every by remember { mutableStateOf(3) }
    var manual by remember { mutableStateOf(false) }
    var logged by remember { mutableStateOf(false) }
    val trainer = state.trainer

    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.riffbuddy.ui.SectionHeader("LOOP TRAINER")
            when {
                trainer == null -> {
                    Stepper("Start at", "$start%", { start = (start - 5).coerceAtLeast(25) }, { start = (start + 5).coerceAtMost(target - 5) })
                    Stepper("Finish at", "$target%", { target = (target - 5).coerceAtLeast(start + 5) }, { target = (target + 5).coerceAtMost(150) })
                    Stepper("Go up by", "$step%", { step = (step - 1).coerceAtLeast(1) }, { step = (step + 1).coerceAtMost(25) })
                    if (!manual) Stepper("Every", "$every loops", { every = (every - 1).coerceAtLeast(1) }, { every = (every + 1).coerceAtMost(20) })
                    SwitchLine("I'll say when it's clean", manual, true) { manual = it }
                    Button(
                        onClick = {
                            logged = false
                            engine.startTrainer(LoopTrainerConfig(start, target, step, every, manual))
                        },
                        enabled = state.loopStartMs != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (state.loopStartMs == null) "Set a loop first" else "Start trainer") }
                }
                trainer.done -> {
                    Text(
                        "Done — ${trainer.config.targetPercent}% played, ${trainer.totalLoops} loops.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onLog(trainer.config.startPercent, trainer.percent, trainer.totalLoops)
                                logged = true
                            },
                            enabled = !logged,
                        ) { Text(if (logged) "Logged" else "Add to today's log") }
                        TextButton(onClick = engine::stopTrainer) { Text("Close") }
                    }
                }
                else -> {
                    Text("${trainer.percent}%", style = MaterialTheme.typography.displaySmall)
                    LinearProgressIndicator(progress = { trainer.progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (trainer.config.manual) "Loop ${trainer.loopsAtThisSpeed + 1} at this speed — tap Clean run when it is"
                        else "Loop ${trainer.loopsAtThisSpeed + 1} of ${trainer.config.everyLoops} at this speed",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (trainer.config.manual) Button(onClick = engine::cleanRun) { Text("Clean run") }
                        TextButton(onClick = {
                            onLog(trainer.config.startPercent, trainer.percent, trainer.totalLoops)
                            engine.pause(); engine.stopTrainer()
                        }) { Text("Stop and log") }
                        TextButton(onClick = { engine.pause(); engine.stopTrainer() }) { Text("Stop") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onDown: () -> Unit, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDown, modifier = Modifier.semantics { contentDescription = "$label lower" }) { Text("−") }
        Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(76.dp))
        TextButton(onClick = onUp, modifier = Modifier.semantics { contentDescription = "$label higher" }) { Text("+") }
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
