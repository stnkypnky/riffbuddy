package com.riffbuddy

import com.riffbuddy.audio.AbMixer
import com.riffbuddy.audio.Pcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbMixerTest {

    private val sr = 48_000
    /** Every sample encodes its own index, so positions can be read off the output. */
    private fun ramp(frames: Int, base: Int) = Pcm(ShortArray(frames) { ((base + it) % 30000).toShort() }, sr)

    /** Switching A/B must keep position within 50 ms. Here it is exact. */
    @Test
    fun `switching takes keeps the position exactly`() {
        val a = ramp(sr * 2, base = 0)
        val b = ramp(sr * 2, base = 10000)
        val m = AbMixer(listOf(a, b), listOf(0, 0))
        m.render(ShortArray(1000))
        assertEquals(1000L, m.position)

        m.select(1)
        assertEquals("switching must not move the position", 1000L, m.position)

        // Render through the 10 ms (480-frame) cross-fade; what follows is B at
        // exactly the position A had reached — no skip, no repeat.
        m.render(ShortArray(480))
        val next = ShortArray(1)
        m.render(next)
        assertEquals((10000 + 1480).toShort(), next[0])
    }

    /** Aligned starts: two takes whose music begins at different points line up. */
    @Test
    fun `each take is read from its own aligned start`() {
        val a = ramp(sr, base = 0)
        val b = ramp(sr, base = 0)
        // B's music starts 300 frames later in its file than A's.
        val m = AbMixer(listOf(a, b), listOf(100, 400))
        val out = ShortArray(1)
        m.render(out)
        assertEquals(100.toShort(), out[0])
        m.select(1)
        m.seek(0)
        m.render(out)
        assertEquals(400.toShort(), out[0])
    }

    @Test
    fun `the switch cross-fades instead of jumping`() {
        val a = Pcm(ShortArray(sr) { 0 }, sr)
        val b = Pcm(ShortArray(sr) { 20000 }, sr)
        val m = AbMixer(listOf(a, b), listOf(0, 0))
        m.render(ShortArray(100))
        m.select(1)
        val out = ShortArray(480)
        m.render(out)
        for (i in 1 until out.size) assertTrue("jump at $i", out[i] - out[i - 1] in 0..100)
        assertTrue(out.last() > 19000)
    }

    @Test
    fun `a single take plays and finishes`() {
        val m = AbMixer(listOf(ramp(500, 0)), listOf(0))
        val out = ShortArray(800)
        assertEquals(500, m.render(out))
        assertTrue(m.finished)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `takes at different sample rates are refused`() {
        AbMixer(listOf(Pcm(ShortArray(10), 48_000), Pcm(ShortArray(10), 44_100)), listOf(0, 0))
    }
}
