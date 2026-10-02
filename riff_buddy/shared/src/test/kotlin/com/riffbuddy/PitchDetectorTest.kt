package com.riffbuddy

import com.riffbuddy.audio.PitchDetector
import com.riffbuddy.domain.music.Notes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generated and recorded test tones from E2 (82.41 Hz) to E6 (1318.5 Hz) read
 * within ±3 cents in an automated test.
 *
 * This is why the detector is pure Kotlin over a float array — the requirement
 * is only testable if it never touches a microphone.
 */
class PitchDetectorTest {

    private val rate = 48_000
    private val detector = PitchDetector(sampleRate = rate)

    private fun tone(
        frequency: Double,
        harmonics: Int = 1,
        noise: Double = 0.0,
        samples: Int = detector.windowSize * 2,
    ): FloatArray {
        val random = Random(1234)
        return FloatArray(samples) { i ->
            var value = 0.0
            for (h in 1..harmonics) {
                // A plucked string's partials fall away; this roughly imitates it.
                value += sin(2 * PI * frequency * h * i / rate) / h
            }
            if (noise > 0) value += random.nextDouble(-noise, noise)
            (value * 0.4).toFloat()
        }
    }

    private fun centsOff(detected: Double, expected: Double) =
        abs(Notes.centsBetween(detected, expected))

    /** The requirement, across the guitar's full range. */
    @Test
    fun `pure tones from E2 to E6 read within three cents`() {
        val notes = mapOf(
            "E2" to 82.41, "A2" to 110.0, "D3" to 146.83, "G3" to 196.0,
            "B3" to 246.94, "E4" to 329.63, "A4" to 440.0, "E5" to 659.26,
            "E6" to 1318.51,
        )
        notes.forEach { (name, frequency) ->
            val detected = detector.detect(tone(frequency))
            assertNotNull("$name was not detected at all", detected)
            val off = centsOff(detected!!, frequency)
            assertTrue("$name read %.2f cents out".format(off), off <= 3.0)
        }
    }

    /**
     * A real string is not a sine wave. Its partials are often louder than its
     * fundamental, which is exactly where naive peak-picking goes wrong by an
     * octave.
     */
    @Test
    fun `a harmonically rich tone still reads its fundamental`() {
        listOf(82.41, 110.0, 196.0, 329.63).forEach { frequency ->
            val detected = detector.detect(tone(frequency, harmonics = 8))
            assertNotNull("nothing detected at $frequency", detected)
            val off = centsOff(detected!!, frequency)
            assertTrue(
                "$frequency Hz read as $detected (%.1f cents out) — likely an octave slip".format(off),
                off <= 3.0,
            )
        }
    }

    /** A practice room is not an anechoic chamber. */
    @Test
    fun `a noisy signal still reads within tolerance`() {
        val detected = detector.detect(tone(146.83, harmonics = 6, noise = 0.05))
        assertNotNull(detected)
        assertTrue(centsOff(detected!!, 146.83) <= 3.0)
    }

    /** The detuned cases a tuner exists for. */
    @Test
    fun `a flat or sharp string reads as flat or sharp`() {
        val target = 82.41
        val flat = detector.detect(tone(target * 0.99))!!      // about 17 cents flat
        val sharp = detector.detect(tone(target * 1.01))!!
        assertTrue(Notes.centsBetween(flat, target) < -10)
        assertTrue(Notes.centsBetween(sharp, target) > 10)
        // And both still resolve to the same note name.
        assertEquals("E2", Notes.nearest(flat).name)
        assertEquals("E2", Notes.nearest(sharp).name)
    }

    /**
     * Null is a real answer. A tuner that always shows a number will show one
     * for a cough or a chair scrape, and the guitarist will turn a peg to chase
     * it.
     */
    @Test
    fun `silence and noise produce no reading`() {
        assertNull("silence", detector.detect(FloatArray(detector.windowSize * 2)))
        val random = Random(7)
        val hiss = FloatArray(detector.windowSize * 2) { random.nextDouble(-0.3, 0.3).toFloat() }
        assertNull("white noise is not a pitch", detector.detect(hiss))
    }

    @Test
    fun `a buffer that is too short is refused rather than guessed at`() {
        assertNull(detector.detect(FloatArray(128)))
    }

    /** An 8-string's low F# is the lowest thing this has to cope with. */
    @Test
    fun `the window reaches low enough for an eight-string`() {
        val fSharp1 = 46.25
        val detected = detector.detect(tone(fSharp1, harmonics = 4))
        assertNotNull("F#1 needs a window at least two periods long", detected)
        assertTrue(centsOff(detected!!, fSharp1) <= 3.0)
    }
}
