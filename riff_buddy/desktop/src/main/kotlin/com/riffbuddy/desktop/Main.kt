package com.riffbuddy.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.riffbuddy.AppServices
import com.riffbuddy.ai.OnlineCheck
import com.riffbuddy.audio.MetronomeEngine
import com.riffbuddy.audio.MetronomeSettings
import com.riffbuddy.audio.TapTempo
import com.riffbuddy.platform.LocalPlatform
import com.riffbuddy.ui.Intro
import com.riffbuddy.ui.RiffBuddyContent
import com.riffbuddy.ui.theme.RiffBuddyTheme
import java.awt.Dimension
import java.io.File
import java.util.prefs.Preferences

/** Riff Buddy on a desktop computer: the same app as on Android, in a window. */
fun main() {
    val dataDir = DesktopPaths.data.apply { mkdirs() }
    val cacheDir = DesktopPaths.cache.apply { mkdirs() }
    AppIdentity.apply()
    LinuxFonts.prepare(cacheDir)
    LinuxFonts.warmUp()
    val services = AppServices(
        db = JdbcDb(File(dataDir, "riffbuddy.db")),
        audioOutputs = DesktopAudioOutputs(),
        audioInputs = DesktopAudioInputs(),
        keys = DesktopSecureStore(),
        // A request with no connection fails fast on a desktop and says so;
        // there is no captive-portal case worth checking for ahead of it.
        isOnline = OnlineCheck { true },
        filesDir = dataDir,
        cacheDir = cacheDir,
        appVersion = System.getProperty("riffbuddy.version") ?: "desktop",
    )
    val platform = DesktopPlatform(services, filesDir = dataDir, cacheDir = cacheDir)
    val prefs = Preferences.userRoot().node("riffbuddy/window")
    val reducedMotion = SystemMotion.reduced()

    application {
        val state = rememberWindowState(
            size = DpSize(prefs.getInt("width", 1100).dp, prefs.getInt("height", 820).dp),
            // Centered, over where the splash was, so the opening animation
            // carries on from it in place.
            position = WindowPosition.Aligned(Alignment.Center),
        )
        val tapper = remember { TapTempo() }
        Window(
            onCloseRequest = {
                prefs.putInt("width", state.size.width.value.toInt())
                prefs.putInt("height", state.size.height.value.toInt())
                services.stopAll()
                exitApplication()
            },
            state = state,
            title = "Riff Buddy",
            icon = AppIdentity.windowIcon(),
            // Keys a focused control did not use. A text field keeps its own
            // typing, so Space in the song search never starts the metronome.
            onKeyEvent = { event -> shortcut(event, services.metronome, tapper, platform) },
        ) {
            LaunchedEffect(window) {
                platform.window = window
                platform.acceptDrops(window)
                window.iconImages = AppIdentity.windowIconImages()
                // Narrower than a phone held upright is narrower than any layout here.
                window.minimumSize = Dimension(420, 560)
            }
            RiffBuddyTheme(condensedFont = Condensed, systemReducedMotion = reducedMotion) {
                CompositionLocalProvider(LocalPlatform provides platform) {
                    RiffBuddyContent(services, overlay = { Intro() })
                }
            }
        }
    }
}

/**
 * Space starts and stops the metronome, T taps the tempo, the arrow keys move
 * it by one (by five with Shift), and Escape does what Back does on a phone.
 */
private fun shortcut(event: KeyEvent, metronome: MetronomeEngine, tapper: TapTempo, platform: DesktopPlatform): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val settings: MetronomeSettings = metronome.state.value.settings
    val step = if (event.isShiftPressed) 5 else 1
    when (event.key) {
        Key.Spacebar -> if (metronome.state.value.isRunning) metronome.stop() else metronome.start(settings)
        Key.T -> tapper.tap(System.currentTimeMillis())?.let { metronome.setBpm(it) }
        Key.DirectionUp, Key.DirectionRight -> metronome.setBpm(settings.bpm + step)
        Key.DirectionDown, Key.DirectionLeft -> metronome.setBpm(settings.bpm - step)
        Key.Escape -> return platform.handleBack()
        else -> return false
    }
    return true
}

/**
 * Roboto Condensed for the big numerals — the face Android phones use for
 * them — bundled so the tempo reads the same on every computer.
 */
private val Condensed = FontFamily(
    Font("fonts/RobotoCondensed.ttf", FontWeight.Normal),
    Font("fonts/RobotoCondensed.ttf", FontWeight.Medium),
)

/** Where each system keeps an app's data, so it lives where people and backup tools expect. */
object DesktopPaths {
    private val os = System.getProperty("os.name").lowercase()
    private val home = File(System.getProperty("user.home"))

    val data: File = when {
        "win" in os -> File(System.getenv("APPDATA") ?: home.path, "RiffBuddy")
        "mac" in os -> File(home, "Library/Application Support/RiffBuddy")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "riffbuddy")
    }

    val cache: File = when {
        "win" in os -> File(System.getenv("LOCALAPPDATA") ?: home.path, "RiffBuddy/cache")
        "mac" in os -> File(home, "Library/Caches/RiffBuddy")
        else -> File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", "riffbuddy")
    }
}
