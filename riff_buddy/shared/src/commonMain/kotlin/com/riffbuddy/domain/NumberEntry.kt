package com.riffbuddy.domain

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Reading a number the player typed: "63", "62.5", "137 bpm", "432,5". Kept
 * apart from the screen so the rules are tested on the JVM.
 *
 * A typed value is never silently changed into a different one. Out of range,
 * it is refused with the range in words, so "500" for a tempo says "20 to 300"
 * rather than quietly becoming 300; the field keeps what was typed until it is
 * fixed or abandoned.
 */
object NumberEntry {

    sealed interface Result {
        data class Ok(val value: Double) : Result
        /** Nothing a number can be read from. */
        data object NotANumber : Result
        data class OutOfRange(val value: Double) : Result
    }

    /**
     * [decimals] is how many places the setting keeps; more are rounded to it
     * (a speed of 62.53 is 62.5 when the setting keeps one place).
     */
    fun parse(text: String, range: ClosedFloatingPointRange<Double>, decimals: Int = 0): Result {
        val cleaned = text.trim()
            // A comma is a decimal point in much of the world.
            .replace(',', '.')
            // A unit typed after the number ("137 bpm", "75%", "440hz").
            .trimEnd { !it.isDigit() && it != '.' }
            .trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "-") return Result.NotANumber
        val raw = cleaned.toDoubleOrNull() ?: return Result.NotANumber
        if (raw.isNaN() || raw.isInfinite()) return Result.NotANumber
        val value = round(raw, decimals)
        return if (value < range.start || value > range.endInclusive) Result.OutOfRange(value) else Result.Ok(value)
    }

    fun round(value: Double, decimals: Int): Double {
        val f = 10.0.pow(decimals)
        return (value * f).roundToLong() / f
    }

    /** "63", or "62.5" — no trailing ".0" on a whole number. */
    fun format(value: Double, decimals: Int = 0): String {
        val r = round(value, decimals)
        return if (decimals == 0 || r == r.roundToLong().toDouble()) r.roundToLong().toString()
        else r.toFixed(decimals).trimEnd('0').trimEnd('.')
    }

    /** "20 to 300 BPM" — the range in words, for the hint under a field. */
    fun describe(range: ClosedFloatingPointRange<Double>, decimals: Int, unit: String): String {
        val u = if (unit.isEmpty()) "" else if (unit == "%") "%" else " $unit"
        return "${format(range.start, decimals)} to ${format(range.endInclusive, decimals)}$u"
    }

    /**
     * A time of day: "7:30", "19:30", "7:30 pm", "730", "7pm". Null when it
     * isn't one. Answers hours 0–23 and minutes 0–59.
     */
    fun parseTime(text: String): Pair<Int, Int>? {
        val t = text.trim().lowercase().replace(".", "").replace(" ", "")
        val pm = t.endsWith("pm") || t.endsWith("p")
        val am = t.endsWith("am") || t.endsWith("a")
        val digits = t.removeSuffix("pm").removeSuffix("am").removeSuffix("p").removeSuffix("a")
        val (h, m) = when {
            ':' in digits -> {
                val parts = digits.split(':')
                if (parts.size != 2) return null
                (parts[0].toIntOrNull() ?: return null) to (parts[1].toIntOrNull() ?: return null)
            }
            digits.length <= 2 -> (digits.toIntOrNull() ?: return null) to 0
            digits.length in 3..4 -> {
                val n = digits.toIntOrNull() ?: return null
                (n / 100) to (n % 100)
            }
            else -> return null
        }
        if (m !in 0..59) return null
        val hour = when {
            pm || am -> {
                if (h !in 1..12) return null
                (h % 12) + if (pm) 12 else 0
            }
            else -> if (h in 0..23) h else return null
        }
        return hour to m
    }
}
