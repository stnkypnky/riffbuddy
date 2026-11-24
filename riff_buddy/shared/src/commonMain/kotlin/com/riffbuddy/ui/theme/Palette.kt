package com.riffbuddy.ui.theme

/**
 * One color scheme, named after a guitar finish or a tonewood. Plain ARGB
 * numbers with no Compose types, so `ContrastTest` can check every finish on the
 * JVM: every token pair used for text is checked automatically, for every
 * finish. [Theme] turns these into Compose `Color`s and nothing else should
 * read them.
 *
 * The roles are the same in a dark and a light finish; only the direction
 * changes. [surface] is a card and must sit visibly off [bg] (a card that is
 * not visibly a card makes the whole screen read as one flat rectangle).
 * [accent] is the color with a job: selected chips, the primary button, the
 * section bars. [accentStrong] is a lamp lit or a knob's pointer, and
 * [lampDark] the same lamp off, which has to stay visible as a lamp on both a
 * card and the bare background, or the beat has nowhere to land.
 *
 * Every value was picked against the contrast numbers rather than by eye, and
 * the test is what keeps it that way.
 */
class Finish(
    /** Stored in settings. Never rename one, or a saved choice falls back to the default. */
    val id: String,
    val label: String,
    val dark: Boolean,
    /** The finish itself, for the picker: the body color and the edge of the burst. */
    val swatch: Long,
    val swatchEdge: Long,
    // Neutrals — the ramp from the room to the most raised surface.
    val bg: Long,
    val surfaceLowest: Long,
    val surfaceLow: Long,
    val surface: Long,
    val surfaceHigh: Long,
    val surfaceHighest: Long,
    // Text.
    val text: Long,
    val textMuted: Long,
    // The accent.
    val accent: Long,
    val accentStrong: Long,
    val accentDeep: Long,
    val onAccent: Long,
    val accentContainer: Long,
    val onAccentContainer: Long,
    // In tune, done.
    val success: Long,
    val successContainer: Long,
    val onSuccessContainer: Long,
    // Out of tune, errors. Legible on the highest surface, which is where a
    // message in a raised sheet lands.
    val danger: Long,
    val dangerContainer: Long,
    val onDangerContainer: Long,
    // Edges. outline is for lines that must be seen; outlineQuiet for dividers.
    val outline: Long,
    val outlineQuiet: Long,
    val lampDark: Long,
    val onSuccess: Long,
    val onDanger: Long,
) {
    /** Surfaces that text and accents are expected to sit on. Used by the test. */
    val surfaces get() = listOf(bg, surfaceLowest, surfaceLow, surface, surfaceHigh, surfaceHighest)

    /** Foregrounds that must stay readable on every one of [surfaces]. */
    val foregrounds get() = listOf(text, textMuted, accent, success, danger)
}

/**
 * The finishes on offer. Sunburst is the original warm tube-amp look and the
 * default, so an install that never opens the picker looks as it always did.
 */
object Finishes {

    val SUNBURST = Finish(
        id = "sunburst", label = "Sunburst", dark = true,
        swatch = 0xFFE8920BL, swatchEdge = 0xFF3A1E0EL,
        bg = 0xFF241F1BL,
        surfaceLowest = 0xFF1C1815L,
        surfaceLow = 0xFF332C26L,
        surface = 0xFF40372EL,
        surfaceHigh = 0xFF4B4137L,
        surfaceHighest = 0xFF564A3EL,
        text = 0xFFF7F0E4L,
        textMuted = 0xFFCBBFACL,
        accent = 0xFFFFB43DL,
        accentStrong = 0xFFFFD07AL,
        accentDeep = 0xFFE8920BL,
        onAccent = 0xFF241F1BL,
        accentContainer = 0xFF6B4407L,
        onAccentContainer = 0xFFFFD07AL,
        success = 0xFF9EDD9EL,
        successContainer = 0xFF355435L,
        onSuccessContainer = 0xFFD2F0D2L,
        danger = 0xFFFFA89BL,
        dangerContainer = 0xFF6B2820L,
        onDangerContainer = 0xFFFFDAD4L,
        outline = 0xFFA1937FL,
        outlineQuiet = 0xFF5C5145L,
        lampDark = 0xFF7D5F27L,
        onSuccess = 0xFF0F2A0FL,
        onDanger = 0xFF4A120AL,
    )

    val CHERRY = Finish(
        id = "cherry", label = "Cherry", dark = true,
        swatch = 0xFFB3202AL, swatchEdge = 0xFF4A0E12L,
        bg = 0xFF231819L,
        surfaceLowest = 0xFF1A1112L,
        surfaceLow = 0xFF342324L,
        surface = 0xFF432D2FL,
        surfaceHigh = 0xFF4F3638L,
        surfaceHighest = 0xFF5B3F41L,
        text = 0xFFF8ECEAL,
        textMuted = 0xFFD2BDBAL,
        accent = 0xFFFF9C94L,
        accentStrong = 0xFFFFC0B8L,
        accentDeep = 0xFFE0574FL,
        onAccent = 0xFF2A1213L,
        accentContainer = 0xFF6E1F1CL,
        onAccentContainer = 0xFFFFD3CEL,
        success = 0xFFA6DDA0L,
        successContainer = 0xFF34522FL,
        onSuccessContainer = 0xFFD6F0D0L,
        danger = 0xFFFFB37AL,
        dangerContainer = 0xFF6A3512L,
        onDangerContainer = 0xFFFFE0C9L,
        outline = 0xFFA88D8AL,
        outlineQuiet = 0xFF604849L,
        lampDark = 0xFF7E4440L,
        onSuccess = 0xFF0F2A0FL,
        onDanger = 0xFF4A1E05L,
    )

    val ROSEWOOD = Finish(
        id = "rosewood", label = "Rosewood", dark = true,
        swatch = 0xFF6B3B2CL, swatchEdge = 0xFF2A1612L,
        bg = 0xFF221A1DL,
        surfaceLowest = 0xFF1A1316L,
        surfaceLow = 0xFF33272BL,
        surface = 0xFF413238L,
        surfaceHigh = 0xFF4C3B42L,
        surfaceHighest = 0xFF58444CL,
        text = 0xFFF6EEF0L,
        textMuted = 0xFFCDBEC4L,
        accent = 0xFFF2AE8CL,
        accentStrong = 0xFFFFD3BCL,
        accentDeep = 0xFFCC7F5CL,
        onAccent = 0xFF2A1A1DL,
        accentContainer = 0xFF5E3424L,
        onAccentContainer = 0xFFFFD7C4L,
        success = 0xFFA0DCA6L,
        successContainer = 0xFF335238L,
        onSuccessContainer = 0xFFD3F0D6L,
        danger = 0xFFFFA8A0L,
        dangerContainer = 0xFF6B2A26L,
        onDangerContainer = 0xFFFFDAD6L,
        outline = 0xFFA69299L,
        outlineQuiet = 0xFF5E4C53L,
        lampDark = 0xFF7A5546L,
        onSuccess = 0xFF0F2A12L,
        onDanger = 0xFF4A140FL,
    )

    val EBONY = Finish(
        id = "ebony", label = "Ebony", dark = true,
        swatch = 0xFF2A2A2EL, swatchEdge = 0xFF0C0C0EL,
        bg = 0xFF161618L,
        surfaceLowest = 0xFF0F0F11L,
        surfaceLow = 0xFF222225L,
        surface = 0xFF2E2E32L,
        surfaceHigh = 0xFF38383DL,
        surfaceHighest = 0xFF434349L,
        text = 0xFFF2F2F4L,
        textMuted = 0xFFC2C3C8L,
        accent = 0xFFD8DEE8L,
        accentStrong = 0xFFF4F7FBL,
        accentDeep = 0xFFA9B3C2L,
        onAccent = 0xFF18181BL,
        accentContainer = 0xFF3E4552L,
        onAccentContainer = 0xFFE3E9F2L,
        success = 0xFF9EDDA8L,
        successContainer = 0xFF2F4D35L,
        onSuccessContainer = 0xFFD2F0D8L,
        danger = 0xFFFFA89BL,
        dangerContainer = 0xFF6B2820L,
        onDangerContainer = 0xFFFFDAD4L,
        outline = 0xFF9A9BA3L,
        outlineQuiet = 0xFF4F4F56L,
        lampDark = 0xFF5E6168L,
        onSuccess = 0xFF0F2A12L,
        onDanger = 0xFF4A120AL,
    )

    val MAHOGANY = Finish(
        id = "mahogany", label = "Mahogany", dark = true,
        swatch = 0xFF8A3A1CL, swatchEdge = 0xFF3A160AL,
        bg = 0xFF26180FL,
        surfaceLowest = 0xFF1D120BL,
        surfaceLow = 0xFF382519L,
        surface = 0xFF47301FL,
        surfaceHigh = 0xFF533926L,
        surfaceHighest = 0xFF5F422DL,
        text = 0xFFF8EDE3L,
        textMuted = 0xFFD3BCA8L,
        accent = 0xFFFFA866L,
        accentStrong = 0xFFFFC795L,
        accentDeep = 0xFFDA7424L,
        onAccent = 0xFF2A170AL,
        accentContainer = 0xFF6E3910L,
        onAccentContainer = 0xFFFFD6B5L,
        success = 0xFFA4DD9EL,
        successContainer = 0xFF345234L,
        onSuccessContainer = 0xFFD4F0D0L,
        danger = 0xFFFFA8A8L,
        dangerContainer = 0xFF6B2828L,
        onDangerContainer = 0xFFFFDADAL,
        outline = 0xFFAA9180L,
        outlineQuiet = 0xFF634A39L,
        lampDark = 0xFF815530L,
        onSuccess = 0xFF0F2A0FL,
        onDanger = 0xFF4A1010L,
    )

    val SURF_GREEN = Finish(
        id = "surf_green", label = "Surf Green", dark = true,
        swatch = 0xFF8FD3B8L, swatchEdge = 0xFF3E8C74L,
        bg = 0xFF13201EL,
        surfaceLowest = 0xFF0D1817L,
        surfaceLow = 0xFF1E302DL,
        surface = 0xFF283E3AL,
        surfaceHigh = 0xFF314843L,
        surfaceHighest = 0xFF3A534EL,
        text = 0xFFE9F5F1L,
        textMuted = 0xFFB7CFC8L,
        accent = 0xFF7FE3C6L,
        accentStrong = 0xFFB8F4E2L,
        accentDeep = 0xFF3FBF9CL,
        onAccent = 0xFF0F2420L,
        accentContainer = 0xFF1A5246L,
        onAccentContainer = 0xFFC9F7EAL,
        success = 0xFFC8E89AL,
        successContainer = 0xFF3C5226L,
        onSuccessContainer = 0xFFE2F4C9L,
        danger = 0xFFFFAD9EL,
        dangerContainer = 0xFF6B2A20L,
        onDangerContainer = 0xFFFFDBD3L,
        outline = 0xFF87A49DL,
        outlineQuiet = 0xFF435A55L,
        lampDark = 0xFF3C6E61L,
        onSuccess = 0xFF1E2A08L,
        onDanger = 0xFF4A140AL,
    )

    val LAKE_PLACID_BLUE = Finish(
        id = "lake_placid_blue", label = "Lake Placid Blue", dark = true,
        swatch = 0xFF4C7FCCL, swatchEdge = 0xFF1E3A66L,
        bg = 0xFF141B26L,
        surfaceLowest = 0xFF0E141DL,
        surfaceLow = 0xFF1F2A39L,
        surface = 0xFF293647L,
        surfaceHigh = 0xFF314054L,
        surfaceHighest = 0xFF3A4B61L,
        text = 0xFFEDF2FAL,
        textMuted = 0xFFBAC6D8L,
        accent = 0xFF8EC2FFL,
        accentStrong = 0xFFC3DEFFL,
        accentDeep = 0xFF4F93E8L,
        onAccent = 0xFF0F1A2AL,
        accentContainer = 0xFF1C3E6EL,
        onAccentContainer = 0xFFCFE3FFL,
        success = 0xFF9EDDA8L,
        successContainer = 0xFF2F4D35L,
        onSuccessContainer = 0xFFD2F0D8L,
        danger = 0xFFFFAD9EL,
        dangerContainer = 0xFF6B2A20L,
        onDangerContainer = 0xFFFFDBD3L,
        outline = 0xFF8C9AAEL,
        outlineQuiet = 0xFF46546AL,
        lampDark = 0xFF3D5F8CL,
        onSuccess = 0xFF0F2A12L,
        onDanger = 0xFF4A140AL,
    )

    val MAPLE = Finish(
        id = "maple", label = "Maple", dark = false,
        swatch = 0xFFF0D29AL, swatchEdge = 0xFFC8984EL,
        bg = 0xFFE6D6B8L,
        surfaceLowest = 0xFFFFFDF8L,
        surfaceLow = 0xFFF6ECDAL,
        surface = 0xFFFFF9EFL,
        surfaceHigh = 0xFFF3E7D3L,
        surfaceHighest = 0xFFEADBC2L,
        text = 0xFF2B2119L,
        textMuted = 0xFF5C4A39L,
        accent = 0xFF8A4800L,
        accentStrong = 0xFF9A5200L,
        accentDeep = 0xFF6E3900L,
        onAccent = 0xFFFFFFFFL,
        accentContainer = 0xFFF7D9A8L,
        onAccentContainer = 0xFF4A2800L,
        success = 0xFF2A6330L,
        successContainer = 0xFFCFE8C8L,
        onSuccessContainer = 0xFF133A16L,
        danger = 0xFFA3291DL,
        dangerContainer = 0xFFF8D3CCL,
        onDangerContainer = 0xFF5E130BL,
        outline = 0xFF877563L,
        outlineQuiet = 0xFFCDBBA0L,
        lampDark = 0xFFC2A57AL,
        onSuccess = 0xFFFFFFFFL,
        onDanger = 0xFFFFFFFFL,
    )

    val OLYMPIC_WHITE = Finish(
        id = "olympic_white", label = "Olympic White", dark = false,
        swatch = 0xFFF4F0E4L, swatchEdge = 0xFFC9C2B0L,
        bg = 0xFFDEDAD1L,
        surfaceLowest = 0xFFFFFFFFL,
        surfaceLow = 0xFFF5F4F0L,
        surface = 0xFFFFFFFFL,
        surfaceHigh = 0xFFF1EFEAL,
        surfaceHighest = 0xFFE6E3DCL,
        text = 0xFF1F1E1CL,
        textMuted = 0xFF4F4C47L,
        accent = 0xFF8A3A10L,
        accentStrong = 0xFF7A3000L,
        accentDeep = 0xFF5E2408L,
        onAccent = 0xFFFFFFFFL,
        accentContainer = 0xFFF2D3BEL,
        onAccentContainer = 0xFF4A1C05L,
        success = 0xFF2B6A36L,
        successContainer = 0xFFCDE8CFL,
        onSuccessContainer = 0xFF0F3A16L,
        danger = 0xFFA3291DL,
        dangerContainer = 0xFFF8D3CCL,
        onDangerContainer = 0xFF5E130BL,
        outline = 0xFF7D7972L,
        outlineQuiet = 0xFFC8C4BBL,
        lampDark = 0xFFB9B3A8L,
        onSuccess = 0xFFFFFFFFL,
        onDanger = 0xFFFFFFFFL,
    )

    val BUTTERSCOTCH = Finish(
        id = "butterscotch", label = "Butterscotch", dark = false,
        swatch = 0xFFEDBE6AL, swatchEdge = 0xFFB07A2AL,
        bg = 0xFFEED7A6L,
        surfaceLowest = 0xFFFFF9EAL,
        surfaceLow = 0xFFF8EACBL,
        surface = 0xFFFFF6E0L,
        surfaceHigh = 0xFFF4E3BDL,
        surfaceHighest = 0xFFEAD5A6L,
        text = 0xFF2A1E10L,
        textMuted = 0xFF5A4524L,
        accent = 0xFF7A3B00L,
        accentStrong = 0xFF8A4500L,
        accentDeep = 0xFF5C2B00L,
        onAccent = 0xFFFFFFFFL,
        accentContainer = 0xFFF3CF92L,
        onAccentContainer = 0xFF3F1E00L,
        success = 0xFF2A6330L,
        successContainer = 0xFFCFE8C8L,
        onSuccessContainer = 0xFF133A16L,
        danger = 0xFFA3291DL,
        dangerContainer = 0xFFF8D3CCL,
        onDangerContainer = 0xFF5E130BL,
        outline = 0xFF806A45L,
        outlineQuiet = 0xFFC9AF7CL,
        lampDark = 0xFFC4A46CL,
        onSuccess = 0xFFFFFFFFL,
        onDanger = 0xFFFFFFFFL,
    )

    val ALL = listOf(
        SUNBURST, CHERRY, ROSEWOOD, EBONY, MAHOGANY, SURF_GREEN, LAKE_PLACID_BLUE,
        MAPLE, OLYMPIC_WHITE, BUTTERSCOTCH,
    )

    val DEFAULT = SUNBURST

    fun byId(id: String?): Finish = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
