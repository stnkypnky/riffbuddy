package com.riffbuddy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import com.riffbuddy.platform.AndroidPlatform
import com.riffbuddy.platform.AndroidUi
import com.riffbuddy.platform.LocalPlatform
import com.riffbuddy.ui.Intro
import com.riffbuddy.ui.RiffBuddyContent
import com.riffbuddy.ui.theme.RiffBuddyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Off the launch theme (the splash) before anything draws.
        setTheme(R.style.Theme_RiffBuddy)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as RiffBuddyApp
        val platform = AndroidPlatform(this, app)

        setContent {
            RiffBuddyTheme(
                condensedFont = AndroidUi.condensedFont,
                systemReducedMotion = AndroidUi.animationsDisabled(LocalContext.current),
            ) {
                CompositionLocalProvider(LocalPlatform provides platform) {
                    // The intro over the app while it loads, continuing the system
                    // splash; Android-only.
                    RiffBuddyContent(app.services, overlay = { Intro() })
                }
            }
        }
    }
}
