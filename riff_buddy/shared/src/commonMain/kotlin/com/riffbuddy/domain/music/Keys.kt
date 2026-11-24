package com.riffbuddy.domain.music

/** A chord in a key, with the numeral players think in. */
data class DiatonicChord(
    val numeral: String,
    val root: Int,
    val quality: ChordQuality,
) {
    fun name(preferFlats: Boolean = false): String =
        Notes.name(root + 60, preferFlats).dropLast(1) + quality.suffix
}

data class Progression(val name: String, val numerals: List<String>) {
    /** The actual chords, in a given key. */
    fun chordsIn(key: Key): List<DiatonicChord> =
        numerals.mapNotNull { numeral -> key.chords.firstOrNull { it.numeral == numeral } }
}

/**
 * A key and what is in it.
 *
 * The qualities are fixed by the scale's own intervals — a major key's third
 * chord is minor because the notes make it so, not because a table says it is.
 * Building them from the scale is what makes all twenty-four keys correct at
 * once rather than twenty-four chances to mistype something.
 */
data class Key(val tonic: Int, val isMinor: Boolean) {

    val scale: Scale get() = if (isMinor) Scale.NATURAL_MINOR else Scale.MAJOR

    fun name(preferFlats: Boolean = false): String =
        Notes.name(tonic + 60, preferFlats).dropLast(1) + if (isMinor) " minor" else " major"

    /** The seven chords built on each degree of the scale. */
    val chords: List<DiatonicChord>
        get() {
            val degrees = scale.semitones
            val numerals = if (isMinor) MINOR_NUMERALS else MAJOR_NUMERALS
            return degrees.mapIndexed { index, offset ->
                val root = (tonic + offset).mod(12)
                DiatonicChord(numerals[index], root, qualityAt(index, degrees))
            }
        }

    /**
     * The quality of the chord on a degree, worked out from the scale itself:
     * stack thirds and see what comes out.
     */
    private fun qualityAt(degree: Int, degrees: List<Int>): ChordQuality {
        fun step(offset: Int) = degrees[(degree + offset).mod(7)]
        val root = step(0)
        val third = (step(2) - root).mod(12)
        val fifth = (step(4) - root).mod(12)
        return when {
            third == 4 && fifth == 7 -> ChordQuality.MAJOR
            third == 3 && fifth == 7 -> ChordQuality.MINOR
            third == 3 && fifth == 6 -> ChordQuality.DIMINISHED
            third == 4 && fifth == 8 -> ChordQuality.AUGMENTED
            else -> ChordQuality.MAJOR
        }
    }

    /** The key that shares these notes — every guitarist's shortcut. */
    val relative: Key
        get() = if (isMinor) Key((tonic + 3).mod(12), false)
        else Key((tonic + 9).mod(12), true)

    /** What to solo with over this key. */
    val soloingScales: List<Scale>
        get() = if (isMinor) {
            listOf(Scale.MINOR_PENTATONIC, Scale.NATURAL_MINOR, Scale.BLUES, Scale.DORIAN)
        } else {
            listOf(Scale.MAJOR_PENTATONIC, Scale.MAJOR, Scale.MIXOLYDIAN, Scale.BLUES)
        }

    /** Moving a whole key up or down, for singers and capos. */
    fun transpose(semitones: Int): Key = copy(tonic = (tonic + semitones).mod(12))

    companion object {
        private val MAJOR_NUMERALS = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
        private val MINOR_NUMERALS = listOf("i", "ii°", "III", "iv", "v", "VI", "VII")

        val PROGRESSIONS = listOf(
            Progression("12-bar blues", listOf("I", "I", "I", "I", "IV", "IV", "I", "I", "V", "IV", "I", "V")),
            Progression("I–V–vi–IV", listOf("I", "V", "vi", "IV")),
            Progression("ii–V–I", listOf("ii", "V", "I")),
            Progression("I–vi–IV–V", listOf("I", "vi", "IV", "V")),
            Progression("i–VII–VI", listOf("i", "VII", "VI")),
            Progression("i–iv–v", listOf("i", "iv", "v")),
        )

        /** Only the progressions whose numerals exist in this key. */
        fun progressionsFor(key: Key): List<Progression> =
            PROGRESSIONS.filter { progression ->
                progression.numerals.all { numeral -> key.chords.any { it.numeral == numeral } }
            }
    }
}
