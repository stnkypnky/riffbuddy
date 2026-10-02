package com.riffbuddy

import com.riffbuddy.audio.GuidedTuning
import com.riffbuddy.audio.PitchSmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PitchSmootherTest {

    @Test
    fun `a wobbling reading settles to the middle`() {
        val smoother = PitchSmoother()
        listOf(110.0, 110.4, 109.7, 110.2, 109.9).forEach { smoother.accept(it) }
        val settled = smoother.accept(110.1)!!
        assertTrue("settled on $settled", abs(settled - 110.0) < 0.3)
    }

    /** A single wild frame must not move the needle. */
    @Test
    fun `one outlier is ignored`() {
        val smoother = PitchSmoother()
        listOf(110.0, 110.1, 109.9, 110.0).forEach { smoother.accept(it) }
        val withOutlier = smoother.accept(220.0)
        // 220 is an octave away, so it counts as a jump and the history clears —
        // which is correct, but the result must then be unsure rather than wrong.
        assertNull("a jump should withhold a reading until it is confirmed", withOutlier)
    }

    /**
     * Moving to the next string has to be instant. Smoothing across a real
     * change would show the previous string's pitch while the player is turning a
     * different peg.
     */
    @Test
    fun `a real pitch change is followed quickly, not averaged`() {
        val smoother = PitchSmoother()
        repeat(6) { smoother.accept(82.41) }        // low E, settled
        smoother.accept(110.0)                       // moved to A
        smoother.accept(110.0)
        val now = smoother.accept(110.0)!!
        assertTrue("should be on A, not between E and A: $now", abs(now - 110.0) < 1.0)
    }

    /** A pick lifting for one frame is not the note ending. */
    @Test
    fun `a single dropped frame holds the last reading`() {
        val smoother = PitchSmoother()
        repeat(5) { smoother.accept(146.83) }
        assertNotNull("a gap should not blank the display", smoother.accept(null))
    }

    @Test
    fun `too few frames gives no reading at all`() {
        val smoother = PitchSmoother(window = 5)
        assertNull(smoother.accept(110.0))
        assertNull(smoother.accept(110.0))
        assertNotNull(smoother.accept(110.0))
    }

    @Test
    fun `reset clears the history`() {
        val smoother = PitchSmoother()
        repeat(5) { smoother.accept(110.0) }
        smoother.reset()
        assertNull(smoother.accept(110.0))
    }
}

class GuidedTuningTest {

    /**
     * A string crosses in-tune while being turned. Advancing on that instant
     * would march ahead of the player, so it has to hold first.
     */
    @Test
    fun `a brief moment in tune does not advance`() {
        val guided = GuidedTuning(stringCount = 6, holdFrames = 8)
        repeat(3) { assertFalse(guided.accept(true)) }
        guided.accept(false)
        repeat(7) { assertFalse(guided.accept(true)) }
        assertEquals(0, guided.index)
    }

    @Test
    fun `holding in tune advances to the next string`() {
        val guided = GuidedTuning(stringCount = 6, holdFrames = 3)
        repeat(2) { guided.accept(true) }
        assertTrue(guided.accept(true))
        assertEquals(1, guided.index)
    }

    @Test
    fun `the last string finishes rather than wrapping`() {
        val guided = GuidedTuning(stringCount = 6, holdFrames = 1)
        repeat(5) { guided.accept(true) }
        assertEquals(5, guided.index)
        guided.accept(true)
        assertTrue(guided.finished)
        assertEquals(5, guided.index)
    }

    @Test
    fun `a string can be chosen directly`() {
        val guided = GuidedTuning(stringCount = 6)
        guided.select(4)
        assertEquals(4, guided.index)
        guided.select(99)
        assertEquals(5, guided.index)
        guided.restart()
        assertEquals(0, guided.index)
        assertFalse(guided.finished)
    }
}
