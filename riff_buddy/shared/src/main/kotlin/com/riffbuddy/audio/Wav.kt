package com.riffbuddy.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV files for takes. Pure java.io, so tested on the JVM.
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
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataBytes).coerceAtMost(0xFFFF_FFFFL).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)                                  // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(bits.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.coerceAtMost(0xFFFF_FFFFL).toInt())
        }.array()
    }

    /**
     * Makes a take's header agree with its file's actual length. Returns true
     * if anything was changed. Only for files this app wrote: 44-byte header,
     * 16-bit mono.
     */
    fun repair(file: File): Boolean {
        if (!file.isFile || file.length() < HEADER_BYTES) return false
        RandomAccessFile(file, "rw").use { raf ->
            val head = ByteArray(HEADER_BYTES)
            raf.readFully(head)
            val buf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF") return false
            // An odd trailing byte is half a sample from a write cut short.
            val data = ((file.length() - HEADER_BYTES) / 2) * 2
            if (buf.getInt(40).toLong() and 0xFFFF_FFFFL == data) return false
            raf.seek(0)
            raf.write(header(buf.getInt(24), data))
            raf.setLength(HEADER_BYTES + data)
        }
        return true
    }

    /**
     * Reads a 16-bit PCM WAV into memory, mono. Walks the chunks rather than
     * assuming 44 bytes, since WAVs from elsewhere often carry extra ones.
     */
    fun read(file: File): Pcm {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a WAV file" }
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    require(buf.getShort(body).toInt() == 1) { "Only PCM WAV is supported" }
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
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
                        for (c in 0 until channels) sum += buf.getShort(body + (f * channels + c) * 2)
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
    fun slice(src: File, fromFrame: Int, toFrame: Int, dst: File): Int {
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
class WavWriter(val file: File, val sampleRate: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(Wav.header(sampleRate, 0))
    }
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
            raf.write(bytes, 0, n * 2)
            done += n
        }
        frames += count
    }

    override fun close() {
        raf.seek(0)
        raf.write(Wav.header(sampleRate, frames * 2))
        raf.close()
    }
}
