package com.riffbuddy

import com.riffbuddy.audio.Wav
import com.riffbuddy.audio.WavWriter
import java.io.File
import okio.Path.Companion.toOkioPath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WavTest {

    private val dir: File = Files.createTempDirectory("wav").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun dataSize(f: File) = ByteBuffer.wrap(f.readBytes(), 40, 4).order(ByteOrder.LITTLE_ENDIAN).int

    /** Trimming: a clip holds exactly the frames selected, and nothing else. */
    @Test
    fun `a slice is exactly the selected frames`() {
        val src = File(dir, "take.wav")
        val samples = ShortArray(48_000) { (it % 1000).toShort() }
        WavWriter(src.toOkioPath(), 48_000).use { it.write(samples, samples.size) }

        val clip = File(dir, "clip.wav")
        assertEquals(9_600, Wav.slice(src.toOkioPath(), 12_000, 21_600, clip.toOkioPath()))
        val pcm = Wav.read(clip.toOkioPath())
        assertEquals(48_000, pcm.sampleRate)
        assertArrayEquals(samples.copyOfRange(12_000, 21_600), pcm.samples)
        assertEquals(9_600 * 2, dataSize(clip))

        // A selection dragged past the end keeps what exists.
        assertEquals(8_000, Wav.slice(src.toOkioPath(), 40_000, 99_999, File(dir, "tail.wav").toOkioPath()))
    }

    @Test
    fun `export names are readable file names`() {
        assertEquals("Take 3 (clip)", com.riffbuddy.ui.recorder.safeName("Take 3 (clip)"))
        assertEquals("Riff_ A_B", com.riffbuddy.ui.recorder.safeName("Riff: A/B"))
        assertEquals("Take", com.riffbuddy.ui.recorder.safeName("   "))
    }

    @Test
    fun `a take written in pieces reads back sample for sample`() {
        val f = File(dir, "take.wav")
        val samples = ShortArray(50_000) { ((it * 37) % 65536 - 32768).toShort() }
        WavWriter(f.toOkioPath(), 48_000).use { w ->
            var i = 0
            while (i < samples.size) {
                val n = minOf(777, samples.size - i)
                w.write(samples.copyOfRange(i, i + n), n)
                i += n
            }
        }
        val pcm = Wav.read(f.toOkioPath())
        assertEquals(48_000, pcm.sampleRate)
        assertArrayEquals(samples, pcm.samples)
        assertEquals(50_000 * 2, dataSize(f))
    }

    /** The crash case: samples on disk, header still claiming zero. */
    @Test
    fun `a take cut off before Stop is repaired, not lost`() {
        val f = File(dir, "cut.wav")
        val w = WavWriter(f.toOkioPath(), 48_000)
        w.write(ShortArray(4800) { 1000 }, 4800)
        // No close(): the app died here. Simulate the file as it would be left.
        val raf = java.io.RandomAccessFile(f, "r"); raf.close()
        assertEquals(0, dataSize(f))

        assertTrue(Wav.repair(f.toOkioPath()))
        assertEquals(9600, dataSize(f))
        assertEquals(4800, Wav.read(f.toOkioPath()).frames)
    }

    @Test
    fun `repair trims half a sample left by a cut-off write`() {
        val f = File(dir, "odd.wav")
        f.writeBytes(Wav.header(44_100, 0) + ByteArray(101))
        assertTrue(Wav.repair(f.toOkioPath()))
        assertEquals(Wav.HEADER_BYTES + 100L, f.length())
    }

    @Test
    fun `repairing a healthy take changes nothing`() {
        val f = File(dir, "ok.wav")
        WavWriter(f.toOkioPath(), 48_000).use { it.write(ShortArray(100), 100) }
        val before = f.readBytes()
        assertFalse(Wav.repair(f.toOkioPath()))
        assertArrayEquals(before, f.readBytes())
    }

    /** Even unrepaired, a cut-off take reads: the file is trusted over the header. */
    @Test
    fun `reading trusts the file length over a zero header`() {
        val f = File(dir, "zero.wav")
        f.writeBytes(Wav.header(48_000, 0) + ByteArray(400))
        assertEquals(200, Wav.read(f.toOkioPath()).frames)
    }

    /** The bundled sample recordings are WAVs from Python's wave module. */
    @Test
    fun `the bundled sample recordings read`() {
        val tone = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/assets/samples/a440-test-tone.wav") }.first { it.exists() }
        val pcm = Wav.read(tone.toOkioPath())
        assertEquals(22_050, pcm.sampleRate)
        assertEquals(22_050 * 15, pcm.frames)
    }

    @Test
    fun `stereo is averaged to mono`() {
        val f = File(dir, "st.wav")
        val header = Wav.header(44_100, 8, channels = 2)
        val body = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(100).putShort(300).putShort(-200).putShort(200).array()
        f.writeBytes(header + body)
        assertArrayEquals(shortArrayOf(200, 0), Wav.read(f.toOkioPath()).samples)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `something that is not a WAV is refused`() {
        val f = File(dir, "x.wav")
        f.writeBytes("definitely not audio".toByteArray())
        Wav.read(f.toOkioPath())
    }
}
