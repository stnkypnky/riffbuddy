package com.riffbuddy.data

import com.riffbuddy.domain.Session
import com.riffbuddy.domain.SessionItem
import com.riffbuddy.domain.SessionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * The running practice session, shared by every screen.
 *
 * Lives with the app alongside the metronome engine, for the same reason: the
 * quick bar shows the timer from any tab, so the session cannot belong to a
 * screen.
 *
 * The elapsed time is never counted up. It is always the difference between the
 * start timestamp and now, which is what keeps it right when the screen locks,
 * the app is backgrounded, or the phone sleeps for twenty minutes mid-song.
 */
class PracticeController(private val repo: SessionRepo) {

    data class State(
        val session: Session? = null,
        /** A session found unfinished at launch, awaiting the player's decision. */
        val recoverable: Session? = null,
    ) {
        val isRunning: Boolean get() = session != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * Looks for a session a previous launch left open.
     *
     * Deliberately *not* resumed automatically. An open session might be five
     * minutes old because the app crashed, or fourteen hours old because the
     * phone was put down on Tuesday — and silently counting the second as a
     * fourteen-hour practice would be worse than losing it. The player is asked.
     */
    suspend fun checkForRecoverable() {
        val open = repo.openSession()
        if (!needsAsking(open, _state.value.session)) return
        _state.update { it.copy(recoverable = open) }
    }

    suspend fun resumeRecovered() {
        val open = _state.value.recoverable ?: return
        _state.update { State(session = open, recoverable = null) }
    }

    /** Ends the recovered session at the time it was last plausibly practicing. */
    suspend fun keepRecoveredAsIs(endedAt: Instant) {
        val open = _state.value.recoverable ?: return
        repo.end(open.id, now = endedAt)
        _state.update { it.copy(recoverable = null) }
    }

    suspend fun discardRecovered() {
        val open = _state.value.recoverable ?: return
        repo.discard(open.id)
        _state.update { it.copy(recoverable = null) }
    }

    suspend fun start(mode: SessionMode = SessionMode.FREE, now: Instant = Instant.now()) {
        if (_state.value.isRunning) return
        val id = repo.start(mode, now)
        _state.update { State(session = repo.load(id)) }
    }

    suspend fun addItem(item: SessionItem) {
        val current = _state.value.session ?: return
        repo.addItem(item.copy(sessionId = current.id))
        _state.update { it.copy(session = repo.load(current.id)) }
    }

    /** Stops the clock without ending the session. */
    suspend fun pause(now: Instant = Instant.now()) {
        val current = _state.value.session ?: return
        repo.pause(current.id, now)
        _state.update { it.copy(session = repo.load(current.id)) }
    }

    suspend fun resume(now: Instant = Instant.now()) {
        val current = _state.value.session ?: return
        repo.resume(current.id, now)
        _state.update { it.copy(session = repo.load(current.id)) }
    }

    /** Ends the session and returns it, so the summary sheet can show it. */
    suspend fun end(rating: Int? = null, notes: String = "", now: Instant = Instant.now()): Session? {
        val current = _state.value.session ?: return null
        repo.end(current.id, rating, notes, now)
        val finished = repo.load(current.id)
        _state.update { State() }
        return finished
    }

    suspend fun discard() {
        val current = _state.value.session ?: return
        repo.discard(current.id)
        _state.update { State() }
    }

    companion object {
        /** Below this, a session is probably a misfire, and discarding it is offered. */
        const val SHORT_SESSION_SECONDS = 60L

        /**
         * Whether an open session in the database is one to ask about.
         *
         * Not if it is the one running now. This check runs whenever the
         * screen is built, and a screen is rebuilt for more than a cold start:
         * changing the font size mid-routine once brought up "A session was
         * left running" about the very session in progress. The controller
         * outlives the screen, so it knows the difference.
         */
        fun needsAsking(open: Session?, running: Session?): Boolean =
            open != null && open.id != running?.id
    }
}
