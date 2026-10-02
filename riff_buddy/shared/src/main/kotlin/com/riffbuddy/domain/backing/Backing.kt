package com.riffbuddy.domain.backing

import com.riffbuddy.domain.music.ChordQuality
import com.riffbuddy.domain.music.Key
import com.riffbuddy.domain.music.Scale
import org.json.JSONObject

/** How the beat is divided. */
enum class Feel { STRAIGHT, SHUFFLE, SIXTEENTHS }

/** The five backing styles. */
enum class BackingStyle(val label: String, val feel: Feel, val defaultTempo: Int) {
    ROCK("Rock, straight", Feel.STRAIGHT, 110),
    SHUFFLE("Blues shuffle", Feel.SHUFFLE, 90),
    BALLAD("Ballad", Feel.STRAIGHT, 70),
    FUNK("Funk-lite", Feel.SIXTEENTHS, 96),
    HARD_ROCK("Hard rock", Feel.STRAIGHT, 128),
}

/** Everything a backing track is made from. Saved as JSON; the score is regenerated. */
data class BackingSpec(
    val keyTonic: Int = 9,
    val minor: Boolean = false,
    val chart: String = Chart.PRESETS.first().chart,
    val barsPerChord: Int = 1,
    val tempo: Int = 90,
    val beatsPerBar: Int = 4,
    val style: BackingStyle = BackingStyle.SHUFFLE,
    /** Swing the eighths of a straight style (a shuffle is already swung). */
    val swing: Boolean = false,
    val name: String = "",
) {
    val key: Key get() = Key(keyTonic, minor)

    fun toJson(): String = JSONObject()
        .put("key", keyTonic).put("minor", minor).put("chart", chart).put("barsPerChord", barsPerChord)
        .put("tempo", tempo).put("beatsPerBar", beatsPerBar).put("style", style.name).put("swing", swing)
        .put("name", name).toString()

    companion object {
        fun fromJson(json: String): BackingSpec {
            val o = JSONObject(json)
            return BackingSpec(
                keyTonic = o.optInt("key", 9), minor = o.optBoolean("minor"), chart = o.optString("chart"),
                barsPerChord = o.optInt("barsPerChord", 1), tempo = o.optInt("tempo", 90),
                beatsPerBar = o.optInt("beatsPerBar", 4),
                style = BackingStyle.entries.firstOrNull { it.name == o.optString("style") } ?: BackingStyle.ROCK,
                swing = o.optBoolean("swing"), name = o.optString("name"),
            )
        }
    }
}

/** Which chord is playing over which bars — for the "what to play" panel. */
data class ChordSpan(val startBar: Int, val bars: Int, val chord: ChartChord)

data class BackingScore(
    val tex: String,
    val timeline: List<ChordSpan>,
    val totalBars: Int,
    val beatsPerBar: Int,
    val tempo: Int,
) {
    /** The chord at a (0-based) bar, wrapping as the player loops. */
    fun chordAt(bar: Int): ChartChord? {
        if (timeline.isEmpty() || totalBars <= 0) return null
        val b = bar.mod(totalBars)
        return timeline.lastOrNull { it.startBar <= b }?.chord
    }
}

/**
 * Builds a backing track as an alphaTex score: drums, bass and a
 * rhythm part, played by alphaTab so speed, loop and per-track mute come from
 * the same engine as the tab player.
 *
 * **Rules, not recordings.** Each part is generated beat by beat from a small
 * set of rules — hats on every subdivision, kick on the downbeats, snare on
 * the backbeats, the bass starting every bar on the chord's root — with each
 * style adding its own touches. That is why 3/4 needs no second set of tables,
 * and why the same spec always produces the same score: saved tracks reload
 * identically because only the spec is saved.
 *
 * Every bar is checked to add up to exactly the bar's length before it is
 * written; alphaTab would otherwise silently shift everything after it.
 */
object BackingGenerator {

    // Time is counted in units of a twelfth of a beat, which divides evenly
    // into eighths (6), sixteenths (3) and triplets (8 and 4).
    private const val BEAT = 12

    private const val KICK = 36
    private const val SNARE = 38
    private const val SIDE_STICK = 37
    private const val HAT = 42
    private const val HAT_OPEN = 46
    private const val CRASH = 49
    private const val RIDE = 51

    fun generate(spec: BackingSpec): Result<BackingScore> = runCatching {
        require(spec.beatsPerBar in 3..4) { "4/4 and 3/4 are supported" }
        require(spec.tempo in 30..300) { "tempo must be 30–300" }
        val chords = Chart.parse(spec.chart, spec.key, spec.barsPerChord).getOrThrow()
        val bars = chords.flatMap { c -> List(c.bars) { i -> c to i } }
        val timeline = buildList {
            var at = 0
            chords.forEach { add(ChordSpan(at, it.bars, it)); at += it.bars }
        }

        val tex = buildString {
            appendLine("\\title \"${spec.name.ifBlank { "Backing track" }.replace("\"", "'")}\"")
            appendLine("\\tempo ${spec.tempo}")
            appendLine(".")
            appendLine("\\track \"Drums\"")
            appendLine("\\instrument percussion")
            appendLine("\\articulation defaults")
            bars.forEachIndexed { i, (c, n) ->
                if (i == 0 && spec.beatsPerBar == 3) append("\\ts 3 4 ")
                appendLine(drums(spec, i, isChange = n == 0) + " |")
            }
            appendLine("\\track \"Bass\"")
            appendLine("\\instrument 33")
            // Score and tab both: the display setting chooses which to show
            // (tab only by default), and the tab needs real fret positions.
            appendLine("\\staff {score tabs}")
            appendLine("\\tuning (G2 D2 A1 E1)")
            appendLine("\\clef F4")
            bars.forEach { (c, n) -> appendLine(bass(spec, c, n) + " |") }
            appendLine("\\track \"${rhythmName(spec.style)}\"")
            appendLine("\\instrument ${rhythmProgram(spec.style)}")
            appendLine("\\staff {score tabs}")
            bars.forEach { (c, n) -> appendLine(rhythm(spec, c, n) + " |") }
        }
        BackingScore(tex, timeline, bars.size, spec.beatsPerBar, spec.tempo)
    }

    // ---- the grid ----------------------------------------------------------

    /** One event: its length in units, and what sounds (null is a rest). */
    private class Event(val units: Int, val notes: List<String>?)

    /** The subdivisions of one beat, as lengths in units. */
    private fun subdivisions(spec: BackingSpec): List<Int> = when {
        spec.style.feel == Feel.SHUFFLE || spec.swing && spec.style.feel == Feel.STRAIGHT -> listOf(8, 4)
        spec.style.feel == Feel.SIXTEENTHS -> listOf(3, 3, 3, 3)
        else -> listOf(6, 6)
    }

    private fun token(units: Int): String = when (units) {
        48 -> ".1"; 24 -> ".2"; 12 -> ".4"; 6 -> ".8"; 3 -> ".16"
        8 -> ".4{tu 3}"; 4 -> ".8{tu 3}"
        else -> error("no duration for $units units")
    }

    /**
     * One bar of alphaTex. With a [tuning], notes are written as fret.string
     * so the track has tablature; drums have none and pass null.
     */
    private fun write(events: List<Event>, spec: BackingSpec, tuning: List<Int>? = null): String {
        val total = events.sumOf { it.units }
        check(total == spec.beatsPerBar * BEAT) { "a bar came to $total units, not ${spec.beatsPerBar * BEAT}" }
        return events.joinToString(" ") { e ->
            val notes = e.notes
            if (notes.isNullOrEmpty()) return@joinToString "r" + token(e.units)
            val written = tuning?.let { t -> Fretting.place(notes.map(::midiOf), t) } ?: notes
            "(" + written.joinToString(" ") + ")" + token(e.units)
        }
    }

    /** Beat-by-beat events from a function of (beat, subdivision). */
    private fun grid(spec: BackingSpec, tuning: List<Int>? = null, at: (beat: Int, sub: Int) -> List<String>?): String {
        val subs = subdivisions(spec)
        val events = (0 until spec.beatsPerBar).flatMap { beat ->
            subs.mapIndexed { sub, units -> Event(units, at(beat, sub)) }
        }
        return write(events, spec, tuning)
    }

    // ---- drums -------------------------------------------------------------

    private fun drums(spec: BackingSpec, bar: Int, isChange: Boolean): String = grid(spec) { beat, sub ->
        val backbeat = if (spec.beatsPerBar == 4) beat == 1 || beat == 3 else beat >= 1
        val hits = mutableListOf<Int>()
        when (spec.style) {
            BackingStyle.SHUFFLE -> {
                hits += RIDE
                if (sub == 0 && (beat == 0 || beat == 2)) hits += KICK
                if (sub == 0 && backbeat) hits += SNARE
            }
            BackingStyle.BALLAD -> {
                hits += HAT
                if (sub == 0 && beat == 0) hits += KICK
                if (spec.beatsPerBar == 4 && beat == 2 && sub == 1) hits += KICK
                // Half-time: one backbeat, on three, and a side stick rather
                // than a full snare, so it stays under a quiet part.
                if (sub == 0 && (if (spec.beatsPerBar == 4) beat == 2 else beat >= 1)) hits += SIDE_STICK
            }
            BackingStyle.FUNK -> {
                if (sub % 2 == 0) hits += HAT
                if (beat == 0 && sub == 0) hits += KICK
                if (beat == 0 && sub == 3) hits += KICK
                if (beat == 2 && sub == 2) hits += KICK
                if (sub == 0 && backbeat) hits += SNARE
            }
            BackingStyle.HARD_ROCK -> {
                hits += if (beat == spec.beatsPerBar - 1 && sub == 1) HAT_OPEN else HAT
                if (beat == 0 || beat == 2) hits += KICK
                if (sub == 0 && backbeat) hits += SNARE
            }
            BackingStyle.ROCK -> {
                hits += HAT
                if (sub == 0 && (beat == 0 || beat == 2)) hits += KICK
                if (beat == 2 && sub == 1) hits += KICK
                if (sub == 0 && backbeat) hits += SNARE
            }
        }
        // A crash marks the top of the track and each chord change in the
        // heavier styles, the way a drummer would.
        if (beat == 0 && sub == 0 && (bar == 0 || (isChange && spec.style in CRASH_ON_CHANGE))) {
            hits.remove(HAT); hits.remove(RIDE); hits += CRASH
        }
        hits.distinct().sorted().map { it.toString() }
    }

    private val CRASH_ON_CHANGE = setOf(BackingStyle.HARD_ROCK, BackingStyle.ROCK)

    // ---- bass --------------------------------------------------------------

    private fun bassNote(c: ChartChord, interval: Int): String = pitch(bassRoot(c.root) + interval)

    /** The bass's root: E1 to D#2, the bottom octave of a four-string. */
    private fun bassRoot(pc: Int) = 28 + (pc - 4).mod(12)

    /**
     * The unit-tested rule: every bar's first bass note is the chord's root,
     * in every style. The rest is style.
     */
    private fun bass(spec: BackingSpec, c: ChartChord, barOfChord: Int): String = when (spec.style) {
        BackingStyle.SHUFFLE -> {
            // Boogie: each beat's two triplet notes the same, up the chord and
            // back — root, third, fifth, sixth; then root, seventh, sixth, fifth.
            val up = listOf(0, c.third, c.fifth, 9)
            val down = listOf(0, c.seventh, 9, c.fifth)
            val line = if (barOfChord % 2 == 0) up else down
            grid(spec, Fretting.BASS) { beat, _ -> listOf(bassNote(c, line[beat % line.size])) }
        }
        BackingStyle.BALLAD -> {
            val events = if (spec.beatsPerBar == 4) {
                listOf(Event(24, listOf(bassNote(c, 0))), Event(24, listOf(bassNote(c, c.fifth))))
            } else {
                listOf(Event(24, listOf(bassNote(c, 0))), Event(12, listOf(bassNote(c, c.fifth))))
            }
            write(events, spec, Fretting.BASS)
        }
        BackingStyle.FUNK -> grid(spec, Fretting.BASS) { beat, sub ->
            val slot = beat * 4 + sub
            when (slot) {
                0, 3 -> listOf(bassNote(c, 0))
                6 -> listOf(bassNote(c, 12))
                10 -> listOf(bassNote(c, 0))
                14 -> listOf(bassNote(c, c.fifth))
                else -> null
            }
        }
        BackingStyle.ROCK, BackingStyle.HARD_ROCK -> grid(spec, Fretting.BASS) { _, _ -> listOf(bassNote(c, 0)) }
    }

    // ---- rhythm ------------------------------------------------------------

    /**
     * Short on purpose. With tab only there is one staff per track, and
     * alphaTab writes the name sideways down its left edge: "Rhythm guitar"
     * came out as "Rhythm gui" on a tablet. The chip that mutes it says
     * "Rhythm" anyway.
     */
    private fun rhythmName(style: BackingStyle) = if (style == BackingStyle.BALLAD) "Clean" else "Rhythm"

    /**
     * General MIDI programs: overdriven, distortion, clean guitar. The ballad
     * was a warm pad (89) until the tracks were given tab — alphaTab only
     * accepts fret positions on a plucked-string instrument, and a pad part
     * would have been the one track missing from the tab.
     */
    private fun rhythmProgram(style: BackingStyle) = when (style) {
        BackingStyle.ROCK, BackingStyle.SHUFFLE -> 29
        BackingStyle.HARD_ROCK -> 30
        BackingStyle.FUNK, BackingStyle.BALLAD -> 27
    }

    /** A power chord: root (E2–D#3), fifth, octave. */
    private fun power(c: ChartChord): List<String> {
        val r = 40 + (c.root - 4).mod(12)
        return listOf(pitch(r), pitch(r + 7), pitch(r + 12))
    }

    /** A close voicing around the middle of the keyboard, with a seventh if it has one. */
    private fun voicing(c: ChartChord): List<String> {
        val r = 48 + c.root.mod(12)
        val tones = mutableListOf(0, c.third, c.fifth)
        if (c.quality in SEVENTHS) tones += c.seventh
        if (c.quality == ChordQuality.POWER) return power(c)
        return tones.map { pitch(r + it) }
    }

    private val SEVENTHS = setOf(
        ChordQuality.DOMINANT_7, ChordQuality.MAJOR_7, ChordQuality.MINOR_7, ChordQuality.HALF_DIMINISHED, ChordQuality.NINTH,
    )

    private fun rhythm(spec: BackingSpec, c: ChartChord, barOfChord: Int): String = when (spec.style) {
        BackingStyle.SHUFFLE -> {
            // The Jimmy Reed boogie: root and fifth, root and sixth, in the
            // shuffle's long-short — the sound of the twelve-bar.
            val r = 40 + (c.root - 4).mod(12)
            grid(spec, Fretting.GUITAR) { beat, _ -> listOf(pitch(r), pitch(r + if (beat % 2 == 0) 7 else 9)) }
        }
        BackingStyle.BALLAD -> {
            val events = if (spec.beatsPerBar == 4) listOf(Event(48, voicing(c)))
            else listOf(Event(24, voicing(c)), Event(12, voicing(c)))
            write(events, spec, Fretting.GUITAR)
        }
        BackingStyle.FUNK -> grid(spec, Fretting.GUITAR) { _, sub -> if (sub == 2) voicing(c).map { it } else null }
        BackingStyle.HARD_ROCK -> grid(spec, Fretting.GUITAR) { _, _ -> power(c) }
        BackingStyle.ROCK -> grid(spec, Fretting.GUITAR) { _, sub -> if (sub == 0) power(c) else null }
    }

    // ---- pitch names ---------------------------------------------------------

    private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** MIDI note to alphaTex's pitch name: 60 is C4. */
    fun pitch(midi: Int): String = NAMES[midi.mod(12)] + (midi / 12 - 1)

    /** The inverse of [pitch]. */
    private fun midiOf(name: String): Int {
        val split = name.indexOfFirst { it.isDigit() || it == '-' }
        return NAMES.indexOf(name.substring(0, split)) + (name.substring(split).toInt() + 1) * 12
    }

    // ---- what to play --------------------------------------------------------

    /**
     * The scale to solo with over the whole track. A blues — shuffle style, or
     * a chart of dominant sevenths — takes the blues scale on the key's root,
     * which is the standard advice and works over all three chords. Otherwise
     * the key's pentatonic, the safest place to start.
     */
    fun suggestedScale(spec: BackingSpec): Scale {
        val chords = Chart.parse(spec.chart, spec.key, spec.barsPerChord).getOrNull().orEmpty()
        val bluesy = spec.style == BackingStyle.SHUFFLE ||
            (chords.isNotEmpty() && chords.count { it.quality == ChordQuality.DOMINANT_7 } * 2 >= chords.size)
        return when {
            bluesy -> Scale.BLUES
            spec.minor -> Scale.MINOR_PENTATONIC
            else -> Scale.MAJOR_PENTATONIC
        }
    }
}
