package com.riffbuddy

import com.riffbuddy.tabs.TabFileException
import com.riffbuddy.tabs.TabParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import succeeds for three sample files in different formats, and a corrupt
 * file shows a clear error and changes nothing.
 *
 * The three samples are Riff Buddy's own — see samples/README.md — so there is
 * no license question about shipping them or testing against them.
 */
class TabFormatsTest {

    private fun sample(name: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/assets/samples/$name") }
            .firstOrNull { it.exists() }
            ?: error("sample $name not found")

    private fun parse(name: String) = TabParser.parse(sample(name).readBytes(), name)

    @Test
    fun `alphatex sample opens with both tracks and all twelve bars`() {
        val summary = parse("pentatonic-study.alphatex").getOrThrow()
        assertEquals(listOf("Guitar", "Bass"), summary.tracks)
        assertEquals(12, summary.bars)
        assertEquals(96, summary.tempo)
        assertEquals("Pentatonic Study", summary.title)
    }

    @Test
    fun `guitar pro sample opens`() {
        val summary = parse("pentatonic-study.gp").getOrThrow()
        assertEquals(listOf("Guitar", "Bass"), summary.tracks)
        assertEquals(12, summary.bars)
    }

    @Test
    fun `musicxml sample opens`() {
        val summary = parse("a-minor-blues.musicxml").getOrThrow()
        assertEquals(1, summary.tracks.size)
        assertEquals(8, summary.bars)
        assertEquals(84, summary.tempo)
    }

    @Test
    fun `random bytes are refused with a message a person can read`() {
        val junk = ByteArray(4096) { (it * 31 + 7).toByte() }
        val result = TabParser.parse(junk, "song.gp5")
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is TabFileException)
        assertTrue(error!!.message!!.startsWith("song.gp5 could not be read"))
    }

    /**
     * The failure that got through the first version of the parser. These bytes
     * are not rejected by alphaTab — they come back as a tidy empty score with
     * one track and one bar — so only a check for actual notes catches them.
     */
    @Test
    fun `bytes alphaTab reads as an empty score are still refused`() {
        val result = TabParser.parse(ByteArray(2048) { it.toByte() }, "counting.gp5")
        assertTrue(result.isFailure)
    }

    /**
     * The file that crashed the app on both devices: the first MusicXML sample,
     * with string and fret marks but no tuning. It parses cleanly, so the
     * original tests passed it; alphaTab then threw while turning it into
     * sound. Kept verbatim as a fixture so the check that now catches it can
     * never quietly stop running.
     */
    @Test
    fun `a file that opens but cannot be played is refused, not crashed on`() {
        val bytes = javaClass.getResourceAsStream("/tabs/tab-marks-without-tuning.musicxml")!!.readBytes()
        val result = TabParser.parse(bytes, "blues.musicxml")
        assertTrue("it was accepted", result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("cannot be turned into sound"))
    }

    /** A real failure mode: a download that stopped halfway. */
    @Test
    fun `a truncated guitar pro file is refused`() {
        val whole = sample("pentatonic-study.gp").readBytes()
        val half = whole.copyOf(whole.size / 2)
        assertTrue(TabParser.parse(half, "cut-off.gp").isFailure)
    }

    @Test
    fun `an empty file is refused before anything tries to read it`() {
        val result = TabParser.parse(ByteArray(0), "nothing.gp")
        assertEquals("nothing.gp is empty.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `only the supported formats get past the picker`() {
        listOf("a.gp3", "a.GP5", "a.gpx", "a.gp", "a.musicxml", "a.xml", "a.mxl", "a.alphatex")
            .forEach { assertTrue(it, TabParser.isSupportedName(it)) }
        listOf("a.pdf", "a.mp3", "a.txt", "gp5", "a.gp5.zip")
            .forEach { assertFalse(it, TabParser.isSupportedName(it)) }
    }
}
