package com.riffbuddy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import com.riffbuddy.audio.AudioOutputs
import com.riffbuddy.audio.DecodedAudio
import com.riffbuddy.audio.Pcm
import com.riffbuddy.domain.ReminderSettings
import com.riffbuddy.tabs.PlaybackMemory
import com.riffbuddy.tabs.TabPlayer
import com.riffbuddy.tabs.TabSource
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.ZonedDateTime

/**
 * What screens need from the platform they run on, beyond the repositories
 * and engines the app hands the shell. Provided once, at the root of the UI,
 * through [LocalPlatform], so a screen deep in the tree can reach it without
 * every function in between passing it along.
 */
interface Platform {

    // ---- sound -----------------------------------------------------------------

    val audioOutputs: AudioOutputs

    /**
     * Decodes a recording into memory for the looper. [ref] is what the file
     * picker returned. Throws [com.riffbuddy.audio.AudioFileException] with a
     * message fit to show.
     */
    fun decodeAudio(ref: String, onProgress: (Float) -> Unit, canceled: () -> Boolean): DecodedAudio

    /** Whether takes can leave the app as M4A (AAC) here, not only as WAV. */
    val canEncodeM4a: Boolean

    /** Writes [pcm] to [out] as M4A. Only called when [canEncodeM4a]. */
    fun encodeM4a(pcm: Pcm, out: File)

    /** The beeps before a take starts; null if the platform has no way to make them. */
    fun countdownBeeper(): CountdownBeeper?

    /**
     * Calls [onChange] when an audio input appears ([added] true) or goes —
     * an amp plugged in or pulled out — while this is on screen.
     */
    @Composable
    fun OnAudioInputsChanged(onChange: (added: Boolean) -> Unit)

    // ---- tabs ------------------------------------------------------------------

    /** A tab player for one file, starting from what was remembered about it. */
    fun tabPlayer(memory: PlaybackMemory): TabPlayer

    /** The score itself, drawn and played by [player], which this platform made. */
    @Composable
    fun TabScoreView(player: TabPlayer, source: TabSource, modifier: Modifier)

    // ---- files -----------------------------------------------------------------

    /** The app's own storage, private to it. */
    val filesDir: File

    /** Somewhere for throwaway files, such as a calibration recording. */
    val cacheDir: File

    /** A file shipped inside the app, such as a sample recording or tab. */
    fun openAsset(path: String): InputStream

    /**
     * A launcher for the open-file picker. [keepAccess] asks for the file to
     * stay readable after the app restarts — needed for a recording the looper
     * refers to rather than copies. [onPicked] gets null if nothing was chosen.
     */
    @Composable
    fun rememberOpenFile(mimeTypes: List<String>, keepAccess: Boolean, onPicked: (PickedFile?) -> Unit): () -> Unit

    /**
     * Files dragged onto the app from outside, one at a time, as if picked.
     * A phone has nothing to drag from, so by default none ever arrive.
     */
    val droppedFiles: kotlinx.coroutines.flow.Flow<PickedFile> get() = kotlinx.coroutines.flow.emptyFlow()

    /** Reads a picked file. */
    fun openInput(ref: String): InputStream

    /** Gives back the lasting access [rememberOpenFile] took, once nothing refers to the file. */
    fun releaseAccess(ref: String)

    /** The reference a file in the app's own storage is known by, as a picked file would be. */
    fun refOf(file: File): String

    /**
     * A launcher for the save-file picker, suggesting a name. [write] fills
     * the file the person chose; nothing happens if they cancel.
     */
    @Composable
    fun rememberCreateFile(mimeType: String, write: suspend (OutputStream) -> Unit): (suggestedName: String) -> Unit

    /**
     * A launcher that saves a copy of an existing file where the person
     * chooses, named after it. [onResult] gets a sentence to show.
     */
    @Composable
    fun rememberSaveCopy(extension: String, mimeType: String, onResult: (String) -> Unit): (File) -> Unit

    /** Hands [file] to another app, or shows it in the file manager where there is no sharing. */
    fun shareFile(file: File, mimeType: String, title: String)

    // ---- permissions -----------------------------------------------------------

    /**
     * Whether [permission] is held now. Always true where the platform has no
     * such permission. Asked when needed rather than remembered, so one
     * changed in the system's settings is seen.
     */
    fun hasPermission(permission: Permission): Boolean

    /** A launcher that asks for [permission]; [onResult] hears the answer. */
    @Composable
    fun rememberPermissionRequest(permission: Permission, onResult: (granted: Boolean) -> Unit): () -> Unit

    // ---- reminders -------------------------------------------------------------

    /** Schedules the next practice reminder, or cancels it when there is none. Returns when it will fire. */
    fun scheduleReminder(settings: ReminderSettings): ZonedDateTime?

    /** Puts the reminder back from saved settings, in case the system dropped it. */
    suspend fun rearmReminders()

    /** Anything the reminder settings should say about how reminders work here; null for nothing. */
    val reminderNote: String? get() = null

    // ---- the window and the system ---------------------------------------------

    /**
     * Opens a web link outside the app. Links are only ever handed over, never
     * shown inside the app: Songsterr in particular is link-out only, and on a
     * platform where its own app is installed, that app is preferred.
     */
    fun openLink(url: String)

    /** Leaves the app without ending it, as going to the home screen does. */
    fun leaveApp()

    /** Catches the system's Back while [enabled]; a platform without one does nothing. */
    @Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit)

    /** Keeps the display awake while [on] and this is on screen. */
    @Composable
    fun KeepScreenOn(on: Boolean)

    /** The app's icon artwork (the guitar, without a background), for welcome screens. */
    @Composable
    fun appIcon(): Painter

    /**
     * The size the system's own launch screen drew the icon at, so the opening
     * animation starts from exactly what was already showing.
     */
    val launchIconDp: Int get() = 220

    /** A screen reader is on: a silent animation would only be a delay. */
    val screenReaderOn: Boolean get() = false
}

/**
 * A file the person chose. [ref] is how this platform finds it again — a
 * content URI on Android, a path on the desktop — and is what gets stored.
 * [name] and [size] are null when the platform could not tell.
 */
class PickedFile(val ref: String, val name: String?, val size: Long?)

enum class Permission { MICROPHONE, NOTIFICATIONS }

/** Beeps for a countdown: an ordinary one each second, a higher one for the last. */
interface CountdownBeeper {
    fun beep(last: Boolean)
    fun release()
}

val LocalPlatform = staticCompositionLocalOf<Platform> { error("No Platform provided at the root of the UI") }
