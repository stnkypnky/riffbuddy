package com.riffbuddy.web

import com.riffbuddy.audio.AudioInputs
import com.riffbuddy.audio.AudioOutputs
import com.riffbuddy.audio.FrameTime
import com.riffbuddy.audio.PcmInput
import com.riffbuddy.audio.PcmOutput
import com.riffbuddy.audio.RecordInput
import com.riffbuddy.audio.StreamKind
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Sound in a browser, through audio-bridge.js and its worklets. An engine's
 * render loop is a coroutine here, on the page's thread; a write hands one
 * block to the stream's ring and then waits, by suspending, until the ring
 * has played down below the stream's buffer, which is what paces the loop as
 * a blocking write does on a phone.
 */

class WebAudioOutputs : AudioOutputs {

    override fun open(sampleRate: Int, kind: StreamKind, lowLatency: Boolean, bufferBytes: (minimum: Int) -> Int): PcmOutput =
        WebPcmOutput(sampleRate, bufferBytes(MIN_FRAMES * 2).coerceAtLeast(MIN_FRAMES * 2) / 2)

    override fun playClip(pcm: ShortArray, sampleRate: Int, kind: StreamKind) {
        val out = WebPcmOutput(sampleRate, pcm.size + MIN_FRAMES)
        scope.launch {
            out.write(pcm, 0, pcm.size)
            out.play()
            delay(pcm.size * 1000L / sampleRate + 500)
            out.release()
        }
    }

    companion object {
        /**
         * The smallest stream buffer, about 64 ms at 48 kHz. Larger than a
         * phone's, because the page's thread also lays out and draws the
         * screen and can be away that long; each engine scales it by its own rule.
         */
        const val MIN_FRAMES = 3072
        private val scope = MainScope()
    }
}

private class WebPcmOutput(override val sampleRate: Int, override val bufferFrames: Int) : PcmOutput {
    private val id = jsOpen(sampleRate)
    private var written = 0L
    private var playedBase = 0L
    private var released = false

    override suspend fun write(pcm: ShortArray, offset: Int, frames: Int): Int {
        if (released) return -1
        jsBegin(frames)
        for (i in 0 until frames) jsSet(i, pcm[offset + i] / 32768.0)
        jsSend(id)
        written += frames
        // Waits while the ring holds a whole buffer still to play.
        while (!released && written - jsPlayed(id).toLong() >= bufferFrames) delay(4)
        return frames
    }

    override fun play() = jsCmd(id, "play")
    override fun pause() = jsCmd(id, "pause")

    override fun flush() {
        jsCmd(id, "flush")
        written = jsPlayed(id).toLong()
    }

    override fun stop() {
        pause()
        flush()
    }

    override fun release() {
        if (released) return
        released = true
        jsClose(id)
    }

    override fun setVolume(volume: Float) = jsVolume(id, volume.toDouble())

    override val playbackHeadPosition: Long get() = jsPlayed(id).toLong() - playedBase

    override val underrunCount: Int get() = jsUnderruns(id)

    override fun timestamp(into: FrameTime): Boolean {
        val heard = jsHeardAt(id)
        if (heard < 0) return false
        into.frame = jsPlayed(id).toLong()
        into.nanoTime = (heard * 1_000_000).toLong()
        return true
    }
}

class WebAudioInputs : AudioInputs {
    init { jsWatchDevices() }

    /**
     * The browser names a device by a long string; the app wants a number. Each
     * string gets the next number the first time it is seen and keeps it, so a
     * chosen input stays chosen when another is plugged in beside it.
     */
    private val known = mutableListOf<String>()

    override fun inputs(): List<RecordInput> {
        val devices = (0 until jsDeviceCount()).map { i ->
            val deviceId = jsDeviceId(i)
            val number = known.indexOf(deviceId).takeIf { it >= 0 } ?: known.size.also { known += deviceId }
            // Names are blank until the microphone has been allowed once.
            val label = jsDeviceLabel(i).ifBlank { "Input ${i + 1}" }
            // An amp's own USB output arrives at line level, not microphone level.
            RecordInput(number, label, line = AMP_NAMES.any { label.contains(it, ignoreCase = true) })
        }
        return listOf(RecordInput(null, "Default input", line = false)) + devices
    }

    override fun open(sampleRate: Int, bufferBytes: Int, inputId: Int?): PcmInput =
        WebPcmInput(sampleRate, inputId?.let { known.getOrNull(it) })

    private companion object {
        val AMP_NAMES = listOf("Mustang", "Fender", "Katana", "Spark")
    }
}

/** Calls [onChange] whenever the browser's list of inputs changes, with true if it grew. */
fun watchAudioInputs(onChange: (added: Boolean) -> Unit): () -> Unit {
    var count = jsDeviceCount()
    jsOnDevices {
        val now = jsDeviceCount()
        val added = now > count
        count = now
        onChange(added)
    }
    return { jsOnDevices(null) }
}

private class WebPcmInput(private val sampleRate: Int, private val deviceId: String?) : PcmInput {
    private var id = -1
    private var released = false

    override fun start() {
        if (id < 0) id = jsOpenInput(sampleRate, deviceId ?: "")
    }

    override suspend fun read(buffer: FloatArray, offset: Int, frames: Int): Int {
        if (id < 0) start()
        while (!released) {
            val available = jsInputAvailable(id)
            if (available < 0) return -1
            if (available >= frames) break
            delay(5)
        }
        if (released) return -1
        val got = jsTakeInput(id, frames)
        for (i in 0 until got) buffer[offset + i] = jsTakeAt(i).toFloat()
        return got
    }

    override fun timestamp(into: FrameTime): Boolean {
        val at = jsInputFrameAt(id)
        if (id < 0 || at < 0) return false
        into.frame = jsInputFrame(id).toLong()
        into.nanoTime = (at * 1_000_000).toLong()
        return true
    }

    override fun release() {
        released = true
        if (id >= 0) jsCloseInput(id)
    }
}

private fun jsOpen(rate: Int): Int = js("rbAudio.open(rate)")
private fun jsBegin(n: Int): Unit = js("rbAudio.begin(n)")
private fun jsSet(i: Int, v: Double): Unit = js("rbAudio.set(i, v)")
private fun jsSend(id: Int): Unit = js("rbAudio.send(id)")
private fun jsCmd(id: Int, c: String): Unit = js("rbAudio.cmd(id, c)")
private fun jsVolume(id: Int, v: Double): Unit = js("rbAudio.volume(id, v)")
private fun jsPlayed(id: Int): Double = js("rbAudio.played(id)")
private fun jsUnderruns(id: Int): Int = js("rbAudio.underruns(id)")
private fun jsHeardAt(id: Int): Double = js("rbAudio.heardAt(id)")
private fun jsClose(id: Int): Unit = js("rbAudio.close(id)")
private fun jsOpenInput(rate: Int, deviceId: String): Int = js("rbAudio.openInput(rate, deviceId)")
private fun jsWatchDevices(): Unit = js("rbAudio.watchDevices()")
private fun jsDeviceCount(): Int = js("rbAudio.deviceCount()")
private fun jsDeviceId(i: Int): String = js("rbAudio.deviceId(i)")
private fun jsDeviceLabel(i: Int): String = js("rbAudio.deviceLabel(i)")
private fun jsOnDevices(cb: (() -> Unit)?): Unit = js("rbAudio.devicesChanged = cb")
private fun jsInputAvailable(id: Int): Int = js("rbAudio.inputAvailable(id)")
private fun jsTakeInput(id: Int, n: Int): Int = js("rbAudio.takeInput(id, n)")
private fun jsTakeAt(i: Int): Double = js("rbAudio.takeAt(i)")
private fun jsInputFrame(id: Int): Double = js("rbAudio.inputFrame(id)")
private fun jsInputFrameAt(id: Int): Double = js("rbAudio.inputFrameAt(id)")
private fun jsCloseInput(id: Int): Unit = js("rbAudio.closeInput(id)")
