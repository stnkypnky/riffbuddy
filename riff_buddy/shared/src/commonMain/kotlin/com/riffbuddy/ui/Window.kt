package com.riffbuddy.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffbuddy.ui.theme.Space

/**
 * The width of the window the app is drawn in. On a phone that is the screen;
 * on a desktop it is whatever size the window has been dragged to, which is
 * what a layout should follow.
 */
@Composable
fun windowWidth(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/**
 * Three widths a layout changes at, rather than a different number in every
 * screen. The breakpoints are Material's window size classes, so a phone held
 * upright is compact, a small tablet or a phone on its side is medium, and a
 * big tablet in landscape or a desktop window is expanded.
 */
enum class WidthClass {
    /** Under 600 dp: one column, tabs along the bottom. */
    COMPACT,
    /** 600 to 840 dp: a rail of icons down the side, still one column. */
    MEDIUM,
    /** 840 dp and up: the rail with words, and room for two panes. */
    EXPANDED;

    val atLeastMedium: Boolean get() = this != COMPACT

    companion object {
        val MEDIUM_FROM = 600.dp
        val EXPANDED_FROM = 840.dp

        fun of(width: Dp): WidthClass = when {
            width < MEDIUM_FROM -> COMPACT
            width < EXPANDED_FROM -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** The window's [WidthClass], following it as a desktop window is resized. */
@Composable
fun widthClass(): WidthClass = WidthClass.of(windowWidth())

/** Padding at the edge of the content: a phone has no width to spare, a bigger screen looks cramped without it. */
@Composable
fun screenPadding(): Dp = if (widthClass() == WidthClass.COMPACT) Space.screen else Space.screenWide
