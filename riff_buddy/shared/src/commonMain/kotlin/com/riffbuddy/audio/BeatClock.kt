package com.riffbuddy.audio

import kotlin.math.roundToLong

/**
 * Where every pulse lands in the sample stream.
 *
 * This is the whole reason the metronome does not drift, so it is worth being
 * explicit about what it does *not* do.
 *
 * The tempting implementation is to keep a running position and add a period to
 * it each pulse: `next += 60.0 / bpm`. Every pulse then inherits the rounding
 * error of the one before, the errors accumulate in one direction, and ten
 * minutes later the click is audibly late. Correcting afterwards does not help,
 * because the total is already wrong.
 *
 * Instead every pulse is computed from its own index, straight from the origin.
 * Pulse 10,000 is derived from 10,000 — not from pulse 9,999 — so there is no
 * total for error to gather in. The worst any pulse can be out is half a sample
 * from rounding, about 0.01 ms at 48 kHz, and it never compounds.
 *
 * A tempo change does not restart the count. It moves the *origin* to the next
 * pulse and starts counting from there at the new rate, which is how a tempo
 * change takes effect on the next beat with no stutter: nothing already
 * scheduled moves, and nothing is dropped.
 */
class BeatClock(private val sampleRate: Int) {

    /** Pulse index the origin refers to. */
    var originPulse: Long = 0
        private set

    /** Sample position of [originPulse] in the output stream. */
    var originSample: Long = 0
        private set

    var bpm: Double = 100.0
        private set

    var pulsesPerBeat: Int = 1
        private set

    fun reset(bpm: Double, pulsesPerBeat: Int, startSample: Long = 0) {
        this.bpm = bpm
        this.pulsesPerBeat = pulsesPerBeat
        originPulse = 0
        originSample = startSample
    }

    /** Samples between one pulse and the next. Not rounded — see [sampleOf]. */
    fun samplesPerPulse(): Double = 60.0 * sampleRate / (bpm * pulsesPerBeat)

    /** Where pulse [index] sounds, in samples from the start of the stream. */
    fun sampleOf(index: Long): Long =
        originSample + ((index - originPulse) * samplesPerPulse()).roundToLong()

    /**
     * Change tempo, or subdivision, from [fromPulse] onward.
     *
     * Everything before [fromPulse] keeps the position it was already given —
     * that is what stops a tempo change from shifting a click that has already
     * been written into the buffer.
     */
    fun rebase(fromPulse: Long, newBpm: Double = bpm, newPulsesPerBeat: Int = pulsesPerBeat) {
        originSample = sampleOf(fromPulse)
        originPulse = fromPulse
        bpm = newBpm
        pulsesPerBeat = newPulsesPerBeat
    }

    /** First pulse at or after sample [sample]; used to fill the next buffer. */
    fun firstPulseAtOrAfter(sample: Long): Long {
        if (sample <= originSample) return originPulse
        val pulses = (sample - originSample) / samplesPerPulse()
        var candidate = originPulse + pulses.toLong()
        // Rounding can put the candidate a hair early; step forward if so.
        while (sampleOf(candidate) < sample) candidate++
        while (candidate > originPulse && sampleOf(candidate - 1) >= sample) candidate--
        return candidate
    }
}
