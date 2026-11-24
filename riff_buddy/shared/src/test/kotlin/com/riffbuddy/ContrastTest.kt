package com.riffbuddy

import com.riffbuddy.ui.theme.Finish
import com.riffbuddy.ui.theme.Finishes
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
 * edges of controls you have to be able to find. Every check runs on every
 * finish, dark and light, and names the finish that fails.
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

    /** Runs [check] on every finish, collecting failures with the finish's name. */
    private fun eachFinish(check: Finish.(MutableList<String>) -> Unit) {
        val failures = mutableListOf<String>()
        Finishes.ALL.forEach { f ->
            val own = mutableListOf<String>()
            f.check(own)
            own.forEach { failures += "${f.label}: $it" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `every foreground is readable on every surface`() = eachFinish { out ->
        foregrounds.forEach { fg ->
            surfaces.forEach { bg ->
                val r = ratio(fg, bg)
                if (r < 4.5) out += "${hex(fg)} on ${hex(bg)} = %.2f, needs 4.5".format(r)
            }
        }
    }

    @Test
    fun `text on its containers is readable`() = eachFinish { out ->
        listOf(
            onAccent to accent,
            onAccent to accentStrong,
            onAccent to accentDeep,
            onAccentContainer to accentContainer,
            onSuccessContainer to successContainer,
            onDangerContainer to dangerContainer,
            onSuccess to success,
            onDanger to danger,
        ).forEach { (fg, bg) ->
            val r = ratio(fg, bg)
            if (r < 4.5) out += "${hex(fg)} on ${hex(bg)} = %.2f, needs 4.5".format(r)
        }
    }

    /** A control's edge has to be findable: 3:1 against what it sits on. */
    @Test
    fun `outline is visible against the surfaces it borders`() = eachFinish { out ->
        listOf(bg, surfaceLow, surface).forEach { b ->
            val r = ratio(outline, b)
            if (r < 3.0) out += "outline on ${hex(b)} = %.2f, needs 3.0".format(r)
        }
    }

    /**
     * The regression that prompted the brighter palette: a card has to look like
     * a card. The original tokens put a card 1.12 from the background, which is
     * indistinguishable — the whole screen read as one flat rectangle.
     */
    @Test
    fun `a card is visibly raised off the background`() = eachFinish { out ->
        val r = ratio(surface, bg)
        if (r < 1.30) out += "card only %.2f from the background — too flat".format(r)
    }

    /**
     * An unlit lamp must still read as a lamp — on a card (the streak lamp) and
     * on the bare background (the metronome), which are in opposite directions
     * from it.
     */
    @Test
    fun `unlit lamp is visible on card and on background`() = eachFinish { out ->
        listOf(surface, bg).forEach { b ->
            val r = ratio(lampDark, b)
            if (r < 1.30) out += "unlit lamp only %.2f from ${hex(b)}".format(r)
        }
    }

    /** ...and must be unmistakably dimmer than the same lamp lit. */
    @Test
    fun `unlit lamp reads as off next to a lit one`() = eachFinish { out ->
        val r = ratio(lampDark, accentStrong)
        if (r < 2.5) out += "lit and unlit only %.2f apart".format(r)
    }

    /** A saved choice is found again by its id; ids never collide. */
    @Test
    fun `finish ids are unique and round-trip`() {
        val ids = Finishes.ALL.map { it.id }
        assertTrue("duplicate ids in $ids", ids.toSet().size == ids.size)
        Finishes.ALL.forEach { assertTrue(Finishes.byId(it.id) === it) }
        assertTrue(Finishes.byId(null) === Finishes.DEFAULT)
        assertTrue(Finishes.byId("no-such-finish") === Finishes.DEFAULT)
    }

    /** The light finishes really are light, and the dark ones dark. */
    @Test
    fun `each finish is the brightness it says`() = eachFinish { out ->
        val l = luminance(bg)
        if (dark && l > 0.2) out += "marked dark but background luminance is %.2f".format(l)
        if (!dark && l < 0.5) out += "marked light but background luminance is %.2f".format(l)
    }
}
