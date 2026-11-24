@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.riffbuddy.tabs

import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.Settings
import alphaTab.midi.AlphaSynthMidiFileHandler
import alphaTab.midi.MidiFile
import alphaTab.midi.MidiFileGenerator
import alphaTab.model.Score

/** A tab file read by alphaTab's Kotlin build, for [TabParser]. */
internal fun readTab(bytes: ByteArray, fileName: String): TabSummary = TabScores.summarize(TabScores.load(bytes, fileName))

/** alphaTab scores from file bytes, for the parser and the player. */
object TabScores {

    /**
     * For scores this app wrote itself (backing tracks): the same
     * parse, without the trial MIDI pass. BackingTest runs every style through
     * that pass on the JVM, so doing it again on the device only doubled the
     * time from a style tap to hearing the new style.
     */
    internal fun loadTrusted(bytes: ByteArray, fileName: String): Score =
        if (fileName.endsWith(".alphatex", ignoreCase = true)) ScoreLoader.loadAlphaTex(bytes.decodeToString())
        else ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.toUByteArray()))

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
    internal fun load(bytes: ByteArray, fileName: String): Score {
        // alphaTex is text with no magic number, so the byte sniffers that
        // recognize the binary formats pass it by. It goes to its own reader.
        val score = loadTrusted(bytes, fileName)
        try {
            MidiFileGenerator(score, Settings(), AlphaSynthMidiFileHandler(MidiFile())).generate()
        } catch (e: Exception) {
            throw TabParser.unplayable(fileName, e)
        }
        return score
    }

    private fun noteCount(score: Score): Int {
        var notes = 0
        for (track in score.tracks) for (staff in track.staves) for (bar in staff.bars)
            for (voice in bar.voices) for (beat in voice.beats) notes += beat.notes.count()
        return notes
    }

    internal fun summarize(score: Score): TabSummary {
        // A plain loop, not .map: alphaTab's List has a map of its own
        // (a TypeScript Array.map, translated), and the two are ambiguous.
        val tracks = buildList {
            for (track in score.tracks) add(track.name.ifBlank { "Track ${track.index.toInt() + 1}" })
        }
        return TabParser.summary(score.title, score.artist, tracks, score.masterBars.count(), noteCount(score), score.tempo.toInt())
    }
}
