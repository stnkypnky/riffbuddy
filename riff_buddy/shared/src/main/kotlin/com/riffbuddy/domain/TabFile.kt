package com.riffbuddy.domain

import java.time.Instant

/**
 * An imported tab file, and what the player remembers about it.
 *
 * [name] is the player's to rename; [originalName] is what the file was called when it
 * arrived and is kept because the extension is the only format hint alphaTex
 * files carry.
 */
data class TabFile(
    val id: Long = 0,
    val songId: Long? = null,
    val name: String,
    val originalName: String,
    val storedName: String,
    val sizeBytes: Long,
    val sha256: String,
    val title: String = "",
    val artist: String = "",
    val trackCount: Int = 0,
    val barCount: Int = 0,
    val positionMs: Long = 0,
    val speedPercent: Int = 100,
    /** 1-based bar numbers, as a player counts them. Both null means no loop. */
    val loopStartBar: Int? = null,
    val loopEndBar: Int? = null,
    /** Tracks, shown track and view, as they were left. */
    val prefs: TabPrefs = TabPrefs(),
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
) {
    /** A line for the library list: what is in the file, not what it is called. */
    val contents: String
        get() = listOfNotNull(
            listOf(title, artist).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { null },
            if (trackCount == 1) "1 track" else "$trackCount tracks",
            "$barCount bars",
        ).joinToString(" · ")

    val format: String
        get() = when (originalName.substringAfterLast('.', "").lowercase()) {
            "gp3", "gp4", "gp5", "gpx", "gp" -> "Guitar Pro"
            "musicxml", "xml", "mxl" -> "MusicXML"
            "alphatex" -> "alphaTex"
            else -> "Tab"
        }

    companion object {
        /** Playback speed, in percent. */
        val SPEED_RANGE = 25..150
    }
}
