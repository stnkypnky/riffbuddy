package com.riffbuddy

import com.riffbuddy.domain.music.ChordLibrary
import com.riffbuddy.domain.music.ChordQuality
import com.riffbuddy.domain.music.Degrees
import com.riffbuddy.domain.music.Fretboard
import com.riffbuddy.domain.music.Scale
import com.riffbuddy.domain.music.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleTest {

    private val standard = Tuning.STANDARD

    /** A major scale is the reference everything else is described against. */
    @Test
    fun `C major has no accidentals`() {
        assertEquals(
            setOf(0, 2, 4, 5, 7, 9, 11),
            Scale.MAJOR.pitchClasses(0),
        )
    }

    /** The modes are one scale started in seven places, and must prove it. */
    @Test
    fun `every mode is the major scale rotated`() {
        val cMajor = Scale.MAJOR.pitchClasses(0)
        // D Dorian, E Phrygian, F Lydian, G Mixolydian, A Aeolian, B Locrian
        mapOf(
            Scale.DORIAN to 2, Scale.PHRYGIAN to 4, Scale.LYDIAN to 5,
            Scale.MIXOLYDIAN to 7, Scale.AEOLIAN to 9, Scale.LOCRIAN to 11,
        ).forEach { (mode, root) ->
            assertEquals("$mode on $root should be C major", cMajor, mode.pitchClasses(root))
        }
    }

    /** A minor pentatonic is the minor scale without its two most awkward notes. */
    @Test
    fun `minor pentatonic is natural minor without the second and sixth`() {
        val minor = Scale.NATURAL_MINOR.pitchClasses(9)      // A minor
        val pentatonic = Scale.MINOR_PENTATONIC.pitchClasses(9)
        assertTrue(minor.containsAll(pentatonic))
        assertEquals(setOf(11, 5), minor - pentatonic)        // B and F
    }

    @Test
    fun `the blues scale is minor pentatonic plus the flat five`() {
        val pentatonic = Scale.MINOR_PENTATONIC.pitchClasses(9)
        val blues = Scale.BLUES.pitchClasses(9)
        assertEquals(setOf(3), blues - pentatonic)            // Eb, the blue note
    }

    @Test
    fun `degrees are named the way players name them`() {
        assertEquals("1", Degrees.of(0, 0))
        assertEquals("b3", Degrees.of(0, 3))
        assertEquals("5", Degrees.of(0, 7))
        assertEquals("b7", Degrees.of(0, 10))
        // A minor third is b3 and never #2, even though it is the same fret.
        assertFalse(Degrees.of(0, 3) == "#2")
    }

    // ---- the fretboard ----------------------------------------------------

    @Test
    fun `open strings appear when they are in the scale`() {
        val positions = Fretboard.positions(standard, root = 4, scale = Scale.MINOR_PENTATONIC)
        // E minor pentatonic: the low E open string is the root.
        val openLowE = positions.first { it.string == 0 && it.fret == 0 }
        assertTrue(openLowE.isRoot)
        assertEquals("1", openLowE.degree)
    }

    /**
     * The real test: the diagram must change with the tuning, because a
     * stored picture would be wrong in every tuning but one.
     */
    @Test
    fun `the diagram follows the tuning into drop D`() {
        val dropD = Tuning.byName("Drop D")!!
        val standardRoots = Fretboard.positions(standard, 2, Scale.MAJOR)
            .filter { it.string == 0 && it.isRoot }.map { it.fret }
        val dropRoots = Fretboard.positions(dropD, 2, Scale.MAJOR)
            .filter { it.string == 0 && it.isRoot }.map { it.fret }

        // D is at fret 10 on a standard low E, and open on a dropped one.
        assertTrue(standardRoots.contains(10))
        assertTrue(dropRoots.contains(0))
        assertFalse(dropRoots.contains(10))
    }

    @Test
    fun `a seven string neck simply has another string`() {
        val seven = Tuning.byName("7-string standard (B)")!!
        val positions = Fretboard.positions(seven, 4, Scale.MINOR_PENTATONIC)
        assertEquals(7, positions.map { it.string }.distinct().size)
    }

    /**
     * A minor pentatonic in standard tuning shows the correct five boxes,
     * checked against reference data.
     *
     * A minor pentatonic is the one every guitarist knows by heart, and box one
     * starts at the fifth fret. If these numbers are wrong, so is every diagram
     * the app will ever draw.
     */
    @Test
    fun `A minor pentatonic has its five boxes in the right places`() {
        val boxes = Fretboard.pentatonicBoxes(standard, root = 9, scale = Scale.MINOR_PENTATONIC)
        assertEquals(5, boxes.size)
        assertTrue("two notes per string", boxes.all { b -> b.cells.groupBy { it.first }.values.all { it.size == 2 } })

        // Box 1 is the one every guitarist knows: 5 8, 5 7, 5 7, 5 7, 5 8, 5 8.
        val box1 = mapOf(0 to listOf(5, 8), 1 to listOf(5, 7), 2 to listOf(5, 7),
            3 to listOf(5, 7), 4 to listOf(5, 8), 5 to listOf(5, 8))
            .flatMap { (s, frets) -> frets.map { s to it } }.toSet()
        assertEquals("Box 1", boxes[0].name)
        assertEquals(box1, boxes[0].cells)

        // Box 2 starts on C at fret 8 of the low E, but its A, D and G string
        // notes are at fret 7. A fixed four frets from the anchor cut them off.
        assertEquals(7..10, boxes[1].frets)
        assertTrue((1 to 7) in boxes[1].cells)
    }

    @Test
    fun `boxes are only offered for pentatonic scales`() {
        assertTrue(Fretboard.pentatonicBoxes(standard, 9, Scale.NATURAL_MINOR).isEmpty())
        assertEquals(5, Fretboard.pentatonicBoxes(standard, 0, Scale.MAJOR_PENTATONIC).size)
    }

    @Test
    fun `a Drop D box keeps the hand in one place`() {
        val dropD = Tuning.byName("Drop D")!!
        Fretboard.pentatonicBoxes(dropD, 2, Scale.MINOR_PENTATONIC).forEach { box ->
            assertTrue("${box.name} ${box.cells}", box.cells.all { it.second >= 0 })
            assertTrue("${box.name} spans ${box.frets}", box.frets.last - box.frets.first <= 5)
        }
    }

    @Test
    fun `an arpeggio shows only the chord tones`() {
        val positions = Fretboard.arpeggioPositions(standard, 0, ChordQuality.MAJOR)
        assertEquals(setOf(0, 4, 7), positions.map { it.pitchClass }.toSet())
    }
}

class ChordsTest {

    private val standard = Tuning.STANDARD

    /**
     * At least 60 curated standard-tuning voicings, each validated by a test
     * that its notes match the intended chord's pitch classes.
     *
     * This is the test that matters most in the whole theory section. A mistyped
     * fret in a chord diagram is not a crash — it is a player learning a chord
     * wrong and wondering why it sounds bad.
     */
    @Test
    fun `every curated voicing actually sounds its chord`() {
        ChordLibrary.allOpenVoicings().forEach { (root, quality, voicing) ->
            val sounded = voicing.pitchClasses(standard)
            val allowed = quality.pitchClasses(root)

            // No foreign notes. This is the half that catches a mistyped fret.
            assertTrue(
                "${voicing.name} sounds ${sounded - allowed}, which is not in the chord",
                sounded.all { it in allowed },
            )

            // The tones that make the chord what it is must be there. The fifth
            // is deliberately not among them: open C7 is x32310, which has no G
            // in it at all, and that is a correct and extremely common voicing.
            // Guitarists drop the fifth first, because six strings and four
            // fingers force a choice and the fifth carries the least meaning.
            val root12 = root.mod(12)
            assertTrue("${voicing.name} has no root", root12 in sounded)

            quality.semitones.filter { it in ESSENTIAL_INTERVALS }.forEach { interval ->
                val note = (root12 + interval).mod(12)
                assertTrue(
                    "${voicing.name} is missing the interval $interval that defines it",
                    note in sounded,
                )
            }
        }
    }

    private companion object {
        /**
         * Intervals a voicing may not omit: the third and the seventh, which
         * carry the chord's quality, and the second and fourth that replace the
         * third in a suspended chord. Everything else — most often the fifth —
         * is fair game to drop.
         */
        val ESSENTIAL_INTERVALS = setOf(2, 3, 4, 5, 10, 11)
    }

    @Test
    fun `barre shapes sound their chord in every key`() {
        listOf(ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.DOMINANT_7, ChordQuality.MINOR_7)
            .forEach { quality ->
                (0..11).forEach { root ->
                    ChordLibrary.voicings(root, quality)
                        .filter { it.isMoveable }
                        .forEach { voicing ->
                            assertEquals(
                                "${voicing.name} rooted at $root",
                                quality.pitchClasses(root),
                                voicing.pitchClasses(standard),
                            )
                        }
                }
            }
    }

    @Test
    fun `the library offers at least sixty voicings`() {
        val total = (0..11).sumOf { root ->
            ChordQuality.entries.sumOf { quality -> ChordLibrary.voicings(root, quality).size }
        }
        assertTrue("only $total voicings", total >= 60)
    }

    /** A power chord is deliberately two notes: no third, so neither major nor minor. */
    @Test
    fun `a power chord has no third`() {
        assertEquals(setOf(0, 7), ChordQuality.POWER.pitchClasses(0))
    }

    @Test
    fun `muted strings are not sounded`() {
        val openA = ChordLibrary.voicings(9, ChordQuality.MAJOR).first { it.name == "Open A" }
        // The low E is muted, so five notes sound, not six.
        assertEquals(5, openA.midiNotes(standard).size)
    }

    @Test
    fun `chord names read the way they are written`() {
        assertEquals("Am", ChordLibrary.chord(9, ChordQuality.MINOR).name())
        assertEquals("C", ChordLibrary.chord(0, ChordQuality.MAJOR).name())
        assertEquals("G7", ChordLibrary.chord(7, ChordQuality.DOMINANT_7).name())
        assertEquals("E5", ChordLibrary.chord(4, ChordQuality.POWER).name())
    }
}
