package com.riffbuddy.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import com.riffbuddy.AppServices
import com.riffbuddy.audio.AudioFileException
import com.riffbuddy.audio.AudioOutputs
import com.riffbuddy.audio.DecodedAudio
import com.riffbuddy.audio.Pcm
import com.riffbuddy.audio.PcmBuilder
import com.riffbuddy.audio.StreamKind
import com.riffbuddy.audio.Waveform
import com.riffbuddy.data.BackupFormat
import com.riffbuddy.data.ReminderStore
import com.riffbuddy.desktop.tabs.DesktopScoreView
import com.riffbuddy.domain.ReminderSchedule
import com.riffbuddy.domain.ReminderSettings
import com.riffbuddy.platform.AppLog
import com.riffbuddy.platform.CountdownBeeper
import com.riffbuddy.platform.Permission
import com.riffbuddy.platform.PickedFile
import com.riffbuddy.platform.Platform
import com.riffbuddy.tabs.AlphaTabPlayer
import com.riffbuddy.tabs.PlaybackMemory
import com.riffbuddy.tabs.TabPlayer
import com.riffbuddy.tabs.TabSource
import fr.delthas.javamp3.Sound
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.buffer
import okio.sink
import okio.source

/**
 * The shared [Platform] on a desktop computer. Where a phone idea has no
 * desktop equivalent, the nearest honest one stands in: Escape for the Back
 * button, "show in folder" for sharing, a tray notice while the app is open
 * for a scheduled reminder.
 */
class DesktopPlatform(
    private val services: AppServices,
    private val dataFolder: File,
    private val cacheFolder: File,
) : Platform {

    override val filesDir: Path = dataFolder.toOkioPath()
    override val cacheDir: Path = cacheFolder.toOkioPath()

    /** Set once the window exists; file dialogs and minimizing need it. */
    var window: ComposeWindow? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Help and hints name the device; here it's a computer with a microphone, not a phone.
        com.riffbuddy.domain.Device.onComputer = true
    }

    // ---- files dropped on the window ---------------------------------------------

    private val drops = MutableSharedFlow<PickedFile>(extraBufferCapacity = 16)
    override val droppedFiles: Flow<PickedFile> get() = drops

    /** Opens [file] as if it had been dropped on the window. */
    fun receive(file: File) {
        when {
            file.isFile -> drops.tryEmit(PickedFile(file.absolutePath, file.name, file.length()))
            // A backup is a zip inside, and some file managers unpack it on a
            // double-click. The unpacked folder is still the whole backup, so
            // it's packed back up and imported as if the file itself was dropped.
            File(file, BackupFormat.ENTRY_NAME).isFile -> scope.launch {
                val packed = runCatching { repack(file) }.getOrElse {
                    AppLog.w("RiffDrop", "could not repack ${file.name}: ${it.message}")
                    return@launch
                }
                drops.emit(PickedFile(packed.absolutePath, packed.name, packed.length()))
            }
            // Anything else is passed on, so the app can say it can't open it.
            else -> drops.tryEmit(PickedFile(file.absolutePath, file.name, null))
        }
    }

    private fun repack(folder: File): File {
        val out = File(cacheFolder, "${folder.name}.${BackupFormat.FILE_EXTENSION}")
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
            folder.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(folder).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    /** Takes files dragged onto [window] from a file manager or the desktop. */
    fun acceptDrops(window: ComposeWindow) {
        window.contentPane.dropTarget = object : DropTarget() {
            override fun drop(event: DropTargetDropEvent) {
                event.acceptDrop(DnDConstants.ACTION_COPY)
                val files = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                }.getOrElse {
                    AppLog.w("RiffDrop", "drop held no files: ${it.message}")
                    emptyList()
                }
                files.forEach(::receive)
                event.dropComplete(files.isNotEmpty())
            }
        }
    }

    // ---- sound -----------------------------------------------------------------

    override val audioOutputs: AudioOutputs get() = services.audioOutputs

    /**
     * WAV and AIFF through Java's own decoders, MP3 through a small pure-Java
     * one. Everything is read into memory, mixed to mono and kept at the file's
     * own rate — what the looper needs to reach any moment instantly.
     */
    override suspend fun decodeAudio(ref: String, onProgress: (Float) -> Unit, canceled: () -> Boolean): DecodedAudio {
        val file = File(ref)
        if (!file.isFile) throw AudioFileException("That file could not be opened. It may have been moved or deleted.")
        // Progress follows the file read, not the samples made: a compressed
        // file makes many times its own size in samples.
        val counted = CountingStream(file.inputStream().buffered(64 * 1024))
        val opened = runCatching { openPcm16(file, counted) }.getOrElse {
            counted.close()
            throw AudioFileException(
                "This computer can open WAV, AIFF and MP3 files. Save the song in one of those and load that.", it,
            )
        }
        opened.stream.use { pcm16 ->
            val rate = opened.rate
            val maxMinutes = 15
            val builder = PcmBuilder(maxFrames = rate * 60 * maxMinutes)
            val total = file.length().coerceAtLeast(1)
            val bytes = ByteArray(64 * 1024)
            val shorts = ShortArray(bytes.size / 2)
            var carry = -1
            while (true) {
                if (canceled()) throw AudioFileException("Canceled.")
                var n = pcm16.read(bytes, if (carry >= 0) 1 else 0, bytes.size - 1)
                if (n <= 0) break
                // A read can end halfway through a sample; keep the odd byte for the next one.
                if (carry >= 0) { bytes[0] = carry.toByte(); n++; carry = -1 }
                if (n % 2 == 1) carry = bytes[n - 1].toInt() and 0xff
                val count = n / 2
                for (i in 0 until count) {
                    val lo = bytes[2 * i + if (opened.bigEndian) 1 else 0].toInt() and 0xff
                    val hi = bytes[2 * i + if (opened.bigEndian) 0 else 1].toInt()
                    shorts[i] = (lo or (hi shl 8)).toShort()
                }
                builder.appendShorts(shorts, count, opened.channels)
                onProgress((counted.count.toFloat() / total).coerceAtMost(1f))
            }
            val pcm: Pcm = builder.build(rate)
            if (pcm.frames == 0) throw AudioFileException("That file decoded to silence — there is no audio to loop.")
            return DecodedAudio(pcm, Waveform.peaks(pcm.samples), builder.truncated)
        }
    }

    private class Pcm16(val stream: InputStream, val rate: Int, val channels: Int, val bigEndian: Boolean)

    /** A 16-bit PCM stream from [file], whatever its format, or an exception if it is not one this can read. */
    private fun openPcm16(file: File, input: InputStream): Pcm16 {
        if (file.extension.equals("mp3", ignoreCase = true)) {
            val sound = Sound(input)
            val format = sound.audioFormat
            return Pcm16(sound, sound.samplingFrequency, format.channels, format.isBigEndian)
        }
        val source = AudioSystem.getAudioInputStream(input)
        val src = source.format
        val pcm = if (src.encoding == AudioFormat.Encoding.PCM_SIGNED && src.sampleSizeInBits == 16) source
        else AudioSystem.getAudioInputStream(AudioFormat(src.sampleRate, 16, src.channels, true, false), source)
        return Pcm16(pcm, src.sampleRate.toInt(), src.channels, pcm.format.isBigEndian)
    }

    private class CountingStream(input: InputStream) : java.io.FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    /** No AAC encoder ships with Java; takes leave as WAV, which plays everywhere. */
    override val canEncodeM4a: Boolean get() = false

    override fun encodeM4a(pcm: Pcm, out: Path) = error("M4A export is not available on this computer.")

    /** Two short sine beeps, the last one higher — the same shape Android's tone generator plays. */
    override fun countdownBeeper(): CountdownBeeper = object : CountdownBeeper {
        override fun beep(last: Boolean) = audioOutputs.playClip(beepPcm(if (last) 1320.0 else 880.0), BEEP_RATE, StreamKind.SONIFICATION)
        override fun release() {}
    }

    /** Desktop systems give no plug-in notice through Java's audio; the input list is read when shown. */
    @Composable
    override fun OnAudioInputsChanged(onChange: (added: Boolean) -> Unit) {}

    // ---- tabs ------------------------------------------------------------------

    override fun tabPlayer(memory: PlaybackMemory): TabPlayer = AlphaTabPlayer(memory)

    @Composable
    override fun TabScoreView(player: TabPlayer, source: TabSource, modifier: Modifier) =
        DesktopScoreView(player as AlphaTabPlayer, source, modifier)

    // ---- files -----------------------------------------------------------------

    /** Bundled files — the sample recordings and tabs — travel inside the app as resources. */
    override fun openAsset(path: String): Source =
        (javaClass.getResourceAsStream("/$path") ?: error("$path is missing from the app.")).source()

    @Composable
    override fun rememberOpenFile(mimeTypes: List<String>, keepAccess: Boolean, onPicked: (PickedFile?) -> Unit): () -> Unit {
        val latest by rememberUpdatedState(onPicked)
        return remember(mimeTypes) {
            {
                val file = chooseFile(FileDialog.LOAD, null, extensionsFor(mimeTypes))
                latest(file?.let { PickedFile(it.absolutePath, it.name, it.length()) })
            }
        }
    }

    override fun openInput(ref: String): Source = File(ref).source()

    /** A path stays readable as long as the file is there; there is no grant to give back. */
    override fun releaseAccess(ref: String) {}

    override fun refOf(file: Path): String = file.toFile().absolutePath

    @Composable
    override fun rememberCreateFile(mimeType: String, write: suspend (BufferedSink) -> Unit): (String) -> Unit {
        val scope = rememberCoroutineScope()
        val latest by rememberUpdatedState(write)
        return { name ->
            chooseFile(FileDialog.SAVE, name, emptyList())?.let { target ->
                scope.launch { withContext(Dispatchers.IO) { target.sink().buffer().use { latest(it) } } }
            }
        }
    }

    @Composable
    override fun rememberSaveCopy(extension: String, mimeType: String, onResult: (String) -> Unit): (Path) -> Unit {
        val scope = rememberCoroutineScope()
        val report by rememberUpdatedState(onResult)
        return { source ->
            chooseFile(FileDialog.SAVE, source.name, emptyList())?.let { target ->
                scope.launch {
                    report(
                        runCatching { withContext(Dispatchers.IO) { source.toFile().copyTo(target, overwrite = true) } }
                            .fold({ "Saved ${target.name}." }, { "Couldn't save: ${it.message}" })
                    )
                }
            }
        }
    }

    /**
     * A desktop has no share sheet. The file is shown in its folder instead,
     * from where it can be dragged into an email or a chat.
     */
    override fun shareFile(path: Path, mimeType: String, title: String) {
        val file = path.toFile()
        val desktop = Desktop.getDesktop()
        when {
            desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) -> desktop.browseFileDirectory(file)
            desktop.isSupported(Desktop.Action.OPEN) -> desktop.open(file.parentFile)
            else -> runCatching { ProcessBuilder("xdg-open", file.parent).start() }
        }
    }

    private fun chooseFile(mode: Int, suggested: String?, extensions: List<String>): File? {
        val dialog = FileDialog(window, if (mode == FileDialog.LOAD) "Open" else "Save", mode)
        if (suggested != null) dialog.file = suggested
        if (extensions.isNotEmpty()) {
            dialog.setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
        }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    }

    private fun extensionsFor(mimeTypes: List<String>): List<String> =
        if ("audio/*" in mimeTypes) listOf("wav", "wave", "aif", "aiff", "mp3") else emptyList()

    // ---- permissions -----------------------------------------------------------

    /**
     * Desktop apps hold no runtime permissions. macOS asks about the microphone
     * itself, the first time an input opens.
     */
    override fun hasPermission(permission: Permission): Boolean = true

    @Composable
    override fun rememberPermissionRequest(permission: Permission, onResult: (Boolean) -> Unit): () -> Unit {
        val latest by rememberUpdatedState(onResult)
        return { latest(true) }
    }

    // ---- reminders -------------------------------------------------------------

    private var reminderJob: Job? = null

    /**
     * A desktop app is not woken by the system the way Android wakes an app for
     * an alarm, so the reminder is a timer inside the running app, shown as a
     * notice from its tray icon. Closed, the app reminds no one — said plainly
     * on the reminder settings.
     */
    override fun scheduleReminder(settings: ReminderSettings): Instant? {
        reminderJob?.cancel()
        val next = ReminderSchedule.nextFire(settings, Clock.System.now(), ReminderSchedule.zone()) ?: return null
        reminderJob = scope.launch {
            delay((next - Clock.System.now()).coerceAtLeast(Duration.ZERO))
            ReminderStore.dueNotices(services.repo, services.sessions).forEach { notify(it.title, it.text) }
            scheduleReminder(ReminderStore.load(services.repo))
        }
        AppLog.i(TAG, "next reminder at $next")
        return next
    }

    override val reminderNote: String
        get() = "Reminders appear while Riff Buddy is open, from its icon in the system tray."

    override suspend fun rearmReminders() {
        scheduleReminder(ReminderStore.load(services.repo))
    }

    private val tray: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) return@lazy null
        runCatching {
            val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            TrayIcon(image, "Riff Buddy").also { it.isImageAutoSize = true; SystemTray.getSystemTray().add(it) }
        }.getOrNull()
    }

    private fun notify(title: String, text: String) {
        val icon = tray
        if (icon != null) icon.displayMessage(title, text, TrayIcon.MessageType.INFO)
        else AppLog.i(TAG, "reminder (no tray to show it): $title — $text")
    }

    // ---- the window and the system ---------------------------------------------

    /**
     * Link-out only, as on every platform: the default browser opens the page,
     * and nothing from it comes back into the app.
     */
    override fun openLink(url: String) {
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()
        if (desktop?.isSupported(Desktop.Action.BROWSE) == true) desktop.browse(URI(url))
        else runCatching { ProcessBuilder("xdg-open", url).start() }
    }

    override fun leaveApp() {
        window?.isMinimized = true
    }

    /**
     * Escape does what Back does on a phone. Handlers stack like Android's: the
     * most recently shown one that is enabled gets the key.
     */
    internal val backHandlers = mutableStateListOf<BackEntry>()

    internal class BackEntry(var enabled: Boolean, var onBack: () -> Unit)

    /** Called by the window on Escape; true if something took it. */
    fun handleBack(): Boolean {
        val entry = backHandlers.lastOrNull { it.enabled } ?: return false
        entry.onBack()
        return true
    }

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

    /** Screens on a desktop do not sleep mid-practice the way a phone's does. */
    @Composable
    override fun KeepScreenOn(on: Boolean) {}

    @Composable
    override fun appIcon(): Painter {
        val density = LocalDensity.current
        return remember(density) { loadSvgPainter(openAsset("icon/foreground.svg").buffer().inputStream(), density) }
    }

    private companion object {
        const val TAG = "RiffDesktop"
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
