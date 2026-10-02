package com.riffbuddy.ui.tuner

import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import com.riffbuddy.ui.ConfirmDelete

import kotlinx.coroutines.launch

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riffbuddy.audio.ReferenceTone
import com.riffbuddy.audio.TunerEngine
import com.riffbuddy.domain.music.Tuning

/** A song's tuning and capo, handed to the tuner. */
data class TunerPreset(val tuningId: Long?, val capo: Int, val nonce: Long = System.nanoTime())


/**
 * Owns the tuner's permission and lifecycle.
 *
 * The microphone is requested here, the first time the tuner is opened — never
 * during first-run setup, for a good reason: a permission prompt
 * on first launch, before the app has shown what it is for, is one people deny
 * and then have to be talked through re-enabling.
 */
@Composable
fun TunerHost(
    engine: TunerEngine,
    referenceTone: ReferenceTone,
    tunings: List<Tuning> = Tuning.BUILT_IN,
    /** Opening the tuner from a song preselects that song's tuning. */
    preset: TunerPreset? = null,
    /** Saves a custom tuning; null back means the name is taken. */
    onSaveCustom: suspend (Tuning) -> Long? = { null },
    onDeleteCustom: (Tuning) -> Unit = {},
) {
    val platform = com.riffbuddy.platform.LocalPlatform.current
    val state by engine.state.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(platform.hasPermission(com.riffbuddy.platform.Permission.MICROPHONE)) }
    var tuning by remember { mutableStateOf(Tuning.STANDARD) }
    var capo by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Tuning?>(null) }
    var editProblem by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Tuning?>(null) }
    val scope = rememberCoroutineScope()

    // Applied once per request, keyed on the preset itself: a later visit to
    // the tuner from the Tools tab keeps whatever was picked since.
    LaunchedEffect(preset) {
        preset ?: return@LaunchedEffect
        tunings.firstOrNull { it.id == preset.tuningId }?.let { tuning = it }
        capo = preset.capo
    }
    var guided by remember { mutableStateOf(false) }

    val effective = remember(tuning, capo) { tuning.withCapo(capo) }

    val askForMic = platform.rememberPermissionRequest(com.riffbuddy.platform.Permission.MICROPHONE) { allowed ->
        granted = allowed
        if (allowed) {
            engine.tuning = effective
            engine.start()
        }
    }

    // Listening follows the screen, both ways: opening it starts, leaving it
    // stops. Revoking permission in system settings while the app is alive comes
    // back through the same check on the next visit.
    DisposableEffect(granted) {
        if (granted) {
            engine.tuning = effective
            engine.start()
        }
        onDispose {
            engine.stop()
            referenceTone.stop()
        }
    }

    LaunchedEffect(effective) {
        engine.tuning = effective
    }
    LaunchedEffect(guided) {
        engine.setGuided(guided)
    }

    Column(Modifier.fillMaxSize()) {

        TunerScreen(
            state = state,
            tuning = effective,
            capo = capo,
            hasPermission = granted,
            guided = guided,
            onRequestPermission = askForMic,
            onPickTuning = { tuning = it },
            onCapo = { capo = it },
            onToggleGuided = { guided = !guided },
            onSelectString = { engine.selectString(it) },
            onPlayString = { index ->
                effective.frequencies(engine.a4).getOrNull(index)?.let { referenceTone.play(it) }
            },
            onPlayAll = { referenceTone.playAll(effective.frequencies(engine.a4)) },
            a4Hz = engine.a4.toInt(),
            tunings = tunings,
            onDroneString = { index ->
                effective.frequencies(engine.a4).getOrNull(index)?.let { referenceTone.drone(it) }
            },
            onStopDrone = { referenceTone.stop() },
            onNewTuning = { editProblem = null; editing = tuning.copy(name = tuning.name.substringBefore(", capo"), isCustom = false) },
            onEditTuning = { editProblem = null; editing = it },
        )
        editing?.let { start ->
            TuningEditorDialog(
                start = start,
                problem = editProblem,
                onDismiss = { editing = null },
                onSave = { candidate ->
                    scope.launch {
                        val id = onSaveCustom(candidate)
                        if (id == null) editProblem = "A tuning called \"${candidate.name}\" already exists."
                        else { tuning = candidate.copy(id = id); editing = null }
                    }
                },
                onDelete = if (start.isCustom) {
                    { deleting = start; editing = null }
                } else null,
            )
        }
        deleting?.let { doomed ->
            ConfirmDelete(
                "\"${doomed.name}\"",
                "Songs in this tuning keep everything else; their tuning becomes \"not set\".",
                onConfirm = { onDeleteCustom(doomed); tuning = Tuning.STANDARD },
                onDismiss = { deleting = null },
            )
        }
    }
}
