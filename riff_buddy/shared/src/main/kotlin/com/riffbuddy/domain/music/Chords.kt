package com.riffbuddy.domain.music

/** Chord types, as intervals from the root. */
enum class ChordQuality(val label: String, val suffix: String, val semitones: List<Int>) {
    MAJOR("Major", "", listOf(0, 4, 7)),
    MINOR("Minor", "m", listOf(0, 3, 7)),
    POWER("Power chord", "5", listOf(0, 7)),
    DOMINANT_7("Dominant 7", "7", listOf(0, 4, 7, 10)),
    MAJOR_7("Major 7", "maj7", listOf(0, 4, 7, 11)),
    MINOR_7("Minor 7", "m7", listOf(0, 3, 7, 10)),
    SUS2("Suspended 2", "sus2", listOf(0, 2, 7)),
    SUS4("Suspended 4", "sus4", listOf(0, 5, 7)),
    ADD9("Add 9", "add9", listOf(0, 4, 7, 14)),
    DIMINISHED("Diminished", "dim", listOf(0, 3, 6)),
    DIMINISHED_7("Diminished 7", "dim7", listOf(0, 3, 6, 9)),
    HALF_DIMINISHED("Half diminished", "m7b5", listOf(0, 3, 6, 10)),
    AUGMENTED("Augmented", "aug", listOf(0, 4, 8)),
    SIXTH("Sixth", "6", listOf(0, 4, 7, 9)),
    MINOR_6("Minor 6", "m6", listOf(0, 3, 7, 9)),
    NINTH("Ninth", "9", listOf(0, 4, 7, 10, 14));

    /** The pitch classes this chord is made of. */
    fun pitchClasses(root: Int): Set<Int> = semitones.map { (root + it).mod(12) }.toSet()

    companion object {
        val COMMON = listOf(MAJOR, MINOR, POWER, DOMINANT_7, MAJOR_7, MINOR_7, SUS2, SUS4)
    }
}

/**
 * One way of playing a chord.
 *
 * [frets] runs low string to high. -1 is a muted string, 0 is open. [fingers]
 * matches it, 0 meaning open or muted.
 */
data class Voicing(
    val name: String,
    val frets: List<Int>,
    val fingers: List<Int> = emptyList(),
    val baseFret: Int = 1,
    val isMoveable: Boolean = false,
) {
    /** The notes this actually sounds, given a tuning. */
    fun midiNotes(tuning: Tuning): List<Int> =
        frets.mapIndexedNotNull { string, fret ->
            if (fret < 0) null else tuning.midi.getOrNull(string)?.plus(fret)
        }

    fun pitchClasses(tuning: Tuning): Set<Int> = midiNotes(tuning).map { it.mod(12) }.toSet()
}

data class Chord(val root: Int, val quality: ChordQuality, val voicings: List<Voicing>) {
    fun name(preferFlats: Boolean = false): String =
        Notes.name(root + 60, preferFlats).dropLast(1) + quality.suffix
}

/**
 * Curated chord shapes for standard tuning.
 *
 * Shapes are given rather than generated because a generated chord is not
 * necessarily a playable one — a computer will happily produce a voicing that
 * needs six fingers or a hand span of seven frets. These are the shapes people
 * actually use.
 *
 * Every one is checked by `ChordsTest` against the chord's own interval formula,
 * so a mistyped fret becomes a failing test rather than a chord someone learns
 * wrong.
 */
object ChordLibrary {

    private const val X = -1

    /** Open shapes, standard tuning, low to high. */
    private val OPEN: Map<Pair<Int, ChordQuality>, List<Voicing>> = mapOf(
        // E
        4 to ChordQuality.MAJOR to listOf(
            Voicing("Open E", listOf(0, 2, 2, 1, 0, 0), listOf(0, 2, 3, 1, 0, 0)),
        ),
        4 to ChordQuality.MINOR to listOf(
            Voicing("Open Em", listOf(0, 2, 2, 0, 0, 0), listOf(0, 2, 3, 0, 0, 0)),
        ),
        4 to ChordQuality.DOMINANT_7 to listOf(
            Voicing("Open E7", listOf(0, 2, 0, 1, 0, 0), listOf(0, 2, 0, 1, 0, 0)),
        ),
        4 to ChordQuality.MINOR_7 to listOf(
            Voicing("Open Em7", listOf(0, 2, 0, 0, 0, 0), listOf(0, 2, 0, 0, 0, 0)),
        ),
        // A
        9 to ChordQuality.MAJOR to listOf(
            Voicing("Open A", listOf(X, 0, 2, 2, 2, 0), listOf(0, 0, 1, 2, 3, 0)),
        ),
        9 to ChordQuality.MINOR to listOf(
            Voicing("Open Am", listOf(X, 0, 2, 2, 1, 0), listOf(0, 0, 2, 3, 1, 0)),
        ),
        9 to ChordQuality.DOMINANT_7 to listOf(
            Voicing("Open A7", listOf(X, 0, 2, 0, 2, 0), listOf(0, 0, 2, 0, 3, 0)),
        ),
        9 to ChordQuality.MINOR_7 to listOf(
            Voicing("Open Am7", listOf(X, 0, 2, 0, 1, 0), listOf(0, 0, 2, 0, 1, 0)),
        ),
        9 to ChordQuality.SUS2 to listOf(
            Voicing("Open Asus2", listOf(X, 0, 2, 2, 0, 0), listOf(0, 0, 1, 2, 0, 0)),
        ),
        9 to ChordQuality.SUS4 to listOf(
            Voicing("Open Asus4", listOf(X, 0, 2, 2, 3, 0), listOf(0, 0, 1, 2, 3, 0)),
        ),
        // D
        2 to ChordQuality.MAJOR to listOf(
            Voicing("Open D", listOf(X, X, 0, 2, 3, 2), listOf(0, 0, 0, 1, 3, 2)),
        ),
        2 to ChordQuality.MINOR to listOf(
            Voicing("Open Dm", listOf(X, X, 0, 2, 3, 1), listOf(0, 0, 0, 2, 3, 1)),
        ),
        2 to ChordQuality.DOMINANT_7 to listOf(
            Voicing("Open D7", listOf(X, X, 0, 2, 1, 2), listOf(0, 0, 0, 2, 1, 3)),
        ),
        2 to ChordQuality.SUS2 to listOf(
            Voicing("Open Dsus2", listOf(X, X, 0, 2, 3, 0), listOf(0, 0, 0, 1, 2, 0)),
        ),
        2 to ChordQuality.SUS4 to listOf(
            Voicing("Open Dsus4", listOf(X, X, 0, 2, 3, 3), listOf(0, 0, 0, 1, 2, 3)),
        ),
        // G
        7 to ChordQuality.MAJOR to listOf(
            Voicing("Open G", listOf(3, 2, 0, 0, 0, 3), listOf(2, 1, 0, 0, 0, 3)),
        ),
        7 to ChordQuality.DOMINANT_7 to listOf(
            Voicing("Open G7", listOf(3, 2, 0, 0, 0, 1), listOf(3, 2, 0, 0, 0, 1)),
        ),
        // C
        0 to ChordQuality.MAJOR to listOf(
            Voicing("Open C", listOf(X, 3, 2, 0, 1, 0), listOf(0, 3, 2, 0, 1, 0)),
        ),
        0 to ChordQuality.MAJOR_7 to listOf(
            Voicing("Open Cmaj7", listOf(X, 3, 2, 0, 0, 0), listOf(0, 3, 2, 0, 0, 0)),
        ),
        0 to ChordQuality.DOMINANT_7 to listOf(
            Voicing("Open C7", listOf(X, 3, 2, 3, 1, 0), listOf(0, 3, 2, 4, 1, 0)),
        ),
        // F, the one everybody struggles with.
        5 to ChordQuality.MAJOR to listOf(
            Voicing("F barre (E shape)", listOf(1, 3, 3, 2, 1, 1), listOf(1, 3, 4, 2, 1, 1)),
            // The four-string F, which is how most people first play it —
            // no barre, and the two low strings simply left out.
            Voicing("F (small)", listOf(X, X, 3, 2, 1, 1), listOf(0, 0, 3, 2, 1, 1)),
        ),
        // Fmaj7 is where the open-E version belongs: it sounds an E, the major
        // seventh, which is not in an F triad at all.
        5 to ChordQuality.MAJOR_7 to listOf(
            Voicing("Open Fmaj7", listOf(X, X, 3, 2, 1, 0), listOf(0, 0, 3, 2, 1, 0)),
        ),
    )

    /**
     * Moveable shapes, expressed relative to their root.
     *
     * The offsets are from the root note's own fret, so the same shape serves
     * all twelve keys — which is exactly why guitarists learn them.
     */
    private val MOVEABLE_E_ROOT = listOf(
        Voicing("E shape barre", listOf(0, 2, 2, 1, 0, 0), listOf(1, 3, 4, 2, 1, 1), isMoveable = true),
        Voicing("Em shape barre", listOf(0, 2, 2, 0, 0, 0), listOf(1, 3, 4, 1, 1, 1), isMoveable = true),
        Voicing("E7 shape barre", listOf(0, 2, 0, 1, 0, 0), listOf(1, 3, 1, 2, 1, 1), isMoveable = true),
        Voicing("Em7 shape barre", listOf(0, 2, 0, 0, 0, 0), listOf(1, 3, 1, 1, 1, 1), isMoveable = true),
        Voicing("Power chord", listOf(0, 2, 2, X, X, X), listOf(1, 3, 4, 0, 0, 0), isMoveable = true),
    )

    private val MOVEABLE_A_ROOT = listOf(
        Voicing("A shape barre", listOf(X, 0, 2, 2, 2, 0), listOf(0, 1, 3, 3, 3, 1), isMoveable = true),
        Voicing("Am shape barre", listOf(X, 0, 2, 2, 1, 0), listOf(0, 1, 3, 4, 2, 1), isMoveable = true),
        Voicing("A7 shape barre", listOf(X, 0, 2, 0, 2, 0), listOf(0, 1, 3, 1, 4, 1), isMoveable = true),
        Voicing("Am7 shape barre", listOf(X, 0, 2, 0, 1, 0), listOf(0, 1, 3, 1, 2, 1), isMoveable = true),
        Voicing("Power chord (A string)", listOf(X, 0, 2, 2, X, X), listOf(0, 1, 3, 4, 0, 0), isMoveable = true),
    )

    private val E_SHAPE_QUALITY = listOf(
        ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.DOMINANT_7,
        ChordQuality.MINOR_7, ChordQuality.POWER,
    )

    /** Every voicing for a chord in standard tuning: open shapes plus barres. */
    fun voicings(root: Int, quality: ChordQuality): List<Voicing> {
        val open = OPEN[root.mod(12) to quality].orEmpty()

        val barres = buildList {
            // E-shape barre: the root sits on the sixth string.
            E_SHAPE_QUALITY.indexOf(quality).takeIf { it >= 0 }?.let { index ->
                val shift = (root.mod(12) - 4).mod(12)
                if (shift in 1..11) {
                    val shape = MOVEABLE_E_ROOT[index]
                    add(shape.copy(frets = shape.frets.map { if (it < 0) it else it + shift }, baseFret = shift))
                }
            }
            // A-shape barre: root on the fifth string.
            E_SHAPE_QUALITY.indexOf(quality).takeIf { it >= 0 }?.let { index ->
                val shift = (root.mod(12) - 9).mod(12)
                if (shift in 1..11) {
                    val shape = MOVEABLE_A_ROOT[index]
                    add(shape.copy(frets = shape.frets.map { if (it < 0) it else it + shift }, baseFret = shift))
                }
            }
        }

        return open + barres
    }

    fun chord(root: Int, quality: ChordQuality): Chord =
        Chord(root.mod(12), quality, voicings(root, quality))

    /** Every curated open shape, for the test that validates them all. */
    fun allOpenVoicings(): List<Triple<Int, ChordQuality, Voicing>> =
        OPEN.flatMap { (key, voicings) -> voicings.map { Triple(key.first, key.second, it) } }
}

/**
 * Favorite voicings, kept in one setting as keys.
 *
 * A key is the chord and the exact frets, so it names a shape rather than a
 * position in a list — adding a voicing to the library later cannot make a
 * favorite point at a different one.
 */
object ChordFavorites {
    const val SETTING = "chord_favourites"

    fun key(root: Int, quality: ChordQuality, voicing: Voicing): String =
        "${root.mod(12)}:${quality.name}:${voicing.frets.joinToString(",")}"

    fun parse(stored: String?): Set<String> =
        stored?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun format(keys: Set<String>): String = keys.sorted().joinToString(";")

    /** The favorites that still name a curated voicing, as chords to show. */
    fun resolve(keys: Set<String>): List<Triple<Int, ChordQuality, Voicing>> =
        keys.mapNotNull { k ->
            val parts = k.split(":")
            if (parts.size != 3) return@mapNotNull null
            val root = parts[0].toIntOrNull() ?: return@mapNotNull null
            val quality = runCatching { ChordQuality.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
            ChordLibrary.voicings(root, quality).firstOrNull { key(root, quality, it) == k }
                ?.let { Triple(root, quality, it) }
        }.sortedWith(compareBy({ it.first }, { it.second.ordinal }))
}

