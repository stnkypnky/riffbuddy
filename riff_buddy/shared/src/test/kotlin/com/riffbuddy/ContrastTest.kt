package com.riffbuddy

import com.riffbuddy.ui.theme.Palette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The automated contrast check: it passes for every token pair used for
 * text.
 *
 * This is the thing that lets the palette be changed with any confidence. Color
 * chosen by eye on a bright laptop in a bright room is exactly how an app ends
 * up unreadable on a music stand in a dim one, and "it looked fine to me" is not
 * a test. Runs on the JVM, so it costs nothing to run constantly.
 *
 * Thresholds are WCAG 2.1: 4.5:1 for body text, 3:1 for large text and for the
 * edges of controls you have to be able to find.
 */
class ContrastTest {

    /** WCAG relative luminance. Channels are linearized before weighting. */
    private fun luminance(argb: Long): Double {
        val channels = listOf(16, 8, 0).map { shift ->
            val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
            if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
    }

    private fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun hex(argb: Long) = "#%06X".format(argb and 0xFFFFFF)

    @Test
    fun `every foreground is readable on every surface`() {
        val failures = mutableListOf<String>()
        Palette.FOREGROUNDS.forEach { fg ->
            Palette.SURFACES.forEach { bg ->
                val r = ratio(fg, bg)
                if (r < 4.5) failures += "${hex(fg)} on ${hex(bg)} = %.2f".format(r)
            }
        }
        assertTrue("below 4.5:1 — $failures", failures.isEmpty())
    }

    @Test
    fun `text on its containers is readable`() {
        listOf(
            Palette.ON_AMBER to Palette.AMBER,
            Palette.ON_AMBER to Palette.AMBER_STRONG,
            Palette.ON_AMBER to Palette.AMBER_DEEP,
            Palette.ON_AMBER_CONTAINER to Palette.AMBER_CONTAINER,
            Palette.ON_SUCCESS_CONTAINER to Palette.SUCCESS_CONTAINER,
            Palette.ON_DANGER_CONTAINER to Palette.DANGER_CONTAINER,
        ).forEach { (fg, bg) ->
            val r = ratio(fg, bg)
            assertTrue("${hex(fg)} on ${hex(bg)} = %.2f, needs 4.5".format(r), r >= 4.5)
        }
    }

    /** A control's edge has to be findable: 3:1 against what it sits on. */
    @Test
    fun `outline is visible against the surfaces it borders`() {
        listOf(Palette.BG, Palette.SURFACE_LOW, Palette.SURFACE).forEach { bg ->
            val r = ratio(Palette.OUTLINE, bg)
            assertTrue("outline on ${hex(bg)} = %.2f, needs 3.0".format(r), r >= 3.0)
        }
    }

    /**
     * The regression that prompted the brighter palette: a card has to look like
     * a card. The original tokens put a card 1.12 from the background, which is
     * indistinguishable — the whole screen read as one flat rectangle.
     */
    @Test
    fun `a card is visibly raised off the background`() {
        val r = ratio(Palette.SURFACE, Palette.BG)
        assertTrue("card only %.2f from the background — too flat".format(r), r >= 1.30)
    }

    /**
     * An unlit lamp must still read as a lamp — on a card (the streak lamp) and
     * on the bare background (the metronome), which are in opposite directions
     * from it.
     */
    @Test
    fun `unlit lamp is visible on card and on background`() {
        listOf(Palette.SURFACE, Palette.BG).forEach { bg ->
            val r = ratio(Palette.LAMP_DARK, bg)
            assertTrue("unlit lamp only %.2f from ${hex(bg)}".format(r), r >= 1.30)
        }
    }

    /** ...and must be unmistakably dimmer than the same lamp lit. */
    @Test
    fun `unlit lamp reads as off next to a lit one`() {
        val r = ratio(Palette.LAMP_DARK, Palette.AMBER_STRONG)
        assertTrue("lit and unlit only %.2f apart".format(r), r >= 2.5)
    }
}
