package com.riffbuddy.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import java.awt.event.KeyEvent as AwtKey
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import com.riffbuddy.AppServices
import com.riffbuddy.ai.OnlineCheck
import com.riffbuddy.platform.LocalPlatform
import com.riffbuddy.ui.LocalShortcuts
import com.riffbuddy.ui.Shortcut
import com.riffbuddy.ui.ShortcutRegistry
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
 *           key:NAME, such as key:space, key:ctrl+2, key:shift+up (as if typed on the window)
 *
 * The app runs against a fresh database in <out-dir>/data, so nothing real is
 * touched. Motion is off, so screens are caught at rest; set RIFF_MOTION=1 to
 * catch the opening animation instead.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
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
    // The window's keys, as Main sets them up.
    val shortcuts = ShortcutRegistry(listOf(Shortcut("Esc", "Go back", listOf(Key.Escape)) { platform.handleBack() }))
    val motion = System.getenv("RIFF_MOTION")
    val scene = onUi { ImageComposeScene(w, h, Density(1f)) {
        RiffBuddyTheme(condensedFont = condensed, systemReducedMotion = motion == null) {
            CompositionLocalProvider(LocalPlatform provides platform, LocalShortcuts provides shortcuts) {
                RiffBuddyContent(services, overlay = { com.riffbuddy.ui.Intro() })
            }
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
            // scroll:X,Y,LINES — a mouse wheel over that point; positive scrolls down.
            "scroll" -> {
                val (x, y, lines) = value.split(",").map { it.toFloat() }
                onUi { scene.sendPointerEvent(PointerEventType.Scroll, Offset(x, y), scrollDelta = Offset(0f, lines)) }
                settle(500)
            }
            "key" -> {
                val parts = value.lowercase().split("+")
                val (code, char) = KEYS[parts.last()] ?: error("No key called ${parts.last()}")
                var mods = 0
                if ("ctrl" in parts) mods = mods or AwtKey.CTRL_DOWN_MASK
                if ("meta" in parts) mods = mods or AwtKey.META_DOWN_MASK
                if ("alt" in parts) mods = mods or AwtKey.ALT_DOWN_MASK
                if ("shift" in parts) mods = mods or AwtKey.SHIFT_DOWN_MASK
                val typed = if (mods and (AwtKey.CTRL_DOWN_MASK or AwtKey.META_DOWN_MASK or AwtKey.ALT_DOWN_MASK) == 0) char else AwtKey.CHAR_UNDEFINED
                // Real AWT events, converted the way the window converts them:
                // a text field tells typing from a shortcut by what they carry.
                fun send(id: Int, keyCode: Int, keyChar: Char) = scene.sendKeyEvent(
                    KeyEvent(
                        key = Key(keyCode),
                        type = when (id) {
                            AwtKey.KEY_PRESSED -> KeyEventType.KeyDown
                            AwtKey.KEY_RELEASED -> KeyEventType.KeyUp
                            else -> KeyEventType.Unknown
                        },
                        codePoint = if (keyChar == AwtKey.CHAR_UNDEFINED) 0 else keyChar.code,
                        isCtrlPressed = "ctrl" in parts, isMetaPressed = "meta" in parts,
                        isAltPressed = "alt" in parts, isShiftPressed = "shift" in parts,
                        nativeEvent = AwtKey(awtSource, id, System.currentTimeMillis(), mods, keyCode, keyChar),
                    )
                )
                onUi {
                    val pressed = send(AwtKey.KEY_PRESSED, code, typed)
                    val typedUsed = typed != AwtKey.CHAR_UNDEFINED && send(AwtKey.KEY_TYPED, AwtKey.VK_UNDEFINED, typed)
                    send(AwtKey.KEY_RELEASED, code, typed)
                    val used = pressed || typedUsed
                    println("key $value: ${if (used) "used" else "unused"}")
                }
                settle(500)
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

private val awtSource = java.awt.Canvas()

/** Each key's AWT code and the character it types, if any. */
private val KEYS: Map<String, Pair<Int, Char>> = mapOf(
    "space" to (AwtKey.VK_SPACE to ' '), "escape" to (AwtKey.VK_ESCAPE to '\u001b'),
    "home" to (AwtKey.VK_HOME to AwtKey.CHAR_UNDEFINED), "f1" to (AwtKey.VK_F1 to AwtKey.CHAR_UNDEFINED),
    "slash" to (AwtKey.VK_SLASH to '/'), "minus" to (AwtKey.VK_MINUS to '-'), "equals" to (AwtKey.VK_EQUALS to '='),
    "up" to (AwtKey.VK_UP to AwtKey.CHAR_UNDEFINED), "down" to (AwtKey.VK_DOWN to AwtKey.CHAR_UNDEFINED),
    "left" to (AwtKey.VK_LEFT to AwtKey.CHAR_UNDEFINED), "right" to (AwtKey.VK_RIGHT to AwtKey.CHAR_UNDEFINED),
) + ('a'..'z').associate { it.toString() to (AwtKey.getExtendedKeyCodeForChar(it.code) to it) } +
    ('1'..'5').associate { it.toString() to (AwtKey.getExtendedKeyCodeForChar(it.code) to it) }
