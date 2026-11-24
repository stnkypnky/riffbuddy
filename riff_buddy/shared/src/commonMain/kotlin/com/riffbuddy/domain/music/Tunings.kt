package com.riffbuddy.domain.music

/** How a tuning is grouped in the picker. */
enum class TuningGroup(val label: String) {
    STANDARD("Standard and offsets"),
    DROP("Drop"),
    OPEN("Open"),
    MODAL("Modal and other"),
    EXTENDED("7- and 8-string"),
    CUSTOM("Yours"),
}

/**
 * A tuning, strings listed low to high.
 *
 * Stored as MIDI numbers rather than names so that transposing — which is all a
 * capo is — is arithmetic rather than string manipulation.
 */
data class Tuning(
    val name: String,
    val group: TuningGroup,
    val midi: List<Int>,
    val isCustom: Boolean = false,
    /** Row id in `tunings`; 0 until stored. Songs point at this. */
    val id: Long = 0,
) {
    val stringCount: Int get() = midi.size

    fun noteNames(): List<String> = midi.map { Notes.name(it) }

    fun frequencies(a4: Double = Notes.DEFAULT_A4): List<Double> =
        midi.map { Notes.frequencyOf(it, a4) }

    /**
     * A capo shifts every target up by its fret number.
     *
     * The capo belongs here rather than in the tuner's display because a capoed
     * guitar genuinely *is* in a different tuning — showing E2 while the string
     * sounds F#2 would be a tuner that disagrees with the instrument.
     */
    fun withCapo(fret: Int): Tuning =
        if (fret <= 0) this
        else copy(name = "$name, capo $fret", midi = midi.map { it + fret })

    /** As stored: note names, low to high, space-separated — readable in a backup. */
    fun storedStrings(): String = midi.joinToString(" ") { Notes.name(it) }

    companion object {
        /** The custom-tuning editor allows six to eight strings. */
        val STRING_COUNTS = 6..8

        /** Parses [storedStrings]; null if any string is unreadable. */
        fun parseStrings(text: String): List<Int>? {
            val parts = text.trim().split(Regex("\\s+"))
            val midi = parts.map { Notes.parse(it) ?: return null }
            return midi.takeIf { it.isNotEmpty() }
        }

        private fun of(name: String, group: TuningGroup, strings: String) = Tuning(
            name = name,
            group = group,
            midi = strings.split(" ").mapNotNull { Notes.parse(it) },
        )

        /** The built-in tunings. Verified against interval math by TuningsTest. */
        val BUILT_IN: List<Tuning> = listOf(
            of("Standard", TuningGroup.STANDARD, "E2 A2 D3 G3 B3 E4"),
            of("Half step down (Eb)", TuningGroup.STANDARD, "Eb2 Ab2 Db3 Gb3 Bb3 Eb4"),
            of("Whole step down (D)", TuningGroup.STANDARD, "D2 G2 C3 F3 A3 D4"),
            of("One and a half steps down (C#)", TuningGroup.STANDARD, "C#2 F#2 B2 E3 G#3 C#4"),
            of("Two steps down (C)", TuningGroup.STANDARD, "C2 F2 Bb2 Eb3 G3 C4"),
            of("Baritone (B standard)", TuningGroup.STANDARD, "B1 E2 A2 D3 F#3 B3"),

            of("Drop D", TuningGroup.DROP, "D2 A2 D3 G3 B3 E4"),
            of("Drop C#", TuningGroup.DROP, "C#2 G#2 C#3 F#3 A#3 D#4"),
            of("Drop C", TuningGroup.DROP, "C2 G2 C3 F3 A3 D4"),
            of("Drop B", TuningGroup.DROP, "B1 F#2 B2 E3 G#3 C#4"),
            of("Drop A (baritone)", TuningGroup.DROP, "A1 E2 A2 D3 F#3 B3"),
            of("Double drop D", TuningGroup.DROP, "D2 A2 D3 G3 B3 D4"),

            of("Open D", TuningGroup.OPEN, "D2 A2 D3 F#3 A3 D4"),
            of("Open G", TuningGroup.OPEN, "D2 G2 D3 G3 B3 D4"),
            of("Open E", TuningGroup.OPEN, "E2 B2 E3 G#3 B3 E4"),
            of("Open A", TuningGroup.OPEN, "E2 A2 E3 A3 C#4 E4"),
            of("Open C", TuningGroup.OPEN, "C2 G2 C3 G3 C4 E4"),
            of("Open D minor", TuningGroup.OPEN, "D2 A2 D3 F3 A3 D4"),
            of("Open G minor", TuningGroup.OPEN, "D2 G2 D3 G3 Bb3 D4"),

            of("DADGAD", TuningGroup.MODAL, "D2 A2 D3 G3 A3 D4"),
            of("New Standard Tuning", TuningGroup.MODAL, "C2 G2 D3 A3 E4 G4"),
            of("Nashville (high-strung)", TuningGroup.MODAL, "E3 A3 D4 G4 B3 E4"),

            of("7-string standard (B)", TuningGroup.EXTENDED, "B1 E2 A2 D3 G3 B3 E4"),
            of("7-string drop A", TuningGroup.EXTENDED, "A1 E2 A2 D3 G3 B3 E4"),
            of("7-string half step down", TuningGroup.EXTENDED, "Bb1 Eb2 Ab2 Db3 Gb3 Bb3 Eb4"),
            of("7-string A standard", TuningGroup.EXTENDED, "A1 D2 G2 C3 F3 A3 D4"),
            of("8-string standard", TuningGroup.EXTENDED, "F#1 B1 E2 A2 D3 G3 B3 E4"),
        )

        val STANDARD: Tuning get() = BUILT_IN.first()

        fun byName(name: String): Tuning? = BUILT_IN.firstOrNull { it.name == name }
    }
}
