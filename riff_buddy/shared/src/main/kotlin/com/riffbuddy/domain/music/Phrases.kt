package com.riffbuddy.domain.music

/**
 * One note of something to listen to.
 *
 * [string] and [fret] say where it is on the neck, so the screen can light
 * the dot that is sounding; -1 means it has no place on a diagram (a chord
 * the library has no shape for). [group] ties notes to what the screen
 * shows as one thing, like the chord in a progression.
 */
data class PhraseNote(
    val atMs: Int,
    val midi: Int,
    val lengthMs: Int,
    val string: Int = -1,
    val fret: Int = -1,
    val velocity: Float = 1f,
    val group: Int = 0,
)

data class Phrase(val notes: List<PhraseNote>) {
    /** When the last note stops being played; the synth adds its ring after. */
    val lengthMs: Int get() = notes.maxOfOrNull { it.atMs + it.lengthMs } ?: 0

    /** The notes started and not yet released at [ms]. */
    fun soundingAt(ms: Long): List<PhraseNote> =
        notes.filter { ms >= it.atMs && ms < it.atMs + it.lengthMs }

    /** The group of the note most recently started at [ms], for "which chord is this". */
    fun groupAt(ms: Long): Int? = notes.lastOrNull { it.atMs <= ms }?.group
}

/**
 * Turns what the Theory screen shows into something to hear: a scale
 * position played through, a chord strummed and picked, a progression.
 *
 * Pure timing and pitch, no sound. The synth renders a [Phrase]; this only
 * says which notes and when, so it's tested on the JVM like the rest of
 * `domain/`.
 */
object Phrases {

    /** The speeds offered, as beats per minute. One scale note per beat. */
    enum class Speed(val label: String, val bpm: Int) {
        SLOW("Slow", 60), MEDIUM("Medium", 90), FAST("Fast", 130)
    }

    private fun beatMs(bpm: Int) = 60_000 / bpm

    /**
     * Up the position and back down, one note per beat.
     *
     * The top note is played once, not twice. Each note is held until the
     * next one starts, as a guitarist playing a scale would — letting them
     * ring on would blur a run into a chord.
     */
    fun scaleRun(tuning: Tuning, cells: List<Pair<Int, Int>>, speed: Speed): Phrase {
        val up = cells.sortedBy { (s, f) -> tuning.midi[s] + f }
        val path = up + up.dropLast(1).reversed()
        val beat = beatMs(speed.bpm)
        return Phrase(path.mapIndexed { i, (s, f) ->
            // The last note rings out so the run ends on a held root.
            val length = if (i == path.lastIndex) beat * 2 else beat
            PhraseNote(i * beat, tuning.midi[s] + f, length, s, f)
        })
    }

    /**
     * A chord three ways, to learn what it should sound like and then check
     * each string of your own: strummed, picked one string at a time, and
     * strummed again.
     */
    fun chord(tuning: Tuning, frets: List<Int>, speed: Speed): Phrase {
        val beat = beatMs(speed.bpm)
        val sounding = frets.withIndex().filter { it.value >= 0 && it.index < tuning.stringCount }
        val notes = mutableListOf<PhraseNote>()
        notes += strum(tuning, frets, atMs = 0, lengthMs = beat * 2)
        val picked = beat * 2
        sounding.forEachIndexed { i, (s, f) ->
            notes += PhraseNote(picked + i * beat / 2, tuning.midi[s] + f, beat, s, f)
        }
        val again = picked + sounding.size * beat / 2 + beat / 2
        notes += strum(tuning, frets, atMs = again, lengthMs = beat * 3)
        return Phrase(notes)
    }

    /**
     * A progression, one chord per bar of 4/4, strummed on beats one and
     * three. Plain on purpose: the point is hearing the chords change, and a
     * busy rhythm would make that harder to follow. Each chord's notes carry
     * its place in the list as their group, so the screen can light it.
     */
    fun progression(chords: List<List<Int>>, speed: Speed, tuning: Tuning = Tuning.STANDARD): Phrase {
        // Chords change slower than scale notes; the Slow run is already one
        // note a second, and a bar at 60 would be four seconds per chord.
        val beat = beatMs(speed.bpm + 20)
        val notes = mutableListOf<PhraseNote>()
        chords.forEachIndexed { index, frets ->
            val bar = index * beat * 4
            notes += strum(tuning, frets, bar, beat * 2, group = index)
            notes += strum(tuning, frets, bar + beat * 2, beat * 2, group = index, velocity = 0.8f)
        }
        return Phrase(notes)
    }

    /**
     * One downstroke: the strings sound low to high a few milliseconds apart,
     * as a pick crosses them. All at once sounds like a keyboard.
     */
    private fun strum(
        tuning: Tuning,
        frets: List<Int>,
        atMs: Int,
        lengthMs: Int,
        group: Int = 0,
        velocity: Float = 1f,
    ): List<PhraseNote> =
        frets.withIndex()
            .filter { it.value >= 0 && it.index < tuning.stringCount }
            .mapIndexed { i, (s, f) ->
                PhraseNote(atMs + i * STRUM_GAP_MS, tuning.midi[s] + f, lengthMs - i * STRUM_GAP_MS, s, f, velocity * 0.8f, group)
            }

    private const val STRUM_GAP_MS = 18

    /**
     * The frets to play a chord with: the library's first shape (an open
     * chord where there is one), or, for a chord it has no shape for — the
     * diminished chord in every key — the triad on the D, G and B strings,
     * which is where that chord is usually played anyway.
     */
    fun fretsFor(root: Int, quality: ChordQuality): List<Int> {
        ChordLibrary.voicings(root, quality).firstOrNull()?.let { return it.frets }
        val open = Tuning.STANDARD.midi
        // The third and fifth sit up to three frets behind the root on the
        // next two strings, so the root needs to be at fret 3 or higher.
        val rootFret = (root - open[2]).mod(12).let { if (it < 3) it + 12 else it }
        val rootMidi = open[2] + rootFret
        val frets = MutableList(6) { -1 }
        frets[2] = rootFret
        quality.semitones.drop(1).take(2).forEachIndexed { i, interval ->
            frets[3 + i] = rootMidi + interval - open[3 + i]
        }
        return frets
    }
}
