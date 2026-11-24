package com.riffbuddy

import com.riffbuddy.audio.GainStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GainStageTest {

    /** A typical raw take: peaks at −22 dBFS. Set level should add 16 dB. */
    @Test
    fun `a take peaking at minus 22 gets 16 dB, landing at minus 6`() {
        val rawPeak = GainStage.linear(-22f)
        assertEquals(16f, GainStage.suggest(rawPeak), 0.05f)
    }

    @Test
    fun `a take that arrives too hot is cut to land at minus 6`() {
        assertEquals(-3f, GainStage.suggest(GainStage.linear(-3f)), 0.05f)
        assertEquals(-6f, GainStage.suggest(1f), 0.05f)
        // The cut has a floor too, like the boost.
        assertEquals(GainStage.MIN_DB, GainStage.suggest(GainStage.linear(30f)), 0f)
    }

    /** Silence while "setting level" must not produce a runaway 60 dB. */
    @Test
    fun `the suggestion is capped`() {
        assertEquals(GainStage.MAX_DB, GainStage.suggest(GainStage.linear(-80f)), 0f)
        assertEquals(GainStage.MAX_DB, GainStage.suggest(0f), 0f)
    }

    @Test
    fun `gain is applied before rounding to 16 bits`() {
        // A signal too quiet to survive 16-bit rounding on its own: 1/65536.
        val tiny = floatArrayOf(1f / 65536f)
        val out = ShortArray(1)
        GainStage.process(tiny, 1, gainDb = 20f, out = out)
        assertEquals(4, out[0].toInt())   // boosted then rounded, not rounded to 0 then boosted
    }

    /** Clipping is judged after the gain: a clean raw signal can clip once boosted. */
    @Test
    fun `clipping is detected after the gain`() {
        val input = floatArrayOf(0.2f, -0.3f)
        val out = ShortArray(2)
        assertFalse(GainStage.process(input, 2, gainDb = 0f, out = out))
        assertTrue(GainStage.process(input, 2, gainDb = 12f, out = out))
        assertEquals(-32767, out[1].toInt())   // held at full scale, not wrapped
    }
}
