package com.riffbuddy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
 * Charcoal-and-amber by default, with a choice of finishes: each one a guitar
 * body or a tonewood, dark or light (see [Finishes]).
 *
 * The tokens themselves live in [Finish], as plain numbers with no Compose
 * types, so ContrastTest can check every pair of every finish on the JVM.
 * Nothing here invents a color; this file only assigns a finish's tokens to
 * Material's roles.
 *
 * Two deliberate choices.
 *
 * Dynamic color is *not* used. It derives from the wallpaper, which would mean
 * every app on the phone rendering in the same palette. An app you open with a
 * guitar in your hands should be recognizable at arm's length on a music stand,
 * so the player picks a finish rather than the system picking for them, and
 * isSystemInDarkTheme() is not consulted either.
 *
 * Every color role is defined, even ones this app may never reach. An app that
 * sets only a few can look fine on a phone whose dynamic color quietly fills in
 * the rest, then show Material's purple baseline everywhere on a device
 * without it. With dynamic color off, an unset role has nothing to inherit from.
 */

/** The finish on screen. Read through [RiffColors] and MaterialTheme, not directly. */
val LocalFinish = staticCompositionLocalOf { Finishes.DEFAULT }

/** Material's roles for a finish. The same mapping serves dark and light. */
fun Finish.colorScheme(): ColorScheme {
    val c = { v: Long -> Color(v) }
    val roles: ColorScheme = if (dark) darkColorScheme() else lightColorScheme()
    return roles.copy(
        primary = c(accent), onPrimary = c(onAccent),
        primaryContainer = c(accentContainer), onPrimaryContainer = c(onAccentContainer),
        inversePrimary = c(accentDeep),
        secondary = c(textMuted), onSecondary = c(bg),
        secondaryContainer = c(surfaceHigh), onSecondaryContainer = c(text),
        tertiary = c(success), onTertiary = c(onSuccess),
        tertiaryContainer = c(successContainer), onTertiaryContainer = c(onSuccessContainer),
        error = c(danger), onError = c(onDanger),
        errorContainer = c(dangerContainer), onErrorContainer = c(onDangerContainer),
        background = c(bg), onBackground = c(text),
        // surface tracks the background; the container ramp is what cards and
        // sheets land on, and that is where the raised tones do their work.
        surface = c(bg), onSurface = c(text),
        surfaceVariant = c(surfaceHigh), onSurfaceVariant = c(textMuted),
        surfaceTint = c(accent),
        inverseSurface = c(text), inverseOnSurface = c(bg),
        outline = c(outline), outlineVariant = c(outlineQuiet),
        scrim = Color.Black,
        surfaceBright = c(surfaceHighest), surfaceDim = c(surfaceLowest),
        surfaceContainerLowest = c(surfaceLowest),
        surfaceContainerLow = c(surfaceLow),
        surfaceContainer = c(surface),
        surfaceContainerHigh = c(surfaceHigh),
        surfaceContainerHighest = c(surfaceHighest),
    )
}

/*
 * An amp head is a box with softened corners, not a pill. Rounder than a
 * document app, squarer than Material's default — enough to feel built rather
 * than drawn.
 */
internal val AmpShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)

/*
 * Two faces. Text is a clean humanist sans, legible small on a phone at arm's
 * length. Big numerals and page titles are a condensed face, so three digits of
 * tempo stay huge on a 360 dp screen and a title reads like the silkscreen on an
 * amp's panel.
 *
 * Each platform supplies both through [RiffBuddyTheme]: the same bundled files
 * everywhere, so the app reads the same on a phone, a computer and in a browser.
 */
val LocalCondensedFont = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }

private fun ampTypography(text: FontFamily, condensed: FontFamily): Typography {
    val base = Typography()
    fun TextStyle.t() = copy(fontFamily = text)
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = condensed, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        displayMedium = base.displayMedium.copy(fontFamily = condensed, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        displaySmall = base.displaySmall.copy(fontFamily = condensed, fontWeight = FontWeight.SemiBold),
        // Page titles: the condensed face, upright and a touch heavier, like a panel's name.
        headlineLarge = base.headlineLarge.copy(fontFamily = condensed, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = condensed, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = text, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.copy(fontFamily = text, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.3).sp),
        titleMedium = base.titleMedium.copy(fontFamily = text, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        titleSmall = base.titleSmall.t().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.t().copy(lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.t().copy(lineHeight = 20.sp),
        bodySmall = base.bodySmall.t().copy(lineHeight = 17.sp),
        labelLarge = base.labelLarge.t().copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        // Panel labels, the way an amp silkscreens GAIN or MASTER under a knob.
        labelMedium = base.labelMedium.t().copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.0.sp, fontSize = 11.sp),
        labelSmall = base.labelSmall.t().copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp, fontSize = 10.sp),
    )
}

/** The tempo readout, the cents offset, a knob's value — read at a glance, from a stand. */
val BigNumeral: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = TextStyle(
        fontFamily = LocalCondensedFont.current,
        fontWeight = FontWeight.SemiBold,
        fontSize = 80.sp,
        lineHeight = 84.sp,
        letterSpacing = (-1).sp,
    )

/**
 * The few colors with a job Material3 has no role for, from the finish on
 * screen.
 *
 * Each of these marks a *state*, and state is never carried by color alone —
 * so every one of them is paired with text or an icon at the call site.
 * The lamp glow is decoration and must respect the reduced-motion setting.
 */
object RiffColors {
    /** Metronome beat lamp and streak lamp, lit. */
    val lampLit: Color @Composable @ReadOnlyComposable get() = Color(LocalFinish.current.accentStrong)
    /** The same lamp, dark. Visible as a shape so the beat has somewhere to land. */
    val lampDark: Color @Composable @ReadOnlyComposable get() = Color(LocalFinish.current.lampDark)
    /** Tuner: within +/-3 cents, held. Always shown with the words "IN TUNE". */
    val inTune: Color @Composable @ReadOnlyComposable get() = Color(LocalFinish.current.success)
    /** Tuner: sharp or flat. Always shown with a direction arrow and the cents number. */
    val outOfTune: Color @Composable @ReadOnlyComposable get() = Color(LocalFinish.current.danger)
    /** Amp faceplate: the engraved panel behind the knobs. */
    val faceplate: Color @Composable @ReadOnlyComposable get() = Color(LocalFinish.current.surfaceHigh)
    /** Amp faceplate: a knob's pointer and its value arc. */
    val knobPointer: Color @Composable @ReadOnlyComposable get() = Color(LocalFinish.current.accentStrong)
    /** The finish itself, for swatches and the soft glow behind a hero card. */
    val finishBody: Color @Composable @ReadOnlyComposable get() = Color(LocalFinish.current.swatch)
    val finishEdge: Color @Composable @ReadOnlyComposable get() = Color(LocalFinish.current.swatchEdge)
    /** True for a light finish: shadows show, and glows should be softer. */
    val isLight: Boolean @Composable @ReadOnlyComposable get() = !LocalFinish.current.dark
}

@Composable
fun RiffBuddyTheme(
    condensedFont: FontFamily,
    systemReducedMotion: Boolean,
    textFont: FontFamily = FontFamily.Default,
    reducedMotion: Boolean = false,
    finish: Finish = Finishes.DEFAULT,
    content: @Composable () -> Unit,
) {
    val typography = remember(condensedFont, textFont) { ampTypography(textFont, condensedFont) }
    CompositionLocalProvider(
        LocalReducedMotion provides (reducedMotion || systemReducedMotion),
        LocalCondensedFont provides condensedFont,
    ) {
        FinishTheme(finish) {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme,
                typography = typography,
                shapes = AmpShapes,
                content = content,
            )
        }
    }
}

/**
 * Switches the finish for everything inside, keeping the type and shapes. The
 * app's root uses it so a new choice in Settings shows at once, without the
 * platform having to know about it.
 */
@Composable
fun FinishTheme(finish: Finish, content: @Composable () -> Unit) {
    val scheme = remember(finish) { finish.colorScheme() }
    CompositionLocalProvider(LocalFinish provides finish) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}
