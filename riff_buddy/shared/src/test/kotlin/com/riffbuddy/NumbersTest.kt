package com.riffbuddy

import com.riffbuddy.domain.Songsterr
import com.riffbuddy.domain.minutesAndSeconds
import com.riffbuddy.domain.pad
import com.riffbuddy.domain.toFixed
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLEncoder
import java.util.Locale

/** The app writes numbers itself, since the browser has no String.format; Java's is the reference. */
class NumbersTest {

    @Test
    fun `decimals match String format`() {
        val values = listOf(0.0, 0.04, 0.05, 0.25, 1.0, 1.25, 2.35, 9.95, 12.345, 99.99, 120.0, 1234.5, -0.04, -1.25, -3.0)
        values.forEach { v ->
            (0..2).forEach { places ->
                assertEquals("$v to $places", String.format(Locale.US, "%.${places}f", v), v.toFixed(places))
            }
        }
    }

    @Test
    fun `clocks and padding`() {
        assertEquals("0:07", minutesAndSeconds(7))
        assertEquals("3:00", minutesAndSeconds(180L))
        assertEquals("61:01", minutesAndSeconds(3661))
        assertEquals("05", pad(5))
        assertEquals("007", pad(7L, 3))
    }

    /** The search link is built the way URLEncoder built it, so saved links and searches match. */
    @Test
    fun `songsterr search encodes as URLEncoder did`() {
        listOf("Floods", "Pantera", "AC/DC", "Guns N' Roses", "Motörhead", "100% & more", "a+b c*d_e.f-g~").forEach { text ->
            val expected = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
            assertEquals(text, true, Songsterr.searchUrl(text, "").contains(expected))
        }
    }
}
