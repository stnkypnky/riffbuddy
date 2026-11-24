package com.riffbuddy.audio

/**
 * What a metronome is set to. Plain data, fully testable.
 */

/** How a single pulse should sound. */
enum class Accent {
    /** The downbeat, or a beat the player has marked. Loudest and highest. */
    ACCENT,
    NORMAL,
    /** Silent, but still counted — the gap in a pattern is part of the pattern. */
    MUTED,
}

/**
 * Beats per bar over the note value that gets the beat.
 *
 * The important part is [tempoBeatsPerBar]: how many times a bar the metronome
 * actually clicks, which is *not* always the numerator.
 *
 * 6/8 is felt in two, not six. Count off a 6/8 tune and you count two, with
 * three eighths inside each — so the click is the dotted quarter and the tempo
 * marking means dotted quarters a minute, exactly as a score writes it. 12/8 is
 * the same idea in four, which is the classic slow-blues shuffle: four on the
 * floor with triplets underneath. Only genuinely odd meters like 7/8 are counted
 * in eighths, and there the grouping (2+2+3) lives in the accents.
 *
 * Getting this wrong is not subtle. Treating 6/8 as six clicks makes the tempo
 * number mean something different from what every chart and every drummer means
 * by it.
 */
data class TimeSignature(val beats: Int, val unit: Int) {
    init {
        require(beats in 1..16) { "beats must be 1-16, was $beats" }
        require(unit == 4 || unit == 8) { "unit must be 4 or 8, was $unit" }
    }

    /**
     * Compound time: eighths that group in threes, felt at the dotted quarter.
     * 6/8, 9/8 and 12/8. 3/8 is left out — it is normally felt in three.
     */
    val isCompound: Boolean get() = unit == 8 && beats > 3 && beats % 3 == 0

    /** Odd meters: counted in eighths, with the grouping carried by accents. */
    val isIrregular: Boolean get() = unit == 8 && !isCompound

    /** How many times the metronome clicks in a bar with no subdivision. */
    val tempoBeatsPerBar: Int get() = if (isCompound) beats / 3 else beats

    /** What one BPM unit is, written the way a chart writes it. */
    val beatUnitSymbol: String get() = when {
        isCompound -> "\u2669."     // dotted quarter
        isIrregular -> "\u266A"     // eighth
        else -> "\u2669"            // quarter
    }

    val beatUnitName: String get() = when {
        isCompound -> "dotted quarter"
        isIrregular -> "eighth"
        else -> "quarter"
    }

    /**
     * Where the accents fall before the player changes anything.
     *
     * Odd meters get their conventional grouping: 7/8 as 2+2+3 and 5/8 as 2+3,
     * which is how they are almost always played. Everything else accents the
     * downbeat alone.
     */
    val defaultAccents: List<Accent> get() {
        val n = tempoBeatsPerBar
        val starts: Set<Int> = when {
            !isIrregular -> setOf(0)
            beats == 7 -> setOf(0, 2, 4)      // 2 + 2 + 3
            beats == 5 -> setOf(0, 2)         // 2 + 3
            else -> setOf(0)
        }
        return (0 until n).map { if (it in starts) Accent.ACCENT else Accent.NORMAL }
    }

    override fun toString() = "$beats/$unit"

    companion object {
        val COMMON = listOf(
            TimeSignature(2, 4), TimeSignature(3, 4), TimeSignature(4, 4),
            TimeSignature(5, 4), TimeSignature(6, 8), TimeSignature(7, 8),
            TimeSignature(12, 8),
        )
        val FOUR_FOUR = TimeSignature(4, 4)

        /**
         * A song's time signature as typed ("6/8", " 7 / 8 "). Null for
         * anything the metronome cannot play, so a song with an odd value
         * leaves the metronome's own setting alone instead of failing.
         */
        fun parse(text: String?): TimeSignature? {
            val parts = text?.split("/")?.map { it.trim().toIntOrNull() } ?: return null
            if (parts.size != 2) return null
            val (beats, unit) = parts
            if (beats == null || unit == null || beats !in 1..16 || (unit != 4 && unit != 8)) return null
            return TimeSignature(beats, unit)
        }
    }
}

/** Extra pulses inside each beat. */
enum class Subdivision(val perBeat: Int, val label: String) {
    NONE(1, "None"),
    EIGHTHS(2, "Eighths"),
    TRIPLETS(3, "Triplets"),
    SIXTEENTHS(4, "Sixteenths");

    /**
     * The name this division goes by in a given meter.
     *
     * In compound time the beat already divides in three, so three divisions of
     * a dotted quarter are simply the eighth notes — calling them triplets would
     * be wrong, and a player would notice.
     */
    fun labelIn(sig: TimeSignature): String = when {
        !sig.isCompound -> label
        this == TRIPLETS -> "Eighths"
        this == SIXTEENTHS -> "Sixteenths"
        this == EIGHTHS -> "Duplets"
        else -> label
    }
}

/** The four click voices. Waveforms are built in [ClickSynth]. */
enum class ClickSound(val label: String) {
    CLICK("Click"), WOODBLOCK("Woodblock"), HIHAT("Hi-hat"), BEEP("Beep"),
}

/**
 * One sounding event: which pulse of the bar, and how it should be voiced.
 *
 * [indexInBar] counts *pulses*, subdivisions included, so it is the index into
 * the sample timeline. [beatInBar] is the musical beat it belongs to, which is
 * what the lamp and the accent editor care about.
 */
data class Pulse(val indexInBar: Int, val beatInBar: Int, val isDownbeat: Boolean, val accent: Accent)

data class MetronomeSettings(
    val bpm: Int = 100,
    val timeSignature: TimeSignature = TimeSignature.FOUR_FOUR,
    val subdivision: Subdivision = Subdivision.NONE,
    /** One entry per beat in the bar. Short lists are padded with NORMAL. */
    val accents: List<Accent> = emptyList(),
    val sound: ClickSound = ClickSound.CLICK,
    /** Bars of clicks before the count starts for real. */
    val countInBars: Int = 0,
    /** 0.0 to 1.0. */
    val volume: Float = 0.8f,
) {
    init {
        require(bpm in BPM_RANGE) { "bpm must be in $BPM_RANGE, was $bpm" }
        require(countInBars in COUNT_IN_RANGE) { "countInBars must be in $COUNT_IN_RANGE, was $countInBars" }
    }

    /** Pulses per bar, subdivisions included. */
    val pulsesPerBar: Int get() = timeSignature.tempoBeatsPerBar * subdivision.perBeat

    /** How many times the bar clicks with no subdivision. */
    val beatsPerBar: Int get() = timeSignature.tempoBeatsPerBar

    /** The accent for a beat, falling back to the meter's conventional grouping. */
    fun accentFor(beat: Int): Accent =
        accents.getOrNull(beat) ?: timeSignature.defaultAccents.getOrElse(beat) { Accent.NORMAL }

    /**
     * The bar expanded into the pulses that will actually sound.
     *
     * A subdivision is never accented — it is the quiet tick between beats — so
     * a muted beat mutes its subdivisions with it, which is what makes the
     * accent editor behave the way it looks like it should.
     */
    fun bar(): List<Pulse> = buildList {
        for (beat in 0 until timeSignature.tempoBeatsPerBar) {
            val accent = accentFor(beat)
            for (sub in 0 until subdivision.perBeat) {
                val onBeat = sub == 0
                add(
                    Pulse(
                        indexInBar = beat * subdivision.perBeat + sub,
                        beatInBar = beat,
                        isDownbeat = onBeat && beat == 0,
                        accent = when {
                            accent == Accent.MUTED -> Accent.MUTED
                            onBeat -> accent
                            else -> Accent.NORMAL
                        },
                    )
                )
            }
        }
    }

    companion object {
        /** The control range. Long-run timing is checked across 40-240. */
        val BPM_RANGE = 20..300

        /**
         * Bars of count-in. Eight is room to put the phone down and pick the
         * guitar up at a slow tempo; more is just waiting.
         */
        val COUNT_IN_RANGE = 0..8
    }
}
