package com.riffbuddy.ui.recorder

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.riffbuddy.audio.LooperEngine
import com.riffbuddy.audio.MetronomeEngine
import com.riffbuddy.audio.RecorderEngine
import com.riffbuddy.domain.TakeSource
import kotlinx.coroutines.delay

/** What plays while a take records, and how the take is lined up with it. */
open class PlayAlongControls {
    var source by mutableStateOf(TakeSource.NONE)
        protected set

    @Composable
    open fun Picker() {}

    /** Called once recording has begun. Starts the source when the input clock is known. */
    open suspend fun start(recorder: RecorderEngine): Long? = null

    /** Stops the source. Returns where in the take it began, in ms, or null. */
    open fun stop(): Long? = null
}

/**
 * Play-along with the metronome or the looper — the two players whose output
 * this app streams itself, and so can timestamp.
 *
 * **How the take is lined up.** Each player reports when its stream's first
 * sample left the speaker (OutputClock); the recorder reports when each input
 * frame was captured, on the same clock. So the frame of the take at which the
 * backing began is known, and stored as the take's align point. Calibration's
 * offset then corrects what the timestamps miss.
 *
 * **Not the tab player.** alphaTab renders its own audio and exposes no output
 * timing, so a take against it could not be lined up. That part is left out,
 * and said so, rather than done approximately.
 */
class PlayAlong(
    private val metronome: MetronomeEngine,
    private val looper: LooperEngine,
    private val looperTitle: () -> String?,
) : PlayAlongControls() {

    private var recorder: RecorderEngine? = null
    private var playing = TakeSource.NONE

    @Composable
    override fun Picker() {
        val looperName = looperTitle()
        // Collected, not read once: a plain .value here would show the tempo
        // as it was when the screen opened, however the metronome changed.
        val bpm = metronome.state.collectAsState().value.settings.bpm
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Play along with", style = MaterialTheme.typography.labelLarge)
            com.riffbuddy.ui.ChipFlow {
                FilterChip(selected = source == TakeSource.NONE, onClick = { source = TakeSource.NONE }, label = { Text("Nothing") })
                FilterChip(
                    selected = source == TakeSource.METRONOME,
                    onClick = { source = TakeSource.METRONOME },
                    label = { Text("Metronome, $bpm BPM") },
                )
                FilterChip(
                    selected = source == TakeSource.LOOPER,
                    onClick = { source = TakeSource.LOOPER },
                    enabled = looperName != null,
                    label = { Text(looperName?.let { "Looper: $it" } ?: "Looper (open a recording first)") },
                )
            }
            // A headphones tip, before play-along recording.
            if (source != TakeSource.NONE) {
                Text(
                    "Use wired headphones. Through the speaker, the backing is recorded along " +
                        "with you. Bluetooth adds a delay of its own that calibration on the speaker " +
                        "does not cover.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }

    override suspend fun start(recorder: RecorderEngine): Long? {
        this.recorder = recorder
        if (source == TakeSource.NONE) return null
        // The source must start after the input has a clock, so the align point
        // falls inside the take and the whole backing is in it.
        var waited = 0
        while (!recorder.clockKnown && waited < 2000) { delay(20); waited += 20 }
        when (source) {
            TakeSource.METRONOME -> {
                // Restarted, not joined: a metronome already running began
                // before the take, and its stream start would be meaningless.
                metronome.stop()
                metronome.start(metronome.state.value.settings)
            }
            TakeSource.LOOPER -> {
                looper.pause()
                looper.play()
            }
            TakeSource.NONE -> Unit
        }
        playing = source
        return null
    }

    override fun stop(): Long? {
        val rec = recorder ?: return null
        val startNanos = when (playing) {
            TakeSource.METRONOME -> metronome.outputClock.streamStartNanos
            TakeSource.LOOPER -> looper.outputClock.streamStartNanos
            TakeSource.NONE -> null
        }
        when (playing) {
            TakeSource.METRONOME -> metronome.stop()
            TakeSource.LOOPER -> looper.pause()
            TakeSource.NONE -> Unit
        }
        playing = TakeSource.NONE
        recorder = null
        val frame = startNanos?.let { rec.inputFrameAt(it) } ?: return null
        return (frame * 1000 / rec.sampleRate).coerceAtLeast(0)
    }
}

private typealias Modifier = androidx.compose.ui.Modifier
