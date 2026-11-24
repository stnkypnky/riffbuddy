package com.riffbuddy.domain.music

/**
 * A scale or a shape the player made, named and saved.
 *
 * Two kinds, because they answer different questions. A [Kind.SCALE] is a set
 * of notes (pitch classes, 0 for C up to 11 for B) and shows everywhere those
 * notes fall, like any built-in scale. [Kind.DOTS] is exact places on the
 * neck, a string and a fret each, for a lick or a chord shape, and keeps the
 * tuning it was placed in, since the same frets in another tuning are
 * different notes.
 *
 * [root] is a pitch class either way: the dots ringed as "home" and the
 * degrees counted from.
 */
data class CustomShape(
    val id: Long = 0,
    val name: String,
    val kind: Kind,
    val root: Int,
    val pitchClasses: Set<Int> = emptySet(),
    val cells: Set<Pair<Int, Int>> = emptySet(),
    val tuningName: String? = null,
) {
    enum class Kind { SCALE, DOTS }
}

object CustomShapes {

    /**
     * The notes as stored: "0,3,5,7,10" for a scale, "0:5,1:7" (string:fret)
     * for dots. Plain text rather than JSON so it can be read without a
     * library and compared by eye in a database browser.
     */
    fun encode(shape: CustomShape): String = when (shape.kind) {
        CustomShape.Kind.SCALE -> shape.pitchClasses.sorted().joinToString(",")
        CustomShape.Kind.DOTS -> shape.cells.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString(",") { (s, f) -> "$s:$f" }
    }

    /** What [encode] wrote, back as notes; anything unreadable is skipped rather than failing the shape. */
    fun decodeScale(text: String): Set<Int> =
        text.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..11 }.toSet()

    fun decodeDots(text: String): Set<Pair<Int, Int>> =
        text.split(',').mapNotNull { part ->
            val (s, f) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val string = s.trim().toIntOrNull() ?: return@mapNotNull null
            val fret = f.trim().toIntOrNull() ?: return@mapNotNull null
            if (string in 0..7 && fret in 0..Fretboard.MAX_FRET) string to fret else null
        }.toSet()

    /**
     * The built-in scales with exactly these notes, the ones from [root]
     * first, so a scale built by ear can be told "that's A Dorian". Scales
     * that are the same notes under two names (Major and Ionian) are given
     * once, by the first name in the picker.
     */
    fun sameNotesAs(root: Int, classes: Set<Int>): List<Pair<Int, Scale>> {
        if (classes.isEmpty()) return emptyList()
        val seen = mutableSetOf<List<Int>>()
        val unique = (Scale.COMMON + Scale.MODES + Scale.EXOTIC).filter { seen.add(it.semitones) }
        return (0..11).flatMap { r -> unique.filter { it.pitchClasses(r) == classes }.map { r to it } }
            .sortedBy { (r, _) -> if (r == root.mod(12)) 0 else 1 }
    }

    /** Dots as neck positions, with degrees counted from [root]; dots off this neck are left out. */
    fun dotPositions(tuning: Tuning, cells: Set<Pair<Int, Int>>, root: Int): List<FretPosition> =
        cells.filter { (s, f) -> s < tuning.stringCount && f in 0..Fretboard.MAX_FRET }.map { (s, f) ->
            val midi = tuning.midi[s] + f
            FretPosition(
                string = s,
                fret = f,
                midi = midi,
                pitchClass = midi.mod(12),
                degree = Degrees.of(root, midi.mod(12)),
                isRoot = midi.mod(12) == root.mod(12),
            )
        }

    /** The root a set of dots has until the player picks one: the lowest note's. */
    fun lowestRoot(tuning: Tuning, cells: Set<Pair<Int, Int>>): Int? =
        cells.filter { it.first < tuning.stringCount }.minOfOrNull { (s, f) -> tuning.midi[s] + f }?.mod(12)
}
