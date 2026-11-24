package com.riffbuddy

import com.riffbuddy.domain.TabFile
import com.riffbuddy.tabs.TabLibrary
import java.io.File
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tab import's file half: judge first, write only what passed. */
class TabLibraryTest {

    private val dir: File = Files.createTempDirectory("tabs").toFile()
    private val library = TabLibrary(dir.toOkioPath())

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun sample(name: String): ByteArray =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/assets/samples/$name") }
            .first { it.exists() }
            .readBytes()

    private fun storedFiles(): List<String> = dir.listFiles()?.map { it.name }.orEmpty()

    /** The heart of it: a corrupt file changes nothing. */
    @Test
    fun `a corrupt file changes nothing on disk`() {
        library.write(library.prepare(sample("pentatonic-study.gp"), "good.gp").getOrThrow())
        val before = storedFiles()

        val result = library.prepare(ByteArray(2048) { it.toByte() }, "broken.gp5")

        assertTrue(result.isFailure)
        assertEquals(before, storedFiles())
    }

    @Test
    fun `a good file is stored byte for byte under a name of its own`() {
        val bytes = sample("pentatonic-study.gp")
        val prepared = library.prepare(bytes, "My Song.gp").getOrThrow()
        library.write(prepared)

        assertArrayEquals(bytes, library.read(prepared.storedName))
        assertTrue(prepared.storedName.endsWith(".gp"))
        assertFalse("stored name must not reuse the original", prepared.storedName.contains("My Song"))
    }

    @Test
    fun `two files with the same name do not collide`() {
        val a = library.prepare(sample("pentatonic-study.gp"), "solo.gp").getOrThrow()
        val b = library.prepare(sample("pentatonic-study.gp"), "solo.gp").getOrThrow()
        assertNotEquals(a.storedName, b.storedName)
    }

    @Test
    fun `identical bytes have identical fingerprints, so a re-import is recognizable`() {
        val a = library.prepare(sample("a-minor-blues.musicxml"), "x.musicxml").getOrThrow()
        val b = library.prepare(sample("a-minor-blues.musicxml"), "renamed.musicxml").getOrThrow()
        assertEquals(a.sha256, b.sha256)
    }

    @Test
    fun `the display name is the song's title when the file has one`() {
        val prepared = library.prepare(sample("pentatonic-study.alphatex"), "ps.alphatex").getOrThrow()
        assertEquals("Pentatonic Study", prepared.displayName)
    }

    @Test
    fun `a pdf is turned away with the formats that would work`() {
        val message = library.prepare(ByteArray(10), "tab.pdf").exceptionOrNull()?.message.orEmpty()
        assertTrue(message.startsWith("A .pdf file is not a tab."))
        assertTrue(message.contains("Guitar Pro"))
    }

    @Test
    fun `an enormous file is refused before it is parsed`() {
        val message = library.prepare(ByteArray(TabLibrary.MAX_BYTES + 1), "huge.gp")
            .exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("MB"))
    }

    @Test
    fun `deleting removes the bytes`() {
        val prepared = library.prepare(sample("pentatonic-study.gp"), "a.gp").getOrThrow()
        library.write(prepared)
        library.delete(prepared.storedName)
        assertFalse(library.exists(prepared.storedName))
    }

    @Test
    fun `leftover partial writes are swept`() {
        File(dir.apply { mkdirs() }, "abc.gp.part").writeText("half")
        library.sweepPartials()
        assertEquals(emptyList<String>(), storedFiles())
    }

    @Test
    fun `the library line describes what is in the file`() {
        val file = TabFile(
            name = "x", originalName = "x.gp5", storedName = "s", sizeBytes = 1, sha256 = "h",
            title = "Floods", artist = "Pantera", trackCount = 6, barCount = 212,
        )
        assertEquals("Floods — Pantera · 6 tracks · 212 bars", file.contents)
        assertEquals("Guitar Pro", file.format)
    }
}
