package com.riffbuddy.ui.tone

/**
 * Where a knob's pointer sits for a given value.
 *
 * Pure, so the mapping across 0-10 and custom ranges is unit-tested: getting
 * it wrong is the kind of bug that looks fine in a screenshot and is wrong on
 * the amp.
 */
object KnobGeometry {

    /**
     * A real amp knob sweeps about 270 degrees, from roughly 7 o'clock round to
     * 5 o'clock, with a dead zone at the bottom. A full 360 would put minimum
     * and maximum in the same place — which is precisely the ambiguity the dead
     * zone exists to prevent.
     */
    const val START_DEGREES = 135.0
    const val SWEEP_DEGREES = 270.0

    /** 0.0 at [min], 1.0 at [max], clamped outside. */
    fun fraction(value: Double, min: Double = 0.0, max: Double = 10.0): Double {
        if (max <= min) return 0.0
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    }

    /** Degrees clockwise from three o'clock, as the canvas measures them. */
    fun angleDegrees(value: Double, min: Double = 0.0, max: Double = 10.0): Double =
        START_DEGREES + SWEEP_DEGREES * fraction(value, min, max)
}
