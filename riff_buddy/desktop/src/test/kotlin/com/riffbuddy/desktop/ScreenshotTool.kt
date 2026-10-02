package com.riffbuddy.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import com.riffbuddy.AppServices
import com.riffbuddy.ai.OnlineCheck
import com.riffbuddy.platform.LocalPlatform
import com.riffbuddy.ui.RiffBuddyContent
import com.riffbuddy.ui.theme.RiffBuddyTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities

/**
 * Renders the real desktop app off-screen and saves what it shows, so the UI
 * can be checked where no screenshot tool is allowed (a Wayland session, a CI
 * runner). Not a test: run it by hand with a list of steps.
 *
 *   ScreenshotTool <out-dir> <width>x<height> step...
 *   steps:  shot:NAME   click:X,Y   wait:MS   drop:PATH (as if dragged onto the window)
 *
 * The app runs against a fresh database in <out-dir>/data, so nothing real is
 * touched.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    val (w, h) = args[1].split("x").map { it.toInt() }
    val data = File(out, "data").apply { mkdirs() }
    LinuxFonts.prepare(File(out, "cache").apply { mkdirs() })
    val services = AppServices(
        db = JdbcDb(File(data, "riffbuddy.db")),
        audioOutputs = DesktopAudioOutputs(),
        audioInputs = DesktopAudioInputs(),
        keys = DesktopSecureStore(),
        isOnline = OnlineCheck { true },
        filesDir = data,
        cacheDir = File(out, "cache").apply { mkdirs() },
        appVersion = "screenshot",
    )
    val platform = DesktopPlatform(services, data, File(out, "cache"))
    val condensed = FontFamily(
        Font("fonts/RobotoCondensed.ttf", FontWeight.Normal),
        Font("fonts/RobotoCondensed.ttf", FontWeight.Medium),
    )
    // Everything touching the scene runs on the window thread, as in the real
    // app: the tab player updates its state there.
    fun <T> onUi(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    val scene = onUi { ImageComposeScene(w, h, Density(1f)) {
        RiffBuddyTheme(condensedFont = condensed, systemReducedMotion = true) {
            CompositionLocalProvider(LocalPlatform provides platform) { RiffBuddyContent(services) }
        }
    } }
    val start = System.nanoTime()
    fun settle(ms: Long) {
        val until = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < until) { onUi { scene.render(System.nanoTime() - start) }; Thread.sleep(16) }
    }
    settle(1500)
    for (step in args.drop(2)) {
        val (kind, value) = step.split(":", limit = 2)
        when (kind) {
            "wait" -> settle(value.toLong())
            "drop" -> {
                platform.receive(File(value))
                settle(1500)
            }
            "click" -> {
                val (x, y) = value.split(",").map { it.toFloat() }
                onUi { scene.sendPointerEvent(PointerEventType.Press, Offset(x, y)) }
                settle(60)
                onUi { scene.sendPointerEvent(PointerEventType.Release, Offset(x, y)) }
                settle(700)
            }
            "shot" -> {
                val png = onUi { scene.render(System.nanoTime() - start) }.encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(out, "$value.png").writeBytes(png)
                println("saved $value.png")
            }
        }
    }
    services.stopAll()
    onUi { scene.close() }
    System.exit(0)
}
