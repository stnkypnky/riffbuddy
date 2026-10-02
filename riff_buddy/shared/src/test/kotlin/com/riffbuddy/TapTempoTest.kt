package com.riffbuddy

import com.riffbuddy.audio.TapTempo
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TapTempoTest {

    /** Tap tempo settles within ±2 BPM after 6 steady taps. */
    @Test
    fun `six steady taps land within two bpm`() {
        listOf(60, 100, 137, 200).forEach { target ->
            val tapper = TapTempo()
            val interval = 60_000L / target
            var now = 0L
            var bpm: Int? = null
            repeat(6) {
                bpm = tapper.tap(now)
                now += interval
            }
            assertTrue("$target bpm read as $bpm", abs(bpm!! - target) <= 2)
        }
    }

    /** A human is not a machine; a few milliseconds of wobble must not matter. */
    @Test
    fun `unsteady taps still settle within two bpm`() {
        val tapper = TapTempo()
        val wobble = listOf(0L, 498L, 1_006L, 1_499L, 2_007L, 2_498L, 3_002L)
        var bpm: Int? = null
        wobble.forEach { bpm = tapper.tap(it) }
        assertTrue("expected about 120, got $bpm", abs(bpm!! - 120) <= 2)
    }

    @Test
    fun `one tap has no opinion`() {
        assertNull(TapTempo().tap(0))
    }

    /** Coming back later and tapping once must not average against the gap. */
    @Test
    fun `a long gap starts a new count`() {
        val tapper = TapTempo()
        tapper.tap(0); tapper.tap(500); tapper.tap(1_000)
        assertNull("the first tap after a long gap starts over", tapper.tap(60_000))
        assertTrue(tapper.tapCount == 1)
    }

    /** Only the last few taps count, so speeding up is followed rather than averaged. */
    @Test
    fun `the window forgets old taps`() {
        val tapper = TapTempo(window = 4)
        var now = 0L
        repeat(4) { tapper.tap(now); now += 1_000L }          // 60 bpm
        repeat(4) { tapper.tap(now); now += 500L }            // then 120
        val bpm = tapper.tap(now)
        assertTrue("should have followed to about 120, got $bpm", abs(bpm!! - 120) <= 3)
    }
}
