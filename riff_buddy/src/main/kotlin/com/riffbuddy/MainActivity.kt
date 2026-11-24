package com.riffbuddy

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.riffbuddy.platform.AndroidPlatform
import com.riffbuddy.platform.AndroidUi
import com.riffbuddy.platform.LocalPlatform
import com.riffbuddy.ui.Intro
import com.riffbuddy.ui.RiffBuddyContent
import com.riffbuddy.ui.settings.SyncHost
import com.riffbuddy.ui.settings.SyncSection
import com.riffbuddy.ui.theme.Finish
import com.riffbuddy.ui.theme.RiffBuddyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Off the launch theme (the splash) before anything draws.
        setTheme(R.style.Theme_RiffBuddy)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as RiffBuddyApp
        val platform = AndroidPlatform(this, app)

        val condensed = AndroidUi.condensedFont(assets)
        val text = AndroidUi.textFont(assets)
        setContent {
            RiffBuddyTheme(
                condensedFont = condensed,
                textFont = text,
                systemReducedMotion = AndroidUi.animationsDisabled(LocalContext.current),
            ) {
                CompositionLocalProvider(LocalPlatform provides platform) {
                    // The intro over the app while it loads, continuing the system
                    // splash; Android-only.
                    RiffBuddyContent(
                        app.services,
                        syncSection = { SyncSection(app.sync) },
                        syncHost = { SyncHost(app.sync) },
                        dataChanges = { app.sync.dataVersion },
                        overlay = { Intro() },
                        onFinish = ::followFinish,
                    )
                }
            }
        }
    }

    /**
     * The status and navigation bar icons follow the finish, not the system's
     * dark mode: white icons on Maple or Olympic White would vanish.
     * enableEdgeToEdge may be called again at any time. The navigation bar
     * scrims are androidx's own defaults; the dark one is also what a light
     * finish falls back to on a version too old to draw dark buttons.
     */
    private fun followFinish(finish: Finish) {
        val lightScrim = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val darkScrim = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
        enableEdgeToEdge(
            statusBarStyle = if (finish.dark) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (finish.dark) SystemBarStyle.dark(darkScrim)
            else SystemBarStyle.light(lightScrim, darkScrim),
        )
    }
}
