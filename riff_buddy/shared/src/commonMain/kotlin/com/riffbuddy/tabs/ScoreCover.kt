package com.riffbuddy.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * How many dialogs or sheets are open over a tab score right now.
 *
 * In a browser the score is a page element laid over the app's canvas, so it
 * draws on top of anything the app draws, dialogs included: a dialog opened
 * over the score is there but can't be seen. The browser's score view hides
 * itself while this is above zero. The other editions draw the score into
 * the app itself and can ignore it.
 */
object ScoreCover {
    var count by mutableIntStateOf(0)
        private set

    val covered: Boolean get() = count > 0

    /** Call from a dialog's content: counts it as covering the score while it is shown. */
    @Composable
    fun Covering() {
        DisposableEffect(Unit) {
            count++
            onDispose { count-- }
        }
    }
}
