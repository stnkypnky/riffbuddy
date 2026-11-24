package com.riffbuddy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.riffbuddy.platform.Volatile

/**
 * Once per process: a cold start plays the intro, coming back from the
 * background doesn't (the process is still alive, so this is still true).
 * A platform with no intro simply never sets [showing].
 */
object IntroGate {
    @Volatile var played = false

    /**
     * True while the intro is on screen. Celebrations wait for it: a milestone
     * found at launch would otherwise be bannered *under* the intro, time out
     * unseen, and be marked celebrated anyway.
     */
    var showing by mutableStateOf(false)
}
