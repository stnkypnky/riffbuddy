package com.riffbuddy.ui.theme

/**
 * The color tokens, as plain ARGB numbers with no Compose types, so
 * `ContrastTest` can check every one of them on the JVM: every token pair used
 * for text is checked automatically. [Theme] turns
 * these into Compose `Color`s and nothing else should read them.
 *
 * Still "warm tube-amp", but lifted: an earlier ramp put the
 * background at #1B1A19 and cards at #262422, a contrast of 1.12, which meant a
 * card was not visibly a card — the app read as one flat dark rectangle with a
 * single amber pill in it. Everything here is warmer (more red and yellow in the
 * neutrals) and the steps are further apart, so a card reads as a lit panel.
 * Cards now sit at 1.40 against the background.
 *
 * Every value was picked against the contrast numbers rather than by eye, and
 * the test is what keeps it that way.
 */
internal object Palette {

    // Neutrals — the ramp from the room to the brightest raised surface.
    const val BG = 0xFF241F1BL
    const val SURFACE_LOWEST = 0xFF1C1815L
    const val SURFACE_LOW = 0xFF332C26L
    const val SURFACE = 0xFF40372EL
    const val SURFACE_HIGH = 0xFF4B4137L
    const val SURFACE_HIGHEST = 0xFF564A3EL

    // Text.
    const val TEXT = 0xFFF7F0E4L
    const val TEXT_MUTED = 0xFFCBBFACL

    // The accent, and the glow it comes from.
    const val AMBER = 0xFFFFB43DL
    const val AMBER_STRONG = 0xFFFFD07AL
    const val AMBER_DEEP = 0xFFE8920BL
    const val ON_AMBER = 0xFF241F1BL
    const val AMBER_CONTAINER = 0xFF6B4407L
    const val ON_AMBER_CONTAINER = 0xFFFFD07AL

    // In tune, done.
    const val SUCCESS = 0xFF9EDD9EL
    const val SUCCESS_CONTAINER = 0xFF355435L
    const val ON_SUCCESS_CONTAINER = 0xFFD2F0D2L

    // Out of tune, errors. Bright enough to stay legible on the highest
    // surface, which is where a message in a raised sheet lands.
    const val DANGER = 0xFFFFA89BL
    const val DANGER_CONTAINER = 0xFF6B2820L
    const val ON_DANGER_CONTAINER = 0xFFFFDAD4L

    // Edges. OUTLINE is for lines that must be seen; OUTLINE_QUIET for dividers.
    const val OUTLINE = 0xFFA1937FL
    const val OUTLINE_QUIET = 0xFF5C5145L

    // The metronome and streak lamp, unlit. A lamp that is off still has to be
    // visible as a lamp, or the beat has nowhere to land — and it has to work
    // both on a card (the streak lamp) and on the bare background (the
    // metronome). Dim brass rather than dark amber: anything dark enough to
    // read as "off" against a card disappeared entirely against the background.
    const val LAMP_DARK = 0xFF7D5F27L

    /** Surfaces that text and accents are expected to sit on. Used by the test. */
    val SURFACES = listOf(BG, SURFACE_LOWEST, SURFACE_LOW, SURFACE, SURFACE_HIGH, SURFACE_HIGHEST)

    /** Foregrounds that must stay readable on every one of [SURFACES]. */
    val FOREGROUNDS = listOf(TEXT, TEXT_MUTED, AMBER, SUCCESS, DANGER)
}
