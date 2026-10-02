package com.riffbuddy.platform

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

/**
 * The Android half of the theme: what the shared [com.riffbuddy.ui.theme.RiffBuddyTheme]
 * is handed instead of asking the platform itself.
 */
object AndroidUi {

    /**
     * The device's own condensed face for the big numerals — present on every
     * Android build, no download, no license to track.
     */
    val condensedFont = FontFamily(Font(DeviceFontFamilyName("sans-serif-condensed")))

    /**
     * True when "Remove animations" is on: Android's accessibility setting
     * sets the animator duration scale to zero.
     */
    fun animationsDisabled(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
}
