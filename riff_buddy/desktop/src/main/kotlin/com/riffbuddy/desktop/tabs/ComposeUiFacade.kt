@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.riffbuddy.desktop.tabs

import alphaTab.AlphaTabApiBase
import alphaTab.IEventEmitter
import alphaTab.IEventEmitterOfT
import alphaTab.LayoutMode
import alphaTab.PlayerMode
import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Score
import alphaTab.platform.Cursors
import alphaTab.platform.IContainer
import alphaTab.platform.IMouseEventArgs
import alphaTab.platform.IUiFacade
import alphaTab.platform.skia.AlphaSkiaImage
import alphaTab.rendering.IScoreRenderer
import alphaTab.rendering.RenderFinishedEventArgs
import alphaTab.rendering.utils.Bounds
import alphaTab.synth.IAlphaSynth
import alphaTab.synth.IAudioExporterWorker
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.riffbuddy.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import javax.swing.SwingUtilities

/**
 * One score on screen: alphaTab's API, and everything it draws into, as
 * Compose state the score view reads.
 *
 * alphaTab is written for a browser and talks to its page through a small
 * set of hooks — create a canvas, place a cursor, scroll to here, run this on
 * the main thread. [ComposeUiFacade] answers those hooks; this class holds
 * what they produce: the rendered pieces of the score, the cursors and the
 * scroll positions. alphaTab measures in dp, and draws its pieces at the
 * screen's density, so they stay sharp.
 */
class ScoreHost(val density: Float, horizontal: Boolean, zoom: Float) {

    /** One rendered piece of the score, in dp, with its image once drawn. */
    class Part(val id: String, val x: Double, val y: Double, val w: Double, val h: Double, val image: ImageBitmap?)

    val parts = mutableStateListOf<Part>()
    var totalWidth by mutableStateOf(0.0)
    var totalHeight by mutableStateOf(0.0)
    /** Room past the end, so the last line can scroll up to the top. */
    var overflowX by mutableStateOf(0.0)
    var overflowY by mutableStateOf(0.0)
    var viewportWidth by mutableStateOf(0.0); private set
    var viewportHeight by mutableStateOf(0.0); private set
    var cursorsShown by mutableStateOf(false)

    val vertical = ScrollState(0)
    val horizontal = ScrollState(0)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The score view's own scope: animated scrolling needs its frame clock. */
    var viewScope: CoroutineScope? = null

    val root = RootContainer(this)
    val surface = BoxContainer(this)
    val cursorLayer = BoxContainer(this)
    val barCursor = BoxContainer(this)
    val beatCursor = BoxContainer(this)
    val selectionLayer = BoxContainer(this)

    private val visible = CompletableDeferred<Unit>()
    private val whenVisible = mutableListOf<() -> Unit>()

    val settings = Settings().apply {
        core.engine = "skia"
        core.useWorkers = true
        // Automatic, which becomes the synthesizer once a score loads. Created
        // up front instead, the player exists when the tab player subscribes
        // to its events, and alphaTab replays the last "song loaded" event to
        // a new subscriber by reading a getter of the threaded player's that
        // calls itself forever.
        player.playerMode = PlayerMode.EnabledAutomatic
        player.enableCursor = true
        player.enableUserInteraction = true
        display.layoutMode = if (horizontal) LayoutMode.Horizontal else LayoutMode.Page
        display.scale = zoom.toDouble()
        AlphaTabDesktop.useTextFont(this)
    }

    private val facade = ComposeUiFacade(this)
    val api: AlphaTabApiBase<ScoreHost> = AlphaTabApiBase(facade, this)

    /** alphaTab's main thread is the window's, as a browser's is its page's. */
    fun post(action: () -> Unit) {
        SwingUtilities.invokeLater {
            runCatching(action).onFailure { AppLog.w(TAG, "alphaTab callback failed", it) }
        }
    }

    /** Runs [action] on the window's thread: now if already there. */
    fun ui(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else post(action)
    }

    fun viewportChanged(widthDp: Double, heightDp: Double) {
        if (widthDp == viewportWidth && heightDp == viewportHeight) return
        viewportWidth = widthDp
        viewportHeight = heightDp
        if (widthDp > 0 && !visible.isCompleted) {
            visible.complete(Unit)
            whenVisible.toList().forEach { it() }
            whenVisible.clear()
        }
        (root.resize as Emitter).trigger()
    }

    suspend fun awaitVisible() = visible.await()

    fun onVisible(action: () -> Unit) {
        if (visible.isCompleted) action() else whenVisible += action
    }

    internal fun addPart(result: RenderFinishedEventArgs) {
        totalWidth = result.totalWidth
        totalHeight = result.totalHeight
        parts.removeAll { it.id == result.id }
        parts += Part(result.id, result.x, result.y, result.width, result.height, null)
        // Desktop memory is plentiful and scores are small: every piece is
        // drawn as soon as it is laid out, rather than as it scrolls into view.
        api.renderer.renderResult(result.id)
    }

    internal fun fillPart(result: RenderFinishedEventArgs) {
        val image = (result.renderResult as? AlphaSkiaImage)?.use(::toImageBitmap) ?: return
        val i = parts.indexOfFirst { it.id == result.id }
        val part = Part(result.id, result.x, result.y, result.width, result.height, image)
        if (i >= 0) parts[i] = part else parts += part
    }

    private fun toImageBitmap(image: AlphaSkiaImage): ImageBitmap? {
        val w = image.width.toInt()
        val h = image.height.toInt()
        val pixels = image.readPixels()?.asByteArray() ?: return null
        // alphaSkia hands its pixels over in RGBA order whatever the system's
        // own order is; read as native order, red bar numbers came out blue.
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
        bitmap.installPixels(pixels)
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }

    private var scrollJobs = mutableMapOf<ScrollState, Job>()

    internal fun scrollTo(state: ScrollState, offsetDp: Double, speedMs: Double) {
        ui {
            scrollJobs.remove(state)?.cancel()
            val target = (offsetDp * density).toInt().coerceAtLeast(0)
            scrollJobs[state] = (viewScope ?: return@ui).launch {
                if (speedMs < 10) state.scrollTo(target)
                else state.animateScrollTo(target, androidx.compose.animation.core.tween(speedMs.toInt()))
            }
        }
    }

    internal fun stopScrolling() = ui {
        scrollJobs.values.forEach { it.cancel() }
        scrollJobs.clear()
    }

    fun destroy() {
        runCatching { api.destroy() }.onFailure { AppLog.w(TAG, "alphaTab did not close cleanly", it) }
        scope.cancel()
    }

    companion object {
        const val TAG = "RiffTabs"
    }
}

/** The answers to alphaTab's hooks, for a Compose window. */
internal class ComposeUiFacade(private val host: ScoreHost) : IUiFacade<ScoreHost> {

    private lateinit var api: AlphaTabApiBase<ScoreHost>

    override val rootContainer: IContainer get() = host.root
    override val areWorkersSupported: Boolean get() = true
    override val canRender: Boolean get() = true
    override val resizeThrottle: Double get() = 25.0
    override val canRenderChanged: IEventEmitter = Emitter()

    override val rootContainerBecameVisible: IEventEmitter = object : IEventEmitter {
        override fun on(value: () -> Unit): () -> Unit {
            host.onVisible(value)
            return { }
        }
        override fun off(value: () -> Unit) {}
    }

    override fun initialize(api: AlphaTabApiBase<ScoreHost>, settings: ScoreHost) {
        this.api = api
        api.settings = settings.settings
    }

    override fun destroy() {}

    override fun createCanvasElement(): IContainer = host.surface

    override fun triggerEvent(container: IContainer, eventName: String, details: Any?, originalEvent: IMouseEventArgs?) {}

    override fun initialRender() {
        api.renderer.preRender.on { _ -> host.ui { host.parts.clear() } }
        host.onVisible {
            api.renderer.width = host.root.width
            api.renderer.updateSettings(api.settings)
        }
    }

    override fun beginAppendRenderResults(renderResults: RenderFinishedEventArgs?) {
        if (renderResults != null) host.post { host.addPart(renderResults) }
    }

    override fun beginUpdateRenderResults(renderResults: RenderFinishedEventArgs) {
        host.post { host.fillPart(renderResults) }
    }

    override fun createWorkerRenderer(): IScoreRenderer =
        AlphaTabWorkers.renderer(api) { action -> host.post(action) }

    override fun createWorkerPlayer(): IAlphaSynth {
        val player = AlphaTabWorkers.synth(DesktopSynthOutput(), api.settings) { action -> host.post(action) }
        player.ready.on {
            player.loadSoundFont(Uint8Array(AlphaTabDesktop.resource("sonivox.sf2").toUByteArray()), false)
        }
        return player
    }

    override fun createWorkerAudioExporter(synth: IAlphaSynth?): IAudioExporterWorker =
        throw UnsupportedOperationException("audio export is not used")

    override fun createBackingTrackPlayer(): IAlphaSynth =
        throw UnsupportedOperationException("only the synthesizer player is used")

    override fun createCursors(): Cursors {
        host.ui { host.cursorsShown = true }
        return Cursors(host.cursorLayer, host.barCursor, host.beatCursor, host.selectionLayer)
    }

    override fun destroyCursors() = host.ui { host.cursorsShown = false }

    override fun beginInvoke(action: () -> Unit) {
        if (!AlphaTabWorkers.postIfOnWorker(action)) host.post(action)
    }

    override fun throttle(action: () -> Unit, delay: Double): () -> Unit {
        var job: Job? = null
        return {
            job?.cancel()
            job = host.scope.launch {
                delay(delay.toLong())
                action()
            }
        }
    }

    override fun removeHighlights() {}
    override fun highlightElements(groupId: String, masterBarIndex: Double) {}

    override fun createSelectionElement(): IContainer = BoxContainer(host)

    override fun getScrollContainer(): IContainer = host.root

    override fun getOffset(scrollElement: IContainer?, container: IContainer): Bounds = Bounds().apply {
        x = 0.0; y = 0.0; w = container.width; h = container.height
    }

    override fun scrollToY(scrollElement: IContainer, offset: Double, speed: Double) =
        host.scrollTo(host.vertical, offset, speed)

    override fun scrollToX(scrollElement: IContainer, offset: Double, speed: Double) =
        host.scrollTo(host.horizontal, offset, speed)

    override fun stopScrolling(scrollElement: IContainer) = host.stopScrolling()

    override fun setCanvasOverflow(canvasElement: IContainer, overflow: Double, isVertical: Boolean) = host.ui {
        if (isVertical) host.overflowY = overflow else host.overflowX = overflow
    }

    override fun load(data: Any?, success: (Score) -> Unit, error: (alphaTab.core.ecmaScript.Error) -> Unit): Boolean {
        when (data) {
            is Score -> success(data)
            is ByteArray -> success(ScoreLoader.loadScoreFromBytes(Uint8Array(data.asUByteArray()), api.settings))
            else -> return false
        }
        return true
    }

    override fun loadSoundFont(data: Any?, append: Boolean): Boolean {
        val player = api.player ?: return false
        if (data !is ByteArray) return false
        player.loadSoundFont(Uint8Array(data.asUByteArray()), append)
        return true
    }
}

/**
 * A rectangle alphaTab places: the score's surface, a cursor, a selected
 * stretch, or the layer that holds them. Its bounds are Compose state.
 */
class BoxContainer(private val host: ScoreHost) : IContainer {
    // Every change is queued, never applied on the spot, so changes happen in
    // the order alphaTab asked for them even when some of its own steps are
    // queued: a highlight drawn and then cleared stays cleared.

    /** A move alphaTab asked to happen over time: the beat cursor gliding through a beat. */
    class Glide(val fromX: Double, val toX: Double, val startNanos: Long, val millis: Double) {
        fun xAt(nanos: Long): Double {
            if (millis <= 0) return toX
            val t = ((nanos - startNanos) / 1e6 / millis).coerceIn(0.0, 1.0)
            return fromX + (toX - fromX) * t
        }
    }

    var x by mutableStateOf(0.0); private set
    var y by mutableStateOf(0.0); private set
    var w by mutableStateOf(0.0); private set
    var h by mutableStateOf(0.0); private set
    var glide by mutableStateOf<Glide?>(null); private set
    val children = mutableStateListOf<BoxContainer>()

    override var width: Double
        get() = w
        set(value) = host.post { w = value }
    override var height: Double
        get() = h
        set(value) = host.post { h = value }
    override val isVisible: Boolean get() = true
    override var scrollLeft: Double
        get() = 0.0
        set(@Suppress("UNUSED_PARAMETER") value) {}
    override var scrollTop: Double
        get() = 0.0
        set(@Suppress("UNUSED_PARAMETER") value) {}

    override fun appendChild(child: IContainer) = host.post { children += child as BoxContainer }
    override fun stopAnimation() = host.post { glide = null }

    override fun setBounds(x: Double, y: Double, w: Double, h: Double) = host.post {
        glide = null
        this.x = x; this.y = y; this.w = w; this.h = h
    }

    /**
     * Glides from wherever the cursor is drawn now, not from where the last
     * glide was headed: alphaTab sends the next glide before the last one
     * ends, as a browser animation would carry on from mid-move. Starting
     * from the old target instead made the cursor jump ahead on every note.
     */
    override fun transitionToX(duration: Double, x: Double) = host.post {
        val now = System.nanoTime()
        glide = Glide(glide?.xAt(now) ?: this.x, x, now, duration)
        this.x = x
    }

    override fun clear() = host.post { children.clear() }

    override var resize: IEventEmitter = Emitter()
    override var mouseDown: IEventEmitterOfT<IMouseEventArgs> = EmitterOf()
    override var mouseMove: IEventEmitterOfT<IMouseEventArgs> = EmitterOf()
    override var mouseUp: IEventEmitterOfT<IMouseEventArgs> = EmitterOf()
}

/** The visible window onto the score: its size and scroll positions, in dp. */
class RootContainer(private val host: ScoreHost) : IContainer {
    override var width: Double
        get() = host.viewportWidth
        set(@Suppress("UNUSED_PARAMETER") value) {}
    override var height: Double
        get() = host.viewportHeight
        set(@Suppress("UNUSED_PARAMETER") value) {}
    override val isVisible: Boolean get() = host.viewportWidth > 0
    override var scrollLeft: Double
        get() = host.horizontal.value / host.density.toDouble()
        set(value) = host.scrollTo(host.horizontal, value, 0.0)
    override var scrollTop: Double
        get() = host.vertical.value / host.density.toDouble()
        set(value) = host.scrollTo(host.vertical, value, 0.0)

    override fun appendChild(child: IContainer) {}
    override fun stopAnimation() {}
    override fun setBounds(x: Double, y: Double, w: Double, h: Double) {}
    override fun transitionToX(duration: Double, x: Double) {}
    override fun clear() {}

    override var resize: IEventEmitter = Emitter()
    override var mouseDown: IEventEmitterOfT<IMouseEventArgs> = EmitterOf()
    override var mouseMove: IEventEmitterOfT<IMouseEventArgs> = EmitterOf()
    override var mouseUp: IEventEmitterOfT<IMouseEventArgs> = EmitterOf()
}

/** A mouse event on the score, at a point in score coordinates (dp). */
class ScoreMouse(private val x: Double, private val y: Double, private val host: ScoreHost) : IMouseEventArgs {
    var prevented = false; private set
    override val isLeftMouseButton: Boolean get() = true
    override fun getX(relativeTo: IContainer): Double = if (relativeTo is RootContainer) x - relativeTo.scrollLeft else x
    override fun getY(relativeTo: IContainer): Double = if (relativeTo is RootContainer) y - relativeTo.scrollTop else y
    override fun preventDefault() { prevented = true }
}
