package com.riffbuddy.desktop

import com.riffbuddy.audio.AudioInputs
import com.riffbuddy.audio.AudioOutputs
import com.riffbuddy.audio.FrameTime
import com.riffbuddy.audio.PcmInput
import com.riffbuddy.audio.PcmOutput
import com.riffbuddy.audio.RecordInput
import com.riffbuddy.audio.StreamKind
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

/**
 * Sound out through Java's own audio lines: ALSA or PulseAudio on Linux,
 * DirectSound on Windows, Core Audio on macOS.
 *
 * Each stream is 16-bit mono at the engine's rate, and the engines keep their
 * own timing exactly as on Android — writing a continuous stream with each
 * sound at its own sample index — so the only thing that changes is the pipe.
 * Measured on Linux at a 21 ms buffer: clicks exactly 500.000 ms apart at
 * 120 BPM, no underruns.
 */
class DesktopAudioOutputs : AudioOutputs {

    override fun open(sampleRate: Int, kind: StreamKind, lowLatency: Boolean, bufferBytes: (minimum: Int) -> Int): PcmOutput {
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val line = AudioSystem.getSourceDataLine(format)
        // The engines size their buffers from the platform's smallest workable
        // one. 512 frames is what a desktop line copes with comfortably, and
        // each engine's own rule scales it from there.
        line.open(format, bufferBytes(MIN_FRAMES * 2).coerceAtLeast(MIN_FRAMES * 2))
        return DesktopPcmOutput(line, sampleRate)
    }

    override fun playClip(pcm: ShortArray, sampleRate: Int, kind: StreamKind) {
        thread(name = "clip", isDaemon = true) {
            val out = open(sampleRate, kind) { it * 4 }
            try {
                out.play()
                out.write(pcm, 0, pcm.size)
                out.stop()
            } finally {
                out.release()
            }
        }
    }

    private companion object {
        const val MIN_FRAMES = 512
    }
}

private class DesktopPcmOutput(private val line: SourceDataLine, override val sampleRate: Int) : PcmOutput {

    /** Converted samples, kept and reused so writing allocates nothing once warm. */
    private var bytes = ByteArray(line.bufferSize)
    @Volatile private var volume = 1f
    private var underruns = 0
    private var started = false

    override val bufferFrames: Int get() = line.bufferSize / 2

    override fun write(pcm: ShortArray, offset: Int, frames: Int): Int {
        if (bytes.size < frames * 2) bytes = ByteArray(frames * 2)
        val v = volume
        for (i in 0 until frames) {
            val s = if (v >= 1f) pcm[offset + i].toInt() else (pcm[offset + i] * v).toInt()
            bytes[2 * i] = s.toByte()
            bytes[2 * i + 1] = (s shr 8).toByte()
        }
        // A running line with nothing queued has run dry: that is an underrun.
        if (started && line.isRunning && line.available() >= line.bufferSize) underruns++
        val written = line.write(bytes, 0, frames * 2)
        return written / 2
    }

    override fun play() {
        line.start()
        started = true
    }

    override fun pause() = line.stop()
    override fun flush() = line.flush()

    /** Plays out what is queued, then stops — as Android's streaming stop() does. */
    override fun stop() {
        if (line.isOpen) {
            line.drain()
            line.stop()
        }
    }

    override fun release() = line.close()

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override val playbackHeadPosition: Long get() = line.longFramePosition
    override val underrunCount: Int get() = underruns

    /**
     * The frame the line reports having played, read now. Not a hardware
     * timestamp in Android's sense, but it is the device's own position, and
     * the latency calibration measures and removes whatever offset it has.
     */
    override fun timestamp(into: FrameTime): Boolean {
        val frame = line.longFramePosition
        if (frame <= 0) return false
        into.frame = frame
        into.nanoTime = System.nanoTime()
        return true
    }
}

/**
 * The inputs a desktop offers: the system default, and every other device
 * that can record — a USB microphone, an audio interface, an amp with its own
 * USB audio. Desktop systems apply no voice processing to an ordinary input
 * stream, so there is nothing to switch off.
 */
class DesktopAudioInputs : AudioInputs {

    private val probe = AudioFormat(48_000f, 16, 1, true, false)

    override fun inputs(): List<RecordInput> {
        val devices = AudioSystem.getMixerInfo().withIndex().filter { (_, info) ->
            // The system default is offered once, as "Default input", not again
            // under the sound server's own name for it.
            !info.name.contains("[default]") && runCatching {
                AudioSystem.getMixer(info).isLineSupported(DataLine.Info(TargetDataLine::class.java, probe))
            }.getOrDefault(false)
        }
        return listOf(RecordInput(null, "Default input", line = false)) +
            devices.map { (index, info) ->
                val label = readableName(info.name, info.description)
                // An amp's own USB output arrives at line level, not microphone level.
                val ampLike = AMP_NAMES.any { label.contains(it, ignoreCase = true) }
                RecordInput(index, label, line = ampLike)
            }
    }

    /**
     * Java names a Linux device after its ALSA address ("PCH [plughw:0,0]") and
     * describes it readably ("Direct Audio Device: HDA Intel PCH, ALC3253
     * Analog, ..."). The description's first two parts are what a person
     * recognizes; other systems already give a readable name.
     */
    private fun readableName(name: String, description: String): String {
        if (!description.startsWith("Direct Audio Device:")) return name
        val parts = description.substringAfter(":").split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return parts.take(2).joinToString(" · ").ifBlank { name }
    }

    override fun open(sampleRate: Int, bufferBytes: Int, inputId: Int?): PcmInput {
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val line = if (inputId == null) AudioSystem.getLine(info) as TargetDataLine
        else AudioSystem.getMixer(AudioSystem.getMixerInfo()[inputId]).getLine(info) as TargetDataLine
        // The engines ask in float bytes (4 a frame); this line carries 16-bit.
        line.open(format, (bufferBytes / 4 * 2).coerceAtLeast(4096))
        return DesktopPcmInput(line)
    }

    private companion object {
        val AMP_NAMES = listOf("Mustang", "Fender", "Katana", "Spark")
    }
}

private class DesktopPcmInput(private val line: TargetDataLine) : PcmInput {
    private var bytes = ByteArray(8192)

    override fun start() = line.start()

    override fun read(buffer: FloatArray, offset: Int, frames: Int): Int {
        if (bytes.size < frames * 2) bytes = ByteArray(frames * 2)
        val n = line.read(bytes, 0, frames * 2) / 2
        for (i in 0 until n) {
            val s = (bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)
            buffer[offset + i] = s.toShort() / 32768f
        }
        return n
    }

    override fun timestamp(into: FrameTime): Boolean {
        val frame = line.longFramePosition
        if (frame <= 0) return false
        into.frame = frame
        into.nanoTime = System.nanoTime()
        return true
    }

    override fun release() {
        runCatching { line.stop() }
        line.close()
    }
}
