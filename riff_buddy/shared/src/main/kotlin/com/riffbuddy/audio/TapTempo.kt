package com.riffbuddy.audio

import kotlin.math.roundToInt

/**
 * Tap tempo: the average of the last 4-8 taps.
 *
 * Averaging *intervals* rather than taking the last one is what makes it settle
 * instead of jittering — a single unsteady tap moves the answer by a fraction of
 * its error rather than throwing the whole reading.
 *
 * A long gap starts a new count. Without that, coming back to the app an hour
 * later and tapping once would average against an hour-long interval and read 0.
 */
class TapTempo(private val window: Int = 8, private val restartAfterMs: Long = 2_500) {

    private val taps = ArrayDeque<Long>()

    /** Returns the tempo once there are enough taps to have an opinion. */
    fun tap(nowMs: Long): Int? {
        val last = taps.lastOrNull()
        if (last != null && nowMs - last > restartAfterMs) taps.clear()
        taps.addLast(nowMs)
        while (taps.size > window) taps.removeFirst()
        if (taps.size < 2) return null

        val intervals = taps.zipWithNext { a, b -> (b - a).toDouble() }
        val mean = intervals.average()
        if (mean <= 0) return null
        return (60_000.0 / mean).roundToInt().coerceIn(MetronomeSettings.BPM_RANGE)
    }

    /** How many taps are in the current count — the UI shows this as feedback. */
    val tapCount: Int get() = taps.size

    fun reset() = taps.clear()
}
