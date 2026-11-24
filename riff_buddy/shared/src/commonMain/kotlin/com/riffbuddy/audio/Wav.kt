package com.riffbuddy.audio

import com.riffbuddy.platform.appFileSystem
import com.riffbuddy.platform.isFile
import com.riffbuddy.platform.readBytes
import com.riffbuddy.platform.size
import okio.Buffer
import okio.Closeable
import okio.FileHandle
import okio.Path
import okio.use

/**
 * WAV files for takes. Pure apart from the file system, so tested.
 *
 * **Why WAV.** It is the format that records most reliably: the samples arrive
 * from the microphone as 16-bit PCM and go to disk unchanged, with no encoder
 * to fail, stall or add latency mid-take. The cost is size — 5.8 MB a minute
 * at 48 kHz mono — which is why the recorder shows the storage used.
 *
 * **The header problem.** A WAV header states the length of the audio, which is
 * unknown until Stop. So it is written with zeros and patched at the end — and
 * a take cut off by the app being killed keeps its zeros, claiming to be empty
 * while every sample is on disk. [repair] measures the file and fixes the
 * header, so an interrupted take comes back instead of vanishing.
 */
object Wav {

    const val HEADER_BYTES = 44

    fun header(sampleRate: Int, dataBytes: Long, channels: Int = 1, bits: Int = 16): ByteArray {
        val blockAlign = channels * bits / 8
        return Buffer()
            .writeUtf8("RIFF")
            .writeIntLe((36 + dataBytes).coerceAtMost(0xFFFF_FFFFL).toInt())
            .writeUtf8("WAVE")
            .writeUtf8("fmt ")
            .writeIntLe(16)
            .writeShortLe(1)                                  // PCM
            .writeShortLe(channels)
            .writeIntLe(sampleRate)
            .writeIntLe(sampleRate * blockAlign)
            .writeShortLe(blockAlign)
            .writeShortLe(bits)
            .writeUtf8("data")
            .writeIntLe(dataBytes.coerceAtMost(0xFFFF_FFFFL).toInt())
            .readByteArray()
    }

    /**
     * Makes a take's header agree with its file's actual length. Returns true
     * if anything was changed. Only for files this app wrote: 44-byte header,
     * 16-bit mono.
     */
    fun repair(file: Path): Boolean {
        if (!file.isFile || file.size() < HEADER_BYTES) return false
        appFileSystem.openReadWrite(file).use { handle ->
            val head = ByteArray(HEADER_BYTES)
            handle.read(0, head, 0, HEADER_BYTES)
            if (head.ascii(0) != "RIFF") return false
            // An odd trailing byte is half a sample from a write cut short.
            val data = ((handle.size() - HEADER_BYTES) / 2) * 2
            if (head.intLe(40).toLong() and 0xFFFF_FFFFL == data) return false
            val fixed = header(head.intLe(24), data)
            handle.write(0, fixed, 0, fixed.size)
            handle.resize(HEADER_BYTES + data)
        }
        return true
    }

    /**
     * Reads a 16-bit PCM WAV into memory, mono. Walks the chunks rather than
     * assuming 44 bytes, since WAVs from elsewhere often carry extra ones.
     */
    fun read(file: Path): Pcm {
        val bytes = file.readBytes()
        require(bytes.size >= 12 && bytes.ascii(0) == "RIFF" && bytes.ascii(8) == "WAVE") { "Not a WAV file" }
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = bytes.ascii(pos)
            val size = bytes.intLe(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    require(bytes.shortLe(body) == 1) { "Only PCM WAV is supported" }
                    channels = bytes.shortLe(body + 2)
                    rate = bytes.intLe(body + 4)
                    bits = bytes.shortLe(body + 14)
                }
                "data" -> {
                    require(bits == 16 && channels >= 1 && rate > 0) { "Only 16-bit PCM WAV is supported" }
                    // Trust the file over the header: a take cut short has a
                    // zero here until it is repaired.
                    val available = bytes.size - body
                    val length = if (size in 1..available) size else available
                    val frames = length / (2 * channels)
                    val out = ShortArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0
                        for (c in 0 until channels) sum += bytes.shortLe(body + (f * channels + c) * 2)
                        out[f] = (sum / channels).toShort()
                    }
                    return Pcm(out, rate)
                }
            }
            pos = body + size + (size and 1)
        }
        error("No audio data in that WAV file")
    }

    /**
     * Frames [fromFrame, toFrame) of [src] written to [dst] as a new take-shaped
     * WAV: a trimmed clip. The ends are clamped to the audio that exists, so a
     * selection dragged past the end of the waveform still saves. Returns the
     * frames written.
     */
    fun slice(src: Path, fromFrame: Int, toFrame: Int, dst: Path): Int {
        val pcm = read(src)
        val a = fromFrame.coerceIn(0, pcm.frames)
        val b = toFrame.coerceIn(a, pcm.frames)
        WavWriter(dst, pcm.sampleRate).use { it.write(pcm.samples.copyOfRange(a, b), b - a) }
        return b - a
    }
}

/**
 * Streams a take to disk as it is recorded. The header is written first with a
 * zero length and put right in [close] — or, if the app dies first, by
 * [Wav.repair] on the next launch.
 */
class WavWriter(val file: Path, val sampleRate: Int) : Closeable {
    private val handle: FileHandle = appFileSystem.openReadWrite(file).apply {
        resize(0)
        Wav.header(sampleRate, 0).let { write(0, it, 0, it.size) }
    }
    private var at = Wav.HEADER_BYTES.toLong()
    private val bytes = ByteArray(8192)

    var frames: Long = 0
        private set

    fun write(samples: ShortArray, count: Int) {
        var done = 0
        while (done < count) {
            val n = minOf(count - done, bytes.size / 2)
            for (i in 0 until n) {
                val v = samples[done + i].toInt()
                bytes[i * 2] = v.toByte()
                bytes[i * 2 + 1] = (v shr 8).toByte()
            }
            handle.write(at, bytes, 0, n * 2)
            at += n * 2
            done += n
        }
        frames += count
    }

    override fun close() {
        Wav.header(sampleRate, frames * 2).let { handle.write(0, it, 0, it.size) }
        handle.close()
    }
}

/* Little-endian fields of a WAV file, read from its bytes. */

private fun ByteArray.ascii(at: Int): String = (0 until 4).map { this[at + it].toInt().toChar() }.joinToString("")

private fun ByteArray.shortLe(at: Int): Int = ((this[at].toInt() and 0xFF) or (this[at + 1].toInt() shl 8)).toShort().toInt()

private fun ByteArray.intLe(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or (this[at + 3].toInt() shl 24)
