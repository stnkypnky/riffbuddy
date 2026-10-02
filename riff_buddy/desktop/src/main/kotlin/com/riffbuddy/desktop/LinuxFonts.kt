package com.riffbuddy.desktop

import com.riffbuddy.platform.AppLog
import com.sun.jna.NativeLibrary
import java.io.File

/**
 * Gives Skia a font setup of its own on Linux, before anything draws.
 *
 * Skia — both Compose's and alphaTab's — carries its own copy of fontconfig,
 * an older one than most current Linux systems run. Pointed at the system's
 * configuration and shared cache, it can spin for minutes on startup: on one
 * machine with 2,868 fonts it never finished in five. Given the same font
 * folders with a cache of its own, it takes about four seconds the first time
 * and under one after that.
 *
 * So the app writes a small configuration naming the usual font folders, a
 * cache inside the app's own cache folder, and sensible fallbacks for the
 * generic names, and points Skia at it. Every installed font is still there.
 * Windows and macOS use their own font systems and need none of this.
 *
 * Must run before the first window or score is created. A FONTCONFIG_FILE the
 * person set themselves is left alone.
 */
object LinuxFonts {

    fun prepare(cacheDir: File) {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        if (System.getenv("FONTCONFIG_FILE") != null) return
        val home = System.getProperty("user.home")
        val dir = File(cacheDir, "fontconfig").apply { mkdirs() }
        val conf = File(dir, "fonts.conf")
        val folders = listOf(
            "/usr/share/fonts", "/usr/local/share/fonts",
            "$home/.local/share/fonts", "$home/.fonts",
        ).filter { File(it).isDirectory }
        conf.writeText(
            buildString {
                appendLine("""<?xml version="1.0"?>""")
                appendLine("""<!DOCTYPE fontconfig SYSTEM "fonts.dtd">""")
                appendLine("<fontconfig>")
                folders.forEach { appendLine("  <dir>$it</dir>") }
                appendLine("  <cachedir>${File(dir, "cache").path}</cachedir>")
                alias("sans-serif", "Roboto", "Noto Sans", "DejaVu Sans", "Liberation Sans")
                alias("serif", "Noto Serif", "DejaVu Serif", "Liberation Serif")
                alias("monospace", "DejaVu Sans Mono", "Noto Sans Mono", "Liberation Mono")
                appendLine("</fontconfig>")
            }
        )
        val ok = runCatching {
            NativeLibrary.getInstance("c").getFunction("setenv").invokeInt(arrayOf("FONTCONFIG_FILE", conf.path, 0))
        }.getOrDefault(-1)
        if (ok != 0) AppLog.w("RiffFonts", "could not set FONTCONFIG_FILE; font setup may be slow on this system")
    }

    private fun StringBuilder.alias(generic: String, vararg families: String) {
        appendLine("  <alias><family>$generic</family><prefer>")
        families.forEach { appendLine("    <family>$it</family>") }
        appendLine("  </prefer></alias>")
    }
}
