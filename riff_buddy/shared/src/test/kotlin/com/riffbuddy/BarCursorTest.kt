package com.riffbuddy

import com.riffbuddy.audio.BarCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarCursorTest {

    @Test
    fun `counts through a steady bar`() {
        val c = BarCursor(4)
        (0L..3L).forEach { assertEquals(it.toInt(), c.indexOf(it)) }
        assertFalse(c.startsNewBar(3))
        assertTrue(c.startsNewBar(4))
        c.advance()
        assertEquals(0, c.indexOf(4))
        assertEquals(1, c.barNumber)
    }

    /**
     * The regression this class exists for.
     *
     * With `pulse % barLength`, changing 4/4 to 3/4 puts the downbeat on the
     * wrong pulse for the rest of the session — silently, with no crash to point
     * at. Counting from the bar's own start stays correct.
     */
    @Test
    fun `changing time signature keeps the downbeat in the right place`() {
        val c = BarCursor(4)
        c.advance()          // bar 1 starts at pulse 4
        c.advance(3)         // bar 2 starts at pulse 8, now in 3/4

        assertEquals(0, c.indexOf(8))
        assertEquals(1, c.indexOf(9))
        assertEquals(2, c.indexOf(10))
        assertTrue("pulse 11 begins the next bar", c.startsNewBar(11))

        // What the naive version would have said, for contrast.
        assertEquals("the modulo would have been wrong here", 3, (11L % 4).toInt())
    }

    /** Turning on triplets mid-bar-count must not shift the downbeat either. */
    @Test
    fun `changing subdivision keeps the downbeat in the right place`() {
        val c = BarCursor(4)
        c.advance(12)        // 4/4 with triplets: 12 pulses a bar, from pulse 4
        assertEquals(0, c.indexOf(4))
        assertEquals(11, c.indexOf(15))
        assertTrue(c.startsNewBar(16))
        c.advance()
        assertEquals(0, c.indexOf(16))
    }

    @Test
    fun `bar numbers keep counting across changes`() {
        val c = BarCursor(4)
        repeat(3) { c.advance() }
        c.advance(6)
        c.advance()
        assertEquals(5, c.barNumber)
    }
}
