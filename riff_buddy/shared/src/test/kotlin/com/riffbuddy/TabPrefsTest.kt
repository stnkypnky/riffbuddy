package com.riffbuddy

import com.riffbuddy.domain.TabPrefs
import com.riffbuddy.domain.TrackMix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A tab file opens with its tracks and view as they were left. */
class TabPrefsTest {

    @Test
    fun `the muted rain track comes back muted`() {
        val prefs = TabPrefs(
            shownTrack = 1, zoom = 1.2f, horizontal = true, countIn = true, metronome = false,
            tracks = mapOf(3 to TrackMix(muted = true), 0 to TrackMix(solo = true, volume = 0.6f)),
        )
        assertEquals(prefs, TabPrefs.decode(prefs.encode()))
    }

    @Test
    fun `an untouched file stores nothing, and nothing reads as defaults`() {
        assertNull(TabPrefs().encode())
        assertNull(TabPrefs(tracks = mapOf(2 to TrackMix())).encode())
        assertEquals(TabPrefs(), TabPrefs.decode(null))
        assertEquals(TabPrefs(), TabPrefs.decode(""))
    }

    @Test
    fun `a bad field costs that setting, not the rest`() {
        val prefs = TabPrefs.decode("show=x;zoom=NaN;t1=m,-,0.5;t2=m;tq=m,-,1;t4=-,s,7;junk")
        assertNull(prefs.shownTrack)
        assertEquals(1f, prefs.zoom)
        assertEquals(TrackMix(muted = true, volume = 0.5f), prefs.mix(1))
        assertEquals(TrackMix(), prefs.mix(2))
        // A volume out of range is clamped, not dropped.
        assertEquals(TrackMix(solo = true, volume = 1f), prefs.mix(4))
    }
}
