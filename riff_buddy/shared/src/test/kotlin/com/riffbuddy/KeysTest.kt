package com.riffbuddy

import com.riffbuddy.domain.music.ChordQuality
import com.riffbuddy.domain.music.Key
import com.riffbuddy.domain.music.Scale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Diatonic chords are correct for all 12 major and 12 minor keys. */
class KeysTest {

    @Test
    fun `C major is the one everybody can check by eye`() {
        val chords = Key(0, isMinor = false).chords
        assertEquals(
            listOf("C", "Dm", "Em", "F", "G", "Am", "Bdim"),
            chords.map { it.name() },
        )
    }

    @Test
    fun `A minor is C major started three frets lower`() {
        val chords = Key(9, isMinor = true).chords
        assertEquals(
            listOf("Am", "Bdim", "C", "Dm", "Em", "F", "G"),
            chords.map { it.name() },
        )
    }

    /**
     * The pattern is the point: major keys are always
     * major-minor-minor-major-major-minor-diminished. If one key breaks it,
     * something is wrong with the derivation and not with that key.
     */
    @Test
    fun `every major key has the same pattern of qualities`() {
        val expected = listOf(
            ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.MINOR,
            ChordQuality.MAJOR, ChordQuality.MAJOR, ChordQuality.MINOR,
            ChordQuality.DIMINISHED,
        )
        (0..11).forEach { tonic ->
            assertEquals(
                "key of ${Key(tonic, false).name()}",
                expected, Key(tonic, false).chords.map { it.quality },
            )
        }
    }

    @Test
    fun `every minor key has the same pattern of qualities`() {
        val expected = listOf(
            ChordQuality.MINOR, ChordQuality.DIMINISHED, ChordQuality.MAJOR,
            ChordQuality.MINOR, ChordQuality.MINOR, ChordQuality.MAJOR,
            ChordQuality.MAJOR,
        )
        (0..11).forEach { tonic ->
            assertEquals(
                "key of ${Key(tonic, true).name()}",
                expected, Key(tonic, true).chords.map { it.quality },
            )
        }
    }

    /** Relatives share every note; that is what makes them relatives. */
    @Test
    fun `a key and its relative contain the same notes`() {
        (0..11).forEach { tonic ->
            val major = Key(tonic, false)
            val minor = major.relative
            assertTrue(minor.isMinor)
            assertEquals(
                "${major.name()} and ${minor.name()}",
                major.scale.pitchClasses(major.tonic),
                minor.scale.pitchClasses(minor.tonic),
            )
            assertEquals("round trip", major.tonic, minor.relative.tonic)
        }
    }

    @Test
    fun `a twelve bar blues in A is the one every guitarist knows`() {
        val key = Key(9, isMinor = false)
        val blues = Key.PROGRESSIONS.first { it.name == "12-bar blues" }
        val chords = blues.chordsIn(key).map { it.name() }
        assertEquals(12, chords.size)
        assertEquals("A", chords.first())
        assertEquals("D", chords[4])     // the IV, bar five
        assertEquals("E", chords[8])     // the V, bar nine
    }

    /** A minor-key progression must not be offered in a major key. */
    @Test
    fun `progressions are filtered to the ones that fit`() {
        val major = Key.progressionsFor(Key(0, false)).map { it.name }
        val minor = Key.progressionsFor(Key(9, true)).map { it.name }
        assertTrue(major.contains("I–V–vi–IV"))
        assertTrue("a major key has no i chord", !major.contains("i–VII–VI"))
        assertTrue(minor.contains("i–VII–VI"))
        assertTrue("a minor key has no I chord", !minor.contains("I–V–vi–IV"))
    }

    @Test
    fun `transposing moves the whole key and wraps around`() {
        assertEquals(2, Key(0, false).transpose(2).tonic)
        assertEquals(0, Key(10, false).transpose(2).tonic)
        assertEquals(11, Key(0, false).transpose(-1).tonic)
    }

    @Test
    fun `soloing suggestions suit the key`() {
        assertTrue(Key(9, true).soloingScales.contains(Scale.MINOR_PENTATONIC))
        assertTrue(Key(0, false).soloingScales.contains(Scale.MAJOR_PENTATONIC))
    }
}
