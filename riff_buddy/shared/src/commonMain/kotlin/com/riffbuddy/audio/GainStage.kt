package com.riffbuddy.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Input gain for the recorder, as pure arithmetic.
 *
 * **Why it exists.** The recorder asks for the microphone's raw signal with
 * automatic gain off, which is right — AGC would pump a guitar — but a raw
 * phone mic is quiet: a typical take peaks around −22 dBFS, about 16 dB below
 * where a recording should sit, which means pushing the amp very loud just to
 * hear the take back. This is the gain the phone's own processing would have
 * added, set once by hand instead of chasing every note.
 *
 * **What it cannot do.** Gain raises the room with the guitar. When the room
 * is only 29 dB under the playing, the fix is the microphone nearer the
 * speaker, or recording the amp's own output, not a setting.
 *
 * Capture is floating point, so a quiet signal is boosted before it is rounded
 * to 16 bits rather than after — no detail is thrown away by the boost.
 */
object GainStage {

    const val MAX_DB = 30f
    /**
     * A cut as well as a boost: a computer's microphone, with the system's own
     * input level turned up, can arrive hotter than a recording should sit,
     * and raw capture has nothing else to bring it down.
     */
    const val MIN_DB = -20f
    /** Where "Set level" aims the loudest peak: room to play harder than that. */
    const val TARGET_PEAK_DB = -6f

    fun linear(db: Float): Float = 10f.pow(db / 20f)

    /**
     * Applies [gainDb] to float samples and writes 16-bit. Returns true if any
     * sample clipped — measured *after* the gain, since that is what reaches
     * the file.
     */
    fun process(input: FloatArray, count: Int, gainDb: Float, out: ShortArray): Boolean {
        val g = linear(gainDb)
        var clipped = false
        for (i in 0 until count) {
            val v = input[i] * g
            if (abs(v) >= 0.995f) clipped = true
            out[i] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return clipped
    }

    /**
     * The gain that puts a measured raw peak at [TARGET_PEAK_DB], from a cut
     * of [MIN_DB] for a signal that arrives too hot to a boost of [MAX_DB].
     */
    fun suggest(rawPeak: Float): Float {
        if (rawPeak <= 0f) return MAX_DB
        val peakDb = 20f * log10(rawPeak)
        return (TARGET_PEAK_DB - peakDb).coerceIn(MIN_DB, MAX_DB)
    }
}
