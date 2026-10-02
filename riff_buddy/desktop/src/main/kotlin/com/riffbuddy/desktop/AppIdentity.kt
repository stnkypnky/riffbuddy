package com.riffbuddy.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.riffbuddy.platform.AppLog
import java.awt.Image
import java.awt.Toolkit
import javax.imageio.ImageIO

/**
 * How the running app is recognized by the desktop around it: its icons, and
 * on Linux the name that ties its window to its menu entry.
 */
object AppIdentity {

    /**
     * The installer's menu entry for the app, by file name. GNOME, and the
     * desktops built on it, show a window's taskbar icon by finding the entry
     * whose name matches the window's class; a Java window's class is otherwise
     * its main class's name, which matches nothing, and gets a generic icon.
     * The installer names the entry from the package and app names.
     */
    private const val LINUX_ENTRY = "riffbuddy-Riff_Buddy"

    private val SIZES = listOf(16, 24, 32, 48, 64, 128, 256)

    /** Must run before the first window opens. */
    fun apply() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        // Java keeps the class name in a field of its X11 toolkit, read as each
        // window is created; the build opens that package to this app.
        runCatching {
            val toolkit = Toolkit.getDefaultToolkit()
            toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(toolkit, LINUX_ENTRY)
        }.onFailure { AppLog.w("RiffWindow", "could not name the window class: ${it.message}") }
    }

    /** The window's icon at every size, for the title bar, taskbar and window switcher to choose from. */
    fun windowIconImages(): List<Image> = SIZES.mapNotNull { size ->
        AppIdentity::class.java.classLoader.getResourceAsStream("icon/window/riffbuddy-$size.png")?.use { ImageIO.read(it) }
    }

    fun windowIcon(): Painter {
        val image = AppIdentity::class.java.classLoader.getResourceAsStream("icon/window/riffbuddy-256.png")!!.use { ImageIO.read(it) }
        return BitmapPainter(image.toComposeImageBitmap())
    }
}
