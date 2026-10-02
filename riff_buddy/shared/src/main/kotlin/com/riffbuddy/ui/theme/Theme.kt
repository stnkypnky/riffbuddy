package com.riffbuddy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * "Warm tube-amp": charcoal surfaces, glowing amber, tactile controls.
 *
 * The tokens themselves live in [Palette], as plain numbers with no Compose
 * types, so ContrastTest can check every pair on the JVM. Nothing here invents a
 * color; this file only assigns Palette's tokens to Material's roles.
 *
 * Three deliberate choices.
 *
 * Dynamic color is *not* used. It derives from the wallpaper, which would mean
 * every app on the phone rendering in the same palette. An app you open with a
 * guitar in your hands should be recognizable at arm's length on a music stand.
 *
 * Dark only — there is no light scheme to fall back to, so
 * isSystemInDarkTheme() is not consulted.
 *
 * Every color role is defined, even ones this app may never reach. An app that
 * sets only a few can look fine on a phone whose dynamic color quietly fills in
 * the rest, then show Material's purple baseline everywhere on a device
 * without it. With dynamic color off, an unset role has nothing to inherit from.
 */

private val AmpScheme = darkColorScheme(
    primary = Color(Palette.AMBER), onPrimary = Color(Palette.ON_AMBER),
    primaryContainer = Color(Palette.AMBER_CONTAINER),
    onPrimaryContainer = Color(Palette.ON_AMBER_CONTAINER),
    inversePrimary = Color(Palette.AMBER_DEEP),
    secondary = Color(Palette.TEXT_MUTED), onSecondary = Color(Palette.BG),
    secondaryContainer = Color(Palette.SURFACE_HIGH),
    onSecondaryContainer = Color(Palette.TEXT),
    tertiary = Color(Palette.SUCCESS), onTertiary = Color(0xFF0F2A0F),
    tertiaryContainer = Color(Palette.SUCCESS_CONTAINER),
    onTertiaryContainer = Color(Palette.ON_SUCCESS_CONTAINER),
    error = Color(Palette.DANGER), onError = Color(0xFF4A120A),
    errorContainer = Color(Palette.DANGER_CONTAINER),
    onErrorContainer = Color(Palette.ON_DANGER_CONTAINER),
    background = Color(Palette.BG), onBackground = Color(Palette.TEXT),
    // surface tracks the background; the container ramp is what cards and
    // sheets land on, and that is where the lifted tones do their work.
    surface = Color(Palette.BG), onSurface = Color(Palette.TEXT),
    surfaceVariant = Color(Palette.SURFACE_HIGH),
    onSurfaceVariant = Color(Palette.TEXT_MUTED),
    surfaceTint = Color(Palette.AMBER),
    inverseSurface = Color(Palette.TEXT), inverseOnSurface = Color(Palette.BG),
    outline = Color(Palette.OUTLINE), outlineVariant = Color(Palette.OUTLINE_QUIET),
    scrim = Color(0xFF000000),
    surfaceBright = Color(Palette.SURFACE_HIGHEST),
    surfaceDim = Color(Palette.SURFACE_LOWEST),
    surfaceContainerLowest = Color(Palette.SURFACE_LOWEST),
    surfaceContainerLow = Color(Palette.SURFACE_LOW),
    surfaceContainer = Color(Palette.SURFACE),
    surfaceContainerHigh = Color(Palette.SURFACE_HIGH),
    surfaceContainerHighest = Color(Palette.SURFACE_HIGHEST),
)

/*
 * An amp head is a box with softened corners, not a pill. Rounder than Record
 * Buddy's document look, squarer than Material's default — enough to feel
 * built rather than drawn.
 */
private val AmpShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/*
 * Big numerals are the signature element: BPM, cents offset, the countdown in
 * a speed-trainer ramp. Those want a condensed face so three digits stay huge
 * on a 360 dp screen.
 *
 * Each platform supplies its own condensed face through [LocalCondensedFont] —
 * on Android the device's own, present on every build, with nothing to
 * download and no license to track.
 */
val LocalCondensedFont = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }

private val base = Typography()

private fun ampTypography(condensed: FontFamily) = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = condensed, fontWeight = FontWeight.Medium),
    displayMedium = base.displayMedium.copy(fontFamily = condensed, fontWeight = FontWeight.Medium),
    displaySmall = base.displaySmall.copy(fontFamily = condensed, fontWeight = FontWeight.Medium),
    headlineMedium = base.headlineMedium.copy(fontFamily = condensed),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
    // Panel labels, the way an amp silkscreens GAIN or MASTER under a knob.
    labelMedium = base.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.0.sp,
        fontSize = 11.sp,
    ),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp, fontSize = 10.sp),
)

/** The tempo readout, the cents offset, a knob's value — read at a glance, from a stand. */
val BigNumeral: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = TextStyle(
        fontFamily = LocalCondensedFont.current,
        fontWeight = FontWeight.Medium,
        fontSize = 72.sp,
        lineHeight = 76.sp,
    )

/**
 * The few colors with a job Material3 has no role for.
 *
 * Each of these marks a *state*, and state is never carried by color alone —
 * so every one of them is paired with text or an icon at the call site.
 * The lamp glow is decoration and must respect the reduced-motion setting.
 */
object RiffColors {
    /** Metronome beat lamp and streak lamp, lit. */
    val lampLit = Color(Palette.AMBER_STRONG)
    /** The same lamp, dark. Visible as a shape so the beat has somewhere to land. */
    val lampDark = Color(Palette.LAMP_DARK)
    /** Tuner: within +/-3 cents, held. Always shown with the words "IN TUNE". */
    val inTune = Color(Palette.SUCCESS)
    /** Tuner: sharp or flat. Always shown with a direction arrow and the cents number. */
    val outOfTune = Color(Palette.DANGER)
    /** Amp faceplate: the engraved panel behind the knobs. */
    val faceplate = Color(Palette.SURFACE_HIGH)
    /** Amp faceplate: a knob's pointer and its value arc. */
    val knobPointer = Color(Palette.AMBER_STRONG)
}

@Composable
fun RiffBuddyTheme(
    condensedFont: FontFamily,
    systemReducedMotion: Boolean,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val typography = remember(condensedFont) { ampTypography(condensedFont) }
    CompositionLocalProvider(
        LocalReducedMotion provides (reducedMotion || systemReducedMotion),
        LocalCondensedFont provides condensedFont,
    ) {
        MaterialTheme(
            colorScheme = AmpScheme,
            typography = typography,
            shapes = AmpShapes,
            content = content,
        )
    }
}
