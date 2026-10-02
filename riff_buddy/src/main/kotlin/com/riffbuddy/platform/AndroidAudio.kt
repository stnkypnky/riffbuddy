package com.riffbuddy.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.riffbuddy.audio.AudioInputs
import com.riffbuddy.audio.AudioOutputs
import com.riffbuddy.audio.FrameTime
import com.riffbuddy.audio.PcmInput
import com.riffbuddy.audio.PcmOutput
import com.riffbuddy.audio.RawMicrophone
import com.riffbuddy.audio.RecordInput
import com.riffbuddy.audio.StreamKind

/**
 * The shared audio seam over AudioTrack. Every stream is built the
 * way the engines built their own before there was a second platform: MEDIA
 * usage (a metronome must follow the media volume and must not be
 * ducked by a notification), 16-bit mono, streaming.
 */
class AndroidAudioOutputs : AudioOutputs {

    override fun open(sampleRate: Int, kind: StreamKind, lowLatency: Boolean, bufferBytes: (minimum: Int) -> Int): PcmOutput {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "This device rejected a $sampleRate Hz mono stream" }
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes(kind))
            .setAudioFormat(format(sampleRate))
            .setBufferSizeInBytes(bufferBytes(min))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (lowLatency) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return AndroidPcmOutput(builder.build(), sampleRate)
    }

    override fun playClip(pcm: ShortArray, sampleRate: Int, kind: StreamKind) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes(kind))
            .setAudioFormat(format(sampleRate))
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        // A static track plays from memory; release it once it has had time to
        // finish. A timer rather than a marker callback, which would need a
        // Looper on whatever thread called this.
        val ms = pcm.size * 1000L / sampleRate + 500
        Thread({ Thread.sleep(ms); track.release() }, "clip").apply { isDaemon = true }.start()
    }

    private fun attributes(kind: StreamKind) = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(
            when (kind) {
                StreamKind.SONIFICATION -> AudioAttributes.CONTENT_TYPE_SONIFICATION
                StreamKind.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
            }
        )
        .build()

    private fun format(sampleRate: Int) = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()
}

private class AndroidPcmOutput(private val track: AudioTrack, override val sampleRate: Int) : PcmOutput {
    private val ts = AudioTimestamp()

    override val bufferFrames: Int get() = track.bufferSizeInFrames
    override fun write(pcm: ShortArray, offset: Int, frames: Int) = track.write(pcm, offset, frames)
    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun flush() = track.flush()
    override fun stop() = track.stop()
    override fun release() = track.release()
    override fun setVolume(volume: Float) { track.setVolume(volume) }
    override val playbackHeadPosition: Long get() = track.playbackHeadPosition.toLong()
    override val underrunCount: Int get() = track.underrunCount

    /** Allocates nothing, so it is safe to call from a render loop. */
    override fun timestamp(into: FrameTime): Boolean {
        if (!track.getTimestamp(ts) || ts.framePosition <= 0) return false
        into.frame = ts.framePosition
        into.nanoTime = ts.nanoTime
        return true
    }
}

/** The shared input seam over [RawMicrophone], which keeps all the unprocessed-source logic. */
class AndroidAudioInputs(private val context: Context) : AudioInputs {

    /**
     * The device's own microphone, and the amp's headphone output when it is
     * plugged in.
     *
     * The amp's own USB port is not offered: on 2026-09-22 neither the S22 nor
     * the Fire could get the LT25 to identify itself over USB, so recording it
     * that way waits for the desktop edition. Its headphone output carries the
     * same modeled tone. Android sees it as a headset microphone: on the
     * Fire's 3.5 mm jack as a wired headset, and through a USB-C to 3.5 mm
     * adapter (the phone's route, one day) as a USB headset. Either way,
     * nothing reaches the app unless the plug is a four-ring headset (TRRS)
     * plug with the amp on the microphone ring — a plain stereo cable is
     * headphones to Android, not an input.
     */
    override fun inputs(): List<RecordInput> {
        val amp = devices().filter {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }.map { RecordInput(it.id, "Amp headphone out", line = true) }
        return listOf(RecordInput(null, "Device microphone", line = false)) + amp
    }

    override fun open(sampleRate: Int, bufferBytes: Int, inputId: Int?): PcmInput {
        val mic = RawMicrophone(context)
        val preferred = inputId?.let { id -> devices().firstOrNull { it.id == id } }
        return try {
            AndroidPcmInput(mic, mic.open(sampleRate, AudioFormat.ENCODING_PCM_FLOAT, bufferBytes, preferred))
        } catch (t: Throwable) {
            mic.release()
            throw t
        }
    }

    private fun devices(): List<AudioDeviceInfo> =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
}

private class AndroidPcmInput(private val mic: RawMicrophone, private val record: AudioRecord) : PcmInput {
    private val ts = AudioTimestamp()

    override fun start() = record.startRecording()
    override fun read(buffer: FloatArray, offset: Int, frames: Int) =
        record.read(buffer, offset, frames, AudioRecord.READ_BLOCKING)

    override fun timestamp(into: FrameTime): Boolean {
        if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS || ts.framePosition <= 0) return false
        into.frame = ts.framePosition
        into.nanoTime = ts.nanoTime
        return true
    }

    override fun release() = mic.release()
}
