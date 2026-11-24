package com.riffbuddy.platform

import android.content.Context
import android.provider.Settings
import android.content.res.AssetManager
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily

/**
 * The Android half of the theme: what the shared [com.riffbuddy.ui.theme.RiffBuddyTheme]
 * is handed instead of asking the platform itself.
 */
object AndroidUi {

    /**
     * The bundled faces, the same files the desktop and web editions use, so the
     * app reads the same everywhere (a design note, closing a design note): Barlow Condensed
     * for big numerals and titles, Inter for text. Loaded from assets, which
     * every edition already shares.
     */
    fun condensedFont(assets: AssetManager) = FontFamily(
        Font("fonts/BarlowCondensed-Medium.ttf", assets, FontWeight.Normal),
        Font("fonts/BarlowCondensed-Medium.ttf", assets, FontWeight.Medium),
        Font("fonts/BarlowCondensed-SemiBold.ttf", assets, FontWeight.SemiBold),
        Font("fonts/BarlowCondensed-Bold.ttf", assets, FontWeight.Bold),
    )

    fun textFont(assets: AssetManager) = FontFamily(
        Font("fonts/Inter-Regular.ttf", assets, FontWeight.Normal),
        Font("fonts/Inter-Medium.ttf", assets, FontWeight.Medium),
        Font("fonts/Inter-SemiBold.ttf", assets, FontWeight.SemiBold),
        Font("fonts/Inter-SemiBold.ttf", assets, FontWeight.Bold),
    )

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
