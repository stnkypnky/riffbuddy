package com.riffbuddy.domain

import kotlin.math.abs

/*
 * Numbers as text, written here because String.format is Java's and the
 * browser build has none.
 */

/** [n] with at least [width] digits, zeros in front: 7 → "07". */
fun pad(n: Long, width: Int = 2): String = n.toString().padStart(width, '0')

fun pad(n: Int, width: Int = 2): String = pad(n.toLong(), width)

/**
 * [this] rounded to [decimals] places, as "%.1f" writes it: the shortest
 * decimal that is this double, rounded half up, so 9.95 gives "10.0".
 */
fun Double.toFixed(decimals: Int): String {
    // "9.95", "1.0E-7" on the JVM, "1e-7" in a browser.
    val parts = abs(this).toString().lowercase().split('e')
    val mantissa = parts[0]
    val point = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it }
    var digits = mantissa.replace(".", "")
    var pointAt = point + (parts.getOrNull(1)?.toInt() ?: 0)
    if (pointAt < 1) { digits = "0".repeat(1 - pointAt) + digits; pointAt = 1 }
    digits = digits.padEnd(pointAt + decimals + 1, '0')

    val kept = digits.take(pointAt + decimals).map { it - '0' }.toMutableList()
    if (digits[pointAt + decimals] >= '5') {
        var i = kept.lastIndex
        while (i >= 0 && kept[i] == 9) { kept[i] = 0; i-- }
        if (i >= 0) kept[i]++ else { kept.add(0, 1); pointAt++ }
    }
    val whole = kept.take(pointAt).joinToString("").trimStart('0').ifEmpty { "0" }
    val frac = kept.drop(pointAt).joinToString("")
    val sign = if (this < 0) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole.$frac"
}

/** Minutes and seconds, "3:07". */
fun minutesAndSeconds(seconds: Long): String = "${seconds / 60}:${pad(seconds % 60)}"

fun minutesAndSeconds(seconds: Int): String = minutesAndSeconds(seconds.toLong())

/** Adds [amount] to the running total under [key], starting from zero. */
fun <K> MutableMap<K, Long>.tally(key: K, amount: Long) {
    this[key] = (this[key] ?: 0L) + amount
}

/**
 * [value] encoded for a web address the way an HTML form sends it: letters,
 * digits and ".-*_" as they are, a space as "+", everything else as UTF-8
 * percent escapes.
 */
fun formEncode(value: String): String = buildString {
    value.encodeToByteArray().forEach { b ->
        val c = b.toInt().toChar()
        when {
            b >= 0 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ".-*_") -> append(c)
            c == ' ' -> append('+')
            else -> append('%').append(HEX[(b.toInt() shr 4) and 15]).append(HEX[b.toInt() and 15])
        }
    }
}

private const val HEX = "0123456789ABCDEF"
