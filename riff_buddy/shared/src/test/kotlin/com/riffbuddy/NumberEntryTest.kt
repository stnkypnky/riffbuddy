package com.riffbuddy

import com.riffbuddy.domain.NumberEntry
import com.riffbuddy.domain.NumberEntry.Result
import com.riffbuddy.ui.stepFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberEntryTest {

    private val speed = 25.0..150.0
    private val tempo = 20.0..300.0

    @Test
    fun `plain numbers and numbers with a unit read`() {
        assertEquals(Result.Ok(63.0), NumberEntry.parse("63", speed))
        assertEquals(Result.Ok(137.0), NumberEntry.parse("137 bpm", tempo))
        assertEquals(Result.Ok(75.0), NumberEntry.parse("75%", speed))
        assertEquals(Result.Ok(432.5), NumberEntry.parse("432.5Hz", 415.0..466.0, decimals = 1))
    }

    @Test
    fun `a comma is a decimal point`() {
        assertEquals(Result.Ok(62.5), NumberEntry.parse("62,5", speed, decimals = 1))
    }

    @Test
    fun `extra places round to what the setting keeps`() {
        assertEquals(Result.Ok(62.5), NumberEntry.parse("62.53", speed, decimals = 1))
        assertEquals(Result.Ok(63.0), NumberEntry.parse("62.7", speed, decimals = 0))
    }

    @Test
    fun `out of range is refused, not clamped`() {
        assertEquals(Result.OutOfRange(500.0), NumberEntry.parse("500", tempo))
        assertEquals(Result.OutOfRange(10.0), NumberEntry.parse("10", speed))
    }

    @Test
    fun `nothing usable is not a number`() {
        listOf("", "  ", "abc", ".", "-", "bpm").forEach {
            assertEquals("'$it'", Result.NotANumber, NumberEntry.parse(it, speed))
        }
    }

    @Test
    fun `format drops a trailing zero`() {
        assertEquals("63", NumberEntry.format(63.0))
        assertEquals("62.5", NumberEntry.format(62.5, 1))
        assertEquals("440", NumberEntry.format(440.0, 1))
        assertEquals("20 to 300 BPM", NumberEntry.describe(tempo, 0, "BPM"))
        assertEquals("25 to 150%", NumberEntry.describe(speed, 0, "%"))
    }

    @Test
    fun `times of day read in either clock`() {
        assertEquals(19 to 30, NumberEntry.parseTime("7:30 pm"))
        assertEquals(19 to 30, NumberEntry.parseTime("19:30"))
        assertEquals(7 to 30, NumberEntry.parseTime("730"))
        assertEquals(19 to 0, NumberEntry.parseTime("7pm"))
        assertEquals(0 to 15, NumberEntry.parseTime("12:15 am"))
        assertEquals(12 to 5, NumberEntry.parseTime("12:05 PM"))
        assertNull(NumberEntry.parseTime("25:00"))
        assertNull(NumberEntry.parseTime("7:75"))
        assertNull(NumberEntry.parseTime("13pm"))
        assertNull(NumberEntry.parseTime("soon"))
    }

    @Test
    fun `a step from off the grid lands on it`() {
        assertEquals(65.0, stepFrom(63.0, 5.0, speed, 0), 1e-9)
        assertEquals(60.0, stepFrom(63.0, -5.0, speed, 0), 1e-9)
        assertEquals(70.0, stepFrom(65.0, 5.0, speed, 0), 1e-9)
        assertEquals(150.0, stepFrom(148.0, 5.0, speed, 0), 1e-9)
        assertEquals(25.0, stepFrom(25.0, -5.0, speed, 0), 1e-9)
        assertEquals(440.5, stepFrom(440.0, 0.5, 415.0..466.0, 1), 1e-9)
    }
}
