package com.riffbuddy.audio

import com.riffbuddy.domain.music.NoteReading
import com.riffbuddy.domain.music.Notes
import com.riffbuddy.domain.music.Tuning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

data class TunerState(
    val listening: Boolean = false,
    val reading: NoteReading? = null,
    /** Which string of the tuning the reading is nearest, or null. */
    val nearestString: Int? = null,
    /** Strings confirmed in tune this session, for the ladder's ticks. */
    val settled: Set<Int> = emptySet(),
    val guidedIndex: Int? = null,
    /** True when the signal is too quiet or too noisy to call. */
    val lowSignal: Boolean = false,
    val level: Float = 0f,
    val error: String? = null,
)

/**
 * The tuner's microphone: a raw input, with the speech processors off.
 *
 * **Why the processing must be off.** Automatic gain control, noise suppression
 * and echo cancellation are all built for speech. They assume a voice, and a
 * sustained guitar note looks to them like noise to be attenuated — AGC in
 * particular will fade a held note away while it is still ringing, which is
 * exactly the part a tuner needs. Voice processing does not make a tuner
 * slightly worse; it makes it wrong.
 */
class TunerEngine(private val inputs: AudioInputs) {

    private val _state = MutableStateFlow(TunerState())
    val state: StateFlow<TunerState> = _state

    @Volatile private var running = false
    private var listenThread: Thread? = null
    @Volatile private var mic: PcmInput? = null

    @Volatile var tuning: Tuning = Tuning.STANDARD
    @Volatile var a4: Double = Notes.DEFAULT_A4
    @Volatile private var guided: GuidedTuning? = null

    fun setGuided(enabled: Boolean) {
        guided = if (enabled) GuidedTuning(tuning.stringCount) else null
        _state.update { it.copy(guidedIndex = guided?.index) }
    }

    fun selectString(index: Int) {
        guided?.select(index)
        _state.update { it.copy(guidedIndex = guided?.index) }
    }

    /** Caller must already hold the microphone permission, asked for at the tuner, not before. */
    fun start() {
        if (running) return
        running = true
        _state.update { it.copy(listening = true, error = null, settled = emptySet()) }
        listenThread = thread(name = "tuner", priority = Thread.MAX_PRIORITY) {
            try {
                listen()
            } catch (t: Throwable) {
                running = false
                _state.update { it.copy(listening = false, error = t.message ?: "Microphone stopped") }
            }
        }
    }

    fun stop() {
        running = false
        listenThread?.join(400)
        listenThread = null
        release()
        _state.update { TunerState() }
    }

    private fun listen() {
        val sampleRate = 48_000
        val detector = PitchDetector(sampleRate = sampleRate)
        val smoother = PitchSmoother()

        // Raw, with speech processing off — see AudioInputs.open for why.
        val recorder = inputs.open(sampleRate, detector.windowSize * 4 * 2)
        mic = recorder
        recorder.start()

        val window = FloatArray(detector.windowSize * 2)
        val chunk = FloatArray(detector.windowSize / 2)

        while (running) {
            val read = recorder.read(chunk, 0, chunk.size)
            if (read <= 0) continue

            // Slide the window along so each analysis overlaps the last. Without
            // the overlap the display would update four times a second; ten
            // is what a needle needs to feel live.
            System.arraycopy(window, read, window, 0, window.size - read)
            System.arraycopy(chunk, 0, window, window.size - read, read)

            var sum = 0.0
            for (i in 0 until read) sum += chunk[i] * chunk[i]
            val level = sqrt(sum / read).toFloat()

            val raw = detector.detect(window)
            val smoothed = smoother.accept(raw)
            val reading = smoothed?.let { Notes.nearest(it, a4) }

            val nearest = reading?.let { nearestString(it) }
            if (reading != null && nearest != null && reading.inTune) {
                _state.update { it.copy(settled = it.settled + nearest) }
            }

            guided?.let { sequence ->
                val target = tuning.midi.getOrNull(sequence.index)
                val onTarget = reading != null && target != null && reading.midi == target
                sequence.accept(onTarget && reading.inTune)
            }

            _state.update {
                it.copy(
                    reading = reading,
                    nearestString = nearest,
                    guidedIndex = guided?.index,
                    level = level,
                    // Quiet enough that any reading would be a guess.
                    lowSignal = level < LOW_SIGNAL && reading == null,
                )
            }
        }

        release()
        _state.update { it.copy(listening = false) }
    }

    /** Which string of the current tuning this reading is closest to. */
    private fun nearestString(reading: NoteReading): Int? {
        val strings = tuning.midi
        if (strings.isEmpty()) return null
        val best = strings.indices.minByOrNull { abs(strings[it] - reading.midi) } ?: return null
        // More than three semitones away is not that string being out of tune,
        // it is a different note entirely.
        return if (abs(strings[best] - reading.midi) <= 3) best else null
    }

    private fun release() {
        // Released explicitly: a tuner left holding the microphone would block
        // every other app that wants it.
        mic?.release()
        mic = null
    }

    private companion object {
        const val LOW_SIGNAL = 0.005f
    }
}
