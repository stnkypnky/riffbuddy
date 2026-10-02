package com.riffbuddy.audio

/**
 * Which pulse of the current bar we are on, across changes of bar length.
 *
 * Exists because the obvious version is wrong. `pulse % pulsesPerBar` is right
 * only while the bar never changes length — switch 4/4 to 3/4 mid-session, or
 * turn on triplets, and every downbeat afterwards lands in the wrong place, with
 * no error and no crash to point at it. Counting from the bar's own start is
 * correct across any change, and this is small enough to test on its own.
 */
class BarCursor(barLength: Int) {

    var barStartPulse: Long = 0
        private set

    var barLength: Int = barLength
        private set

    /** Bars completed since the count began. */
    var barNumber: Int = 0
        private set

    /** True when [pulse] begins a new bar and the cursor should [advance]. */
    fun startsNewBar(pulse: Long): Boolean = pulse - barStartPulse >= barLength

    /** Move to the next bar. [newLength] applies from this bar onward. */
    fun advance(newLength: Int = barLength) {
        barStartPulse += barLength
        barLength = newLength
        barNumber++
    }

    /** Position of [pulse] within the current bar. */
    fun indexOf(pulse: Long): Int = (pulse - barStartPulse).toInt()
}
