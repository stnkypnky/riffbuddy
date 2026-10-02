package com.riffbuddy

import com.riffbuddy.audio.LoopRenderer
import com.riffbuddy.audio.Pcm
import com.riffbuddy.audio.PitchDetector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slow-downer's criteria, measured rather than listened for.
 *
 * "At 50 % speed the pitch is unchanged, verified with the tuner on a test
 * tone" is taken literally: the tone goes through the stretcher and comes out
 * into the same PitchDetector the tuner uses, which is itself tested to
 * ±3 cents.
 */
class LoopRendererTest {

    private val sr = 44_100

    private fun tone(hz: Double, seconds: Double, harmonics: Int = 1): Pcm {
        val frames = (sr * seconds).toInt()
        val samples = ShortArray(frames) { i ->
            var v = 0.0
            for (h in 1..harmonics) v += sin(2 * PI * hz * h * i / sr) / h
            (v / harmonics * 0.6 * Short.MAX_VALUE).toInt().toShort()
        }
        return Pcm(samples, sr)
    }

    private fun renderAll(r: LoopRenderer, frames: Int): ShortArray {
        val out = ShortArray(frames)
        var done = 0
        val block = 512
        while (done < frames) {
            val n = minOf(block, frames - done)
            val chunk = ShortArray(n)
            r.render(chunk, n)
            System.arraycopy(chunk, 0, out, done, n)
            done += n
        }
        return out
    }

    private fun cents(measured: Double, expected: Double) = 1200 * ln(measured / expected) / ln(2.0)

    /** Median of pitch readings across the output, ignoring the fade-in. */
    private fun pitchOf(audio: ShortArray): Double {
        val detector = PitchDetector(sampleRate = sr)
        val w = detector.windowSize
        val readings = mutableListOf<Double>()
        var start = sr / 10
        while (start + w <= audio.size) {
            val buf = FloatArray(w) { audio[start + it] / 32768f }
            detector.detect(buf)?.let { readings += it }
            start += w
        }
        assertTrue("no pitch readings at all", readings.size >= 3)
        return readings.sorted()[readings.size / 2]
    }

    @Test
    fun `at 50 percent a 440 Hz tone is still 440 Hz`() {
        val r = LoopRenderer(tone(440.0, 2.0)).apply { speed = 0.5 }
        val out = renderAll(r, sr * 3)
        val c = cents(pitchOf(out), 440.0)
        assertTrue("off by $c cents", abs(c) <= 3.0)
    }

    /** A guitar-like tone — harmonics, low string — is the harder case. */
    @Test
    fun `pitch holds at every speed the slider allows, on a low string with harmonics`() {
        for (speed in listOf(0.25, 0.5, 0.75, 1.25, 1.5)) {
            val r = LoopRenderer(tone(110.0, 3.0, harmonics = 6)).apply { this.speed = speed }
            val out = renderAll(r, sr * 2)
            val c = cents(pitchOf(out), 110.0)
            assertTrue("at $speed off by $c cents", abs(c) <= 3.0)
        }
    }

    /** Half speed takes twice as long: the other half of "slowed down". */
    @Test
    fun `half speed covers half as much of the song`() {
        val r = LoopRenderer(tone(440.0, 4.0)).apply { speed = 0.5 }
        renderAll(r, sr * 2)   // two seconds heard
        val covered = r.position / sr
        assertEquals(1.0, covered, 0.03)   // one second of song
    }

    /** At normal speed, nothing touches the sound. */
    @Test
    fun `at 100 percent the output is the file, bit for bit`() {
        val pcm = tone(330.0, 1.0, harmonics = 3)
        val out = renderAll(LoopRenderer(pcm), pcm.frames)
        assertArrayEquals(pcm.samples, out)
    }

    /**
     * Loop points must be accurate within 20 ms. At 100 % the loop is exact to
     * the sample: after B comes A, with no gap at all — not 30 ms, not 1.
     */
    @Test
    fun `a loop repeats from exactly A, with no gap`() {
        // A ramp makes every sample unique, so any slip in position shows.
        val pcm = Pcm(ShortArray(sr * 2) { (it % 30000).toShort() }, sr)
        val r = LoopRenderer(pcm)
        val a = sr / 2
        val b = a + sr / 2   // half a second
        r.setLoop(a, b)
        val out = renderAll(r, (b - a) * 3)

        val len = b - a
        val fade = (sr * 0.006).toInt() + 1
        for (pass in 0 until 3) {
            // Outside the short cross-fade before B, every sample is the file's own.
            for (i in 0 until len - fade) {
                assertEquals("pass $pass sample $i", pcm.samples[a + i], out[pass * len + i])
            }
        }
        assertEquals(3, r.loopsCompleted)
    }

    /** The join is a blend, not a jump: no click when arriving back at A. */
    @Test
    fun `the join from B back to A does not click`() {
        // A tone whose period does not divide the loop, so a hard splice would jump.
        val pcm = tone(137.0, 2.0)
        val r = LoopRenderer(pcm)
        r.setLoop(sr / 4, sr / 4 + (sr * 0.4).toInt())
        val out = renderAll(r, sr)
        val maxStepInTone = (2 * PI * 137.0 / sr * 0.6 * Short.MAX_VALUE) * 1.5
        for (i in 1 until out.size) {
            assertTrue("jump at $i", abs(out[i] - out[i - 1]) <= maxStepInTone)
        }
    }

    @Test
    fun `slowed loops still count, and still come back to A`() {
        val pcm = tone(220.0, 3.0)
        val r = LoopRenderer(pcm).apply { speed = 0.5 }
        r.setLoop(sr, sr + sr / 2)   // half a second of song = one second heard
        renderAll(r, sr * 3 + sr / 4)
        assertEquals(3, r.loopsCompleted)
        assertTrue(r.position >= sr && r.position < sr + sr / 2)
    }

    @Test
    fun `a loop too short for the stretcher is refused`() {
        val r = LoopRenderer(tone(220.0, 2.0))
        assertTrue(!r.setLoop(1000, 1000 + sr / 10))
    }

    @Test
    fun `speed is held to the range the slider shows`() {
        val r = LoopRenderer(tone(220.0, 1.0))
        r.speed = 0.1
        assertEquals(0.25, r.speed, 0.0)
        r.speed = 3.0
        assertEquals(1.5, r.speed, 0.0)
    }

    @Test
    fun `with no loop the song ends and says so`() {
        val pcm = tone(220.0, 0.5)
        val r = LoopRenderer(pcm)
        val out = ShortArray(sr)
        val real = r.render(out)
        assertEquals(pcm.frames, real)
        assertTrue(r.finished)
    }

    /**
     * The bundled test tone, end to end: the actual asset a player will load,
     * through the stretcher at half speed, read by the tuner's detector. This
     * is the on-device check, rehearsed on the JVM with the real file.
     */
    @Test
    fun `the bundled A440 test tone still reads A at half speed`() {
        val file = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "src/main/assets/samples/a440-test-tone.wav") }
            .first { it.exists() }
        val bytes = file.readBytes()
        val rate = java.nio.ByteBuffer.wrap(bytes, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        val data = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(data.remaining()).also { data.get(it) }
        val r = LoopRenderer(Pcm(samples, rate)).apply { speed = 0.5 }
        val out = ShortArray(rate * 4)
        r.render(out)
        val detector = PitchDetector(sampleRate = rate)
        val w = detector.windowSize
        val readings = (1 until out.size / w).mapNotNull { k ->
            detector.detect(FloatArray(w) { out[k * w + it] / 32768f })
        }.sorted()
        val c = 1200 * ln(readings[readings.size / 2] / 440.0) / ln(2.0)
        assertTrue("off by $c cents", abs(c) <= 3.0)
    }
}
