package com.riffbuddy

import com.riffbuddy.domain.backing.Chart
import com.riffbuddy.domain.music.ChordQuality
import com.riffbuddy.domain.music.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartTest {

    private val aMajor = Key(9, isMinor = false)
    private val aMinor = Key(9, isMinor = true)

    private fun names(text: String, key: Key) = Chart.parse(text, key).getOrThrow().map { it.name() }

    @Test
    fun `the 12-bar blues in A is A7 D7 E7 in the right bars`() {
        val preset = Chart.PRESETS.first { it.name == "12-bar blues" }
        assertEquals(
            listOf("A7", "A7", "A7", "A7", "D7", "D7", "A7", "A7", "E7", "D7", "A7", "E7"),
            names(preset.chart, aMajor),
        )
    }

    @Test
    fun `case sets the quality and a suffix overrides it`() {
        assertEquals(listOf("A", "F#m", "Bm7", "E7", "Amaj7", "A5"), names("I vi ii7 V7 Imaj7 I5", aMajor))
    }

    /** Minor numerals follow the minor scale, as the chord chart does. */
    @Test
    fun `in A minor VII is G and VI is F`() {
        assertEquals(listOf("Am", "G", "F", "E"), names("i VII VI V", aMinor))
    }

    @Test
    fun `a flat moves a numeral down a semitone`() {
        assertEquals(listOf("A", "G", "D"), names("I bVII IV", aMajor))
    }

    @Test
    fun `chord names are read as written, whatever the key`() {
        assertEquals(listOf("A7", "Dm", "F#m7", "Bb", "E5", "Cmaj7"), names("A7 Dm F#m7 Bb E5 Cmaj7", aMajor))
    }

    @Test
    fun `anything a person types between chords separates them`() {
        assertEquals(4, Chart.parse("I | IV, V – I", aMajor).getOrThrow().size)
    }

    @Test
    fun `a typo names the token it could not read`() {
        val message = Chart.parse("I IV Xq V", aMajor).exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("\"Xq\""))
    }

    @Test
    fun `every preset parses, in the mode it asks for`() {
        Chart.PRESETS.forEach { p ->
            val key = Key(9, p.minor)
            val chords = Chart.parse(p.chart, key, p.barsPerChord).getOrThrow()
            assertTrue(p.name, chords.isNotEmpty())
            assertTrue(p.name, chords.all { it.bars == p.barsPerChord })
        }
    }

    @Test
    fun `the thirds, fifths and sevenths come from the quality`() {
        val (dom, min, dim) = Chart.parse("A7 Am Adim", aMajor).getOrThrow()
        assertEquals(4, dom.third); assertEquals(10, dom.seventh)
        assertEquals(3, min.third)
        assertEquals(6, dim.fifth)
        assertEquals(ChordQuality.DIMINISHED, dim.quality)
    }
}
