package com.riffbuddy

import com.riffbuddy.audio.Accent
import com.riffbuddy.audio.MetronomeSettings
import com.riffbuddy.audio.TimeSignature
import com.riffbuddy.data.PresetRepo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A preset round-trips exactly.
 *
 * The database itself needs a device, but the part that can actually be wrong is
 * the encoding, and that is pure.
 */
class PresetRoundTripTest {

    @Test
    fun `accents round-trip through their stored form`() {
        val accents = listOf(Accent.ACCENT, Accent.NORMAL, Accent.MUTED, Accent.NORMAL, Accent.ACCENT)
        val settings = MetronomeSettings(timeSignature = TimeSignature(5, 4), accents = accents)
        val stored = PresetRepo.formatAccents(settings)
        assertEquals("A,N,M,N,A", stored)
        assertEquals(accents, PresetRepo.parseAccents(stored))
    }

    /**
     * An unedited preset still stores the accented downbeat it was playing.
     * An empty accent list means "defaults", which is not the same as all-normal,
     * and saving it as all-normal would quietly lose the downbeat.
     */
    @Test
    fun `defaults are stored as what they actually sound like`() {
        val stored = PresetRepo.formatAccents(MetronomeSettings())
        assertEquals("A,N,N,N", stored)
    }

    @Test
    fun `time signatures round-trip`() {
        TimeSignature.COMMON.forEach { sig ->
            assertEquals(sig, PresetRepo.parseTimeSignature(sig.toString()))
        }
    }

    /** A damaged row must not take the metronome down with it. */
    @Test
    fun `nonsense in the column falls back instead of throwing`() {
        listOf(null, "", "banana", "4/", "/4", "0/4", "4/7", "99/4").forEach { raw ->
            assertEquals("'$raw' should fall back", TimeSignature.FOUR_FOUR, PresetRepo.parseTimeSignature(raw))
        }
        assertEquals(emptyList<Accent>(), PresetRepo.parseAccents(null))
        assertEquals(emptyList<Accent>(), PresetRepo.parseAccents("x,y,z"))
    }
}
