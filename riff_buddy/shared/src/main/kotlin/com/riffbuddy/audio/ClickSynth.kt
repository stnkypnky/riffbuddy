package com.riffbuddy.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The click sounds, generated rather than shipped as files.
 *
 * Four voices, three strengths each, built as short mono PCM at startup. Two
 * reasons not to bundle WAVs: there is no license to track for a sine wave
 * (LICENSES.md stays honest by staying empty here), and a generated click can
 * be rendered at whatever sample rate the device actually wants, so nothing is
 * resampled on the fly.
 *
 * Every voice is a simple oscillator times an exponential decay. That is enough:
 * a metronome click wants to be short and unambiguous, not pretty. What matters
 * is a sharp attack, because the *start* of the sound is what a player hears as
 * the beat — a slow attack makes a metronome feel late even when it is exact.
 */
object ClickSynth {

    /** How loud each strength is relative to the accent. */
    private fun gainFor(accent: Accent, onBeat: Boolean): Float = when {
        accent == Accent.MUTED -> 0f
        accent == Accent.ACCENT -> 1.0f
        onBeat -> 0.62f
        else -> 0.34f   // a subdivision: present, but clearly underneath
    }

    /** Pitch multiplier, so an accent reads as higher as well as louder. */
    private fun pitchFor(accent: Accent, onBeat: Boolean): Double = when {
        accent == Accent.ACCENT -> 1.5
        onBeat -> 1.0
        else -> 1.18
    }

    /**
     * One voice as 16-bit mono PCM.
     *
     * Returns an empty array for a muted pulse — the engine then writes nothing,
     * which is cheaper than mixing silence and keeps "muted" genuinely silent.
     */
    fun render(
        sound: ClickSound,
        accent: Accent,
        onBeat: Boolean,
        sampleRate: Int,
    ): ShortArray {
        val gain = gainFor(accent, onBeat)
        if (gain == 0f) return ShortArray(0)
        val pitch = pitchFor(accent, onBeat)

        val (baseHz, decayMs, noise) = when (sound) {
            // A dry tick: mostly noise, gone almost immediately.
            ClickSound.CLICK -> Triple(2000.0, 18.0, 0.65)
            // Hollow and wooden: a low-ish tone with a very fast decay.
            ClickSound.WOODBLOCK -> Triple(1150.0, 42.0, 0.12)
            // Bright and hissy: all noise, no pitch to speak of.
            ClickSound.HIHAT -> Triple(6000.0, 34.0, 0.95)
            // A plain tone, for when the others get lost against a loud amp.
            ClickSound.BEEP -> Triple(880.0, 70.0, 0.0)
        }

        val frames = (sampleRate * decayMs / 1000.0).toInt().coerceAtLeast(8)
        val out = ShortArray(frames)
        val hz = baseHz * pitch
        val step = 2 * PI * hz / sampleRate
        // Decay to about -60 dB across the sound's length, so it ends silently
        // and never clicks on the way out.
        val decay = 6.9 / frames
        val random = Random(sound.ordinal * 31 + accent.ordinal)

        for (i in 0 until frames) {
            val envelope = exp(-decay * i)
            val tone = sin(step * i)
            val hiss = random.nextDouble(-1.0, 1.0)
            val mixed = tone * (1 - noise) + hiss * noise
            // 0.9 of full scale: leaves room for two voices to overlap at a
            // fast subdivision without clipping.
            out[i] = (mixed * envelope * gain * Short.MAX_VALUE * 0.9).toInt().toShort()
        }
        return out
    }

    /** Every voice a running metronome can need, rendered once up front. */
    fun renderAll(sound: ClickSound, sampleRate: Int): Map<Pair<Accent, Boolean>, ShortArray> =
        buildMap {
            for (accent in Accent.entries) {
                for (onBeat in listOf(true, false)) {
                    put(accent to onBeat, render(sound, accent, onBeat, sampleRate))
                }
            }
        }
}
