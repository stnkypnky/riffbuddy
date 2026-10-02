package com.riffbuddy.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * A take as a small M4A file: AAC audio in an MP4 container. Used for sharing
 * and saving a copy.
 *
 * **Why AAC and not MP3.** Android can play MP3 but has no MP3 *encoder*;
 * making MP3 would mean bundling one (LAME), which is a new library and
 * The player's call. AAC is built into every Android device, sounds at least as good
 * as MP3 at the same size, and an .m4a plays on iPhones, Windows, WhatsApp and
 * anything else the player is likely to send it to. A design note.
 *
 * **How it works.** Two platform pieces. [MediaCodec] is the encoder: PCM
 * samples go into its input buffers and compressed AAC frames come out of its
 * output buffers, a few frames behind. [MediaMuxer] wraps those frames in the
 * MP4 container, and it can't start until the encoder says what its output
 * looks like (INFO_OUTPUT_FORMAT_CHANGED). That's why the loop below feeds
 * and drains in turn instead of encoding everything first.
 */
object AacEncoder {

    /** 128 kbit/s mono is well past transparent for one guitar: about 1 MB a minute. */
    const val BIT_RATE = 128_000

    fun encode(pcm: Pcm, out: File, bitRate: Int = BIT_RATE) {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, pcm.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val samples = pcm.samples
            var next = 0               // next frame to feed
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    // Blocks only briefly: when the encoder has no free input
                    // buffer, draining its output below is what frees one.
                    val inIndex = codec.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        buf.clear()
                        val n = minOf(buf.remaining() / 2, samples.size - next)
                        for (i in 0 until n) {
                            val v = samples[next + i].toInt()
                            buf.put(v.toByte()); buf.put((v shr 8).toByte())
                        }
                        val timeUs = next * 1_000_000L / pcm.sampleRate
                        next += n
                        if (next >= samples.size) {
                            codec.queueInputBuffer(inIndex, 0, n * 2, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n * 2, timeUs, 0)
                        }
                    }
                }
                // Drain everything that is ready, without waiting while there is
                // still input to feed. The first version waited 10 ms for output
                // after every input buffer (a few hundred samples each on the
                // Fire), which made a 31 s take take 16 s to encode.
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                        continue
                    }
                    if (outIndex < 0) continue
                    val buf = codec.getOutputBuffer(outIndex)!!
                    // The codec-config frame is already in the track format.
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxing) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputDone = true; break }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
