package com.riffbuddy

import com.riffbuddy.audio.InputLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputLevelTest {

    @Test
    fun `full scale is 0 dB and half scale about minus 6`() {
        assertEquals(0f, InputLevel.toDb(32767), 0.01f)
        assertEquals(-6.02f, InputLevel.toDb(16384), 0.05f)
    }

    @Test
    fun `silence sits on the floor instead of minus infinity`() {
        assertEquals(InputLevel.FLOOR_DB, InputLevel.read(ShortArray(100), 100).peakDb)
    }

    /** One flattened pick attack in an otherwise quiet buffer is still clipping. */
    @Test
    fun `a single sample at full scale is clipping`() {
        val buf = ShortArray(960) { 2000 }.also { it[500] = (-32768).toShort() }
        assertTrue(InputLevel.read(buf, buf.size).clipped)
    }

    @Test
    fun `loud but clean is not clipping`() {
        val buf = ShortArray(960) { if (it % 2 == 0) 30000 else -30000 }
        assertFalse(InputLevel.read(buf, buf.size).clipped)
    }

    @Test
    fun `only the samples read count, not stale ones past the end`() {
        val buf = ShortArray(960) { 32767 }
        java.util.Arrays.fill(buf, 0, 480, 100)
        assertFalse(InputLevel.read(buf, 480).clipped)
    }

    @Test
    fun `the meter runs from the floor to full scale`() {
        assertEquals(0f, InputLevel.meter(InputLevel.FLOOR_DB), 0f)
        assertEquals(1f, InputLevel.meter(0f), 0f)
        assertEquals(0.5f, InputLevel.meter(-30f), 0.001f)
    }
}
