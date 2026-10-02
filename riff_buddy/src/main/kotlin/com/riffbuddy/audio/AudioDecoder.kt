package com.riffbuddy.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Turns an audio file into samples in memory, using the phone's own decoders
 * ("formats the platform can decode, such as mp3, m4a, wav, ogg").
 *
 * The whole song is decoded up front rather than streamed, because the looper
 * needs to be able to read any moment of it instantly — that is what makes a
 * loop with no seek, and so no gap, possible. The price is a pause on open,
 * which on the Fire is a few seconds for a typical song, shown as progress.
 */
object AudioDecoder {

    /** Longest song accepted. Mono at 48 kHz, fifteen minutes is about 86 MB. */
    const val MAX_MINUTES = 15

    fun decode(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {},
        canceled: () -> Boolean = { false },
    ): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw AudioFileException("That file could not be opened. It may have been moved or deleted.", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioFileException("There is no audio in that file.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > MAX_MINUTES * 60_000_000L) {
                throw AudioFileException(
                    "That recording is ${durationUs / 60_000_000} minutes long. The looper takes up to " +
                        "$MAX_MINUTES minutes — cut out the part you are learning and load that."
                )
            }

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val builder = PcmBuilder(maxFrames = sampleRate * 60 * MAX_MINUTES)

            codec = try {
                MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            } catch (e: Exception) {
                throw AudioFileException("This phone cannot decode that kind of audio ($mime).", e)
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var shorts = ShortArray(0)
            var floats = FloatArray(0)

            while (!outputDone) {
                if (canceled()) throw AudioFileException("Canceled.")
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The decoder's own word on the output beats the file's
                        // header: some report one rate and deliver another.
                        val out = codec.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (isFloat) {
                            val n = info.size / 4
                            if (floats.size < n) floats = FloatArray(n)
                            buffer.asFloatBuffer().get(floats, 0, n)
                            builder.appendFloats(floats, n, channels)
                        } else {
                            val n = info.size / 2
                            if (shorts.size < n) shorts = ShortArray(n)
                            buffer.asShortBuffer().get(shorts, 0, n)
                            builder.appendShorts(shorts, n, channels)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            if (builder.frames == 0) throw AudioFileException("That file decoded to silence — there is no audio to loop.")
            onProgress(1f)
            val pcm = builder.build(sampleRate)
            return DecodedAudio(pcm, Waveform.peaks(pcm.samples), builder.truncated)
        } catch (e: AudioFileException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw AudioFileException("That recording is too big to hold in memory on this device.", e)
        } catch (e: Exception) {
            throw AudioFileException("That file could not be decoded: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
