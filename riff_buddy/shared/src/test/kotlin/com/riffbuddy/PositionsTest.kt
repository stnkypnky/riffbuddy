package com.riffbuddy

import com.riffbuddy.domain.music.ChordFavorites
import com.riffbuddy.domain.music.ChordLibrary
import com.riffbuddy.domain.music.ChordQuality
import com.riffbuddy.domain.music.Fretboard
import com.riffbuddy.domain.music.Scale
import com.riffbuddy.domain.music.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Three-notes-per-string and CAGED overlays; favorite chord shapes. */
class PositionsTest {

    private val standard = Tuning.STANDARD

    @Test
    fun `G major 3nps position one matches the reference shape`() {
        // Worked by hand from a method book: low E 3-5-7, A 3-5-7, D 4-5-7,
        // G 4-5-7, B 5-7-8, high E 5-7-8.
        val expected = mapOf(
            0 to listOf(3, 5, 7), 1 to listOf(3, 5, 7), 2 to listOf(4, 5, 7),
            3 to listOf(4, 5, 7), 4 to listOf(5, 7, 8), 5 to listOf(5, 7, 8),
        ).flatMap { (s, frets) -> frets.map { s to it } }.toSet()
        val positions = Fretboard.threeNotesPerString(standard, root = 7, scale = Scale.MAJOR)
        assertEquals(7, positions.size)
        assertTrue("G-rooted position present", positions.any { it.cells == expected })
    }

    @Test
    fun `every 3nps cell is a scale note, three per string`() {
        val classes = Scale.NATURAL_MINOR.pitchClasses(9)
        Fretboard.threeNotesPerString(standard, 9, Scale.NATURAL_MINOR).map { it.cells }.forEach { cells ->
            cells.forEach { (s, f) -> assertTrue((standard.midi[s] + f).mod(12) in classes) }
            cells.groupBy { it.first }.values.forEach { assertEquals(3, it.size) }
        }
    }

    @Test
    fun `pentatonic scales have no 3nps positions`() {
        assertTrue(Fretboard.threeNotesPerString(standard, 9, Scale.MINOR_PENTATONIC).isEmpty())
    }

    private fun cells(vararg strings: Pair<Int, List<Int>>) =
        strings.flatMap { (s, frets) -> frets.map { s to it } }.toSet()

    @Test
    fun `C major CAGED shapes sit where the open chords do`() {
        val shapes = Fretboard.cagedRegions(standard, root = 0, scale = Scale.MAJOR)
        assertEquals(listOf("C", "A", "G", "E", "D"), shapes.map { it.name.first().toString() })
        // The open C position, as a method book draws it.
        assertEquals(
            cells(0 to listOf(0, 1, 3), 1 to listOf(0, 2, 3), 2 to listOf(0, 2, 3),
                3 to listOf(0, 2), 4 to listOf(0, 1, 3), 5 to listOf(0, 1, 3)),
            shapes[0].cells,
        )
        // The A shape around the barre at 3, with the F on the B string's
        // sixth fret as the little-finger stretch.
        assertEquals(
            cells(0 to listOf(3, 5), 1 to listOf(2, 3, 5), 2 to listOf(2, 3, 5),
                3 to listOf(2, 4, 5), 4 to listOf(3, 5, 6), 5 to listOf(3, 5)),
            shapes[1].cells,
        )
    }

    @Test
    fun `G major E shape is the method-book pattern`() {
        val e = Fretboard.cagedRegions(standard, root = 7, scale = Scale.MAJOR).first { it.name == "E shape" }
        assertEquals(
            cells(0 to listOf(2, 3, 5), 1 to listOf(2, 3, 5), 2 to listOf(2, 4, 5),
                3 to listOf(2, 4, 5), 4 to listOf(3, 5), 5 to listOf(2, 3, 5)),
            e.cells,
        )
    }

    /**
     * A guitar teacher's catch: A Phrygian's E shape was drawn over frets 5-7,
     * the Em chord's own frets, and left the C on fret 8 of both E strings out.
     */
    @Test
    fun `Phrygian E shape covers frets 5 to 8`() {
        val e = Fretboard.cagedRegions(standard, root = 9, scale = Scale.PHRYGIAN).first { it.name == "E shape" }
        assertEquals(5..8, e.frets)
        assertEquals(
            cells(0 to listOf(5, 6, 8), 1 to listOf(5, 7, 8), 2 to listOf(5, 7, 8),
                3 to listOf(5, 7), 4 to listOf(5, 6, 8), 5 to listOf(5, 6, 8)),
            e.cells,
        )
    }

    /**
     * The rule that would have caught it: a position never skips a scale note
     * between its lowest and highest, and holds nothing outside the scale.
     * Every root, every scale, every kind of position.
     */
    @Test
    fun `no position leaves out a note it passes over`() {
        for (root in 0..11) for (scale in Scale.entries) {
            val classes = scale.pitchClasses(root)
            val all = Fretboard.pentatonicBoxes(standard, root, scale) +
                Fretboard.threeNotesPerString(standard, root, scale) +
                Fretboard.cagedRegions(standard, root, scale)
            all.forEach { position ->
                val pitches = position.cells.map { (s, f) -> standard.midi[s] + f }
                assertEquals("$root ${scale.label} ${position.name}: a note twice", pitches.size, pitches.toSet().size)
                assertTrue(pitches.all { it.mod(12) in classes })
                val expected = (pitches.min()..pitches.max()).filter { it.mod(12) in classes }
                assertEquals("$root ${scale.label} ${position.name} ${position.cells}", expected, pitches.sorted())
            }
        }
    }

    @Test
    fun `CAGED is offered only in tunings shaped like standard`() {
        assertTrue(Fretboard.cagedRegions(Tuning.byName("Drop D")!!, 2, Scale.MAJOR).isEmpty())
        // Half a step down is standard's shapes, a fret lower in pitch.
        val eFlat = Tuning.byName("Half step down (Eb)")!!
        assertEquals(
            Fretboard.cagedRegions(standard, 7, Scale.MAJOR).map { it.cells },
            Fretboard.cagedRegions(eFlat, 6, Scale.MAJOR).map { it.cells },
        )
    }

    @Test
    fun `a favorite round-trips and resolves to the same shape`() {
        val v = ChordLibrary.voicings(7, ChordQuality.MAJOR).first()
        val keys = setOf(ChordFavorites.key(7, ChordQuality.MAJOR, v))
        val back = ChordFavorites.parse(ChordFavorites.format(keys))
        assertEquals(keys, back)
        assertEquals(v, ChordFavorites.resolve(back).single().third)
    }

    @Test
    fun `a favorite that no longer names a shape is dropped, not shown wrong`() {
        assertTrue(ChordFavorites.resolve(setOf("7:MAJOR:9,9,9,9,9,9", "junk")).isEmpty())
    }
}
