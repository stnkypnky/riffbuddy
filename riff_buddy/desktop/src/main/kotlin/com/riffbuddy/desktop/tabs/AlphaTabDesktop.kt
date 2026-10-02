@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.riffbuddy.desktop.tabs

import alphaTab.Environment
import alphaTab.Settings
import alphaTab.alphaSkia.AlphaSkiaJre
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.model.Font

/**
 * alphaTab's drawing engine on a desktop, set up once per run.
 *
 * alphaTab draws with alphaSkia, whose native library comes in one build per
 * system; the build ships only the one for the system it targets, so it is
 * found by name. The music symbols come from Bravura, which alphaTab ships.
 * Text — titles, tempo marks, fret numbers — comes from Liberation Sans,
 * bundled here: alphaTab asks for its text fonts by name, and a name the
 * system can't supply stops the drawing outright rather than falling back.
 */
object AlphaTabDesktop {

    private var textFamily: String? = null

    /** [density] is pixels per dp: the score is drawn at the screen's own sharpness. */
    @Synchronized
    fun prepare(density: Double) {
        if (textFamily != null) return
        AlphaTabWorkers.setDensity(density)
        val os = System.getProperty("os.name").lowercase()
        val loader = when {
            "win" in os -> "alphaTab.alphaSkia.AlphaSkiaWindows"
            "mac" in os -> "alphaTab.alphaSkia.AlphaSkiaMacOs"
            else -> "alphaTab.alphaSkia.AlphaSkiaLinux"
        }
        (Class.forName(loader).getField("INSTANCE").get(null) as AlphaSkiaJre).initialize()
        Environment.enableAlphaSkia(resource("Bravura.otf").toUByteArray(), null)
        var family = ""
        for (style in listOf("Regular", "Bold", "Italic", "BoldItalic")) {
            family = Environment.registerAlphaSkiaCustomFont(
                Uint8Array(resource("fonts/LiberationSans-$style.ttf").toUByteArray()),
            ).family
        }
        textFamily = family
    }

    /** Points every text font in [settings] at the bundled family. */
    fun useTextFont(settings: Settings) {
        val family = textFamily ?: return
        val resources = settings.display.resources
        // The text fonts are separate properties, one per kind of text, plus a
        // map for score elements; every one has to name a font that exists.
        for (getter in resources.javaClass.methods) {
            if (getter.parameterCount == 0 && getter.name.startsWith("get") && getter.name.endsWith("Font")) {
                (getter.invoke(resources) as? Font)?.families = alphaTab.collections.List(family)
            }
        }
        for (font in resources.elementFonts.values()) font.families = alphaTab.collections.List(family)
    }

    fun resource(path: String): ByteArray =
        AlphaTabDesktop::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("missing resource $path")
}
