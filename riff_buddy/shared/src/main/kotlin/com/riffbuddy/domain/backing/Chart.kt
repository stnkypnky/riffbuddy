package com.riffbuddy.domain.backing

import com.riffbuddy.domain.music.ChordQuality
import com.riffbuddy.domain.music.Key
import com.riffbuddy.domain.music.Notes

/** One chord of a progression, with how long it lasts. */
data class ChartChord(
    val root: Int,
    val quality: ChordQuality,
    val bars: Int,
    /** Written with a flat ("Bb", "bVII"), so shown with one: Bb, not A#. */
    val flat: Boolean = false,
) {
    fun name(preferFlats: Boolean = false): String =
        Notes.name(root + 60, preferFlats || flat).dropLast(1) + quality.suffix

    val third: Int get() = if (quality in MINOR_THIRD) 3 else 4
    val fifth: Int get() = when (quality) {
        ChordQuality.DIMINISHED, ChordQuality.DIMINISHED_7, ChordQuality.HALF_DIMINISHED -> 6
        ChordQuality.AUGMENTED -> 8
        else -> 7
    }
    /** The seventh a bass or riff should lean on: flat unless the chord says major 7. */
    val seventh: Int get() = if (quality == ChordQuality.MAJOR_7) 11 else 10

    private companion object {
        val MINOR_THIRD = setOf(
            ChordQuality.MINOR, ChordQuality.MINOR_7, ChordQuality.MINOR_6, ChordQuality.DIMINISHED,
            ChordQuality.DIMINISHED_7, ChordQuality.HALF_DIMINISHED,
        )
    }
}

/** A named progression for the presets list. */
data class ChartPreset(val name: String, val chart: String, val minor: Boolean, val barsPerChord: Int = 1)

/**
 * Reads a progression typed by hand, as numerals ("I IV V", "ii7 V7 Imaj7",
 * "i bVII bVI") or chord names ("A7 D7 E7", "F#m C#7").
 *
 * **Numerals follow the key's own scale.** In A minor, "VII" is G, as it is on
 * Key's chord chart; a flat or sharp in front moves it a semitone, so
 * "bVII" in A major is also G. Case sets the quality — upper major, lower
 * minor — and a suffix overrides it: 7, maj7, m7, 5, °/dim, ø.
 *
 * Separators are anything a person might type between chords: spaces, commas,
 * bar lines, dashes.
 */
object Chart {

    val PRESETS = listOf(
        ChartPreset("12-bar blues", "I7 I7 I7 I7 IV7 IV7 I7 I7 V7 IV7 I7 V7", minor = false),
        ChartPreset("12-bar blues, quick change", "I7 IV7 I7 I7 IV7 IV7 I7 I7 V7 IV7 I7 V7", minor = false),
        ChartPreset("Minor blues", "i7 i7 i7 i7 iv7 iv7 i7 i7 VI7 V7 i7 V7", minor = true),
        ChartPreset("I–V–vi–IV", "I V vi IV", minor = false, barsPerChord = 2),
        ChartPreset("I–vi–IV–V", "I vi IV V", minor = false, barsPerChord = 2),
        ChartPreset("ii–V–I", "ii7 V7 Imaj7 Imaj7", minor = false, barsPerChord = 2),
        ChartPreset("i–VII–VI", "i VII VI VI", minor = true, barsPerChord = 2),
        ChartPreset("i–iv–v", "i iv v i", minor = true, barsPerChord = 2),
        ChartPreset("Andalusian (i–VII–VI–V)", "i VII VI V", minor = true, barsPerChord = 2),
        ChartPreset("Rock I–bVII–IV", "I bVII IV I", minor = false, barsPerChord = 2),
    )

    /**
     * Parses [text] in [key]. On failure the message names the token that
     * could not be read, so a typo is findable.
     */
    fun parse(text: String, key: Key, barsPerChord: Int = 1): Result<List<ChartChord>> {
        val tokens = text.split(Regex("[\\s,|–—-]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return Result.failure(IllegalArgumentException("Type some chords — numerals like I IV V, or names like A7 D7 E7."))
        val out = mutableListOf<ChartChord>()
        for (t in tokens) {
            val chord = numeral(t, key) ?: named(t)
                ?: return Result.failure(IllegalArgumentException("\"$t\" is not a chord I can read. Try I, IV7, bVII, or A, Dm, E7."))
            out += chord.copy(bars = barsPerChord.coerceIn(1, 8))
        }
        return Result.success(out)
    }

    private val NUMERAL = Regex("^([b#♭♯]?)(VII|VI|IV|V|III|II|I|vii|vi|iv|v|iii|ii|i)(.*)$")
    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII")

    private fun numeral(token: String, key: Key): ChartChord? {
        val m = NUMERAL.matchEntire(token) ?: return null
        val (accidental, roman, suffix) = m.destructured
        val degree = ROMAN.indexOf(roman.uppercase())
        if (degree < 0) return null
        val shift = when (accidental) { "b", "♭" -> -1; "#", "♯" -> 1; else -> 0 }
        val root = (key.tonic + key.scale.semitones[degree] + shift).mod(12)
        val upper = roman[0].isUpperCase()
        val quality = qualityFor(suffix, upper) ?: return null
        return ChartChord(root, quality, 1, flat = shift < 0)
    }

    private val NAME = Regex("^([A-Ga-g])([#b♯♭]?)(.*)$")

    private fun named(token: String): ChartChord? {
        val m = NAME.matchEntire(token) ?: return null
        val (letter, accidental, suffix) = m.destructured
        val root = Notes.parse(letter.uppercase() + accidental.replace('♯', '#').replace('♭', 'b') + "4")
            ?.mod(12) ?: return null
        // Chord names say "m" for minor; there is no case to go by.
        val quality = when {
            suffix.startsWith("maj") -> qualityFor(suffix, upper = true)
            suffix.startsWith("m") && !suffix.startsWith("maj") -> qualityFor(suffix.drop(1), upper = false)
            else -> qualityFor(suffix, upper = true)
        } ?: return null
        return ChartChord(root, quality, 1, flat = accidental == "b" || accidental == "♭")
    }

    /** The quality a suffix names, given whether the root was written major. */
    private fun qualityFor(suffix: String, upper: Boolean): ChordQuality? = when (suffix) {
        "" -> if (upper) ChordQuality.MAJOR else ChordQuality.MINOR
        "7" -> if (upper) ChordQuality.DOMINANT_7 else ChordQuality.MINOR_7
        "maj7", "M7", "Δ" -> ChordQuality.MAJOR_7
        "m7" -> ChordQuality.MINOR_7
        "5" -> ChordQuality.POWER
        "°", "o", "dim" -> ChordQuality.DIMINISHED
        "°7", "o7", "dim7" -> ChordQuality.DIMINISHED_7
        "ø", "ø7", "m7b5" -> ChordQuality.HALF_DIMINISHED
        "sus2" -> ChordQuality.SUS2
        "sus4", "sus" -> ChordQuality.SUS4
        "6" -> if (upper) ChordQuality.SIXTH else ChordQuality.MINOR_6
        "9" -> ChordQuality.NINTH
        "add9" -> ChordQuality.ADD9
        "aug", "+" -> ChordQuality.AUGMENTED
        else -> null
    }
}
