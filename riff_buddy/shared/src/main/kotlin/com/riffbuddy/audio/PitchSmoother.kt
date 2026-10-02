package com.riffbuddy.audio

import kotlin.math.abs

/**
 * Steadies the needle, so it does not jitter on a steady note.
 *
 * Two jobs, and they pull in opposite directions. A raw YIN reading wobbles by
 * a cent or two frame to frame, which makes a needle twitch and a guitarist
 * chase it. But a heavily smoothed reading lags, and a tuner that answers half a
 * second late is worse than one that twitches — you turn the peg past the point
 * and come back.
 *
 * So: a median of recent frames to kill outliers, and a jump detector that
 * throws the history away when the pitch really has changed. Moving to the next
 * string must be instant; sitting on one must be still.
 */
class PitchSmoother(
    private val window: Int = 5,
    /** A change this large is a new note, not noise. A semitone is 100 cents. */
    private val jumpCents: Double = 60.0,
) {

    private val recent = ArrayDeque<Double>()

    /** Feeds a raw reading and returns the steadied one, or null while unsure. */
    fun accept(frequency: Double?): Double? {
        if (frequency == null) {
            // One dropped frame is a pick lifting, not the note ending. Losing
            // the history here would make the needle restart on every dip.
            return recent.lastOrNull()?.let { median() }
        }

        val previous = recent.lastOrNull()
        if (previous != null && abs(cents(frequency, previous)) > jumpCents) {
            recent.clear()
        }

        recent.addLast(frequency)
        while (recent.size > window) recent.removeFirst()

        // Below half the window there is not enough to be confident, and a
        // confident wrong answer is the thing to avoid.
        return if (recent.size >= (window + 1) / 2) median() else null
    }

    fun reset() = recent.clear()

    private fun median(): Double {
        val sorted = recent.sorted()
        return sorted[sorted.size / 2]
    }

    private fun cents(a: Double, b: Double): Double =
        1200 * (kotlin.math.ln(a / b) / kotlin.math.ln(2.0))
}

/**
 * Guided tuning: walk the strings low to high, advancing when one holds
 * in tune.
 *
 * "Holds" is the important word. A string crosses in-tune on its way past while
 * being turned, and advancing on that instant would march ahead of the player —
 * so it has to stay there for a moment first.
 */
class GuidedTuning(private val stringCount: Int, private val holdFrames: Int = 8) {

    var index: Int = 0
        private set

    private var framesInTune = 0

    var finished: Boolean = false
        private set

    /** Returns true when this call completed a string and moved on. */
    fun accept(inTune: Boolean): Boolean {
        if (finished) return false
        if (!inTune) {
            framesInTune = 0
            return false
        }
        framesInTune++
        if (framesInTune < holdFrames) return false

        framesInTune = 0
        if (index >= stringCount - 1) {
            finished = true
        } else {
            index++
        }
        return true
    }

    fun restart() {
        index = 0
        framesInTune = 0
        finished = false
    }

    /** Lets the player jump to a string rather than waiting for the sequence. */
    fun select(target: Int) {
        index = target.coerceIn(0, stringCount - 1)
        framesInTune = 0
        finished = false
    }
}
