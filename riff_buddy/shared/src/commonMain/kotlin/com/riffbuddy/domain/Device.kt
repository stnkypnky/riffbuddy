package com.riffbuddy.domain

import com.riffbuddy.platform.Volatile

/**
 * Words for the device the app runs on, where its text has to name it. A
 * phone is itself held up to the amp; a computer has a microphone somewhere
 * near it. The desktop edition sets [onComputer] before anything is shown,
 * and the web edition [inBrowser]: a browser can be on either.
 */
object Device {
    @Volatile var onComputer = false

    /** Running as a web page, which can't reach other devices or wake itself for a reminder. */
    @Volatile var inBrowser = false

    /** "phone" or "computer", as in "every phone has a small delay". */
    val noun: String get() = if (onComputer) "computer" else "phone"

    /** What gets moved toward the amp: the phone itself, or the computer's microphone. */
    val mic: String get() = if (onComputer) "the microphone" else "the phone"
}
