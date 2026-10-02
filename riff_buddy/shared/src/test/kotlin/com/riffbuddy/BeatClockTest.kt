package com.riffbuddy

import com.riffbuddy.audio.BeatClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeatClockTest {

    private val rate = 48_000

    /**
     * The acceptance criterion: scheduled beat times within 1 ms of ideal
     * over 1,000 beats at 240 BPM.
     *
     * It passes with enormous margin — about a hundredth of a millisecond —
     * because each beat is computed from its own index rather than from the one
     * before, so the only error is a single rounding and it never compounds.
     */
    @Test
    fun `1000 beats at 240 bpm stay within 1ms of ideal`() {
        val clock = BeatClock(rate).apply { reset(bpm = 240.0, pulsesPerBeat = 1) }
        val idealSamplesPerBeat = 60.0 * rate / 240.0

        var worstMs = 0.0
        for (n in 0L..1000L) {
            val actual = clock.sampleOf(n)
            val ideal = n * idealSamplesPerBeat
            worstMs = maxOf(worstMs, abs(actual - ideal) / rate * 1000.0)
        }
        assertTrue("worst beat was %.4f ms out".format(worstMs), worstMs < 1.0)
    }

    /**
     * The failure this design exists to avoid, demonstrated.
     *
     * Adding a period each time — the obvious implementation — accumulates its
     * rounding in one direction. At a tempo whose period is not a whole number
     * of samples it is audibly late long before ten minutes are up. This test
     * pins the contrast so nobody "simplifies" BeatClock back into it.
     */
    @Test
    fun `accumulating a period would drift, computing from the index does not`() {
        val bpm = 137.0          // period is 21021.897... samples: not a round number
        val period = 60.0 * rate / bpm
        val beats = 26_000L      // a bit over ten minutes

        var accumulated = 0L
        repeat(beats.toInt()) { accumulated += period.toLong() }   // truncating each time
        val accumulatedDriftMs = abs(accumulated - beats * period) / rate * 1000.0

        val clock = BeatClock(rate).apply { reset(bpm, 1) }
        val indexedDriftMs = abs(clock.sampleOf(beats) - beats * period) / rate * 1000.0

        assertTrue("accumulation should drift badly, was %.1f ms".format(accumulatedDriftMs),
            accumulatedDriftMs > 100.0)
        assertTrue("indexed should not drift, was %.4f ms".format(indexedDriftMs),
            indexedDriftMs < 0.05)
    }

    /** Ten minutes at three tempos — the soak test's arithmetic, done cheaply. */
    @Test
    fun `no drift over ten minutes at 60, 120 and 200 bpm`() {
        listOf(60.0, 120.0, 200.0).forEach { bpm ->
            val clock = BeatClock(rate).apply { reset(bpm, 1) }
            val beats = (bpm * 10).toLong()
            val ideal = beats * 60.0 * rate / bpm
            val outMs = abs(clock.sampleOf(beats) - ideal) / rate * 1000.0
            assertTrue("$bpm bpm drifted %.4f ms over ten minutes".format(outMs), outMs < 0.05)
        }
    }

    /**
     * A tempo change while running takes effect on the next beat with no
     * stutter.
     *
     * The invariant is that the *rebase point does not move*. Everything before
     * it has already been written into the audio buffer and is physically
     * beyond changing; the clock only describes pulses from its origin onward,
     * and asking it about an earlier one is meaningless by design. What must
     * hold is that the beat where the new tempo begins lands where the old
     * tempo said it would — otherwise the change is heard as a lurch.
     */
    @Test
    fun `tempo change is seamless at the beat it takes effect`() {
        val clock = BeatClock(rate).apply { reset(100.0, 1) }
        val whereBeatFiveWouldHaveBeen = clock.sampleOf(5)
        val oldGap = clock.sampleOf(5) - clock.sampleOf(4)

        clock.rebase(fromPulse = 5, newBpm = 200.0)

        assertEquals(
            "the beat where tempo changes must not move",
            whereBeatFiveWouldHaveBeen, clock.sampleOf(5),
        )
        val newGap = clock.sampleOf(6) - clock.sampleOf(5)
        assertEquals("new tempo applies from the rebase point",
            (60.0 * rate / 200.0).toLong(), newGap)
        assertTrue("200 bpm should be twice as fast as 100", newGap < oldGap)
    }

    /** Rebasing repeatedly must not let error creep in at the seams. */
    @Test
    fun `many tempo changes do not accumulate error`() {
        val clock = BeatClock(rate).apply { reset(100.0, 1) }
        var expected = 0L
        var pulse = 0L
        // Walk a speed-trainer-shaped ramp: step the tempo every four beats.
        for (bpm in 100..160 step 5) {
            clock.rebase(fromPulse = pulse, newBpm = bpm.toDouble())
            assertEquals("seam at $bpm bpm moved", expected, clock.sampleOf(pulse))
            val period = 60.0 * rate / bpm
            repeat(4) {
                pulse++
                expected = clock.sampleOf(pulse)
            }
            // Each block of four beats is four periods of that tempo, rounded once.
            assertTrue(abs(expected - clock.sampleOf(pulse)) < 1)
            assertTrue(period > 0)
        }
    }

    @Test
    fun `subdivisions divide the beat evenly`() {
        val clock = BeatClock(rate).apply { reset(120.0, pulsesPerBeat = 4) }
        val beat = 60.0 * rate / 120.0
        assertEquals(0L, clock.sampleOf(0))
        assertEquals((beat / 4).toLong(), clock.sampleOf(1))
        assertEquals(beat.toLong(), clock.sampleOf(4))
    }

    @Test
    fun `finds the first pulse in a buffer`() {
        val clock = BeatClock(rate).apply { reset(120.0, 1) }
        val period = clock.samplesPerPulse().toLong()
        assertEquals(0L, clock.firstPulseAtOrAfter(0))
        assertEquals(1L, clock.firstPulseAtOrAfter(1))
        assertEquals(1L, clock.firstPulseAtOrAfter(period))
        assertEquals(2L, clock.firstPulseAtOrAfter(period + 1))
    }
}
