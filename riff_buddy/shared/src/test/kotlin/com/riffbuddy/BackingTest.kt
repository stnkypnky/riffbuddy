@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.riffbuddy

import alphaTab.model.Score
import com.riffbuddy.domain.backing.BackingGenerator
import com.riffbuddy.domain.backing.BackingSpec
import com.riffbuddy.domain.backing.BackingStyle
import com.riffbuddy.domain.backing.Chart
import com.riffbuddy.domain.music.Scale
import com.riffbuddy.tabs.TabParser
import com.riffbuddy.tabs.TabScores
import com.riffbuddy.domain.backing.Fretting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backing-track criteria, run through alphaTab on the JVM — and through its MIDI
 * generation too (TabScores.load), so "generates and plays" means the score
 * turned into sound, not merely that it parsed.
 */
class BackingTest {

    private val bluesInA = BackingSpec(keyTonic = 9, minor = false, chart = Chart.PRESETS[0].chart, tempo = 90, style = BackingStyle.SHUFFLE)

    private fun play(spec: BackingSpec): Pair<Score, com.riffbuddy.domain.backing.BackingScore> {
        val backing = BackingGenerator.generate(spec).getOrThrow()
        return TabScores.load(backing.tex.toByteArray(), "backing.alphatex") to backing
    }

    private fun firstNotes(score: Score, track: Int): List<Int> = buildList {
        for (bar in score.tracks[track].staves[0].bars) {
            val beat = bar.voices[0].beats[0]
            add(if (beat.notes.count() == 0) -1 else beat.notes[0].realValue.toInt())
        }
    }

    private fun notesIn(score: Score, track: Int): Int {
        var n = 0
        for (st in score.tracks[track].staves) for (b in st.bars) for (v in b.voices) for (beat in v.beats) n += beat.notes.count()
        return n
    }

    /** A 12-bar blues in A at 90 BPM generates and plays with drums, bass and rhythm parts. */
    @Test
    fun `a 12-bar blues in A at 90 BPM plays with drums, bass and rhythm`() {
        val (score, backing) = play(bluesInA)
        assertEquals(3, score.tracks.count())
        assertEquals(12, score.masterBars.count())
        assertEquals(90, score.tempo.toInt())
        assertTrue("drums are a percussion staff", score.tracks[0].staves[0].isPercussion)
        (0 until 3).forEach { assertTrue("track $it is empty", notesIn(score, it) > 0) }
        assertEquals(12, backing.totalBars)
    }

    /** Bass-line roots match the progression, checked on the generated score. */
    @Test
    fun `every bar's bass starts on the chord's root`() {
        val (score, backing) = play(bluesInA)
        val bassFirst = firstNotes(score, 1)
        bassFirst.forEachIndexed { bar, midi ->
            val chord = backing.chordAt(bar)!!
            assertEquals("bar ${bar + 1} (${chord.name()})", chord.root, midi.mod(12))
            assertTrue("bass out of range: $midi", midi in 28..52)
        }
        // A7 A7 A7 A7 D7 D7 A7 A7 E7 D7 A7 E7 — as pitch classes.
        assertEquals(listOf(9, 9, 9, 9, 2, 2, 9, 9, 4, 2, 9, 4), bassFirst.map { it.mod(12) })
    }

    /** The root rule holds in every style and both time signatures, not just the blues. */
    @Test
    fun `every style plays in 4-4 and 3-4, swung or not, with roots on the downbeat`() {
        for (style in BackingStyle.entries) for (beats in listOf(4, 3)) for (swing in listOf(false, true)) {
            val spec = BackingSpec(keyTonic = 4, minor = true, chart = "i VII VI V", barsPerChord = 2, tempo = 100,
                beatsPerBar = beats, style = style, swing = swing)
            val (score, backing) = play(spec)
            val label = "$style $beats/4 swing=$swing"
            assertEquals(label, 8, score.masterBars.count())
            assertEquals(label, beats, score.masterBars[0].timeSignatureNumerator.toInt())
            firstNotes(score, 1).forEachIndexed { bar, midi ->
                assertEquals("$label bar ${bar + 1}", backing.chordAt(bar)!!.root, midi.mod(12))
            }
        }
    }

    @Test
    fun `the shuffle is played in triplets`() {
        val (score, _) = play(bluesInA)
        val beat = score.tracks[0].staves[0].bars[0].voices[0].beats[0]
        assertEquals(3, beat.tupletNumerator.toInt())
        assertEquals(2, beat.tupletDenominator.toInt())
    }

    /** Saved tracks reload identically — only the spec is saved. */
    @Test
    fun `the same spec gives the same score, including after a save and reload`() {
        val a = BackingGenerator.generate(bluesInA).getOrThrow().tex
        val reloaded = BackingSpec.fromJson(bluesInA.toJson())
        assertEquals(bluesInA, reloaded)
        assertEquals(a, BackingGenerator.generate(reloaded).getOrThrow().tex)
    }

    /** Changing tempo or style regenerates within 1 s — here with room to spare for a slow tablet. */
    @Test
    fun `regenerating and re-reading a track is well under a second`() {
        play(bluesInA) // warm up
        val started = System.nanoTime()
        for (style in BackingStyle.entries) play(bluesInA.copy(style = style, tempo = 120))
        val perTrackMs = (System.nanoTime() - started) / 1_000_000.0 / BackingStyle.entries.size
        assertTrue("took $perTrackMs ms per track", perTrackMs < 100)
    }

    @Test
    fun `the chord timeline wraps as the player loops`() {
        val backing = BackingGenerator.generate(bluesInA).getOrThrow()
        assertEquals("E7", backing.chordAt(8)!!.name())
        assertEquals("A7", backing.chordAt(12)!!.name())   // bar 13 is bar 1 again
    }

    /** The generator's pitch names agree with alphaTab's: 60 is C4. */
    @Test
    fun `pitch names mean what alphaTab thinks they mean`() {
        assertEquals("C4", BackingGenerator.pitch(60))
        assertEquals("A1", BackingGenerator.pitch(33))
        val score = TabScores.load("\\tempo 90\n.\n\\track \"t\"\n\\staff {score}\n(A1).4 (C4).4 (C#2).2 |".toByteArray(), "x.alphatex")
        val beats = score.tracks[0].staves[0].bars[0].voices[0].beats
        assertEquals(listOf(33, 60, 37), (0 until beats.count()).map { beats[it].notes[0].realValue.toInt() })
    }

    @Test
    fun `a bad chart is an error with the chord named, not a broken score`() {
        val message = BackingGenerator.generate(bluesInA.copy(chart = "I IV Zz")).exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("Zz"))
    }

    @Test
    fun `what to play is the blues scale over a blues and pentatonic otherwise`() {
        assertEquals(Scale.BLUES, BackingGenerator.suggestedScale(bluesInA))
        assertEquals(Scale.MINOR_PENTATONIC, BackingGenerator.suggestedScale(BackingSpec(minor = true, chart = "i VII VI", style = BackingStyle.ROCK)))
        assertEquals(Scale.MAJOR_PENTATONIC, BackingGenerator.suggestedScale(BackingSpec(minor = false, chart = "I V vi IV", style = BackingStyle.ROCK)))
    }

    @Test
    fun `power chords and a boogie land on the fingerings a player uses`() {
        // A5 as x-0-2-2: A string open, D and G at the second fret.
        assertEquals(listOf("2.3", "2.4", "0.5"), Fretting.place(listOf(45, 52, 57), Fretting.GUITAR))
        // E5 as 0-2-2 on the three low strings.
        assertEquals(listOf("2.4", "2.5", "0.6"), Fretting.place(listOf(40, 47, 52), Fretting.GUITAR))
        // The A boogie's sixth: open A with the D string at the fourth fret.
        assertEquals(listOf("4.4", "0.5"), Fretting.place(listOf(45, 54), Fretting.GUITAR))
        // Bass: E1 is the open low string.
        assertEquals(listOf("0.4"), Fretting.place(listOf(28), Fretting.BASS))
    }

    @Test
    fun `every bass and rhythm note in every style has a place on the neck`() {
        // A note left as a pitch would be missing from the tab.
        BackingStyle.entries.forEach { style ->
            listOf(3, 4).forEach { beats ->
                val (score, _) = play(BackingSpec(keyTonic = 7, chart = "I IV V vi", style = style, beatsPerBar = beats))
                for (track in 1..2) {
                    for (st in score.tracks[track].staves) for (b in st.bars) for (v in b.voices) for (beat in v.beats) {
                        for (n in beat.notes) assertTrue("$style ${beats}/4 track $track", n.isStringed)
                    }
                    assertTrue(score.tracks[track].staves[0].showTablature)
                }
            }
        }
    }
}

