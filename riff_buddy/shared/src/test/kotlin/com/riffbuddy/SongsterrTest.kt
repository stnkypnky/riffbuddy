package com.riffbuddy

import com.riffbuddy.domain.Songsterr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongsterrTest {

    @Test
    fun `search combines artist and title`() {
        assertEquals(
            "https://www.songsterr.com/?pattern=Metallica%20Nothing%20Else%20Matters&inst=guitar",
            Songsterr.searchUrl(title = "Nothing Else Matters", artist = "Metallica"),
        )
    }

    @Test
    fun `search still works with no artist`() {
        assertTrue(Songsterr.searchUrl("Blackbird", "").contains("pattern=Blackbird"))
    }

    @Test
    fun `awkward characters survive encoding`() {
        val url = Songsterr.searchUrl(title = "Bad Habit / Rock & Roll", artist = "Steve Lacy")
        assertFalse("a raw ampersand would truncate the query", url.substringAfter("pattern=").contains("&R"))
        assertTrue(url.contains("%26"))
        assertFalse("spaces must be percent-encoded, not plus", url.contains("+"))
    }

    @Test
    fun `an empty song falls back to the site itself`() {
        assertEquals(Songsterr.FALLBACK, Songsterr.searchUrl("  ", "  "))
    }

    @Test
    fun `a real songsterr link is accepted`() {
        listOf(
            "https://www.songsterr.com/a/wsa/metallica-nothing-else-matters-tab-s439171",
            "https://songsterr.com/a/wsa/some-song-tab-s1",
        ).forEach { assertTrue(it, Songsterr.validateSavedUrl(it).isSuccess) }
    }

    /**
     * The check exists because this URL is later handed to the system browser.
     * A lookalike host or a non-https scheme must never reach that point.
     */
    @Test
    fun `lookalike hosts and odd schemes are refused`() {
        listOf(
            "https://notsongsterr.com/a/wsa/x",          // suffix-matching would pass this
            "https://songsterr.com.example.net/a/wsa/x", // and this
            "http://www.songsterr.com/a/wsa/x",          // not https
            "javascript:alert(1)",
            "intent://songsterr.com#Intent;scheme=https;end",
            "https://songsterr.com@evil.example/a/wsa/x",  // the site is after the @
            "https://evil.example\\@songsterr.com/a/wsa/x",
            "",
            "   ",
            "just some words",
        ).forEach { assertTrue("should have been refused: '$it'", Songsterr.validateSavedUrl(it).isFailure) }
    }

    @Test
    fun `a saved tab wins over a search`() {
        val saved = "https://www.songsterr.com/a/wsa/metallica-one-tab-s1234"
        assertEquals(saved, Songsterr.openUrl(saved, "One", "Metallica"))
    }

    /** A saved link that no longer validates must not strand the button. */
    @Test
    fun `a bad saved link falls back to searching`() {
        val url = Songsterr.openUrl("http://evil.example/x", "One", "Metallica")
        assertTrue(url.startsWith("https://www.songsterr.com/?pattern="))
    }

    @Test
    fun `no saved link means search`() {
        assertTrue(Songsterr.openUrl(null, "One", "Metallica").contains("pattern=Metallica%20One"))
    }
}
