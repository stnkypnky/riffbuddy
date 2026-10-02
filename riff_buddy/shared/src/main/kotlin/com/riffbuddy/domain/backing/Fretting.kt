package com.riffbuddy.domain.backing

/**
 * Where on the neck a generated note is played, so a backing track can be
 * shown as tab — which is what most guitarists read — and not only as notation.
 *
 * Each note takes the highest string it fits on with the lowest fret there,
 * highest note first, one note per string. For the shapes the generator
 * writes (power chords, root-fifth boogies, bass lines) that lands on the
 * fingerings a player would use: open strings and the first few frets.
 */
object Fretting {
    /** Standard tuning, low to high, as MIDI. */
    val GUITAR = listOf(40, 45, 50, 55, 59, 64)

    /** Four-string bass, E1 A1 D2 G2. */
    val BASS = listOf(28, 33, 38, 43)

    const val MAX_FRET = 15

    /**
     * alphaTex "fret.string" for each note — strings numbered from 1, the
     * highest. Null if the notes cannot all be placed, so the caller can fall
     * back to plain pitches rather than write a wrong tab.
     */
    fun place(midi: List<Int>, tuning: List<Int>): List<String>? {
        val used = mutableSetOf<Int>()
        return midi.sortedDescending().map { note ->
            val string = tuning.indices.reversed().firstOrNull { it !in used && note - tuning[it] in 0..MAX_FRET }
                ?: return null
            used += string
            "${note - tuning[string]}.${tuning.size - string}"
        }
    }
}
