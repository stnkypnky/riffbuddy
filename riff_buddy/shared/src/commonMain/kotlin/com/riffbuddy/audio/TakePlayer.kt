package com.riffbuddy.audio

import com.riffbuddy.platform.AppLog
import com.riffbuddy.platform.AudioLoop
import com.riffbuddy.platform.Volatile
import com.riffbuddy.platform.locked
import com.riffbuddy.platform.nanoTime
import com.riffbuddy.platform.startAudioLoop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class TakePlayerState(
    val loaded: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val lengthMs: Long = 0,
    val selected: Int = 0,
    val takes: Int = 0,
)

/**
 * Plays a take, or two for A/B compare, through [AbMixer]. The same
 * render-thread shape as the looper and the metronome.
 */
class TakePlayer(private val outputs: AudioOutputs) {

    private val _state = MutableStateFlow(TakePlayerState())
    val state: StateFlow<TakePlayerState> = _state

    private val lock = Any()
    private var mixer: AbMixer? = null
    private var worker: AudioLoop? = null
    @Volatile private var running = false

    fun load(takes: List<Pcm>, startFrames: List<Int>) {
        stop()
        val m = AbMixer(takes, startFrames)
        locked(lock) { mixer = m }
        _state.value = TakePlayerState(loaded = true, lengthMs = m.length * 1000 / m.sampleRate, takes = takes.size)
    }

    fun unload() {
        stop()
        locked(lock) { mixer = null }
        _state.value = TakePlayerState()
    }

    fun togglePlay() = if (running) stop() else play()

    fun play() {
        if (running) return
        val m = locked(lock) { mixer } ?: return
        if (m.finished) locked(lock) { m.seek(0) }
        running = true
        _state.update { it.copy(playing = true) }
        worker = startAudioLoop("takes") {
            try {
                render(m)
            } catch (t: Throwable) {
                AppLog.w("RiffTakes", "playback stopped", t)
            } finally {
                running = false
                _state.update { it.copy(playing = false) }
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }

    /** Instant, and the position does not move. */
    fun select(index: Int) {
        locked(lock) { mixer?.select(index) }
        _state.update { it.copy(selected = index) }
    }

    fun seekMs(ms: Long) {
        locked(lock) {
            val m = mixer ?: return
            m.seek(ms * m.sampleRate / 1000)
            _state.update { it.copy(positionMs = m.position * 1000 / m.sampleRate) }
        }
    }

    fun nudge(deltaMs: Long) = seekMs((_state.value.positionMs + deltaMs).coerceAtLeast(0))

    private suspend fun render(m: AbMixer) {
        val sr = m.sampleRate
        val track = outputs.open(sr, StreamKind.MUSIC) { it * 2 }
        try {
            // Small blocks, so an A/B switch is heard within ~10 ms of the tap.
            val block = 480
            val out = ShortArray(block)
            val prime = (track.bufferFrames - block).coerceAtLeast(block)
            var queued = 0
            var started = false
            var lastPublish = 0L
            while (running) {
                val real = locked(lock) { m.render(out, block) }
                track.write(out, 0, block)
                queued += block
                if (!started && queued >= prime) { track.play(); started = true }
                val now = nanoTime()
                if (now - lastPublish > 60_000_000L) {
                    lastPublish = now
                    _state.update { it.copy(positionMs = m.position * 1000 / sr) }
                }
                if (real < block) break
            }
            if (!started) track.play()
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
            _state.update { it.copy(positionMs = m.position * 1000 / sr) }
        }
    }
}
