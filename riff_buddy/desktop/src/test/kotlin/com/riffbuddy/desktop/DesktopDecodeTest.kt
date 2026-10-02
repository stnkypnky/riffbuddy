package com.riffbuddy.desktop

import com.riffbuddy.AppServices
import com.riffbuddy.ai.OnlineCheck
import com.riffbuddy.audio.AudioFileException
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The looper loads songs through the desktop decoder: whatever the file's
 * layout, it has to come out as the same mono sound at the file's own rate.
 */
class DesktopDecodeTest {

    private val dir = Files.createTempDirectory("riffbuddy-decode").toFile()
    private val services = AppServices(
        JdbcDb(File(dir, "db")), DesktopAudioOutputs(), DesktopAudioInputs(), DesktopSecureStore(),
        OnlineCheck { true }, dir, dir, "test",
    )
    private val platform = DesktopPlatform(services, dir, dir)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** One second of a 440 Hz tone, written in [format] to a file of [type]. */
    private fun tone(name: String, format: AudioFormat, type: AudioFileFormat.Type): File {
        val frames = format.sampleRate.toInt()
        val bytesPerSample = format.sampleSizeInBits / 8
        val data = ByteArray(frames * format.channels * bytesPerSample)
        var at = 0
        for (i in 0 until frames) {
            val v = sin(2 * PI * 440 * i / format.sampleRate) * 0.5
            repeat(format.channels) {
                if (bytesPerSample == 1) {
                    data[at++] = (v * 127 + 128).toInt().toByte() // 8-bit WAV is unsigned
                } else {
                    val s = (v * 32767).toInt()
                    val hi = (s shr 8).toByte(); val lo = s.toByte()
                    if (format.isBigEndian) { data[at++] = hi; data[at++] = lo } else { data[at++] = lo; data[at++] = hi }
                }
            }
        }
        val file = File(dir, name)
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(data), format, frames.toLong()), type, file)
        return file
    }

    /** Counts upward zero crossings: a 440 Hz second has 440 of them. */
    private fun crossings(samples: ShortArray): Int =
        (1 until samples.size).count { samples[it - 1] < 0 && samples[it] >= 0 }

    private fun assertTone(file: File, rate: Int) {
        var lastProgress = 0f
        val decoded = platform.decodeAudio(file.path, onProgress = { lastProgress = it }, canceled = { false })
        assertEquals(rate, decoded.pcm.sampleRate)
        assertEquals(rate, decoded.pcm.frames)
        assertTrue("440 Hz, got ${crossings(decoded.pcm.samples)}", abs(crossings(decoded.pcm.samples) - 440) <= 1)
        assertTrue("progress reached $lastProgress", lastProgress > 0.95f)
    }

    @Test
    fun `16-bit stereo WAV`() =
        assertTone(tone("a.wav", AudioFormat(44_100f, 16, 2, true, false), AudioFileFormat.Type.WAVE), 44_100)

    @Test
    fun `8-bit mono WAV`() =
        assertTone(tone("b.wav", AudioFormat(22_050f, 8, 1, false, false), AudioFileFormat.Type.WAVE), 22_050)

    @Test
    fun `big-endian AIFF`() =
        assertTone(tone("c.aiff", AudioFormat(48_000f, 16, 1, true, true), AudioFileFormat.Type.AIFF), 48_000)

    @Test(expected = AudioFileException::class)
    fun `a file that is not audio says so`() {
        val file = File(dir, "notes.mp3").apply { writeText("not audio at all") }
        platform.decodeAudio(file.path, onProgress = {}, canceled = { false })
    }

    /** No MP3 is bundled for tests; set RIFF_MP3 to a real one to check that path by hand. */
    @Test
    fun `an MP3 decodes to its full length`() {
        val path = System.getenv("RIFF_MP3")
        assumeTrue(path != null)
        val decoded = platform.decodeAudio(path!!, onProgress = {}, canceled = { false })
        println("MP3: ${decoded.pcm.sampleRate} Hz, ${decoded.durationMs} ms, truncated=${decoded.truncated}")
        assertTrue(decoded.durationMs > 10_000)
    }
}
