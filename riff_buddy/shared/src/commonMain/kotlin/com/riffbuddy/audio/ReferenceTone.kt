package com.riffbuddy.audio

import com.riffbuddy.platform.AudioLoop
import com.riffbuddy.platform.Volatile
import com.riffbuddy.platform.startAudioLoop
import kotlin.math.PI
import kotlin.math.sin

/**
 * Reference tones: hear what a string should sound like.
 *
 * Matters more than it looks. A microphone tuner is useless when the room is
 * loud, the string is too dead to read, or permission has been refused — and
 * tuning by ear against a reference is how guitarists managed for a century.
 * This is the fallback when the microphone is unavailable, so it has to work
 * on its own.
 */
class ReferenceTone(private val outputs: AudioOutputs, private val sampleRate: Int = 48_000) {

    @Volatile private var playing: AudioLoop? = null
    @Volatile private var stopRequested = false

    /** Plays [frequency] for [millis], or until [stop]. */
    fun play(frequency: Double, millis: Int = 2_000) {
        stop()
        stopRequested = false
        playing = startAudioLoop("reference-tone", urgent = false) {
            runCatching { render(frequency, millis) }
        }
    }

    /** "Play all strings", low to high. */
    fun playAll(frequencies: List<Double>, eachMillis: Int = 900) {
        stop()
        stopRequested = false
        playing = startAudioLoop("reference-tone-all", urgent = false) {
            runCatching {
                frequencies.forEach { frequency ->
                    if (stopRequested) return@runCatching
                    render(frequency, eachMillis)
                }
            }
        }
    }

    companion object {
        const val DRONE_MAX_MILLIS = 10 * 60 * 1000
    }

    fun stop() {
        stopRequested = true
        playing?.join(300)
        playing = null
    }

    /** A drone: sounds until [stop], up to ten minutes. */
    fun drone(frequency: Double) = play(frequency, DRONE_MAX_MILLIS)

    private suspend fun render(frequency: Double, millis: Int) {
        // In Long: a ten-minute drone is 48 000 × 600 000 before the division,
        // which overflows an Int — the first drone came out negative and silent.
        var frames = (sampleRate.toLong() * millis / 1000).toInt()
        val track = outputs.open(sampleRate, StreamKind.SONIFICATION) { maxOf(it, 4096) }

        try {
            val chunk = ShortArray(1024)
            var written = 0
            var started = false
            // Fade in and out over 20 ms. A tone that starts or stops at full
            // amplitude clicks, and a click is a broadband transient — the one
            // sound most likely to make the tuner misread while it plays.
            val fade = sampleRate / 50

            while (written < frames) {
                // Stopping shortens the tone to a fade from here, rather than
                // cutting it mid-wave: a drone released by lifting a finger
                // would otherwise end in a click every time.
                if (stopRequested && frames > written + fade) frames = written + fade
                for (i in chunk.indices) {
                    val index = written + i
                    if (index >= frames) { chunk[i] = 0; continue }
                    val envelope = when {
                        index < fade -> index.toDouble() / fade
                        index > frames - fade -> (frames - index).toDouble() / fade
                        else -> 1.0
                    }
                    // A touch of second harmonic: a pure sine is hard to hear
                    // against a guitar, and hard to pitch-match by ear.
                    val value = sin(2 * PI * frequency * index / sampleRate) +
                        0.22 * sin(4 * PI * frequency * index / sampleRate)
                    chunk[i] = (value * envelope * 0.32 * Short.MAX_VALUE).toInt().toShort()
                }
                track.write(chunk, 0, chunk.size)
                if (!started) { track.play(); started = true }
                written += chunk.size
            }
            if (started) runCatching { track.stop() }
        } finally {
            track.release()
        }
    }
}
