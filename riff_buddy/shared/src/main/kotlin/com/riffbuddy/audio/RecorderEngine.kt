package com.riffbuddy.audio

import com.riffbuddy.platform.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.concurrent.thread

enum class RecorderStatus { IDLE, RECORDING, PAUSED }

data class RecorderState(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val elapsedMs: Long = 0,
    val levelDb: Float = InputLevel.FLOOR_DB,
    /** A peak hit full scale in the last second and a half. */
    val clipping: Boolean = false,
    val recordingId: Long? = null,
    val error: String? = null,
)

/**
 * Records the microphone to a WAV file: raw source, speech processing off,
 * 48 kHz mono 16-bit straight to disk.
 *
 * **The microphone is held only while recording.** [stop] releases it, and so
 * does pause — systems show a "microphone in use" indicator, and a practice app
 * that kept it lit through a paused take would be alarming as well as rude.
 *
 * **When each sample was heard.** The input stream is timestamped: the
 * input's timestamp gives the monotonic time a given frame was
 * captured. [inputNanosOf] turns any frame of the take into that clock, which is
 * the same clock the metronome and looper report their output on — how a
 * play-along take is lined up with what was playing.
 */
class RecorderEngine(private val audioInputs: AudioInputs) {

    /** Input gain, dB. Applied before the take is written and metered. */
    @Volatile var gainDb: Float = 0f

    /** The chosen input's device id; null is the phone's own microphone. */
    @Volatile var inputId: Int? = null

    /** The inputs worth offering, the default first. */
    fun inputs(): List<RecordInput> = audioInputs.inputs()

    /**
     * "Set level": listens for [millis] while the player plays their loudest, and
     * returns the gain that would put that peak at −6 dBFS. Records nothing.
     */
    suspend fun measureGain(millis: Long = 5000): Float = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var r: PcmInput? = null
        try {
            r = audioInputs.open(sampleRate, sampleRate / 5 * 4, inputId)
            r.start()
            val buf = FloatArray(sampleRate / 50)
            var peak = 0f
            val end = System.nanoTime() + millis * 1_000_000
            while (System.nanoTime() < end) {
                val n = r.read(buf, 0, buf.size)
                for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(buf[i]))
                _state.update { it.copy(levelDb = InputLevel.toDb((peak * 32767).toInt())) }
            }
            GainStage.suggest(peak)
        } finally {
            r?.release()
            _state.update { it.copy(levelDb = InputLevel.FLOOR_DB) }
        }
    }

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state

    private var writer: WavWriter? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    /** The first reliable capture timestamp: (frame in the take, monotonic ns). */
    @Volatile private var anchorFrame = -1L
    @Volatile private var anchorNanos = 0L
    private var framesBeforePause = 0L

    val sampleRate = 48_000

    /** Caller must already hold RECORD_AUDIO. */
    fun start(recordingId: Long, file: File) {
        if (_state.value.status != RecorderStatus.IDLE) return
        writer = WavWriter(file, sampleRate)
        anchorFrame = -1L
        hardwareClock = false
        framesBeforePause = 0
        _state.value = RecorderState(status = RecorderStatus.RECORDING, recordingId = recordingId)
        open()
    }

    fun pause() {
        if (_state.value.status != RecorderStatus.RECORDING) return
        close()
        framesBeforePause = writer?.frames ?: 0
        _state.update { it.copy(status = RecorderStatus.PAUSED, levelDb = InputLevel.FLOOR_DB) }
    }

    fun resume() {
        if (_state.value.status != RecorderStatus.PAUSED) return
        // Timestamps from before a pause say nothing about after it.
        anchorFrame = -1L
        hardwareClock = false
        _state.update { it.copy(status = RecorderStatus.RECORDING) }
        open()
    }

    /** Stops, releases the microphone, finishes the WAV. Returns frames written. */
    fun stop(): Long {
        if (_state.value.status == RecorderStatus.IDLE) return 0
        close()
        val w = writer
        writer = null
        val frames = w?.frames ?: 0
        runCatching { w?.close() }
        _state.value = RecorderState()
        return frames
    }

    /** Monotonic nanoseconds at which [frame] of the take was captured, if known. */
    fun inputNanosOf(frame: Long): Long? {
        if (anchorFrame < 0) return null
        return anchorNanos + (frame - anchorFrame) * 1_000_000_000L / sampleRate
    }

    /** The frame of the take captured at monotonic time [nanos], if the clock is known. */
    fun inputFrameAt(nanos: Long): Long? {
        if (anchorFrame < 0) return null
        return LatencyCalibration.inputFrameAt(nanos, anchorFrame, anchorNanos, sampleRate)
    }

    /** True once capture has a time anchor — the moment a play-along source may start. */
    val clockKnown: Boolean get() = anchorFrame >= 0

    /**
     * Whether the anchor came from the hardware's own timestamp or from the
     * fallback. Shown with a calibration result: a fallback clock is rougher,
     * and calibration is what makes it usable.
     */
    @Volatile var hardwareClock = false
        private set

    val framesWritten: Long get() = writer?.frames ?: 0

    private fun open() {
        running = true
        worker = thread(name = "recorder", priority = Thread.MAX_PRIORITY) {
            var record: PcmInput? = null
            try {
                // Float, so a quiet raw signal is boosted before it is rounded.
                record = audioInputs.open(sampleRate, sampleRate / 5 * 4, inputId)
                record.start()
                capture(record)
            } catch (t: Throwable) {
                AppLog.w(TAG, "recorder stopped", t)
                _state.update { it.copy(error = t.message ?: "Recording stopped unexpectedly") }
            } finally {
                record?.release()
            }
        }
    }

    private fun close() {
        running = false
        worker?.join(1000)
        worker = null
    }

    private fun capture(record: PcmInput) {
        val w = writer ?: return
        val block = sampleRate / 50          // 20 ms
        val raw = FloatArray(block)
        val buf = ShortArray(block)
        val ts = FrameTime()
        var lastClip = 0L
        var lastPublish = 0L
        var loudest = InputLevel.FLOOR_DB

        while (running) {
            val n = record.read(raw, 0, block)
            if (n <= 0) continue
            val clippedByGain = GainStage.process(raw, n, gainDb, buf)
            w.write(buf, n)

            // The capture timestamp, taken once it is reported. framePosition
            // counts frames since this input started, which is the take's
            // own frame count offset by whatever was recorded before a pause.
            if (!hardwareClock && record.timestamp(ts)) {
                anchorFrame = framesBeforePause + ts.frame
                anchorNanos = ts.nanoTime
                hardwareClock = true
            } else if (anchorFrame < 0) {
                // Fallback until (or unless) the hardware reports: the last
                // frame just read was captured roughly now. Late by the input
                // buffer — a fixed amount on a given device, which is exactly
                // the kind of error calibration measures and removes.
                anchorFrame = w.frames
                anchorNanos = System.nanoTime()
            }

            val level = InputLevel.read(buf, n)
            val now = System.nanoTime()
            if (level.clipped || clippedByGain) lastClip = now
            loudest = maxOf(loudest, level.peakDb)
            if (now - lastPublish > 50_000_000L) {
                val frames = w.frames
                _state.update {
                    it.copy(
                        elapsedMs = frames * 1000 / sampleRate,
                        levelDb = loudest,
                        clipping = lastClip != 0L && now - lastClip < 1_500_000_000L,
                    )
                }
                loudest = InputLevel.FLOOR_DB
                lastPublish = now
            }
        }
    }

    companion object {
        const val TAG = "RiffRecorder"
    }
}
