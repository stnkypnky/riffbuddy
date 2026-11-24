package com.riffbuddy.audio

import com.riffbuddy.domain.music.Phrase
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.random.Random

/**
 * A plucked string, synthesized: Karplus–Strong.
 *
 * A picked string is a wave running up and down the string, losing a little
 * of its brightness at each end. This does the same with a buffer exactly
 * one vibration long: fill it with noise (the pick), then play it round and
 * round, averaging each sample with the one after it as it goes. The loop's
 * length is the pitch, and the averaging is the string going mellow and
 * dying away — high notes faster than low ones, just as on a guitar.
 *
 * Generated rather than sampled for the same reasons as the clicks
 * (ClickSynth): no files, nothing to license, and any pitch in any tuning.
 *
 * Pure Kotlin, so tested on the JVM. It renders a whole [Phrase] into one
 * array before anything plays, every note at its own sample index — the rule
 * the metronome follows, so a scale run can't drift or stutter.
 */
object PluckSynth {

    /** How long a note keeps ringing after the phrase lets go of it. */
    private const val RELEASE_MS = 90

    /** Silence on the end, so the last strum dies away instead of stopping. */
    const val TAIL_MS = 1_400

    fun render(phrase: Phrase, sampleRate: Int, a4Hz: Double = 440.0): ShortArray {
        val total = (phrase.lengthMs + TAIL_MS).toLong() * sampleRate / 1000
        val mix = FloatArray(total.toInt())
        phrase.notes.forEach { note ->
            val start = (note.atMs.toLong() * sampleRate / 1000).toInt()
            // The last notes ring on into the tail; the others are damped
            // when the phrase moves on, like a finger lifting.
            val held = note.atMs + note.lengthMs >= phrase.lengthMs
            val frames = if (held) mix.size - start
            else ((note.lengthMs + RELEASE_MS).toLong() * sampleRate / 1000).toInt()
            val releaseAt = if (held) Int.MAX_VALUE else (note.lengthMs.toLong() * sampleRate / 1000).toInt()
            pluck(mix, start, frames, releaseAt, frequency(note.midi, a4Hz), note.velocity, sampleRate, note.midi)
        }
        return toPcm(mix)
    }

    fun frequency(midi: Int, a4Hz: Double): Double = a4Hz * 2.0.pow((midi - 69) / 12.0)

    /**
     * One string, added into [mix] from [start].
     *
     * The loop is the whole instrument: a delay line, a two-point average (the
     * damping), and an all-pass filter that makes up the fraction of a sample
     * the pitch needs. Without it the loop can only be a whole number of
     * samples, and a high E at the twelfth fret comes out ten cents sharp —
     * wrong enough to teach a bad ear.
     */
    private fun pluck(
        mix: FloatArray,
        start: Int,
        frames: Int,
        releaseAt: Int,
        frequency: Double,
        velocity: Float,
        sampleRate: Int,
        seed: Int,
    ) {
        // The averaging adds half a sample of delay; the all-pass makes up
        // the rest, and is best-behaved between 0.1 and 1.1 samples.
        val period = sampleRate / frequency
        var length = floor(period - 0.5).toInt()
        var fraction = period - 0.5 - length
        if (fraction < 0.1) { length -= 1; fraction += 1.0 }
        if (length < 2) return
        val coefficient = ((1 - fraction) / (1 + fraction)).toFloat()

        // How much each trip round the loop keeps, so that a note rings for
        // about four seconds before the averaging takes its share too.
        val keep = 10.0.pow(-3.0 / (frequency * 4.0)).toFloat()
        // Lifting the finger: the string stops in about 60 ms.
        val damped = 10.0.pow(-3.0 / (frequency * 0.06)).toFloat()

        // The pick: noise, softened once so it sounds like a pick on a string
        // rather than a burst of hiss, with its average taken out so the note
        // doesn't start with a thump. Seeded, so the same phrase renders the
        // same samples every time (and in the tests).
        val random = Random(seed)
        val line = FloatArray(length) { random.nextFloat() * 2 - 1 }
        for (i in 1 until length) line[i] = 0.5f * (line[i] + line[i - 1])
        val mean = line.average().toFloat()
        for (i in line.indices) line[i] -= mean

        var index = 0
        var previous = 0f
        var allPassIn = 0f
        var allPassOut = 0f
        val end = minOf(mix.size, start + frames)
        for (at in start until end) {
            val sample = line[index]
            val averaged = 0.5f * (sample + previous)
            previous = sample
            val shifted = coefficient * averaged + allPassIn - coefficient * allPassOut
            allPassIn = averaged
            allPassOut = shifted
            line[index] = shifted * (if (at - start >= releaseAt) damped else keep)
            if (++index == length) index = 0
            mix[at] += sample * velocity
        }
    }

    /**
     * Mixed voices can add up past full scale — six strings struck together
     * do. Rather than clip, the whole phrase is turned down together, which
     * keeps every note at the same level against the others.
     */
    private fun toPcm(mix: FloatArray): ShortArray {
        var peak = 0f
        for (v in mix) peak = maxOf(peak, abs(v))
        val gain = if (peak > 0f) minOf(0.45f, 0.8f / peak) else 0f
        return ShortArray(mix.size) { (mix[it] * gain * Short.MAX_VALUE).toInt().toShort() }
    }
}
