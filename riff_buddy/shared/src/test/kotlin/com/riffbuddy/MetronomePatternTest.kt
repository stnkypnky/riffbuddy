package com.riffbuddy

import com.riffbuddy.audio.Accent
import com.riffbuddy.audio.ClickSound
import com.riffbuddy.audio.ClickSynth
import com.riffbuddy.audio.MetronomeSettings
import com.riffbuddy.audio.Subdivision
import com.riffbuddy.audio.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetronomePatternTest {

    @Test
    fun `four four accents the downbeat and nothing else`() {
        val bar = MetronomeSettings(bpm = 100).bar()
        assertEquals(4, bar.size)
        assertEquals(Accent.ACCENT, bar[0].accent)
        assertTrue(bar.drop(1).all { it.accent == Accent.NORMAL })
        assertTrue(bar[0].isDownbeat)
    }

    @Test
    fun `subdivisions expand the bar without accenting themselves`() {
        val bar = MetronomeSettings(subdivision = Subdivision.TRIPLETS).bar()
        assertEquals(12, bar.size)
        assertEquals(Accent.ACCENT, bar[0].accent)
        // The two triplets after the downbeat are ordinary, not accented.
        assertEquals(Accent.NORMAL, bar[1].accent)
        assertEquals(Accent.NORMAL, bar[2].accent)
        assertEquals(0, bar[2].beatInBar)
        assertEquals(1, bar[3].beatInBar)
    }

    /** A muted beat takes its subdivisions with it — silence means silence. */
    @Test
    fun `muting a beat mutes its subdivisions`() {
        val s = MetronomeSettings(
            subdivision = Subdivision.EIGHTHS,
            accents = listOf(Accent.ACCENT, Accent.MUTED, Accent.NORMAL, Accent.NORMAL),
        )
        val beatOne = s.bar().filter { it.beatInBar == 1 }
        assertEquals(2, beatOne.size)
        assertTrue(beatOne.all { it.accent == Accent.MUTED })
    }

    @Test
    fun `all the listed time signatures are usable`() {
        TimeSignature.COMMON.forEach { sig ->
            val bar = MetronomeSettings(timeSignature = sig).bar()
            assertEquals("$sig", sig.tempoBeatsPerBar, bar.size)
        }
    }

    /**
     * 6/8 is felt in two and 12/8 in four — the way anyone counts off a tune in
     * those meters, and the way a score's tempo marking means them. Clicking six
     * and twelve times would make the BPM number mean something different from
     * what every chart means by it.
     */
    @Test
    fun `compound meters are felt in dotted quarters`() {
        assertEquals(2, TimeSignature(6, 8).tempoBeatsPerBar)
        assertEquals(3, TimeSignature(9, 8).tempoBeatsPerBar)
        assertEquals(4, TimeSignature(12, 8).tempoBeatsPerBar)
        assertTrue(TimeSignature(6, 8).isCompound)
        assertEquals("dotted quarter", TimeSignature(12, 8).beatUnitName)

        // Two clicks a bar, six with the eighths turned on.
        assertEquals(2, MetronomeSettings(timeSignature = TimeSignature(6, 8)).bar().size)
        assertEquals(
            6,
            MetronomeSettings(
                timeSignature = TimeSignature(6, 8),
                subdivision = Subdivision.TRIPLETS,
            ).bar().size,
        )
    }

    /** Simple meters are unchanged: the numerator is the count. */
    @Test
    fun `simple meters count the numerator in quarters`() {
        listOf(2, 3, 4, 5).forEach { n ->
            val sig = TimeSignature(n, 4)
            assertEquals(n, sig.tempoBeatsPerBar)
            assertEquals("quarter", sig.beatUnitName)
            assertTrue(!sig.isCompound && !sig.isIrregular)
        }
    }

    /**
     * 7/8 is the exception: counted in eighths, and the 2+2+3 grouping that
     * makes it playable lives in the accents rather than in the tempo.
     */
    @Test
    fun `odd meters count eighths and carry their grouping in the accents`() {
        val sig = TimeSignature(7, 8)
        assertTrue(sig.isIrregular)
        assertEquals(7, sig.tempoBeatsPerBar)
        assertEquals("eighth", sig.beatUnitName)
        assertEquals(
            listOf(0, 2, 4),
            sig.defaultAccents.mapIndexedNotNull { i, a -> i.takeIf { a == Accent.ACCENT } },
        )
        assertEquals(listOf(0, 2), TimeSignature(5, 8).defaultAccents
            .mapIndexedNotNull { i, a -> i.takeIf { a == Accent.ACCENT } })
    }

    /** Three divisions of a dotted quarter are the eighths, not triplets. */
    @Test
    fun `subdivision names follow the meter`() {
        assertEquals("Triplets", Subdivision.TRIPLETS.labelIn(TimeSignature.FOUR_FOUR))
        assertEquals("Eighths", Subdivision.TRIPLETS.labelIn(TimeSignature(6, 8)))
    }

    @Test
    fun `bpm outside the range is rejected`() {
        listOf(19, 301).forEach { bpm ->
            try {
                MetronomeSettings(bpm = bpm)
                throw AssertionError("$bpm should have been rejected")
            } catch (expected: IllegalArgumentException) {
                // as intended
            }
        }
        MetronomeSettings(bpm = 20)
        MetronomeSettings(bpm = 300)
    }

    @Test
    fun `every voice renders audible samples, and a muted pulse renders none`() {
        ClickSound.entries.forEach { sound ->
            val accented = ClickSynth.render(sound, Accent.ACCENT, onBeat = true, sampleRate = 48_000)
            assertTrue("$sound produced nothing", accented.isNotEmpty())
            assertTrue("$sound is silent", accented.any { it.toInt() != 0 })
            assertTrue("$sound clips", accented.all { it > Short.MIN_VALUE && it < Short.MAX_VALUE })

            val muted = ClickSynth.render(sound, Accent.MUTED, onBeat = true, sampleRate = 48_000)
            assertTrue("$sound muted should be empty", muted.isEmpty())
        }
    }

    /** An accent has to be louder than a beat, which has to beat a subdivision. */
    @Test
    fun `accent is louder than normal is louder than subdivision`() {
        fun peak(accent: Accent, onBeat: Boolean) =
            ClickSynth.render(ClickSound.BEEP, accent, onBeat, 48_000).maxOf { kotlin.math.abs(it.toInt()) }

        assertTrue(peak(Accent.ACCENT, true) > peak(Accent.NORMAL, true))
        assertTrue(peak(Accent.NORMAL, true) > peak(Accent.NORMAL, false))
    }

    /** A click that does not end at silence makes a pop on every beat. */
    @Test
    fun `every voice decays to silence`() {
        ClickSound.entries.forEach { sound ->
            val pcm = ClickSynth.render(sound, Accent.ACCENT, true, 48_000)
            val tail = pcm.takeLast(8).maxOf { kotlin.math.abs(it.toInt()) }
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("$sound ends at $tail against a peak of $peak", tail < peak / 50)
        }
    }

    @Test
    fun `a count-in runs up to eight bars and no further`() {
        assertEquals(8, MetronomeSettings(countInBars = 8).countInBars)
        assertEquals(0, MetronomeSettings(countInBars = 0).countInBars)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { MetronomeSettings(countInBars = 9) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { MetronomeSettings(countInBars = -1) }
    }
}
