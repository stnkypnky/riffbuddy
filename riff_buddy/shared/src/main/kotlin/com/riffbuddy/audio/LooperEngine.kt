package com.riffbuddy.audio

import com.riffbuddy.platform.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** What the looper screen draws. */
data class LooperState(
    val loaded: Boolean = false,
    val playing: Boolean = false,
    val countingIn: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speedPercent: Int = 100,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val loopsCompleted: Int = 0,
    val volume: Float = 1f,
    val countIn: Boolean = false,
    val metronome: Boolean = false,
    val bpm: Int? = null,
    val error: String? = null,
    /** The loop trainer, when one is running. */
    val trainer: LoopTrainerState? = null,
    /** When the trainer started, for the practice-log line. */
    val trainerStartedAt: Long? = null,
)

/**
 * The looper: a decoded song, streamed through [LoopRenderer] to an output
 * stream, with an optional count-in and metronome mixed in.
 *
 * Built the way the metronome is: a render thread writing a continuous
 * stream, with every click placed at its own sample index. Nothing here waits
 * for time to pass, so nothing here can drift.
 *
 * Commands from the screen take [lock] for the moment it takes to change a
 * field; the render thread takes it only around producing one block, never
 * around the blocking write to the track.
 */
class LooperEngine(private val outputs: AudioOutputs) {

    private val _state = MutableStateFlow(LooperState())
    val state: StateFlow<LooperState> = _state

    /** When this run's first sample left the speaker, for lining up a play-along take. */
    val outputClock = OutputClock()

    /** Called on the render thread each time the loop comes round. */
    @Volatile var onLoopCompleted: ((loops: Int) -> Unit)? = null

    private val lock = Any()
    private var renderer: LoopRenderer? = null
    private var track: PcmOutput? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private var countInLeft = 0

    // ---- loading -----------------------------------------------------------

    /** Which library entry is decoded and in memory, so reopening it is instant. */
    var loadedId: Long? = null
        private set

    /** The waveform outline of whatever is loaded. */
    var peaks: FloatArray = FloatArray(0)
        private set

    fun load(audio: DecodedAudio, id: Long) {
        stop()
        synchronized(lock) { renderer = LoopRenderer(audio.pcm) }
        loadedId = id
        peaks = audio.peaks
        _state.value = LooperState(loaded = true, durationMs = audio.durationMs)
    }

    /** Frees the song's memory — tens of MB — when it is no longer wanted. */
    fun unload() {
        stop()
        synchronized(lock) { renderer = null }
        loadedId = null
        peaks = FloatArray(0)
        _state.value = LooperState()
    }

    // ---- transport ---------------------------------------------------------

    fun play() {
        if (running || renderer == null) return
        synchronized(lock) {
            val r = renderer ?: return
            // Played to the end with no loop: start again from the top.
            if (r.finished) r.seek(0.0)
            val s = _state.value
            countInLeft = if (s.countIn && s.bpm != null) {
                (4 * r.pcm.sampleRate * 60.0 / (s.bpm * r.speed)).roundToInt()
            } else 0
        }
        running = true
        outputClock.reset()
        _state.update { it.copy(playing = true, countingIn = countInLeft > 0, error = null) }
        worker = thread(name = "looper", priority = Thread.MAX_PRIORITY) {
            try {
                renderLoop()
            } catch (t: Throwable) {
                AppLog.w(TAG, "looper stopped", t)
                _state.update { it.copy(error = t.message ?: "Audio stopped unexpectedly") }
            } finally {
                running = false
                releaseTrack()
                _state.update { it.copy(playing = false, countingIn = false) }
            }
        }
    }

    fun pause() = stop()

    fun togglePlay() = if (running) pause() else play()

    private fun stop() {
        running = false
        worker?.join(500)
        worker = null
        releaseTrack()
    }

    /** The nudge buttons, and the tap on the waveform. */
    fun seekMs(ms: Long) {
        synchronized(lock) {
            val r = renderer ?: return
            r.seek(r.pcm.msToFrame(ms.coerceAtLeast(0)).toDouble())
            publish(r)
        }
    }

    fun nudge(deltaMs: Long) {
        seekMs(_state.value.positionMs + deltaMs)
    }

    fun setSpeed(percent: Int) {
        synchronized(lock) {
            val r = renderer ?: return
            val pct = percent.coerceIn(25, 150)
            r.speed = pct / 100.0
            _state.update { it.copy(speedPercent = pct) }
        }
    }

    /** Returns false when the loop is too short for the stretcher. */
    fun setLoop(startMs: Long?, endMs: Long?): Boolean = synchronized(lock) {
        val r = renderer ?: return false
        if (startMs == null || endMs == null) {
            r.setLoop(null, null)
            _state.update { it.copy(loopStartMs = null, loopEndMs = null, loopsCompleted = 0) }
            return true
        }
        val a = minOf(startMs, endMs)
        val b = maxOf(startMs, endMs)
        val ok = r.setLoop(r.pcm.msToFrame(a), r.pcm.msToFrame(b))
        if (ok) _state.update { it.copy(loopStartMs = a, loopEndMs = b, loopsCompleted = 0) }
        publish(r)
        ok
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        track?.setVolume(v)
        _state.update { it.copy(volume = v) }
    }

    fun setCountIn(on: Boolean) = _state.update { it.copy(countIn = on) }

    fun setMetronome(on: Boolean) = _state.update { it.copy(metronome = on) }

    fun setBpm(bpm: Int?) = _state.update { it.copy(bpm = bpm?.coerceIn(20, 300)) }

    // ---- loop trainer ------------------------------------------------------

    /**
     * Starts the trainer on the current loop: jumps to A at the start speed and
     * plays. From here it runs unattended — each wrap is counted on the render
     * thread, and the speed changes at the exact sample the loop comes round.
     */
    fun startTrainer(config: LoopTrainerConfig): Boolean {
        val loopStart = _state.value.loopStartMs ?: return false
        stop()
        val trainer = LoopTrainer.start(config)
        setSpeed(trainer.percent)
        seekMs(loopStart)
        _state.update { it.copy(trainer = trainer, trainerStartedAt = System.currentTimeMillis()) }
        play()
        return true
    }

    fun stopTrainer() = _state.update { it.copy(trainer = null) }

    /** Manual mode: "Clean run". */
    fun cleanRun() {
        synchronized(lock) {
            val t = _state.value.trainer ?: return
            applyTrainer(LoopTrainer.onCleanRun(t))
        }
    }

    /** Caller holds [lock]. */
    private fun applyTrainer(next: LoopTrainerState) {
        val r = renderer ?: return
        r.speed = next.percent / 100.0
        _state.update { it.copy(trainer = next, speedPercent = next.percent) }
        if (next.done) running = false
    }

    // ---- the render thread -------------------------------------------------

    private fun renderLoop() {
        val r = synchronized(lock) { renderer } ?: return
        val sr = r.pcm.sampleRate
        val t = openTrack(sr)
        track = t
        t.setVolume(_state.value.volume)

        val block = 1024
        val out = ShortArray(block)
        val scratch = ShortArray(block)
        val offsets = IntArray(ClickGrid.MAX_PER_BLOCK)
        val downbeats = BooleanArray(ClickGrid.MAX_PER_BLOCK)
        val accent = ClickSynth.render(ClickSound.WOODBLOCK, Accent.ACCENT, true, sr)
        val normal = ClickSynth.render(ClickSound.WOODBLOCK, Accent.NORMAL, true, sr)
        // A click that runs past the end of a block carries on in the next.
        var carry: ShortArray? = null
        var carryAt = 0

        fun click(v: ShortArray, at: Int) {
            val reached = mixInto(out, v, at, 0)
            if (reached < v.size) { carry = v; carryAt = reached }
        }

        // Queue audio before starting playback, never play() an empty stream —
        // that is an underrun before the first note.
        val prime = (t.bufferFrames - block).coerceAtLeast(block)
        var queued = 0
        var started = false
        var lastPublish = 0L
        var loopsSeen = r.loopsCompleted
        // Headroom, measured where it matters: how long producing a block
        // takes against how long it plays. Logged every ~10 s of audio, so the
        // slowest device's real margin can be read from the log after a session.
        var busyNanos = 0L
        var measuredBlocks = 0
        val blockNanos = block * 1_000_000_000L / sr

        while (running) {
            var songEnded = false
            val began = System.nanoTime()
            synchronized(lock) {
                val s = _state.value
                java.util.Arrays.fill(out, 0.toShort())

                // Finish any click left over from the last block first.
                carry?.let { v ->
                    carryAt = mixInto(out, v, 0, carryAt)
                    if (carryAt >= v.size) carry = null
                }

                // Count-in: silence and four clicks, at the tempo about to be heard.
                var filled = 0
                if (countInLeft > 0 && s.bpm != null) {
                    val beatOut = sr * 60.0 / (s.bpm * r.speed)
                    val done = (4 * beatOut).roundToInt() - countInLeft
                    val take = minOf(countInLeft, block)
                    for (beat in 0 until 4) {
                        val at = (beat * beatOut).roundToInt() - done
                        if (at in 0 until take) click(if (beat == 0) accent else normal, at)
                    }
                    countInLeft -= take
                    filled = take
                    if (countInLeft == 0) _state.update { it.copy(countingIn = false) }
                } else {
                    countInLeft = 0
                }

                // The song, from wherever the count-in ended, sample-exact.
                if (filled < block) {
                    val n = block - filled
                    val startPos = r.position
                    val real = r.render(scratch, n)
                    for (i in 0 until n) {
                        out[filled + i] = (out[filled + i] + scratch[i]).coerceIn(-32768, 32767).toShort()
                    }
                    if (s.metronome && s.bpm != null) {
                        val count = ClickGrid.clicksInto(
                            startPos, n, r.speed, sr * 60.0 / s.bpm, 4, r.loop, 0.0, offsets, downbeats,
                        )
                        for (c in 0 until count) click(if (downbeats[c]) accent else normal, filled + offsets[c])
                    }
                    songEnded = real < n
                }

                if (r.loopsCompleted != loopsSeen) {
                    repeat(r.loopsCompleted - loopsSeen) {
                        _state.value.trainer?.let { applyTrainer(LoopTrainer.onLoop(it)) }
                    }
                    loopsSeen = r.loopsCompleted
                    onLoopCompleted?.invoke(loopsSeen)
                }
            }

            busyNanos += System.nanoTime() - began
            if (++measuredBlocks == 430) {
                val avg = busyNanos / measuredBlocks
                AppLog.i(TAG, "render %.2f ms per %.1f ms block at %d%% — %.0fx real time".format(
                    avg / 1e6, blockNanos / 1e6, (r.speed * 100).roundToInt(), blockNanos.toDouble() / avg,
                ))
                busyNanos = 0; measuredBlocks = 0
            }

            t.write(out, 0, block)
            queued += block
            if (!started && queued >= prime) {
                t.play()
                outputClock.played()
                started = true
            }
            if (started) outputClock.poll(t, sr)

            val now = System.nanoTime()
            if (now - lastPublish > 60_000_000L) {
                lastPublish = now
                synchronized(lock) { publish(r) }
            }
            if (songEnded) break
        }
        // Let a short song's last block actually sound before the track goes.
        if (!started && queued > 0) t.play()
        synchronized(lock) { publish(r) }
    }

    /**
     * Adds [v] into [out], starting at output offset [start] and voice sample
     * [from]. Returns the voice index reached, so a click can continue into the
     * next block. Saturating, so two sounds on top of each other clip rather
     * than wrap around into noise.
     */
    private fun mixInto(out: ShortArray, v: ShortArray, start: Int, from: Int): Int {
        var o = start.coerceAtLeast(0)
        var i = from
        while (o < out.size && i < v.size) {
            out[o] = (out[o] + v[i]).coerceIn(-32768, 32767).toShort()
            o++; i++
        }
        return i
    }

    private fun publish(r: LoopRenderer) {
        val loop = r.loop
        _state.update {
            it.copy(
                positionMs = r.pcm.frameToMs(r.position),
                loopsCompleted = if (loop == null) 0 else r.loopsCompleted,
            )
        }
    }

    // Music, not a metronome: a larger buffer is fine and kinder to the
    // battery. The count-in and clicks are in the same stream, so they stay in
    // time with the song whatever the buffer size.
    private fun openTrack(sampleRate: Int): PcmOutput =
        outputs.open(sampleRate, StreamKind.MUSIC) { it * 4 }

    private fun releaseTrack() {
        track?.let {
            runCatching { it.pause(); it.flush() }
            it.release()
        }
        track = null
    }

    companion object {
        const val TAG = "RiffLooper"
    }
}
