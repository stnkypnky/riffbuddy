package com.riffbuddy.audio

import kotlin.math.abs

/**
 * Collects decoded audio into one mono 16-bit array, whatever shape the
 * decoder hands it over in. Pure, so tested on the JVM; each platform's
 * decoder only has to feed it.
 *
 * **Mono, deliberately.** A five-minute stereo song at 48 kHz is 55 MB as
 * 16-bit samples; mono is half that, and the stretcher does half the work. On
 * a 2 GB tablet that is the difference between comfortable and not. What is
 * lost is the stereo image — a guitar panned hard left is still there, just
 * centered. A trade-off to revisit if it ever matters.
 */
class PcmBuilder(private val maxFrames: Int) {

    private var data = ShortArray(minOf(maxFrames, 1 shl 20))
    var frames = 0
        private set
    /** True if the song ran past [maxFrames] and the rest was dropped. */
    var truncated = false
        private set

    /** Interleaved 16-bit frames with [channels] channels, averaged to mono. */
    fun appendShorts(src: ShortArray, count: Int, channels: Int) {
        val incoming = count / channels
        var i = 0
        for (f in 0 until incoming) {
            var sum = 0
            for (c in 0 until channels) sum += src[i++]
            if (!put((sum / channels).toShort())) return
        }
    }

    /** Interleaved float frames in −1..1, for decoders that output float. */
    fun appendFloats(src: FloatArray, count: Int, channels: Int) {
        val incoming = count / channels
        var i = 0
        for (f in 0 until incoming) {
            var sum = 0f
            for (c in 0 until channels) sum += src[i++]
            val v = (sum / channels * 32767f).toInt().coerceIn(-32768, 32767)
            if (!put(v.toShort())) return
        }
    }

    private fun put(v: Short): Boolean {
        if (frames >= maxFrames) {
            truncated = true
            return false
        }
        if (frames == data.size) data = data.copyOf(minOf(maxFrames, data.size * 2))
        data[frames++] = v
        return true
    }

    fun build(sampleRate: Int): Pcm = Pcm(data.copyOf(frames), sampleRate)
}

/**
 * The waveform outline: the loudest sample in each of [buckets] slices,
 * scaled so the loudest slice fills the height. Drawn once per song; the
 * screen never touches the samples themselves.
 */
object Waveform {
    fun peaks(samples: ShortArray, buckets: Int = 1200): FloatArray {
        if (samples.isEmpty() || buckets <= 0) return FloatArray(0)
        val out = FloatArray(buckets)
        val per = samples.size.toDouble() / buckets
        var loudest = 1
        for (b in 0 until buckets) {
            val from = (b * per).toInt()
            val to = minOf(samples.size, ((b + 1) * per).toInt().coerceAtLeast(from + 1))
            var peak = 0
            for (i in from until to) peak = maxOf(peak, abs(samples[i].toInt()))
            out[b] = peak.toFloat()
            loudest = maxOf(loudest, peak)
        }
        for (b in out.indices) out[b] /= loudest
        return out
    }
}
