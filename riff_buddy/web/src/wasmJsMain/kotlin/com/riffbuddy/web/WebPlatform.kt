package com.riffbuddy.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.riffbuddy.AppServices
import com.riffbuddy.audio.AudioFileException
import com.riffbuddy.audio.AudioOutputs
import com.riffbuddy.audio.DecodedAudio
import com.riffbuddy.audio.Pcm
import com.riffbuddy.audio.PcmBuilder
import com.riffbuddy.audio.StreamKind
import com.riffbuddy.audio.Wav
import com.riffbuddy.audio.Waveform
import com.riffbuddy.data.ReminderStore
import com.riffbuddy.domain.ReminderCalendar
import com.riffbuddy.domain.ReminderSchedule
import com.riffbuddy.domain.ReminderSettings
import com.riffbuddy.platform.AppLog
import com.riffbuddy.platform.CountdownBeeper
import com.riffbuddy.platform.Permission
import com.riffbuddy.platform.PickedFile
import com.riffbuddy.platform.Platform
import com.riffbuddy.platform.appFileSystem
import com.riffbuddy.platform.delete
import com.riffbuddy.platform.mkdirs
import com.riffbuddy.platform.readBytes
import com.riffbuddy.platform.toByteArray
import com.riffbuddy.platform.toUint8Array
import com.riffbuddy.platform.writeBytes
import com.riffbuddy.tabs.PlaybackMemory
import com.riffbuddy.tabs.TabPlayer
import com.riffbuddy.tabs.TabSource
import com.riffbuddy.tabs.WebScoreView
import com.riffbuddy.tabs.WebTabPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import okio.Buffer
import okio.BufferedSink
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import org.jetbrains.skia.Image
import kotlin.js.Promise
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The app's platform in a browser. What a browser can't do the way a phone
 * does is done the nearest honest way and said where it shows: files are
 * picked into the app's storage, saved as downloads or shared through the
 * system sheet; reminders come while the app is open; keys are kept as
 * keys-bridge.js describes.
 */
class WebPlatform(private val services: AppServices) : Platform {

    private val scope = MainScope()

    // ---- sound -----------------------------------------------------------------

    override val audioOutputs: AudioOutputs get() = services.audioOutputs

    /** The browser's own decoders, for whatever it plays: MP3, AAC, WAV, Ogg and usually FLAC. */
    override suspend fun decodeAudio(ref: String, onProgress: (Float) -> Unit, canceled: () -> Boolean): DecodedAudio {
        val bytes = runCatching { ref.toPath().readBytes() }.getOrElse {
            throw AudioFileException("That file could not be opened. It may have been removed.", it)
        }
        onProgress(0.1f)
        val samples = runCatching { jsDecode(bytes.toUint8Array(), DECODE_RATE).await<JsAny>() }.getOrElse {
            throw AudioFileException("This browser could not play that file. MP3, M4A and WAV usually work.", it)
        }
        val builder = PcmBuilder(maxFrames = DECODE_RATE * 60 * 15)
        val total = jsLength(samples)
        val chunk = FloatArray(16_384)
        var at = 0
        while (at < total) {
            if (canceled()) throw AudioFileException("Canceled.")
            val n = minOf(chunk.size, total - at)
            for (i in 0 until n) chunk[i] = jsFloatAt(samples, at + i).toFloat()
            builder.appendFloats(chunk, n, 1)
            at += n
            onProgress(0.1f + 0.9f * at / total)
            // Let the screen draw the progress between chunks.
            if (at % (chunk.size * 16) == 0) delay(1)
        }
        val pcm: Pcm = builder.build(DECODE_RATE)
        if (pcm.frames == 0) throw AudioFileException("That file decoded to silence — there is no audio to loop.")
        return DecodedAudio(pcm, Waveform.peaks(pcm.samples), builder.truncated)
    }

    /** Takes are shared as WAV in a browser; AAC encoding isn't everywhere yet. */
    override val canEncodeM4a: Boolean get() = false

    override fun encodeM4a(pcm: Pcm, out: Path) = error("M4A export is not available in a browser.")

    override fun countdownBeeper(): CountdownBeeper = object : CountdownBeeper {
        override fun beep(last: Boolean) = audioOutputs.playClip(beepPcm(if (last) 1320.0 else 880.0), BEEP_RATE, StreamKind.SONIFICATION)
        override fun release() {}
    }

    @Composable
    override fun OnAudioInputsChanged(onChange: (added: Boolean) -> Unit) {
        val latest by rememberUpdatedState(onChange)
        androidx.compose.runtime.DisposableEffect(Unit) {
            val stop = watchAudioInputs { latest(it) }
            onDispose { stop() }
        }
    }

    // ---- tabs ------------------------------------------------------------------

    override fun tabPlayer(memory: PlaybackMemory): TabPlayer = WebTabPlayer(memory)

    @Composable
    override fun TabScoreView(player: TabPlayer, source: TabSource, modifier: Modifier) =
        WebScoreView(player as WebTabPlayer, source, modifier)

    // ---- files -----------------------------------------------------------------

    override val filesDir: Path = WebFiles.FILES
    override val cacheDir: Path = WebFiles.CACHE

    override fun openAsset(path: String): Source = appFileSystem.source(WebFiles.ASSETS / path)

    /**
     * A picked file is read in whole and kept in the app's storage, because a
     * browser can't open it again later by name. One the player wants kept
     * (a looper recording) goes with the app's files; any other into the cache.
     */
    @Composable
    override fun rememberOpenFile(mimeTypes: List<String>, keepAccess: Boolean, onPicked: (PickedFile?) -> Unit): () -> Unit {
        val latest by rememberUpdatedState(onPicked)
        return remember(mimeTypes, keepAccess) {
            {
                jsPick(acceptFor(mimeTypes)) { name, bytes ->
                    latest(if (name == null || bytes == null) null else store(name, bytes.toByteArray(), keepAccess))
                }
            }
        }
    }

    private fun store(name: String, bytes: ByteArray, keep: Boolean): PickedFile {
        val dir = (if (keep) filesDir / "picked" else cacheDir / "picked").mkdirs()
        val target = dir / "${Random.nextLong().toULong()}-${name.filter { it.isLetterOrDigit() || it in "._- " }.takeLast(80)}"
        target.writeBytes(bytes)
        return PickedFile(target.toString(), name, bytes.size.toLong())
    }

    private val drops = MutableSharedFlow<PickedFile>(extraBufferCapacity = 4)
    override val droppedFiles: Flow<PickedFile> get() = drops

    init {
        jsAcceptDrops { name, bytes -> drops.tryEmit(store(name, bytes.toByteArray(), keep = false)) }
        jsCheckMic()
    }

    override fun openInput(ref: String): Source = appFileSystem.source(ref.toPath())

    /** A kept copy is the app's own, so it goes once nothing refers to it. */
    override fun releaseAccess(ref: String) {
        val path = ref.toPath()
        if (path.toString().startsWith("${filesDir / "picked"}/")) path.delete()
    }

    override fun refOf(file: Path): String = file.toString()

    /** Written in memory, then saved as a download under [suggestedName]. */
    @Composable
    override fun rememberCreateFile(mimeType: String, write: suspend (BufferedSink) -> Unit): (suggestedName: String) -> Unit {
        val latest by rememberUpdatedState(write)
        return { name ->
            scope.launch {
                val buffer = Buffer()
                latest(buffer)
                jsSave(name, mimeType, buffer.readByteArray().toUint8Array())
            }
        }
    }

    @Composable
    override fun rememberSaveCopy(extension: String, mimeType: String, onResult: (String) -> Unit): (Path) -> Unit {
        val report by rememberUpdatedState(onResult)
        return { file ->
            runCatching { jsSave(file.name, mimeType, file.readBytes().toUint8Array()) }
                .fold({ report("Saved ${file.name} to your downloads.") }, { report("Couldn't save: ${it.message}") })
        }
    }

    override fun shareFile(file: Path, mimeType: String, title: String) {
        scope.launch {
            runCatching { jsShare(file.name, mimeType, file.readBytes().toUint8Array(), title).await<JsString>() }
                .onFailure { AppLog.w(TAG, "share failed", it) }
        }
    }

    // ---- permissions -----------------------------------------------------------

    override fun hasPermission(permission: Permission): Boolean = when (permission) {
        Permission.MICROPHONE -> jsMicGranted()
        Permission.NOTIFICATIONS -> jsNotifyGranted()
    }

    @Composable
    override fun rememberPermissionRequest(permission: Permission, onResult: (granted: Boolean) -> Unit): () -> Unit {
        val latest by rememberUpdatedState(onResult)
        return {
            scope.launch {
                val granted = when (permission) {
                    Permission.MICROPHONE -> jsAskMic()
                    Permission.NOTIFICATIONS -> jsAskNotify()
                }.await<JsBoolean>().toBoolean()
                latest(granted)
            }
        }
    }

    // ---- reminders -------------------------------------------------------------

    private var reminderJob: Job? = null

    /**
     * A web page can't be woken by the system, so the reminder is a timer
     * while the app is open, shown as a notice if the browser allows them.
     * Closed, it reminds no one — said plainly on the reminder settings.
     */
    override fun scheduleReminder(settings: ReminderSettings): Instant? {
        reminderJob?.cancel()
        val next = ReminderSchedule.nextFire(settings, Clock.System.now(), ReminderSchedule.zone()) ?: return null
        reminderJob = scope.launch {
            delay((next - Clock.System.now()).coerceAtLeast(Duration.ZERO))
            ReminderStore.dueNotices(services.repo, services.sessions).forEach { jsNotify(it.title, it.text) }
            scheduleReminder(ReminderStore.load(services.repo))
        }
        return next
    }

    override val reminderNote: String
        get() = "Reminders appear while Riff Buddy is open in this browser, as a notice if you allow them. " +
            "To be reminded with it closed, add the reminder to your calendar."

    override val canExportCalendar: Boolean get() = true

    /** Saved as an .ics file, which a phone or computer offers to add to its calendar. */
    override fun exportCalendar(ics: String) =
        jsSave(ReminderCalendar.FILE_NAME, "text/calendar", ics.encodeToByteArray().toUint8Array())

    override suspend fun rearmReminders() {
        scheduleReminder(ReminderStore.load(services.repo))
    }

    // ---- the window and the system ---------------------------------------------

    override fun openLink(url: String) = jsOpen(url)

    /** A web page can't put itself away; Back at the top simply goes where the browser goes. */
    override fun leaveApp() {}

    private class BackEntry(var enabled: Boolean, var onBack: () -> Unit)
    private val backHandlers = mutableStateListOf<BackEntry>()

    init {
        jsArmBack {
            val entry = backHandlers.lastOrNull { it.enabled }
            entry?.onBack?.invoke()
            entry != null
        }
    }

    /** The browser's Back, and a phone's Back gesture, go to the most recent enabled handler, as on Android. */
    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        val latest by rememberUpdatedState(onBack)
        val entry = remember { BackEntry(enabled) { latest() } }
        entry.enabled = enabled
        DisposableEffect(entry) {
            backHandlers += entry
            onDispose { backHandlers -= entry }
        }
    }

    @Composable
    override fun KeepScreenOn(on: Boolean) {
        LaunchedEffect(on) { jsKeepOn(on) }
        DisposableEffect(Unit) { onDispose { jsKeepOn(false) } }
    }

    @Composable
    override fun appIcon(): Painter = remember {
        BitmapPainter(Image.makeFromEncoded(openAsset("icons/foreground.png").readBytes()).toComposeImageBitmap())
    }

    private fun acceptFor(mimeTypes: List<String>): String =
        mimeTypes.filter { it != "*/*" }.flatMap { type ->
            when (type) {
                "audio/*" -> listOf("audio/*", ".m4a", ".mp3", ".wav", ".ogg", ".flac")
                else -> listOf(type)
            }
        }.joinToString(",")

    private companion object {
        const val TAG = "RiffWeb"
        /** The looper's rate in a browser; every file is decoded to it. */
        const val DECODE_RATE = 48_000
        const val BEEP_RATE = 48_000

        fun beepPcm(frequency: Double): ShortArray {
            val frames = BEEP_RATE * 120 / 1000
            val fade = BEEP_RATE / 200
            return ShortArray(frames) { i ->
                val envelope = minOf(1.0, i.toDouble() / fade, (frames - i).toDouble() / fade)
                (sin(2 * PI * frequency * i / BEEP_RATE) * envelope * 0.4 * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }
}

private fun jsDecode(bytes: JsAny, rate: Int): Promise<JsAny> = js("rbPage.decode(bytes, rate)")
private fun jsLength(a: JsAny): Int = js("a.length")
private fun jsFloatAt(a: JsAny, i: Int): Double = js("a[i]")
private fun jsPick(accept: String, onPicked: (String?, JsAny?) -> Unit): Unit = js("rbPage.pick(accept, onPicked)")
private fun jsAcceptDrops(onDropped: (String, JsAny) -> Unit): Unit = js("rbPage.acceptDrops(onDropped)")
private fun jsSave(name: String, type: String, bytes: JsAny): Unit = js("rbPage.save(name, type, bytes)")
private fun jsShare(name: String, type: String, bytes: JsAny, title: String): Promise<JsString> = js("rbPage.share(name, type, bytes, title)")
private fun jsOpen(url: String): Unit = js("rbPage.open(url)")
private fun jsArmBack(handler: () -> Boolean): Unit = js("rbPage.armBack(handler)")
private fun jsKeepOn(on: Boolean): Unit = js("{ rbPage.keepOn(on); }")
private fun jsCheckMic(): Unit = js("{ rbPage.checkMic(); }")
private fun jsMicGranted(): Boolean = js("rbPage.micGranted")
private fun jsNotifyGranted(): Boolean = js("rbPage.notifyGranted()")
private fun jsAskMic(): Promise<JsBoolean> = js("rbPage.askMic()")
private fun jsAskNotify(): Promise<JsBoolean> = js("rbPage.askNotify()")
private fun jsNotify(title: String, text: String): Unit = js("rbPage.notify(title, text)")
