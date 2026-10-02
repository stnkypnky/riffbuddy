package com.riffbuddy

import com.riffbuddy.audio.ClickSound
import com.riffbuddy.audio.ClickSynth
import com.riffbuddy.audio.Accent
import com.riffbuddy.audio.LatencyCalibration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The criterion — after calibration, a recorded click aligns with its
 * source within ±15 ms — tested against recordings with a delay planted in
 * them, through the room noise and speaker ringing a real one has.
 */
class LatencyCalibrationTest {

    private val sr = 48_000
    private val click = ClickSynth.render(ClickSound.CLICK, Accent.ACCENT, true, sr)

    /**
     * A simulated recording: room hiss, a low hum, and each click arriving
     * [delayMs] after its predicted frame (plus [jitterMs] of wobble), passed
     * through a crude speaker — attenuated and with a decaying ring.
     */
    private fun recording(delayMs: Double, jitterMs: Double = 0.0, seed: Int = 7, drop: Set<Int> = emptySet()): Pair<ShortArray, List<Long>> {
        val predicted = LatencyCalibration.clickFrames(sr)
        val length = (predicted.last() + sr).toInt()
        val rnd = java.util.Random(seed.toLong())
        val out = DoubleArray(length) { i -> rnd.nextGaussian() * 60 + sin(2 * PI * 50 * i / sr) * 400 }
        predicted.forEachIndexed { k, p ->
            if (k in drop) return@forEachIndexed
            val wobble = if (jitterMs == 0.0) 0.0 else (rnd.nextDouble() * 2 - 1) * jitterMs
            val at = (p + (delayMs + wobble) * sr / 1000).toInt()
            for (i in click.indices) {
                if (at + i >= length) break
                out[at + i] += click[i] * 0.35
                // Speaker ring: a 900 Hz resonance that trails the click.
                out[at + i] += sin(2 * PI * 900 * i / sr) * 2500 * Math.exp(-i / 400.0)
            }
        }
        return ShortArray(length) { out[it].toInt().coerceIn(-32768, 32767).toShort() } to predicted
    }

    @Test
    fun `a planted 37 ms delay is measured as 37 ms`() {
        val (rec, predicted) = recording(delayMs = 37.0)
        val r = LatencyCalibration.measure(rec, sr, predicted)!!
        assertTrue("measured ${r.offsetMs}", abs(r.offsetMs - 37) <= 1)
        assertEquals(10, r.found)
        assertTrue(r.withinTolerance)
    }

    /**
     * A small phone speaker cannot reproduce a click's sharp edge: it *rings
     * up*, reaching its loudest a few milliseconds after the sound began. Timed
     * from that peak, every measurement would come out late by the ring-up;
     * timed from the first clear rise, it is right. This is the test that tells
     * those two apart.
     */
    @Test
    fun `a speaker that rings up is timed from where the sound starts, not its peak`() {
        val predicted = LatencyCalibration.clickFrames(sr)
        val length = (predicted.last() + sr).toInt()
        val rnd = java.util.Random(11)
        val out = DoubleArray(length) { rnd.nextGaussian() * 50 }
        val delayFrames = (30.0 * sr / 1000).toInt()
        predicted.forEach { p ->
            val at = p.toInt() + delayFrames
            for (i in 0 until 4000) {
                if (at + i >= length) break
                val envelope = (1 - Math.exp(-i / 90.0)) * Math.exp(-i / 700.0)
                out[at + i] += envelope * 14000 * sin(2 * PI * 1200 * i / sr)
            }
        }
        val rec = ShortArray(length) { out[it].toInt().coerceIn(-32768, 32767).toShort() }
        val r = LatencyCalibration.measure(rec, sr, predicted)!!
        assertTrue("measured ${r.offsetMs} for a 30 ms delay", abs(r.offsetMs - 30) <= 1)
    }

    /** A Bluetooth-sized delay is still found inside the search window. */
    @Test
    fun `a long delay is found too`() {
        val (rec, predicted) = recording(delayMs = 180.0)
        val r = LatencyCalibration.measure(rec, sr, predicted)!!
        assertTrue("measured ${r.offsetMs}", abs(r.offsetMs - 180) <= 1)
    }

    /** Timestamps can overshoot: a click slightly *before* its prediction. */
    @Test
    fun `a small negative residual is measured, not missed`() {
        val (rec, predicted) = recording(delayMs = -8.0)
        val r = LatencyCalibration.measure(rec, sr, predicted)!!
        assertTrue("measured ${r.offsetMs}", abs(r.offsetMs + 8) <= 1)
    }

    /** The ±15 ms criterion itself: steady clicks pass, wandering ones do not. */
    @Test
    fun `wobble inside 15 ms passes and wobble beyond it fails`() {
        val (steady, p1) = recording(delayMs = 40.0, jitterMs = 6.0)
        assertTrue(LatencyCalibration.measure(steady, sr, p1)!!.withinTolerance)
        val (wild, p2) = recording(delayMs = 40.0, jitterMs = 45.0, seed = 3)
        assertFalse(LatencyCalibration.measure(wild, sr, p2)!!.withinTolerance)
    }

    /** A click drowned out (a door slam, a cough) does not spoil the median. */
    @Test
    fun `a few missing clicks still give an answer`() {
        val (rec, predicted) = recording(delayMs = 25.0, drop = setOf(2, 7))
        val r = LatencyCalibration.measure(rec, sr, predicted)!!
        assertEquals(8, r.found)
        assertTrue(abs(r.offsetMs - 25) <= 1)
        assertTrue(r.withinTolerance)
    }

    /** Volume at zero, or the mic covered: no clicks, and it says so rather than guessing. */
    @Test
    fun `silence gives no result instead of a made-up one`() {
        val rnd = java.util.Random(1)
        val quiet = ShortArray(sr * 6) { (rnd.nextGaussian() * 40).toInt().toShort() }
        assertNull(LatencyCalibration.measure(quiet, sr, LatencyCalibration.clickFrames(sr)))
    }

    @Test
    fun `too few clicks found is not within tolerance however tidy they are`() {
        val (rec, predicted) = recording(delayMs = 30.0, drop = setOf(0, 1, 2, 3, 4))
        val r = LatencyCalibration.measure(rec, sr, predicted)
        assertNotNull(r)
        assertFalse(r!!.withinTolerance)
    }

    @Test
    fun `the shared clock converts to frames of the take`() {
        // 1 s after the anchor is 48000 frames after the anchor frame.
        assertEquals(1000L + 48_000, LatencyCalibration.inputFrameAt(2_000_000_000L, 1000, 1_000_000_000L, sr))
    }
}
