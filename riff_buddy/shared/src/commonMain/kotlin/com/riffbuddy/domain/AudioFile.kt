package com.riffbuddy.domain

import kotlin.time.Instant

/**
 * A recording in the looper: a reference to a file on the player's device,
 * and what the looper remembers about playing it.
 */
data class AudioFile(
    val id: Long = 0,
    val songId: Long? = null,
    val name: String,
    val originalName: String,
    val uri: String,
    val sizeBytes: Long = 0,
    val durationMs: Long = 0,
    val bpm: Int? = null,
    val positionMs: Long = 0,
    val speedPercent: Int = 100,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val volumePercent: Int = 100,
    val createdAt: Instant = EPOCH,
    val updatedAt: Instant = EPOCH,
)

/**
 * A named loop on an audio file or a tab file. Start and end are in the
 * file's own units: milliseconds for audio, 1-based bars for tabs.
 */
data class SavedLoop(
    val id: Long = 0,
    val audioId: Long? = null,
    val tabId: Long? = null,
    val name: String,
    val start: Long,
    val end: Long,
    val speedPercent: Int = 100,
) {
    init {
        require((audioId == null) != (tabId == null)) { "a loop belongs to exactly one file" }
    }

    /** "1:21.0–1:26.5" for audio, "bars 5–8" for tabs. */
    val range: String
        get() = if (tabId != null) "bars $start–$end" else "${clock(start)}–${clock(end)}"

    companion object {
        fun clock(ms: Long): String {
            val tenths = ms / 100
            return "${tenths / 600}:${pad((tenths / 10) % 60)}.${tenths % 10}"
        }
    }
}
