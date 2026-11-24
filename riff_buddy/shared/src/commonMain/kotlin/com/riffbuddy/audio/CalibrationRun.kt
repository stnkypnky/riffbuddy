package com.riffbuddy.audio

import com.riffbuddy.platform.Blocking
import com.riffbuddy.platform.delete
import com.riffbuddy.platform.nanoTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.Path

/**
 * One latency calibration: ten clicks through the speaker, recorded
 * through the microphone, measured by [LatencyCalibration].
 *
 * Uses the real recorder and a real output stream — the same two paths a
 * play-along take uses — so what it measures is what a take will suffer.
 */
class CalibrationRun(
    private val recorder: RecorderEngine,
    private val scratch: Path,
    private val outputs: AudioOutputs,
) {

    sealed interface Outcome {
        data class Measured(val result: LatencyCalibration.Result, val hardwareClocks: Boolean) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Caller must already hold RECORD_AUDIO. Takes about six seconds. */
    suspend fun run(): Outcome = withContext(Dispatchers.Blocking) {
        val sr = recorder.sampleRate
        val clicks = LatencyCalibration.clickFrames(sr)
        val clock = OutputClock()
        // Always the phone's own microphone, at unity gain: the clicks come out
        // of the phone's speaker, and the amp's headphone output would never hear them.
        val input = recorder.inputId
        val gain = recorder.gainDb
        recorder.inputId = null
        recorder.gainDb = 0f
        try {
            recorder.start(recordingId = -1, file = scratch)
            // Output starts only once input has a clock, or the first clicks
            // would land before the recording has any idea what time it is.
            var waited = 0
            while (!recorder.clockKnown && waited < 2000) { delay(20); waited += 20 }
            if (!recorder.clockKnown) return@withContext Outcome.Failed("The microphone did not start.")

            playClicks(sr, clicks, clock)
            delay(400)   // let the last click's echo arrive
        } finally {
            recorder.stop()
            recorder.inputId = input
            recorder.gainDb = gain
        }

        val start = clock.streamStartNanos
            ?: return@withContext Outcome.Failed("The speaker did not report when it played.")
        val predicted = clicks.map { frame ->
            recorder.inputFrameAt(start + frame * 1_000_000_000L / sr) ?: return@withContext Outcome.Failed("Lost the recording clock.")
        }
        val pcm = runCatching { Wav.read(scratch) }.getOrElse {
            return@withContext Outcome.Failed("Could not read the calibration recording.")
        }
        scratch.delete()
        val result = LatencyCalibration.measure(pcm.samples, sr, predicted)
            ?: return@withContext Outcome.Failed(
                "No clicks were heard. Unplug headphones, turn the volume up, and try again somewhere quiet."
            )
        Outcome.Measured(result, recorder.hardwareClock && clock.hardwareClock)
    }

    private suspend fun playClicks(sr: Int, clicks: List<Long>, clock: OutputClock) {
        val sound = ClickSynth.render(ClickSound.CLICK, Accent.ACCENT, true, sr)
        val total = (clicks.last() + sr / 2).toInt()
        val stream = ShortArray(total)
        clicks.forEach { at -> for (i in sound.indices) if (at + i < total) stream[(at + i).toInt()] = sound[i] }

        val track = outputs.open(sr, StreamKind.SONIFICATION) { it * 2 }
        try {
            val block = 480
            var pos = 0
            var started = false
            val prime = (track.bufferFrames - block).coerceAtLeast(block)
            while (pos < total) {
                val n = minOf(block, total - pos)
                track.write(stream, pos, n)
                pos += n
                if (!started && pos >= prime) { track.play(); clock.played(); started = true }
                if (started) clock.poll(track, sr, every = 5)
            }
            if (!started) { track.play(); clock.played() }
            // Keep polling until the stream has drained, for the best timestamp.
            val end = nanoTime() + 600_000_000L
            while (nanoTime() < end) { clock.poll(track, sr, every = 1); delay(20) }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
