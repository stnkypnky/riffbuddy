package com.riffbuddy

import com.riffbuddy.domain.music.CustomShape
import com.riffbuddy.domain.music.CustomShapes
import com.riffbuddy.domain.music.Fretboard
import com.riffbuddy.domain.music.Phrases
import com.riffbuddy.domain.music.Scale
import com.riffbuddy.domain.music.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scales and dot shapes of the player's own, and the neck past the 15th fret. */
class CustomShapesTest {

    private val standard = Tuning.STANDARD

    @Test
    fun `a scale built note by note fills the neck like the built-in one`() {
        val picked = setOf(9, 0, 2, 4, 7)
        assertEquals(
            Fretboard.positions(standard, 9, Scale.MINOR_PENTATONIC),
            Fretboard.positions(standard, 9, picked),
        )
        assertEquals(
            Fretboard.pentatonicBoxes(standard, 9, Scale.MINOR_PENTATONIC),
            Fretboard.pentatonicBoxes(standard, 9, picked),
        )
        val dorian = Scale.DORIAN.pitchClasses(2)
        assertEquals(
            Fretboard.threeNotesPerString(standard, 2, Scale.DORIAN),
            Fretboard.threeNotesPerString(standard, dorian),
        )
    }

    @Test
    fun `a scale of no common size has no boxes or 3nps, but still fills the neck`() {
        val six = setOf(9, 0, 2, 3, 4, 7)
        assertTrue(Fretboard.pentatonicBoxes(standard, 9, six).isEmpty())
        assertTrue(Fretboard.threeNotesPerString(standard, six).isEmpty())
        assertTrue(Fretboard.positions(standard, 9, six).all { it.pitchClass in six })
    }

    @Test
    fun `picked notes are named after the built-in scale they match, this root first`() {
        val same = CustomShapes.sameNotesAs(root = 2, classes = Scale.MAJOR.pitchClasses(0))
        assertEquals(2 to Scale.DORIAN, same.first())
        // C major and C Ionian are one scale: named once.
        assertTrue(same.contains(0 to Scale.MAJOR))
        assertFalse(same.contains(0 to Scale.IONIAN))
        assertTrue(CustomShapes.sameNotesAs(0, setOf(0, 1, 2)).isEmpty())
    }

    @Test
    fun `shapes survive being stored`() {
        val scale = CustomShape(name = "Mine", kind = CustomShape.Kind.SCALE, root = 9, pitchClasses = setOf(9, 0, 2, 4, 7))
        assertEquals("0,2,4,7,9", CustomShapes.encode(scale))
        assertEquals(scale.pitchClasses, CustomShapes.decodeScale(CustomShapes.encode(scale)))

        val dots = CustomShape(name = "Lick", kind = CustomShape.Kind.DOTS, root = 9, cells = setOf(0 to 5, 1 to 7, 1 to 5, 5 to 22))
        assertEquals("0:5,1:5,1:7,5:22", CustomShapes.encode(dots))
        assertEquals(dots.cells, CustomShapes.decodeDots(CustomShapes.encode(dots)))
    }

    @Test
    fun `damaged stored notes are skipped, not fatal`() {
        assertEquals(setOf(0, 7), CustomShapes.decodeScale("0,x,7,14"))
        assertEquals(setOf(1 to 3), CustomShapes.decodeDots("1:3,2,9:1,0:99,a:b"))
    }

    @Test
    fun `dots get degrees from their root, and the lowest dot is the root until one is chosen`() {
        val cells = setOf(0 to 5, 1 to 7, 2 to 7)  // A, E, A on the low strings
        assertEquals(9, CustomShapes.lowestRoot(standard, cells))
        val positions = CustomShapes.dotPositions(standard, cells, root = 9)
        assertEquals(listOf("1", "5", "1"), positions.sortedBy { it.midi }.map { it.degree })
        assertEquals(2, positions.count { it.isRoot })
        // A seven-string shape opened on six strings loses the string it can't show.
        assertEquals(1, CustomShapes.dotPositions(standard, setOf(6 to 3, 0 to 3), 7).size)
    }

    @Test
    fun `dots heard together play the higher dot where two share a string`() {
        val phrase = Phrases.together(standard, setOf(0 to 3, 0 to 5, 2 to 5), Phrases.Speed.SLOW)
        val strum = phrase.notes.filter { it.atMs < 100 }
        assertEquals(listOf(0 to 5, 2 to 5), strum.map { it.string to it.fret })
    }

    @Test
    fun `shapes repeat an octave up where the whole shape still fits`() {
        val boxes = Fretboard.withOctaveUp(Fretboard.pentatonicBoxes(standard, 9, Scale.MINOR_PENTATONIC))
        val box1 = boxes.filter { it.name == "Box 1" }
        assertEquals(listOf(5..8, 17..20), box1.map { it.frets })
        // Box 3 is at frets 9–13; a copy would need the 25th.
        assertEquals(listOf(9..13), boxes.filter { it.name == "Box 3" }.map { it.frets })
        assertTrue(boxes.all { p -> p.cells.all { it.second <= Fretboard.MAX_FRET } })
    }

    @Test
    fun `octave-up copies of 3nps and CAGED are the same shapes, twelve frets on`() {
        listOf(
            Fretboard.threeNotesPerString(standard, 7, Scale.MAJOR),
            Fretboard.cagedRegions(standard, 0, Scale.MAJOR),
        ).forEach { shapes ->
            val all = Fretboard.withOctaveUp(shapes)
            val copies = all.filter { it !in shapes }
            copies.forEach { copy ->
                val original = shapes.first { it.name == copy.name && it.cells.map { (s, f) -> s to f + 12 }.toSet() == copy.cells }
                assertEquals(original.frets.first + 12, copy.frets.first)
            }
            assertTrue(copies.isNotEmpty())
            assertEquals(all.map { it.frets.first }.sorted(), all.map { it.frets.first })
        }
    }
}
