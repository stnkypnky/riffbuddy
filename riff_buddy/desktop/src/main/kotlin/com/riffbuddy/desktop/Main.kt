package com.riffbuddy.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.riffbuddy.AppServices
import com.riffbuddy.ai.OnlineCheck
import com.riffbuddy.platform.LocalPlatform
import com.riffbuddy.sync.nearbySync
import com.riffbuddy.ui.Intro
import com.riffbuddy.ui.LocalShortcuts
import com.riffbuddy.ui.RiffBuddyContent
import com.riffbuddy.ui.Shortcut
import com.riffbuddy.ui.ShortcutRegistry
import com.riffbuddy.ui.settings.SyncHost
import com.riffbuddy.ui.settings.SyncSection
import com.riffbuddy.ui.theme.RiffBuddyTheme
import java.awt.Dimension
import java.io.File
import java.util.prefs.Preferences
import okio.Path.Companion.toOkioPath

/** Riff Buddy on a desktop computer: the same app as on Android, in a window. */
fun main() {
    // Skia draws with Direct3D on Windows by default, and on a fast 4K
    // display the window flickered on hover, on cards and whenever a dialog
    // opened; OpenGL drew it cleanly. Set here rather than as a launcher
    // flag so it holds however the app is started, before the first window,
    // and only when no renderer was asked for, so another can still be tried.
    if ("win" in System.getProperty("os.name").lowercase() && System.getProperty("skiko.renderApi") == null) {
        System.setProperty("skiko.renderApi", "OPENGL")
    }
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
        filesDir = dataDir.toOkioPath(),
        cacheDir = cacheDir.toOkioPath(),
        appVersion = System.getProperty("riffbuddy.version") ?: "desktop",
    )
    val sync = services.nearbySync(deviceName = computerName())
    val platform = DesktopPlatform(services, dataFolder = dataDir, cacheFolder = cacheDir)
    val prefs = Preferences.userRoot().node("riffbuddy/window")
    val reducedMotion = SystemMotion.reduced()

    application {
        val state = rememberWindowState(
            size = DpSize(prefs.getInt("width", 1100).dp, prefs.getInt("height", 820).dp),
            // Centered, over where the splash was, so the opening animation
            // carries on from it in place.
            position = WindowPosition.Aligned(Alignment.Center),
        )
        // The keyboard shortcuts are caught inside the content, not here: see
        // shortcutRoot. Escape does what Back does on a phone, at any depth.
        val shortcuts = remember { ShortcutRegistry(listOf(Shortcut("Esc", "Go back", listOf(Key.Escape)) { platform.handleBack() })) }
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
        ) {
            LaunchedEffect(window) {
                platform.window = window
                platform.acceptDrops(window)
                window.iconImages = AppIdentity.windowIconImages()
                // Narrower than a phone held upright is narrower than any layout here.
                window.minimumSize = Dimension(420, 560)
            }
            RiffBuddyTheme(condensedFont = AppFonts.condensed, textFont = AppFonts.text, systemReducedMotion = reducedMotion) {
                CompositionLocalProvider(LocalPlatform provides platform, LocalShortcuts provides shortcuts) {
                    RiffBuddyContent(
                        services,
                        syncSection = { SyncSection(sync) },
                        syncHost = { SyncHost(sync) },
                        dataChanges = { sync.dataVersion },
                        overlay = { Intro() },
                    )
                }
            }
        }
    }
}

/**
 * The bundled faces, the same files every edition ships, so the app reads the
 * same on every computer: Barlow Condensed for big numerals and titles, Inter
 * for text.
 */
object AppFonts {
    val condensed = FontFamily(
        Font("fonts/BarlowCondensed-Medium.ttf", FontWeight.Normal),
        Font("fonts/BarlowCondensed-Medium.ttf", FontWeight.Medium),
        Font("fonts/BarlowCondensed-SemiBold.ttf", FontWeight.SemiBold),
        Font("fonts/BarlowCondensed-Bold.ttf", FontWeight.Bold),
    )
    val text = FontFamily(
        Font("fonts/Inter-Regular.ttf", FontWeight.Normal),
        Font("fonts/Inter-Medium.ttf", FontWeight.Medium),
        Font("fonts/Inter-SemiBold.ttf", FontWeight.SemiBold),
        Font("fonts/Inter-SemiBold.ttf", FontWeight.Bold),
    )
}

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

/**
 * What other devices see this computer called until it is renamed: the
 * name Windows gives it, else the network name ("zorin-laptop"), without
 * the ".local" macOS adds.
 */
private fun computerName(): String =
    System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
        ?: runCatching { File("/etc/hostname").readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName.removeSuffix(".local") }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "This computer"
