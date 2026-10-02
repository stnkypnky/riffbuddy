package com.riffbuddy.domain.music

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Notes and frequencies.
 *
 * Everything is derived from MIDI note numbers and one reference pitch, rather
 * than from a table. A table would have to be regenerated whenever A4 moves —
 * and the settings let it move anywhere from 430 to 450 Hz, which some old
 * recordings genuinely need.
 */
object Notes {

    const val DEFAULT_A4 = 440.0
    val A4_RANGE = 430.0..450.0

    private val SHARP_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    /** MIDI 69 is A4. */
    fun frequencyOf(midi: Int, a4: Double = DEFAULT_A4): Double =
        a4 * 2.0.pow((midi - 69) / 12.0)

    /** The exact (fractional) MIDI number a frequency sits at. */
    fun midiOf(frequency: Double, a4: Double = DEFAULT_A4): Double =
        69 + 12 * log2(frequency / a4)

    /** How far [frequency] is from [target], in cents. Positive is sharp. */
    fun centsBetween(frequency: Double, target: Double): Double =
        1200 * log2(frequency / target)

    /**
     * Parses "E2", "F#3", "Bb1" into a MIDI number.
     *
     * Scientific pitch notation, where C4 is middle C and E2 is the guitar's
     * low string. Returns null rather than throwing: tuning definitions come
     * from a bundled asset and from the player's own custom tunings, and one
     * typo should not take the tuner down.
     */
    fun parse(name: String): Int? {
        val match = Regex("^([A-Ga-g])([#b]?)(-?\\d+)$").find(name.trim()) ?: return null
        val (letter, accidental, octave) = match.destructured
        val base = when (letter.uppercase()) {
            "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5; "G" -> 7; "A" -> 9; "B" -> 11
            else -> return null
        }
        val shift = when (accidental) {
            "#" -> 1; "b" -> -1; else -> 0
        }
        val octaveNumber = octave.toIntOrNull() ?: return null
        return (octaveNumber + 1) * 12 + base + shift
    }

    fun name(midi: Int, preferFlats: Boolean = false): String {
        val names = if (preferFlats) FLAT_NAMES else SHARP_NAMES
        val octave = midi / 12 - 1
        return "${names[((midi % 12) + 12) % 12]}$octave"
    }

    /** The note a frequency is nearest, and how far off it is. */
    fun nearest(frequency: Double, a4: Double = DEFAULT_A4): NoteReading {
        val midi = midiOf(frequency, a4)
        val rounded = midi.roundToInt()
        val target = frequencyOf(rounded, a4)
        return NoteReading(
            frequency = frequency,
            midi = rounded,
            name = name(rounded),
            cents = centsBetween(frequency, target),
        )
    }

    private fun log2(value: Double): Double = ln(value) / ln(2.0)
}

/** A detected pitch, described. */
data class NoteReading(
    val frequency: Double,
    val midi: Int,
    val name: String,
    val cents: Double,
) {
    /** Within three cents counts as in tune. */
    val inTune: Boolean get() = abs(cents) <= IN_TUNE_CENTS

    val sharp: Boolean get() = cents > IN_TUNE_CENTS
    val flat: Boolean get() = cents < -IN_TUNE_CENTS

    companion object {
        const val IN_TUNE_CENTS = 3.0
    }
}
