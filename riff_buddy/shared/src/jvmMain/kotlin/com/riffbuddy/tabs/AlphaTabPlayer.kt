@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.riffbuddy.tabs

import alphaTab.AlphaTabApiBase
import alphaTab.LayoutMode
import alphaTab.collections.DoubleList
import alphaTab.model.Beat
import alphaTab.model.Score
import alphaTab.synth.PlayerState
import com.riffbuddy.platform.AppLog
import com.riffbuddy.domain.TabPrefs
import com.riffbuddy.domain.TrackMix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The tab player on both platforms: its state, for Compose to draw controls
 * from, and every command the controls need, all through alphaTab's API.
 *
 * Each platform draws the score in its own view, which creates the API and
 * attaches it here; from then on events come in from alphaTab and commands go
 * out to it. Nothing outside `tabs/` holds the API or touches an alphaTab type.
 */
class AlphaTabPlayer(private val memory: PlaybackMemory) : TabPlayer {

    override var loading by mutableStateOf(true); private set
    override var ready by mutableStateOf(false); private set
    override var playing by mutableStateOf(false); private set
    override var positionMs by mutableStateOf(0L); private set
    override var lengthMs by mutableStateOf(0L); private set
    override var error by mutableStateOf<String?>(null); private set
    /**
     * The start of the beat now playing, in MIDI ticks (960 a quarter) — what
     * the backing track's "what to play" panel follows.
     *
     * Moves only on a beat boundary, never on every position event. alphaTab
     * reports position hundreds of times a second, and publishing each one
     * redrew the fretboard under it that often: on a slow tablet the main thread
     * went to 100 %, and since alphaTab feeds its synth through the main
     * thread, playback crawled — two bars in sixteen seconds. The panel only
     * needs to keep up within a beat, so a beat is the right grain.
     */
    override var tick by mutableStateOf(0L); private set

    /**
     * Called each time a score's sound is ready — the first load and every
     * regeneration — so a screen can re-apply its mutes and loop to
     * the fresh score.
     */
    override var onReady: (() -> Unit)? = null

    private var wholeLoop = false

    /** The loop playing now is not to be shown: the whole score, looping. */
    private var rangeHidden = false

    /**
     * Loop the whole score.
     *
     * Done as a playback range from the first bar to the last, not as
     * `isLooping` with no range. On a tablet the no-range form reached
     * the end of a backing track and stopped there, still reporting "playing",
     * for as long as it was watched; the ranged form is the path the tab
     * player's bar loop takes, which was already confirmed looping seamlessly
     * on that device. The highlight is cleared, so the score is not tinted
     * from end to end.
     */
    override fun loopWhole(on: Boolean) {
        wholeLoop = on
        val api = this.api ?: return
        if (!on) {
            rangeHidden = false
            api.isLooping = false
            api.playbackRange = null
            api.clearPlaybackRangeHighlight()
            return
        }
        val bars = barsOfShownTrack()?.count() ?: return
        applyRange(1, bars, keepPosition = true, visible = false)
    }

    override var speedPercent by mutableStateOf(memory.speedPercent.coerceIn(SPEEDS)); private set
    override var loopStartBar by mutableStateOf(memory.loopStartBar); private set
    override var loopEndBar by mutableStateOf(memory.loopEndBar); private set
    /** Waiting for a tap on the loop's first bar (1) or its last (2); 0 when not picking. */
    override var pickingLoop by mutableStateOf(0); private set

    override var countIn by mutableStateOf(memory.prefs.countIn); private set
    override var metronome by mutableStateOf(memory.prefs.metronome); private set
    override var zoom by mutableStateOf(memory.prefs.zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)); private set
    override var horizontal by mutableStateOf(memory.prefs.horizontal); private set
    /** Tablature only, or standard notation above it. Tab only unless told otherwise. */
    override var tabOnly by mutableStateOf(true); private set
    override var shownTrack by mutableStateOf(0); internal set
    override val tracks = mutableStateListOf<TrackState>()

    private var api: AlphaTabApiBase<*>? = null
    private var score: Score? = null
    private var restored = false

    // ---- transport ---------------------------------------------------------

    override fun playPause() { api?.playPause() }

    override fun stop() { api?.stop() }

    /**
     * 25–150 %. The synthesizer renders the notes at the new tempo rather than
     * stretching recorded audio, so pitch cannot drift at any speed — which is
     * why "50 % halves the tempo without changing pitch" is a property
     * of MIDI playback rather than something to engineer.
     */
    override fun setSpeed(percent: Int) {
        speedPercent = percent.coerceIn(SPEEDS)
        api?.playbackSpeed = speedPercent / 100.0
    }

    // ---- A–B loop ----------------------------------------------------------

    override fun startPickingLoop() { pickingLoop = 1 }

    override fun cancelPickingLoop() { pickingLoop = 0 }

    /** Jump to a saved loop: bars are 1-based, as the player counts them. */
    override fun useLoop(startBar: Int, endBar: Int, speed: Int) {
        pickingLoop = 0
        loopStartBar = minOf(startBar, endBar)
        loopEndBar = maxOf(startBar, endBar)
        setSpeed(speed)
        applyLoop(keepPosition = false)
    }

    override fun clearLoop() {
        loopStartBar = null
        loopEndBar = null
        pickingLoop = 0
        rangeHidden = false
        api?.apply {
            isLooping = false
            playbackRange = null
            clearPlaybackRangeHighlight()
        }
    }

    /** A bar was tapped. [bar] is 1-based. */
    private fun onBarTapped(bar: Int) {
        when (pickingLoop) {
            1 -> { loopStartBar = bar; loopEndBar = null; pickingLoop = 2 }
            2 -> {
                val first = loopStartBar ?: bar
                loopStartBar = minOf(first, bar)
                loopEndBar = maxOf(first, bar)
                pickingLoop = 0
                applyLoop(keepPosition = false)
                return
            }
        }
        // alphaTab's own tap handling has just cleared the playback range —
        // it treats any tap that is not a drag as "select nothing". Right for
        // a notation viewer, wrong for practice: tapping bar 6 to go back
        // inside a 5–8 loop should not throw the loop away. So it goes back.
        if (loopEndBar != null) applyLoop(keepPosition = true)
        else if (wholeLoop) loopWhole(true)
    }

    /**
     * Sets the loop through alphaTab's own highlight-then-apply path rather
     * than computing ticks here. alphaTab's tick lookup knows about repeats,
     * anacrusis bars and tempo changes; arithmetic on bar starts would not.
     */
    private fun applyLoop(keepPosition: Boolean) {
        val start = loopStartBar ?: return
        val end = loopEndBar ?: return
        applyRange(start, end, keepPosition, visible = true)
    }

    /** Whether the current score has been laid out — cursors and bar bounds exist. */
    private var drawn = false

    /** Range work asked for before the score was drawn, run once it is. */
    private var afterDraw: (() -> Unit)? = null

    /**
     * Highlighting a range needs the score's bar bounds, which exist only once
     * alphaTab has finished laying it out. Its sound can be ready first — a
     * regenerated backing track reliably is — and asking early is a
     * NullPointerException inside alphaTab, on the main thread, which closed
     * the app on a tablet. So range work waits for the drawing, and anything
     * alphaTab still throws here is logged rather than fatal.
     */
    private fun applyRange(start: Int, end: Int, keepPosition: Boolean, visible: Boolean) {
        if (!drawn) {
            afterDraw = { applyRange(start, end, keepPosition, visible) }
            return
        }
        runCatching { applyRangeNow(start, end, keepPosition, visible) }
            .onFailure { AppLog.w(TAG, "could not set the loop", it) }
    }

    private fun applyRangeNow(start: Int, end: Int, keepPosition: Boolean, visible: Boolean) {
        val api = this.api ?: return
        val first = firstBeat(start) ?: return
        val last = lastBeat(end) ?: return
        val tick = api.tickPosition
        rangeHidden = !visible
        api.highlightPlaybackRange(first, last)
        api.applyPlaybackRangeFromHighlight()
        api.isLooping = true
        if (!visible) api.clearPlaybackRangeHighlight()
        if (keepPosition) api.tickPosition = tick
    }

    private fun barsOfShownTrack() =
        score?.tracks?.getOrNull(shownTrack)?.staves?.firstOrNull()?.bars

    private fun firstBeat(bar: Int): Beat? =
        barsOfShownTrack()?.getOrNull(bar - 1)?.voices?.firstOrNull()?.beats?.firstOrNull()

    private fun lastBeat(bar: Int): Beat? =
        barsOfShownTrack()?.getOrNull(bar - 1)?.voices?.firstOrNull()?.beats?.lastOrNull()

    // ---- practice aids -----------------------------------------------------

    override fun useCountIn(on: Boolean) {
        countIn = on
        api?.countInVolume = if (on) 1.0 else 0.0
    }

    override fun useMetronome(on: Boolean) {
        metronome = on
        api?.metronomeVolume = if (on) 1.0 else 0.0
    }

    // ---- mixer -------------------------------------------------------------

    override fun setMute(index: Int, muted: Boolean) = withTrack(index) { track ->
        api?.changeTrackMute(alphaTab.collections.List(track), muted)
        tracks[index] = tracks[index].copy(muted = muted)
    }

    override fun setSolo(index: Int, solo: Boolean) = withTrack(index) { track ->
        api?.changeTrackSolo(alphaTab.collections.List(track), solo)
        tracks[index] = tracks[index].copy(solo = solo)
    }

    override fun setVolume(index: Int, volume: Float) = withTrack(index) { track ->
        api?.changeTrackVolume(alphaTab.collections.List(track), volume.toDouble())
        tracks[index] = tracks[index].copy(volume = volume)
    }

    /** Which track's notation and tab is drawn. Playback is unaffected. */
    override fun showTrack(index: Int) = withTrack(index) {
        val api = this.api ?: return@withTrack
        val s = score ?: return@withTrack
        shownTrack = index
        api.renderScore(s, DoubleList(index.toDouble()))
        // The loop highlight is drawn on beats of the shown track, so it has to
        // be re-drawn on the new one.
        if (loopEndBar != null) applyLoop(keepPosition = true)
    }

    private inline fun withTrack(index: Int, action: (alphaTab.model.Track) -> Unit) {
        val track = score?.tracks?.getOrNull(index) ?: return
        action(track)
    }

    // ---- view --------------------------------------------------------------

    override fun zoomTo(scale: Float) {
        zoom = scale.coerceIn(ZOOM_MIN, ZOOM_MAX)
        api?.apply {
            settings.display.scale = zoom.toDouble()
            updateSettings()
            render()
        }
    }

    /**
     * Tab only, or notation and tab. alphaTab's stave profile decides which
     * staves are drawn; the score itself, and so the sound, is untouched.
     */
    override fun showTabOnly(on: Boolean) {
        if (on == tabOnly && api != null) return
        tabOnly = on
        val s = score ?: return
        applyStaves(s)
        api?.renderScore(s, DoubleList(shownTrack.toDouble()))
        if (loopEndBar != null) applyLoop(keepPosition = true)
    }

    /**
     * Staff by staff rather than alphaTab's global stave profile: the "tab"
     * profile gave a drum track no staff at all, and alphaTab's layout then
     * indexed into an empty list and drew nothing. Fretted staves get tab,
     * plus notation when asked; anything without frets is left as it is.
     */
    internal fun applyStaves(s: Score) {
        for (track in s.tracks) for (staff in track.staves) {
            if (staff.isPercussion || staff.tuning.count() == 0) continue
            staff.showTablature = true
            staff.showStandardNotation = !tabOnly
        }
    }

    /**
     * The track to draw first: a guitar if there is one (it is what the
     * player plays), else anything fretted, else the first. A backing track's first
     * track is its drums, which as tab would be nothing at all.
     */
    internal fun firstTrackToShow(s: Score): Int {
        var fretted = -1
        var i = 0
        for (track in s.tracks) {
            val hasFrets = track.staves.count() > 0 && !track.staves[0].isPercussion && track.staves[0].tuning.count() > 0
            if (hasFrets && track.playbackInfo.program.toInt() in 24..31) return i
            if (hasFrets && fretted < 0) fretted = i
            i++
        }
        return if (fretted >= 0) fretted else 0
    }

    override fun useHorizontal(on: Boolean) {
        horizontal = on
        api?.apply {
            settings.display.layoutMode = if (on) LayoutMode.Horizontal else LayoutMode.Page
            updateSettings()
            render()
        }
    }

    /** What to save when the player leaves this file. */
    override fun currentMemory(): PlaybackMemory = PlaybackMemory(
        positionMs = positionMs,
        speedPercent = speedPercent,
        loopStartBar = loopStartBar.takeIf { loopEndBar != null },
        loopEndBar = loopEndBar,
        prefs = TabPrefs(
            // Only once a file has loaded; before that shownTrack is just the default.
            shownTrack = if (tracks.isEmpty()) memory.prefs.shownTrack else shownTrack,
            zoom = zoom,
            horizontal = horizontal,
            countIn = countIn,
            metronome = metronome,
            tracks = if (tracks.isEmpty()) memory.prefs.tracks
            else tracks.associate { it.index to TrackMix(it.muted, it.solo, it.volume) },
        ),
    )

    // ---- wiring ------------------------------------------------------------

    /** Wires this player to the alphaTab API a platform's score view created. */
    fun attach(api: AlphaTabApiBase<*>) {
        this.api = api
        api.renderFinished.on {
            AppLog.i(TAG, "render finished")
            loading = false
            drawn = true
            afterDraw?.let { pending -> afterDraw = null; pending() }
        }
        api.playerReady.on {
            AppLog.i(TAG, "player ready")
            ready = true
            restore()
            onReady?.invoke()
        }
        // A new score on the same view reloads its MIDI without a second
        // playerReady; this is how a regenerated backing track is noticed.
        api.midiLoaded.on {
            AppLog.i(TAG, "midi loaded")
            if (ready) onReady?.invoke()
        }
        api.playerStateChanged.on { args -> playing = args.state == PlayerState.Playing }
        api.playerPositionChanged.on { args ->
            // Tenths of a second is all a clock readout shows; updating at the
            // synth's own rate would recompose for nothing.
            val now = (args.currentTime.toLong() / 100) * 100
            if (now != positionMs) positionMs = now
            val beat = (args.currentTick.toLong() / 960) * 960
            if (beat != tick) tick = beat
            val end = args.endTime.toLong()
            if (end != lengthMs) lengthMs = end
        }
        api.beatMouseUp.on { beat ->
            if (beat != null) onBarTapped(beat.voice.bar.masterBar.index.toInt() + 1)
        }
        // alphaTab draws the last range it was given again whenever it
        // finishes drawing, and once its cursors exist, which can be after
        // the range was set and cleared. A range meant to stay hidden is
        // cleared again each time it comes back.
        api.playbackRangeHighlightChanged.on { e ->
            if (rangeHidden && e.startBeat != null) api.clearPlaybackRangeHighlight()
        }
        api.error.on { e ->
            AppLog.w(TAG, "alphaTab error", e)
            error = "This file could not be shown: ${e.message ?: "unknown problem"}"
            loading = false
        }
    }

    internal fun loaded(parsed: Score) {
        score = parsed
        drawn = false
        tracks.clear()
        var i = 0
        for (track in parsed.tracks) {
            val mix = memory.prefs.mix(i)
            tracks += TrackState(i, track.name.ifBlank { "Track ${i + 1}" }, mix.muted, mix.solo, mix.volume)
            i++
        }
    }

    /** The track to draw on open: the one last showing, if the file still has it. */
    internal fun trackToShow(s: Score): Int =
        memory.prefs.shownTrack?.takeIf { it < s.tracks.count() } ?: firstTrackToShow(s)

    /** Speed, loop and position come back as they were left. Once. */
    private fun restore() {
        if (restored) return
        restored = true
        val api = this.api ?: return
        api.playbackSpeed = speedPercent / 100.0
        api.countInVolume = if (countIn) 1.0 else 0.0
        api.metronomeVolume = if (metronome) 1.0 else 0.0
        // The mixer as it was left: a track muted last time stays muted.
        tracks.filter { it.muted || it.solo || it.volume != 1f }.forEach { t ->
            withTrack(t.index) { track ->
                val one = alphaTab.collections.List(track)
                if (t.muted) api.changeTrackMute(one, true)
                if (t.solo) api.changeTrackSolo(one, true)
                if (t.volume != 1f) api.changeTrackVolume(one, t.volume.toDouble())
            }
        }
        if (loopStartBar != null && loopEndBar != null) applyLoop(keepPosition = false)
        if (memory.positionMs > 0) api.timePosition = memory.positionMs.toDouble()
    }

    fun detach() {
        api = null
        ready = false
        playing = false
    }

    /**
     * Parses [source] off the main thread, then hands it to alphaTab to draw
     * and turn into sound. Call once the API is attached.
     */
    suspend fun open(source: TabSource) {
        val target = api ?: return
        val score = withContext(Dispatchers.Default) {
            runCatching {
                if (source.generated) TabScores.loadTrusted(source.bytes, source.fileName)
                else TabScores.load(source.bytes, source.fileName)
            }
        }
        score.onSuccess {
            loaded(it)
            applyStaves(it)
            val first = trackToShow(it)
            shownTrack = first
            // renderScore also builds the audio, synchronously, on this — the
            // main — thread. TabScores.load has already run that pass once, so
            // this should not throw; but alphaTab is a large library translated
            // from TypeScript, and an exception here is not an error message,
            // it is the app closing. A backstop costs one try.
            try {
                target.renderScore(it, DoubleList(first.toDouble()))
            } catch (e: Exception) {
                AppLog.w(TAG, "renderScore threw", e)
                failed("This file could not be played: ${e.message ?: e.javaClass.simpleName}")
            }
        }.onFailure { failed(it.message ?: "This file could not be read.") }
    }

    internal fun failed(message: String) {
        error = message
        loading = false
    }

    companion object {
        const val TAG = "RiffTabs"
        val SPEEDS = TabPlayer.SPEEDS
        const val ZOOM_MIN = TabPlayer.ZOOM_MIN
        const val ZOOM_MAX = TabPlayer.ZOOM_MAX
    }
}

private fun <T> alphaTab.collections.List<T>.getOrNull(index: Int): T? =
    if (index in 0 until count()) this[index] else null

private fun <T> alphaTab.collections.List<T>.firstOrNull(): T? = getOrNull(0)

private fun <T> alphaTab.collections.List<T>.lastOrNull(): T? = getOrNull(count() - 1)

