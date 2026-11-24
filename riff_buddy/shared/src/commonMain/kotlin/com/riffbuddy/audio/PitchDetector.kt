package com.riffbuddy.audio

/**
 * Pitch detection by the YIN algorithm.
 *
 * Pure Kotlin over a float array, which is the point: generated tones from E2
 * to E6 must read within three cents, and that is only testable if the
 * detector never touches a microphone.
 *
 * **Why not a Fourier transform.** An FFT's frequency resolution at a useful
 * window length is roughly 10 Hz — about 200 cents at the guitar's low E, where
 * three cents is the requirement. Getting there by FFT means interpolating
 * peaks and fighting the fact that a plucked string's loudest partial is often
 * not its fundamental. YIN works in the time domain on periodicity instead,
 * which is both more accurate here and immune to that particular trap.
 *
 * The four steps are Cheveigné and Kawahara's: a difference function, a
 * cumulative mean that normalizes it, an absolute threshold, and parabolic
 * interpolation to get between samples.
 */
class PitchDetector(
    private val sampleRate: Int = 48_000,
    /**
     * Below this, a candidate period is accepted. 0.15 is the paper's
     * suggestion; lower is stricter and rejects more, which for a tuner is the
     * right direction — a wrong reading is worse than no reading, because the
     * player will act on it.
     */
    private val threshold: Double = 0.12,
    /** An 8-string's low F#1 is 46.25 Hz, so the window must reach below that. */
    private val minFrequency: Double = 40.0,
    private val maxFrequency: Double = 1600.0,
) {

    /** The lowest note is the longest period, so it sets the window size. */
    val windowSize: Int = (sampleRate / minFrequency).toInt() * 2

    private val minTau = (sampleRate / maxFrequency).toInt().coerceAtLeast(2)
    private val maxTau = (sampleRate / minFrequency).toInt()

    private val difference = DoubleArray(maxTau + 1)
    private val normalized = DoubleArray(maxTau + 1)

    /**
     * Returns the detected frequency, or null when the buffer is not periodic
     * enough to call.
     *
     * Null is a real answer. A tuner that always shows a number will show a
     * number for a cough, a chair scrape, or silence — and the guitarist will
     * turn a peg to chase it.
     */
    fun detect(buffer: FloatArray): Double? {
        if (buffer.size < windowSize) return null

        val half = buffer.size / 2
        val usableTau = minOf(maxTau, half - 1)
        if (usableTau <= minTau) return null

        // Too quiet to be a plucked string. Checked before the expensive part.
        var energy = 0.0
        for (i in 0 until half) energy += buffer[i] * buffer[i]
        if (energy / half < SILENCE_ENERGY) return null

        // 1. Difference function.
        for (tau in 0..usableTau) {
            var sum = 0.0
            for (i in 0 until half) {
                val delta = buffer[i] - buffer[i + tau]
                sum += delta * delta
            }
            difference[tau] = sum
        }

        // 2. Cumulative mean normalized difference. This is what stops the
        //    detector locking onto tau = 0, where the difference is always zero.
        normalized[0] = 1.0
        var runningSum = 0.0
        for (tau in 1..usableTau) {
            runningSum += difference[tau]
            normalized[tau] = if (runningSum == 0.0) 1.0 else difference[tau] * tau / runningSum
        }

        // 3. First dip below the threshold, followed down to its local minimum.
        //    Taking the *first* such dip rather than the global minimum is what
        //    keeps it on the fundamental instead of an octave above.
        var tau = minTau
        while (tau <= usableTau) {
            if (normalized[tau] < threshold) {
                while (tau + 1 <= usableTau && normalized[tau + 1] < normalized[tau]) tau++
                return sampleRate / refine(tau, usableTau)
            }
            tau++
        }
        return null
    }

    /**
     * Parabolic interpolation through the minimum and its neighbors.
     *
     * Without this the period is a whole number of samples, which at the
     * guitar's top E is about 11 cents of quantization — nearly four times the
     * tolerance the requirement asks for.
     */
    private fun refine(tau: Int, maxIndex: Int): Double {
        if (tau <= 0 || tau >= maxIndex) return tau.toDouble()
        val before = normalized[tau - 1]
        val at = normalized[tau]
        val after = normalized[tau + 1]
        val divisor = 2 * (2 * at - after - before)
        if (divisor == 0.0) return tau.toDouble()
        return tau + (after - before) / divisor
    }

    private companion object {
        /** Mean square below this is a room, not a note. */
        const val SILENCE_ENERGY = 1e-6
    }
}
