package com.riffbuddy.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant

/*
 * Dates and times, the same on every edition. A moment is a kotlin.time
 * Instant, stored as epoch milliseconds; it becomes a date or a wall-clock
 * time only in a time zone, which is always the device's own.
 */

/** Where stored times count from. A stored zero also means "never set". */
val EPOCH: Instant = Instant.fromEpochMilliseconds(0)

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

fun localZone(): TimeZone = TimeZone.currentSystemDefault()

fun today(zone: TimeZone = localZone()): LocalDate = Clock.System.todayIn(zone)

fun Instant.local(zone: TimeZone = localZone()): LocalDateTime = toLocalDateTime(zone)

fun Instant.localDate(zone: TimeZone = localZone()): LocalDate = local(zone).date

fun LocalDate.plusDays(days: Long): LocalDate = plus(days, DateTimeUnit.DAY)

fun LocalDate.minusDays(days: Long): LocalDate = minus(days, DateTimeUnit.DAY)

/*
 * Day and month names are English, the language the whole app is written in.
 * Formatting is done here rather than by a locale-aware formatter because the
 * browser build has none, and a date that reads the same everywhere is worth
 * more than one that follows the device's region.
 */

val DayOfWeek.fullName: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

val DayOfWeek.shortName: String get() = fullName.take(3)

val DayOfWeek.letter: String get() = fullName.take(1)

val Month.fullName: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

/**
 * A date and time written out by a pattern in the usual letters:
 * yyyy year, M or MM month number, MMM or MMMM month name, d or dd day,
 * EEE or EEEE weekday, h hour on a 12-hour clock, HH on a 24-hour one,
 * mm minutes, a AM or PM. Anything else is copied as it is.
 */
fun LocalDateTime.format(pattern: String): String = buildString {
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        var run = 1
        while (i + run < pattern.length && pattern[i + run] == c) run++
        val hour12 = (hour % 12).let { if (it == 0) 12 else it }
        append(
            when (c) {
                'y' -> year.toString()
                'M' -> when (run) {
                    1 -> month.number.toString()
                    2 -> month.number.toString().padStart(2, '0')
                    3 -> month.fullName.take(3)
                    else -> month.fullName
                }
                'd' -> day.toString().padStart(run, '0')
                'E' -> if (run >= 4) dayOfWeek.fullName else dayOfWeek.shortName
                'h' -> hour12.toString().padStart(run, '0')
                'H' -> hour.toString().padStart(run, '0')
                'm' -> minute.toString().padStart(run, '0')
                'a' -> if (hour < 12) "AM" else "PM"
                else -> c.toString().repeat(run)
            }
        )
        i += run
    }
}

fun LocalDate.format(pattern: String): String = LocalDateTime(this, LocalTime(0, 0)).format(pattern)

fun LocalTime.format(pattern: String): String = LocalDateTime(LocalDate(2000, 1, 1), this).format(pattern)

fun Instant.format(pattern: String, zone: TimeZone = localZone()): String = local(zone).format(pattern)

/** Reads "2025-03-14 18:30", the form a typed-in start time takes; null if it isn't a real one. */
fun parseDateTime(text: String): LocalDateTime? {
    val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2}) (\d{1,2}):(\d{2})""").matchEntire(text.trim()) ?: return null
    val (y, mo, d, h, mi) = m.destructured
    return runCatching { LocalDateTime(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt()) }.getOrNull()
}
