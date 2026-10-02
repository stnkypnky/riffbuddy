package com.riffbuddy

import com.riffbuddy.ui.tone.KnobGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Value-to-angle mapping for 0-10 and custom ranges. */
class KnobGeometryTest {

    @Test
    fun `a 0 to 10 dial maps across the full sweep`() {
        assertEquals(135.0, KnobGeometry.angleDegrees(0.0), 0.001)
        assertEquals(270.0, KnobGeometry.angleDegrees(5.0), 0.001)
        assertEquals(405.0, KnobGeometry.angleDegrees(10.0), 0.001)
    }

    /** Minimum and maximum must never land in the same place. */
    @Test
    fun `the sweep leaves a dead zone at the bottom`() {
        val span = KnobGeometry.angleDegrees(10.0) - KnobGeometry.angleDegrees(0.0)
        assertEquals(270.0, span, 0.001)
        assertTrue("a full circle would make 0 and 10 identical", span < 360.0)
    }

    @Test
    fun `custom ranges scale correctly`() {
        // A 1-5 selector.
        assertEquals(135.0, KnobGeometry.angleDegrees(1.0, 1.0, 5.0), 0.001)
        assertEquals(270.0, KnobGeometry.angleDegrees(3.0, 1.0, 5.0), 0.001)
        assertEquals(405.0, KnobGeometry.angleDegrees(5.0, 1.0, 5.0), 0.001)
        // A range that does not start at zero, off-center.
        assertEquals(0.25, KnobGeometry.fraction(3.0, 2.0, 6.0), 0.001)
    }

    @Test
    fun `values outside the range are clamped rather than spun past the stop`() {
        assertEquals(0.0, KnobGeometry.fraction(-4.0), 0.001)
        assertEquals(1.0, KnobGeometry.fraction(99.0), 0.001)
        assertEquals(KnobGeometry.angleDegrees(10.0), KnobGeometry.angleDegrees(14.0), 0.001)
    }

    /** A degenerate range must not divide by zero. */
    @Test
    fun `a zero-width range is handled`() {
        assertEquals(0.0, KnobGeometry.fraction(5.0, 5.0, 5.0), 0.001)
        assertEquals(135.0, KnobGeometry.angleDegrees(5.0, 5.0, 5.0), 0.001)
    }

    @Test
    fun `the mapping is monotonic`() {
        var previous = KnobGeometry.angleDegrees(0.0)
        for (step in 1..10) {
            val angle = KnobGeometry.angleDegrees(step.toDouble())
            assertTrue("angle must increase with value", angle > previous)
            previous = angle
        }
    }
}
