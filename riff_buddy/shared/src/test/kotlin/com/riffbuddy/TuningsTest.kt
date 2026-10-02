package com.riffbuddy

import com.riffbuddy.domain.music.Notes
import com.riffbuddy.domain.music.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in tunings, checked against interval math rather than re-read by
 * eye.
 *
 * A tuning table is the kind of data that looks right and is wrong: one flat
 * note in Open G minor would have a player tuning a string to the wrong pitch
 * and trusting the app over their own ears.
 */
class TuningsTest {

    /** At least twenty built in. */
    @Test
    fun `every appendix A tuning is present`() {
        assertEquals(27, Tuning.BUILT_IN.size)
        assertTrue(Tuning.BUILT_IN.size >= 20)
    }

    @Test
    fun `no tuning failed to parse`() {
        Tuning.BUILT_IN.forEach { tuning ->
            assertTrue(
                "${tuning.name} has ${tuning.stringCount} strings — a note failed to parse",
                tuning.stringCount in 6..8,
            )
        }
    }

    @Test
    fun `standard tuning is correct`() {
        assertEquals(
            listOf("E2", "A2", "D3", "G3", "B3", "E4"),
            Tuning.STANDARD.noteNames(),
        )
        // And in Hz, against the numbers every tuner prints.
        val frequencies = Tuning.STANDARD.frequencies()
        assertEquals(82.41, frequencies[0], 0.01)
        assertEquals(110.0, frequencies[1], 0.01)
        assertEquals(329.63, frequencies[5], 0.01)
    }

    /**
     * Each "down" tuning must be standard shifted by exactly that many
     * semitones — the check that catches a single mistyped note.
     */
    @Test
    fun `the offset tunings are standard, transposed`() {
        val standard = Tuning.STANDARD.midi
        mapOf(
            "Half step down (Eb)" to -1,
            "Whole step down (D)" to -2,
            "One and a half steps down (C#)" to -3,
            "Two steps down (C)" to -4,
            "Baritone (B standard)" to -5,
        ).forEach { (name, shift) ->
            val tuning = Tuning.byName(name)
            assertNotNull(name, tuning)
            assertEquals(
                "$name should be standard $shift semitones",
                standard.map { it + shift },
                tuning!!.midi,
            )
        }
    }

    /** A drop tuning lowers only the sixth string, by a whole tone. */
    @Test
    fun `drop D lowers only the lowest string`() {
        val drop = Tuning.byName("Drop D")!!.midi
        val standard = Tuning.STANDARD.midi
        assertEquals(standard[0] - 2, drop[0])
        assertEquals(standard.drop(1), drop.drop(1))
    }

    @Test
    fun `double drop D lowers the first and the sixth`() {
        val tuning = Tuning.byName("Double drop D")!!.midi
        val standard = Tuning.STANDARD.midi
        assertEquals(standard[0] - 2, tuning[0])
        assertEquals(standard[5] - 2, tuning[5])
        assertEquals(standard.subList(1, 5), tuning.subList(1, 5))
    }

    /** An open tuning must actually sound its chord when strummed open. */
    @Test
    fun `open tunings spell their chords`() {
        fun pitchClasses(name: String) =
            Tuning.byName(name)!!.midi.map { ((it % 12) + 12) % 12 }.toSet()

        // D major: D, F#, A
        assertEquals(setOf(2, 6, 9), pitchClasses("Open D"))
        // G major: G, B, D
        assertEquals(setOf(7, 11, 2), pitchClasses("Open G"))
        // E major: E, G#, B
        assertEquals(setOf(4, 8, 11), pitchClasses("Open E"))
        // A major: A, C#, E
        assertEquals(setOf(9, 1, 4), pitchClasses("Open A"))
        // C major: C, E, G
        assertEquals(setOf(0, 4, 7), pitchClasses("Open C"))
        // D minor: D, F, A — the flat third is the whole point of the name.
        assertEquals(setOf(2, 5, 9), pitchClasses("Open D minor"))
        // G minor: G, Bb, D
        assertEquals(setOf(7, 10, 2), pitchClasses("Open G minor"))
    }

    /**
     * Nashville is the trap in the table: the bottom four strings go up an
     * octave and the top two stay where they are. Transposing all six would
     * look tidy and be wrong.
     */
    @Test
    fun `nashville raises only the bottom four strings`() {
        val nashville = Tuning.byName("Nashville (high-strung)")!!.midi
        val standard = Tuning.STANDARD.midi
        (0..3).forEach { assertEquals("string $it", standard[it] + 12, nashville[it]) }
        assertEquals(standard[4], nashville[4])
        assertEquals(standard[5], nashville[5])
    }

    @Test
    fun `seven and eight string tunings extend standard downward`() {
        val seven = Tuning.byName("7-string standard (B)")!!.midi
        assertEquals(7, seven.size)
        assertEquals(Tuning.STANDARD.midi, seven.drop(1))

        val eight = Tuning.byName("8-string standard")!!.midi
        assertEquals(8, eight.size)
        assertEquals(seven, eight.drop(1))
    }

    /** Capo 2 on Standard shows targets F#2 B2 E3 A3 C#4 F#4. */
    @Test
    fun `a capo shifts every target up`() {
        assertEquals(
            listOf("F#2", "B2", "E3", "A3", "C#4", "F#4"),
            Tuning.STANDARD.withCapo(2).noteNames(),
        )
        assertEquals(Tuning.STANDARD.midi, Tuning.STANDARD.withCapo(0).midi)
    }

    @Test
    fun `note parsing handles sharps, flats and bad input`() {
        assertEquals(Notes.parse("E2"), Notes.parse("e2"))
        assertEquals(Notes.parse("D#3"), Notes.parse("Eb3"))
        assertEquals(69, Notes.parse("A4"))
        listOf("H2", "", "E", "E#x", "42").forEach { assertNull(it, Notes.parse(it)) }
    }

    /** A4 is adjustable, and everything must move with it. */
    @Test
    fun `changing the reference pitch moves every string`() {
        val at440 = Tuning.STANDARD.frequencies(440.0)
        val at432 = Tuning.STANDARD.frequencies(432.0)
        at440.zip(at432).forEach { (a, b) ->
            assertEquals("every string shifts by the same ratio", 440.0 / 432.0, a / b, 0.0001)
        }
    }

    @Test
    fun `every built-in survives the trip through the tunings table`() {
        Tuning.BUILT_IN.forEach { t ->
            assertEquals(t.name, t.midi, Tuning.parseStrings(t.storedStrings()))
        }
    }

    @Test
    fun `built-in names are unique, as the table requires`() {
        val names = Tuning.BUILT_IN.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `an unreadable string is refused, not guessed`() {
        assertEquals(null, Tuning.parseStrings("E2 A2 H3"))
        assertEquals(null, Tuning.parseStrings(""))
    }
}

