package com.riffbuddy.domain.music

/**
 * Scales as interval formulas.
 *
 * Notes are computed from the tuning and the formulas rather than stored as
 * pictures, and the reason is immediately practical: a guitarist may play in
 * Drop D and half a dozen other tunings, and a stored
 * diagram is wrong in every one of them. Fifteen formulas and a tuning cover
 * every combination there will ever be.
 */
enum class Scale(val label: String, val semitones: List<Int>) {
    MAJOR("Major", listOf(0, 2, 4, 5, 7, 9, 11)),
    NATURAL_MINOR("Natural minor", listOf(0, 2, 3, 5, 7, 8, 10)),
    HARMONIC_MINOR("Harmonic minor", listOf(0, 2, 3, 5, 7, 8, 11)),
    MELODIC_MINOR("Melodic minor", listOf(0, 2, 3, 5, 7, 9, 11)),
    MAJOR_PENTATONIC("Major pentatonic", listOf(0, 2, 4, 7, 9)),
    MINOR_PENTATONIC("Minor pentatonic", listOf(0, 3, 5, 7, 10)),
    BLUES("Blues", listOf(0, 3, 5, 6, 7, 10)),

    IONIAN("Ionian", listOf(0, 2, 4, 5, 7, 9, 11)),
    DORIAN("Dorian", listOf(0, 2, 3, 5, 7, 9, 10)),
    PHRYGIAN("Phrygian", listOf(0, 1, 3, 5, 7, 8, 10)),
    LYDIAN("Lydian", listOf(0, 2, 4, 6, 7, 9, 11)),
    MIXOLYDIAN("Mixolydian", listOf(0, 2, 4, 5, 7, 9, 10)),
    AEOLIAN("Aeolian", listOf(0, 2, 3, 5, 7, 8, 10)),
    LOCRIAN("Locrian", listOf(0, 1, 3, 5, 6, 8, 10)),

    PHRYGIAN_DOMINANT("Phrygian dominant", listOf(0, 1, 4, 5, 7, 8, 10)),
    WHOLE_TONE("Whole tone", listOf(0, 2, 4, 6, 8, 10)),
    DIMINISHED_WH("Diminished (whole-half)", listOf(0, 2, 3, 5, 6, 8, 9, 11)),
    DIMINISHED_HW("Diminished (half-whole)", listOf(0, 1, 3, 4, 6, 7, 9, 10));

    /** Pitch classes this scale contains, for [root] as a pitch class 0-11. */
    fun pitchClasses(root: Int): Set<Int> =
        semitones.map { (root + it).mod(12) }.toSet()

    val isPentatonic: Boolean get() = semitones.size == 5

    companion object {
        /** The modes, grouped so the picker can offer them apart from the rest. */
        val MODES = listOf(IONIAN, DORIAN, PHRYGIAN, LYDIAN, MIXOLYDIAN, AEOLIAN, LOCRIAN)
        val COMMON = listOf(
            MAJOR, NATURAL_MINOR, MAJOR_PENTATONIC, MINOR_PENTATONIC, BLUES,
            HARMONIC_MINOR, MELODIC_MINOR,
        )
        val EXOTIC = listOf(PHRYGIAN_DOMINANT, WHOLE_TONE, DIMINISHED_WH, DIMINISHED_HW)
    }
}

/**
 * The degree names players actually use: 1, b3, 5, b7 and so on.
 *
 * Written out rather than derived, because the sharp-or-flat choice is
 * conventional rather than computable — a minor third is b3 and never #2, even
 * though they are the same fret.
 */
object Degrees {
    private val NAMES = listOf("1", "b2", "2", "b3", "3", "4", "b5", "5", "b6", "6", "b7", "7")

    fun of(root: Int, pitchClass: Int): String = NAMES[(pitchClass - root).mod(12)]
}

/** One playable note on the neck. */
data class FretPosition(
    val string: Int,
    val fret: Int,
    val midi: Int,
    val pitchClass: Int,
    val degree: String,
    val isRoot: Boolean,
)

/**
 * One hand position of a scale: exactly which notes, as
 * (string, fret) cells. The frets it covers fall out of the notes, so the
 * highlight on the neck can never leave one out.
 */
data class Position(val name: String, val cells: Set<Pair<Int, Int>>) {
    val frets: IntRange get() = cells.minOf { it.second }..cells.maxOf { it.second }

    /** The cells from the lowest note to the highest, for playing the position through. */
    fun ascending(tuning: Tuning): List<Pair<Int, Int>> =
        cells.sortedBy { (s, f) -> tuning.midi[s] + f }
}

/**
 * Where a scale falls on a particular neck.
 *
 * Tuning-aware by construction: the strings are whatever the tuning says, so
 * Drop D, a seven-string and DADGAD all work without a special case.
 */
object Fretboard {

    const val MAX_FRET = 24

    fun positions(
        tuning: Tuning,
        root: Int,
        scale: Scale,
        maxFret: Int = MAX_FRET,
    ): List<FretPosition> {
        val classes = scale.pitchClasses(root)
        return buildList {
            tuning.midi.forEachIndexed { stringIndex, openMidi ->
                for (fret in 0..maxFret) {
                    val midi = openMidi + fret
                    val pitchClass = midi.mod(12)
                    if (pitchClass !in classes) continue
                    add(
                        FretPosition(
                            string = stringIndex,
                            fret = fret,
                            midi = midi,
                            pitchClass = pitchClass,
                            degree = Degrees.of(root, pitchClass),
                            isRoot = pitchClass == root.mod(12),
                        )
                    )
                }
            }
        }
    }

    /**
     * The five pentatonic boxes, as the exact notes in each.
     *
     * A box is two notes per string, walked up the scale from one of its
     * degrees on the lowest string. Box 1 starts on the root; A minor's is the
     * one at the fifth fret that every guitarist means by "box one".
     *
     * Worked from pitches rather than as "four frets from the anchor". The
     * fixed width was wrong for three of the five: A minor's Box 2 starts on C
     * at fret 8 on the low E, but its A, D and G string notes sit at fret 7,
     * a column outside the box. Walking the pitches puts every note where the
     * hand actually finds it, and the B string's jog comes for free.
     *
     * Only meaningful for a pentatonic scale; anything else returns nothing
     * rather than inventing a shape that does not exist.
     */
    fun pentatonicBoxes(tuning: Tuning, root: Int, scale: Scale): List<Position> {
        if (!scale.isPentatonic || tuning.midi.isEmpty()) return emptyList()
        val classes = scale.pitchClasses(root)
        val lowest = tuning.midi.first()
        return scale.semitones.mapIndexed { index, offset ->
            val startFret = (0..11).first { (lowest + it).mod(12) == (root + offset).mod(12) }
            Position("Box ${index + 1}", walk(tuning, lowest + startFret, classes, perString = 2))
        }
    }

    /**
     * Walks up the scale from [start] on the lowest string, [perString] notes
     * to a string.
     *
     * One exception keeps drop tunings sane: a note that would land more than
     * a fret behind the shape on the next string stays on this one instead.
     * In Drop D the low string is a fifth below the next, and without this the
     * walk would send the hand back past the nut.
     */
    private fun walk(tuning: Tuning, start: Int, classes: Set<Int>, perString: Int): Set<Pair<Int, Int>> {
        val cells = mutableListOf<Pair<Int, Int>>()
        var string = 0
        var onString = 0
        var pitch = start
        while (string < tuning.stringCount) {
            cells += string to (pitch - tuning.midi[string])
            onString++
            pitch = (pitch + 1..pitch + 12).first { it.mod(12) in classes }
            if (onString < perString) continue
            val next = string + 1
            if (next >= tuning.stringCount) break
            val lowFret = cells.minOf { it.second }
            if (pitch - tuning.midi[next] >= lowFret - 1) { string = next; onString = 0 }
        }
        // A box starting on an open string can reach below the nut on a later
        // string; the same shape an octave up is the one to show.
        val shift = if (cells.any { it.second < 0 }) 12 else 0
        return cells.map { (s, f) -> s to f + shift }.filter { it.second <= MAX_FRET }.toSet()
    }

    /**
     * The three-notes-per-string positions: seven for a seven-note scale,
     * one starting on each degree.
     *
     * Built by walking up the scale from a degree on the lowest string and
     * putting three consecutive notes on each string in turn. The frets fall
     * out of the pitches, so the jog at the B string — and any tuning's
     * equivalent — comes for free instead of being a special case.
     */
    fun threeNotesPerString(tuning: Tuning, root: Int, scale: Scale): List<Position> {
        val classes = scale.pitchClasses(root)
        if (classes.size != 7 || tuning.midi.isEmpty()) return emptyList()
        val lowest = tuning.midi.first()
        return classes.map { pitchClass ->
            val startFret = (0..11).first { (lowest + it).mod(12) == pitchClass }
            val pitches = generateSequence(lowest + startFret) { p ->
                (p + 1..p + 12).first { it.mod(12) in classes }
            }.take(tuning.stringCount * 3).toList()
            val cells = tuning.midi.indices.flatMap { string ->
                (0..2).map { k -> string to (pitches[string * 3 + k] - tuning.midi[string]) }
            }
            // A shape starting near the nut can need a fret below it on a
            // later string. Dropping those notes left holes in the shape; the
            // same shape an octave up is whole.
            val shift = if (cells.any { it.second < 0 }) 12 else 0
            cells.map { (s, f) -> s to f + shift }.filter { (_, fret) -> fret <= MAX_FRET }.toSet()
        }.sortedBy { cells -> cells.minOf { it.second } }
            .mapIndexed { i, cells -> Position("3NPS ${i + 1}", cells) }
    }

    /**
     * The CAGED positions: the scale around each of the five open-chord
     * shapes of the root's chord, lowest on the neck first.
     *
     * The shape is the root's minor chord when the scale has a minor third —
     * Phrygian's E shape is built around an Em shape, not an E — and its major
     * chord otherwise.
     *
     * The scale around a shape is a four-fret hand position, one finger per
     * fret, that contains the whole chord. Most shapes fit two such positions;
     * the one kept is the one that leaves out fewest scale notes, and any it
     * does leave out are added as a little-finger stretch on the lower string.
     * These used to be the chord's own frets, which is three frets for the E
     * shape: A Phrygian's E shape needs frets 5 to 8, and the C on fret 8 of
     * both E strings was outside it.
     *
     * CAGED shapes are standard tuning's open chords moved up the neck, so
     * they are offered in any tuning with standard's intervals (E♭, D, C…)
     * and in no other, rather than shapes that would not match the fingers.
     */
    fun cagedRegions(tuning: Tuning, root: Int, scale: Scale): List<Position> {
        val intervals = tuning.midi.zipWithNext { a, b -> b - a }
        if (intervals != STANDARD_INTERVALS) return emptyList()
        val classes = scale.pitchClasses(root)
        val minor = 3 in scale.semitones && 4 !in scale.semitones
        // How far this tuning's open strings sit from standard's.
        val offset = tuning.midi.first() - Tuning.STANDARD.midi.first()

        return CAGED_SHAPES.map { shape ->
            val open = if (minor) shape.minor else shape.major
            val shift = (root - shape.letter - offset).mod(12)
            val chord = open.filter { it >= 0 }.map { it + shift }
            val lo = chord.min()
            val hi = chord.max()
            val window = (maxOf(0, hi - 3)..lo)
                .map { p -> handPosition(tuning, classes, p) }
                .minWith(compareBy({ it.second }, { it.first.minOf { c -> c.second } }))
                .first
            Position("${shape.name} shape", window)
        }.sortedBy { it.frets.first }
    }

    /**
     * Every scale note in frets [p]..p+3, plus a stretch to p+4 for each note
     * that falls in the gap between two strings; and how many stretches that
     * took.
     */
    private fun handPosition(tuning: Tuning, classes: Set<Int>, p: Int): Pair<Set<Pair<Int, Int>>, Int> {
        val cells = mutableSetOf<Pair<Int, Int>>()
        var stretches = 0
        tuning.midi.forEachIndexed { string, open ->
            (p..p + 3).filter { (open + it).mod(12) in classes }.forEach { cells += string to it }
            // The note above this string's window and below the next one's.
            val next = tuning.midi.getOrNull(string + 1) ?: return@forEachIndexed
            (open + p + 4 until next + p).filter { it.mod(12) in classes }.forEach {
                cells += string to (it - open)
                stretches++
            }
        }
        return cells to stretches
    }

    /**
     * The scale from its lowest root on the lowest string up one octave, in
     * one hand position: what Play means when the whole neck is showing
     * Each note stays on its string until it would need a reach past
     * the fourth fret of the position, then moves across.
     */
    fun oneOctave(tuning: Tuning, root: Int, scale: Scale): Position {
        val classes = scale.pitchClasses(root)
        val lowest = tuning.midi.first()
        val start = (0..11).first { (lowest + it).mod(12) == root.mod(12) }
        var string = 0
        val cells = (lowest + start..lowest + start + 12).filter { it.mod(12) in classes }.map { pitch ->
            while (string + 1 < tuning.stringCount && pitch - tuning.midi[string] > start + 3 &&
                pitch - tuning.midi[string + 1] >= maxOf(0, start - 1)
            ) string++
            string to (pitch - tuning.midi[string])
        }
        return Position("One octave", cells.toSet())
    }

    private val STANDARD_INTERVALS = listOf(5, 5, 5, 4, 5)

    /** The open chords CAGED is named after, low string to high; -1 is muted. */
    private class CagedShape(val name: String, val letter: Int, val major: List<Int>, val minor: List<Int>)

    private val CAGED_SHAPES = listOf(
        CagedShape("C", 0, listOf(-1, 3, 2, 0, 1, 0), listOf(-1, 3, 1, 0, 1, 3)),
        CagedShape("A", 9, listOf(-1, 0, 2, 2, 2, 0), listOf(-1, 0, 2, 2, 1, 0)),
        CagedShape("G", 7, listOf(3, 2, 0, 0, 0, 3), listOf(3, 1, 0, 0, 3, 3)),
        CagedShape("E", 4, listOf(0, 2, 2, 1, 0, 0), listOf(0, 2, 2, 0, 0, 0)),
        CagedShape("D", 2, listOf(-1, -1, 0, 2, 3, 2), listOf(-1, -1, 0, 2, 3, 1)),
    )

    /** Just the chord tones of a triad or seventh, for the arpeggio toggle. */
    fun arpeggioPositions(
        tuning: Tuning,
        root: Int,
        chord: ChordQuality,
        maxFret: Int = MAX_FRET,
    ): List<FretPosition> {
        val classes = chord.semitones.map { (root + it).mod(12) }.toSet()
        return buildList {
            tuning.midi.forEachIndexed { stringIndex, openMidi ->
                for (fret in 0..maxFret) {
                    val midi = openMidi + fret
                    val pitchClass = midi.mod(12)
                    if (pitchClass !in classes) continue
                    add(
                        FretPosition(
                            string = stringIndex,
                            fret = fret,
                            midi = midi,
                            pitchClass = pitchClass,
                            degree = Degrees.of(root, pitchClass),
                            isRoot = pitchClass == root.mod(12),
                        )
                    )
                }
            }
        }
    }
}
