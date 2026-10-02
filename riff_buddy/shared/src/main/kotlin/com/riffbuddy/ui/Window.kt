package com.riffbuddy.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp

/**
 * The width of the window the app is drawn in. On a phone that is the screen;
 * on a desktop it is whatever size the window has been dragged to, which is
 * what a layout should follow.
 */
@Composable
fun windowWidth(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/** The person's language and region, for day and month names. */
@Composable
fun currentLocale(): java.util.Locale = java.util.Locale.forLanguageTag(Locale.current.toLanguageTag())
