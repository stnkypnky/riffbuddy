@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.riffbuddy.desktop.tabs

import alphaTab.IEventEmitter
import alphaTab.IEventEmitterOfT
import alphaTab.collections.List as AtList
import alphaTab.core.ecmaScript.Float32Array
import alphaTab.synth.ISynthOutput
import alphaTab.synth.ISynthOutputDevice
import com.riffbuddy.platform.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Where alphaTab's synthesizer sends its sound on a desktop: a stereo Java
 * audio line, fed from a ring of samples the synthesizer keeps topped up.
 *
 * The synthesizer works ahead of the speaker, so how far the song has got is
 * not how much has been written: it is how much the line reports having
 * played. That is what [samplesPlayed] reports, every 50 ms, which keeps the
 * cursor and the clock on the sound even after the speed changes.
 */
class DesktopSynthOutput : ISynthOutput {

    override val ready: IEventEmitter = Emitter()
    override val samplesPlayed: IEventEmitterOfT<Double> = EmitterOf()
    override val sampleRequest: IEventEmitter = Emitter()
    override val sampleRate: Double get() = RATE.toDouble()

    private var bufferCount = 0
    /** Chunks asked of the synthesizer and not yet delivered, counted as buffered. */
    private var requested = 0
    private lateinit var ring: FloatRing
    private var line: SourceDataLine? = null

    @Volatile private var open = false
    @Volatile private var playing = false
    private val lock = Object()

    // Played-position bookkeeping. When the synthesizer falls behind, the line
    // is fed silence, and silence must not count as song played.
    private var realFrames = 0L
    private var silenceFrames = 0L
    private var lastPosition = -1L
    private var startPosition = 0L
    private var silenceReported = 0L

    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tab-synth-clock").apply { isDaemon = true } }
    private var clock: ScheduledFuture<*>? = null

    override fun activate() {}

    override fun open(bufferTimeInMilliseconds: Double) {
        bufferCount = (bufferTimeInMilliseconds * RATE / 1000 / CHUNK).toInt().coerceAtLeast(4)
        ring = FloatRing(CHUNK * bufferCount)
        val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)
        line = runCatching {
            AudioSystem.getSourceDataLine(format).apply { open(format, CHUNK * 2) }
        }.onFailure { AppLog.w(TAG, "no audio line for the tab player", it) }.getOrNull()
        open = true
        Thread(::writeLoop, "tab-synth-out").apply { isDaemon = true; start() }
        (ready as Emitter).trigger()
    }

    private fun writeLoop() {
        val floats = FloatArray(CHUNK)
        val bytes = ByteArray(CHUNK * 2)
        while (open) {
            val out = line
            if (!playing || out == null) {
                synchronized(lock) { if (open && !playing) lock.wait(50) }
                continue
            }
            val got = synchronized(lock) { ring.read(floats, CHUNK) }
            requestBuffers()
            if (got < CHUNK) java.util.Arrays.fill(floats, got, CHUNK, 0f)
            for (i in 0 until CHUNK) {
                val s = (floats[i].coerceIn(-1f, 1f) * 32767f).toInt()
                bytes[2 * i] = s.toByte()
                bytes[2 * i + 1] = (s shr 8).toByte()
            }
            val written = out.write(bytes, 0, bytes.size) / 4
            val real = minOf(written, got / 2)
            synchronized(lock) {
                realFrames += real
                silenceFrames += written - real
            }
        }
    }

    /** Below half full, ask for half a ring's worth, counting what's already on its way. */
    private fun requestBuffers() {
        val half = bufferCount / 2
        val ask = synchronized(lock) {
            if (ring.count + requested * CHUNK < half * CHUNK) {
                requested += half
                half
            } else 0
        }
        repeat(ask) { (sampleRequest as Emitter).trigger() }
    }

    private fun reportPlayed() {
        val out = line ?: return
        val position = out.longFramePosition
        val delta = synchronized(lock) {
            if (lastPosition < 0) { lastPosition = position; startPosition = position; return }
            val raw = position - lastPosition
            if (raw <= 0) return
            lastPosition = position
            // Anything the line has played beyond the real frames written was silence.
            val silencePlayed = maxOf(0L, (position - startPosition) - realFrames).coerceAtMost(silenceFrames)
            val crossed = silencePlayed - silenceReported
            silenceReported = silencePlayed
            raw - crossed
        }
        if (delta > 0) (samplesPlayed as EmitterOf<Double>).trigger(delta.toDouble())
    }

    override fun play() {
        if (playing) return
        synchronized(lock) {
            realFrames = 0; silenceFrames = 0; silenceReported = 0; lastPosition = -1
        }
        requestBuffers()
        line?.start()
        playing = true
        synchronized(lock) { lock.notifyAll() }
        clock = timer.scheduleWithFixedDelay(::reportPlayed, 0, 50, TimeUnit.MILLISECONDS)
    }

    override fun pause() {
        playing = false
        clock?.cancel(false)
        line?.stop()
    }

    override fun addSamples(samples: Float32Array) {
        synchronized(lock) {
            ring.write(samples.data, samples.length.toInt())
            requested = (requested - 1).coerceAtLeast(0)
        }
    }

    /** A seek: what is queued belongs to the old position. */
    override fun resetSamples() {
        synchronized(lock) { ring.clear() }
        line?.flush()
    }

    override fun destroy() {
        open = false
        playing = false
        clock?.cancel(false)
        timer.shutdownNow()
        synchronized(lock) { lock.notifyAll() }
        line?.close()
    }

    override suspend fun enumerateOutputDevices(): AtList<ISynthOutputDevice> = AtList()
    override suspend fun setOutputDevice(device: ISynthOutputDevice?) {}
    override suspend fun getOutputDevice(): ISynthOutputDevice? = null

    private companion object {
        const val TAG = "RiffTabs"
        const val RATE = 44_100
        /** Samples per chunk the synthesizer renders: 2,048 stereo frames, about 46 ms. */
        const val CHUNK = 4096
    }
}

/** Interleaved samples waiting for the line. Callers hold the output's lock. */
private class FloatRing(capacity: Int) {
    private val data = FloatArray(capacity)
    private var readAt = 0
    private var writeAt = 0
    var count = 0
        private set

    fun write(src: FloatArray, length: Int) {
        for (i in 0 until length) {
            if (count == data.size) { readAt = (readAt + 1) % data.size; count-- }
            data[writeAt] = src[i]
            writeAt = (writeAt + 1) % data.size
            count++
        }
    }

    fun read(dst: FloatArray, max: Int): Int {
        val n = minOf(max, count)
        for (i in 0 until n) {
            dst[i] = data[readAt]
            readAt = (readAt + 1) % data.size
        }
        count -= n
        return n
    }

    fun clear() {
        readAt = 0; writeAt = 0; count = 0
    }
}
