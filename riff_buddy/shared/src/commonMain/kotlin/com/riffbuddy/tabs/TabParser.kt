package com.riffbuddy.tabs

/**
 * What a tab file turned out to contain, in plain Kotlin types.
 *
 * The rest of the app never sees an alphaTab type. alphaTab's Kotlin is
 * machine-translated from TypeScript — its collections, its `Double` indexes,
 * its two experimental opt-ins — and keeping all of that inside `tabs/` means a
 * version bump or a replacement touches one package, not every screen.
 */
data class TabSummary(
    val title: String,
    val artist: String,
    val tracks: List<String>,
    val bars: Int,
    val tempo: Int,
)

/**
 * Reads a tab file. alphaTab does the reading on every edition, its Kotlin
 * build on a phone or computer and its JavaScript build in a browser, so what
 * counts as a valid file is decided by unit tests, not by trying it on a
 * phone. [readTab] is each edition's own.
 */
object TabParser {

    /**
     * The formats. The extension only decides whether the file picker's
     * answer is worth reading at all; the format itself is detected from the
     * bytes, since a `.xml` could be anything and a renamed `.gp5` is still a
     * `.gp5`.
     */
    val EXTENSIONS = setOf("gp3", "gp4", "gp5", "gpx", "gp", "musicxml", "xml", "mxl", "alphatex")

    fun isSupportedName(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /**
     * Parses [bytes] or explains why not.
     *
     * A failure is a Result, never a throw, because a corrupt file must show a
     * clear error and change nothing — and the only way to be sure
     * nothing changed is to learn the file is bad before anything is written.
     */
    fun parse(bytes: ByteArray, fileName: String): Result<TabSummary> {
        if (bytes.isEmpty()) return Result.failure(TabFileException("$fileName is empty."))
        return runCatching { readTab(bytes, fileName) }
            .recoverCatching { cause ->
                if (cause is TabFileException) throw cause
                throw TabFileException(
                    "$fileName could not be read as a Guitar Pro, MusicXML or alphaTex file. " +
                        "It may be damaged, or saved in a format this app does not open.",
                    cause,
                )
            }
    }

    /** What a file that opens but can't be turned into sound is told. */
    fun unplayable(fileName: String, cause: Throwable) = TabFileException(
        "$fileName opens, but its notes cannot be turned into sound. This usually " +
            "means tab fingerings without a guitar tuning — try exporting it again " +
            "from the app that made it.",
        cause,
    )

    /**
     * Counting notes, not tracks or bars, because of how alphaTab fails. Fed
     * bytes it does not recognize, it does not always throw: its most lenient
     * reader hands back a perfectly shaped empty score — one track, one bar,
     * tempo 120, nothing in it. A check for "has tracks and bars" passes that,
     * and garbage would have been imported as a song. A file with no notes is
     * useless whichever way it came about.
     */
    fun summary(title: String, artist: String, tracks: List<String>, bars: Int, notes: Int, tempo: Int): TabSummary {
        if (tracks.isEmpty() || bars == 0 || notes == 0) {
            throw TabFileException("This file opened, but there is no music in it.")
        }
        return TabSummary(title = title.trim(), artist = artist.trim(), tracks = tracks, bars = bars, tempo = tempo)
    }
}

/** A file that is not a tab we can open. The message is written for the player to read. */
class TabFileException(message: String, cause: Throwable? = null) : Exception(message, cause)
