package com.riffbuddy

import com.riffbuddy.audio.ClickGrid
import com.riffbuddy.audio.PcmBuilder
import com.riffbuddy.audio.Waveform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LooperPartsTest {

    // ---- PcmBuilder --------------------------------------------------------

    @Test
    fun `stereo is averaged to mono`() {
        val b = PcmBuilder(maxFrames = 100)
        b.appendShorts(shortArrayOf(100, 300, -200, 200), 4, channels = 2)
        assertArrayEquals(shortArrayOf(200, 0), b.build(44_100).samples)
    }

    @Test
    fun `float output from a decoder becomes 16-bit`() {
        val b = PcmBuilder(maxFrames = 100)
        b.appendFloats(floatArrayOf(1f, 1f, -1f, -1f, 0.5f, 0f), 6, channels = 2)
        assertArrayEquals(shortArrayOf(32767, -32767, 8191), b.build(48_000).samples)
    }

    /** A song longer than the cap is cut, and says so — never an out-of-memory crash. */
    @Test
    fun `a song past the cap is truncated, not allowed to exhaust memory`() {
        val b = PcmBuilder(maxFrames = 3)
        b.appendShorts(ShortArray(10) { it.toShort() }, 10, channels = 1)
        assertEquals(3, b.frames)
        assertTrue(b.truncated)
    }

    @Test
    fun `the buffer grows across many small chunks`() {
        val b = PcmBuilder(maxFrames = 5_000_000)
        repeat(3000) { b.appendShorts(ShortArray(1024) { 7 }, 1024, 1) }
        assertEquals(3000 * 1024, b.frames)
    }

    // ---- Waveform ----------------------------------------------------------

    @Test
    fun `the loudest slice of the waveform fills the height`() {
        val samples = ShortArray(1000) { if (it in 500..509) 16000 else 800 }
        val peaks = Waveform.peaks(samples, buckets = 10)
        assertEquals(1f, peaks[5], 0f)
        assertEquals(800f / 16000f, peaks[0], 1e-6f)
    }

    // ---- ClickGrid ---------------------------------------------------------

    private val sr = 44_100
    private val beat120 = sr * 60.0 / 120   // 22050 frames

    @Test
    fun `at full speed a click lands every beat, to the sample`() {
        val clicks = ClickGrid.clicks(start = 0.0, frames = sr, speed = 1.0, beatFrames = beat120, beatsPerBar = 4, loop = null)
        assertEquals(listOf(0, 22050), clicks.map { it.offset })
        assertTrue(clicks[0].downbeat)
    }

    /** Clicks follow the music: at half speed a beat of song takes twice as long. */
    @Test
    fun `at half speed the clicks are half as fast`() {
        val clicks = ClickGrid.clicks(start = 0.0, frames = sr, speed = 0.5, beatFrames = beat120, beatsPerBar = 4, loop = null)
        assertEquals(listOf(0), clicks.map { it.offset })
        val next = ClickGrid.clicks(start = sr * 0.5, frames = sr, speed = 0.5, beatFrames = beat120, beatsPerBar = 4, loop = null)
        assertEquals(listOf(0), next.map { it.offset })   // song 22050 = output 0 of this block
    }

    @Test
    fun `a beat inside a block is placed at its exact offset`() {
        val clicks = ClickGrid.clicks(start = 20_000.0, frames = 4096, speed = 1.0, beatFrames = beat120, beatsPerBar = 4, loop = null)
        assertEquals(listOf(2050), clicks.map { it.offset })
    }

    /** Beat one is A on every pass, even when the loop is not a whole number of beats. */
    @Test
    fun `the grid restarts at A each time the loop wraps`() {
        val a = 10_000
        val b = a + 30_000   // 1.36 beats long
        val loop = a until b
        // A block that starts 1000 frames before B: the wrap is at output 1000.
        val clicks = ClickGrid.clicks(start = b - 1000.0, frames = 2000, speed = 1.0, beatFrames = beat120, beatsPerBar = 4, loop = loop)
        assertEquals(listOf(1000), clicks.map { it.offset })
        assertTrue("beat at A is a downbeat", clicks[0].downbeat)
    }

    @Test
    fun `the downbeat comes round every bar`() {
        val clicks = ClickGrid.clicks(start = 0.0, frames = (beat120 * 8).toInt(), speed = 1.0, beatFrames = beat120, beatsPerBar = 4, loop = null)
        assertEquals(listOf(true, false, false, false, true, false, false, false), clicks.map { it.downbeat })
    }
}
