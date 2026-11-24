package com.riffbuddy

import com.riffbuddy.domain.format
import com.riffbuddy.domain.parseDateTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dates are written out by the app's own small formatter, because the browser
 * build has no locale-aware one. Java's formatter, in US English, is the
 * reference it must match, for every pattern the screens use.
 */
class DatesTest {

    private val patterns = listOf(
        "EEE, MMM d", "EEE, MMM d, h:mm a", "EEE, MMM d, yyyy", "h:mm a", "MMM d",
        "MMM d, h:mm a", "MMM d, yyyy", "MMM d, yyyy, h:mm a", "yyyy-MM-dd HH:mm",
    )

    @Test
    fun `every pattern matches Java's formatter across a year of times`() {
        var t = java.time.LocalDateTime.of(2026, 1, 1, 0, 0)
        // Every month, every weekday, both sides of noon and midnight.
        repeat(400) {
            val mine = LocalDateTime(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
            patterns.forEach { p ->
                assertEquals("$p at $t", DateTimeFormatter.ofPattern(p, Locale.US).format(t), mine.format(p))
            }
            t = t.plusHours(23).plusMinutes(7)
        }
    }

    @Test
    fun `dates and times alone`() {
        assertEquals("Sun, Mar 8, 2026", LocalDate(2026, 3, 8).format("EEE, MMM d, yyyy"))
        assertEquals("12:05 AM", LocalTime(0, 5).format("h:mm a"))
        assertEquals("12:30 PM", LocalTime(12, 30).format("h:mm a"))
    }

    @Test
    fun `a typed start time reads back, and nonsense does not`() {
        assertEquals(LocalDateTime(2026, 3, 14, 18, 30), parseDateTime(" 2026-03-14 18:30 "))
        assertNull(parseDateTime("2026-02-30 18:30"))
        assertNull(parseDateTime("yesterday"))
    }

    /** The reminder time is stored as text; old values must still read. */
    @Test
    fun `stored times and dates keep their text form`() {
        assertEquals("18:30", LocalTime(18, 30).toString())
        assertEquals(LocalTime(7, 5), LocalTime.parse("07:05"))
        assertEquals(java.time.LocalDate.of(2026, 3, 8).toString(), LocalDate(2026, 3, 8).toString())
    }
}
