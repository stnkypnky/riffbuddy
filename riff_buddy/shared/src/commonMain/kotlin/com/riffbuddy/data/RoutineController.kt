package com.riffbuddy.data

import com.riffbuddy.audio.Accent
import com.riffbuddy.audio.ClickSound
import com.riffbuddy.audio.ClickSynth
import com.riffbuddy.domain.Routine
import com.riffbuddy.domain.RoutineRun
import com.riffbuddy.domain.SessionMode
import com.riffbuddy.platform.Volatile
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A routine being run, owned by the app rather than a screen so it keeps
 * going — and keeps cueing — while the player is in the metronome or on a
 * song's page.
 *
 * The logic is [RoutineRun], pure and tested; this only ticks it once a
 * second, plays the cue when a step ends, and logs the result when it
 * finishes: one session, one item per step.
 */
class RoutineController(
    private val practice: PracticeController,
    private val outputs: com.riffbuddy.audio.AudioOutputs,
) {

    private val _run = MutableStateFlow<RoutineRun?>(null)
    val run: StateFlow<RoutineRun?> = _run

    /** Seconds left on the current step, for the countdown. */
    private val _remaining = MutableStateFlow(0L)
    val remaining: StateFlow<Long> = _remaining

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null
    private val cue = ClickSynth.render(ClickSound.BEEP, Accent.ACCENT, true, 48_000)

    fun start(routine: Routine, now: Instant = Clock.System.now()) {
        if (routine.steps.isEmpty()) return
        _run.value = RoutineRun(routine, stepStartedAt = now)
        scope.launch {
            // One session for the whole routine; if one is already running,
            // the steps join it rather than start a second.
            ownsSession = !practice.state.value.isRunning
            if (ownsSession) practice.start(SessionMode.ROUTINE, now)
        }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val current = _run.value ?: break
                val (next, ended) = current.tick(Clock.System.now())
                if (ended) playCue()
                _run.value = next
                _remaining.value = next.remaining(Clock.System.now())
                if (next.finished) { log(next); break }
                delay(500)
            }
        }
    }

    fun next() = update { it.next(Clock.System.now()) }
    fun skip() = update { it.skip(Clock.System.now()) }
    fun pause() = update { it.pause(Clock.System.now()) }
    fun resume() = update { it.resume(Clock.System.now()) }

    /** Stops the routine and logs what was done. */
    fun stop() {
        val current = _run.value ?: return
        val done = current.finish(Clock.System.now())
        _run.value = done
        ticker?.cancel()
        scope.launch { log(done) }
    }

    /** Clears a finished run from the screen. */
    fun dismiss() {
        if (_run.value?.finished == true) _run.value = null
    }

    private fun update(change: (RoutineRun) -> RoutineRun) {
        val current = _run.value ?: return
        val next = change(current)
        _run.value = next
        _remaining.value = next.remaining(Clock.System.now())
        if (next.finished) {
            ticker?.cancel()
            scope.launch { log(next) }
        }
    }

    private var logged: RoutineRun? = null

    /** Whether this routine started the session, and so should end it. */
    @Volatile private var ownsSession = false

    private suspend fun log(finished: RoutineRun) {
        if (logged === finished || logged?.startedAt == finished.startedAt) return
        logged = finished
        finished.logItems().forEach { practice.addItem(it) }
        // A session the routine opened is closed with it, so the log shows
        // one complete session; one the player had already started is theirs to end.
        if (ownsSession) practice.end()
        playCue()
    }

    /** A short, clear beep through the media stream: audible over an amp at practice volume. */
    private fun playCue() {
        runCatching {
            // Two beeps: distinct from the metronome's single click.
            val twice = ShortArray(cue.size * 3).also { cue.copyInto(it, 0); cue.copyInto(it, cue.size * 2) }
            outputs.playClip(twice, 48_000, com.riffbuddy.audio.StreamKind.SONIFICATION)
        }
    }
}
