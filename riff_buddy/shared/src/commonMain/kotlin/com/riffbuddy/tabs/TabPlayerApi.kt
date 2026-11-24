package com.riffbuddy.tabs

import com.riffbuddy.domain.TabPrefs

/**
 * A tab file's bytes and the name it came with. [generated] marks a score this
 * app wrote (a backing track), which skips the trial sound pass on load.
 */
class TabSource(val bytes: ByteArray, val fileName: String, val generated: Boolean = false)

/** What is remembered per file, put back when it opens. Loop bars are 1-based. */
data class PlaybackMemory(
    val positionMs: Long = 0,
    val speedPercent: Int = 100,
    val loopStartBar: Int? = null,
    val loopEndBar: Int? = null,
    /** Tracks, shown track and view settings. */
    val prefs: TabPrefs = TabPrefs(),
)

/** One track, as the mixer shows it. */
data class TrackState(
    val index: Int,
    val name: String,
    val muted: Boolean = false,
    val solo: Boolean = false,
    /** 0.0–1.0, where 1.0 is the file's own level. */
    val volume: Float = 1f,
)

/**
 * The tab player as the screens see it: state to draw controls from, and every
 * command the controls send. Each platform renders and plays the score its own
 * way behind this; the state properties are Compose state, so reading them in a
 * composable redraws it when they change.
 */
interface TabPlayer {
    val loading: Boolean
    val ready: Boolean
    val playing: Boolean
    val positionMs: Long
    val lengthMs: Long
    val error: String?

    /**
     * The start of the beat now playing, in MIDI ticks (960 a quarter): what a
     * backing track's "what to play" panel follows. It moves once a beat, not
     * on every position report, so whatever follows it redraws at that pace.
     */
    val tick: Long

    /**
     * Called each time a score's sound is ready — the first load and every
     * regeneration — so a screen can re-apply its mutes and loop to the fresh
     * score.
     */
    var onReady: (() -> Unit)?

    val speedPercent: Int
    val loopStartBar: Int?
    val loopEndBar: Int?

    /** Waiting for a tap on the loop's first bar (1) or its last (2); 0 when not picking. */
    val pickingLoop: Int

    val countIn: Boolean
    val metronome: Boolean
    val zoom: Float
    val horizontal: Boolean

    /** Tablature only, or standard notation above it. */
    val tabOnly: Boolean
    val shownTrack: Int
    val tracks: List<TrackState>

    fun playPause()
    fun stop()
    fun setSpeed(percent: Int)

    /** Loop the whole score, end straight back to the start. */
    fun loopWhole(on: Boolean)

    fun startPickingLoop()
    fun cancelPickingLoop()

    /** Jump to a saved loop: bars are 1-based, as the player counts them. */
    fun useLoop(startBar: Int, endBar: Int, speed: Int)
    fun clearLoop()

    fun useCountIn(on: Boolean)
    fun useMetronome(on: Boolean)
    fun setMute(index: Int, muted: Boolean)
    fun setSolo(index: Int, solo: Boolean)
    fun setVolume(index: Int, volume: Float)
    fun showTrack(index: Int)
    fun zoomTo(scale: Float)
    fun showTabOnly(on: Boolean)
    fun useHorizontal(on: Boolean)

    /** Where everything stands now, to be saved with the file. */
    fun currentMemory(): PlaybackMemory

    companion object {
        val SPEEDS = 25..150
        const val ZOOM_MIN = 0.6f
        const val ZOOM_MAX = 1.8f
    }
}
