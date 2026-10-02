@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.riffbuddy.tabs

import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.Settings
import alphaTab.midi.AlphaSynthMidiFileHandler
import alphaTab.midi.MidiFile
import alphaTab.midi.MidiFileGenerator
import alphaTab.model.Score

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
 * Reads a tab file. Pure — alphaTab's importers run on the
 * JVM, so what counts as a valid file is decided by unit tests, not by trying
 * it on a phone.
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
        return runCatching { load(bytes, fileName) }
            .mapCatching { summarize(it) }
            .recoverCatching { cause ->
                if (cause is TabFileException) throw cause
                throw TabFileException(
                    "$fileName could not be read as a Guitar Pro, MusicXML or alphaTex file. " +
                        "It may be damaged, or saved in a format this app does not open.",
                    cause,
                )
            }
    }

    /**
     * The parsed score, for the player — and proof that it will *play*, not
     * just that it opened.
     *
     * Reading a file and turning it into sound are two separate passes in
     * alphaTab, and the second can fail on files the first accepts. The one
     * that showed this: MusicXML with string and fret marks but no tuning. It
     * parses into a staff with zero strings whose notes still claim a string,
     * and the sound pass then reads string −1 and throws — on the main thread,
     * inside `renderScore`, which took the whole app down.
     *
     * So the sound pass runs here too, off the main thread, and a file that
     * fails it is refused like any other bad file. It costs one extra pass
     * over the notes; the alternative was a crash.
     */
    /**
     * For scores this app wrote itself (backing tracks): the same
     * parse, without the trial MIDI pass. BackingTest runs every style through
     * that pass on the JVM, so doing it again on the device only doubled the
     * time from a style tap to hearing the new style.
     */
    internal fun loadTrusted(bytes: ByteArray, fileName: String): Score =
        if (fileName.endsWith(".alphatex", ignoreCase = true)) ScoreLoader.loadAlphaTex(bytes.decodeToString())
        else ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.toUByteArray()))

    internal fun load(bytes: ByteArray, fileName: String): Score {
        // alphaTex is text with no magic number, so the byte sniffers that
        // recognize the binary formats pass it by. It goes to its own reader.
        val score = if (fileName.endsWith(".alphatex", ignoreCase = true)) {
            ScoreLoader.loadAlphaTex(bytes.decodeToString())
        } else {
            ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.toUByteArray()))
        }
        try {
            MidiFileGenerator(score, Settings(), AlphaSynthMidiFileHandler(MidiFile())).generate()
        } catch (e: Exception) {
            throw TabFileException(
                "$fileName opens, but its notes cannot be turned into sound. This usually " +
                    "means tab fingerings without a guitar tuning — try exporting it again " +
                    "from the app that made it.",
                e,
            )
        }
        return score
    }

    private fun noteCount(score: Score): Int {
        var notes = 0
        for (track in score.tracks) for (staff in track.staves) for (bar in staff.bars)
            for (voice in bar.voices) for (beat in voice.beats) notes += beat.notes.count()
        return notes
    }

    private fun summarize(score: Score): TabSummary {
        // A plain loop, not .map: alphaTab's List has a map of its own
        // (a TypeScript Array.map, translated), and the two are ambiguous.
        val tracks = buildList {
            for (track in score.tracks) add(track.name.ifBlank { "Track ${track.index.toInt() + 1}" })
        }
        // Counting notes, not tracks or bars, because of how alphaTab fails. Fed
        // bytes it does not recognize, it does not always throw: its most
        // lenient reader hands back a perfectly shaped empty score — one track,
        // one bar, tempo 120, nothing in it. A check for "has tracks and bars"
        // passes that, and garbage would have been imported as a song. A file
        // with no notes is useless whichever way it came about.
        if (tracks.isEmpty() || score.masterBars.count() == 0 || noteCount(score) == 0) {
            throw TabFileException("This file opened, but there is no music in it.")
        }
        return TabSummary(
            title = score.title.trim(),
            artist = score.artist.trim(),
            tracks = tracks,
            bars = score.masterBars.count(),
            tempo = score.tempo.toInt(),
        )
    }
}

/** A file that is not a tab we can open. The message is written for the player to read. */
class TabFileException(message: String, cause: Throwable? = null) : Exception(message, cause)
