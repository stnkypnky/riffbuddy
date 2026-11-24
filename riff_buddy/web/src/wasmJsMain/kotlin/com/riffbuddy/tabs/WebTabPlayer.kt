package com.riffbuddy.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import com.riffbuddy.domain.TabPrefs
import com.riffbuddy.domain.TrackMix
import com.riffbuddy.json.JSONObject
import com.riffbuddy.platform.AppLog
import com.riffbuddy.platform.toUint8Array
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * The tab player in a browser: the same state and commands as the other
 * editions' player, over alphaTab's JavaScript build (tabs-bridge.js), which
 * draws into an element placed over the app and plays through its own
 * synthesizer. The practice logic — picking a loop by tapping bars, a hidden
 * whole-score loop, what comes back from last time — lives here, as it does
 * in the other editions' player.
 */
class WebTabPlayer(private val memory: PlaybackMemory) : TabPlayer {

    override var loading by mutableStateOf(true); private set
    override var ready by mutableStateOf(false); private set
    override var playing by mutableStateOf(false); private set
    override var positionMs by mutableStateOf(0L); private set
    override var lengthMs by mutableStateOf(0L); private set
    override var error by mutableStateOf<String?>(null); private set
    /** The start of the beat now playing, in MIDI ticks; moves once a beat, not on every position report. */
    override var tick by mutableStateOf(0L); private set
    override var onReady: (() -> Unit)? = null

    override var speedPercent by mutableStateOf(memory.speedPercent.coerceIn(TabPlayer.SPEEDS)); private set
    override var loopStartBar by mutableStateOf(memory.loopStartBar); private set
    override var loopEndBar by mutableStateOf(memory.loopEndBar); private set
    override var pickingLoop by mutableStateOf(0); private set
    override var countIn by mutableStateOf(memory.prefs.countIn); private set
    override var metronome by mutableStateOf(memory.prefs.metronome); private set
    override var zoom by mutableStateOf(memory.prefs.zoom.coerceIn(TabPlayer.ZOOM_MIN, TabPlayer.ZOOM_MAX)); private set
    override var horizontal by mutableStateOf(memory.prefs.horizontal); private set
    override var tabOnly by mutableStateOf(true); private set
    override var shownTrack by mutableStateOf(0); private set
    override val tracks = mutableStateListOf<TrackState>()

    private var id = -1
    private var wholeLoop = false
    private var restored = false
    /** Range work waits until the score has been laid out: its bar bounds only exist then. */
    private var drawn = false
    private var afterDraw: (() -> Unit)? = null

    override fun playPause() = cmd("playPause")
    override fun stop() = cmd("stop")

    override fun setSpeed(percent: Int) {
        speedPercent = percent.coerceIn(TabPlayer.SPEEDS)
        cmd("speed", speedPercent / 100.0)
    }

    override fun loopWhole(on: Boolean) {
        wholeLoop = on
        if (id < 0) return
        if (!on) { jsClearRange(id); return }
        applyRange(1, jsBarCount(id), keepPosition = true, visible = false)
    }

    override fun startPickingLoop() { pickingLoop = 1 }
    override fun cancelPickingLoop() { pickingLoop = 0 }

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
        if (id >= 0) jsClearRange(id)
    }

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
        // alphaTab's own tap handling has just cleared the playback range; a
        // tap inside the loop should move the cursor, not throw the loop away.
        if (loopEndBar != null) applyLoop(keepPosition = true)
        else if (wholeLoop) loopWhole(true)
    }

    private fun applyLoop(keepPosition: Boolean) {
        val start = loopStartBar ?: return
        val end = loopEndBar ?: return
        applyRange(start, end, keepPosition, visible = true)
    }

    private fun applyRange(start: Int, end: Int, keepPosition: Boolean, visible: Boolean) {
        if (!drawn) {
            afterDraw = { applyRange(start, end, keepPosition, visible) }
            return
        }
        runCatching { jsRange(id, start, end, keepPosition, visible) }
            .onFailure { AppLog.w(TAG, "could not set the loop", it) }
    }

    override fun useCountIn(on: Boolean) { countIn = on; cmd("countIn", if (on) 1.0 else 0.0) }
    override fun useMetronome(on: Boolean) { metronome = on; cmd("metronome", if (on) 1.0 else 0.0) }

    override fun setMute(index: Int, muted: Boolean) = withTrack(index) {
        jsTrack(id, index, "mute", if (muted) 1.0 else 0.0)
        tracks[index] = tracks[index].copy(muted = muted)
    }

    override fun setSolo(index: Int, solo: Boolean) = withTrack(index) {
        jsTrack(id, index, "solo", if (solo) 1.0 else 0.0)
        tracks[index] = tracks[index].copy(solo = solo)
    }

    override fun setVolume(index: Int, volume: Float) = withTrack(index) {
        jsTrack(id, index, "volume", volume.toDouble())
        tracks[index] = tracks[index].copy(volume = volume)
    }

    override fun showTrack(index: Int) = withTrack(index) {
        shownTrack = index
        cmd("show", index.toDouble())
        // The loop highlight is drawn on the shown track's beats.
        if (loopEndBar != null) applyLoop(keepPosition = true)
    }

    private inline fun withTrack(index: Int, action: () -> Unit) {
        if (id >= 0 && index in tracks.indices) action()
    }

    override fun zoomTo(scale: Float) {
        zoom = scale.coerceIn(TabPlayer.ZOOM_MIN, TabPlayer.ZOOM_MAX)
        cmd("zoom", zoom.toDouble())
    }

    override fun showTabOnly(on: Boolean) {
        if (on == tabOnly && id >= 0) return
        tabOnly = on
        cmd("tabOnly", if (on) 1.0 else 0.0)
        if (loopEndBar != null) applyLoop(keepPosition = true)
    }

    override fun useHorizontal(on: Boolean) {
        horizontal = on
        cmd("horizontal", if (on) 1.0 else 0.0)
    }

    override fun currentMemory(): PlaybackMemory = PlaybackMemory(
        positionMs = positionMs,
        speedPercent = speedPercent,
        loopStartBar = loopStartBar.takeIf { loopEndBar != null },
        loopEndBar = loopEndBar,
        prefs = TabPrefs(
            shownTrack = if (tracks.isEmpty()) memory.prefs.shownTrack else shownTrack,
            zoom = zoom,
            horizontal = horizontal,
            countIn = countIn,
            metronome = metronome,
            tracks = if (tracks.isEmpty()) memory.prefs.tracks
            else tracks.associate { it.index to TrackMix(it.muted, it.solo, it.volume) },
        ),
    )

    // ---- wiring ----------------------------------------------------------------

    internal fun attach(element: HTMLElement) {
        id = jsCreate(element, horizontal, zoom.toDouble()) { name, json -> onEvent(name, JSONObject(json)) }
    }

    internal fun detach() {
        if (id >= 0) jsDestroy(id)
        id = -1
        ready = false
        playing = false
    }

    internal fun open(source: TabSource) {
        if (id < 0) return
        loading = true
        drawn = false
        val track = memory.prefs.shownTrack ?: -1
        jsOpen(id, source.bytes.toUint8Array(), source.fileName.endsWith(".alphatex", ignoreCase = true), source.generated, track)
    }

    private fun onEvent(name: String, o: JSONObject) {
        when (name) {
            "loaded" -> {
                tracks.clear()
                val names = o.getJSONArray("tracks")
                for (i in 0 until names.length()) {
                    val mix = memory.prefs.mix(i)
                    tracks += TrackState(i, names.getString(i), mix.muted, mix.solo, mix.volume)
                }
                shownTrack = o.optInt("shown")
            }
            "drawn" -> {
                loading = false
                drawn = true
                afterDraw?.let { pending -> afterDraw = null; pending() }
            }
            "ready" -> {
                ready = true
                restore()
                onReady?.invoke()
            }
            // A new score on the same view reloads its sound without a second "ready".
            "midi" -> if (ready) onReady?.invoke()
            "state" -> playing = o.optBoolean("playing")
            "pos" -> {
                positionMs = o.optLong("ms")
                tick = o.optLong("tick")
                lengthMs = o.optLong("end")
            }
            "bar" -> onBarTapped(o.optInt("bar"))
            "error" -> {
                val message = o.optString("message")
                AppLog.w(TAG, "alphaTab error: $message")
                error = if (tracks.isEmpty()) "This file could not be read: $message" else "This file could not be shown: $message"
                loading = false
            }
        }
    }

    /** Speed, mixer, loop and position as they were left. Once. */
    private fun restore() {
        if (restored) return
        restored = true
        cmd("speed", speedPercent / 100.0)
        cmd("countIn", if (countIn) 1.0 else 0.0)
        cmd("metronome", if (metronome) 1.0 else 0.0)
        tracks.filter { it.muted || it.solo || it.volume != 1f }.forEach { t ->
            if (t.muted) jsTrack(id, t.index, "mute", 1.0)
            if (t.solo) jsTrack(id, t.index, "solo", 1.0)
            if (t.volume != 1f) jsTrack(id, t.index, "volume", t.volume.toDouble())
        }
        if (loopStartBar != null && loopEndBar != null) applyLoop(keepPosition = false)
        if (memory.positionMs > 0) cmd("time", memory.positionMs.toDouble())
    }

    private fun cmd(name: String, value: Double = 0.0) {
        if (id >= 0) jsCmd(id, name, value)
    }

    companion object {
        const val TAG = "RiffTabs"
    }
}

/** The score, drawn by alphaTab into a page element over this part of the screen. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WebScoreView(player: WebTabPlayer, source: TabSource, modifier: Modifier) {
    var attached by androidx.compose.runtime.remember { mutableStateOf(false) }
    // The element draws over the whole canvas, dialogs included, so it steps
    // out of sight while one is open over it, or the dialog can't be seen.
    // Its wrapper, which Compose places over the canvas and keeps visible,
    // would still take every click meant for the dialog, so it stops
    // listening too.
    val covered = ScoreCover.covered
    HtmlElementView(
        factory = {
            (document.createElement("div") as HTMLElement).also {
                player.attach(it)
                attached = true
            }
        },
        modifier = modifier,
        update = {
            it.style.visibility = if (covered) "hidden" else "visible"
            (it.parentElement as? HTMLElement)?.style?.setProperty("pointer-events", if (covered) "none" else "auto")
        },
    )
    DisposableEffect(player) { onDispose { player.detach() } }
    LaunchedEffect(attached, source) { if (attached) player.open(source) }
}

private fun jsCreate(el: HTMLElement, horizontal: Boolean, zoom: Double, onEvent: (String, String) -> Unit): Int =
    js("rbTabs.create(el, horizontal, zoom, onEvent)")
private fun jsOpen(id: Int, bytes: JsAny, isTex: Boolean, generated: Boolean, track: Int): Unit =
    js("rbTabs.open(id, bytes, isTex, generated, track)")
private fun jsRange(id: Int, start: Int, end: Int, keep: Boolean, visible: Boolean): Unit =
    js("rbTabs.range(id, start, end, keep, visible)")
private fun jsBarCount(id: Int): Int = js("rbTabs.barCount(id)")
private fun jsClearRange(id: Int): Unit = js("rbTabs.clearRange(id)")
private fun jsCmd(id: Int, name: String, value: Double): Unit = js("rbTabs.cmd(id, name, value)")
private fun jsTrack(id: Int, index: Int, what: String, value: Double): Unit = js("rbTabs.track(id, index, what, value)")
private fun jsDestroy(id: Int): Unit = js("rbTabs.destroy(id)")
