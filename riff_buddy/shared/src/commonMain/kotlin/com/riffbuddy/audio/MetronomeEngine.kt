package com.riffbuddy.audio

import com.riffbuddy.platform.AppLog
import com.riffbuddy.platform.AudioLoop
import com.riffbuddy.platform.Volatile
import com.riffbuddy.platform.locked
import com.riffbuddy.platform.startAudioLoop
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * What the UI needs to know about a running metronome.
 *
 * [pulseTick] only ever increases; the lamp watches it rather than [beatInBar],
 * because two identical beat numbers in a row (a one-beat bar, or a repeat)
 * would otherwise not register as a new flash.
 */
data class MetronomeState(
    val isRunning: Boolean = false,
    val settings: MetronomeSettings = MetronomeSettings(),
    val beatInBar: Int = 0,
    val barsPlayed: Int = 0,
    val isCountIn: Boolean = false,
    val trainer: TrainerState? = null,
    val pulseTick: Long = 0,
    /** Set when audio could not start, so the screen can say so. */
    val error: String? = null,
    /**
     * Times the hardware ran out of samples to play.
     *
     * Kept because it is the only evidence of a dropout available without a
     * microphone: the audio clock cannot drift, so a beat heard late means the
     * render thread failed to keep the buffer fed. Nothing displays it now, but
     * if a hiccup is ever reported this is the number that answers it.
     */
    val underruns: Int = 0,
    /** Frames the hardware has consumed. Drives the once-a-second publish below. */
    val framesPlayed: Long = 0,
    /**
     * The ramp last set up, kept after it stops so the setup panel opens on
     * the numbers the player chose rather than on defaults every time.
     */
    val lastTrainer: TrainerConfig? = null,
) {
    /** What the big number shows: the trainer's tempo when it is driving. */
    val displayBpm: Int get() = trainer?.currentBpm ?: settings.bpm
}

/**
 * The metronome and speed trainer, on one output stream.
 *
 * The design in one sentence: a background thread writes an unbroken stream of
 * samples, and each click is *placed at its own sample index* by [BeatClock], so
 * the beat cannot drift no matter how long it runs or how busy the phone gets.
 * Nothing here sleeps until the next beat; the write itself blocks until the
 * hardware wants more, which is what paces the loop.
 *
 * Two clocks are in play and they are not the same:
 *  - the *write* position, how far the render thread has filled ahead;
 *  - the *playback head*, what the speaker has actually reached.
 * They differ by the buffer, a few tens of milliseconds. Clicks are scheduled
 * against the first, and the lamp is driven by the second — so the light flashes
 * when the beat is heard, not when the app decided on it.
 */
@OptIn(ExperimentalAtomicApi::class)
class MetronomeEngine(private val outputs: AudioOutputs) {

    private val _state = MutableStateFlow(MetronomeState())
    val state: StateFlow<MetronomeState> = _state

    private var track: PcmOutput? = null
    private var renderThread: AudioLoop? = null
    @Volatile private var running = false

    /** Set from the UI thread, picked up by the render thread at the next bar. */
    private val pendingSettings = AtomicReference<MetronomeSettings?>(null)

    /**
     * Tempo is queued separately from everything else, because it is the one
     * setting applied on the *next beat* rather than the next bar.
     * Time signature, subdivision and accents all have to wait for a bar line —
     * changing them mid-bar would put the downbeat in the wrong place — but
     * waiting a whole bar to hear a tempo change makes the slider feel broken.
     */
    private val pendingBpm = AtomicInt(0)
    private val pendingTrainer = AtomicReference<TrainerConfig?>(null)
    @Volatile private var trainerCommand: TrainerCommand? = null

    private enum class TrainerCommand { NAILED_IT, REPEAT }

    private var sampleRate = 48_000

    /** When this run's first click left the speaker, for lining up a play-along take. */
    val outputClock = OutputClock()

    /** A pulse already written, so the lamp can be matched to the playback head. */
    private class Sounded(val sample: Long, val beatInBar: Int, val bar: Int, val countIn: Boolean)

    private val sounded = ArrayDeque<Sounded>()
    private val soundedLock = Any()

    // ---- Control, all called from the UI thread ----------------------------

    fun start(settings: MetronomeSettings, trainer: TrainerConfig? = null) {
        if (!running) outputClock.reset()
        if (running) {
            update(settings)
            return
        }
        // A ramp set up while stopped (startTrainer only records it then) is
        // honored by Start. It used to be dropped here: the card read
        // "101 → 131" and the metronome played a flat 101.
        val config = trainer ?: _state.value.trainer?.config
        val trainerState = config?.let { SpeedTrainer.start(it) }
        _state.value = MetronomeState(
            isRunning = true,
            settings = settings,
            trainer = trainerState,
            isCountIn = settings.countInBars > 0,
            lastTrainer = _state.value.lastTrainer,
        )
        pendingBpm.store(settings.bpm)
        running = true
        renderThread = startAudioLoop("metronome") {
            try {
                render(settings, trainerState)
            } catch (t: Throwable) {
                running = false
                _state.update {
                    it.copy(isRunning = false, error = t.message ?: "Audio stopped unexpectedly")
                }
            }
        }
    }

    fun stop() {
        running = false
        renderThread?.join(500)
        renderThread = null
        releaseTrack()
        locked(soundedLock) { sounded.clear() }
        _state.value = _state.value.copy(isRunning = false, isCountIn = false)
    }

    /**
     * Change settings while running.
     *
     * The state is updated straight away, whether or not the engine has applied
     * it yet. That matters for the tempo slider: binding it to a value the
     * render thread only republishes once a bar makes the thumb jump back under
     * the finger on every drag. The engine honors the request within a beat, so
     * showing it immediately is accurate, not optimistic.
     */
    fun update(settings: MetronomeSettings) {
        _state.update { it.copy(settings = settings) }
        if (running) {
            pendingSettings.store(settings)
            pendingBpm.store(settings.bpm)
        }
    }

    fun setBpm(bpm: Int) = update(_state.value.settings.copy(bpm = bpm.coerceIn(MetronomeSettings.BPM_RANGE)))

    fun startTrainer(config: TrainerConfig) {
        _state.update { it.copy(lastTrainer = config) }
        if (running) pendingTrainer.store(config)
        else _state.update { it.copy(trainer = SpeedTrainer.start(config)) }
    }

    fun stopTrainer() {
        pendingTrainer.store(null)
        _state.update { it.copy(trainer = null) }
        // Hand tempo back to whatever the dial says, rather than leaving it
        // parked wherever the ramp happened to stop.
        if (running) pendingBpm.store(_state.value.settings.bpm)
    }

    fun nailedIt() { trainerCommand = TrainerCommand.NAILED_IT }
    fun repeatBar() { trainerCommand = TrainerCommand.REPEAT }

    /** Called ~60 times a second by the UI to move the lamp to the audible beat. */
    fun syncToPlayback() {
        val t = track ?: return
        val head = t.playbackHeadPosition.toLong()
        val underruns = t.underrunCount

        // Published at one second of resolution, deliberately.
        //
        // The playback head advances every time this is called — sixty times a
        // second — so republishing it verbatim emits sixty state changes a
        // second, and every one of them recomposes the whole metronome screen,
        // the tempo slider included. That is what made dragging the slider feel
        // rough while the metronome was running.
        val current = _state.value
        val second = head / sampleRate
        if (underruns != current.underruns || second != current.framesPlayed / sampleRate) {
            _state.update { it.copy(underruns = underruns, framesPlayed = head) }

        }
        var latest: Sounded? = null
        locked(soundedLock) {
            while (sounded.size > 1 && sounded[1].sample <= head) sounded.removeFirst()
            val first = sounded.firstOrNull()
            if (first != null && first.sample <= head) latest = first
        }
        val pulse = latest ?: return
        val s = _state.value
        if (s.beatInBar != pulse.beatInBar || s.barsPlayed != pulse.bar) {
            _state.update {
                it.copy(
                    beatInBar = pulse.beatInBar,
                    barsPlayed = pulse.bar,
                    isCountIn = pulse.countIn,
                    pulseTick = it.pulseTick + 1,
                )
            }
        }
    }

    // ---- The render thread -------------------------------------------------

    private suspend fun render(initial: MetronomeSettings, initialTrainer: TrainerState?) {
        var settings = initial
        var trainer = initialTrainer

        // Low latency, and twice the smallest buffer the platform allows: small
        // enough that a tempo change is heard on the next beat, large enough
        // that a busy moment does not starve the speaker.
        val t = outputs.open(sampleRate, StreamKind.SONIFICATION, lowLatency = true) { it * 2 }
        track = t
        t.setVolume(settings.volume.coerceIn(0f, 1f))
        // Playback deliberately does not start here. Calling play() on an empty
        // track means the hardware asks for samples that have not been written
        // yet and reports an underrun before a single click has sounded — which
        // is exactly the one underrun the first ten-minute soak recorded. The
        // loop below starts playback once there is something queued to play.

        var voices = ClickSynth.renderAll(settings.sound, sampleRate)
        val clock = BeatClock(sampleRate).apply {
            reset((trainer?.currentBpm ?: settings.bpm).toDouble(), settings.subdivision.perBeat)
        }

        val frames = 512
        val buffer = ShortArray(frames)
        var playing = false
        val primeFrames = (t.bufferFrames - frames).coerceAtLeast(frames).toLong()
        AppLog.i("RiffBuddy", "metronome: track buffer ${t.bufferFrames} frames, priming $primeFrames")
        var streamPos = 0L
        var nextPulse = 0L

        // A click can run past the end of a buffer; this carries the remainder.
        var carry: ShortArray? = null
        var carryOffset = 0

        /*
         * The bar is expanded once and held, not rebuilt per pulse.
         *
         * This loop is the render thread: it must not allocate. A list built for
         * every click is a few hundred bytes a second of garbage, and a GC pause
         * landing between two writes is exactly the dropout the whole design
         * exists to avoid. So the bar's pulses and their waveforms are flattened
         * into arrays whenever the settings change, and the loop only indexes.
         */
        var barPulses: Array<Pulse> = settings.bar().toTypedArray()
        var barVoices: Array<ShortArray> = voicesFor(barPulses, settings, voices)

        // See BarCursor for why this is not `nextPulse % pulsesPerBar`.
        val cursor = BarCursor(barPulses.size)
        var countInBarsLeft = settings.countInBars

        while (running) {
            buffer.fill(0)

            carry?.let { pcm ->
                val n = minOf(frames, pcm.size - carryOffset)
                for (i in 0 until n) buffer[i] = clamp(buffer[i] + pcm[carryOffset + i])
                carryOffset += n
                if (carryOffset >= pcm.size) { carry = null; carryOffset = 0 }
            }

            val bufferEnd = streamPos + frames
            while (true) {
                // A bar boundary is where tempo changes and trainer steps land,
                // which is what makes "tempo steps exactly on bar boundaries" true by
                // construction. Only do the bookkeeping once the downbeat is
                // actually being written into this buffer.
                if (cursor.startsNewBar(nextPulse)) {
                    if (clock.sampleOf(nextPulse) >= bufferEnd) break
                    if (countInBarsLeft > 0) countInBarsLeft--

                    pendingTrainer.exchange(null)?.let { trainer = SpeedTrainer.start(it) }
                    trainerCommand?.let { cmd ->
                        trainerCommand = null
                        trainer = trainer?.let {
                            if (cmd == TrainerCommand.NAILED_IT) SpeedTrainer.onNailedIt(it)
                            else SpeedTrainer.onRepeat(it)
                        }
                    }
                    trainer = trainer?.let { SpeedTrainer.onBarComplete(it) }

                    pendingSettings.exchange(null)?.let { incoming ->
                        if (incoming.sound != settings.sound) {
                            voices = ClickSynth.renderAll(incoming.sound, sampleRate)
                        }
                        if (incoming.volume != settings.volume) {
                            t.setVolume(incoming.volume.coerceIn(0f, 1f))
                        }
                        settings = incoming
                        barPulses = settings.bar().toTypedArray()
                        barVoices = voicesFor(barPulses, settings, voices)
                    }
                    // The new bar length, if the settings just changed it, takes
                    // effect from this bar.
                    cursor.advance(barPulses.size)

                    // The trainer's steps and any change of subdivision land on
                    // the bar line; the dial's tempo is handled per pulse below.
                    val bpm = trainer?.currentBpm ?: pendingBpm.load().takeIf {
                        it in MetronomeSettings.BPM_RANGE
                    } ?: settings.bpm
                    if (bpm.toDouble() != clock.bpm ||
                        settings.subdivision.perBeat != clock.pulsesPerBeat
                    ) {
                        clock.rebase(nextPulse, bpm.toDouble(), settings.subdivision.perBeat)
                    }
                    if (trainer?.shouldStop == true) {
                        running = false
                        break
                    }
                    // Only the trainer is published from here. Settings are
                    // already in the state, put there by update() the moment
                    // they were asked for.
                    val publish = trainer
                    _state.update { it.copy(trainer = publish) }
                }

                // A tempo change while running takes effect on the next beat.
                // Checked every pulse, not only at bar lines — waiting a
                // whole bar is over two seconds at a slow tempo, which reads as
                // the control being broken. The trainer outranks the dial while
                // it is running, since it is the thing setting the tempo.
                if (trainer == null) {
                    val wanted = pendingBpm.load()
                    if (wanted in MetronomeSettings.BPM_RANGE &&
                        wanted.toDouble() != clock.bpm
                    ) {
                        clock.rebase(nextPulse, wanted.toDouble(), clock.pulsesPerBeat)
                    }
                }

                val at = clock.sampleOf(nextPulse)
                if (at >= bufferEnd) break

                val idx = cursor.indexOf(nextPulse)
                val pcm = barVoices[idx]
                if (pcm.isNotEmpty()) {
                    val offset = (at - streamPos).toInt()
                    val n = minOf(frames - offset, pcm.size)
                    for (i in 0 until n) {
                        buffer[offset + i] = clamp(buffer[offset + i] + pcm[i])
                    }
                    if (n < pcm.size) { carry = pcm; carryOffset = n }
                }
                locked(soundedLock) {
                    sounded.addLast(
                        Sounded(at, barPulses[idx].beatInBar, cursor.barNumber, countInBarsLeft > 0)
                    )
                    while (sounded.size > 64) sounded.removeFirst()
                }
                nextPulse++
            }

            // Blocks until the hardware has room, which is what paces this loop
            // — no sleeping, no timer, no drift.
            val written = t.write(buffer, 0, frames)
            if (written < 0) throw IllegalStateException("Audio write failed ($written)")
            streamPos += frames

            // Start only once the track is nearly full, so the first thing the
            // hardware does is play rather than starve. Stopping one buffer
            // short of full matters: write() blocks when there is no space, and
            // space only frees once playback has begun, so filling it completely
            // before calling play() would deadlock.
            if (!playing && streamPos >= primeFrames) {
                t.play()
                outputClock.played()
                playing = true
            }
            if (playing) outputClock.poll(t, sampleRate)
        }

        releaseTrack()
        _state.value = _state.value.copy(isRunning = false, isCountIn = false)
    }

    /** Flattens a bar into one waveform per pulse, so the render loop only indexes. */
    private fun voicesFor(
        pulses: Array<Pulse>,
        settings: MetronomeSettings,
        voices: Map<Pair<Accent, Boolean>, ShortArray>,
    ): Array<ShortArray> = Array(pulses.size) { i ->
        val onBeat = i % settings.subdivision.perBeat == 0
        voices[pulses[i].accent to onBeat] ?: ShortArray(0)
    }

    private fun clamp(v: Int): Short =
        v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private fun releaseTrack() {
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null
    }
}
