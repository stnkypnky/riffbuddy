package com.riffbuddy.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Measuring recording latency from a take of clicks. Pure, so the
 * ±15 ms criterion is tested against recordings with a known, planted delay.
 *
 * **The setup.** Clicks are played through the speaker at known output frames
 * while the microphone records. Android's timestamps say when each output
 * frame left and each input frame arrived, on one clock — so for every click
 * there is a *predicted* frame in the recording where it should appear. The
 * difference between where each click is actually found and where it was
 * predicted is what the timestamps do not capture: the part of the delay that
 * lives in the hardware. Its median over ten clicks is the latency offset; how
 * much the ten disagree says whether the number can be trusted.
 *
 * **Finding a click.** The recording is differentiated (so a slow rumble does
 * not count as an arrival), rectified, and each click is the first sample in
 * its search window to rise well above the quiet just before the window. The
 * first crossing, not the loudest point: the loudest point of a click through
 * a phone speaker is often a resonance several milliseconds after it began.
 */
object LatencyCalibration {

    /** What a calibration run found. */
    data class Result(
        /** Median residual, ms: added to a take's align point to line it up. */
        val offsetMs: Int,
        /** Largest disagreement of any found click from that median, ms. */
        val spreadMs: Int,
        val found: Int,
        val expected: Int,
    ) {
        /** The pass mark: every click within ±15 ms of the offset. */
        val withinTolerance: Boolean get() = found >= MIN_FOUND && spreadMs <= TOLERANCE_MS
    }

    const val TOLERANCE_MS = 15
    const val MIN_FOUND = 6

    /**
     * [predicted] are the frames in [recording] where each click would appear
     * if the timestamps told the whole story. Searches up to [searchMs] after
     * each (latency is a delay, so later) and a little before, in case the
     * timestamps overshoot.
     */
    fun measure(recording: ShortArray, sampleRate: Int, predicted: List<Long>, searchMs: Int = 250): Result? {
        val residuals = predicted.mapNotNull { p ->
            findOnset(recording, sampleRate, p, searchMs)?.let { ((it - p) * 1000.0 / sampleRate) }
        }
        if (residuals.isEmpty()) return null
        val sorted = residuals.sorted()
        val median = sorted[sorted.size / 2]
        val spread = residuals.maxOf { abs(it - median) }
        return Result(median.roundToInt(), spread.roundToInt(), residuals.size, predicted.size)
    }

    fun findOnset(x: ShortArray, sampleRate: Int, predicted: Long, searchMs: Int): Long? {
        val before = sampleRate * 20 / 1000
        val from = (predicted - before).toInt()
        val to = (predicted + searchMs.toLong() * sampleRate / 1000).toInt()
        if (from < 1 || to >= x.size) return null

        // The noise floor: the quiet stretch before the window.
        val noiseFrom = (from - sampleRate * 30 / 1000).coerceAtLeast(1)
        var noise = 0.0
        for (i in noiseFrom until from) noise = maxOf(noise, abs(x[i] - x[i - 1]).toDouble())

        var peak = 0.0
        for (i in from until to) peak = maxOf(peak, abs(x[i] - x[i - 1]).toDouble())
        // Nothing clearly louder than the room: no click to be found.
        if (peak < maxOf(noise * 4, 300.0)) return null

        // Halfway between the floor and the click's own peak is a threshold
        // that ignores the room and does not wait for the resonance.
        val threshold = noise + (peak - noise) * 0.3
        for (i in from until to) {
            if (abs(x[i] - x[i - 1]) >= threshold) return i.toLong()
        }
        return null
    }

    /** Where the calibration clicks are played: after a settling pause, evenly apart. */
    fun clickFrames(sampleRate: Int, count: Int = 10, firstMs: Int = 600, everyMs: Int = 450): List<Long> =
        (0 until count).map { (firstMs + it * everyMs).toLong() * sampleRate / 1000 }

    /**
     * Converts a time on the shared monotonic clock to a frame of the take,
     * given one anchor the recorder reported: (frame, nanos).
     */
    fun inputFrameAt(nanos: Long, anchorFrame: Long, anchorNanos: Long, sampleRate: Int): Long =
        anchorFrame + (nanos - anchorNanos) * sampleRate / 1_000_000_000L
}
