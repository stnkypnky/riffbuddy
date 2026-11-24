package com.riffbuddy.platform

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.riffbuddy.R
import com.riffbuddy.RiffBuddyApp
import com.riffbuddy.audio.AacEncoder
import com.riffbuddy.audio.AudioDecoder
import com.riffbuddy.audio.AudioOutputs
import com.riffbuddy.audio.DecodedAudio
import com.riffbuddy.audio.Pcm
import com.riffbuddy.domain.ReminderSettings
import com.riffbuddy.domain.Songsterr
import com.riffbuddy.reminders.Reminders
import com.riffbuddy.tabs.AlphaTabPlayer
import com.riffbuddy.tabs.PlaybackMemory
import com.riffbuddy.tabs.TabPlayer
import com.riffbuddy.tabs.TabSource
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
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
 * The shared [Platform] on Android: what screens reach through
 * [LocalPlatform]. Made by [com.riffbuddy.MainActivity], because
 * some of it — leaving the app, the system Back, the file pickers — belongs
 * to the activity. Every member here is the Android code that used to sit in
 * the screen that needed it, moved rather than rewritten.
 */
class AndroidPlatform(private val activity: ComponentActivity, private val app: RiffBuddyApp) : Platform {

    private val context: Context get() = activity

    // ---- sound -----------------------------------------------------------------

    override val audioOutputs: AudioOutputs get() = app.services.audioOutputs

    override suspend fun decodeAudio(ref: String, onProgress: (Float) -> Unit, canceled: () -> Boolean): DecodedAudio =
        AudioDecoder.decode(context, Uri.parse(ref), onProgress, canceled)

    override val canEncodeM4a: Boolean get() = true

    override fun encodeM4a(pcm: Pcm, out: Path) = AacEncoder.encode(pcm, out.toFile())

    override fun countdownBeeper(): CountdownBeeper? {
        val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() ?: return null
        return object : CountdownBeeper {
            override fun beep(last: Boolean) {
                tones.startTone(if (last) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 120)
            }
            override fun release() = tones.release()
        }
    }

    @Composable
    override fun OnAudioInputsChanged(onChange: (added: Boolean) -> Unit) {
        val latest by rememberUpdatedState(onChange)
        DisposableEffect(Unit) {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = latest(true)
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = latest(false)
            }
            manager.registerAudioDeviceCallback(callback, null)
            onDispose { manager.unregisterAudioDeviceCallback(callback) }
        }
    }

    // ---- tabs ------------------------------------------------------------------

    /** alphaTab's Android view, behind the shared player interface. */
    override fun tabPlayer(memory: PlaybackMemory): TabPlayer = AlphaTabPlayer(memory)

    @Composable
    override fun TabScoreView(player: TabPlayer, source: TabSource, modifier: Modifier) =
        com.riffbuddy.tabs.TabScoreView(player as AlphaTabPlayer, source, modifier)

    // ---- files -----------------------------------------------------------------

    override val filesDir: Path get() = app.filesDir.toOkioPath()
    override val cacheDir: Path get() = app.cacheDir.toOkioPath()

    override fun openAsset(path: String): Source = context.assets.open(path).source()

    @Composable
    override fun rememberOpenFile(mimeTypes: List<String>, keepAccess: Boolean, onPicked: (PickedFile?) -> Unit): () -> Unit {
        val latest by rememberUpdatedState(onPicked)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            latest(uri?.let { picked(it, keepAccess) })
        }
        return { launcher.launch(mimeTypes.toTypedArray()) }
    }

    /**
     * Reads a picked file's name and size, taking a lasting read grant first
     * when asked. Without the persisted grant, the permission the picker gives
     * lasts only as long as this app's process — a looper recording would
     * "move" every time Android cleared the app from memory.
     */
    private fun picked(uri: Uri, keepAccess: Boolean): PickedFile {
        if (keepAccess) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        if (!c.isNull(0)) name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        }
        return PickedFile(uri.toString(), name, size)
    }

    override fun openInput(ref: String): Source =
        (context.contentResolver.openInputStream(Uri.parse(ref)) ?: error("That file could not be opened.")).source()

    /** Gives the grant back, so Android's small quota of persisted grants is not used up. */
    override fun releaseAccess(ref: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(ref), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun refOf(file: Path): String = Uri.fromFile(file.toFile()).toString()

    @Composable
    override fun rememberCreateFile(mimeType: String, write: suspend (BufferedSink) -> Unit): (String) -> Unit {
        val scope = rememberCoroutineScope()
        val latest by rememberUpdatedState(write)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
            if (uri != null) scope.launch {
                context.contentResolver.openOutputStream(uri)?.sink()?.buffer()?.use { latest(it) }
            }
        }
        return { name -> launcher.launch(name) }
    }

    @Composable
    override fun rememberSaveCopy(extension: String, mimeType: String, onResult: (String) -> Unit): (Path) -> Unit {
        val scope = rememberCoroutineScope()
        val report by rememberUpdatedState(onResult)
        // A path, saveable: the save dialog is another app, and Android may rebuild
        // the screen while it is open. A remembered File would come back empty.
        var pending by rememberSaveable { mutableStateOf<String?>(null) }
        // The type the *device* files .m4a under. Android 9 (the Fire) says
        // audio/mpeg, newer ones audio/mp4. Asking for a type the device doesn't
        // associate with .m4a made the save dialog treat ".m4a" as part of the
        // name, and a second save came out as "Take.m4a (1)", which won't open.
        val type = remember(extension, mimeType) {
            if (extension == "m4a") android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension("m4a") ?: mimeType
            else mimeType
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(type)) { uri ->
            val file = pending?.let(::File)
            pending = null
            if (uri != null && file != null) scope.launch {
                report(
                    runCatching {
                        withContext(Dispatchers.IO) {
                            // "wt", not the default "w": saving over an existing file of the
                            // same name, a longer one, must not leave its tail on the end.
                            context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                        }
                    }.fold({ "Saved ${file.name}." }, { "Couldn't save: ${it.message}" })
                )
            }
        }
        return { file -> pending = file.toFile().path; launcher.launch(file.name) }
    }

    /**
     * Shared through the takes FileProvider, which exposes only the cache's
     * `exports/` folder — where exported takes are written.
     */
    override fun shareFile(file: Path, mimeType: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.takes", file.toFile())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share \"$title\""))
    }

    // ---- permissions -----------------------------------------------------------

    private fun manifestName(permission: Permission): String? = when (permission) {
        Permission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
        // Before Android 13 notifications need no permission at all.
        Permission.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
    }

    override fun hasPermission(permission: Permission): Boolean {
        val name = manifestName(permission) ?: return true
        return ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    }

    @Composable
    override fun rememberPermissionRequest(permission: Permission, onResult: (Boolean) -> Unit): () -> Unit {
        val name = manifestName(permission)
        val latest by rememberUpdatedState(onResult)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { latest(it) }
        return { if (name != null) launcher.launch(name) else latest(true) }
    }

    // ---- reminders -------------------------------------------------------------

    override fun scheduleReminder(settings: ReminderSettings): Instant? = Reminders.schedule(context, settings)

    override suspend fun rearmReminders() = Reminders.rearm(context)

    // ---- the window and the system ---------------------------------------------

    /**
     * Still link-out only — a VIEW intent and nothing else. No WebView, no
     * injected script, no automation.
     *
     * But the Songsterr app is preferred when installed, because that is where
     * The player's subscription is signed in. Naming the package is what makes that
     * happen: the app claims songsterr.com as a verified link, yet an
     * unqualified VIEW intent still ends up in a chooser or a browser.
     */
    override fun openLink(url: String) {
        val uri = url.toUri()
        val direct = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(Songsterr.ANDROID_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val anywhere = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(direct) }
            .recoverCatching { activity.startActivity(anywhere) }
            .onFailure { if (it !is ActivityNotFoundException) throw it }
    }

    override fun leaveApp() {
        activity.moveTaskToBack(true)
    }

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
        androidx.activity.compose.BackHandler(enabled, onBack)

    @Composable
    override fun KeepScreenOn(on: Boolean) {
        val view = LocalView.current
        DisposableEffect(on) {
            view.keepScreenOn = on
            onDispose { view.keepScreenOn = false }
        }
    }

    @Composable
    override fun appIcon(): Painter = painterResource(R.drawable.ic_launcher_foreground)

    // Android 12+ draws the animated splash icon at 240 dp; our own launch window, 220.
    override val launchIconDp: Int get() = if (android.os.Build.VERSION.SDK_INT >= 31) 240 else 220

    override val screenReaderOn: Boolean
        get() = (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager)
            ?.isTouchExplorationEnabled == true
}
