package com.riffbuddy

import com.riffbuddy.audio.PitchDetector
import com.riffbuddy.audio.PluckSynth
import com.riffbuddy.domain.music.ChordQuality
import com.riffbuddy.domain.music.Fretboard
import com.riffbuddy.domain.music.Key
import com.riffbuddy.domain.music.Phrase
import com.riffbuddy.domain.music.PhraseNote
import com.riffbuddy.domain.music.Phrases
import com.riffbuddy.domain.music.Scale
import com.riffbuddy.domain.music.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/** What the Play buttons play, and that the synth plays it in tune. */
class PhrasesTest {

    private val standard = Tuning.STANDARD

    @Test
    fun `a scale run goes up the position and back, top note once`() {
        val box1 = Fretboard.pentatonicBoxes(standard, 9, Scale.MINOR_PENTATONIC).first()
        val run = Phrases.scaleRun(standard, box1.ascending(standard), Phrases.Speed.MEDIUM)
        val midi = run.notes.map { it.midi }
        assertEquals(12 * 2 - 1, midi.size)
        assertEquals(midi.take(12), midi.take(12).sorted())
        assertEquals(midi.takeLast(12), midi.takeLast(12).sortedDescending())
        assertEquals("starts and ends on the root", 45, midi.first())
        assertEquals(45, midi.last())
        // One note a beat, and the dot to light for each.
        assertEquals(666, run.notes[1].atMs - run.notes[0].atMs)
        assertTrue(run.notes.all { (it.string to it.fret) in box1.cells })
    }

    @Test
    fun `only one note of a run sounds at a time`() {
        val pos = Fretboard.threeNotesPerString(standard, 7, Scale.MAJOR).first()
        val run = Phrases.scaleRun(standard, pos.ascending(standard), Phrases.Speed.FAST)
        (0 until run.lengthMs step 25).forEach { ms -> assertTrue(run.soundingAt(ms.toLong()).size <= 1) }
    }

    @Test
    fun `a chord is strummed low to high, picked, and strummed again`() {
        val openC = listOf(-1, 3, 2, 0, 1, 0)
        val phrase = Phrases.chord(standard, openC, Phrases.Speed.MEDIUM)
        // Five strings sound, three times over, and the muted one never does.
        assertEquals(15, phrase.notes.size)
        assertTrue(phrase.notes.none { it.string == 0 })
        val strum = phrase.notes.take(5)
        assertEquals(strum.map { it.atMs }, strum.map { it.atMs }.sorted())
        assertEquals(listOf(48, 52, 55, 60, 64), strum.map { it.midi })
    }

    @Test
    fun `a progression lights each chord for its own bar`() {
        val key = Key(0, false)
        val chords = listOf("I", "V", "vi", "IV").map { n -> key.chords.first { it.numeral == n } }
        val phrase = Phrases.progression(chords.map { Phrases.fretsFor(it.root, it.quality) }, Phrases.Speed.SLOW)
        val bar = 4 * 60_000 / 80
        assertEquals(0, phrase.groupAt(10))
        assertEquals(1, phrase.groupAt(bar + 10L))
        assertEquals(3, phrase.groupAt(3L * bar + 10))
    }

    @Test
    fun `every chord in every key has something to play`() {
        for (tonic in 0..11) for (minor in listOf(false, true)) {
            Key(tonic, minor).chords.forEach { chord ->
                val frets = Phrases.fretsFor(chord.root, chord.quality)
                val sounding = frets.withIndex().filter { it.value >= 0 }
                assertTrue("${chord.name()} $frets", sounding.isNotEmpty() && sounding.all { it.value in 0..15 })
                val classes = sounding.map { (s, f) -> (standard.midi[s] + f).mod(12) }.toSet()
                assertEquals(chord.name(), chord.quality.pitchClasses(chord.root), classes)
            }
        }
    }

    @Test
    fun `the diminished chord gets the usual D G B string triad`() {
        // B diminished: B at the D string's ninth fret, D and F above it.
        assertEquals(listOf(-1, -1, 9, 7, 6, -1), Phrases.fretsFor(11, ChordQuality.DIMINISHED))
    }

    /**
     * The synth teaches what a note should sound like, so it had better be
     * the note: within 3 cents, checked with the tuner's own detector, from
     * the low E to the high E at the twelfth fret — where a loop rounded to
     * a whole sample would be ten cents out.
     */
    @Test
    fun `the plucked string is in tune across the neck`() {
        val detector = PitchDetector()
        listOf(40, 45, 52, 59, 64, 71, 76).forEach { midi ->
            val pcm = PluckSynth.render(Phrase(listOf(PhraseNote(0, midi, 1_000))), 48_000)
            // Past the pick's noise, into the ringing string.
            val window = FloatArray(detector.windowSize * 2) { pcm[4_800 + it] / 32768f }
            val heard = detector.detect(window) ?: error("no pitch for $midi")
            val cents = 1200 * ln(heard / PluckSynth.frequency(midi, 440.0)) / ln(2.0)
            assertTrue("midi $midi is $cents cents out", abs(cents) < 3)
        }
    }

    @Test
    fun `a full strum does not clip`() {
        val phrase = Phrases.chord(standard, listOf(3, 2, 0, 0, 0, 3), Phrases.Speed.FAST)
        val pcm = PluckSynth.render(phrase, 48_000)
        assertTrue(pcm.maxOf { abs(it.toInt()) } < Short.MAX_VALUE * 0.85)
        assertTrue("and can be heard", pcm.maxOf { abs(it.toInt()) } > Short.MAX_VALUE * 0.2)
    }

    @Test
    fun `one octave from the root stays in one hand position`() {
        val octave = Fretboard.oneOctave(standard, 9, Scale.NATURAL_MINOR)
        assertEquals(8, octave.cells.size)
        assertTrue(octave.frets.last - octave.frets.first <= 4)
        assertEquals(45, octave.ascending(standard).first().let { (s, f) -> standard.midi[s] + f })
    }
}
