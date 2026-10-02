package com.riffbuddy.ui.recorder

import com.riffbuddy.domain.Device
import com.riffbuddy.ui.theme.tactileClickable
import java.io.File
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.riffbuddy.ui.tactile.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.MaterialTheme
import com.riffbuddy.ui.tactile.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riffbuddy.audio.InputLevel
import com.riffbuddy.audio.GainStage
import com.riffbuddy.audio.RecordInput
import com.riffbuddy.audio.RecorderEngine
import com.riffbuddy.audio.RecorderStatus
import com.riffbuddy.data.RecordingRepo
import com.riffbuddy.domain.Recording
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.TakeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The recorder and the list of takes.
 *
 * **Recording stops when this screen goes away**, and the take is kept. Android
 * silences the microphone for an app in the background unless it runs a
 * foreground service with a notification, and a take that quietly records
 * silence after switching apps is worse than one that stops and says so.
 */
@Composable
fun RecorderScreen(
    header: @Composable () -> Unit,
    engine: RecorderEngine,
    repo: RecordingRepo,
    takes: List<Recording>,
    songs: List<Song>,
    sessionId: Long?,
    latency: LatencyInfo,
    calibration: () -> com.riffbuddy.audio.CalibrationRun,
    onCalibrated: (com.riffbuddy.audio.LatencyCalibration.Result) -> Unit,
    playAlong: PlayAlongControls,
    /** Saved input gain, dB, for the device mic (false) and the amp's headphone out (true). */
    savedGain: Map<Boolean, Float>,
    onSaveGain: (line: Boolean, db: Float) -> Unit,
    onPlay: (Recording) -> Unit,
    onCompare: (Recording, Recording) -> Unit,
    onOpenInLooper: (Recording) -> Unit,
    onChanged: () -> Unit,
    /** Seconds of countdown before a take starts; 0 is none. Remembered in settings. */
    countdownSeconds: Int = 0,
    onCountdown: (Int) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val state by engine.state.collectAsStateWithLifecycle()
    val platform = com.riffbuddy.platform.LocalPlatform.current
    var granted by remember { mutableStateOf(platform.hasPermission(com.riffbuddy.platform.Permission.MICROPHONE)) }
    val askForMic = platform.rememberPermissionRequest(com.riffbuddy.platform.Permission.MICROPHONE) { granted = it }
    var songId by remember { mutableStateOf<Long?>(null) }
    var storage by remember { mutableStateOf(0L) }
    val picked = remember { mutableStateListOf<Long>() }
    var renaming by remember { mutableStateOf<Recording?>(null) }
    var editingNotes by remember { mutableStateOf<Recording?>(null) }
    var linking by remember { mutableStateOf<Recording?>(null) }
    var deleting by remember { mutableStateOf<Recording?>(null) }
    var current by remember { mutableStateOf<Recording?>(null) }
    var alignMs by remember { mutableStateOf<Long?>(null) }

    // Sharing and saving a copy: pick a format, make the file, then hand it on.
    var exporting by remember { mutableStateOf<Pair<Recording, Boolean>?>(null) }   // take, save (else share)
    var exportBusy by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    // One launcher per type: the save dialog's file type is fixed when it is registered.
    val saveWav = platform.rememberSaveCopy(ExportFormat.WAV.extension, ExportFormat.WAV.mime) { exportNote = it }
    val saveM4a = platform.rememberSaveCopy(ExportFormat.M4A.extension, ExportFormat.M4A.mime) { exportNote = it }
    val export: (Recording, Boolean, ExportFormat) -> Unit = export@{ take, save, format ->
        // One at a time: each export clears the folder the last one wrote to,
        // so a second tap while one was running deleted the file mid-encode.
        if (exportBusy) return@export
        exportBusy = true
        exportNote = null
        scope.launch {
            runCatching { exportTake(platform, repo, take, format) }
                .onSuccess { file ->
                    if (save) (if (format == ExportFormat.WAV) saveWav else saveM4a)(file)
                    else platform.shareFile(file, format.mime, take.name)
                }
                .onFailure { exportNote = "Couldn't make the ${format.label} file: ${it.message}" }
            exportBusy = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(takes) { storage = repo.storageBytes() }

    // The countdown before a take: seconds left, or null when not counting.
    // Nothing is created until it ends, so cancelling leaves no empty take.
    var counting by remember { mutableStateOf<Int?>(null) }
    var countJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }


    /** Stop, keep the take, stop anything playing along. Safe to call twice. */
    val finishTake: () -> Unit = {
        val take = current
        if (take != null) {
            val align = playAlong.stop() ?: alignMs
            val frames = engine.stop()
            current = null
            alignMs = null
            scope.launch(NonCancellable) {
                val f = repo.fileFor(take)
                repo.finish(take.id, frames * 1000 / engine.sampleRate, f.length(), align)
                onChanged()
            }
        }
    }
    /** Creates the take and starts the microphone, then anything playing along. */
    suspend fun beginTake() {
        val songTitle = songs.firstOrNull { it.id == songId }?.title
        val count = takes.count { it.songId == songId } + 1
        val take = repo.begin(
            name = listOfNotNull(songTitle, "take $count").joinToString(", ").replaceFirstChar { it.uppercase() },
            sampleRate = engine.sampleRate,
            songId = songId,
            sessionId = sessionId,
            latencyOffsetMs = latency.offsetMs,
            source = playAlong.source,
        )
        current = take
        engine.start(take.id, repo.fileFor(take))
        // The source starts only once the microphone
        // is capturing, so the whole of it is in the take.
        alignMs = playAlong.start(engine)
    }

    /**
     * Record, after the countdown if one is set: time to get a hand back on
     * the neck after tapping the screen. A beep each second, a higher one on
     * the last; they end a second before the microphone opens, so none of
     * them is in the take.
     */
    val record: () -> Unit = {
        countJob?.cancel()
        countJob = scope.launch {
            if (countdownSeconds > 0) {
                val beeper = platform.countdownBeeper()
                try {
                    for (left in countdownSeconds downTo 1) {
                        counting = left
                        beeper?.beep(last = left == 1)
                        kotlinx.coroutines.delay(1000)
                    }
                } finally {
                    beeper?.release()
                    counting = null
                }
            }
            beginTake()
        }
    }
    val cancelCountdown: () -> Unit = {
        countJob?.cancel()
        counting = null
    }

    /**
     * Messed it up: throw the take away and go again, countdown and all.
     * Whatever was playing along stops and restarts with the new take, so it
     * still lines up from its first beat.
     */
    /** Stops and throws the take away; [then] runs once it's gone. */
    fun discard(then: () -> Unit) {
        val take = current ?: return
        playAlong.stop()
        engine.stop()
        current = null
        alignMs = null
        scope.launch {
            kotlinx.coroutines.withContext(NonCancellable) { repo.delete(take) }
            onChanged()
            then()
        }
    }
    val startOver: () -> Unit = { discard(then = record) }

    /** Messed it up and done for now: nothing kept, back to ready. */
    val stopAndDelete: () -> Unit = { discard(then = {}) }

    val finish by rememberUpdatedState(finishTake)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            // Leaving mid-countdown cancels it: a take shouldn't start with
            // nobody at the screen.
            if (e == Lifecycle.Event.ON_STOP) { countJob?.cancel(); counting = null; finish() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); countJob?.cancel(); finish() }
    }

    Column(Modifier.fillMaxSize()) {
        header()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!granted) {
                com.riffbuddy.ui.Panel {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Record yourself playing", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Riff Buddy needs the microphone to record. It is used only while you are " +
                                "recording, and released the moment you stop.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = askForMic) { Text("Allow microphone") }
                    }
                }
            } else {
                InputPanel(
                    engine = engine,
                    enabled = state.status == RecorderStatus.IDLE,
                    savedGain = savedGain,
                    onSaveGain = onSaveGain,
                )
                RecordPanel(
                    elapsedMs = state.elapsedMs,
                    levelDb = state.levelDb,
                    clipping = state.clipping,
                    status = state.status,
                    error = state.error,
                    songs = songs,
                    songId = songId,
                    onSong = { songId = it },
                    sessionId = sessionId,
                    playAlong = playAlong,
                    canPause = playAlong.source == TakeSource.NONE,
                    countingDown = counting,
                    countdownSeconds = countdownSeconds,
                    onCountdown = onCountdown,
                    onRecord = record,
                    onCancelCountdown = cancelCountdown,
                    onStartOver = startOver,
                    onStopAndDelete = stopAndDelete,
                    onPause = engine::pause,
                    onResume = engine::resume,
                    onStop = finishTake,
                )
            }

            if (granted && state.status == RecorderStatus.IDLE) {
                CalibrationCard(info = latency, run = calibration, onSave = onCalibrated)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                com.riffbuddy.ui.SectionHeader("TAKES", modifier = Modifier.weight(1f))
                Text(
                    "${takes.size} · ${"%.1f".format(storage / 1_000_000.0)} MB on this device",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            exportNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (picked.size == 2) {
                Button(onClick = {
                    val a = takes.first { it.id == picked[0] }
                    val b = takes.first { it.id == picked[1] }
                    picked.clear()
                    onCompare(a, b)
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Compare these two") }
            } else if (takes.size >= 2) {
                Text(
                    "Tick two takes to compare them A/B.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (takes.isEmpty()) {
                Text(
                    "No takes yet. Record one above — every take is kept until you delete it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            takes.forEach { take ->
                TakeRow(
                    take = take,
                    song = songs.firstOrNull { it.id == take.songId }?.title,
                    picked = take.id in picked,
                    recording = current?.id == take.id,
                    onPick = {
                        if (take.id in picked) picked.remove(take.id)
                        else { if (picked.size == 2) picked.removeAt(0); picked.add(take.id) }
                    },
                    onPlay = { onPlay(take) },
                    onStar = { scope.launch { repo.setStarred(take.id, !take.starred); onChanged() } },
                    onRate = { r -> scope.launch { repo.setRating(take.id, if (take.rating == r) null else r); onChanged() } },
                    onRename = { renaming = take },
                    onNotes = { editingNotes = take },
                    onLink = { linking = take },
                    onShare = { exporting = take to false },
                    onSave = { exporting = take to true },
                    onLoop = { onOpenInLooper(take) },
                    onDelete = { deleting = take },
                )
            }
        }
    }

    if (exportBusy) {
        // Up until the file is ready, because an M4A of a long take takes a
        // few seconds and a small line of text went unnoticed.
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Making the file…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("A few seconds for a long take.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
        )
    }
    exporting?.let { (take, save) ->
        AlertDialog(
            onDismissRequest = { exporting = null },
            title = { Text(if (save) "Save a copy of \"${take.name}\"" else "Share \"${take.name}\"") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.entries.filter { it != ExportFormat.M4A || platform.canEncodeM4a }.forEach { format ->
                        OutlinedButton(
                            onClick = { exporting = null; export(take, save, format) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(format.label, style = MaterialTheme.typography.titleSmall)
                                Text(format.detail, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (save) {
                        Text("You choose the folder next — Music or Downloads keeps it with your other files.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { exporting = null }) { Text("Cancel") } },
        )
    }
    renaming?.let { take ->
        TextDialog("Rename", take.name, singleLine = true, onDismiss = { renaming = null }) { text ->
            renaming = null
            scope.launch { repo.rename(take.id, text); onChanged() }
        }
    }
    editingNotes?.let { take ->
        TextDialog("Notes", take.notes, singleLine = false, onDismiss = { editingNotes = null }) { text ->
            editingNotes = null
            scope.launch { repo.setNotes(take.id, text); onChanged() }
        }
    }
    linking?.let { take ->
        SongPicker(songs, take.songId, onDismiss = { linking = null }) { id ->
            linking = null
            scope.launch { repo.link(take.id, id); onChanged() }
        }
    }
    deleting?.let { take ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${take.name}\"?") },
            text = { Text("The recording is deleted from this device. If it is in the looper, it goes from there too, with its saved loops. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { repo.delete(take); picked.remove(take.id); onChanged() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

/**
 * Where the sound comes from and how loud it is recorded.
 *
 * The amp's headphone output is chosen automatically when it is plugged in:
 * recording it directly has no microphone and no room in it — the real fix
 * for background noise. Gain is remembered separately for the device mic and
 * for the amp, since the two want completely different amounts.
 */
@Composable
private fun InputPanel(
    engine: RecorderEngine,
    enabled: Boolean,
    savedGain: Map<Boolean, Float>,
    onSaveGain: (Boolean, Float) -> Unit,
) {
    val platform = com.riffbuddy.platform.LocalPlatform.current
    val scope = rememberCoroutineScope()
    var inputs by remember { mutableStateOf(engine.inputs()) }
    var chosen by remember { mutableStateOf(engine.inputId) }
    var gain by remember { mutableStateOf(engine.gainDb) }
    var measuring by remember { mutableStateOf(false) }
    val line = inputs.firstOrNull { it.id == chosen }?.line == true

    fun choose(input: RecordInput) {
        chosen = input.id
        engine.inputId = input.id
        gain = savedGain[input.line] ?: 0f
        engine.gainDb = gain
    }

    // Follows the cable: plugging the amp in selects it, unplugging falls back.
    platform.OnAudioInputsChanged { added ->
        inputs = engine.inputs()
        if (added) inputs.firstOrNull { it.line }?.let { choose(it) }
        else if (inputs.none { it.id == chosen }) choose(inputs.first())
    }
    LaunchedEffect(Unit) {
        (inputs.firstOrNull { it.line } ?: inputs.firstOrNull { it.id == chosen } ?: inputs.first()).let { choose(it) }
    }

    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.riffbuddy.ui.SectionHeader("INPUT")
            com.riffbuddy.ui.ChipFlow {
                inputs.forEach { input ->
                    FilterChip(
                        selected = chosen == input.id,
                        onClick = { choose(input) },
                        enabled = enabled,
                        label = { Text(input.label) },
                    )
                }
            }
            Text(
                if (line) {
                    "Recording the amp directly — no microphone, so no room noise. Start with the amp's " +
                        "volume low and use Set level: this input expects a microphone, and a loud " +
                        "headphone output will distort. Play-along timing was calibrated with the " +
                        "microphone and may be a few ms out."
                } else {
                    "Put the device 20–30 cm in front of the amp's speaker, pointing at it. From across " +
                        "the room, the room is recorded as loudly as the guitar. For no room at all, " +
                        "connect the amp's headphone output to the headphone jack with a headset (TRRS) " +
                        "adapter — a plain stereo cable is not an input."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Level +${gain.roundToInt()} dB", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(110.dp))
                androidx.compose.material3.Slider(
                    value = gain,
                    onValueChange = { gain = it.roundToInt().toFloat(); engine.gainDb = gain },
                    onValueChangeFinished = { onSaveGain(line, gain) },
                    valueRange = 0f..GainStage.MAX_DB,
                    enabled = enabled && !measuring,
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = "Input level"
                        stateDescription = "plus ${gain.roundToInt()} decibels"
                    },
                )
            }
            OutlinedButton(
                onClick = {
                    measuring = true
                    scope.launch {
                        val suggested = runCatching { engine.measureGain() }.getOrNull()
                        if (suggested != null) {
                            gain = suggested.roundToInt().toFloat()
                            engine.gainDb = gain
                            onSaveGain(line, gain)
                        }
                        measuring = false
                    }
                },
                enabled = enabled && !measuring,
            ) { Text(if (measuring) "Play your loudest part… (5 s)" else "Set level") }
        }
    }
}

@Composable
private fun RecordPanel(
    elapsedMs: Long,
    levelDb: Float,
    clipping: Boolean,
    status: RecorderStatus,
    error: String?,
    songs: List<Song>,
    songId: Long?,
    onSong: (Long?) -> Unit,
    sessionId: Long?,
    playAlong: PlayAlongControls,
    canPause: Boolean,
    countingDown: Int?,
    countdownSeconds: Int,
    onCountdown: (Int) -> Unit,
    onRecord: () -> Unit,
    onCancelCountdown: () -> Unit,
    onStartOver: () -> Unit,
    onStopAndDelete: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    var choosingSong by remember { mutableStateOf(false) }
    val idle = status == RecorderStatus.IDLE
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (countingDown != null) {
                Text(
                    "Recording in",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "$countingDown",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics {
                        contentDescription = "Recording starts in $countingDown"
                        liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Assertive
                    },
                )
            } else Text(
                clock(elapsedMs),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.semantics { contentDescription = "Recorded ${elapsedMs / 1000} seconds" },
            )
            LevelMeter(levelDb, clipping, active = status == RecorderStatus.RECORDING)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            if (idle && countingDown == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "For: " + (songs.firstOrNull { it.id == songId }?.title ?: "no particular song"),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { choosingSong = true }) { Text("Change") }
                }
                if (sessionId != null) {
                    Text(
                        "Goes into today's practice session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                playAlong.Picker()
            }

            // A flow: Stop, Pause and Start over side by side don't fit a phone.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    countingDown != null -> OutlinedButton(onClick = onCancelCountdown, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text("Cancel")
                    }
                    status == RecorderStatus.IDLE -> {
                        Button(
                            onClick = onRecord,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) { Text("● Record") }
                        Box(Modifier.heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                            com.riffbuddy.ui.MenuChip(
                                text = if (countdownSeconds == 0) "No countdown" else "Countdown ${countdownSeconds} s",
                                options = COUNTDOWNS,
                                selected = countdownSeconds,
                                label = { if (it == 0) "No countdown" else "$it second countdown" },
                                onPick = onCountdown,
                                description = "Countdown before recording",
                                active = countdownSeconds > 0,
                            )
                        }
                    }
                    status == RecorderStatus.RECORDING -> {
                        Button(onClick = onStop, modifier = Modifier.heightIn(min = 56.dp)) { Text("■ Stop") }
                        if (canPause) OutlinedButton(onClick = onPause, modifier = Modifier.heightIn(min = 56.dp)) { Text("Pause") }
                        OutlinedButton(onClick = onStartOver, modifier = Modifier.heightIn(min = 56.dp)) { Text("↺ Start over") }
                        StopAndDelete(onStopAndDelete)
                    }
                    else -> {
                        Button(onClick = onResume, modifier = Modifier.heightIn(min = 56.dp)) { Text("● Resume") }
                        OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 56.dp)) { Text("■ Stop") }
                        OutlinedButton(onClick = onStartOver, modifier = Modifier.heightIn(min = 56.dp)) { Text("↺ Start over") }
                        StopAndDelete(onStopAndDelete)
                    }
                }
            }
            if (status != RecorderStatus.IDLE) {
                Text(
                    "Start over throws this take away and starts a new one" +
                        (if (countdownSeconds > 0) ", after the countdown" else "") +
                        ". Stop & delete throws it away and stops.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status == RecorderStatus.PAUSED) {
                Text("Paused — the microphone is off until you resume.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (choosingSong) SongPicker(songs, songId, onDismiss = { choosingSong = false }) { onSong(it); choosingSong = false }
}

/**
 * Input level, peak-reading, with the clipping warning in words: no state by
 * color alone — a red bar means nothing to someone who cannot tell red from
 * amber.
 */
@Composable
private fun LevelMeter(db: Float, clipping: Boolean, active: Boolean) {
    val fill = InputLevel.meter(if (active) db else InputLevel.FLOOR_DB)
    val color = when {
        clipping -> MaterialTheme.colorScheme.error
        db > -6f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(14.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                .semantics {
                    contentDescription = "Input level"
                    stateDescription = if (clipping) "clipping" else "${db.roundToInt()} decibels"
                },
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(color, MaterialTheme.shapes.small))
        }
        Text(
            when {
                !active -> "Level shows while recording"
                clipping -> "CLIPPING — too loud. Turn the amp down or move ${Device.mic} back."
                db < -40f -> "Very quiet — move ${Device.mic} closer to the amp"
                else -> "Level ${db.roundToInt()} dB — good"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (clipping) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TakeRow(
    take: Recording,
    song: String?,
    picked: Boolean,
    recording: Boolean,
    onPick: () -> Unit,
    onPlay: () -> Unit,
    onStar: () -> Unit,
    onRate: (Int) -> Unit,
    onRename: () -> Unit,
    onNotes: () -> Unit,
    onLink: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onLoop: () -> Unit,
    onDelete: () -> Unit,
) {
    com.riffbuddy.ui.Panel {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).tactileClickable(enabled = !recording, onClickLabel = "Play ${take.name}", onClick = onPlay)) {
                    Text(take.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            if (recording) "Recording now…" else clock(take.durationMs),
                            DATE.format(take.createdAt.atZone(ZoneId.systemDefault())),
                            song,
                            take.source.takeIf { it != TakeSource.NONE }?.label?.lowercase(),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (take.notes.isNotBlank()) {
                        Text(take.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
                TextButton(onClick = onStar, modifier = Modifier.semantics { stateDescription = if (take.starred) "starred" else "not starred" }) {
                    Text(if (take.starred) "★" else "☆", style = MaterialTheme.typography.titleLarge)
                }
                FilterChip(selected = picked, onClick = onPick, label = { Text(if (picked) "Picked" else "A/B") }, enabled = !recording)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                (1..5).forEach { r ->
                    Text(
                        if ((take.rating ?: 0) >= r) "●" else "○",
                        modifier = Modifier
                            .selectable(selected = take.rating == r, role = Role.RadioButton) { onRate(r) }
                            .padding(horizontal = 6.dp, vertical = 10.dp)
                            .semantics { contentDescription = "Rate $r of 5" },
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Wraps rather than scrolls. It scrolled sideways, and on a narrow
            // phone "Song" and "Delete" sat past the right edge with nothing
            // to say the row moved: a take could not visibly be deleted.
            FlowRow {
                TextButton(onClick = onPlay, enabled = !recording) { Text("Play") }
                TextButton(onClick = onLoop, enabled = !recording) { Text("Loop it") }
                TextButton(onClick = onShare, enabled = !recording) { Text("Share") }
                TextButton(onClick = onSave, enabled = !recording) { Text("Save") }
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onNotes) { Text("Notes") }
                TextButton(onClick = onLink) { Text("Song") }
                TextButton(onClick = onDelete, enabled = !recording) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun TextDialog(title: String, initial: String, singleLine: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = singleLine,
                modifier = if (singleLine) Modifier else Modifier.heightIn(min = 120.dp))
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SongPicker(songs: List<Song>, current: Long?, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    var choice by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which song?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (listOf<Pair<Long?, String>>(null to "No particular song") + songs.sortedBy { it.title.lowercase() }.map { it.id to it.title })
                    .forEach { (id, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = choice == id, role = Role.RadioButton) { choice = id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == id, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(choice) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")

fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** The countdowns offered before a take, in seconds; 0 is none. */
private val COUNTDOWNS = listOf(0, 3, 5, 10)

/**
 * Stop & delete, in the error color so it isn't mistaken for Stop. No
 * confirmation, like Start over: it's for the moment a take has already
 * gone wrong, and the take so far is all it can lose.
 */
@Composable
private fun StopAndDelete(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 56.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text("✕ Stop & delete") }
}
